package com.sailpoint.poc.uiagent.eval.benchmark;

import com.sailpoint.poc.uiagent.aggregation.PaginationPattern;
import com.sailpoint.poc.uiagent.eval.shared.StepSimilarity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure static metric calculations for eval scoring.
 *
 * <p>No I/O or LLM calls — fully unit-testable.
 * All fuzzy comparisons use a Jaccard threshold of 0.6.
 */
public final class EvalMetrics {

    private static final double FUZZY_THRESHOLD = 0.6;

    /** Metric weights for overall score computation. */
    private static final double W_STEP_RECALL     = 0.30;
    private static final double W_STEP_PRECISION  = 0.20;
    private static final double W_STEP_ORDER      = 0.15;
    private static final double W_LABEL_ACCURACY  = 0.15;
    private static final double W_PLACEHOLDER     = 0.10;
    private static final double W_PAGINATION      = 0.10;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w._%+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}");

    /**
     * Matches values directly entered or selected by the agent:
     *   enter "VALUE" ...   or   select "VALUE" ...
     * Anchoring to action verbs avoids false positives from inter-token text.
     */
    private static final Pattern ENTERED_VALUE_PATTERN =
            Pattern.compile("(?:enter|select|type|fill)\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private EvalMetrics() {}

    // ── Core metric computations ──────────────────────────────────────────────

    /**
     * Computes the fraction of GT steps found in the generated output.
     * A GT step is "found" if any generated step scores {@code >= 0.6} Jaccard.
     */
    public static double computeStepRecall(List<String> gtSteps, List<String> generatedSteps) {
        if (gtSteps == null || gtSteps.isEmpty()) return 1.0;
        if (generatedSteps == null || generatedSteps.isEmpty()) return 0.0;
        long matched = gtSteps.stream()
                .filter(gt -> StepSimilarity.bestMatch(gt, generatedSteps) >= FUZZY_THRESHOLD)
                .count();
        return (double) matched / gtSteps.size();
    }

    /**
     * Computes the fraction of generated steps matching any GT step.
     * Low precision indicates hallucinated steps.
     */
    public static double computeStepPrecision(List<String> gtSteps, List<String> generatedSteps) {
        if (generatedSteps == null || generatedSteps.isEmpty()) return 1.0;
        if (gtSteps == null || gtSteps.isEmpty()) return 0.0;
        long matched = generatedSteps.stream()
                .filter(gen -> StepSimilarity.bestMatch(gen, gtSteps) >= FUZZY_THRESHOLD)
                .count();
        return (double) matched / generatedSteps.size();
    }

    /**
     * Computes a normalized Kendall Tau order score for matched step pairs.
     *
     * @return score 0.0–1.0; 1.0 = perfect order
     */
    public static double computeStepOrderScore(List<String> gtSteps, List<String> generatedSteps) {
        if (gtSteps == null || gtSteps.isEmpty()) return 1.0;
        if (generatedSteps == null || generatedSteps.isEmpty()) return 0.0;

        // Build list of GT indices in the order they appear in generatedSteps
        List<Integer> gtIndexSequence = new ArrayList<>();
        for (String gen : generatedSteps) {
            int bestGtIdx = -1;
            double bestScore = FUZZY_THRESHOLD - 0.001;
            for (int i = 0; i < gtSteps.size(); i++) {
                double score = StepSimilarity.similarity(gen, gtSteps.get(i));
                if (score > bestScore) {
                    bestScore = score;
                    bestGtIdx = i;
                }
            }
            if (bestGtIdx >= 0) {
                gtIndexSequence.add(bestGtIdx);
            }
        }

        if (gtIndexSequence.size() < 2) return 1.0;

        // Count concordant vs discordant pairs (Kendall Tau)
        int concordant = 0;
        int discordant = 0;
        for (int i = 0; i < gtIndexSequence.size() - 1; i++) {
            for (int j = i + 1; j < gtIndexSequence.size(); j++) {
                int diff = gtIndexSequence.get(i) - gtIndexSequence.get(j);
                if (diff < 0) concordant++;
                else if (diff > 0) discordant++;
            }
        }

        int total = concordant + discordant;
        if (total == 0) return 1.0;
        double tau = (double) (concordant - discordant) / total;
        return (tau + 1.0) / 2.0;
    }

    /**
     * Computes average similarity score between matched GT/generated step pairs.
     */
    public static double computeLabelAccuracy(List<String> gtSteps, List<String> generatedSteps) {
        if (gtSteps == null || gtSteps.isEmpty()) return 1.0;
        if (generatedSteps == null || generatedSteps.isEmpty()) return 0.0;
        double total = 0.0;
        int matched = 0;
        for (String gt : gtSteps) {
            double best = StepSimilarity.bestMatch(gt, generatedSteps);
            if (best > 0.0) {
                total += best;
                matched++;
            }
        }
        return matched == 0 ? 0.0 : total / matched;
    }

    /**
     * Checks for credential leaks — only meaningful for AGGREGATION + PLACEHOLDER mode.
     *
     * <p>For PROVISIONING tasks this always returns {@code 1.0} because the
     * {@code VideoToGoalPrompt} transcribes literal values from the video by design;
     * placeholder tokenization happens afterward in the UI as a post-processing step.
     *
     * <p>For AGGREGATION LITERAL mode this returns {@code 1.0} trivially.
     *
     * <p>For AGGREGATION PLACEHOLDER mode the prompt explicitly instructs Claude to
     * output {@code {Token}} syntax, so any literal credential in the output is a
     * genuine model failure.
     *
     * @param navigationGoal  the generated navigation goal string (raw Claude output)
     * @param taskType        "PROVISIONING" or "AGGREGATION"
     * @param mode            "PLACEHOLDER" or "LITERAL"
     * @param credentialLeaks mutable list to populate with detected leaks (may be null)
     * @return 1.0 if clean / not applicable, 0.0 if any leak found
     */
    public static double computePlaceholderScore(
            String navigationGoal, String taskType, String mode, List<String> credentialLeaks) {
        // PROVISIONING: literals are expected — tokenization is a UI post-processing step
        if ("PROVISIONING".equalsIgnoreCase(taskType)) return 1.0;
        // AGGREGATION LITERAL: nothing to check
        if (!"PLACEHOLDER".equalsIgnoreCase(mode)) return 1.0;
        if (navigationGoal == null || navigationGoal.isBlank()) return 1.0;

        List<String> found = detectCredentialLeaks(navigationGoal);
        if (credentialLeaks != null) credentialLeaks.addAll(found);
        return found.isEmpty() ? 1.0 : 0.0;
    }

    /**
     * @deprecated Use {@link #computePlaceholderScore(String, String, String, List)} instead.
     */
    @Deprecated
    public static double computePlaceholderScore(
            String navigationGoal, String mode, List<String> credentialLeaks) {
        return computePlaceholderScore(navigationGoal, "AGGREGATION", mode, credentialLeaks);
    }

    /**
     * Scores pagination pattern accuracy for AGGREGATION tasks.
     * Returns 0.0 for PROVISIONING tasks.
     */
    public static double computePaginationScore(
            EvalCase.PaginationGT gt, PaginationPattern generated, String taskType) {
        if ("PROVISIONING".equalsIgnoreCase(taskType)) return 0.0;
        if (gt == null || generated == null) return 0.0;

        double typeScore = gt.type() != null && gt.type().equalsIgnoreCase(generated.type()) ? 1.0 : 0.0;
        double selectorScore = StepSimilarity.similarity(
                nullToEmpty(gt.selectorHint()), nullToEmpty(generated.selectorHint()));
        double descScore = StepSimilarity.similarity(
                nullToEmpty(gt.description()), nullToEmpty(generated.description()));

        return (typeScore * 0.5) + (selectorScore * 0.3) + (descScore * 0.2);
    }

    /**
     * Computes the weighted overall score from individual metric results.
     *
     * <p>Weight table:
     * <pre>
     *                      AGGREGATION   PROVISIONING
     *  stepRecall               30%          50%   ← gains pagination(10%) + placeholder(10%)
     *  stepPrecision            20%          20%
     *  stepOrderScore           15%          15%
     *  labelAccuracyScore       15%          15%
     *  placeholderScore         10%           0%   ← N/A: tokenization is a UI post-process
     *  paginationScore          10%           0%   ← N/A: no pagination for provisioning
     * </pre>
     */
    public static double computeOverallScore(EvalResult result) {
        boolean isProvisioning = "PROVISIONING".equalsIgnoreCase(result.taskType());

        // For PROVISIONING: pagination (10%) + placeholder (10%) both roll into stepRecall
        double wRecall = isProvisioning
                ? W_STEP_RECALL + W_PAGINATION + W_PLACEHOLDER
                : W_STEP_RECALL;

        return (result.stepRecall()         * wRecall)
             + (result.stepPrecision()      * W_STEP_PRECISION)
             + (result.stepOrderScore()     * W_STEP_ORDER)
             + (result.labelAccuracyScore() * W_LABEL_ACCURACY)
             + (result.placeholderScore()   * (isProvisioning ? 0.0 : W_PLACEHOLDER))
             + (result.paginationScore()    * (isProvisioning ? 0.0 : W_PAGINATION));
    }

    // ── Detection helpers ─────────────────────────────────────────────────────

    /**
     * Returns steps in {@code generatedSteps} that do not match any GT step.
     */
    public static List<String> detectHallucinatedSteps(
            List<String> gtSteps, List<String> generatedSteps) {
        List<String> hallucinated = new ArrayList<>();
        if (generatedSteps == null) return hallucinated;
        for (String gen : generatedSteps) {
            if (gtSteps == null || StepSimilarity.bestMatch(gen, gtSteps) < FUZZY_THRESHOLD) {
                hallucinated.add(gen);
            }
        }
        return hallucinated;
    }

    /**
     * Returns GT steps not found in {@code generatedSteps}.
     */
    public static List<String> detectMissingSteps(
            List<String> gtSteps, List<String> generatedSteps) {
        List<String> missing = new ArrayList<>();
        if (gtSteps == null) return missing;
        for (String gt : gtSteps) {
            if (generatedSteps == null || StepSimilarity.bestMatch(gt, generatedSteps) < FUZZY_THRESHOLD) {
                missing.add(gt);
            }
        }
        return missing;
    }

    /**
     * Detects credential leaks in a navigation goal string produced by Claude in
     * AGGREGATION + PLACEHOLDER mode (the only context where this check is meaningful).
     *
     * <p>Only inspects values that are directly entered or selected by the agent,
     * e.g. {@code enter "value" in ...} or {@code select "value" from ...}.
     * This avoids false positives from matching the navigation text that sits
     * <em>between</em> quoted values.
     *
     * <p>A value is considered a leak when it:
     * <ul>
     *   <li>matches an email pattern (contains {@code @}), or</li>
     *   <li>is not wrapped in {@code {braces}} (i.e. not a proper placeholder token)</li>
     * </ul>
     */
    public static List<String> detectCredentialLeaks(String navigationGoal) {
        List<String> leaks = new ArrayList<>();
        if (navigationGoal == null || navigationGoal.isBlank()) return leaks;

        Matcher actionMatcher = ENTERED_VALUE_PATTERN.matcher(navigationGoal);
        while (actionMatcher.find()) {
            String val = actionMatcher.group(1).trim();
            if (val.isEmpty()) continue;

            boolean isProperToken = val.startsWith("{") && val.endsWith("}");
            if (isProperToken) continue;

            // Any non-token entered/selected value is a potential credential leak
            if (!leaks.contains(val)) {
                leaks.add(val);
            }
        }

        return leaks;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
