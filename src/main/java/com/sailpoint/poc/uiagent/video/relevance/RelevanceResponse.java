package com.sailpoint.poc.uiagent.video.relevance;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Renders a rejection as the JSON body returned by the generate endpoints.
 *
 * <p>A rejected video is answered with HTTP 200 and {@code status: "REJECTED"}, not an error
 * status. Nothing failed — the upload was understood and declined — and the browser needs to
 * tell "your video is unusable" apart from "the server broke", because only one of those is
 * worth retrying.
 *
 * <p>No steps are included. Returning partial output alongside a rejection only invites the
 * user to run it.
 */
public final class RelevanceResponse {

    private RelevanceResponse() {}

    /**
     * Builds the rejection body.
     *
     * @param result     the rejecting verdict
     * @param frameCount frames extracted before triage, reported for transparency
     */
    public static String rejectionJson(RelevanceResult result, int frameCount) {
        JSONObject rejection = new JSONObject()
                .put("category", result.category().name())
                .put("reason", result.reason())
                .put("suggestion", result.suggestion())
                .put("confidence", result.confidence());

        return new JSONObject()
                .put("status", "REJECTED")
                .put("rejection", rejection)
                .put("steps", new JSONArray())
                .put("isValid", false)
                .put("frameCount", frameCount)
                .put("inputTokens", result.tokenUsage().inputTokens())
                .put("outputTokens", result.tokenUsage().outputTokens())
                .put("costUsd", result.tokenUsage().totalCostUsd())
                .toString();
    }

    /** One-line summary for the server log / SSE stream. */
    public static String logLine(RelevanceResult result) {
        return result.category().name() + " (confidence " + result.confidence() + ") — "
                + result.reason();
    }
}
