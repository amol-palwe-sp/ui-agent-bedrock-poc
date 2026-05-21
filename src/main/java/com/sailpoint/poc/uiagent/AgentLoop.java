package com.sailpoint.poc.uiagent;

import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Observe page (screenshot + indexed elements) → Bedrock → execute JSON actions → repeat.
 *
 * <p>Key capabilities:
 * <ul>
 *   <li>URL-change detection ends a batch immediately so the model sees a fresh element list.</li>
 *   <li>Loop detection: if the agent is stuck on the same URL for {@code noProgressLimit}
 *       consecutive steps, a warning hint is injected into history.</li>
 *   <li>History is kept compact: only failures and navigation events survive beyond the current
 *       page, successes are dropped.</li>
 *   <li>Action types: GOTO, CLICK, TYPE, SELECT_OPTION, KEYPRESS, SCROLL, HOVER, RELOAD_PAGE,
 *       CHECK, WAIT, DONE, TERMINATE.</li>
 *   <li>Every action outcome is written to a JSONL log via {@link ActionLogger}.</li>
 *   <li>Per-step and cumulative {@link TokenUsage} are printed after each LLM call.</li>
 * </ul>
 */
public final class AgentLoop {

    private static final String SYSTEM_PROMPT =
            """
            You are a browser automation planner. You receive:
            - A user goal in natural language.
            - The current page URL.
            - A numbered list of interactable elements (each with [id], tag, role, type, name,
              placeholder, current value, visible label, and — for <select> — its options).
              The id is stable for THIS observation only; it changes after the page navigates
              or re-renders.
            - A viewport screenshot of the page (when visual state may have changed). On
              new pages or after navigation you may receive MULTIPLE images — vertical tiles
              of the SAME page from top to bottom. Use them together to understand the full
              page layout (long forms, multi-section pages). Element ids in the list below
              are valid even when the corresponding element is in a lower tile.
            - A short feedback log of what the previous steps actually did: key successes
              (CLICK, TYPE, SELECT_OPTION, CHECK, HOVER), failures, and navigation events.
              Use this log to know which actions are already done — do NOT repeat them.

            Reply with a single JSON object ONLY (no markdown, no prose outside JSON):
            {
              "reasoning": "short, user-agnostic reasoning",
              "goal_achieved": false,
              "actions": [
                { "type": "GOTO",         "url": "https://..." },
                { "type": "CLICK",        "element_id": 0 },
                { "type": "TYPE",         "element_id": 1, "text": "literal text to type" },
                { "type": "SELECT_OPTION","element_id": 2, "label": "Alaska" },
                { "type": "KEYPRESS",     "key": "Enter" },
                { "type": "SCROLL",       "direction": "down", "amount": 600 },
                { "type": "HOVER",        "element_id": 3 },
                { "type": "CHECK",        "element_id": 4, "checked": true },
                { "type": "RELOAD_PAGE" },
                { "type": "WAIT",         "ms": 500 },
                { "type": "DONE" }
              ]
            }

            Rules:
            - Treat the entire "User goal" as the instruction; never collapse it to a summary.
            - Plan AT MOST 3 actions per response.
            - After any CLICK / GOTO / HOVER that may navigate or open a modal, STOP and wait
              for the next observation — do not chain TYPE on element ids from a previous DOM.
            - TYPE writes literal characters into an input/textarea/contenteditable field. The
              "text" field is exactly what to type — not a verb like "type" or "enter".
            - SELECT_OPTION targets <select> elements; prefer "label" matching visible option text.
            - KEYPRESS fires a named key (e.g. "Enter", "Escape", "Tab").
            - SCROLL when a target is below the viewport.
            - HOVER over an element to reveal hidden menus, tooltips, or dropdown triggers.
            - CHECK to toggle a checkbox; include "checked": true or false.
            - RELOAD_PAGE if the page is stuck (spinner, blank, stalled after form submission).
            - GOTO only when the goal requires a different URL than the current one.
            - Set goal_achieved to true and include { "type": "DONE" } when the goal is fully met.
            - If the goal cannot be achieved, set goal_achieved to true with
              { "type": "TERMINATE", "message": "why" }.
            - Read the previous-step feedback: if an action failed, adjust strategy for the
              next attempt (different element_id, HOVER to reveal first, RELOAD_PAGE if stuck).
            - Valid types: GOTO, CLICK, TYPE, SELECT_OPTION, KEYPRESS, SCROLL, HOVER, CHECK,
              RELOAD_PAGE, WAIT, DONE, TERMINATE.

            Action priority on long forms (CRITICAL — read carefully):
            1. ALWAYS prefer ACTING on a visible element over SCROLLING. Before emitting a
               SCROLL, scan the numbered elements list for the target by name / label /
               placeholder / option text. If you find a match, emit the corresponding
               TYPE / CLICK / SELECT_OPTION / CHECK action immediately on that element_id —
               do NOT scroll first to "verify visually". Element ids are valid even when
               the element is partially below the visible viewport.
            2. Submit / Create / Save / Confirm / Apply buttons on long forms are typically
               at the BOTTOM of the form, not the top. After all required fields are filled
               (per the feedback log), scroll DOWN to find the submit button. Do NOT scroll
               UP looking for it.
            3. Anti-oscillation: if your last 2 actions in the feedback log are both SCROLL
               and you have not acted on a field since, your next action MUST be one of:
                 (a) act on a visible element from the indexed list, OR
                 (b) continue scrolling in the SAME direction.
               Do NOT reverse scroll direction unless the screenshot clearly shows the
               target element ABOVE the current viewport.
            4. Trust the feedback log for completion. If "s_N: SELECT_OPTION ✓",
               "s_N: TYPE ✓", "s_N: CHECK ✓", or similar appears in the log for a field,
               that field is DONE — never re-verify by scrolling back to look at it.
               Move on to the next unfilled step in the goal.
            5. Dropdown discipline: when the goal says "select X from the Y dropdown" and
               an element with role/select matching Y appears in the elements list, issue
               SELECT_OPTION on that element immediately. Do not scroll past a visible
               dropdown looking for "a better one".
            """;

