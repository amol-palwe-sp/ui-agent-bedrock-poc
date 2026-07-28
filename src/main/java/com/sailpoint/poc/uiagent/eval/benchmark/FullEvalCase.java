package com.sailpoint.poc.uiagent.eval.benchmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One system entry inside {@code aggregation-full-eval.json}.
 *
 * <p>Reuses {@link EvalCase.GroundTruth} so there is no parallel type for the
 * {@code navigationGoal / steps / paginationPattern / tokens} block.
 *
 * <p>{@link #runsPerSystemOverride()} is {@code null} when the system inherits
 * {@link FullEvalConfig#defaultRunsPerSystem()}; call {@link #effectiveRuns(int)}
 * to resolve the actual run count for any system.
 */
public record FullEvalCase(
        String id,
        String description,
        String videoPath,
        String targetUrl,
        Credentials credentials,
        EvalCase.GroundTruth groundTruth,
        Integer runsPerSystemOverride,
        /**
         * Manual fallback for the expected total account count when the page oracle
         * ({@code detectExpectedTotal}) cannot find a number on the page.
         *
         * <p>Set this to the real count from the target system's UI.
         * When {@code null} (field omitted from JSON), the oracle result is used alone;
         * when both are {@code null}, the completeness check is skipped and {@code matched}
         * reports as {@code N/A} rather than a false {@code false}.
         *
         * <p><b>Do not use the scrape count itself as this value</b> — that creates a
         * circular match when the scrape is capped by {@code aggregation.max.pages}.
         */
        Integer expectedTotalAccounts) {

    /**
     * Login credentials for this system.
     *
     * <p>{@code identifier} is the login field's value (email, username, or user id —
     * whatever the target system asks for), and {@code secret} is the password.
     * Named generically because the video-analysis step infers its own placeholder
     * name fresh each run (e.g. {@code {Email}} on one run, {@code {Username}} on
     * another, for the exact same field) — see {@link AggregationFullEvaluator}'s
     * alias-based token substitution, which maps both values to every common
     * placeholder name Claude might generate, not just one fixed pair.
     */
    public record Credentials(String identifier, String secret) {}

    /**
     * Returns the run count for this system: the per-system override when set,
     * otherwise the shared default from {@link FullEvalConfig}.
     */
    public int effectiveRuns(int defaultRuns) {
        return runsPerSystemOverride != null ? runsPerSystemOverride : defaultRuns;
    }

    // ── JSON deserialization ───────────────────────────────────────────────────

    static FullEvalCase fromJson(JSONObject o) {
        String id          = o.optString("id",          "");
        String description = o.optString("description", "");
        String videoPath   = o.optString("videoPath",   "");
        String targetUrl   = o.optString("targetUrl",   "");

        JSONObject credsJson = o.optJSONObject("credentials");
        Credentials credentials = credsJson != null
                ? new Credentials(credsJson.optString("identifier", ""), credsJson.optString("secret", ""))
                : new Credentials("", "");

        JSONObject gt = o.optJSONObject("groundTruth");
        EvalCase.GroundTruth groundTruth = parseGroundTruth(gt != null ? gt : new JSONObject());

        Integer runsOverride  = o.has("runsPerSystem")         ? o.getInt("runsPerSystem")         : null;
        Integer manualTotal   = o.has("expectedTotalAccounts") ? o.optInt("expectedTotalAccounts", 0) > 0
                                    ? o.getInt("expectedTotalAccounts") : null
                                : null;

        return new FullEvalCase(id, description, videoPath, targetUrl,
                credentials, groundTruth, runsOverride, manualTotal);
    }

    private static EvalCase.GroundTruth parseGroundTruth(JSONObject gt) {
        String navigationGoal = gt.optString("navigationGoal", "");

        List<String> steps = new ArrayList<>();
        JSONArray stepsArr = gt.optJSONArray("steps");
        if (stepsArr != null) {
            for (int i = 0; i < stepsArr.length(); i++) {
                String s = stepsArr.optString(i, "").trim();
                if (!s.isBlank()) steps.add(s);
            }
        }

        EvalCase.PaginationGT paginationGT = null;
        JSONObject ppJson = gt.optJSONObject("paginationPattern");
        if (ppJson != null) {
            paginationGT = new EvalCase.PaginationGT(
                    ppJson.optString("type",         "unknown"),
                    ppJson.optString("description",  ""),
                    ppJson.optString("selectorHint", ""));
        }

        List<EvalCase.TokenDef> tokens = new ArrayList<>();
        JSONArray tokensArr = gt.optJSONArray("tokens");
        if (tokensArr != null) {
            for (int i = 0; i < tokensArr.length(); i++) {
                JSONObject t = tokensArr.optJSONObject(i);
                if (t != null) {
                    tokens.add(new EvalCase.TokenDef(
                            t.optString("name",  ""),
                            t.optString("label", ""),
                            t.optString("type",  "text")));
                }
            }
        }

        return new EvalCase.GroundTruth(
                navigationGoal,
                Collections.unmodifiableList(steps),
                paginationGT,
                Collections.unmodifiableList(tokens));
    }
}
