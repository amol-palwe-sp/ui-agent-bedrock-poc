package com.sailpoint.poc.uiagent.eval.benchmark;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.llm.InvokeResult;
import com.sailpoint.poc.uiagent.llm.LlmClient;
import com.sailpoint.poc.uiagent.llm.LlmClientFactory;
import com.sailpoint.poc.uiagent.eval.realtime.ConfidenceEvaluator;
import com.sailpoint.poc.uiagent.eval.realtime.ConfidenceResult;
import com.sailpoint.poc.uiagent.eval.report.EvalReport;
import com.sailpoint.poc.uiagent.eval.shared.LlmJudge;
import com.sailpoint.poc.uiagent.video.GoalExtractor;
import com.sailpoint.poc.uiagent.video.TokenDefinition;
import com.sailpoint.poc.uiagent.video.VideoAnalysisPrompt;
import com.sailpoint.poc.uiagent.video.VideoAnalysisRequest;
import com.sailpoint.poc.uiagent.video.VideoAnalysisResult;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;
import com.sailpoint.poc.uiagent.video.VideoToGoalPrompt;
import com.sailpoint.poc.uiagent.video.relevance.RelevanceResult;
import com.sailpoint.poc.uiagent.video.relevance.VideoRelevanceGate;

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
 *   <li>Run the relevance gate — the only place the system can refuse a video</li>
 *   <li>Build request + prompt</li>
 *   <li>Invoke Claude via the {@link LlmClient} that {@code llm.provider} selects</li>
 *   <li>Parse response via {@link VideoAnalysisResult#parse}</li>
 *   <li>Call {@link LlmJudge} — the sole pass condition for cases with ground truth</li>
 *   <li>Build {@link EvalResult}</li>
 *   <li>Generate {@link EvalReport}</li>
 * </ol>
 *
 * <h2>CLI usage</h2>
 * <pre>
 * ./gradlew runEval -Pargs="--benchmarks=./src/main/resources/eval/stage1-dataset.json"
 * ./gradlew runEval -Pargs="--case=eval_001"
 * ./gradlew runEval -Pargs="--output=./eval-reports"
 * </pre>
 */
public final class VideoAnalysisEvaluator {

    public static void main(String[] args) throws Exception {
        PocConfig config = new PocConfig();

        String benchmarksPath = config.evalBenchmarksPath();
        String outputDir      = config.evalOutputDir();
        boolean skipJudge     = config.evalSkipJudge();
        boolean skipTriage    = false;
        String singleCase     = null;

        for (String arg : args) {
            if (arg.startsWith("--benchmarks=")) benchmarksPath = arg.substring("--benchmarks=".length());
            else if (arg.startsWith("--output="))    outputDir      = arg.substring("--output=".length());
            else if (arg.startsWith("--case="))      singleCase     = arg.substring("--case=".length());
            else if (arg.equals("--skip-judge"))     skipJudge      = true;
            else if (arg.equals("--skip-triage"))    skipTriage     = true;
        }

        run(benchmarksPath, outputDir, skipJudge, skipTriage, singleCase, config, System.out::println);
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
        run(benchmarksPath, outputDir, skipJudge, false, singleCase, config, log);
    }

    /**
     * As {@link #run(String, String, boolean, String, PocConfig, Consumer)}, with control over
     * the triage gate.
     *
     * @param skipTriage when true the relevance gate is not run, and no case can be rejected
     *                   before generation. Cheaper, but it makes the invalid and unworkable
     *                   families unmeasurable, since the gate is what they are testing.
     */
    public static void run(
            String benchmarksPath,
            String outputDir,
            boolean skipJudge,
            boolean skipTriage,
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

        // The judge is the only thing that can pass a scored case, so skipping it does not
        // make the run cheaper — it makes it meaningless. Say so rather than emitting a
        // report full of failures that look like quality problems.
        if (skipJudge) {
            long scoredCount = casesToRun.stream()
                    .filter(c -> c.expectation().hasGroundTruthSteps()).count();
            if (scoredCount > 0) {
                log.accept("LOG:WARN:--skip-judge is set and " + scoredCount + " case(s) have "
                        + "ground truth. The judge is the only pass condition, so those cases "
                        + "will be reported as unscored rather than passed.");
            }
        }

        log.accept("LOG:INFO:Running " + casesToRun.size() + " case(s)"
                + (skipJudge ? " [LLM judge skipped]" : ""));
        log.accept("PROGRESS:0:" + casesToRun.size() + ":Starting...");

        LlmClientFactory clients = LlmClientFactory.from(config);
        log.accept("LOG:INFO:LLM transport: " + clients.describe());

        try (LlmClient llm = clients.create("stage1-eval")) {

            for (int i = 0; i < casesToRun.size(); i++) {
                EvalCase evalCase = casesToRun.get(i);
                log.accept("LOG:INFO:▶ [" + (i + 1) + "/" + casesToRun.size() + "] "
                        + evalCase.id() + " — " + evalCase.description());
                log.accept("PROGRESS:" + i + ":" + casesToRun.size() + ":"
                        + evalCase.id() + " — " + evalCase.description());

                EvalResult result = runCase(evalCase, llm, clients, config, skipJudge, skipTriage, log);
                results.add(result);

                String status = result.passed() ? "✅ PASS" : "❌ FAIL";
                // Unhappy cases are never judged, so report the gate verdict instead of a
                // score, and say "unscored" rather than "0.00" when the judge did not run.
                String score;
                if (!result.scoredAgainstGroundTruth()) {
                    score = result.gateVerdict();
                } else if (result.judgeApplicable() && !result.judgeFailed()) {
                    score = String.format("%.2f", result.judgeOverallScore());
                } else {
                    score = "unscored";
                }
                log.accept("LOG:" + (result.passed() ? "SUCCESS" : "ERROR") + ":"
                        + evalCase.id() + " → " + score
                        + " " + status
                        + (result.failureReason().isBlank() ? "" : " | " + result.failureReason())
                        + (result.issues().isEmpty() ? "" : " | " + result.issues().size() + " issue(s)"));
            }
        }

        log.accept("PROGRESS:" + casesToRun.size() + ":" + casesToRun.size() + ":Generating report...");
        log.accept("LOG:INFO:Generating eval report...");
        EvalReport.generate(results, clients.defaultModelId(), outputDir,
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
            if (!Files.exists(p) || !Files.isRegularFile(p)) return false;
            // A zero-byte file normally means "not recorded yet", so we skip it. For an
            // invalid-video case it is the fixture itself — skipping would quietly drop the
            // very test that checks we handle a corrupt upload gracefully.
            if (Files.size(p) == 0) {
                return c.expectation() == EvalCase.Expectation.INVALID;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Per-case pipeline ─────────────────────────────────────────────────────

    private static EvalResult runCase(
            EvalCase evalCase,
            LlmClient llm,
            LlmClientFactory clients,
            PocConfig config,
            boolean skipJudge,
            boolean skipTriage,
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
        log.accept("LOG:INFO:  Extracted " + frames.size() + " frames");

        // Step 2b: Relevance triage — the same gate the product runs before generation.
        // The eval has to go through it too, otherwise the invalid and unworkable families
        // are testing nothing: the gate is the only place the system can refuse a video.
        RelevanceResult relevance = RelevanceResult.skipped();
        if (!skipTriage) {
            log.accept("LOG:INFO:  Checking whether the video shows a usable UI workflow...");
            relevance = new VideoRelevanceGate(config.relevance(), clients).evaluate(frames);

            if (relevance.isRejected()) {
                log.accept("LOG:INFO:  Video rejected by triage — " + relevance.category()
                        + " (confidence " + relevance.confidence() + "): " + relevance.reason());
                // Stop here exactly as the product does. For an invalid video this is the
                // correct outcome, not an error, so it is not recorded as an issue.
                return buildGateRejectedResult(
                        evalCase, relevance, issues, System.currentTimeMillis() - startMs);
            }
            if (relevance.isUncertain()) {
                log.accept("LOG:WARN:  Triage uncertain (confidence " + relevance.confidence()
                        + "): " + relevance.reason());
            }
        }
        log.accept("LOG:INFO:  Calling Claude...");

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

            InvokeResult invokeResult;
            try {
                invokeResult = llm.invokeWithMultipleImages(
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

            InvokeResult invokeResult;
            try {
                invokeResult = llm.invokeWithMultipleImages(
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

        // Step 6: Normalise the generated steps for reporting.
        // Strip the system-appended halt clause — it is added by the prompt/UI and is never
        // part of the authored plan, so it would read as an invented step if kept.
        List<String> genSteps = generated.steps().stream()
                .filter(s -> !s.toLowerCase().contains("do not perform any further actions"))
                .collect(java.util.stream.Collectors.toList());

        // Triage is part of what a real run costs, so it belongs in the case total.
        TokenUsage tokenUsage = invokeUsage.add(relevance.tokenUsage());

        // Step 6b: For unworkable captures the question is not "are the steps right" but
        // "did it admit it was unsure", so we need the confidence signal to judge the case.
        String confidenceRecommendation = "";
        if (evalCase.expectation() == EvalCase.Expectation.UNWORKABLE) {
            try {
                ConfidenceResult confidence = ConfidenceEvaluator.evaluate(
                        generated, evalCase.taskType(), evalCase.mode(), llm);
                confidenceRecommendation = confidence.recommendation();
                tokenUsage = tokenUsage.add(confidence.tokenUsage());
                log.accept("LOG:INFO:  Confidence: " + confidenceRecommendation
                        + " (" + confidence.confidenceScore() + ")");
            } catch (Exception e) {
                issues.add("Confidence check failed: " + e.getMessage());
                log.accept("LOG:ERROR:  Confidence check failed: " + e.getMessage());
            }
        }

        // Step 7: LLM judge — the only thing that decides a scored case.
        double judgeCorrectness   = 0.0;
        double judgeOrder         = 0.0;
        double judgeHallucination = 0.0;
        double judgeOverall       = 0.0;
        boolean judgeTestPassed   = false;
        boolean judgeFailed       = false;
        String judgeReasoning     = "";
        boolean judgeApplicable   = false;
        List<String> judgeIssues  = new ArrayList<>();
        List<String> hallucinated = new ArrayList<>();
        List<String> missing      = new ArrayList<>();

        // The judge compares against known-good steps, so it has nothing to work with on a
        // case whose correct answer was "produce nothing".
        boolean judgeable = evalCase.expectation().hasGroundTruthSteps();
        if (!skipJudge && judgeable) {
            log.accept("LOG:INFO:  Calling LLM judge...");
            LlmJudge.JudgeResult judgeResult = LlmJudge.judge(evalCase, generated, llm);
            judgeApplicable    = true;
            judgeCorrectness   = judgeResult.correctness();
            judgeOrder         = judgeResult.order();
            judgeHallucination = judgeResult.hallucination();
            judgeOverall       = judgeResult.overall();
            judgeTestPassed    = judgeResult.testPassed();
            judgeFailed        = judgeResult.judgeFailed();
            judgeReasoning     = judgeResult.reasoning();
            judgeIssues        = judgeResult.issues();
            // The judge quotes its own evidence; these are no longer computed by word overlap.
            hallucinated       = judgeResult.hallucinatedSteps();
            missing            = judgeResult.missingSteps();
            tokenUsage         = tokenUsage.add(judgeResult.tokenUsage());

            if (judgeFailed) {
                issues.add("Judge unavailable: " + judgeReasoning);
                log.accept("LOG:ERROR:  Judge unavailable — case cannot be scored: " + judgeReasoning);
            } else {
                for (String ji : judgeIssues) {
                    log.accept("LOG:WARN:  Judge self-check: " + ji);
                }
            }
        }

        // Step 8: Build EvalResult
        String paginationType = generated.paginationPattern() != null
                ? generated.paginationPattern().type() : "";

        return EvalResult.builder()
                .caseId(evalCase.id())
                .uiVariety(evalCase.uiVariety())
                .description(evalCase.description())
                .taskType(evalCase.taskType())
                .mode(evalCase.mode())
                .judgeApplicable(judgeApplicable)
                .expectation(evalCase.expectation())
                .gateVerdict(relevance.verdict().name())
                .gateCategory(relevance.category().name())
                .gateConfidence(relevance.confidence())
                .gateReason(relevance.reason())
                .expectedRejection(evalCase.expectedRejection())
                .confidenceRecommendation(confidenceRecommendation)
                .judgeCorrectnessScore(judgeCorrectness)
                .judgeOrderScore(judgeOrder)
                .judgeHallucinationScore(judgeHallucination)
                .judgeOverallScore(judgeOverall)
                .judgeTestPassed(judgeTestPassed)
                .judgeFailed(judgeFailed)
                .judgeIssues(judgeIssues)
                .judgeReasoning(judgeReasoning)
                .generatedSteps(genSteps)
                .generatedGoal(generated.navigationGoal())
                .generatedPaginationType(paginationType)
                .hallucinatedSteps(hallucinated)
                .missingSteps(missing)
                .issues(issues)
                .durationMs(System.currentTimeMillis() - startMs)
                .tokenUsage(tokenUsage)
                .build();
    }

    // ── Result helpers ────────────────────────────────────────────────────────

    /**
     * Result for a case the triage gate turned away before generation.
     *
     * <p>Not marked as crashed: refusing is a real verdict, and for an invalid video it is the
     * correct one. Whether it counts as a pass is decided by the case's expectation, so a
     * wrongly rejected happy video still fails here.
     */
    private static EvalResult buildGateRejectedResult(
            EvalCase c, RelevanceResult relevance, List<String> issues, long durationMs) {
        return EvalResult.builder()
                .caseId(c.id())
                .uiVariety(c.uiVariety())
                .description(c.description())
                .taskType(c.taskType())
                .mode(c.mode())
                .expectation(c.expectation())
                .gateVerdict(relevance.verdict().name())
                .gateCategory(relevance.category().name())
                .gateConfidence(relevance.confidence())
                .gateReason(relevance.reason())
                .expectedRejection(c.expectedRejection())
                .issues(issues)
                .durationMs(durationMs)
                .tokenUsage(relevance.tokenUsage())
                .build();
    }

    private static EvalResult buildErrorResult(EvalCase c, List<String> issues, long durationMs) {
        return EvalResult.builder()
                .caseId(c.id())
                .uiVariety(c.uiVariety())
                .description(c.description())
                .taskType(c.taskType())
                .mode(c.mode())
                .expectation(c.expectation())
                // An error is not a refusal. Without this an invalid case would "pass" by
                // crashing, which is the one way of producing no steps that must not count.
                .crashed(true)
                .issues(issues)
                .durationMs(durationMs)
                .build();
    }
}
