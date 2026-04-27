package com.sailpoint.poc.uiagent;

import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Observe page (screenshot + indexed elements) → Bedrock → execute JSON actions → repeat.
 *
 * <p>Every action goes through a fallback ladder in {@link BrowserSession}, the page is re-scraped between turns, and
 * a navigation mid-batch ends the batch so the model gets a fresh element list before its next decision.
 *
 * <p>Per-step and cumulative {@link TokenUsage} is printed after each LLM call and summarised at the end.
 */
public final class AgentLoop {

    private static final String SYSTEM_PROMPT =
            """
            You are a browser automation planner. You receive:
            - A user goal in natural language.
            - The current page URL.
            - A numbered list of interactable elements (each with [id], tag, role, type, name, placeholder, current value, visible label, and — for <select> — its options). The id is stable for THIS observation only; it changes after the page navigates or re-renders.
            - A viewport screenshot (PNG) of the page.
            - A short feedback log of what the previous step's actions actually did (success/failure + strategy used).

            Reply with a single JSON object ONLY (no markdown, no prose outside JSON) using this schema:
            {
              "reasoning": "short, user-agnostic reasoning",
              "goal_achieved": false,
              "actions": [
                 { "type": "GOTO", "url": "https://..." },
                 { "type": "CLICK", "element_id": 0 },
                 { "type": "TYPE", "element_id": 1, "text": "literal text to type" },
                 { "type": "SELECT_OPTION", "element_id": 2, "label": "Alaska" },
                 { "type": "KEYPRESS", "key": "Enter" },
                 { "type": "SCROLL", "direction": "down", "amount": 600 },
                 { "type": "WAIT", "ms": 500 },
                 { "type": "DONE" }
              ]
            }

            Rules:
            - Treat the entire "User goal" as the instruction; do not collapse it to a single word.
            - Plan AT MOST 3 actions per response. After any CLICK / GOTO that may navigate or open a modal, STOP and wait for the next observation — do not chain a TYPE on element ids that came from a previous DOM.
            - Use TYPE only on inputs/textareas/contenteditable; the "text" field is the literal characters to enter (e.g. an email address) — never the verb "type" or "enter" unless those characters are literally desired.
            - Use SELECT_OPTION for <select> elements; prefer "label" matching the visible option text.
            - Use KEYPRESS (e.g. "Enter", "Escape", "Tab") when no clickable element achieves the same effect.
            - Use SCROLL when a target element is not in the indexed list because it is below the viewport.
            - Use GOTO only when the goal requires a different URL than the current one.
            - Set goal_achieved to true and include { "type": "DONE" } when the user goal is fully satisfied.
            - If the goal cannot be achieved, set goal_achieved to true with action { "type": "TERMINATE", "message": "why" }.
            - Read the previous-step feedback: if a TYPE failed, retry with the correct element_id from the new list.
            - Valid action types: GOTO, CLICK, TYPE, SELECT_OPTION, KEYPRESS, SCROLL, WAIT, DONE, TERMINATE.
            """;

    private static final int MAX_ACTIONS_PER_BATCH = 3;
    private static final int MAX_HISTORY_LINES = 12;

    private final BedrockAnthropicClient bedrock;
    private final BrowserSession browser;
    private final int maxSteps;
    private final String userGoal;

    public AgentLoop(BedrockAnthropicClient bedrock, BrowserSession browser, int maxSteps, String userGoal) {
        this.bedrock = bedrock;
        this.browser = browser;
        this.maxSteps = maxSteps;
        this.userGoal = userGoal;
    }

    public void run() throws Exception {
        List<String> history = new ArrayList<>();
        TokenUsage totalUsage = TokenUsage.ZERO;

        for (int step = 0; step < maxSteps; step++) {
            JSONArray elements = browser.listInteractables();
            String elementText = browser.formatElementsForPrompt(elements);
            byte[] png = browser.viewportScreenshotPng();

            String userMessage = buildUserMessage(step, elementText, history);

            System.out.println("--- Step " + (step + 1) + " / " + maxSteps + " ---");
            System.out.println("URL: " + browser.currentUrl());
            System.out.println("Indexed elements: " + elements.length());

            InvokeResult invokeResult = bedrock.invokeWithVision(SYSTEM_PROMPT, userMessage, png);

            TokenUsage stepUsage = invokeResult.usage();
            totalUsage = totalUsage.add(stepUsage);
            System.out.println("  [Token Usage] Step " + (step + 1) + ": " + stepUsage);

            String raw = invokeResult.text();
            JSONObject plan;
            try {
                plan = BedrockAnthropicClient.parseModelJson(raw);
            } catch (JSONException ex) {
                String preview = raw.length() > 800 ? raw.substring(0, 800) + "…" : raw;
                System.err.println("Model returned non-JSON (skipping this step). Preview:\n" + preview);
                addHistory(history, "step " + step + ": parse error; reply with a single JSON object only");
                continue;
            }

            String reasoning = plan.optString("reasoning", "");
            System.out.println("  Model reasoning: " + reasoning);

            boolean goalAchieved = plan.optBoolean("goal_achieved", false);
            JSONArray actions = plan.optJSONArray("actions");
            if (actions == null || actions.isEmpty()) {
                addHistory(history, "step " + step + ": no actions returned; reasoning=" + reasoning);
                continue;
            }

            BatchOutcome outcome = executeActions(actions, history, step);
            if (outcome.stop || goalAchieved) {
                System.out.println("Stopped after goal achieved or terminal action.");
                printTotalUsage(totalUsage);
                return;
            }
        }

        System.out.println("Max steps reached without explicit DONE.");
        printTotalUsage(totalUsage);
    }

