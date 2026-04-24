package com.sailpoint.poc.uiagent;

/**
 * Bedrock routing rules change by model; many Claude 4.x foundation ids cannot be used with on-demand
 * {@code InvokeModel} and require an inference profile ARN as {@code modelId}.
 */
public final class BedrockModelHints {

    private BedrockModelHints() {}

    /**
     * @return true if this id is almost certainly not valid for bare on-demand invoke (not an ARN / profile id).
     */
    public static boolean likelyRequiresInferenceProfileArn(String modelId) {
        if (modelId == null || modelId.isBlank()) return false;
        String m = modelId.trim();
        if (m.startsWith("arn:")) return false;
        if (m.startsWith("global.") || m.startsWith("us.")) return false;
        String lower = m.toLowerCase();
        return lower.contains("claude-sonnet-4")
                || lower.contains("claude-opus-4")
                || lower.contains("claude-haiku-4")
                || lower.contains("claude-4-");
    }
}
