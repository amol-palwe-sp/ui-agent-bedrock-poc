package com.sailpoint.poc.uiagent.aggregation;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.pipeline.AgentPipeline;
import com.sailpoint.poc.uiagent.pipeline.PipelineConfig;
import com.sailpoint.poc.uiagent.pipeline.PipelineResult;
import com.sailpoint.poc.uiagent.pipeline.ProgressListener;
import com.sailpoint.poc.uiagent.video.VideoAnalysisPrompt;
import com.sailpoint.poc.uiagent.video.VideoAnalysisRequest;
import com.sailpoint.poc.uiagent.video.VideoAnalysisResult;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Step 2 of the two-step aggregation pipeline — navigate, detect table, scrape all pages,
 * write CSV.
 *
 * <h2>Two-step workflow (recommended)</h2>
 * <pre>
 *   # Step 1 — generate plan (video analysis + navigate to user list)
 *   ./gradlew runAggregationPlan \
 *     --args='--video=recording.mp4 --url=https://admin.google.com/ac/users'
 *
 *   # Step 2 — scrape with real credentials
 *   ./gradlew runAggregation \
 *     --args='--plan=./output/aggregation-plan_20260430_140000.json \
 *             --url=https://admin.google.com/ac/users \
 *             --goal=enter "user@example.com" in Email, then click Next,
 *                    then enter "password" in password field, then click Next'
 * </pre>
 *
 * <h2>One-step workflow (legacy — still supported)</h2>
 * <pre>
 *   ./gradlew runAggregation \
 *     --args='--video=recording.mp4 --url=https://admin.google.com/ac/users \
 *             --goal=enter "user@example.com" in Email, then click Next, ...'
 * </pre>
 *
 * <h2>Argument reference</h2>
 * <ul>
 *   <li>{@code --plan=<path>}  — path to plan JSON from Step 1 (skips Phase 1 video analysis)</li>
 *   <li>{@code --video=<path>} — MP4 video (required when --plan is not provided)</li>
 *   <li>{@code --url=<url>}    — target URL (required)</li>
 *   <li>{@code --goal=<text>}  — navigation goal with real credentials (overrides Claude-extracted goal)</li>
 * </ul>
 */
public final class AggregationRunner {

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

        PocConfig config  = new PocConfig();
        int    maxPages   = config.aggregationMaxPages();
        String outputDir  = config.aggregationOutputDir();

        TokenUsage videoUsage = TokenUsage.ZERO;
        AggregationVideoAnalysis videoAnalysis;

