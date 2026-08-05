package com.sailpoint.poc.uiagent.eval.benchmark;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.pipeline.AgentPipeline;
import com.sailpoint.poc.uiagent.pipeline.PipelineConfig;
import com.sailpoint.poc.uiagent.pipeline.PipelineResult;
import com.sailpoint.poc.uiagent.pipeline.ProgressListener;
import com.sailpoint.poc.uiagent.video.VideoAnalysisPrompt;
import com.sailpoint.poc.uiagent.video.VideoAnalysisRequest;
import com.sailpoint.poc.uiagent.video.VideoAnalysisResult;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Full aggregation eval harness — video-to-script plus live execution, repeated N times per system.
 *
 * <p>Produces consistency data for the AI team: per-run script-gen metrics, execution
 * completeness (via the expected-total oracle), and combined cost across all systems.
 *
 * <h2>Report structure</h2>
 * <ul>
 *   <li>Hard failures (video parse error, pipeline exception) are excluded from statistics
 *       and tallied separately as {@code runsFailed} with their {@code errorType}.</li>
 *   <li>Soft failures (agent completed but scraped fewer rows than the oracle expected)
 *       are counted in statistics — they are meaningful data points, not harness errors.</li>
 * </ul>
 *
 * <h2>CLI usage</h2>
 * <pre>
 * ./gradlew runFullAggregationEval
 * ./gradlew runFullAggregationEval -Pargs="--config=./path/to/aggregation-full-eval.json"
 * ./gradlew runFullAggregationEval -Pargs="--output=./eval-reports --system=coupa_uat_users"
 * </pre>
 */
public final class AggregationFullEvaluator {

    private AggregationFullEvaluator() {}

    // ── CLI entry point ───────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        PocConfig config = new PocConfig();

        String configPath  = "./src/main/resources/eval/aggregation-full-eval.json";
        String outputDir   = config.evalOutputDir();
        String singleSystem = null;

        for (String arg : args) {
            if (arg.startsWith("--config="))  configPath   = arg.substring("--config=".length());
            else if (arg.startsWith("--output="))  outputDir   = arg.substring("--output=".length());
            else if (arg.startsWith("--system=")) singleSystem = arg.substring("--system=".length());
        }

