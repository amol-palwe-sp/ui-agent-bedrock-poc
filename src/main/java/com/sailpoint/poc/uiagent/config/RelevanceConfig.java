package com.sailpoint.poc.uiagent.config;

/**
 * Typed snapshot of pre-flight video-relevance triage settings.
 *
 * <p>Obtain via {@link com.sailpoint.poc.uiagent.PocConfig#relevance()}.
 *
 * @param enabled          whether the triage call runs at all
 * @param sampleFrames     how many evenly spaced frames the classifier sees
 * @param modelId          model used for triage; blank falls back to the main Bedrock model
 * @param maxTokens        response budget — the classifier returns a small JSON object
 * @param minConfidence    reject only when the classifier is at least this certain (0–100)
 * @param rejectOutOfDomain whether a consumer web app counts as a rejection or only a warning
 */
public record RelevanceConfig(
        boolean enabled,
        int     sampleFrames,
        String  modelId,
        int     maxTokens,
        int     minConfidence,
        boolean rejectOutOfDomain) {

    /** Resolves the triage model, falling back to the main model when unset. */
    public String resolveModelId(String fallbackModelId) {
        return (modelId == null || modelId.isBlank()) ? fallbackModelId : modelId;
    }
}