    private static void printTotalUsage(TokenUsage total) {
        System.out.println("\n========== TOTAL TOKEN USAGE ==========");
        System.out.println(total);
        System.out.println("========================================");
    }

    private String buildUserMessage(int step, String elementText, List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("User goal:\n").append(userGoal).append("\n\n");
        sb.append("Current URL:\n").append(browser.currentUrl()).append("\n\n");
        sb.append("Interactable elements (use element_id matching [n]):\n").append(elementText).append('\n');
        if (!history.isEmpty()) {
            sb.append("Previous step feedback (most recent last):\n");
            for (String h : history) sb.append("- ").append(h).append('\n');
        }
        sb.append("\nReturn the next JSON plan for step ").append(step).append('.');
        return sb.toString();
    }

    private BatchOutcome executeActions(JSONArray actions, List<String> history, int step) throws InterruptedException {
        String urlBefore = browser.currentUrl();
        int limit = Math.min(actions.length(), MAX_ACTIONS_PER_BATCH);
        if (actions.length() > MAX_ACTIONS_PER_BATCH) {
            System.out.println("Batch trimmed from " + actions.length() + " to " + MAX_ACTIONS_PER_BATCH + " actions.");
        }
        for (int i = 0; i < limit; i++) {
            JSONObject a = actions.getJSONObject(i);
            String type = a.optString("type", "").toUpperCase();

            if ("DONE".equals(type)) {
                System.out.println("ACTION DONE");
                addHistory(history, "step " + step + " action " + i + " DONE");
                return new BatchOutcome(true);
            }
            if ("TERMINATE".equals(type)) {
                String message = a.optString("message", "");
                System.out.println("ACTION TERMINATE: " + message);
                addHistory(history, "step " + step + " action " + i + " TERMINATE: " + message);
                return new BatchOutcome(true);
            }

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
            System.out.println("  result: " + result);
            addHistory(history, "step " + step + " action " + i + " " + type + " → " + summarize(result, a));

            String urlAfter = browser.currentUrl();
            if (!urlAfter.equals(urlBefore)) {
                System.out.println("URL changed (" + urlBefore + " → " + urlAfter + "). Ending batch to re-observe.");
                addHistory(history, "step " + step + " navigated to " + urlAfter + "; remaining actions skipped");
                break;
            }
            if (!result.optBoolean("ok", false)) {
                System.out.println("Action failed; ending batch to re-observe.");
                break;
            }
        }
        return new BatchOutcome(false);
    }

    private JSONObject dispatch(String type, JSONObject a) throws InterruptedException {
        return switch (type) {
            case "GOTO" -> {
                String url = a.optString("url", "");
                if (url.isBlank()) yield new JSONObject().put("ok", false).put("err", "missing url");
                System.out.println("ACTION GOTO " + url);
                browser.navigate(url);
                yield new JSONObject().put("ok", true).put("strategy", "goto");
            }
            case "CLICK" -> {
                int id = a.optInt("element_id", -1);
                System.out.println("ACTION CLICK element_id=" + id);
                yield browser.clickByElementId(id);
            }
            case "TYPE" -> {
                int id = a.optInt("element_id", -1);
                String text = a.optString("text", "");
                System.out.println("ACTION TYPE element_id=" + id + " text=\"" + previewText(text) + "\"");
                yield browser.typeByElementId(id, text);
            }
            case "SELECT_OPTION" -> {
                int id = a.optInt("element_id", -1);
                String value = a.optString("value", "");
                String label = a.optString("label", "");
                System.out.println("ACTION SELECT_OPTION element_id=" + id + " label=\"" + label + "\" value=\"" + value + "\"");
                yield browser.selectOptionByElementId(id, value, label);
            }
            case "KEYPRESS" -> {
                String key = a.optString("key", "");
                System.out.println("ACTION KEYPRESS " + key);
                yield browser.keypress(key);
            }
            case "SCROLL" -> {
                String dir = a.optString("direction", "down");
                int amount = a.optInt("amount", 600);
                System.out.println("ACTION SCROLL " + dir + " " + amount);
                yield browser.scroll(dir, amount);
            }
            case "WAIT" -> {
                int ms = Math.min(10_000, Math.max(0, a.optInt("ms", 500)));
                System.out.println("ACTION WAIT " + ms + "ms");
                Thread.sleep(ms);
                yield new JSONObject().put("ok", true).put("strategy", "wait").put("ms", ms);
            }
            default -> {
                System.out.println("Unknown action type: " + type);
                yield new JSONObject().put("ok", false).put("err", "unknown action type: " + type);
            }
        };
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        String head = nl >= 0 ? s.substring(0, nl) : s;
        return head.length() > 200 ? head.substring(0, 200) + "…" : head;
    }

    private static String summarize(JSONObject result, JSONObject action) {
        if (result.optBoolean("ok", false)) {
            return "ok (" + result.optString("strategy", "") + ")";
        }
        String hint = "TYPE".equalsIgnoreCase(action.optString("type"))
                ? "; expected=\"" + previewText(action.optString("text")) + "\""
                : "";
        return "FAIL: " + result.optString("err") + hint;
    }

    private static String previewText(String text) {
        if (text == null) return "";
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    private static void addHistory(List<String> history, String entry) {
        history.add(entry);
        while (history.size() > MAX_HISTORY_LINES) history.remove(0);
    }

    private record BatchOutcome(boolean stop) {}
}
