package com.sailpoint.poc.uiagent.video;

import com.sailpoint.poc.uiagent.aggregation.AggregationUIAnalysis;
import com.sailpoint.poc.uiagent.aggregation.AggregationVideoAnalysis;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Unified parsed result from any video analysis call.
 *
 * <p>Implements REQ-1 (Unified Video Analysis Schema). Replaces three separate result types:
 * <ul>
 *   <li>{@link GoalExtractor.ExtractionResult} (provisioning CLI)</li>
 *   <li>{@link AggregationVideoAnalysis} (aggregation CLI)</li>
 *   <li>{@link AggregationUIAnalysis} (aggregation web UI)</li>
 * </ul>
 *
 * <h2>Schema returned from Claude (all task types)</h2>
 * <pre>
 * {
 *   "targetUrl":       "https://...",
 *   "navigationGoal":  "click Sign In, then enter \"{Email}\" in Email field, then ...",
 *   "tokens": [
 *     { "name": "Email",    "label": "Email field",    "type": "email"    },
 *     { "name": "Password", "label": "Password field", "type": "password" }
 *   ],
 *   "paginationPattern": {          // null / absent for PROVISIONING
 *     "type":         "next_button",
 *     "description":  "...",
 *     "selector_hint": "button[aria-label='Next']"
 *   }
 * }
 * </pre>
 *
 * <p>Obtain instances via {@link #parse(String, VideoAnalysisRequest)}.
 */
public record VideoAnalysisResult(
        VideoAnalysisRequest.TaskType taskType,
        String                        targetUrl,
        String                        navigationGoal,
        List<TokenDefinition>         tokens,
        PaginationPattern             paginationPattern,   // null for PROVISIONING
        boolean                       isValid,
        List<String>                  issues) {

    // ── Factory: parse ────────────────────────────────────────────────────────

    /**
     * Parses Claude's raw text response (may be JSON or JSON wrapped in markdown fences)
     * into a {@link VideoAnalysisResult}.
     *
     * @param rawResponse the raw text from Claude
     * @param request     the request parameters used to build the prompt
     * @return a result; check {@link #isValid()} and {@link #issues()} on failure
     */
    public static VideoAnalysisResult parse(String rawResponse, VideoAnalysisRequest request) {
        List<String> issues = new ArrayList<>();

        if (rawResponse == null || rawResponse.isBlank()) {
            issues.add("Response from Claude is empty");
            return invalid(request.taskType(), request.targetUrl(), issues);
        }

        JSONObject json;
        try {
            json = BedrockAnthropicClient.parseModelJson(rawResponse);
        } catch (Exception e) {
            issues.add("Claude returned invalid JSON: " + e.getMessage());
            return invalid(request.taskType(), request.targetUrl(), issues);
        }

        // ── targetUrl ────────────────────────────────────────────────────────
        String extractedUrl = json.optString("targetUrl", "").trim();
        String targetUrl;
        if (!extractedUrl.isBlank()) {
            targetUrl = extractedUrl;
        } else if (request.hasTargetUrl()) {
            targetUrl = request.targetUrl();
        } else {
            targetUrl = "";
            if (request.isAggregation()) {
                System.err.println("WARNING: targetUrl is blank — supply it via --url or the UI override field");
            }
        }

        // ── navigationGoal ────────────────────────────────────────────────────
        String navigationGoal = json.optString("navigationGoal", "").trim();
        if (navigationGoal.isBlank()) {
            issues.add("navigationGoal is blank in Claude response");
        }

        // ── tokens ────────────────────────────────────────────────────────────
        List<TokenDefinition> tokens = new ArrayList<>();
        JSONArray tokensJson = json.optJSONArray("tokens");
        if (tokensJson != null) {
            for (int i = 0; i < tokensJson.length(); i++) {
                JSONObject t = tokensJson.optJSONObject(i);
                if (t != null) {
                    tokens.add(new TokenDefinition(
                            t.optString("name",  "").trim(),
                            t.optString("label", "").trim(),
                            t.optString("type",  "text").trim()));
                }
            }
        }

        // ── paginationPattern (AGGREGATION only) ──────────────────────────────
        PaginationPattern paginationPattern = null;
        if (request.isAggregation()) {
            JSONObject ppJson = json.optJSONObject("paginationPattern");
            if (ppJson == null) {
                issues.add("paginationPattern is missing (required for aggregation)");
                paginationPattern = new PaginationPattern("unknown", "", "");
            } else {
                String type        = ppJson.optString("type",          "unknown").trim().toLowerCase();
                String description = ppJson.optString("description",   "").trim();
                // Accept both snake_case (from prompt) and camelCase (defensive)
                String selectorHint = ppJson.optString("selector_hint",
                                      ppJson.optString("selectorHint", "")).trim();

                paginationPattern = new PaginationPattern(type, description, selectorHint);
                if (!paginationPattern.isValidType()) {
                    System.err.println("WARNING: paginationPattern.type '" + type
                            + "' not recognised. Expected: " + PaginationPattern.validTypes()
                            + ". Treating as 'unknown'.");
                    paginationPattern = new PaginationPattern("unknown", description, selectorHint);
                }
            }
        }

        boolean isValid = issues.isEmpty();
        return new VideoAnalysisResult(
                request.taskType(), targetUrl, navigationGoal,
                Collections.unmodifiableList(tokens),
                paginationPattern, isValid,
                Collections.unmodifiableList(issues));
    }

    // ── Convenience adapters (backward compatibility) ─────────────────────────

    /**
     * Converts to the legacy {@link AggregationVideoAnalysis} record.
     * Useful when calling code that was written before the unified schema.
     */
    public AggregationVideoAnalysis toAggregationVideoAnalysis() {
        PaginationPattern pp = paginationPattern != null
                ? paginationPattern
                : new PaginationPattern("unknown", "", "");
        return new AggregationVideoAnalysis(navigationGoal, pp);
    }

    /**
     * Converts to the legacy {@link AggregationUIAnalysis} record.
     * Useful when calling code that was written before the unified schema.
     */
    public AggregationUIAnalysis toAggregationUIAnalysis() {
        PaginationPattern pp = paginationPattern != null
                ? paginationPattern
                : new PaginationPattern("unknown", "", "");
        return new AggregationUIAnalysis(targetUrl, navigationGoal, pp);
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Splits {@link #navigationGoal()} on {@code ", then "} into individual step strings.
     */
    public List<String> steps() {
        if (navigationGoal == null || navigationGoal.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String s : navigationGoal.split(",\\s*then\\s+")) {
            String t = s.trim();
            if (!t.isBlank()) result.add(t);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns {@link #navigationGoal()} with all {@code {Token}} placeholders replaced
     * by their values from the supplied map.
     *
     * @param tokenValues map of token name → real value (e.g. {@code "Email" → "user@corp.com"})
     */
    public String resolveGoal(Map<String, String> tokenValues) {
        if (tokenValues == null || tokenValues.isEmpty()) return navigationGoal;
        String resolved = navigationGoal;
        for (Map.Entry<String, String> e : tokenValues.entrySet()) {
            resolved = resolved.replace("{" + e.getKey() + "}", e.getValue());
        }
        return resolved;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static VideoAnalysisResult invalid(
            VideoAnalysisRequest.TaskType taskType, String targetUrl, List<String> issues) {
        return new VideoAnalysisResult(
                taskType, targetUrl != null ? targetUrl : "", "",
                List.of(), null, false,
                Collections.unmodifiableList(issues));
    }
}
