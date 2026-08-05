package com.sailpoint.poc.uiagent.video.relevance;

import com.sailpoint.poc.uiagent.TokenUsage;

/**
 * Verdict from {@link VideoRelevanceGate} for one uploaded video.
 *
 * @param verdict        whether to continue to step generation
 * @param category       why it was rejected, or {@link RejectionCategory#NONE}
 * @param confidence     0–100 self-reported certainty from the classifier
 * @param reason         human-readable explanation shown to the user
 * @param detectedTaskType {@code PROVISIONING}, {@code AGGREGATION} or {@code UNKNOWN};
 *                         informational only — the gate never re-routes the request
 * @param tokenUsage     cost of the triage call, so it can be added to the run total
 */
public record RelevanceResult(
        RelevanceVerdict verdict,
        RejectionCategory category,
        int confidence,
        String reason,
        String detectedTaskType,
        TokenUsage tokenUsage) {

    public boolean isRejected() {
        return verdict == RelevanceVerdict.REJECT;
    }

    /** True when the video passed but the classifier was not confident about it. */
    public boolean isUncertain() {
        return verdict == RelevanceVerdict.UNCERTAIN;
    }

    /** Actionable guidance for the user, derived from the rejection category. */
    public String suggestion() {
        return category.suggestion();
    }

    public static RelevanceResult accepted(int confidence, String reason,
                                           String detectedTaskType, TokenUsage usage) {
        return new RelevanceResult(RelevanceVerdict.ACCEPT, RejectionCategory.NONE,
                confidence, reason, detectedTaskType, usage);
    }

    public static RelevanceResult rejected(RejectionCategory category, int confidence,
                                           String reason, TokenUsage usage) {
        String text = (reason == null || reason.isBlank()) ? category.defaultReason() : reason;
        return new RelevanceResult(RelevanceVerdict.REJECT, category,
                confidence, text, "UNKNOWN", usage);
    }

    public static RelevanceResult uncertain(int confidence, String reason,
                                            String detectedTaskType, TokenUsage usage) {
        return new RelevanceResult(RelevanceVerdict.UNCERTAIN, RejectionCategory.NONE,
                confidence, reason, detectedTaskType, usage);
    }

    /**
     * Fail-open result used when the triage call itself errors. A Bedrock outage or a
     * malformed classifier response must never block an otherwise valid upload.
     */
    public static RelevanceResult unavailable(String reason) {
        return new RelevanceResult(RelevanceVerdict.ACCEPT, RejectionCategory.NONE,
                0, "Relevance check unavailable: " + reason, "UNKNOWN", TokenUsage.ZERO);
    }

    /** Result used when the gate is switched off in configuration. */
    public static RelevanceResult skipped() {
        return new RelevanceResult(RelevanceVerdict.ACCEPT, RejectionCategory.NONE,
                0, "Relevance check disabled", "UNKNOWN", TokenUsage.ZERO);
    }
}
