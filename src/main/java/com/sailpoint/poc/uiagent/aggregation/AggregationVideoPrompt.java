package com.sailpoint.poc.uiagent.aggregation;

import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import org.json.JSONObject;

/**
 * System and user prompts for Phase 1 (video analysis) of the aggregation pipeline,
 * plus a static {@link #parse(String)} helper that converts Claude's JSON response
 * into an {@link AggregationVideoAnalysis}.
 *
 * @deprecated Replaced by {@link com.sailpoint.poc.uiagent.video.VideoAnalysisPrompt} with
 *             {@link com.sailpoint.poc.uiagent.video.VideoAnalysisRequest#aggregation(
 *             com.sailpoint.poc.uiagent.video.VideoAnalysisRequest.CredentialMode)
 *             VideoAnalysisRequest.aggregation(CredentialMode.LITERAL)}.
 *             This class will be removed in a future cleanup pass.
 */
@Deprecated(since = "refactor/unified-pipeline", forRemoval = true)
public final class AggregationVideoPrompt {

    private AggregationVideoPrompt() {}

    public static final String SYSTEM_PROMPT =
            """
            You are a browser automation analyst. You receive a sequence of video frames captured
            from a web browser recording of someone navigating to a user/account list page.

            Your job is to extract:
            1. The navigation steps needed to reach the user list / account list page.
            2. The pagination pattern used on that table.

            Reply with a single valid JSON object and NOTHING ELSE — no markdown, no prose outside JSON:
            {
              "navigationGoal": "<step1>, then <step2>, then <step3>",
              "paginationPattern": {
                "type": "<next_button|page_numbers|load_more|infinite_scroll|unknown>",
                "description": "<human readable description of what you saw>",
                "selector_hint": "<best-guess CSS selector for the next-page control>"
              }
            }

            Rules for navigationGoal:
            - Describe every interaction from the first frame until the user/account list is visible.
            - Format: verb phrase joined by ", then " — e.g.
              "click Sign In button, then enter \\"admin@example.com\\" in the Email field, then click Submit"
            - If the video already starts on the list page, write "navigate directly to the list page".

            Rules for paginationPattern:
            - type must be exactly one of: next_button, page_numbers, load_more, infinite_scroll, unknown
            - selector_hint should be the most specific CSS selector you can infer, e.g.
              button[aria-label='Next'], .pagination .next, a[rel='next'], button.load-more
            - If you cannot determine the pattern, use type = "unknown" and leave selector_hint blank.

            Reply with ONLY the JSON object.
            """;

    public static final String USER_PROMPT =
            "Analyse these browser video frames. Extract the navigation steps and pagination pattern. "
            + "Return the JSON object only.";

    /**
     * Builds a user prompt that also tells Claude the target URL so it can validate its output.
     */
    public static String userPromptWithUrl(String url) {
        return "Target URL (user/account list page): " + url + "\n\n"
                + "Analyse these browser video frames. Extract the navigation steps needed to reach "
                + "the user/account list page and identify the pagination pattern on that table. "
                + "Return the JSON object only.";
    }

    // -------------------------------------------------------------------------
    // Parse
    // -------------------------------------------------------------------------

    /**
     * Parses Claude's raw text response into an {@link AggregationVideoAnalysis}.
     *
     * @param rawResponse raw text from Claude (may contain markdown fences)
     * @return parsed analysis record
     * @throws IllegalArgumentException if the response is unparseable or fails validation
     */
    public static AggregationVideoAnalysis parse(String rawResponse) {
        JSONObject json;
        try {
            json = BedrockAnthropicClient.parseModelJson(rawResponse);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Claude returned invalid JSON for video analysis.\nRaw response:\n" + rawResponse, e);
        }

        String navigationGoal = json.optString("navigationGoal", "").trim();
        if (navigationGoal.isBlank()) {
            throw new IllegalArgumentException(
                    "navigationGoal is blank in Claude response.\nRaw response:\n" + rawResponse);
        }

        JSONObject paginationJson = json.optJSONObject("paginationPattern");
        if (paginationJson == null) {
            throw new IllegalArgumentException(
                    "paginationPattern missing in Claude response.\nRaw response:\n" + rawResponse);
        }

        String type        = paginationJson.optString("type",          "unknown").trim().toLowerCase();
        String description = paginationJson.optString("description",   "").trim();
        String selectorHint= paginationJson.optString("selector_hint", "").trim();

        PaginationPattern pattern = new PaginationPattern(type, description, selectorHint);
        if (!pattern.isValidType()) {
            System.err.println("WARNING: paginationPattern.type '" + type
                    + "' is not a recognised value. Expected one of: "
                    + PaginationPattern.validTypes() + ". Treating as 'unknown'.");
            pattern = new PaginationPattern("unknown", description, selectorHint);
        }

        return new AggregationVideoAnalysis(navigationGoal, pattern);
    }
}
