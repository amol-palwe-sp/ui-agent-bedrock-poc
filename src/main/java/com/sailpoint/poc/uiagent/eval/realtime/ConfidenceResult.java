package com.sailpoint.poc.uiagent.eval.realtime;

import com.sailpoint.poc.uiagent.TokenUsage;

import java.util.List;

/**
 * Result of real-time confidence evaluation for a new video upload.
 *
 * <h2>Recommendation thresholds</h2>
 * <ul>
 *   <li>{@code TRUST}   — confidenceScore &ge; 85 and warnings empty</li>
 *   <li>{@code REVIEW}  — confidenceScore 60–84 or warnings not empty</li>
 *   <li>{@code CAUTION} — confidenceScore &lt; 60 or suspectedHallucinations not empty</li>
 * </ul>
 */
public record ConfidenceResult(
        int          confidenceScore,
        String       recommendation,
        List<String> warnings,
        List<String> suspectedHallucinations,
        boolean      placeholderCompliant,
        int          stepCount,
        String       reasoning,
        TokenUsage   tokenUsage) {

    /**
     * Derives the recommendation string from score and warnings.
     *
     * @param score    0–100
     * @param warnings list of warning strings (empty = no warnings)
     * @param suspected list of suspected hallucinations
     * @return "TRUST", "REVIEW", or "CAUTION"
     */
    public static String deriveRecommendation(int score, List<String> warnings, List<String> suspected) {
        if (suspected != null && !suspected.isEmpty()) return "CAUTION";
        if (score < 60) return "CAUTION";
        if (score >= 85 && (warnings == null || warnings.isEmpty())) return "TRUST";
        return "REVIEW";
    }
}
