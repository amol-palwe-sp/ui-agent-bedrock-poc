package com.sailpoint.poc.uiagent.aggregation;

import com.sailpoint.poc.uiagent.ActionLogger;
import com.sailpoint.poc.uiagent.AgentLoop;
import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;

import java.io.File;
import java.util.List;

/**
 * Step 1 of the two-step aggregation pipeline.
 *
 * <p>This runner:
 * <ol>
 *   <li>Extracts frames from the MP4 video.</li>
 *   <li>Calls Claude to get the {@code navigationGoal} + {@code paginationPattern}.</li>
 *   <li>Navigates to {@code --url} and runs {@code AgentLoop} with the extracted goal
 *       so the browser lands on the user list page.</li>
 *   <li>Saves an {@link AggregationPlan} JSON file to {@code ./output/}.</li>
 *   <li>Prints the next command to run (Step 2).</li>
 * </ol>
 *
 * <pre>
 *   ./gradlew runAggregationPlan \
 *     --args='--video=recording.mp4 --url=https://admin.google.com/ac/users'
 * </pre>
 *
 * <p>After this completes, run Step 2 with your real credentials:
 * <pre>
 *   ./gradlew runAggregation \
 *     --args='--plan=./output/aggregation-plan_&lt;timestamp&gt;.json \
 *             --url=https://admin.google.com/ac/users \
 *             --goal=enter "user@example.com" in Email, then click Next, then enter "password" in password field, then click Next'
 * </pre>
 */
public final class AggregationPlanRunner {

