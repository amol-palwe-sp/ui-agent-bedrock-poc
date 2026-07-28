package com.sailpoint.poc.uiagent.replay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One recorded automation step (REQ-RR-2.2).
 */
public record ScriptStep(
        int stepIndex,
        String action,
        String stableId,
        int stableIdOrdinal,
        String fingerprintString,
        int fingerprintLevel,
        String elementLabel,
        List<String> fallbackSelectors,
        String text,
        String key,
        String label,
        Boolean checked,
        String url,
        String direction,
        Integer amount,
        Integer ms,
        String expectedUrlPattern,
        boolean expectedUrlExact,
        ReplayStrategy lastReplayStrategy,
        boolean lastReplaySuccess,
        int missCount,
        String structuralHash) {

    public static ScriptStep fromJson(JSONObject o) {
        List<String> fallbacks = new ArrayList<>();
        JSONArray fb = o.optJSONArray("fallbackSelectors");
        if (fb != null) {
            for (int i = 0; i < fb.length(); i++) {
                fallbacks.add(fb.optString(i));
            }
        }
        ReplayStrategy strategy = null;
        String strat = o.optString("lastReplayStrategy", "");
        if (!strat.isBlank()) {
            try {
                strategy = ReplayStrategy.valueOf(strat);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ScriptStep(
                o.optInt("stepIndex", 0),
                o.optString("action", ""),
                o.optString("stableId", null),
                o.optInt("stableIdOrdinal", 0),
                o.optString("fingerprintString", ""),
                o.optInt("fingerprintLevel", 0),
                o.optString("elementLabel", ""),
                fallbacks,
                o.optString("text", null),
                o.optString("key", null),
                o.optString("label", null),
                o.has("checked") ? o.optBoolean("checked") : null,
                o.optString("url", null),
                o.optString("direction", null),
                o.has("amount") ? o.optInt("amount") : null,
                o.has("ms") ? o.optInt("ms") : null,
                o.optString("expectedUrlPattern", ""),
                o.optBoolean("expectedUrlExact", false),
                strategy,
                o.optBoolean("lastReplaySuccess", false),
                o.optInt("missCount", 0),
                o.optString("structuralHash", ""));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("stepIndex", stepIndex);
        o.put("action", action);
        if (stableId != null) o.put("stableId", stableId);
        o.put("stableIdOrdinal", stableIdOrdinal);
        if (fingerprintString != null) o.put("fingerprintString", fingerprintString);
        o.put("fingerprintLevel", fingerprintLevel);
        if (elementLabel != null) o.put("elementLabel", elementLabel);
        o.put("fallbackSelectors", new JSONArray(fallbackSelectors));
        if (text != null) o.put("text", text);
        if (key != null) o.put("key", key);
        if (label != null) o.put("label", label);
        if (checked != null) o.put("checked", checked);
        if (url != null) o.put("url", url);
        if (direction != null) o.put("direction", direction);
        if (amount != null) o.put("amount", amount);
        if (ms != null) o.put("ms", ms);
        if (expectedUrlPattern != null) o.put("expectedUrlPattern", expectedUrlPattern);
        o.put("expectedUrlExact", expectedUrlExact);
        if (lastReplayStrategy != null) o.put("lastReplayStrategy", lastReplayStrategy.name());
        o.put("lastReplaySuccess", lastReplaySuccess);
        o.put("missCount", missCount);
        if (structuralHash != null && !structuralHash.isBlank()) o.put("structuralHash", structuralHash);
        return o;
    }

    public ScriptStep withUpdatedFingerprint(
            String newStableId,
            String newFingerprint,
            int newLevel,
            List<String> newFallbacks,
            ReplayStrategy strategy) {
        return new ScriptStep(
                stepIndex, action, newStableId, stableIdOrdinal,
                newFingerprint, newLevel, elementLabel, newFallbacks,
                text, key, label, checked, url, direction, amount, ms,
                expectedUrlPattern, expectedUrlExact, strategy, true, missCount + 1,
                structuralHash);
    }

    public ScriptStep withReplayMeta(ReplayStrategy strategy, boolean success) {
        return new ScriptStep(
                stepIndex, action, stableId, stableIdOrdinal,
                fingerprintString, fingerprintLevel, elementLabel, fallbackSelectors,
                text, key, label, checked, url, direction, amount, ms,
                expectedUrlPattern, expectedUrlExact, strategy, success, missCount,
                structuralHash);
    }
}