    private static final int MAX_ACTIONS_PER_BATCH  = 3;
    private static final int MAX_HISTORY_LINES      = 12;
    /**
     * Maximum times the same action+element_id pair may succeed before it is considered a loop.
     * The map is cleared on every page navigation so elements that re-appear on a fresh page are
     * not accidentally blocked.
     */
    private static final int MAX_SAME_ACTION_REPEATS = 2;

    /**
     * Action types excluded from the repeat-loop guard entirely.
     *
     * <p>SCROLL, WAIT, and RELOAD_PAGE all have no meaningful {@code element_id} (they always
     * map to {@code -1}), so the guard key is identical for every invocation regardless of
     * direction, amount, or purpose.  Blocking them after two occurrences incorrectly kills
     * legitimate long-form workflows that require multiple scrolls or waits.
     *
     * <p>URL-level progress tracking ({@code noProgressStreak}) still catches agents that are
     * genuinely stuck scrolling or waiting forever on the same page.
     */
    private static final java.util.Set<String> LOOP_GUARD_EXCLUDED_TYPES =
            java.util.Set.of("SCROLL", "WAIT", "RELOAD_PAGE");

    private final BedrockAnthropicClient bedrock;
    private final BrowserSession         browser;
    private final ActionLogger           actionLogger;
    private final int                    maxSteps;
    private final String                 userGoal;
    private final int                    noProgressLimit;

    /**
     * Number of viewport-tiled screenshots to capture on page-shape-changing observations
     * (INIT / GOTO / RELOAD_PAGE).  Default {@code 1} = current single-viewport behaviour.
     * Callers opt in via {@link #setMultiViewportMaxFrames(int)}.
     */
    private int multiViewportMaxFrames = 1;

    public AgentLoop(
            BedrockAnthropicClient bedrock,
            BrowserSession browser,
            ActionLogger actionLogger,
            int maxSteps,
            String userGoal,
            int noProgressLimit) {
        this.bedrock          = bedrock;
        this.browser          = browser;
        this.actionLogger     = actionLogger;
        this.maxSteps         = maxSteps;
        this.userGoal         = userGoal;
        this.noProgressLimit  = noProgressLimit > 0 ? noProgressLimit : 3;
    }

