package com.sailpoint.poc.uiagent.replay;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sailpoint.poc.uiagent.config.ReplayConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a recorded {@link Script} with multi-level element fallback (REQ-RR-4, REQ-SIV).
 */
public final class ScriptExecutor {

    private static final int MISS_WARN_THRESHOLD = 3;
    private static final double HIGH_MISS_RATE = 0.30;

    /** Bounded wait for an element to render before giving up on a step (recorded WAIT steps are
     *  not replayed, so this absorbs post-action render latency such as a confirmation dialog). */
    private static final long ELEMENT_APPEAR_TIMEOUT_MS = 4_000;
    private static final int ELEMENT_APPEAR_POLL_MS = 500;

    private static final String SCROLL_INTO_VIEW_JS = """
            (selector) => {
              const el = document.querySelector(selector);
              if (!el) return JSON.stringify({ ok: false, notInDom: true });
              const rectBefore = el.getBoundingClientRect();
              const foldPx = Math.max(0, rectBefore.top - window.innerHeight);
              el.scrollIntoView({ behavior: 'instant', block: 'center' });
              return JSON.stringify({
                ok: true,
                topBefore: rectBefore.top,
                foldPx: foldPx,
                viewportHeight: window.innerHeight
              });
            }
            """;

    private final BrowserSession browser;
    private final BedrockAnthropicClient bedrock;
    private final boolean selfHeal;
    private final ReplayConfig replay;

    public ScriptExecutor(
            BrowserSession browser,
            BedrockAnthropicClient bedrock,
            boolean selfHeal,
            ReplayConfig replay) {
        this.browser = browser;
        this.bedrock = bedrock;
        this.selfHeal = selfHeal;
        this.replay = replay != null ? replay : defaultReplayConfig();
    }

    private static ReplayConfig defaultReplayConfig() {
        return new ReplayConfig(300, 400, 15, 250);
    }

    public ReplayResult replay(Script script, TokenValues tokens) throws Exception {
        validatePreReplay(script, tokens);

        int total = script.steps().size();
        int succeeded = 0;
        int failed = 0;
        int claudeCalls = 0;
        boolean goalVerifiedComplete = false;
        TokenUsage cost = TokenUsage.ZERO;
        ReplayResult.Builder result = ReplayResult.builder().stepsTotal(total);

        System.out.println("Replaying: " + script.scriptName());
        System.out.printf("Steps: %d | Health: %.0f%% | Last run: %s%n",
                total, script.healthScore() * 100, script.lastRunAt());

        browser.navigate(script.startUrl());

        for (ScriptStep step : script.steps()) {
            int n = step.stepIndex() + 1;
            logStepStart(n, total, step);
            validateUrl(step);

            StepOutcome outcome = executeStep(step, tokens);
            if (outcome.usage != null) {
                cost = cost.add(outcome.usage);
            }
            if (outcome.claudeUsed) {
                claudeCalls++;
            }

            if (outcome.success) {
                succeeded++;
                result.addStrategy(outcome.strategy);
                logStepSuccess(step, outcome);
                if (selfHeal && outcome.updatedStep != null) {
                    updateStepInScript(script, outcome.updatedStep);
                }
            } else {
                logStepFailure(outcome);

                // Generic, system-agnostic salvage before aborting the whole run: a recorded step
                // (often a trailing confirmation like a "DONE"/"Copy password" dialog) may fail to
                // relocate even though the task itself already succeeded. Rather than hardcode which
                // steps are optional per system, we JUDGE FROM THE SCREEN via vision — a success/
                // error toast or the expected end state — and only finish early when the goal is
                // genuinely complete. If not, we abort as before.
                VerifyResult vr = verifyCompletion(script, tokens);
                if (vr.attempted) {
                    claudeCalls++;
                    if (vr.usage != null) {
                        cost = cost.add(vr.usage);
                    }
                }
                if (vr.complete) {
                    System.out.printf(
                            "  → Goal verified complete via screen despite step miss (%s \"%s\"): %s%n",
                            step.action(), step.elementLabel(), vr.reason);
                    goalVerifiedComplete = true;
                    break;
                }

                failed++;
                result.addFailed(step.stepIndex(), outcome.error);
                result.success(false);
                break;
            }
        }

        if (goalVerifiedComplete) {
            System.out.println("Replay finished early: goal confirmed complete from on-screen state.");
        }

        script.incrementRunCount();
        script.setSuccessRate((double) succeeded / Math.max(1, total));
        if (selfHeal && script.sourcePath() != null) {
            script.saveInPlace();
        }

        printSummary(script, succeeded, failed, result, claudeCalls, cost);
        checkMissWarnings(script);

        return result
                .stepsSucceeded(succeeded)
                .stepsFailed(failed)
                .claudeCalls(claudeCalls)
                .totalCost(cost)
                .build();
    }

