package com.sailpoint.poc.uiagent;

import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.browser.BrowserSession;

/**
 * Entry point for the UI Agent POC.
 *
 * <pre>
 *   ./gradlew run --args='--url=https://example.com --goal=Follow the first obvious link you see'
 *   ./gradlew run --args='--url=https://example.com --goal=enter "a@b.com" in email then submit'
 *   (Words after --goal= are merged until the next token starting with --, so quoted args split by Gradle still work.)
 * </pre>
 */
public final class UiAgentPocApplication {

    public static void main(String[] args) throws Exception {
        ParsedArgs parsed = ParsedArgs.parse(args);
        if (parsed == null) {
            printUsage();
            System.exit(1);
            return;
        }
        if (!parsed.startUrl.startsWith("http://") && !parsed.startUrl.startsWith("https://")) {
            System.err.println("ERROR: --url must start with http:// or https:// (got: " + parsed.startUrl + ")");
            System.exit(1);
            return;
        }

        System.out.println("Goal (merged): " + parsed.goal);

        PocConfig config = new PocConfig();
        String modelId = config.bedrockModelId();
        String envModel = config.bedrockModelIdEnvRaw();
        String source = envModel != null && !envModel.isBlank() ? "env BEDROCK_MODEL_ID" : "application.properties";
        System.out.println("Bedrock model id: " + modelId + " (from " + source + ")");
        if (BedrockModelHints.likelyRequiresInferenceProfileArn(modelId)) {
            System.err.println(
                    """
                    ERROR: This model id is not usable as a bare on-demand InvokeModel target. Bedrock expects an inference profile ARN (or profile id) for many Claude 4.x models.

                    Fix one of:
                      1) Set bedrock.model.id (or env BEDROCK_MODEL_ID) to an inference profile ARN from AWS console → Bedrock → Inference profiles.
                      2) Or use an on-demand model your account still allows, e.g. anthropic.claude-3-5-sonnet-20241022-v2:0

                    If BEDROCK_MODEL_ID is set in your shell, unset it:  unset BEDROCK_MODEL_ID
                    """);
            System.exit(2);
            return;
        }

        try (BedrockAnthropicClient bedrock = new BedrockAnthropicClient(
                        config.awsRegion(),
                        config.awsProfile(),
                        modelId,
                        config.maxTokens(),
                        config.temperature());
                BrowserSession browser = new BrowserSession(
                        config.browserHeadless(),
                        config.browserSlowMoMs(),
                        config.browserViewportWidth(),
                        config.browserViewportHeight())) {

            System.out.println("Navigating to: " + parsed.startUrl);
            browser.navigate(parsed.startUrl);

            AgentLoop loop = new AgentLoop(bedrock, browser, config.agentMaxSteps(), parsed.goal);
            loop.run();
        }
    }

    private static void printUsage() {
        System.out.println(
                """
                UI Agent POC (Bedrock + Playwright)

                Required:
                  --url=<https://...>     Starting page (or: --url https://...)
                  --goal=...              Goal text; any argv tokens after --goal= until the next --flag are appended
                                          (fixes Gradle splitting: --goal=enter test@gmail.com ...)
                  Or: --goal <words...>   Same merging rule (no = on --goal)

                Configuration: src/main/resources/application.properties
                  (see application.properties.example)
                  Env BEDROCK_MODEL_ID overrides bedrock.model.id if set.

                AWS credentials: default profile or aws.profile in properties.
                If using SSO: run 'aws sso login' first.

                First run: Playwright downloads Chromium automatically.
                """);
    }

    private record ParsedArgs(String startUrl, String goal) {
        static ParsedArgs parse(String[] args) {
            String url = null;
            String goal = null;

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--url=")) {
                    url = a.substring("--url=".length()).trim();
                } else if ("--url".equals(a) && i + 1 < args.length && !isOptionPrefix(args[i + 1])) {
                    i++;
                    url = args[i].trim();
                } else if (a.startsWith("--goal=")) {
                    StringBuilder g = new StringBuilder(a.substring("--goal=".length()).trim());
                    i = appendMergedWords(args, i, g);
                    goal = g.toString().trim();
                } else if ("--goal".equals(a)) {
                    if (i + 1 >= args.length || isOptionPrefix(args[i + 1])) continue;
                    StringBuilder g = new StringBuilder();
                    i = appendMergedWords(args, i, g);
                    goal = g.toString().trim();
                }
            }
            if (url == null || url.isBlank() || goal == null || goal.isBlank()) return null;
            return new ParsedArgs(url, goal);
        }

        private static int appendMergedWords(String[] args, int goalArgIndex, StringBuilder out) {
            int j = goalArgIndex + 1;
            while (j < args.length && !isOptionPrefix(args[j])) {
                if (!out.isEmpty()) out.append(' ');
                out.append(args[j]);
                j++;
            }
            return j - 1;
        }

        private static boolean isOptionPrefix(String token) {
            return token != null && token.startsWith("--");
        }
    }
}
