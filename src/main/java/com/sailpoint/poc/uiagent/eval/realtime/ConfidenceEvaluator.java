package com.sailpoint.poc.uiagent.eval.realtime;

import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalMetrics;
import com.sailpoint.poc.uiagent.eval.shared.LlmJudge;
import com.sailpoint.poc.uiagent.video.VideoAnalysisResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Real-time confidence scoring for newly uploaded videos.
 *
 * <p>Runs automated pre-checks (no LLM needed) then calls
 * {@link LlmJudge#judgeWithoutGroundTruth} to obtain an AI confidence score.
 * The two signals are combined into a {@link ConfidenceResult}.
 */
public final class ConfidenceEvaluator {

    private ConfidenceEvaluator() {}

    /**
     * Evaluates a generated result and returns a confidence assessment.
     *
     * @param generated the parsed video analysis result
     * @param taskType  "AGGREGATION" or "PROVISIONING"
     * @param mode      "PLACEHOLDER" or "LITERAL"
     * @param bedrock   the Bedrock client for the LLM judge call
     * @return a {@link ConfidenceResult} with score, recommendation, and warnings
     */
    public static ConfidenceResult evaluate(
            VideoAnalysisResult generated,
            String taskType,
            String mode,
            BedrockAnthropicClient bedrock) {

        List<String> warnings            = new ArrayList<>();
        List<String> suspectedHalls      = new ArrayList<>();
        boolean      placeholderCompliant = true;
        List<String> steps               = generated.steps();
        int          stepCount           = steps.size();

        // ── Pre-check 1: step count ────────────────────────────────────────────
        if (stepCount < 2) {
            warnings.add("Step count is " + stepCount + " — suspiciously few steps (expected >= 2)");
            if (stepCount == 0) warnings.add("No steps were extracted — output may be empty");
        } else if (stepCount > 25) {
            warnings.add("Step count is " + stepCount + " — suspiciously high (expected <= 25), likely hallucination");
        }

        // ── Pre-check 2: placeholder compliance ──────────────────────────────
        // Only applies to AGGREGATION + PLACEHOLDER: the prompt explicitly asks Claude
        // to output {Token} syntax. For PROVISIONING, literals are expected — the UI
        // handles tokenization as a post-processing step.
        if ("AGGREGATION".equalsIgnoreCase(taskType) && "PLACEHOLDER".equalsIgnoreCase(mode)) {
            List<String> leaks = EvalMetrics.detectCredentialLeaks(generated.navigationGoal());
            if (!leaks.isEmpty()) {
                placeholderCompliant = false;
                leaks.forEach(leak -> warnings.add("Credential leak detected in output: " + leak));
            }
        }

        // ── Pre-check 3: logical flow ─────────────────────────────────────────
        String goalLower = generated.navigationGoal().toLowerCase();
        boolean hasClickOrType = goalLower.contains("click") || goalLower.contains("type")
                || goalLower.contains("enter") || goalLower.contains("fill");
        if (!hasClickOrType) {
            warnings.add("No click or type step found — output may be missing action steps");
        }

        if ("AGGREGATION".equalsIgnoreCase(taskType)) {
            if (generated.paginationPattern() == null) {
                warnings.add("No pagination pattern found — required for AGGREGATION task type");
            } else if ("unknown".equalsIgnoreCase(generated.paginationPattern().type())) {
                warnings.add("Pagination type is 'unknown' — could not identify pagination mechanism");
            }
        }

        // ── Pre-check 4: step format ──────────────────────────────────────────
        for (String step : steps) {
            if (step.length() > 200) {
                warnings.add("Step longer than 200 characters (likely malformed): " + step.substring(0, 80) + "...");
                break;
            }
        }

        String combinedSteps = String.join(" ", steps);
        if (combinedSteps.matches(".*\\b[1-9]\\d*\\.\\s.*")) {
            warnings.add("Steps appear to contain numbered lists (1. 2. 3.) — wrong format");
        }

        if (!goalLower.contains(" then ")) {
            warnings.add("Steps do not contain 'then' connector — format may be incorrect");
        }

        // ── LLM judge ─────────────────────────────────────────────────────────
        LlmJudge.JudgeResult judgeResult = LlmJudge.judgeWithoutGroundTruth(
                generated, taskType, mode, bedrock);

        int confidenceScore = judgeResult.confidenceScore();
        suspectedHalls.addAll(judgeResult.hallucinatedSteps());

        // Merge judge warnings with pre-check warnings (deduplicated)
        for (String w : judgeResult.warnings()) {
            if (!warnings.contains(w)) warnings.add(w);
        }

        // Lower confidence if pre-checks found issues
        int preCheckPenalty = Math.min(40, warnings.size() * 8);
        confidenceScore = Math.max(0, confidenceScore - preCheckPenalty);

        String recommendation = ConfidenceResult.deriveRecommendation(
                confidenceScore, warnings, suspectedHalls);

        return new ConfidenceResult(
                confidenceScore,
                recommendation,
                List.copyOf(warnings),
                List.copyOf(suspectedHalls),
                placeholderCompliant,
                stepCount,
                judgeResult.reasoning(),
                judgeResult.tokenUsage());
    }
}
