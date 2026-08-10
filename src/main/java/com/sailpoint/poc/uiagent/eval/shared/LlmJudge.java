package com.sailpoint.poc.uiagent.eval.shared;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalCase;
import com.sailpoint.poc.uiagent.llm.InvokeResult;
import com.sailpoint.poc.uiagent.llm.LlmClient;
import com.sailpoint.poc.uiagent.video.VideoAnalysisResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-as-Judge for both benchmark and real-time eval modes.
 *
 * <p>Implements the contract in {@code src/main/resources/prompts/04_LLM_JUDGE.md}. The
 * system prompt carries the whole rubric and is identical on every call, so it caches well
 * and cannot drift case to case; the user message carries only the data being judged.
 *
 * <p>The judge is the sole arbiter of pass/fail for cases that have ground truth. Because
 * it shares a model with the generator it is scored defensively: the composite and the
 * pass verdict are recomputed here from the per-dimension scores rather than taken from
 * the model, and any disagreement is recorded in {@link JudgeResult#issues()}.
 */
public final class LlmJudge {

    // ── Scoring contract ──────────────────────────────────────────────────────

    /** Composite weights. The judge is told this formula; we recompute it rather than trust it. */
    private static final double W_CORRECTNESS   = 0.40;
    private static final double W_ORDER         = 0.30;
    private static final double W_HALLUCINATION = 0.30;

    /** A case passes only when the composite clears this bar. Provisional pending sign-off. */
    public static final double MIN_OVERALL = 0.70;
    /**
     * ...and correctness clears it independently. Without this a plan that reaches the wrong
     * destination can still pass on the strength of good ordering and no invention.
     */
    public static final double MIN_CORRECTNESS = 0.70;

    /** Largest gap tolerated between the model's own composite and the recomputed one. */
    private static final double COMPOSITE_TOLERANCE = 0.011;

    // ── System prompts (static per mode — see 04_LLM_JUDGE.md) ────────────────

    private static final String BENCHMARK_SYSTEM_PROMPT = """
            <role>
            You are an LLM-as-Judge for an agent that generates a UI navigation goal from a video
            recording. The agent returns a single navigation-goal string describing the ordered actions
            needed to drive a browser to a destination. You compare the GENERATED goal against the
            GROUND TRUTH goal authored for the benchmark case. GROUND TRUTH is the sole authority:
            score how closely GENERATED matches it, never whether GENERATED merely looks plausible.
            Be precise, fair and consistent — the same pair of goals must always receive the same scores.
            </role>

            <task>
            The user message contains only data: the ground-truth navigation goal and the generated
            navigation goal. Evaluate GENERATED against GROUND TRUTH and return JSON only with:
            - correctness       <float 0.0-1.0>  workflow coverage vs ground truth
            - order             <float 0.0-1.0>  chronological sequence vs ground truth
            - hallucination     <float 0.0-1.0>  1.0 = nothing invented, 0.0 = heavily invented
            - overall           <float 0.0-1.0>  weighted composite, computed with the formula below
            - missing_steps     <string[]>       ground-truth actions absent from GENERATED
            - hallucinated_steps<string[]>       GENERATED actions absent from ground truth
            - reasoning         <string>         exactly 2 to 4 complete sentences of plain prose
            - test_passed       <bool>           whether the case clears the quality bar
            </task>

            <scoring_guidelines>
            Score ONLY by comparing GENERATED to GROUND TRUTH. Ignore writing style, verbosity,
            sentence structure and whether the script would succeed in a live browser — those are out
            of scope for this evaluation. Do NOT grade word-level label wording: a different phrasing
            for the same control is acceptable when the intended action is unambiguous.

            **Composite (required — do not free-hand the overall score):**
            overall = 0.40*correctness + 0.30*order + 0.30*hallucination
            Round every score to 2 decimal places. If your computed overall disagrees with your
            per-dimension scores, fix the per-dimension scores — never override the formula.

            **Score bands (apply per dimension, and when sanity-checking overall):**
            - 1.0        matches ground truth on that dimension
            - 0.7-0.9    minor deviations only; all critical ground-truth actions present and ordered
            - 0.4-0.6    partial match; a critical action is missing, mildly reordered, or mildly invented
            - 0.1-0.3    major mismatch; wrong destination, wrong workflow, heavy omission or invention
            - 0.0        empty output, unrelated workflow, or a fully fabricated goal

            ────────────────────────────────
            1) correctness — does GENERATED cover the same workflow as GROUND TRUTH?
            ────────────────────────────────
            A ground-truth action is CRITICAL when removing it would change where the script ends up or
            prevent it from getting there — authentication, each navigation hop, opening the target
            page or form, and any action the ground truth performs at the destination.

            Required for correctness >= 0.7:
            1. Every critical ground-truth action appears in GENERATED, matched by meaning rather than
               exact wording.
            2. GENERATED ends at the same destination as GROUND TRUTH — no earlier, no further.
            3. Every authentication or entry action present in GROUND TRUTH is present in GENERATED.
            4. Any action ground truth performs at the destination is reproduced with the same intent.

            Soft deductions (0.05-0.15 each; do not by themselves drop below 0.7):
            - Synonym labels for the same control ("Users" vs "User list", "Next" vs "Continue").
            - Extra waits, page-load pauses, or terminal guardrail phrases such as
              "this completes all steps" when the destination is unchanged.
            - Additional clarifying wording that does not add or remove an action.

            Hard failures (score <= 0.3):
            - A critical ground-truth action is missing — always record it in missing_steps.
            - The destination differs from ground truth, or the script stops short of it.
            - Authentication required by ground truth is absent.
            - The generated goal describes a different application or a different workflow entirely.

            ────────────────────────────────
            2) order — are critical actions in the same sequence as GROUND TRUTH?
            ────────────────────────────────
            Only the relative order of CRITICAL actions matters. Compare pairwise: for each pair of
            critical actions, does GENERATED keep the ground-truth precedence?

            Required for order >= 0.7:
            - Every critical pair preserves ground-truth precedence, in particular:
              authentication before anything behind it; a page opened before it is acted on;
              a destination reached before actions performed there.

            Soft deductions (0.05-0.15):
            - Adjacent swaps of non-critical phrasing where neither action depends on the other.
            - Inserted waits or load steps that do not move a critical action.

            Hard failures (score <= 0.3):
            - An action is performed on a page before that page is opened.
            - A post-authentication action precedes authentication.
            - The destination is acted on before it is reached.
            - Two or more critical pairs are inverted.

            ────────────────────────────────
            3) hallucination — 1.0 means nothing was invented
            ────────────────────────────────
            An action is hallucinated when GROUND TRUTH neither contains it nor implies it. Benign
            waits, synonym phrasings and terminal guardrails are NOT hallucinations.

            Required for hallucination >= 0.7:
            - No invented navigation target, menu, page, application or form field.
            - No action that contradicts ground truth — in particular, no action that goes beyond the
              point where ground truth deliberately stops.

            Soft deductions (~0.1):
            - Extra clicks that stay on the ground-truth path and change neither destination nor state.

            Hard failures (score <= 0.3):
            - Invented pages, controls or applications absent from ground truth.
            - Submitting, saving, creating or otherwise mutating state when ground truth stops before it.
            - Invented mechanics that ground truth does not describe.

            ────────────────────────────────
            Evidence lists
            ────────────────────────────────
            - missing_steps: short action phrases taken from GROUND TRUTH that are absent from
              GENERATED. Quote the ground-truth phrasing, not your paraphrase. [] when none.
            - hallucinated_steps: short action phrases taken from GENERATED that are absent from
              GROUND TRUTH. Quote the generated phrasing. [] when none.
            - These lists must agree with the scores: a non-empty missing_steps means correctness
              cannot be 1.0, and a non-empty hallucinated_steps means hallucination cannot be 1.0.

            ────────────────────────────────
            Pass threshold and test_passed consistency
            ────────────────────────────────
            Set test_passed = true only when BOTH of the following hold:
              1. overall >= 0.7
              2. correctness >= 0.7
            Otherwise set test_passed = false. Never pass a case on the strength of order and
            hallucination when correctness is below 0.7, and never fail a case that satisfies both
            conditions.

            ────────────────────────────────
            reasoning
            ────────────────────────────────
            Exactly 2 to 4 complete sentences of plain prose — no bullet lists, no markdown, no JSON.
            State the main match or mismatch against ground truth first, then the order or
            hallucination issue if there is one. Name the specific action at fault rather than
            describing the problem abstractly.
            </scoring_guidelines>

            <output_format>
            Respond ONLY with this JSON. No markdown fences, no text before or after.
            {
              "correctness": <0.0-1.0>,
              "order": <0.0-1.0>,
              "hallucination": <0.0-1.0>,
              "overall": <0.0-1.0>,
              "missing_steps": ["ground-truth action phrase not in generated"],
              "hallucinated_steps": ["generated action phrase not in ground truth"],
              "reasoning": "2 to 4 sentences of plain prose",
              "test_passed": <true|false>
            }
            </output_format>
            """;

    private static final String REALTIME_SYSTEM_PROMPT = """
            <role>
            You are an LLM-as-Judge for a UI navigation goal generated from a video recording. There is
            NO ground truth. Judge whether the goal is internally coherent and self-consistent, and give
            a holistic confidence score. Do not enumerate every possible concern.
            </role>

            <task>
            The user message contains only data: the generated navigation goal and light context about
            it. Return the three dimensions on a 0.0-1.0 scale, a weighted overall, a confidence score
            and a recommendation.
            </task>

            <scoring_guidelines>
            Use the same composite as benchmark mode:
            overall = 0.40*correctness + 0.30*order + 0.30*hallucination
            Round every score to 2 decimal places.

            - correctness   0.0-1.0: the workflow is plausible and complete — it authenticates where
                            required, navigates, and arrives somewhere coherent.
            - order         0.0-1.0: the sequence is logically possible; nothing acts on a page before
                            that page is opened.
            - hallucination 0.0-1.0: 1.0 when actions are concrete and mutually consistent; lower when
                            steps contradict each other or reference things the script never reached.
            - confidence_score 0-100: overall trustworthiness of this script.

            missing_steps and hallucinated_steps are always empty in this mode — there is no ground
            truth to compare against, so do not speculate about what might be missing or invented.

            Warn ONLY for clear structural problems: an empty goal, an impossible action order, or a
            required action that is completely missing such as navigating before any authentication.
            Do NOT warn about specific label choices, individual credential values, or navigation steps
            you merely suspect are missing.

            reasoning must be 2 to 4 complete sentences of plain prose.
            </scoring_guidelines>

            <output_format>
            Respond ONLY with this JSON. No markdown fences, no text before or after.
            {
              "correctness": <0.0-1.0>,
              "order": <0.0-1.0>,
              "hallucination": <0.0-1.0>,
              "overall": <0.0-1.0>,
              "reasoning": "2 to 4 sentences of plain prose",
              "missing_steps": [],
              "hallucinated_steps": [],
              "confidence_score": <0-100>,
              "warnings": ["warning message"],
              "recommendation": "TRUST|REVIEW|CAUTION"
            }
            </output_format>
            """;

    private LlmJudge() {}

    // ── JudgeResult record ────────────────────────────────────────────────────

    /**
     * Structured result from one LLM judge invocation.
     *
     * @param overall    the recomputed composite, not the model's own arithmetic
     * @param testPassed recomputed from {@code overall} and {@code correctness}
     * @param judgeFailed true when the call or the parse failed — an infrastructure problem,
     *                    not a statement about the quality of the generated plan
     * @param issues     inconsistencies found while validating the model's response
     */
    public record JudgeResult(
            double       correctness,
            double       order,
            double       hallucination,
            double       overall,
            boolean      testPassed,
            String       reasoning,
            List<String> hallucinatedSteps,
            List<String> missingSteps,
            int          confidenceScore,
            List<String> warnings,
            String       recommendation,
            boolean      judgeFailed,
            List<String> issues,
            TokenUsage   tokenUsage) {

        /**
         * Returns a fallback result used when the LLM call or parse fails.
         *
         * <p>Flagged with {@code judgeFailed} so callers can report the case as unscored
         * rather than as a plan that scored zero. Those are different failures and only one
         * of them says anything about the agent.
         */
        public static JudgeResult failure(String reason) {
            return new JudgeResult(0, 0, 0, 0, false,
                    "Judge call failed: " + reason,
                    List.of(), List.of(), 0, List.of("Judge unavailable"), "CAUTION",
                    true, List.of("Judge call failed: " + reason),
                    TokenUsage.ZERO);
        }
    }

    // ── Benchmark mode (with ground truth) ───────────────────────────────────

    /**
     * Judges a generated navigation goal against ground truth (benchmark mode).
     *
     * @param evalCase  the benchmark case with ground truth
     * @param generated the parsed result from Claude
     * @param llm       the client to reach the judge model through
     * @return a populated JudgeResult
     */
    public static JudgeResult judge(
            EvalCase evalCase,
            VideoAnalysisResult generated,
            LlmClient llm) {

        String userPrompt = buildBenchmarkPrompt(evalCase, generated);
        try {
            InvokeResult result = llm.invokeWithVision(BENCHMARK_SYSTEM_PROMPT, userPrompt, null);
            return parseJudgeResponse(result.text(), result.usage(), true);
        } catch (Exception e) {
            return JudgeResult.failure(e.getMessage());
        }
    }

    // ── Real-time mode (without ground truth) ────────────────────────────────

    /**
     * Judges a generated navigation goal for self-consistency, with no ground truth.
     *
     * @param generated the parsed result from Claude
     * @param llm       the client to reach the judge model through
     * @return a populated JudgeResult
     */
    public static JudgeResult judgeWithoutGroundTruth(
            VideoAnalysisResult generated,
            LlmClient llm) {

        String userPrompt = buildRealtimePrompt(generated);
        try {
            InvokeResult result = llm.invokeWithVision(REALTIME_SYSTEM_PROMPT, userPrompt, null);
            return parseJudgeResponse(result.text(), result.usage(), false);
        } catch (Exception e) {
            return JudgeResult.failure(e.getMessage());
        }
    }

    // ── Prompt builders — data only, no instructions ─────────────────────────

    /**
     * Builds the benchmark user message. Data only: every rule lives in the system prompt,
     * so anything here is read by the judge as material to score, never as direction.
     *
     * <p>Task type and mode are deliberately absent — the judge is task-agnostic, and the
     * ground truth already encodes whatever destination and mechanics the case requires.
     */
    private static String buildBenchmarkPrompt(EvalCase evalCase, VideoAnalysisResult generated) {
        String groundTruth = evalCase.groundTruth().navigationGoal();
        String generatedGoal = generated.navigationGoal();

        return "<ground_truth_navigation_goal>\n"
                + (groundTruth == null || groundTruth.isBlank() ? "(not provided)" : groundTruth)
                + "\n</ground_truth_navigation_goal>\n\n"
                + "<generated_navigation_goal>\n"
                + (generatedGoal == null || generatedGoal.isBlank() ? "(empty)" : generatedGoal)
                + "\n</generated_navigation_goal>\n";
    }

    /** Builds the real-time user message. Data only, as above. */
    private static String buildRealtimePrompt(VideoAnalysisResult generated) {
        String url  = generated.targetUrl();
        String goal = generated.navigationGoal();

        return "<target_url>" + (url == null || url.isBlank() ? "(not specified)" : url) + "</target_url>\n"
                + "<step_count>" + generated.steps().size() + "</step_count>\n\n"
                + "<generated_navigation_goal>\n"
                + (goal == null || goal.isBlank() ? "(empty)" : goal)
                + "\n</generated_navigation_goal>\n";
    }

    // ── Response parser ───────────────────────────────────────────────────────

    /**
     * Parses and validates a judge response.
     *
     * <p>The composite and the pass verdict are recomputed from the three dimensions rather
     * than read from the model. An LLM asked for a weighted sum will occasionally get the
     * arithmetic wrong, and with the judge as the only gate there is nothing downstream to
     * catch it. Where the model's own values disagree with the recomputed ones the
     * recomputed value wins and the disagreement is recorded.
     *
     * @param benchmarkMode true for benchmark mode, where {@code test_passed} applies
     */
    private static JudgeResult parseJudgeResponse(
            String rawText, TokenUsage usage, boolean benchmarkMode) {
        try {
            JSONObject j = BedrockAnthropicClient.parseModelJson(rawText);
            List<String> issues = new ArrayList<>();

            double correctness   = clampScore(j.optDouble("correctness",   0.0), "correctness",   issues);
            double order         = clampScore(j.optDouble("order",         0.0), "order",         issues);
            double hallucination = clampScore(j.optDouble("hallucination", 0.0), "hallucination", issues);

            double overall = round2(
                    (correctness * W_CORRECTNESS)
                    + (order * W_ORDER)
                    + (hallucination * W_HALLUCINATION));

            if (j.has("overall")) {
                double claimed = j.optDouble("overall", 0.0);
                if (Math.abs(claimed - overall) > COMPOSITE_TOLERANCE) {
                    issues.add(String.format(
                            "Judge composite arithmetic wrong: claimed %.2f, formula gives %.2f",
                            claimed, overall));
                }
            }

            boolean testPassed = overall >= MIN_OVERALL && correctness >= MIN_CORRECTNESS;
            if (benchmarkMode && j.has("test_passed")) {
                boolean claimed = j.optBoolean("test_passed", false);
                if (claimed != testPassed) {
                    issues.add(String.format(
                            "Judge verdict contradicts its scores: said test_passed=%b, "
                            + "but overall=%.2f and correctness=%.2f give %b",
                            claimed, overall, correctness, testPassed));
                }
            }

            String reasoning = j.optString("reasoning", "");
            int confidenceScore = j.optInt("confidence_score", 0);

            List<String> hallucinatedSteps = parseStringArray(j.optJSONArray("hallucinated_steps"));
            List<String> missingSteps      = parseStringArray(j.optJSONArray("missing_steps"));
            List<String> warnings          = parseStringArray(j.optJSONArray("warnings"));

            // The evidence lists are the judge's own justification, so a list that contradicts
            // the score it explains means one of the two is wrong and the case needs a look.
            if (!missingSteps.isEmpty() && correctness >= 1.0) {
                issues.add("Judge reported missing steps but scored correctness 1.0");
            }
            if (!hallucinatedSteps.isEmpty() && hallucination >= 1.0) {
                issues.add("Judge reported hallucinated steps but scored hallucination 1.0");
            }

            String recommendation;
            if (confidenceScore >= 85 && warnings.isEmpty()) recommendation = "TRUST";
            else if (confidenceScore >= 60)                  recommendation = "REVIEW";
            else                                             recommendation = "CAUTION";

            return new JudgeResult(
                    correctness, order, hallucination, overall, testPassed,
                    reasoning, hallucinatedSteps, missingSteps,
                    confidenceScore, warnings, recommendation,
                    false, issues, usage);

        } catch (Exception e) {
            return JudgeResult.failure("JSON parse error: " + e.getMessage());
        }
    }

    /**
     * Holds a dimension inside 0.0–1.0. A model handed a 0.0–1.0 rubric still occasionally
     * answers on the old 0–10 scale, which would sail past every threshold unchallenged.
     */
    private static double clampScore(double raw, String field, List<String> issues) {
        if (raw < 0.0 || raw > 1.0) {
            issues.add(String.format("Judge returned %s=%.2f, outside 0.0-1.0 — clamped", field, raw));
            return Math.max(0.0, Math.min(1.0, raw));
        }
        return raw;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static List<String> parseStringArray(JSONArray arr) {
        List<String> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isBlank()) list.add(s);
        }
        return list;
    }
}
