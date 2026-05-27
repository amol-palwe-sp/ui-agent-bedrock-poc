package com.sailpoint.poc.uiagent.eval.shared;

import com.sailpoint.poc.uiagent.replay.FingerprintMatcher;

import java.util.List;

/**
 * Fuzzy string similarity for step matching.
 *
 * <p>Delegates to {@link FingerprintMatcher#jaccardSimilarity} for token-based
 * Jaccard comparison. Shared by {@link EvalMetrics} and {@link LlmJudge}.
 */
public final class StepSimilarity {

    private StepSimilarity() {}

    /**
     * Returns a Jaccard similarity score between two step strings.
     *
     * @param a first step string
     * @param b second step string
     * @return score 0.0 (no overlap) to 1.0 (identical tokens)
     */
    public static double similarity(String a, String b) {
        return FingerprintMatcher.jaccardSimilarity(a, b);
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
