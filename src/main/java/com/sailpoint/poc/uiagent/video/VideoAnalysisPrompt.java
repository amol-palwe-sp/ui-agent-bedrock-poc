package com.sailpoint.poc.uiagent.video;

/**
 * Single parameterized prompt builder for all video analysis tasks.
 *
 * <p>Implements REQ-2 (Unified Prompt). Replaces three separate prompt classes:
 * <ul>
 *   <li>{@link VideoToGoalPrompt} — provisioning (delegated from {@link #build} when provisioning)</li>
 *   <li>{@link com.sailpoint.poc.uiagent.aggregation.AggregationVideoPrompt} — aggregation CLI (LITERAL mode)</li>
 *   <li>{@link com.sailpoint.poc.uiagent.aggregation.AggregationVideoAnalysisPrompt} — aggregation web UI (PLACEHOLDER mode)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * VideoAnalysisRequest request = VideoAnalysisRequest.aggregation(
 *         VideoAnalysisRequest.CredentialMode.PLACEHOLDER, "https://admin.google.com/ac/users");
 *
 * VideoAnalysisPrompt.PromptPair prompts = VideoAnalysisPrompt.build(request);
 * InvokeResult result = bedrock.invokeWithMultipleImages(
 *         prompts.systemPrompt(), prompts.userPrompt(), frames);
 *
 * VideoAnalysisResult analysis = VideoAnalysisResult.parse(result.text(), request);
 * </pre>
 */
public final class VideoAnalysisPrompt {

    private VideoAnalysisPrompt() {}

    /**
     * Pair of system and user prompts ready to pass to {@code BedrockAnthropicClient}.
     */
    public record PromptPair(String systemPrompt, String userPrompt) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Builds a {@link PromptPair} tailored to the supplied {@link VideoAnalysisRequest}.
     * The resulting prompts instruct Claude to return the unified JSON schema documented
     * in {@link VideoAnalysisResult}.
     */
    public static PromptPair build(VideoAnalysisRequest request) {
        if (request.isProvisioning()) {
            String user = request.hasTargetUrl()
                    ? VideoToGoalPrompt.userPromptWithUrl(request.targetUrl())
                    : VideoToGoalPrompt.USER_PROMPT;
            return new PromptPair(VideoToGoalPrompt.SYSTEM_PROMPT, user);
        }
        return new PromptPair(buildSystemPrompt(request), buildUserPrompt(request));
    }

    // ── System prompt ─────────────────────────────────────────────────────────