    public static void main(String[] args) {
        try {
            int exitCode = run(args);
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static int run(String[] args) throws Exception {
        ParsedArgs parsed = ParsedArgs.parse(args);
        if (parsed == null) {
            printUsage();
            return 1;
        }

        File videoFile = new File(parsed.video());
        if (!videoFile.exists()) {
            System.err.println("ERROR: Video file not found: " + parsed.video());
            return 1;
        }

        PocConfig config = new PocConfig();

        // ── Phase 1 — Video Analysis ─────────────────────────────────────────
        printSeparator("Step 1 / Phase 1 — Video Analysis");

        VideoFrameExtractor extractor = new VideoFrameExtractor(
                config.videoMaxFrames(),
                config.videoChangeThreshold(),
                config.videoMinGapSeconds(),
                config.videoDebugFramesDir());

        System.out.println("Extracting frames from: " + videoFile.getAbsolutePath());
        List<byte[]> frames = extractor.extractFrames(parsed.video());
        System.out.printf("Extracted %d frames.%n%n", frames.size());

        if (frames.isEmpty()) {
            System.err.println("ERROR: No frames extracted from video.");
            return 1;
        }

        TokenUsage totalUsage = TokenUsage.ZERO;
        AggregationVideoAnalysis videoAnalysis;

        try (BedrockAnthropicClient bedrock = new BedrockAnthropicClient(
                        config.awsRegion(),
                        config.awsProfile(),
                        config.bedrockModelId(),
                        config.maxTokens(),
                        config.temperature());
                BrowserSession browser = new BrowserSession(
                        config.browserHeadless(),
                        config.browserSlowMoMs(),
                        config.browserViewportWidth(),
                        config.browserViewportHeight(),
                        config.browserStartMaximized(),
                        config.browserFullscreenViewportWidth(),
                        config.browserFullscreenViewportHeight(),
                        config.actionTimeoutClickMs(),
                        config.actionTimeoutTypeMs(),
                        config.actionTimeoutNavigateMs(),
                        config.interActionDelayMs());
                ActionLogger actionLogger = new ActionLogger(config.agentLogFile())) {

            System.out.println("Calling Claude for video analysis (model: " + config.bedrockModelId() + ")...");
            String userPrompt = AggregationVideoPrompt.userPromptWithUrl(parsed.url());
            InvokeResult videoResult = bedrock.invokeWithMultipleImages(
                    AggregationVideoPrompt.SYSTEM_PROMPT, userPrompt, frames);
            totalUsage = totalUsage.add(videoResult.usage());
            System.out.println("Video analysis tokens: " + videoResult.usage());

            try {
                videoAnalysis = AggregationVideoPrompt.parse(videoResult.text());
            } catch (IllegalArgumentException e) {
                System.err.println("ERROR: " + e.getMessage());
                return 1;
            }

            System.out.println();
            System.out.println("═══ Extracted Plan ═══════════════════════════════════");
            System.out.println("Navigation goal  : " + videoAnalysis.navigationGoal());
            System.out.println("Pagination type  : " + videoAnalysis.paginationPattern().type());
            System.out.println("Description      : " + videoAnalysis.paginationPattern().description());
            System.out.println("Selector hint    : " + videoAnalysis.paginationPattern().selectorHint());
            System.out.println("══════════════════════════════════════════════════════");

            // ── Phase 2 — Navigate to user list page ─────────────────────────
            printSeparator("Step 1 / Phase 2 — Navigate to User List Page");

            System.out.println("Navigating to: " + parsed.url());
            browser.navigate(parsed.url());
            System.out.println("Current URL: " + browser.currentUrl());
            System.out.println();
            System.out.println("Running AgentLoop with extracted navigation goal...");
            System.out.println("(Using goal from video — for real credentials use --goal in Step 2)");
            System.out.println();

            AgentLoop agentLoop = new AgentLoop(
                    bedrock,
                    browser,
                    actionLogger,
                    config.agentMaxSteps(),
                    videoAnalysis.navigationGoal(),
                    config.agentNoProgressLimit());
            agentLoop.run();

            System.out.println();
            System.out.println("Landing URL: " + browser.currentUrl());

            // ── Save Plan File ────────────────────────────────────────────────
            printSeparator("Step 1 — Saving Plan");

            AggregationPlan plan = AggregationPlan.from(parsed.url(), videoAnalysis);
            plan.save(config.aggregationOutputDir());

            // ── Print Step 2 command ──────────────────────────────────────────
            System.out.println();
            System.out.println("════════════════════════════════════════════════════");
            System.out.println("STEP 1 COMPLETE — Review browser, then run Step 2:");
            System.out.println("════════════════════════════════════════════════════");
            System.out.println();
            System.out.println("./gradlew runAggregation \\");
            System.out.println("  --args='--plan=<path-above> \\");
            System.out.println("          --url=" + parsed.url() + " \\");
            System.out.println("          --goal=enter \"YOUR_EMAIL\" in the Email field, then click Next, then enter \"YOUR_PASSWORD\" in the password field, then click Next'");
            System.out.println();
            System.out.println("Replace <path-above> with the plan path printed above.");
            System.out.println("Replace YOUR_EMAIL and YOUR_PASSWORD with real credentials.");
            System.out.println();
            System.out.printf("Token usage (Step 1): %s%n", totalUsage);
        }

        return 0;
    }

    private static void printSeparator(String title) {
        System.out.println();
        System.out.println("────────────────────────────────────────────────────");
        System.out.println(title);
        System.out.println("────────────────────────────────────────────────────");
    }

    private static void printUsage() {
        System.out.println(
                """
                Aggregation Plan Runner (Step 1 of 2)

                Required:
                  --video=<path>          MP4 recording of navigation to user list + one page click
                  --url=<https://...>     Direct URL to the user/account list page

                Example:
                  ./gradlew runAggregationPlan \\
                    --args='--video=recording.mp4 --url=https://admin.google.com/ac/users'

                This saves a plan JSON to ./output/ and prints the Step 2 command.
                """);
    }

    private record ParsedArgs(String video, String url) {
        static ParsedArgs parse(String[] args) {
            String video = null;
            String url   = null;
            for (String arg : args) {
                if (arg.startsWith("--video=")) video = arg.substring("--video=".length()).trim();
                else if (arg.startsWith("--url=")) url = arg.substring("--url=".length()).trim();
            }
            if (video == null || video.isBlank()) {
                System.err.println("ERROR: --video is required.");
                return null;
            }
            if (url == null || url.isBlank()) {
                System.err.println("ERROR: --url is required.");
                return null;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                System.err.println("ERROR: --url must start with http:// or https://");
                return null;
            }
            return new ParsedArgs(video, url);
        }
    }
}
