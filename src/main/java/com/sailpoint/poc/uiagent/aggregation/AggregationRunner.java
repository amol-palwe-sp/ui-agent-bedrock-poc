package com.sailpoint.poc.uiagent.aggregation;

import com.sailpoint.poc.uiagent.ActionLogger;
import com.sailpoint.poc.uiagent.AgentLoop;
import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;
import com.sailpoint.poc.uiagent.browser.BrowserSession;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Step 2 of the two-step aggregation pipeline — navigate, detect table, scrape all pages, write CSV.
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
 *             --goal=enter "user@example.com" in Email, then click Next, then enter "password" in password, then click Next'
 * </pre>
 *
 * <h2>One-step workflow (legacy — still supported)</h2>
 * <pre>
 *   ./gradlew runAggregation \
 *     --args='--video=recording.mp4 --url=https://admin.google.com/ac/users \
 *             --goal=enter "user@example.com" in Email, then click Next, then enter "password" in password, then click Next'
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

        PocConfig config = new PocConfig();
        int    maxPages  = config.aggregationMaxPages();
        String outputDir = config.aggregationOutputDir();

        TokenUsage              totalUsage  = TokenUsage.ZERO;
        AggregationVideoAnalysis videoAnalysis;
        TableDetectionResult    tableResult  = null;
        List<Map<String, String>> allRows    = new ArrayList<>();
        int    pagesScraped   = 0;
        String outputFilePath = null;

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
            // One-step path (legacy): video → Claude → extract plan
            printSeparator("Phase 1 — Video Analysis");

            File videoFile = new File(parsed.video());
            if (!videoFile.exists()) {
                System.err.println("ERROR: Video file not found: " + parsed.video());
                return 1;
            }

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

            // Temporary client just for Phase 1 — closed before main try-with-resources opens
            try (BedrockAnthropicClient phase1Bedrock = new BedrockAnthropicClient(
                    config.awsRegion(), config.awsProfile(),
                    config.bedrockModelId(), config.maxTokens(), config.temperature())) {

                System.out.println("Calling Claude for video analysis...");
                InvokeResult videoResult = phase1Bedrock.invokeWithMultipleImages(
                        AggregationVideoPrompt.SYSTEM_PROMPT,
                        AggregationVideoPrompt.userPromptWithUrl(parsed.url()),
                        frames);
                totalUsage = totalUsage.add(videoResult.usage());
                System.out.println("Video analysis tokens: " + videoResult.usage());

                try {
                    videoAnalysis = AggregationVideoPrompt.parse(videoResult.text());
                } catch (IllegalArgumentException e) {
                    System.err.println("ERROR: " + e.getMessage());
                    return 1;
                }
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

        final AggregationVideoAnalysis resolvedAnalysis = videoAnalysis;

        // ── Phases 2-5: browser session ──────────────────────────────────────
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

            // ── Phase 2 — Browser Navigation ─────────────────────────────────
            printSeparator("Phase 2 — Browser Navigation");

            System.out.println("Navigating to: " + parsed.url());
            browser.navigate(parsed.url());
            System.out.println("Current URL: " + browser.currentUrl());
            System.out.println();
            System.out.println("Running AgentLoop with goal...");
            System.out.println("(AgentLoop token usage is printed per step below)");
            System.out.println();

            AgentLoop agentLoop = new AgentLoop(
                    bedrock, browser, actionLogger,
                    config.agentMaxSteps(),
                    effectiveGoal,
                    config.agentNoProgressLimit())
                    .setMultiViewportMaxFrames(config.agentMultiViewportMaxFrames());
            agentLoop.run();

            System.out.println();
            System.out.println("Landing URL: " + browser.currentUrl());

            // ── Phase 3 — Table Detection ─────────────────────────────────────
            printSeparator("Phase 3 — Table Detection");

            AccountAggregator aggregator = new AccountAggregator(browser, bedrock);
            tableResult = aggregator.detectTable();

            if (tableResult == null) {
                System.err.println("ERROR: No table found by JS or Claude. Cannot aggregate accounts.");
                return 1;
            }

            totalUsage = totalUsage.add(aggregator.accumulatedUsage());
            System.out.println("Table selector   : " + tableResult.selector());
            System.out.println("Columns detected : " + tableResult.headers());
            System.out.println("Detected by      : "
                    + (tableResult.detectedByJs() ? "JS (no LLM cost)" : "Claude vision"));

            // ── Phase 4 — Pagination Loop ─────────────────────────────────────
            printSeparator("Phase 4 — Pagination Loop");

            TokenUsage beforePhase4 = aggregator.accumulatedUsage();
            allRows = aggregator.paginationLoop(
                    tableResult,
                    resolvedAnalysis.paginationPattern(),
                    maxPages);
            pagesScraped = aggregator.pagesScraped();

            TokenUsage phase4Usage = aggregator.accumulatedUsage().add(negate(beforePhase4));
            totalUsage = totalUsage.add(phase4Usage);

            // ── Phase 5 — CSV Write ───────────────────────────────────────────
            printSeparator("Phase 5 — CSV Write");

            List<String> effectiveHeaders = resolveHeaders(tableResult.headers(), allRows);
            System.out.printf("Writing %d total accounts to output directory: %s%n",
                    allRows.size(), outputDir);

            try {
                outputFilePath = writeCsv(allRows, effectiveHeaders, outputDir);
                System.out.println("Done. CSV saved.");
            } catch (IOException e) {
                System.err.println("ERROR: CSV write failed: " + e.getMessage());
                System.err.println("Printing rows to console as fallback:");
                printRowsToConsole(allRows, effectiveHeaders);
            }
        }

        // ── Phase 6 — Summary ─────────────────────────────────────────────────
        List<String> summaryHeaders = tableResult != null
                ? resolveHeaders(tableResult.headers(), allRows)
                : List.of();
        printSummary(pagesScraped, allRows.size(), summaryHeaders, outputFilePath, totalUsage,
                parsed.plan() != null);
        return 0;
    }

    // -------------------------------------------------------------------------
    // CSV write
    // -------------------------------------------------------------------------

    private static String writeCsv(
            List<Map<String, String>> rows,
            List<String> headers,
            String outputDirStr) throws IOException {

        Path outputDir = Path.of(outputDirStr);
        Files.createDirectories(outputDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path filePath = outputDir.resolve("accounts_" + timestamp + ".csv");

        try (BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            writer.write(headers.stream()
                    .map(AggregationRunner::csvEscape)
                    .collect(Collectors.joining(",")));
            writer.newLine();

            for (Map<String, String> row : rows) {
                writer.write(headers.stream()
                        .map(h -> csvEscape(row.getOrDefault(h, "")))
                        .collect(Collectors.joining(",")));
                writer.newLine();
            }
        }

        return filePath.toAbsolutePath().toString();
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // -------------------------------------------------------------------------
    // Summary
    // -------------------------------------------------------------------------

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
                columns.isEmpty() ? "(none detected)" : String.join(", ", columns));
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
        System.out.println("    Navigation phase (AgentLoop) tokens are printed above.");
        System.out.println(sep);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static List<String> resolveHeaders(
            List<String> detectedHeaders, List<Map<String, String>> rows) {
        if (detectedHeaders != null && !detectedHeaders.isEmpty()) return detectedHeaders;
        if (!rows.isEmpty()) return new ArrayList<>(rows.get(0).keySet());
        return List.of();
    }

    private static void printRowsToConsole(List<Map<String, String>> rows, List<String> headers) {
        System.out.println(String.join(",", headers));
        for (Map<String, String> row : rows) {
            System.out.println(headers.stream()
                    .map(h -> csvEscape(row.getOrDefault(h, "")))
                    .collect(Collectors.joining(",")));
        }
    }

    private static TokenUsage negate(TokenUsage u) {
        return new TokenUsage(-u.inputTokens(), -u.outputTokens(),
                -u.inputCostUsd(), -u.outputCostUsd());
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
                  aggregation.max.pages=50
                  aggregation.output.dir=./output
                """);
    }

    // -------------------------------------------------------------------------
    // Argument parsing
    // -------------------------------------------------------------------------

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