        // ── Phase 1 — Video Analysis OR load plan ───────────────────────────
        if (parsed.plan() != null) {
            // Two-step path: load pre-computed plan, skip video analysis entirely
            printSeparator("Phase 1 — Loading Plan (skipping video analysis)");
            System.out.println("Loading plan from: " + parsed.plan());
            AggregationPlan plan = AggregationPlan.load(parsed.plan());
            videoAnalysis = new AggregationVideoAnalysis(
                    plan.navigationGoal(), plan.paginationPattern());
            System.out.println("Plan created at    : " + plan.createdAt());
            System.out.println("Pagination type    : " + plan.paginationPattern().type());
            System.out.println("Description        : " + plan.paginationPattern().description());
            System.out.println("Selector hint      : " + plan.paginationPattern().selectorHint());
        } else {
            // One-step path: video → VideoAnalysisPrompt → VideoAnalysisResult
            printSeparator("Phase 1 — Video Analysis");

            File videoFile = new File(parsed.video());
            if (!videoFile.exists()) {
                System.err.println("ERROR: Video file not found: " + parsed.video());
                return 1;
            }

            VideoFrameExtractor extractor = new VideoFrameExtractor(config.video());

            System.out.println("Extracting frames from: " + videoFile.getAbsolutePath());
            List<byte[]> frames = extractor.extractFrames(parsed.video());
            System.out.printf("Extracted %d frames.%n%n", frames.size());

            if (frames.isEmpty()) {
                System.err.println("ERROR: No frames extracted from video.");
                return 1;
            }

            // Short-lived Bedrock client for video analysis only (REQ-3.4: pipeline owns its own)
            VideoAnalysisRequest request = VideoAnalysisRequest.aggregation(
                    VideoAnalysisRequest.CredentialMode.LITERAL, parsed.url());
            VideoAnalysisPrompt.PromptPair prompts = VideoAnalysisPrompt.build(request);

            System.out.println("Calling Claude for video analysis (model: "
                    + config.bedrockModelId() + ")...");

            try (BedrockAnthropicClient phase1Bedrock = new BedrockAnthropicClient(
                    config.bedrock().region(), config.bedrock().profile(),
                    config.bedrock().modelId(), config.bedrock().maxTokens(),
                    config.bedrock().temperature())) {

                InvokeResult videoResult = phase1Bedrock.invokeWithMultipleImages(
                        prompts.systemPrompt(), prompts.userPrompt(), frames);
                videoUsage = videoResult.usage();
                System.out.println("Video analysis tokens: " + videoUsage);

                VideoAnalysisResult result = VideoAnalysisResult.parse(videoResult.text(), request);
                if (!result.isValid()) {
                    System.err.println("ERROR: Video analysis parse failed — " + result.issues());
                    return 1;
                }
                videoAnalysis = result.toAggregationVideoAnalysis();
            }

            System.out.println();
            System.out.println("Navigation goal  : " + videoAnalysis.navigationGoal());
            System.out.println("Pagination type  : " + videoAnalysis.paginationPattern().type());
            System.out.println("Description      : " + videoAnalysis.paginationPattern().description());
            System.out.println("Selector hint    : " + videoAnalysis.paginationPattern().selectorHint());
        }

        // Determine effective navigation goal (--goal overrides Claude-extracted goal)
        final String effectiveGoal;
        if (parsed.goal() != null && !parsed.goal().isBlank()) {
            effectiveGoal = parsed.goal();
            System.out.println();
            System.out.println("Using --goal from CLI (credentials provided at runtime):");
            System.out.println("  " + effectiveGoal);
        } else {
            effectiveGoal = videoAnalysis.navigationGoal();
            System.out.println();
            System.out.println("Using navigation goal from video analysis (no --goal provided):");
            System.out.println("  " + effectiveGoal);
        }

        // ── Phases 2-5 — Navigate + Detect + Aggregate + CSV ─────────────────
        // AgentPipeline owns all resource lifecycle (REQ-3.4).
        printSeparator("Phase 2 — Navigation / Phase 3 — Table Detection / Phase 4-5 — Aggregation + CSV");

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .taskType(PipelineConfig.TaskType.AGGREGATION)
                .startUrl(parsed.url())
                .goal(effectiveGoal)
                .paginationPattern(videoAnalysis.paginationPattern())
                .bedrockConfig(config.bedrock())
                .browserConfig(config.browser())
                .agentConfig(config.agent())
                .aggregationConfig(config.aggregation())
                .build();

        PipelineResult pipelineResult = AgentPipeline.run(pipelineConfig, ProgressListener.SILENT);

        // ── Phase 6 — Summary ─────────────────────────────────────────────────
        TokenUsage grandTotal = videoUsage.add(pipelineResult.totalUsage());

        printSummary(
                pipelineResult.pagesScraped(),
                pipelineResult.rowsScraped(),
                pipelineResult.headers(),
                pipelineResult.csvPath().isBlank() ? null : pipelineResult.csvPath(),
                grandTotal,
                parsed.plan() != null);

