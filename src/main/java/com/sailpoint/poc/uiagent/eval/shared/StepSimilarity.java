package com.sailpoint.poc.uiagent.eval.shared;

import com.sailpoint.poc.uiagent.replay.FingerprintMatcher;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Fuzzy string similarity for step matching.
 *
 * <p>Delegates to {@link FingerprintMatcher#jaccardSimilarity} for token-based
 * Jaccard comparison. Shared by {@link EvalMetrics} and {@link LlmJudge}.
 *
 * <p>Comparison is <em>structural</em>: quoted values (e.g. {@code "jdoe"}) are masked to a
 * single sentinel token before scoring, so a step matches on its action + target regardless of
 * the specific value entered. This prevents a long or legitimately-different entered value from
 * diluting the token overlap and sinking an otherwise-correct match. Value correctness, where it
 * matters, is judged separately (LLM judge / placeholder gate) rather than through this fuzzy match.
 */
public final class StepSimilarity {

    private static final Pattern QUOTED_VALUE = Pattern.compile("\"[^\"]*\"");
    /** Sentinel that survives the Jaccard tokenizer (length > 1, no delimiter chars). */
    private static final String VALUE_SENTINEL = " valuetoken ";

    private StepSimilarity() {}

    /**
     * Returns a Jaccard similarity score between two step strings, with quoted values masked so
     * the comparison reflects structure (action + target) rather than the specific value.
     *
     * @param a first step string
     * @param b second step string
     * @return score 0.0 (no overlap) to 1.0 (identical tokens)
     */
    public static double similarity(String a, String b) {
        return FingerprintMatcher.jaccardSimilarity(maskValues(a), maskValues(b));
    }

    /** Replaces each quoted value with a single sentinel token so values do not affect matching. */
    public static String maskValues(String s) {
        if (s == null) {
            return "";
        }
        return QUOTED_VALUE.matcher(s).replaceAll(VALUE_SENTINEL);
    }

    /**
     * Returns the best similarity score between {@code target} and any candidate.
     *
     * @param target     the step to match
     * @param candidates list of candidate steps
     * @return highest similarity score found, or 0.0 if candidates is empty
     */
    public static double bestMatch(String target, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return 0.0;
        double best = 0.0;
        for (String candidate : candidates) {
            double score = similarity(target, candidate);
            if (score > best) best = score;
        }
        return best;
    }
}
