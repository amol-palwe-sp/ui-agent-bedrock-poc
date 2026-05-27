package com.sailpoint.poc.uiagent.ui;

import com.sailpoint.poc.uiagent.pipeline.PipelineMode;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Parsed POST /api/run body. */
public record RunRequest(
        String mode,
        String goalLine,
        String scriptPath,
        Map<String, String> tokenValues) {

    public static RunRequest fromJson(String body) {
        JSONObject json = new JSONObject(body.trim());
        String mode = json.optString("mode", "RUN").trim().toUpperCase();

        Map<String, String> tokens = new HashMap<>();
        JSONObject tv = json.optJSONObject("tokenValues");
        if (tv != null) {
            Iterator<String> keys = tv.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                tokens.put(key, tv.optString(key, ""));
            }
        }

        return new RunRequest(
                mode,
                json.optString("goalLine", "").trim(),
                json.optString("scriptPath", "").trim(),
                tokens);
    }

    public boolean isReplay() {
        return "REPLAY".equals(mode);
    }

    public boolean isRecord() {
        return "RECORD".equals(mode);
    }

    public PipelineMode pipelineMode() {
        if (isReplay()) {
            return PipelineMode.REPLAY;
        }
        if (isRecord()) {
            return PipelineMode.RECORD;
        }
        return PipelineMode.GENERATE;
    }
}
