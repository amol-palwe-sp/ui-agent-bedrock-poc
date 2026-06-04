package com.sailpoint.poc.uiagent.aggregation;

import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import org.json.JSONObject;

/**
 * System and user prompts for the unified aggregation video-analysis call used by the
 * Aggregation UI tab.
 *
 * <p><b>Key difference from {@link AggregationVideoAnalysis}:</b> this prompt instructs
 * Claude to replace every sensitive credential value with a {@code {Token}} placeholder
 * (e.g. {@code enter "{Email}" in the Email field}) so that the UI can present labelled
 * inputs for the user to fill in before running the pipeline.
 */
public final class AggregationVideoAnalysisPrompt {

    private AggregationVideoAnalysisPrompt() {}

    public static final String SYSTEM_PROMPT =
            """
            You are a browser automation analyst. You receive a sequence of video frames captured
            from a web browser recording of someone navigating to a user/account list page.

            Your job is to extract:
            1. The exact URL of the user/account list page (from the browser address bar).
            2. The navigation steps needed to reach the user list / account list page,
               WITH {Token} placeholders replacing every sensitive credential value.
            3. The pagination pattern used on that table.

            CRITICAL — Placeholder Substitution Rule:
            Replace EVERY credential, password, username, e-mail address, or other sensitive
            value you observe with a descriptive {Token} placeholder.  Use the field label to
            name the token:
              Email field           → {Email}
              Password field        → {Password}
              Primary email field   → {PrimaryEmail}
              Username field        → {Username}
            Example:
              WRONG:   enter "admin@example.com" in the Email field
              CORRECT: enter "{Email}" in the Email field

            Reply with a single valid JSON object and NOTHING ELSE — no markdown, no prose:
            {
              "targetUrl": "https://admin.google.com/ac/users",
              "navigationGoal": "enter \\"{Email}\\" in the Email field, then click Next button, then enter \\"{Password}\\" in the Password field, then click Next button",
              "paginationPattern": {
                "type": "<next_button|page_numbers|load_more|infinite_scroll|unknown>",
                "description": "<human readable description of what you saw>",
                "selector_hint": "<best-guess CSS selector for the next-page control>"
              }
            }

            Rules for targetUrl:
            - Extract the exact URL visible in the browser address bar when the user/account list page is shown.
            - Must start with https:// or http://.
            - If the URL cannot be determined from the video, return an empty string "".

            Rules for navigationGoal:
            - Describe every interaction from the first frame until the user/account list is visible.
            - Format: verb phrase joined by ", then " — e.g.
              "click Sign In, then enter \\"{Email}\\" in the Email field, then click Submit"
            - Replace ALL sensitive values with {Token} placeholders (CRITICAL rule above).
            - If the video already starts on the list page, write "navigate directly to the list page".

            Rules for paginationPattern:
            - type must be exactly one of: next_button, page_numbers, load_more, infinite_scroll, unknown
            - selector_hint should be the most specific CSS selector you can infer, e.g.
              button[aria-label='Next'], .pagination .next, a[rel='next'], button.load-more
            - If you cannot determine the pattern, use type = "unknown" and leave selector_hint blank.

            Reply with ONLY the JSON object.
            """;

    public static final String USER_PROMPT =
            "Analyse these browser video frames. "
            + "Extract the navigation steps WITH {Token} placeholders for sensitive values, "
            + "and identify the pagination pattern on the user/account list table. "
            + "Return the JSON object only.";

    /**
     * Builds a user prompt that tells Claude the target URL explicitly so it does not guess.
     * Claude should use this URL verbatim in the {@code targetUrl} field.
     */
    public static String userPromptWithUrl(String url) {
        return "Target URL (use exactly as provided in targetUrl): " + url + "\n\n"
                + "Analyse these browser video frames. "
                + "Extract the navigation steps WITH {Token} placeholders for sensitive values "
                + "needed to reach the user/account list page, and identify the pagination pattern. "
                + "Return the JSON object only.";
    }

    // -------------------------------------------------------------------------
    // Parse
    // -------------------------------------------------------------------------

    /**
     * Parses Claude's raw text response into an {@link AggregationUIAnalysis}.
     *
     * @param rawResponse raw text from Claude (may contain markdown fences)
     * @return parsed analysis record
     * @throws IllegalArgumentException if the response is unparseable or fails validation
     */
    public static AggregationUIAnalysis parse(String rawResponse) {
        JSONObject json;
        try {
            json = BedrockAnthropicClient.parseModelJson(rawResponse);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Claude returned invalid JSON for aggregation video analysis.\nRaw response:\n"
                    + rawResponse, e);
        }

        String targetUrl = json.optString("targetUrl", "").trim();
        if (targetUrl.isBlank()) {
            System.err.println("WARNING: targetUrl is blank — user must provide URL via override field");
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

        String type         = paginationJson.optString("type",          "unknown").trim().toLowerCase();
        String description  = paginationJson.optString("description",   "").trim();
        String selectorHint = paginationJson.optString("selector_hint", "").trim();

        PaginationPattern pattern = new PaginationPattern(type, description, selectorHint);
        if (!pattern.isValidType()) {
            System.err.println("WARNING: paginationPattern.type '" + type
                    + "' is not a recognised value. Expected one of: "
                    + PaginationPattern.validTypes() + ". Treating as 'unknown'.");
            pattern = new PaginationPattern("unknown", description, selectorHint);
        }

        return new AggregationUIAnalysis(targetUrl, navigationGoal, pattern);
    }
}