    private static String buildSystemPrompt(VideoAnalysisRequest request) {
        StringBuilder sb = new StringBuilder();

        // Role + context
        sb.append("You are a browser automation analyst. You receive a sequence of video frames\n");
        sb.append("captured from a web browser recording.\n\n");

        // What to extract
        sb.append("Your job is to extract:\n");
        sb.append("1. The exact starting URL from the browser address bar.\n");
        sb.append("2. The navigation steps needed");
        if (request.isAggregation()) {
            sb.append(" to reach the user/account list page");
        }
        sb.append(".\n");
        if (request.isAggregation()) {
            sb.append("3. The pagination pattern used on the accounts table.\n");
        }
        sb.append("\n");

        // PLACEHOLDER mode rule (must appear before schema)
        if (request.isPlaceholderMode()) {
            sb.append("CRITICAL — Credential Placeholder Rule:\n");
            sb.append("Replace EVERY credential, password, username, e-mail, or other sensitive value\n");
            sb.append("you observe with a descriptive {Token} placeholder. Name each token after its\n");
            sb.append("field label:\n");
            sb.append("  Email field          → {Email}\n");
            sb.append("  Password field       → {Password}\n");
            sb.append("  Primary email field  → {PrimaryEmail}\n");
            sb.append("  Username field       → {Username}\n");
            sb.append("Example (WRONG):   enter \"admin@example.com\" in the Email field\n");
            sb.append("Example (CORRECT): enter \"{Email}\" in the Email field\n\n");
        }

        // Output schema
        sb.append("Reply with a single valid JSON object and NOTHING ELSE — no markdown, no prose:\n");
        sb.append("{\n");
        sb.append("  \"targetUrl\": \"https://...\",\n");
        sb.append("  \"navigationGoal\": \"step1, then step2, then ...\",\n");
        if (request.isPlaceholderMode()) {
            sb.append("  \"tokens\": [\n");
            sb.append("    { \"name\": \"Email\",    \"label\": \"Email field\",    \"type\": \"email\"    },\n");
            sb.append("    { \"name\": \"Password\", \"label\": \"Password field\", \"type\": \"password\" }\n");
            sb.append("  ],\n");
        } else {
            sb.append("  \"tokens\": [],\n");
        }
        if (request.isAggregation()) {
            sb.append("  \"paginationPattern\": {\n");
            sb.append("    \"type\": \"<next_button|page_numbers|load_more|infinite_scroll|unknown>\",\n");
            sb.append("    \"description\": \"<human readable description of what you saw>\",\n");
            sb.append("    \"selector_hint\": \"<best-guess CSS selector for the next-page control>\"\n");
            sb.append("  }\n");
        } else {
            sb.append("  \"paginationPattern\": null\n");
        }
        sb.append("}\n\n");

        // Rules for targetUrl
        sb.append("Rules for targetUrl:\n");
        if (request.hasTargetUrl()) {
            sb.append("- Use exactly this URL: ").append(request.targetUrl()).append("\n");
            sb.append("- Do NOT change it, even if the video shows a different URL.\n");
        } else {
            sb.append("- Extract the exact URL visible in the browser address bar ");
            sb.append(request.isAggregation()
                    ? "when the user/account list page is shown.\n"
                    : "on the first stable page shown.\n");
            sb.append("- Must start with https:// or http://.\n");
            sb.append("- If the URL cannot be determined, return an empty string \"\".\n");
        }
        sb.append("\n");

        // Rules for navigationGoal
        sb.append("Rules for navigationGoal:\n");
        if (request.isProvisioning()) {
            sb.append("- List ONLY actions you can clearly see in the recording — no invented steps.\n");
            sb.append("- Chronological order. No newlines inside the string. No numbered lists.\n");
        } else {
            sb.append("- Describe every interaction from the first frame until the user/account\n");
            sb.append("  list is visible.\n");
            sb.append("- If the video already starts on the list page, write:\n");
            sb.append("  \"navigate directly to the list page\"\n");
        }
        sb.append("- Format: verb phrases joined by \", then \" — e.g.\n");
        sb.append("  \"click Sign In button, then enter \\\"admin@corp.com\\\" in the Email field,");
        sb.append(" then click Next button\"\n");
        if (request.isPlaceholderMode()) {
            sb.append("- Replace ALL credential values with {Token} placeholders (CRITICAL rule above).\n");
        }
        sb.append("\n");

        // Rules for tokens (PLACEHOLDER mode)
        if (request.isPlaceholderMode()) {
            sb.append("Rules for tokens:\n");
            sb.append("- List every {Token} placeholder used in navigationGoal.\n");
            sb.append("- \"name\"  — placeholder name without braces, e.g. \"Email\".\n");
            sb.append("- \"label\" — field label as shown in the UI, e.g. \"Email field\".\n");
            sb.append("- \"type\"  — one of: email, password, text, username.\n");
            sb.append("- If no placeholders were used, return an empty array [].\n\n");
        }

        // Rules for paginationPattern (AGGREGATION only)
        if (request.isAggregation()) {
            sb.append("Rules for paginationPattern:\n");
            sb.append("- type must be exactly one of:\n");
            sb.append("  next_button, page_numbers, load_more, infinite_scroll, unknown\n");
            sb.append("- selector_hint: the most specific CSS selector you can infer, e.g.\n");
            sb.append("  button[aria-label='Next'], .pagination .next, a[rel='next'], button.load-more\n");
            sb.append("- If the pattern cannot be determined, use type = \"unknown\" and\n");
            sb.append("  leave selector_hint as an empty string.\n\n");
        }

        // Extra instructions
        if (!request.extraInstructions().isBlank()) {
            sb.append("Additional instructions:\n");
            sb.append(request.extraInstructions()).append("\n\n");
        }

        sb.append("Reply with ONLY the JSON object. No markdown. No prose before or after the JSON.");
        return sb.toString();
    }

    // ── User prompt ───────────────────────────────────────────────────────────

    private static String buildUserPrompt(VideoAnalysisRequest request) {
        StringBuilder sb = new StringBuilder();

        if (request.hasTargetUrl()) {
            if (request.isAggregation()) {
                sb.append("Target URL (user/account list page — use exactly as-is in targetUrl): ");
            } else {
                sb.append("Starting URL (use exactly as-is in targetUrl): ");
            }
            sb.append(request.targetUrl()).append("\n\n");
        }

        sb.append("Analyse these browser video frames. ");

        if (request.isProvisioning()) {
            sb.append("Extract every user interaction you see, in chronological order");
        } else {
            sb.append("Extract the navigation steps needed to reach the user/account list page");
            sb.append(", and identify the pagination pattern on that table");
        }

        if (request.isPlaceholderMode()) {
            sb.append(". Use {Token} placeholders for all sensitive credential values");
        }

        sb.append(". Return the JSON object only.");
        return sb.toString();
    }
}