        run(configPath, outputDir, singleSystem, config, System.out::println);
    }

    // ── Programmatic entry point ──────────────────────────────────────────────

    /**
     * Runs the full eval harness.
     *
     * @param configPath   path to {@code aggregation-full-eval.json}
     * @param outputDir    directory to write the JSON report
     * @param singleSystem optional system id to run; null = run all
     * @param config       loaded application config
     * @param log          consumer for log lines
     */
    public static void run(
            String configPath,
            String outputDir,
            String singleSystem,
            PocConfig config,
            Consumer<String> log) throws Exception {

        log.accept("LOG:INFO:Loading full eval config from: " + configPath);
        FullEvalConfig evalConfig;
        try {
            evalConfig = FullEvalConfig.loadAll(configPath);
        } catch (IOException e) {
            log.accept("LOG:ERROR:Cannot load eval config: " + e.getMessage());
            throw e;
        }

        List<FullEvalCase> systemsToRun = new ArrayList<>();
        for (FullEvalCase sys : evalConfig.systems()) {
            if (singleSystem == null || singleSystem.equals(sys.id())) {
                systemsToRun.add(sys);
            }
        }

        if (systemsToRun.isEmpty()) {
            String msg = "No matching systems found"
                    + (singleSystem != null ? " for id=" + singleSystem : "");
            log.accept("LOG:ERROR:" + msg);
            throw new IllegalArgumentException(msg);
        }

        log.accept("LOG:INFO:Running " + systemsToRun.size() + " system(s), "
                + evalConfig.defaultRunsPerSystem() + " runs each (default)");

        // Single shared Bedrock client for all video-analysis calls across all systems/runs.
        // Pipeline executions open their own client internally.
        try (BedrockAnthropicClient bedrock = new BedrockAnthropicClient(
                config.bedrock().region(), config.bedrock().profile(),
                config.bedrock().modelId(), config.bedrock().maxTokens(),
                config.bedrock().temperature())) {

            JSONArray systemsJson = new JSONArray();
            int grandTotalAttempted = 0;
            int grandTotalFailed    = 0;
            double grandTotalCostUsd = 0.0;
            TokenUsage grandVideoUsage = TokenUsage.ZERO;
            TokenUsage grandExecUsage  = TokenUsage.ZERO;

            for (FullEvalCase system : systemsToRun) {
                int runsForSystem = system.effectiveRuns(evalConfig.defaultRunsPerSystem());
                log.accept("LOG:INFO:▶ System [" + system.id() + "] — "
                        + system.description() + " (" + runsForSystem + " runs)");

                SystemRunResult result = runSystem(
                        system, runsForSystem, bedrock, config, log);

                systemsJson.put(result.toJson());
                grandTotalAttempted += result.runsAttempted();
                grandTotalFailed    += result.runsFailed();
                grandTotalCostUsd   += result.totalCostUsd();
                grandVideoUsage      = grandVideoUsage.add(result.totalVideoUsage());
                grandExecUsage       = grandExecUsage.add(result.totalExecUsage());

                log.accept("LOG:INFO:  → " + result.runsSucceeded() + "/" + result.runsAttempted()
                        + " succeeded, cost=$" + String.format("%.4f", result.totalCostUsd()));
            }

            JSONObject report = new JSONObject();
            report.put("runAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            report.put("modelId", config.bedrockModelId());
            report.put("config", new JSONObject().put("defaultRunsPerSystem", evalConfig.defaultRunsPerSystem()));
            report.put("systems", systemsJson);

            TokenUsage grandTotalTokens = grandVideoUsage.add(grandExecUsage);
            JSONObject grandTotal = new JSONObject();
            grandTotal.put("totalRunsAttempted", grandTotalAttempted);
            grandTotal.put("totalRunsFailed",    grandTotalFailed);
            grandTotal.put("totalCostUsd",       round4(grandTotalCostUsd));
            grandTotal.put("tokens", new JSONObject()
                    .put("videoAnalysis", tokenUsageToJson(grandVideoUsage))
                    .put("execution",     tokenUsageToJson(grandExecUsage))
                    .put("total",         tokenUsageToJson(grandTotalTokens)));
            report.put("grandTotal", grandTotal);

            writeReport(report, outputDir, log);
        }
    }

    // ── Per-system orchestration ──────────────────────────────────────────────

    private static SystemRunResult runSystem(
            FullEvalCase system,
            int totalRuns,
            BedrockAnthropicClient bedrock,
            PocConfig config,
            Consumer<String> log) {

        List<JSONObject> runRows       = new ArrayList<>();
        List<String>     failureReasons = new ArrayList<>();
        double           totalCostUsd  = 0.0;
        TokenUsage       totalVideoUsage = TokenUsage.ZERO;
        TokenUsage       totalExecUsage  = TokenUsage.ZERO;

        // Stats accumulators (succeeded runs only)
        List<Double> overallScores  = new ArrayList<>();
        List<Integer> scrapedCounts = new ArrayList<>();
        List<Boolean> matchedFlags  = new ArrayList<>();
        int oracleAvailableCount    = 0;

        for (int runIdx = 1; runIdx <= totalRuns; runIdx++) {
            log.accept("LOG:INFO:  Run " + runIdx + "/" + totalRuns + " — " + system.id());

            PerRunResult perRun = executeSingleRun(system, runIdx, bedrock, config, log);
            totalCostUsd   += perRun.costUsd();
            totalVideoUsage = totalVideoUsage.add(perRun.videoUsage());
            totalExecUsage  = totalExecUsage.add(perRun.execUsage());
            runRows.add(perRun.toJson(runIdx));

            if (perRun.failed()) {
                String reason = perRun.errorType();
                failureReasons.add(reason);
                log.accept("LOG:WARNING:    Run " + runIdx + " FAILED: " + reason
                        + " — " + perRun.errorMessage());
            } else {
                overallScores.add(perRun.overallScore());
                scrapedCounts.add(perRun.scraped());
                matchedFlags.add(perRun.matched());
                if (perRun.oracleTotal() != null) oracleAvailableCount++;
            }
        }

        int runsAttempted = totalRuns;
        int runsFailed    = failureReasons.size();
        int runsSucceeded = runsAttempted - runsFailed;

        JSONObject summary = buildSummary(
                runsAttempted, runsSucceeded, runsFailed, failureReasons,
                overallScores, scrapedCounts, matchedFlags,
                oracleAvailableCount, totalCostUsd, totalVideoUsage, totalExecUsage);

        return new SystemRunResult(system.id(), runsAttempted, runsSucceeded, runsFailed,
                failureReasons, runRows, summary, totalCostUsd, totalVideoUsage, totalExecUsage);
    }

    // ── Single-run execution ──────────────────────────────────────────────────

    private static PerRunResult executeSingleRun(
            FullEvalCase system,
            int runIdx,
            BedrockAnthropicClient bedrock,
            PocConfig config,
            Consumer<String> log) {

        TokenUsage videoUsage = TokenUsage.ZERO;

        // Phase 1: extract frames
        String videoPath = system.videoPath();
        if (!Files.exists(Paths.get(videoPath))) {
            return PerRunResult.failure("VIDEO_NOT_FOUND",
                    "Video file not found: " + videoPath, TokenUsage.ZERO);
        }

        List<byte[]> frames;
        try {
            VideoFrameExtractor extractor = new VideoFrameExtractor(config.video());
            frames = extractor.extractFrames(videoPath);
        } catch (Exception e) {
            return PerRunResult.failure("FRAME_EXTRACTION_ERROR",
                    e.getMessage(), TokenUsage.ZERO);
        }

        if (frames.isEmpty()) {
            return PerRunResult.failure("NO_FRAMES",
                    "No frames extracted from video", TokenUsage.ZERO);
        }

        int frameCount = frames.size();
        log.accept("LOG:INFO:    Frames: " + frameCount + " — calling Claude for script...");

        // Phase 2: video analysis → navigation goal + pagination pattern
        VideoAnalysisRequest request = system.targetUrl().isBlank()
                ? VideoAnalysisRequest.aggregation(VideoAnalysisRequest.CredentialMode.PLACEHOLDER)
                : VideoAnalysisRequest.aggregation(
                        VideoAnalysisRequest.CredentialMode.PLACEHOLDER, system.targetUrl());

        VideoAnalysisPrompt.PromptPair prompts = VideoAnalysisPrompt.build(request);

        BedrockAnthropicClient.InvokeResult invokeResult;
        try {
            invokeResult = bedrock.invokeWithMultipleImages(
                    prompts.systemPrompt(), prompts.userPrompt(), frames);
        } catch (Exception e) {
            return PerRunResult.failure("CLAUDE_CALL_ERROR", e.getMessage(), videoUsage);
        }
        videoUsage = invokeResult.usage();

        VideoAnalysisResult derived;
        try {
            derived = VideoAnalysisResult.parse(invokeResult.text(), request);
        } catch (Exception e) {
            return PerRunResult.failure("VIDEO_PARSE_ERROR",
                    e.getMessage(), videoUsage);
        }

        // Script-gen metrics against ground truth
        EvalCase.GroundTruth gt       = system.groundTruth();
        List<String>         genSteps = derived.steps().stream()
                .filter(s -> !s.toLowerCase().contains("do not perform any further actions"))
                .collect(Collectors.toList());

        // Normalize both sides so a focus-click before typing (a UI artifact, not a distinct
        // action) does not count against precision. Applied symmetrically to keep matching fair.
        List<String>         gtSteps  = EvalMetrics.collapseFocusClicks(gt.steps());
        genSteps                      = EvalMetrics.collapseFocusClicks(genSteps);

        double stepRecall     = EvalMetrics.computeStepRecall(gtSteps, genSteps);
        double stepPrecision  = EvalMetrics.computeStepPrecision(gtSteps, genSteps);
        double stepOrderScore = EvalMetrics.computeStepOrderScore(gtSteps, genSteps);
        double labelAccuracy  = EvalMetrics.computeLabelAccuracy(gtSteps, genSteps);
        double placeholderScore = EvalMetrics.computePlaceholderScore(
                derived.navigationGoal(), genSteps, "PLACEHOLDER", gt.tokens(), null);
        double paginationScore = EvalMetrics.computePaginationScore(
                gt.paginationPattern(),
                derived.paginationPattern() != null
                        ? derived.paginationPattern()
                        : new PaginationPattern("unknown", "", ""),
                "AGGREGATION");

        // Compute overallScore via a minimal EvalResult proxy
        EvalResult proxy = EvalResult.builder()
                .taskType("AGGREGATION").mode("PLACEHOLDER")
                .stepRecall(stepRecall).stepPrecision(stepPrecision)
                .stepOrderScore(stepOrderScore).labelAccuracyScore(labelAccuracy)
                .placeholderScore(placeholderScore).paginationScore(paginationScore)
                .overallScore(0.0).build();
        double overallScore = EvalMetrics.computeOverallScore(proxy);

        log.accept("LOG:INFO:    Script-gen score: " + String.format("%.3f", overallScore)
                + " — executing pipeline...");

        // Phase 3: build PipelineConfig + execute
        PaginationPattern paginationPattern = derived.paginationPattern() != null
                ? derived.paginationPattern()
                : buildFallbackPagination(gt.paginationPattern());

        PipelineConfig pipelineConfig = PipelineConfig.builder()
                .taskType(PipelineConfig.TaskType.AGGREGATION)
                .startUrl(system.targetUrl())
                .goal(derived.navigationGoal())
                .tokenValues(buildTokenValues(system.credentials()))
                .paginationPattern(paginationPattern)
                .bedrockConfig(config.bedrock())
                .browserConfig(config.browser())
                .agentConfig(config.agent())
                .aggregationConfig(config.aggregation())
                .build();

        PipelineResult pipelineResult;
        try {
            pipelineResult = AgentPipeline.run(pipelineConfig, ProgressListener.SILENT);
        } catch (Exception e) {
            return PerRunResult.failure("PIPELINE_EXCEPTION", e.getMessage(), videoUsage);
        }

        if (!pipelineResult.success()) {
            return PerRunResult.failure("PIPELINE_FAILED",
                    pipelineResult.errorMessage(), videoUsage);
        }

        TokenUsage execUsage = pipelineResult.totalUsage();

        int     scraped      = pipelineResult.rowsScraped();
        Integer pageTotal    = pipelineResult.expectedTotalAccounts();  // from JS/LLM on page
        Integer manualTotal  = system.expectedTotalAccounts();           // from config
        // Page oracle wins; manual config is the fallback when the page shows nothing.
        Integer effectiveTotal = pageTotal != null ? pageTotal : manualTotal;
        String  oracleSource   = pageTotal   != null ? "PAGE"
                               : manualTotal != null ? "MANUAL" : "NONE";
        Boolean matched = effectiveTotal != null ? scraped == effectiveTotal : null;
        int     pages       = pipelineResult.pagesScraped();
        String  exitReason  = pipelineResult.exitReason() != null
                ? pipelineResult.exitReason().name() : "UNKNOWN";

        ScriptGenMetrics sgMetrics = new ScriptGenMetrics(overallScore, stepRecall,
                stepPrecision, stepOrderScore, labelAccuracy, paginationScore,
                genSteps, derived.navigationGoal());

        double costUsd = videoUsage.add(execUsage).totalCostUsd();
        log.accept("LOG:INFO:    Scraped=" + scraped
                + (effectiveTotal != null ? "/" + effectiveTotal + " [" + oracleSource + "]" : "")
                + " matched=" + (matched != null ? matched : "N/A")
                + " cost=$" + String.format("%.4f", costUsd)
                + " (video=$" + String.format("%.4f", videoUsage.totalCostUsd())
                + " exec=$" + String.format("%.4f", execUsage.totalCostUsd()) + ")");

        return PerRunResult.success(frameCount, sgMetrics,
                scraped, effectiveTotal, matched, oracleSource, pages, exitReason,
                videoUsage, execUsage);
    }

    // ── Statistics helpers ────────────────────────────────────────────────────

    private static JSONObject buildSummary(
            int runsAttempted, int runsSucceeded, int runsFailed,
            List<String> failureReasons,
            List<Double> overallScores,
            List<Integer> scrapedCounts,
            List<Boolean> matchedFlags,
            int oracleAvailableCount,
            double totalCostUsd,
            TokenUsage totalVideoUsage,
            TokenUsage totalExecUsage) {

        JSONObject summary = new JSONObject();
        summary.put("runsAttempted", runsAttempted);
        summary.put("runsSucceeded", runsSucceeded);
        summary.put("runsFailed",    runsFailed);

        if (!failureReasons.isEmpty()) {
            // Deduplicate failure reasons while preserving count as values
            JSONObject reasons = new JSONObject();
            for (String r : failureReasons) {
                reasons.put(r, reasons.optInt(r, 0) + 1);
            }
            summary.put("failureReasonCounts", reasons);
        }

        if (runsSucceeded > 0) {
            double meanScore  = mean(overallScores);
            double stdScore   = stddev(overallScores, meanScore);
            JSONObject sg = new JSONObject();
            sg.put("meanOverallScore",   round4(meanScore));
            sg.put("stddevOverallScore", round4(stdScore));
            summary.put("scriptGen", sg);

            double meanScraped  = meanInt(scrapedCounts);
            double stdScraped   = stddevInt(scrapedCounts, meanScraped);

            // passRate is computed only over runs where a total was available (page or manual).
            // When no run had an oracle, passRate is reported as null (N/A) rather than 0,
            // which would falsely imply the agent failed the completeness check.
            long verifiableCount = matchedFlags.stream().filter(Objects::nonNull).count();
            long passCount       = matchedFlags.stream().filter(Boolean.TRUE::equals).count();
            double oracleRate    = (double) oracleAvailableCount / runsSucceeded;

            JSONObject exec = new JSONObject();
            if (verifiableCount > 0) {
                exec.put("passRate", round4((double) passCount / verifiableCount));
            } else {
                exec.put("passRate", JSONObject.NULL);
                exec.put("passRateNote", "oracle unavailable — set expectedTotalAccounts in config");
            }
            exec.put("meanScraped",          round2(meanScraped));
            exec.put("stddevScraped",        round2(stdScraped));
            exec.put("oracleAvailableRate",  round4(oracleRate));
            summary.put("execution", exec);

            summary.put("meanCostUsd", round4(totalCostUsd / runsSucceeded));

            // Per-phase token breakdown: videoAnalysis = one-time cost per customer,
            // execution = recurring cost per run.
            TokenUsage totalTokens = totalVideoUsage.add(totalExecUsage);
            summary.put("tokens", new JSONObject()
                    .put("videoAnalysis", new JSONObject()
                            .put("totalInputTokens",  totalVideoUsage.inputTokens())
                            .put("totalOutputTokens", totalVideoUsage.outputTokens())
                            .put("meanCostUsd",        round4(totalVideoUsage.totalCostUsd() / runsSucceeded)))
                    .put("execution", new JSONObject()
                            .put("totalInputTokens",  totalExecUsage.inputTokens())
                            .put("totalOutputTokens", totalExecUsage.outputTokens())
                            .put("meanCostUsd",        round4(totalExecUsage.totalCostUsd() / runsSucceeded)))
                    .put("total", new JSONObject()
                            .put("totalInputTokens",  totalTokens.inputTokens())
                            .put("totalOutputTokens", totalTokens.outputTokens())
                            .put("meanCostUsd",        round4(totalTokens.totalCostUsd() / runsSucceeded))));
        }

        return summary;
    }

    private static double mean(List<Double> vals) {
        if (vals.isEmpty()) return 0.0;
        return vals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private static double stddev(List<Double> vals, double mean) {
        if (vals.size() < 2) return 0.0;
        double variance = vals.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average().orElse(0.0);
        return Math.sqrt(variance);
    }

    private static double meanInt(List<Integer> vals) {
        if (vals.isEmpty()) return 0.0;
        return vals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    private static double stddevInt(List<Integer> vals, double mean) {
        if (vals.size() < 2) return 0.0;
        double variance = vals.stream()
                .mapToDouble(v -> (v - mean) * (v - mean))
                .average().orElse(0.0);
        return Math.sqrt(variance);
    }

    private static JSONObject tokenUsageToJson(TokenUsage u) {
        return new JSONObject()
                .put("inputTokens",  u.inputTokens())
                .put("outputTokens", u.outputTokens())
                .put("costUsd",      round4(u.totalCostUsd()));
    }

    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
    private static double round2(double v) { return Math.round(v * 100.0)   / 100.0; }

    /**
     * Placeholder names Claude's video analysis has been observed to generate for the
     * login-identifier field, depending on what label it sees on screen (e.g. "Email",
     * "Username or Email Address", "User ID"). The same video can yield a different name
     * across runs, so every alias is mapped to the same real value rather than picking one.
     */
    private static final List<String> IDENTIFIER_ALIASES =
            List.of("Email", "Username", "UserId", "User", "Login", "UserID");

    /** Placeholder names observed for the password field. */
    private static final List<String> SECRET_ALIASES = List.of("Password", "Pwd");

    /**
     * Builds the token-substitution map by expanding each credential value across every
     * alias name it might be referenced by in a given run's derived navigation goal.
     */
    private static Map<String, String> buildTokenValues(FullEvalCase.Credentials credentials) {
        Map<String, String> tokenValues = new HashMap<>();
        for (String alias : IDENTIFIER_ALIASES) {
            tokenValues.put(alias, credentials.identifier());
        }
        for (String alias : SECRET_ALIASES) {
            tokenValues.put(alias, credentials.secret());
        }
        return tokenValues;
    }

    private static PaginationPattern buildFallbackPagination(EvalCase.PaginationGT gt) {
        if (gt != null) {
            return new PaginationPattern(gt.type(), gt.description(), gt.selectorHint());
        }
        return new PaginationPattern("next_button", "", "");
    }

    // ── Report file writer ────────────────────────────────────────────────────

    private static void writeReport(JSONObject report, String outputDir,
                                    Consumer<String> log) throws IOException {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        Path file = dir.resolve("full-agg-eval-report_" + ts + ".json");
        Files.writeString(file, report.toString(2));
        log.accept("LOG:SUCCESS:Full-agg eval report written to: " + file.toAbsolutePath());
    }

    // ── Data carriers ─────────────────────────────────────────────────────────

    private record ScriptGenMetrics(
            double overallScore,
            double stepRecall,
            double stepPrecision,
            double stepOrderScore,
            double labelAccuracy,
            double paginationScore,
            List<String> generatedSteps,
            String generatedGoal) {

        JSONObject toJson() {
            JSONArray stepsArr = new JSONArray();
            if (generatedSteps != null) generatedSteps.forEach(stepsArr::put);
            return new JSONObject()
                    .put("overallScore",    round4(overallScore))
                    .put("stepRecall",      round4(stepRecall))
                    .put("stepPrecision",   round4(stepPrecision))
                    .put("stepOrderScore",  round4(stepOrderScore))
                    .put("labelAccuracy",   round4(labelAccuracy))
                    .put("paginationScore", round4(paginationScore))
                    .put("generatedGoal",   generatedGoal != null ? generatedGoal : "")
                    .put("generatedSteps",  stepsArr);
        }
    }

    private record PerRunResult(
            boolean failed,
            String errorType,
            String errorMessage,
            int frameCount,
            ScriptGenMetrics scriptGen,
            int scraped,
            Integer oracleTotal,
            Boolean matched,
            String oracleSource,
            int pages,
            String exitReason,
            TokenUsage videoUsage,   // Phase 1: video frames → script (one-time per customer)
            TokenUsage execUsage) {  // Phase 2: agent loop + table detection + pagination

        double costUsd() { return videoUsage.add(execUsage).totalCostUsd(); }

        static PerRunResult failure(String errorType, String message, TokenUsage videoUsage) {
            return new PerRunResult(true, errorType, message,
                    0, null, 0, null, null, "NONE", 0, null,
                    videoUsage != null ? videoUsage : TokenUsage.ZERO,
                    TokenUsage.ZERO);
        }

        static PerRunResult success(int frameCount, ScriptGenMetrics sg,
                                    int scraped, Integer oracleTotal, Boolean matched,
                                    String oracleSource,
                                    int pages, String exitReason,
                                    TokenUsage videoUsage, TokenUsage execUsage) {
            return new PerRunResult(false, null, null,
                    frameCount, sg, scraped, oracleTotal, matched, oracleSource,
                    pages, exitReason, videoUsage, execUsage);
        }

        double overallScore() { return scriptGen != null ? scriptGen.overallScore() : 0.0; }

        JSONObject toJson(int runNumber) {
            JSONObject o = new JSONObject();
            o.put("run", runNumber);

            TokenUsage total = videoUsage.add(execUsage);
            o.put("tokens", new JSONObject()
                    .put("videoAnalysis", tokenUsageToJson(videoUsage))
                    .put("execution",     tokenUsageToJson(execUsage))
                    .put("total",         tokenUsageToJson(total)));
            o.put("costUsd", round4(total.totalCostUsd()));

            if (failed) {
                o.put("status",       "FAILED");
                o.put("errorType",    errorType   != null ? errorType   : "UNKNOWN");
                o.put("errorMessage", errorMessage != null ? errorMessage : "");
            } else {
                o.put("status",      "SUCCESS");
                o.put("frameCount",  frameCount);
                o.put("scriptGen",   scriptGen.toJson());

                JSONObject exec = new JSONObject();
                exec.put("scraped",       scraped);
                exec.put("oracleSource",  oracleSource != null ? oracleSource : "NONE");
                if (oracleTotal != null) {
                    exec.put("expectedTotal", oracleTotal);
                    exec.put("matched",       Boolean.TRUE.equals(matched));
                } else {
                    exec.put("expectedTotal", JSONObject.NULL);
                    exec.put("matched",       JSONObject.NULL);
                }
                exec.put("pages",      pages);
                exec.put("exitReason", exitReason != null ? exitReason : "");
                o.put("execution", exec);
            }

            return o;
        }
    }

    private record SystemRunResult(
            String id,
            int runsAttempted,
            int runsSucceeded,
            int runsFailed,
            List<String> failureReasons,
            List<JSONObject> runs,
            JSONObject summary,
            double totalCostUsd,
            TokenUsage totalVideoUsage,
            TokenUsage totalExecUsage) {

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("id",            id);
            o.put("runsAttempted", runsAttempted);
            o.put("runsSucceeded", runsSucceeded);
            o.put("runsFailed",    runsFailed);

            JSONArray runsArr = new JSONArray();
            runs.forEach(runsArr::put);
            o.put("runs", runsArr);

            o.put("summary", summary);
            return o;
        }
    }
}