    /**
     * Enables multi-viewport screenshot tiling on page-shape-changing observations so the LLM
     * can see a tall page (long forms) in one observation.  Pass {@code 0} or {@code 1} to keep
     * the default single-viewport behaviour.
     */
    public AgentLoop setMultiViewportMaxFrames(int maxFrames) {
        this.multiViewportMaxFrames = Math.max(1, maxFrames);
        return this;
    }

    public TokenUsage run() throws Exception {
        List<String>        history          = new ArrayList<>();
        TokenUsage          totalUsage       = TokenUsage.ZERO;
        String              lastActionType   = "INIT"; // forces screenshot on first step
        Map<String, Integer> actionRepeatCounts = new HashMap<>(); // Bug 1: per-run repeat tracking

        // URL-level loop-detection state
        int    noProgressStreak = 0;
        String lastObservedUrl  = "";

        for (int step = 0; step < maxSteps; step++) {
            JSONArray elements    = browser.listInteractables();
            String    elementText = browser.formatElementsForPrompt(elements);

            // Decide screenshot strategy.
            //   - PAGE_SHAPE_CHANGED (INIT / GOTO / RELOAD_PAGE) → multi-viewport tiles
            //     (when enabled) so the LLM sees the whole tall page at once.
            //   - Other visual-changing actions (CLICK, TYPE, SCROLL, …) → single viewport;
            //     fast and cheap, the page shape hasn't changed.
            //   - WAIT and similar → no screenshot at all.
            List<byte[]> screenshots = java.util.Collections.emptyList();
            if (shouldTakeScreenshot(lastActionType)) {
                if (multiViewportMaxFrames > 1 && isPageShapeChange(lastActionType)) {
                    screenshots = browser.viewportScrollScreenshotsJpeg(multiViewportMaxFrames, 70);
                    int totalKb = 0;
                    for (byte[] s : screenshots) totalKb += s.length;
                    System.out.printf("  [Screenshot] %d viewport tiles (%d KB total)%n",
                            screenshots.size(), totalKb / 1024);
                } else {
                    byte[] single = browser.viewportScreenshotJpeg(70);
                    if (single.length > 0) screenshots = java.util.List.of(single);
                    System.out.printf("  [Screenshot] JPEG taken (%d KB)%n", single.length / 1024);
                }
            } else {
                System.out.println("  [Screenshot] Skipped (last action=" + lastActionType + ")");
            }

            boolean multiViewport = screenshots.size() > 1;
            String userMessage = buildUserMessage(step, elementText, history, multiViewport, screenshots.size());

            System.out.println("--- Step " + (step + 1) + " / " + maxSteps + " ---");
            System.out.println("URL: " + browser.currentUrl());
            System.out.println("Indexed elements: " + elements.length());

            InvokeResult invokeResult;
            if (multiViewport) {
                invokeResult = bedrock.invokeWithMultipleImages(SYSTEM_PROMPT, userMessage, screenshots);
            } else {
                byte[] single = screenshots.isEmpty() ? new byte[0] : screenshots.get(0);
                invokeResult = bedrock.invokeWithVision(SYSTEM_PROMPT, userMessage, single);
            }

            TokenUsage stepUsage = invokeResult.usage();
            totalUsage = totalUsage.add(stepUsage);
            System.out.println("  [Token Usage] Step " + (step + 1) + ": " + stepUsage);

            String raw = invokeResult.text();
            JSONObject plan;
            try {
                plan = BedrockAnthropicClient.parseModelJson(raw);
            } catch (JSONException ex) {
                String preview = raw.length() > 800 ? raw.substring(0, 800) + "…" : raw;
                System.err.println("Model returned non-JSON (skipping step). Preview:\n" + preview);
                addHistory(history, "parse error — reply with a single JSON object only");
                continue;
            }

            String reasoning = plan.optString("reasoning", "");
            System.out.println("  Model reasoning: " + reasoning);

            boolean    goalAchieved = plan.optBoolean("goal_achieved", false);
            JSONArray  actions      = plan.optJSONArray("actions");
            if (actions == null || actions.isEmpty()) {
                addHistory(history, "s" + step + ": no actions returned; reasoning=" + reasoning);
                continue;
            }

            BatchOutcome outcome = executeActions(actions, history, step, actionRepeatCounts);
            lastActionType = getLastActionType(actions);

            // --- URL-level loop detection ---
            String currentUrl = browser.currentUrl();
            boolean urlChanged = !currentUrl.equals(lastObservedUrl) && !lastObservedUrl.isEmpty();
            boolean anySuccess = outcome.hadSuccess();
            // Clear per-element repeat counts when the page changes so new-page elements aren't blocked.
            if (urlChanged) actionRepeatCounts.clear();

            if (urlChanged || anySuccess) {
                noProgressStreak = 0;
            } else {
                noProgressStreak++;
                if (noProgressStreak >= noProgressLimit) {
                    String stuckHint = "s" + step + ": stuck on same URL for "
                            + noProgressStreak
                            + " steps — consider RELOAD_PAGE, HOVER to reveal elements,"
                            + " SCROLL down, or TERMINATE if truly blocked";
                    System.out.println("  [Loop Detection] " + stuckHint);
                    addHistory(history, stuckHint);
                }
            }
            lastObservedUrl = currentUrl;

            if (outcome.stop() || goalAchieved) {
                System.out.println("Stopped after goal achieved or terminal action.");
                printTotalUsage(totalUsage);
                return totalUsage;
            }
        }

        System.out.println("Max steps reached without explicit DONE.");
        printTotalUsage(totalUsage);
        return totalUsage;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean shouldTakeScreenshot(String lastAction) {
        if (lastAction == null) return true;
        return switch (lastAction.toUpperCase()) {
            // These change visual state → always take a fresh screenshot.
            case "INIT", "GOTO", "CLICK", "SELECT_OPTION", "KEYPRESS",
                 "HOVER", "CHECK", "RELOAD_PAGE",
                 // Bug 3 fix: TYPE changes the field value visually; without a screenshot the
                 // LLM has no confirmation the text landed and will repeat the TYPE next step.
                 "TYPE",
                 // Scroll fix: on long forms, scrolling reveals entirely different sections of
                 // the page. Without a screenshot after scroll the agent is blind to what is
                 // now visible and falls back to stale history, causing ↑↓ oscillation loops.
                 "SCROLL" -> true;
            // WAIT genuinely does not change visual state — keep it skipped.
            case "WAIT" -> false;
            default -> true; // unknown — safe to screenshot
        };
    }

    /**
     * True when the last action almost certainly produced an entirely new page layout
     * (initial load, navigation, or full reload).  These are the only actions where
     * paying for multi-viewport screenshot tiles is worth it; CLICK / TYPE / SCROLL stay
     * cheap with a single viewport tile.
     */
    private static boolean isPageShapeChange(String lastAction) {
        if (lastAction == null) return true;
        return switch (lastAction.toUpperCase()) {
            case "INIT", "GOTO", "RELOAD_PAGE" -> true;
            default -> false;
        };
    }

    private static String getLastActionType(JSONArray actions) {
        if (actions == null || actions.isEmpty()) return "UNKNOWN";
        int lastIdx = Math.min(actions.length(), MAX_ACTIONS_PER_BATCH) - 1;
        return actions.getJSONObject(lastIdx).optString("type", "UNKNOWN").toUpperCase();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

//    private static boolean shouldTakeScreenshot(String lastAction) {
//        if (lastAction == null) return true;
//        return switch (lastAction.toUpperCase()) {
//            // These change visual state → always take a fresh screenshot.
//            case "INIT", "GOTO", "CLICK", "SELECT_OPTION", "KEYPRESS",
//                 "HOVER", "CHECK", "RELOAD_PAGE",
//                 // Bug 3 fix: TYPE changes the field value visually; without a screenshot the
//                 // LLM has no confirmation the text landed and will repeat the TYPE next step.
//                 "TYPE" -> true;
//            // Pure positional / temporal actions — no meaningful visual diff.
//            case "SCROLL", "WAIT" -> false;
//            default -> true; // unknown — safe to screenshot
//        };
//    }

//    private static String getLastActionType(JSONArray actions) {
//        if (actions == null || actions.isEmpty()) return "UNKNOWN";
//        int lastIdx = Math.min(actions.length(), MAX_ACTIONS_PER_BATCH) - 1;
//        return actions.getJSONObject(lastIdx).optString("type", "UNKNOWN").toUpperCase();
//    }

    private static void printTotalUsage(TokenUsage total) {
        System.out.println("\n========== TOTAL TOKEN USAGE ==========");
        System.out.println(total);
        System.out.println("========================================");
    }

    private String buildUserMessage(int step, String elementText, List<String> history,
                                    boolean multiViewport, int screenshotCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("User goal:\n").append(userGoal).append("\n\n");
        sb.append("Current URL:\n").append(browser.currentUrl()).append("\n\n");
        if (multiViewport) {
            sb.append("Screenshots (").append(screenshotCount).append(" tiles):\n")
              .append("The attached images are vertical slices of the SAME page from top to bottom.\n")
              .append("Image 1 = top of page, image ").append(screenshotCount)
              .append(" = bottom. Use them together to understand the full page layout — locate\n")
              .append("the field, dropdown, or submit button you need, then act on the matching\n")
              .append("element_id from the list below (element ids are valid even when an element\n")
              .append("appears in a lower tile that is currently below the visible viewport).\n\n");
        }
        sb.append("Interactable elements (use element_id matching [n]):\n").append(elementText).append('\n');
        if (!history.isEmpty()) {
            sb.append("Previous step feedback (do NOT repeat actions marked ✓):\n");
            for (String h : history) sb.append("- ").append(h).append('\n');
        }
        sb.append("\nReturn the next JSON plan for step ").append(step).append('.');
        return sb.toString();
    }

    /** Action types whose successes are worth recording so the LLM doesn't repeat them. */
    private static final java.util.Set<String> HISTORY_WORTHY_TYPES = java.util.Set.of(
            "CLICK", "TYPE", "SELECT_OPTION", "CHECK", "HOVER", "KEYPRESS",
            "SCROLL");  // scroll history lets the LLM self-detect ↑↓ oscillation patterns

    private BatchOutcome executeActions(
            JSONArray actions,
            List<String> history,
            int step,
            Map<String, Integer> actionRepeatCounts)
            throws InterruptedException {

        String  urlBefore  = browser.currentUrl();
        int     limit      = Math.min(actions.length(), MAX_ACTIONS_PER_BATCH);
        boolean hadSuccess = false;

        if (actions.length() > MAX_ACTIONS_PER_BATCH) {
            System.out.println("Batch trimmed from " + actions.length()
                    + " to " + MAX_ACTIONS_PER_BATCH + " actions.");
        }

        for (int i = 0; i < limit; i++) {
            JSONObject a    = actions.getJSONObject(i);
            String     type = a.optString("type", "").toUpperCase();

            if ("DONE".equals(type)) {
                System.out.println("ACTION DONE");
                actionLogger.log(step, i, "DONE",
                        new JSONObject().put("ok", true).put("strategy", "done"),
                        browser.currentUrl(), 0);
                addHistory(history, "s" + step + ": DONE ✓");
                return new BatchOutcome(true, hadSuccess);
            }
            if ("TERMINATE".equals(type)) {
                String message = a.optString("message", "");
                System.out.println("ACTION TERMINATE: " + message);
                actionLogger.log(step, i, "TERMINATE",
                        new JSONObject().put("ok", true).put("strategy", "terminate"),
                        browser.currentUrl(), 0);
                addHistory(history, "s" + step + ": TERMINATE: " + message);
                return new BatchOutcome(true, hadSuccess);
            }

            // Bug 1: Per-element repeat-loop guard.
            // If the same action+element succeeds more than MAX_SAME_ACTION_REPEATS times
            // across the entire run, the agent is in a loop. Inject a history hint and stop.
            int elementId = a.optInt("element_id", -1);
            String actionKey = type + ":" + elementId;
            int priorRepeats = actionRepeatCounts.getOrDefault(actionKey, 0);
            if (!LOOP_GUARD_EXCLUDED_TYPES.contains(type) && priorRepeats >= MAX_SAME_ACTION_REPEATS) {
                String loopMsg = "s" + step + ": LOOP DETECTED — " + type
                        + (elementId >= 0 ? " id=" + elementId : "")
                        + " already done " + priorRepeats + "x — move on to the next step";
                System.out.println("  [Loop Guard] " + loopMsg);
                addHistory(history, loopMsg);
                break;
            }

            long       t0     = System.currentTimeMillis();
            JSONObject result;
            try {
                result = dispatch(type, a);
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Throwable ex) {
                String msg = ex.getClass().getSimpleName() + ": "
                        + (ex.getMessage() == null ? "(no message)" : firstLine(ex.getMessage()));
                System.err.println("Action " + type + " threw: " + msg);
                result = new JSONObject().put("ok", false).put("err", msg);
            }
            long elapsedMs = System.currentTimeMillis() - t0;

            System.out.println("  result: " + result);
            actionLogger.log(step, i, type, elementId, result, browser.currentUrl(), elapsedMs);

            boolean ok = result.optBoolean("ok", false);
            if (ok) {
                hadSuccess = true;
                // Increment repeat counter only on success — failed actions shouldn't count.
                // SCROLL, WAIT, and RELOAD_PAGE are excluded: they have no meaningful element_id
                // so their key is always the same, causing false loop detection on long forms.
                if (!LOOP_GUARD_EXCLUDED_TYPES.contains(type)) {
                    actionRepeatCounts.put(actionKey, priorRepeats + 1);
                }

                // Bug 4: Log key successes so the LLM knows they're already done.
                if (HISTORY_WORTHY_TYPES.contains(type)) {
                    addHistory(history, "s" + step + ": " + summarize(result, a));
                }
            } else {
                // Always log failures so the LLM can adjust strategy.
                addHistory(history, "s" + step + ": " + summarize(result, a));
            }

            // URL-change detection: end batch and collapse history.
            String urlAfter = browser.currentUrl();
            if (!urlAfter.equals(urlBefore)) {
                System.out.println("URL changed (" + urlBefore + " → " + urlAfter
                        + "). Ending batch to re-observe.");
                collapseHistoryOnNavigation(history, urlBefore, urlAfter, step);
                break;
            }

            if (!ok) {
                System.out.println("Action failed; ending batch to re-observe.");
                break;
            }
        }
        return new BatchOutcome(false, hadSuccess);
    }

    private JSONObject dispatch(String type, JSONObject a) throws InterruptedException {
        return switch (type) {
            case "GOTO" -> {
                String url = a.optString("url", "");
                if (url.isBlank()) yield fail("missing url");
                System.out.println("ACTION GOTO " + url);
                browser.navigate(url);
                yield ok("goto");
            }
            case "CLICK" -> {
                int id = a.optInt("element_id", -1);
                System.out.println("ACTION CLICK element_id=" + id);
                yield browser.clickByElementId(id);
            }
            case "TYPE" -> {
                int    id   = a.optInt("element_id", -1);
                String text = a.optString("text", "");
                System.out.println("ACTION TYPE element_id=" + id
                        + " text=\"" + previewText(text) + "\"");
                yield browser.typeByElementId(id, text);
            }
            case "SELECT_OPTION" -> {
                int    id    = a.optInt("element_id", -1);
                String value = a.optString("value", "");
                String label = a.optString("label", "");
                System.out.println("ACTION SELECT_OPTION element_id=" + id
                        + " label=\"" + label + "\" value=\"" + value + "\"");
                yield browser.selectOptionByElementId(id, value, label);
            }
            case "KEYPRESS" -> {
                String key = a.optString("key", "");
                System.out.println("ACTION KEYPRESS " + key);
                yield browser.keypress(key);
            }
            case "SCROLL" -> {
                String dir    = a.optString("direction", "down");
                int    amount = a.optInt("amount", 600);
                System.out.println("ACTION SCROLL " + dir + " " + amount);
                yield browser.scroll(dir, amount);
            }
            case "HOVER" -> {
                int id = a.optInt("element_id", -1);
                System.out.println("ACTION HOVER element_id=" + id);
                yield browser.hoverByElementId(id);
            }
            case "CHECK" -> {
                int     id      = a.optInt("element_id", -1);
                boolean checked = a.optBoolean("checked", true);
                System.out.println("ACTION CHECK element_id=" + id + " checked=" + checked);
                yield browser.checkboxByElementId(id, checked);
            }
            case "RELOAD_PAGE" -> {
                System.out.println("ACTION RELOAD_PAGE");
                yield browser.reloadPage();
            }
            case "WAIT" -> {
                int ms = Math.min(10_000, Math.max(0, a.optInt("ms", 500)));
                System.out.println("ACTION WAIT " + ms + "ms");
                Thread.sleep(ms);
                yield ok("wait").put("ms", ms);
            }
            default -> {
                System.out.println("Unknown action type: " + type);
                yield fail("unknown action type: " + type);
            }
        };
    }

    // -------------------------------------------------------------------------
    // History management
    // -------------------------------------------------------------------------

    /**
     * Collapses fine-grained per-action history from the old page into a single navigation summary
     * line, keeping only failures (entries containing "✗"). The summary is prepended so it is
     * always kept even when history is trimmed.
     */
    private static void collapseHistoryOnNavigation(
            List<String> history, String fromUrl, String toUrl, int step) {
        // Remove all entries that are not failures (no "✗") and not pre-existing summaries.
        history.removeIf(entry -> !entry.contains("✗") && !entry.contains("✓ Login")
                && !entry.contains("✓ Navigated"));
        // Prepend a single compact transition line.
        String summary = detectPageTransition(fromUrl, toUrl, step);
        history.add(0, summary);
        // Enforce limit after insertion.
        while (history.size() > MAX_HISTORY_LINES) history.remove(history.size() - 1);
    }

    private static String detectPageTransition(String fromUrl, String toUrl, int step) {
        if (fromUrl == null || toUrl == null) return "s" + step + ": page changed";
        String from = fromUrl.toLowerCase();
        String to   = toUrl.toLowerCase();
        if ((from.contains("signin") || from.contains("login"))
                && !to.contains("signin") && !to.contains("login")) {
            return "s" + step + ": ✓ Login completed";
        }
        if (to.contains("challenge/pwd") || to.contains("challenge/password")) {
            return "s" + step + ": ✓ Email entered, on password page";
        }
        return "s" + step + ": ✓ Navigated to " + trimUrl(toUrl, 50);
    }

    private static String trimUrl(String url, int max) {
        if (url == null) return "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = (uri.getHost() != null ? uri.getHost() : "") + uri.getPath();
            return path.length() <= max ? path : path.substring(0, max) + "…";
        } catch (Exception e) {
            return url.length() <= max ? url : url.substring(0, max) + "…";
        }
    }

    private static String summarize(JSONObject result, JSONObject action) {
        String type = action.optString("type", "").toUpperCase();
        if (result.optBoolean("ok", false)) {
            String idPart = action.has("element_id")
                    ? " id=" + action.optInt("element_id") : "";
            String extra = switch (type) {
                case "GOTO"    -> " " + trimUrl(action.optString("url"), 40);
                case "TYPE"    -> idPart + " \"" + previewText(action.optString("text"), 20) + "\"";
                case "KEYPRESS"-> " " + action.optString("key");
                case "WAIT"    -> " " + action.optInt("ms") + "ms";
                case "SCROLL"  -> " " + action.optString("direction");
                case "CHECK"   -> idPart + " checked=" + action.optBoolean("checked", true);
                default        -> idPart;
            };
            return type + extra + " ✓";
        }
        String idPart = action.has("element_id") ? " id=" + action.optInt("element_id") : "";
        String err    = firstLine(result.optString("err", "failed"));
        return type + idPart + " ✗ " + err;
    }

    private static void addHistory(List<String> history, String entry) {
        history.add(entry);
        while (history.size() > MAX_HISTORY_LINES) history.remove(0);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        return head.length() > 200 ? head.substring(0, 200) + "…" : head;
    }

    private static String previewText(String text) {
        return previewText(text, 60);
    }

    private static String previewText(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static JSONObject ok(String strategy) {
        return new JSONObject().put("ok", true).put("strategy", strategy);
    }

    private static JSONObject fail(String message) {
        return new JSONObject().put("ok", false).put("err", message);
    }

    private record BatchOutcome(boolean stop, boolean hadSuccess) {}
}