    private StepOutcome executeStep(ScriptStep step, TokenValues tokens) throws Exception {
        String action = step.action().toUpperCase();

        return switch (action) {
            case "GOTO" -> {
                browser.navigate(step.url() != null ? step.url() : step.expectedUrlPattern());
                yield StepOutcome.ok(ReplayStrategy.FINGERPRINT);
            }
            case "CLICK" -> executeElementStep(step, tokens, (id) -> browser.clickByStableId(id));
            case "TYPE" -> {
                String text = tokens.substitute(step.text() != null ? step.text() : "");
                yield executeElementStep(step, tokens,
                        (id) -> browser.typeByStableId(id, text));
            }
            case "SELECT_OPTION" -> executeElementStep(step, tokens,
                    (id) -> browser.selectOptionByStableId(id, "", step.label()));
            case "HOVER" -> executeElementStep(step, tokens, (id) -> browser.hoverByStableId(id));
            case "CHECK" -> executeElementStep(step, tokens,
                    (id) -> browser.checkboxByStableId(id, step.checked() != null && step.checked()));
            case "KEYPRESS" -> {
                JSONObject r = browser.keypress(step.key());
                yield r.optBoolean("ok") ? StepOutcome.ok(ReplayStrategy.FINGERPRINT)
                        : StepOutcome.fail(r.optString("err", "keypress failed"));
            }
            case "SCROLL" -> {
                JSONObject r = browser.scroll(
                        step.direction() != null ? step.direction() : "down",
                        step.amount() != null ? step.amount() : 600);
                yield r.optBoolean("ok") ? StepOutcome.ok(ReplayStrategy.FINGERPRINT)
                        : StepOutcome.fail(r.optString("err", "scroll failed"));
            }
            case "RELOAD_PAGE" -> {
                JSONObject r = browser.reloadPage();
                yield r.optBoolean("ok") ? StepOutcome.ok(ReplayStrategy.FINGERPRINT)
                        : StepOutcome.fail(r.optString("err", "reload failed"));
            }
            default -> StepOutcome.fail("Unsupported action: " + action);
        };
    }

    @FunctionalInterface
    private interface ElementAction {
        JSONObject run(String stableId) throws Exception;
    }

