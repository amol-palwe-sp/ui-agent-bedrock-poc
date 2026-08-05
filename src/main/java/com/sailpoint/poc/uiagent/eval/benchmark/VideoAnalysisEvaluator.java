package com.sailpoint.poc.uiagent.eval.benchmark;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.eval.report.EvalReport;
import com.sailpoint.poc.uiagent.eval.shared.LlmJudge;
import com.sailpoint.poc.uiagent.video.GoalExtractor;
import com.sailpoint.poc.uiagent.video.TokenDefinition;
import com.sailpoint.poc.uiagent.video.VideoAnalysisPrompt;
import com.sailpoint.poc.uiagent.video.VideoAnalysisRequest;
import com.sailpoint.poc.uiagent.video.VideoAnalysisResult;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;
import com.sailpoint.poc.uiagent.video.VideoToGoalPrompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Main runner for benchmark eval of video analysis.
 *
 * <p>Orchestrates the full pipeline per case:
 * <ol>
 *   <li>Load {@link EvalCase} from benchmarks.json</li>
 *   <li>Extract frames via {@link VideoFrameExtractor}</li>
 *   <li>Build request + prompt</li>
 *   <li>Invoke Claude via {@link BedrockAnthropicClient}</li>
 *   <li>Parse response via {@link VideoAnalysisResult#parse}</li>
 *   <li>Compute metrics via {@link EvalMetrics}</li>
 *   <li>Call {@link LlmJudge} (unless --skip-judge)</li>
 *   <li>Build {@link EvalResult}</li>
 *   <li>Generate {@link EvalReport}</li>
 * </ol>
 *
 * <h2>CLI usage</h2>
 * <pre>
 * ./gradlew runEval -Pargs="--benchmarks=./src/main/resources/eval/benchmarks.json"
 * ./gradlew runEval -Pargs="--case=eval_001"
 * ./gradlew runEval -Pargs="--skip-judge"
 * ./gradlew runEval -Pargs="--output=./eval-reports"
 * </pre>
 */
public final class VideoAnalysisEvaluator {

    public static void main(String[] args) throws Exception {
        PocConfig config = new PocConfig();

        String benchmarksPath = config.evalBenchmarksPath();
        String outputDir      = config.evalOutputDir();
        boolean skipJudge     = config.evalSkipJudge();
        String singleCase     = null;

        for (String arg : args) {
            if (arg.startsWith("--benchmarks=")) benchmarksPath = arg.substring("--benchmarks=".length());
            else if (arg.startsWith("--output="))    outputDir      = arg.substring("--output=".length());
            else if (arg.startsWith("--case="))      singleCase     = arg.substring("--case=".length());
            else if (arg.equals("--skip-judge"))     skipJudge      = true;
        }

        run(benchmarksPath, outputDir, skipJudge, singleCase, config, System.out::println);
    }

    /**
     * Programmatic entry point — called by both the CLI and the UI run handler.
     *
     * @param benchmarksPath path to benchmarks.json
     * @param outputDir      directory to write JSON report
     * @param skipJudge      when true the LLM judge step is skipped
     * @param singleCase     optional case id to run; null = run all
     * @param config         loaded application config
     * @param log            consumer that receives log lines (e.g. System.out::println or SSE queue)
     * @throws Exception on unrecoverable errors (benchmarks not found, etc.)
     */
    public static void run(
            String benchmarksPath,
            String outputDir,
            boolean skipJudge,
            String singleCase,
            PocConfig config,
            Consumer<String> log) throws Exception {

        log.accept("LOG:INFO:Loading benchmark cases from: " + benchmarksPath);
        List<EvalCase> allCases;
        try {
            allCases = EvalCase.loadAll(benchmarksPath);
        } catch (IOException e) {
            log.accept("LOG:ERROR:Cannot load benchmarks.json: " + e.getMessage());
            throw e;
        }

        List<EvalCase> casesToRun = new ArrayList<>();
        for (EvalCase c : allCases) {
            if (singleCase == null || singleCase.equals(c.id())) {
                casesToRun.add(c);
            }
        }

        if (casesToRun.isEmpty()) {
            String msg = "No matching cases found" + (singleCase != null ? " for id=" + singleCase : "");
            log.accept("LOG:ERROR:" + msg);
            throw new IllegalArgumentException(msg);
        }

        // Skip cases whose video is missing or empty — exclude them from the run and the
        // report instead of failing. This lets the dataset be run before every video is recorded.
        List<EvalCase> skipped  = new ArrayList<>();
        List<EvalCase> runnable = new ArrayList<>();
        for (EvalCase c : casesToRun) {
            if (hasUsableVideo(c)) {
                runnable.add(c);
            } else {
                skipped.add(c);
                String vp = (c.videoPath() == null || c.videoPath().isBlank()) ? "<empty>" : c.videoPath();
                log.accept("LOG:WARN:⏭ Skipping " + c.id() + " — video missing or empty: " + vp);
            }
        }
        casesToRun = runnable;
        if (!skipped.isEmpty()) {
            log.accept("LOG:INFO:Skipped " + skipped.size() + " case(s) with missing/empty video paths");
        }

        List<EvalResult> results = new ArrayList<>();

        if (casesToRun.isEmpty()) {
            log.accept("LOG:WARN:No runnable cases — all " + skipped.size()
                    + " matching case(s) had missing/empty video paths");
            EvalReport.generate(results, config.bedrockModelId(), outputDir,
                    line -> log.accept("LOG:INFO:" + line));
            log.accept("LOG:SUCCESS:Done — 0 ran, " + skipped.size() + " skipped. Report saved to " + outputDir);
            log.accept("DONE:0");
            return;
        }

        log.accept("LOG:INFO:Running " + casesToRun.size() + " case(s)"
                + (skipJudge ? " [LLM judge skipped]" : ""));
        log.accept("PROGRESS:0:" + casesToRun.size() + ":Starting...");

        try (BedrockAnthropicClient bedrock = new BedrockAnthropicClient(
                config.bedrock().region(), config.bedrock().profile(),
                config.bedrock().modelId(), config.bedrock().maxTokens(),
                config.bedrock().temperature())) {

            for (int i = 0; i < casesToRun.size(); i++) {
                EvalCase evalCase = casesToRun.get(i);
                log.accept("LOG:INFO:▶ [" + (i + 1) + "/" + casesToRun.size() + "] "
                        + evalCase.id() + " — " + evalCase.description());
                log.accept("PROGRESS:" + i + ":" + casesToRun.size() + ":"
                        + evalCase.id() + " — " + evalCase.description());

                EvalResult result = runCase(evalCase, bedrock, config, skipJudge, log);
                results.add(result);

                String status = result.passed() ? "✅ PASS" : "❌ FAIL";
                log.accept("LOG:" + (result.passed() ? "SUCCESS" : "ERROR") + ":"
                        + evalCase.id() + " → " + String.format("%.3f", result.overallScore())
                        + " " + status
                        + (result.issues().isEmpty() ? "" : " | " + result.issues().size() + " issue(s)"));
            }
        }

        log.accept("PROGRESS:" + casesToRun.size() + ":" + casesToRun.size() + ":Generating report...");
        log.accept("LOG:INFO:Generating eval report...");
        EvalReport.generate(results, config.bedrockModelId(), outputDir,
                line -> log.accept("LOG:INFO:" + line));
        long passed = results.stream().filter(EvalResult::passed).count();
        log.accept("LOG:SUCCESS:Done — " + passed + "/" + results.size() + " passed"
                + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped")
                + ". Report saved to " + outputDir);
        log.accept("DONE:0");
    }

    /**
     * Returns true only when the case points at a real, non-empty video file on disk.
     * Empty/blank paths and missing or zero-byte files are treated as "no video yet"
     * so the case can be skipped rather than failed.
     */
    private static boolean hasUsableVideo(EvalCase c) {
        String vp = c.videoPath();
        if (vp == null || vp.isBlank()) return false;
        try {
            java.nio.file.Path p = Paths.get(vp);
            return Files.exists(p) && Files.isRegularFile(p) && Files.size(p) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Per-case pipeline ─────────────────────────────────────────────────────

    private static EvalResult runCase(
            EvalCase evalCase,
            BedrockAnthropicClient bedrock,
            PocConfig config,
            boolean skipJudge,
            Consumer<String> log) {

        long startMs = System.currentTimeMillis();
        List<String> issues = new ArrayList<>();

        // Step 1-2: Check video file + extract frames
        String videoPath = evalCase.videoPath();
        if (!Files.exists(Paths.get(videoPath))) {
            String msg = "Video file not found: " + videoPath;
            issues.add(msg);
            log.accept("LOG:ERROR:" + msg);
            return buildErrorResult(evalCase, issues, System.currentTimeMillis() - startMs);
        }

        log.accept("LOG:INFO:  Extracting frames from " + videoPath);
        List<byte[]> frames;
        try {
            VideoFrameExtractor extractor = new VideoFrameExtractor(config.video());
            frames = extractor.extractFrames(videoPath);
        } catch (Exception e) {
            issues.add("Frame extraction failed: " + e.getMessage());
            log.accept("LOG:ERROR:  Frame extraction failed: " + e.getMessage());
            return buildErrorResult(evalCase, issues, System.currentTimeMillis() - startMs);
        }

        if (frames.isEmpty()) {
            issues.add("No frames extracted from video");
            log.accept("LOG:ERROR:  No frames extracted from video");
            return buildErrorResult(evalCase, issues, System.currentTimeMillis() - startMs);
        }
        log.accept("LOG:INFO:  Extracted " + frames.size() + " frames — calling Claude...");

        // Steps 3-5: Build prompt, call Claude, parse response
        // PROVISIONING uses VideoToGoalPrompt (goal block) + GoalExtractor
        // AGGREGATION  uses VideoAnalysisPrompt (JSON) + VideoAnalysisResult.parse
        VideoAnalysisResult generated;
        TokenUsage invokeUsage;

        if (evalCase.isProvisioning()) {
            // ── Provisioning path ─────────────────────────────────────────────
            String userPrompt = evalCase.targetUrl().isBlank()
                    ? VideoToGoalPrompt.USER_PROMPT
                    : VideoToGoalPrompt.userPromptWithUrl(evalCase.targetUrl());
            // PLACEHOLDER mode → instruct the model to tokenize entered values as {Field} placeholders.
            String systemPrompt = VideoToGoalPrompt.systemPrompt(evalCase.isPlaceholderMode());

            BedrockAnthropicClient.InvokeResult invokeResult;
            try {
                invokeResult = bedrock.invokeWithMultipleImages(
                        systemPrompt, userPrompt, frames);
            } catch (Exception e) {
                issues.add("Claude call failed: " + e.getMessage());
                log.accept("LOG:ERROR:  Claude call failed: " + e.getMessage());
                return buildErrorResult(evalCase, issues, System.currentTimeMillis() - startMs);
            }
            invokeUsage = invokeResult.usage();
            log.accept("LOG:INFO:  Claude responded — extracting goal block...");

            GoalExtractor.ExtractionResult extraction;
            try {
                extraction = GoalExtractor.extract(invokeResult.text());
            } catch (Exception e) {
                issues.add("Goal extraction failed: " + e.getMessage());
                log.accept("LOG:ERROR:  Goal extraction failed: " + e.getMessage());
                return buildErrorResult(evalCase, issues, System.currentTimeMillis() - startMs);
            }

            if (!extraction.isValid()) {
                issues.addAll(extraction.issues());
                log.accept("LOG:ERROR:  Goal validation failed: " + extraction.issues());
            }

            // Wrap extraction result into VideoAnalysisResult so the rest of the pipeline is uniform
            String navigationGoal = extraction.steps().isEmpty() ? ""
                    : String.join(", then ", extraction.steps());
            generated = new VideoAnalysisResult(
                    VideoAnalysisRequest.TaskType.PROVISIONING,
                    extraction.url() != null ? extraction.url() : evalCase.targetUrl(),
                    navigationGoal,
                    java.util.List.of(),
                    null,
                    extraction.isValid(),
                    java.util.List.copyOf(extraction.issues()));

        } else {
            // ── Aggregation path ──────────────────────────────────────────────
            VideoAnalysisRequest.CredentialMode credMode = evalCase.isPlaceholderMode()
                    ? VideoAnalysisRequest.CredentialMode.PLACEHOLDER
                    : VideoAnalysisRequest.CredentialMode.LITERAL;
            VideoAnalysisRequest request = evalCase.targetUrl().isBlank()
                    ? VideoAnalysisRequest.aggregation(credMode)
                    : VideoAnalysisRequest.aggregation(credMode, evalCase.targetUrl());

            VideoAnalysisPrompt.PromptPair prompts = VideoAnalysisPrompt.build(request);

            BedrockAnthropicClient.InvokeResult invokeResult;
            try {
                invokeResult = bedrock.invokeWithMultipleImages(
                        prompts.systemPrompt(), prompts.userPrompt(), frames);
            } catch (Exception e) {
                issues.add("Claude call failed: " + e.getMessage());
                log.accept("LOG:ERROR:  Claude call failed: " + e.getMessage());
                return buildErrorResult(evalCase, issues, System.currentTimeMillis() - startMs);
            }
            invokeUsage = invokeResult.usage();
            log.accept("LOG:INFO:  Claude responded — parsing JSON schema...");

            try {
                generated = VideoAnalysisResult.parse(invokeResult.text(), request);
            } catch (Exception e) {
                issues.add("Response parse failed: " + e.getMessage());
                log.accept("LOG:ERROR:  Parse failed: " + e.getMessage());
                return buildErrorResult(evalCase, issues, System.currentTimeMillis() - startMs);
            }

            if (!generated.isValid()) {
                issues.addAll(generated.issues());
            }
        }

        // Step 6: Compute automated metrics
        EvalCase.GroundTruth gt = evalCase.groundTruth();

        // Strip the system-appended halt clause — it is added by the prompt/UI and is never
        // part of the GT steps, so it would always be flagged as a hallucination if kept.
        List<String> genSteps = generated.steps().stream()
                .filter(s -> !s.toLowerCase().contains("do not perform any further actions"))
                .collect(java.util.stream.Collectors.toList());

        // Normalize both sides so a focus-click before typing (a UI artifact, not a distinct
        // action) does not count against precision. Applied symmetrically to keep matching fair.
        List<String> gtSteps  = EvalMetrics.collapseFocusClicks(gt.steps());
        genSteps              = EvalMetrics.collapseFocusClicks(genSteps);

        List<String> missingPlaceholders = new ArrayList<>();
        double stepRecall     = EvalMetrics.computeStepRecall(gtSteps, genSteps);
        double stepPrecision  = EvalMetrics.computeStepPrecision(gtSteps, genSteps);
        double stepOrderScore = EvalMetrics.computeStepOrderScore(gtSteps, genSteps);
        double labelAccuracy  = EvalMetrics.computeLabelAccuracy(gtSteps, genSteps);
        double placeholderScore = EvalMetrics.computePlaceholderScore(
                generated.navigationGoal(), genSteps, evalCase.mode(), gt.tokens(), missingPlaceholders);
        double paginationScore = EvalMetrics.computePaginationScore(
                gt.paginationPattern(),
                generated.paginationPattern() != null ? generated.paginationPattern()
                        : new PaginationPattern("unknown", "", ""),
                evalCase.taskType());

        // Record whether each check had anything to check, so the report can say "N/A"
        // instead of printing a score that was never actually earned.
        boolean placeholderApplicable = "PLACEHOLDER".equalsIgnoreCase(evalCase.mode())
                && gt.tokens() != null
                && gt.tokens().stream().anyMatch(t -> t.name() != null && !t.name().isBlank());
        boolean paginationApplicable = "AGGREGATION".equalsIgnoreCase(evalCase.taskType())
                && gt.paginationPattern() != null;

        List<String> hallucinated = EvalMetrics.detectHallucinatedSteps(gtSteps, genSteps);
        List<String> missing      = EvalMetrics.detectMissingSteps(gtSteps, genSteps);
        boolean misordered        = stepOrderScore < 0.8;

        TokenUsage tokenUsage = invokeUsage;

        // Step 7: LLM judge (optional)
        double judgeCorrectness   = 0.0;
        double judgeOrder         = 0.0;
        double judgeHallucination = 0.0;
        double judgeLabel         = 0.0;
        double judgePlaceholder   = 0.0;
        double judgeOverall       = 0.0;
        String judgeReasoning     = "";
        boolean judgeApplicable   = false;

        if (!skipJudge) {
            log.accept("LOG:INFO:  Calling LLM judge...");
            LlmJudge.JudgeResult judgeResult;
            try {
                judgeResult = LlmJudge.judge(evalCase, generated, bedrock);
                judgeApplicable = true;
            } catch (Exception e) {
                issues.add("Judge call failed: " + e.getMessage());
                log.accept("LOG:ERROR:  Judge call failed: " + e.getMessage());
                judgeResult = LlmJudge.JudgeResult.failure(e.getMessage());
            }
            judgeCorrectness   = judgeResult.correctness();
            judgeOrder         = judgeResult.order();
            judgeHallucination = judgeResult.hallucination();
            judgeLabel         = judgeResult.labelQuality();
            judgePlaceholder   = judgeResult.placeholder();
            judgeOverall       = judgeResult.overall();
            judgeReasoning     = judgeResult.reasoning();
            tokenUsage         = tokenUsage.add(judgeResult.tokenUsage());
        }

        // Step 8: Build EvalResult
        String paginationType = generated.paginationPattern() != null
                ? generated.paginationPattern().type() : "";

        EvalResult partialResult = EvalResult.builder()
                .caseId(evalCase.id())
                .uiVariety(evalCase.uiVariety())
                .description(evalCase.description())
                .taskType(evalCase.taskType())
                .mode(evalCase.mode())
                .stepRecall(stepRecall)
                .stepPrecision(stepPrecision)
                .stepOrderScore(stepOrderScore)
                .labelAccuracyScore(labelAccuracy)
                .placeholderScore(placeholderScore)
                .paginationScore(paginationScore)
                .placeholderApplicable(placeholderApplicable)
                .paginationApplicable(paginationApplicable)
                .judgeApplicable(judgeApplicable)
                .overallScore(0.0)  // recomputed below
                .judgeCorrectnessScore(judgeCorrectness)
                .judgeOrderScore(judgeOrder)
                .judgeHallucinationScore(judgeHallucination)
                .judgeLabelScore(judgeLabel)
                .judgePlaceholderScore(judgePlaceholder)
                .judgeOverallScore(judgeOverall)
                .judgeReasoning(judgeReasoning)
                .generatedSteps(genSteps)
                .generatedGoal(generated.navigationGoal())
                .generatedPaginationType(paginationType)
                .hallucinatedSteps(hallucinated)
                .missingSteps(missing)
                .misordered(misordered)
                .missingPlaceholders(missingPlaceholders)
                .issues(issues)
                .durationMs(System.currentTimeMillis() - startMs)
                .tokenUsage(tokenUsage)
                .build();

        double overallScore = EvalMetrics.computeOverallScore(partialResult);

        return EvalResult.builder()
                .caseId(evalCase.id())
                .uiVariety(evalCase.uiVariety())
                .description(evalCase.description())
                .taskType(evalCase.taskType())
                .mode(evalCase.mode())
                .stepRecall(stepRecall)
                .stepPrecision(stepPrecision)
                .stepOrderScore(stepOrderScore)
                .labelAccuracyScore(labelAccuracy)
                .placeholderScore(placeholderScore)
                .paginationScore(paginationScore)
                .placeholderApplicable(placeholderApplicable)
                .paginationApplicable(paginationApplicable)
                .judgeApplicable(judgeApplicable)
                .overallScore(overallScore)
                .judgeCorrectnessScore(judgeCorrectness)
                .judgeOrderScore(judgeOrder)
                .judgeHallucinationScore(judgeHallucination)
                .judgeLabelScore(judgeLabel)
                .judgePlaceholderScore(judgePlaceholder)
                .judgeOverallScore(judgeOverall)
                .judgeReasoning(judgeReasoning)
                .generatedSteps(genSteps)
                .generatedGoal(generated.navigationGoal())
                .generatedPaginationType(paginationType)
                .hallucinatedSteps(hallucinated)
                .missingSteps(missing)
                .misordered(misordered)
                .missingPlaceholders(missingPlaceholders)
                .issues(issues)
                .durationMs(System.currentTimeMillis() - startMs)
                .tokenUsage(tokenUsage)
                .build();
    }

    // ── Error result helper ───────────────────────────────────────────────────

    private static EvalResult buildErrorResult(EvalCase c, List<String> issues, long durationMs) {
        return EvalResult.builder()
                .caseId(c.id())
                .uiVariety(c.uiVariety())
                .description(c.description())
                .taskType(c.taskType())
                .mode(c.mode())
                .issues(issues)
                .durationMs(durationMs)
                .build();
    }
}
