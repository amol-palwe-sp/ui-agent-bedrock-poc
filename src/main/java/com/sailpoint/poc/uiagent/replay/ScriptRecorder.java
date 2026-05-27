package com.sailpoint.poc.uiagent.replay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds {@link Script} steps from successful AgentLoop actions (REQ-RR-3).
 */
public final class ScriptRecorder {

    private final Script script;
    private int stepIndex;

    public ScriptRecorder(String startUrl, String goal, String taskType, String scriptName) {
        this.script = new Script();
        this.script.setStartUrl(startUrl);
        this.script.setGoal(goal);
        this.script.setTaskType(taskType);
        script.setScriptName(scriptName != null && !scriptName.isBlank()
                ? scriptName : Script.goalSlug(goal));
        stepIndex = 0;
    }

    public Script script() {
        return script;
    }

    /**
     * Records a successful action. Returns false for non-recordable types.
     */
    public boolean recordSuccess(
            String actionType,
            JSONObject action,
            JSONObject elementMeta,
            String currentUrl) {
        String type = actionType.toUpperCase();
        if ("DONE".equals(type) || "TERMINATE".equals(type) || "WAIT".equals(type)) {
            return false;
        }

        List<String> fallbacks = new ArrayList<>();
        String stableId = null;
        String fp = "";
        int level = 0;
        String label = "";
        int ordinal = 0;

        if (elementMeta != null) {
            stableId = elementMeta.optString("id", null);
            fp = elementMeta.optString("fingerprintString", "");
            level = elementMeta.optInt("fingerprintLevel", 0);
            label = elementMeta.optString("elementLabel",
                    elementMeta.optString("text", ""));
            ordinal = elementMeta.optInt("stableIdOrdinal", 0);
            JSONArray fb = elementMeta.optJSONArray("fallbackSelectors");
            if (fb != null) {
                for (int i = 0; i < fb.length(); i++) {
                    fallbacks.add(fb.optString(i));
                }
            }
        }

        String text = action.optString("text", null);
        if (text != null && !text.isBlank() && "TYPE".equals(type)) {
            text = tokenizeForScript(text);
        }

        String urlPattern = urlPatternFrom(currentUrl);

        ScriptStep step = new ScriptStep(
                stepIndex++,
                type,
                stableId,
                ordinal,
                fp,
                level,
                label,
                fallbacks,
                text,
                action.optString("key", null),
                action.optString("label", null),
                action.has("checked") ? action.optBoolean("checked") : null,
                action.optString("url", null),
                action.optString("direction", null),
                action.has("amount") ? action.optInt("amount") : null,
                action.has("ms") ? action.optInt("ms") : null,
                urlPattern,
                false,
                null,
                true,
                0);
        script.addStep(step);
        return true;
    }

    /** Replace literal credential-like values with {Token} placeholders when possible. */
    private static String tokenizeForScript(String text) {
        if (text.contains("@") && !text.startsWith("{")) {
            return "{Email}";
        }
        if (text.length() >= 6 && !text.startsWith("{")) {
            return "{Password}";
        }
        return text;
    }

    private static String urlPatternFrom(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost() != null ? uri.getHost() : url;
        } catch (Exception e) {
            return url.length() > 80 ? url.substring(0, 80) : url;
        }
    }

    public boolean shouldSave() {
        return script.steps().size() >= 2;
    }
}