        return pipelineResult.success() ? 0 : 1;
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private static void printSummary(
            int pages, int total, List<String> columns,
            String outputFile, TokenUsage usage, boolean usedPlan) {

        String sep = "═".repeat(52);
        System.out.println();
        System.out.println(sep);
        System.out.println("ACCOUNT AGGREGATION COMPLETE");
        System.out.println(sep);
        System.out.printf("Pages scraped    : %d%n", pages);
        System.out.printf("Total accounts   : %d%n", total);
        System.out.printf("Columns          : %s%n",
                (columns == null || columns.isEmpty()) ? "(none detected)" : String.join(", ", columns));
        System.out.printf("Output file      : %s%n",
                outputFile != null ? outputFile : "(CSV write failed — see console above)");
        System.out.printf("Token usage*     : input=%d ($%.6f) | output=%d ($%.6f) | total=$%.6f%n",
                usage.inputTokens(), usage.inputCostUsd(),
                usage.outputTokens(), usage.outputCostUsd(),
                usage.totalCostUsd());
        if (usedPlan) {
            System.out.println("  * Covers Phase 3/4 (aggregation only — Phase 1 was in Step 1).");
        } else {
            System.out.println("  * Covers Phase 1 (video) + Phase 3/4 (aggregation).");
        }
        System.out.println("    Navigation phase (AgentLoop) tokens printed above by pipeline.");
        System.out.println(sep);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void printSeparator(String title) {
        System.out.println();
        System.out.println("────────────────────────────────────────────────────");
        System.out.println(title);
        System.out.println("────────────────────────────────────────────────────");
    }

    private static void printUsage() {
        System.out.println(
                """
                Account Aggregation Step 2 — Bedrock + Playwright

                Two-step usage (recommended):
                  # Step 1: analyse video, navigate, save plan
                  ./gradlew runAggregationPlan \\
                    --args='--video=recording.mp4 --url=https://app.example.com/users'

                  # Step 2: scrape with real credentials
                  ./gradlew runAggregation \\
                    --args='--plan=./output/aggregation-plan_<ts>.json \\
                            --url=https://app.example.com/users \\
                            --goal=enter "user@email.com" in Email, then click Next, ...'

                One-step usage (legacy):
                  ./gradlew runAggregation \\
                    --args='--video=recording.mp4 --url=https://app.example.com/users \\
                            --goal=enter "user@email.com" in Email, then click Next, ...'

                Arguments:
                  --plan=<path>   Plan JSON from Step 1 (skips video analysis)
                  --video=<path>  MP4 video (required when --plan is not provided)
                  --url=<url>     Target URL (always required)
                  --goal=<text>   Navigation goal with real credentials (highly recommended)

                Configuration: src/main/resources/application.properties
                  aggregation.max.pages=5
                  aggregation.output.dir=./output
                """);
    }

    // ── Argument parsing ──────────────────────────────────────────────────────

    private record ParsedArgs(String video, String plan, String url, String goal) {

        static ParsedArgs parse(String[] args) {
            String video = null;
            String plan  = null;
            String url   = null;
            String goal  = null;

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--video=")) {
                    video = a.substring("--video=".length()).trim();
                } else if (a.startsWith("--plan=")) {
                    plan = a.substring("--plan=".length()).trim();
                } else if (a.startsWith("--url=")) {
                    url = a.substring("--url=".length()).trim();
                } else if (a.startsWith("--goal=")) {
                    StringBuilder g = new StringBuilder(a.substring("--goal=".length()).trim());
                    i = appendMergedWords(args, i, g);
                    goal = g.toString().trim();
                } else if ("--goal".equals(a)) {
                    StringBuilder g = new StringBuilder();
                    i = appendMergedWords(args, i, g);
                    goal = g.toString().trim();
                }
            }

            if (url == null || url.isBlank()) {
                System.err.println("ERROR: --url is required.");
                return null;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                System.err.println("ERROR: --url must start with http:// or https://");
                return null;
            }
            if (plan == null && (video == null || video.isBlank())) {
                System.err.println("ERROR: Either --plan or --video is required.");
                return null;
            }
            return new ParsedArgs(video, plan, url, goal);
        }

        private static int appendMergedWords(String[] args, int goalIdx, StringBuilder out) {
            int j = goalIdx + 1;
            while (j < args.length && !args[j].startsWith("--")) {
                if (!out.isEmpty()) out.append(' ');
                out.append(args[j]);
                j++;
            }
            return j - 1;
        }
    }
}