    private StepOutcome executeElementStep(ScriptStep step, TokenValues tokens, ElementAction action)
            throws Exception {
        String stableId = step.stableId();
        if (stableId == null || stableId.isBlank()) {
            return StepOutcome.fail("missing stableId");
        }

        // Level 1 — FINGERPRINT
        if (browser.countByStableId(stableId) > 0) {
            JSONObject r = action.run(stableId);
            if (r.optBoolean("ok")) {
                return StepOutcome.ok(ReplayStrategy.FINGERPRINT);
            }
        }

        // Level 2 — RETAG (viewport only)
        browser.listInteractables(true);
        if (browser.countByStableId(stableId) > 0) {
            JSONObject r = action.run(stableId);
            if (r.optBoolean("ok")) {
                return StepOutcome.ok(ReplayStrategy.RETAG);
            }
        }

        // Level 2.5 — WAIT FOR APPEARANCE (full-DOM retag with bounded polling).
        // Recorded WAIT steps are intentionally not replayed, so an element that renders a beat
        // late (e.g. a "Copy password"/DONE dialog that only appears after the submit settles)
        // would otherwise be declared missing on the very first probe. We poll the full DOM: if
        // the element is already present but off-screen it resolves on the first pass (Playwright
        // auto-scrolls on click/type); if it is a late-rendering node we retry until it shows or
        // the budget expires, then fall through to the scroll/fuzzy/LLM levels.
        StepOutcome appeared = waitForAppearance(stableId, action);
        if (appeared != null) {
            return appeared;
        }

        // Level 2.7 — STRUCTURAL_HASH remap. Viewport-independent, LLM-free heal for the common
        // case where the recorded fingerprint/id churned (e.g. text-only buttons like DONE whose
        // section label shifted) but the element's structural identity is unchanged. Only trusted
        // on a UNIQUE match — 0 or >1 matches hand off to the next level (Skyvern-style).
        StepOutcome structural = tryStructuralHash(step, action);
        if (structural != null) {
            return structural;
        }

        // Level 3 — SCROLL_INTO_VIEW
        StepOutcome scroll = tryScrollIntoView(step, action);
        if (scroll != null) {
            return scroll;
        }

        // Level 4 — PROGRESSIVE_SCROLL
        StepOutcome progressive = tryProgressiveScroll(step, action);
        if (progressive != null) {
            return progressive;
        }

        // Level 5 — FUZZY (full DOM)
        JSONArray elements = browser.listInteractables(false);
        FingerprintMatcher.MatchResult fuzzy =
                FingerprintMatcher.findByFuzzy(elements, step.fingerprintString());
        if (fuzzy != null) {
            JSONObject r = action.run(fuzzy.stableId());
            if (r.optBoolean("ok")) {
                ScriptStep updated = step.withUpdatedFingerprint(
                        fuzzy.stableId(),
                        fuzzy.element().optString("fingerprintString"),
                        fuzzy.element().optInt("fingerprintLevel"),
                        jsonFallbacks(fuzzy.element()),
                        ReplayStrategy.FUZZY);
                return StepOutcome.ok(ReplayStrategy.FUZZY, updated);
            }
        }

        // Level 6 — CLAUDE
        StepOutcome claude = claudeRecover(step, action);
        if (claude.success) {
            return claude;
        }

        return StepOutcome.fail("all replay strategies failed for " + step.elementLabel());
    }

    /**
     * Polls the full DOM for {@code stableId} until it appears (and the action succeeds) or the
     * {@link #ELEMENT_APPEAR_TIMEOUT_MS} budget expires. Returns a successful {@link StepOutcome}
     * (reported as {@link ReplayStrategy#RETAG}) or {@code null} to let the caller continue down
     * the fallback ladder. Only genuinely-missing elements incur the wait — elements already in
     * the DOM resolve on the first poll.
     */
    private StepOutcome waitForAppearance(String stableId, ElementAction action) throws Exception {
        long deadline = System.currentTimeMillis() + ELEMENT_APPEAR_TIMEOUT_MS;
        boolean warned = false;
        while (true) {
            browser.listInteractables(false);
            if (browser.countByStableId(stableId) > 0) {
                JSONObject r = action.run(stableId);
                if (r.optBoolean("ok")) {
                    if (warned) {
                        System.out.println("  → Element rendered after wait — retagged, found");
                    }
                    return StepOutcome.ok(ReplayStrategy.RETAG);
                }
                return null; // in DOM but action failed — hand off to remaining levels
            }
            if (System.currentTimeMillis() >= deadline) {
                return null;
            }
            if (!warned) {
                System.out.println("  → Element not in DOM yet — waiting for it to render...");
                warned = true;
            }
            sleepMs(ELEMENT_APPEAR_POLL_MS);
        }
    }

    /**
     * Remaps the step to a live element by its viewport-independent {@code structuralHash}. Returns
     * a successful outcome (re-fingerprinting the step) only when exactly one live element matches;
     * {@code null} on no/ambiguous match or action failure so the caller continues the ladder.
     */
    private StepOutcome tryStructuralHash(ScriptStep step, ElementAction action) throws Exception {
        String wanted = step.structuralHash();
        if (wanted == null || wanted.isBlank()) {
            return null;
        }
        JSONArray elements = browser.listInteractables(false);
        String matchId = null;
        JSONObject matchEl = null;
        int matches = 0;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.getJSONObject(i);
            if (wanted.equals(el.optString("structuralHash"))) {
                matches++;
                matchId = el.optString("id");
                matchEl = el;
            }
        }
        if (matches != 1 || matchId == null || matchId.isBlank()) {
            return null;
        }
        JSONObject r = action.run(matchId);
        if (r.optBoolean("ok")) {
            ScriptStep updated = step.withUpdatedFingerprint(
                    matchId,
                    matchEl.optString("fingerprintString"),
                    matchEl.optInt("fingerprintLevel"),
                    jsonFallbacks(matchEl),
                    ReplayStrategy.STRUCTURAL_HASH);
            System.out.println("  → STRUCTURAL_HASH remapped to [" + matchId + "]");
            return StepOutcome.ok(ReplayStrategy.STRUCTURAL_HASH, updated);
        }
        return null;
    }

    private StepOutcome tryScrollIntoView(ScriptStep step, ElementAction action) throws Exception {
        String stableId = step.stableId();
        List<String> selectors = querySelectorSafe(step.fallbackSelectors());
        if (selectors.isEmpty()) {
            System.out.println("  → SCROLL_INTO_VIEW: no querySelector-safe fallback selectors");
            return null;
        }

        for (String selector : selectors) {
            String raw = (String) browser.page().evaluate(SCROLL_INTO_VIEW_JS, selector);
            JSONObject info = new JSONObject(raw);

            if (info.optBoolean("notInDom")) {
                System.out.println("  → SCROLL_INTO_VIEW: querySelector returned null");
                System.out.println("  → for selector: " + selector);
                continue;
            }

            int foldPx = info.optInt("foldPx", 0);
            int topBefore = (int) info.optDouble("topBefore", 0);
            sleepMs(replay.scrollSettleMs());

            browser.listInteractables(false);
            if (browser.countByStableId(stableId) > 0) {
                JSONObject r = action.run(stableId);
                if (r.optBoolean("ok")) {
                    if (foldPx > 0) {
                        System.out.printf(
                                "  → Element was %dpx below viewport fold%n", foldPx);
                    }
                    System.out.println("  → Scrolled to center, retagged, found");
                    return StepOutcome.ok(ReplayStrategy.SCROLL_INTO_VIEW);
                }
            }

            System.out.printf(
                    "  → SCROLL_INTO_VIEW: element in DOM at top=%dpx%n", topBefore);
            System.out.println("  → scrollIntoView executed, retag failed to find");
        }

        System.out.println("  → Falling through to PROGRESSIVE_SCROLL");
        return null;
    }

    private StepOutcome tryProgressiveScroll(ScriptStep step, ElementAction action) throws Exception {
        String stableId = step.stableId();

        browser.page().evaluate("() => window.scrollTo(0, 0)");
        sleepMs(200);

        for (int chunk = 0; chunk < replay.progressiveScrollMaxChunks(); chunk++) {
            if (browser.countByStableId(stableId) > 0) {
                JSONObject r = action.run(stableId);
                if (r.optBoolean("ok")) {
                    return StepOutcome.ok(ReplayStrategy.PROGRESSIVE_SCROLL);
                }
            }

            browser.listInteractables(false);
            if (browser.countByStableId(stableId) > 0) {
                JSONObject r = action.run(stableId);
                if (r.optBoolean("ok")) {
                    return StepOutcome.ok(ReplayStrategy.PROGRESSIVE_SCROLL);
                }
            }

            Object atBottom = browser.page().evaluate("""
                    () => {
                      const y = window.scrollY + window.innerHeight;
                      const h = document.documentElement.scrollHeight;
                      return y >= h - 2;
                    }
                    """);
            if (Boolean.TRUE.equals(atBottom)) {
                break;
            }

            browser.page().evaluate(
                    "(px) => window.scrollBy(0, px)", replay.progressiveScrollChunkPx());
            sleepMs(replay.progressiveScrollChunkWaitMs());
        }

        return null;
    }

    private static List<String> querySelectorSafe(List<String> selectors) {
        List<String> out = new ArrayList<>();
        if (selectors == null) {
            return out;
        }
        for (String s : selectors) {
            if (s == null || s.isBlank()) {
                continue;
            }
            if (s.contains(":has-text")
                    || s.contains(":text(")
                    || s.contains("nth=")) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    private List<String> jsonFallbacks(JSONObject el) {
        List<String> list = new ArrayList<>();
        JSONArray fb = el.optJSONArray("fallbackSelectors");
        if (fb != null) {
            for (int i = 0; i < fb.length(); i++) {
                list.add(fb.optString(i));
            }
        }
        return list;
    }

    /**
     * Last-resort self-heal: ask Claude (vision) to locate the intended element from a screenshot
     * plus the live interactables list, then act on the id it returns. Unlike the deterministic
     * levels this genuinely invokes Bedrock, so its token usage/cost is tracked on the outcome and
     * a successful match re-fingerprints the step for future runs.
     */
    private StepOutcome claudeRecover(ScriptStep step, ElementAction action) {
        try {
            JSONArray elements = browser.listInteractables(false);
            if (elements.isEmpty()) {
                return StepOutcome.fail("claude recovery: no interactable elements on page");
            }

            byte[] screenshot = browser.viewportScreenshotJpeg(85);
            String elementList = browser.formatElementsForPrompt(elements);

            String system = """
                    You are a browser-automation self-healing assistant. A recorded replay step
                    could not locate its target element by fingerprint. Using the screenshot and the
                    list of currently interactable elements (each line starts with its [id]), pick the
                    single element that best matches the intended target.
                    Return ONLY the id value of the best match (the token inside the brackets), or the
                    exact word NONE if nothing plausibly matches. No prose, no markdown, no brackets.
                    """;

            String user = "Intended action: " + step.action() + "\n"
                    + "Target label: " + safe(step.elementLabel()) + "\n"
                    + "Recorded fingerprint: " + safe(step.fingerprintString()) + "\n\n"
                    + "Interactable elements:\n" + elementList + "\n"
                    + "Which [id] is the target? Reply with the id only, or NONE.";

            var res = bedrock.invokeWithVision(system, user, screenshot);
            TokenUsage usage = res.usage();

            String id = extractElementId(res.text(), elements);
            if (id == null) {
                return new StepOutcome(false, null, null,
                        "claude vision fallback found no matching element", true, usage);
            }

            JSONObject chosen = findById(elements, id);
            JSONObject r = action.run(id);
            if (r.optBoolean("ok")) {
                ScriptStep updated = step.withUpdatedFingerprint(
                        id,
                        chosen != null ? chosen.optString("fingerprintString") : step.fingerprintString(),
                        chosen != null ? chosen.optInt("fingerprintLevel") : 0,
                        chosen != null ? jsonFallbacks(chosen) : new ArrayList<>(),
                        ReplayStrategy.CLAUDE);
                System.out.println("  → CLAUDE vision located element [" + id + "]");
                return new StepOutcome(true, ReplayStrategy.CLAUDE, updated, null, true, usage);
            }
            return new StepOutcome(false, null, null,
                    "claude vision fallback: action failed on chosen element [" + id + "]", true, usage);
        } catch (Exception e) {
            return StepOutcome.fail("claude recovery error: " + firstLine(e.getMessage()));
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Resolves the model's reply to a known element id. Accepts a bare/quoted/bracketed id, or any
     * reply that contains a known id (handles suffixed ids like {@code 38b1b845_1}). Returns
     * {@code null} for NONE or when no known id is referenced.
     */
    private static String extractElementId(String modelText, JSONArray elements) {
        if (modelText == null) {
            return null;
        }
        String t = modelText.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("NONE")) {
            return null;
        }
        String cleaned = t.replaceAll("[\\[\\]\"'`]", "").trim();
        if (findById(elements, cleaned) != null) {
            return cleaned;
        }
        for (int i = 0; i < elements.length(); i++) {
            String id = elements.getJSONObject(i).optString("id");
            if (!id.isBlank() && t.contains(id)) {
                return id;
            }
        }
        return null;
    }

    private static JSONObject findById(JSONArray elements, String id) {
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.getJSONObject(i);
            if (id.equals(el.optString("id"))) {
                return el;
            }
        }
        return null;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    /**
     * Screen-based completion check (system-agnostic). Asks Claude, from a fresh screenshot and the
     * (secret-redacted) goal, whether the task is fully and successfully complete — recognizing
     * success/error toasts and expected end states. Used to salvage a run when a trailing step
     * fails to relocate. Best-effort: on any error it reports "not attempted / not complete".
     */
    private VerifyResult verifyCompletion(Script script, TokenValues tokens) {
        try {
            browser.waitForPageReady();
            byte[] screenshot = browser.viewportScreenshotJpeg(85);
            String goal = redactGoal(script.goal(), tokens);

            String system = """
                    You are a strict web-automation completion checker. Given a screenshot of the
                    current page and the user's overall goal, decide whether the goal is FULLY and
                    SUCCESSFULLY complete based only on what is visible on screen.
                    Treat as COMPLETE only on a clear success signal: a confirmation/success toast or
                    message, or the expected end state (e.g. a newly created record shown, a dialog
                    closed returning to the list). Treat an error/validation toast, a still-open form,
                    or a blank/loading page as NOT complete.
                    Respond with ONLY compact JSON: {"complete": true|false, "reason": "<short>"}.
                    No markdown, no extra text.
                    """;

            String user = "User goal:\n" + goal + "\n\n"
                    + "Is the goal fully and successfully complete based on the current screen?";

            var res = bedrock.invokeWithVision(system, user, screenshot);
            JSONObject parsed = parseJsonLoose(res.text());
            boolean complete = parsed != null && parsed.optBoolean("complete", false);
            String reason = parsed != null
                    ? parsed.optString("reason", "")
                    : "unparseable verifier reply";
            return new VerifyResult(true, complete, reason, res.usage());
        } catch (Exception e) {
            System.out.println("  → completion verification error: " + firstLine(e.getMessage()));
            return new VerifyResult(false, false, "verifier error", null);
        }
    }

    /** Replaces registered secret values in the goal with their {Token} placeholders so real
     *  credentials never enter the verification prompt. */
    private static String redactGoal(String goal, TokenValues tokens) {
        if (goal == null) {
            return "";
        }
        String out = goal;
        if (tokens != null) {
            for (Map.Entry<String, String> e : tokens.asMap().entrySet()) {
                String value = e.getValue();
                if (value != null && !value.isBlank()) {
                    out = out.replace(value, "{" + e.getKey() + "}");
                }
            }
        }
        return out;
    }

    private static JSONObject parseJsonLoose(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new JSONObject(t.substring(start, end + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record VerifyResult(boolean attempted, boolean complete, String reason, TokenUsage usage) {}

    private void validatePreReplay(Script script, TokenValues tokens) throws Exception {
        if (script.steps().isEmpty()) {
            throw new IllegalArgumentException("Script has no steps");
        }
        if (!Script.VERSION.equals(script.version())) {
            System.out.println("WARNING: script version " + script.version()
                    + " may not be fully compatible with " + Script.VERSION);
        }
        List<String> required = new ArrayList<>();
        for (ScriptStep step : script.steps()) {
            if (step.text() != null) {
                required.addAll(TokenValues.tokensIn(step.text()));
            }
        }
        tokens.validateRequired(required.stream().distinct().toList());
    }

    private void validateUrl(ScriptStep step) {
        if (step.expectedUrlPattern() == null || step.expectedUrlPattern().isBlank()) {
            return;
        }
        String current = browser.currentUrl();
        boolean ok = step.expectedUrlExact()
                ? current.equals(step.expectedUrlPattern())
                : current.contains(step.expectedUrlPattern());
        if (!ok) {
            System.out.printf("  [Replay] URL mismatch at step %d (current=%s)%n",
                    step.stepIndex(), current);
        }
    }

    private void updateStepInScript(Script script, ScriptStep updated) {
        script.setStep(updated.stepIndex(), updated);
    }

    private static void logStepStart(int n, int total, ScriptStep step) {
        String label = step.elementLabel() != null ? step.elementLabel() : step.action();
        System.out.printf("[Replay] Step %d/%d: %s \"%s\"%n", n, total, step.action(), label);
    }

    private static void logStepSuccess(ScriptStep step, StepOutcome outcome) {
        System.out.printf("  → Strategy: %s ✓%n", outcome.strategy);
    }

    private static void logStepFailure(StepOutcome outcome) {
        System.out.printf("  → FAILED: %s%n", outcome.error);
    }

    private static void printSummary(
            Script script,
            int succeeded,
            int failed,
            ReplayResult.Builder result,
            int claudeCalls,
            TokenUsage cost) {
        int total = succeeded + failed;
        System.out.println("══════════════════════════════════════");
        System.out.println("REPLAY COMPLETE");
        System.out.println("══════════════════════════════════════");
        System.out.println("Script:       " + script.scriptName());
        System.out.printf("Steps:        %d/%d succeeded%n", succeeded, total);
        System.out.println("Claude calls: " + claudeCalls);
        System.out.println("Claude cost:  $" + String.format("%.4f", cost.totalCostUsd()));
        System.out.printf("Script health: %.0f%%%n", script.healthScore() * 100);
        printStrategyBreakdown(result.strategiesUsed(), succeeded);
        System.out.println("══════════════════════════════════════");
    }

    private static void printStrategyBreakdown(Map<ReplayStrategy, Integer> strategies, int succeeded) {
        if (strategies.isEmpty() || succeeded == 0) {
            return;
        }
        System.out.println("Strategies:");
        EnumMap<ReplayStrategy, Integer> ordered = new EnumMap<>(ReplayStrategy.class);
        ordered.putAll(strategies);
        for (ReplayStrategy s : List.of(
                ReplayStrategy.FINGERPRINT,
                ReplayStrategy.RETAG,
                ReplayStrategy.STRUCTURAL_HASH,
                ReplayStrategy.SCROLL_INTO_VIEW,
                ReplayStrategy.PROGRESSIVE_SCROLL,
                ReplayStrategy.FUZZY,
                ReplayStrategy.CLAUDE)) {
            int count = ordered.getOrDefault(s, 0);
            if (count == 0) {
                continue;
            }
            int pct = (int) Math.round(100.0 * count / succeeded);
            System.out.printf("  %-18s %d steps  (%d%%)%n", strategyLabel(s), count, pct);
        }
    }

    private static String strategyLabel(ReplayStrategy s) {
        return switch (s) {
            case FINGERPRINT -> "Fingerprint";
            case RETAG -> "Retag";
            case STRUCTURAL_HASH -> "Structural-Hash";
            case SCROLL_INTO_VIEW -> "Scroll-Into-View";
            case PROGRESSIVE_SCROLL -> "Progressive";
            case FUZZY -> "Fuzzy";
            case CLAUDE -> "Claude";
            case CSS_FALLBACK -> "CSS-Fallback";
        };
    }

    private static void checkMissWarnings(Script script) {
        for (ScriptStep step : script.steps()) {
            if (step.missCount() >= MISS_WARN_THRESHOLD) {
                System.out.printf(
                        "WARNING: Step %d missed fingerprint %d times — consider re-recording.%n",
                        step.stepIndex(), step.missCount());
            }
        }
        long misses = script.steps().stream().filter(s -> s.missCount() > 0).count();
        if (!script.steps().isEmpty() && (double) misses / script.steps().size() > HIGH_MISS_RATE) {
            System.out.println("WARNING: High miss rate (>30%). Consider re-recording with --mode=RECORD.");
        }
    }

    private static void sleepMs(int ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    private record StepOutcome(
            boolean success,
            ReplayStrategy strategy,
            ScriptStep updatedStep,
            String error,
            boolean claudeUsed,
            TokenUsage usage) {

        static StepOutcome ok(ReplayStrategy s) {
            return new StepOutcome(true, s, null, null, false, null);
        }

        static StepOutcome ok(ReplayStrategy s, ScriptStep updated) {
            return new StepOutcome(true, s, updated, null, false, null);
        }

        static StepOutcome fail(String err) {
            return new StepOutcome(false, null, null, err, false, null);
        }
    }
}
