package com.sailpoint.poc.uiagent;

/**
 * USD pricing table for Anthropic models on Amazon Bedrock (price per 1,000 tokens).
 *
 * <p>Prices are approximate list prices — update here when AWS pricing changes. Cross-region / inference-profile
 * model ids are matched by substring so both bare ids and ARNs resolve correctly.
 */
public final class ModelPricing {

    private ModelPricing() {}

    // -----------------------------------------------------------------------
    // Input prices (USD / 1,000 tokens)
    // -----------------------------------------------------------------------
    public static double inputPricePer1kTokens(String modelId) {
        if (modelId == null) return 0.0;
        String m = modelId.toLowerCase();

        // Note: "Claude 4" is speculative. Prices are based on Claude 3.
        if (m.contains("claude-sonnet-4") || m.contains("claude-4-sonnet")) return 0.003;
        if (m.contains("claude-opus-4")   || m.contains("claude-4-opus"))   return 0.015;
        if (m.contains("claude-haiku-4")  || m.contains("claude-4-haiku"))  return 0.00025; // Corrected Price

        if (m.contains("claude-3-5-sonnet"))  return 0.003;
        if (m.contains("claude-3-5-haiku"))   return 0.00025; // Corrected Price
        if (m.contains("claude-3-sonnet"))    return 0.003;
        if (m.contains("claude-3-haiku"))     return 0.00025;
        if (m.contains("claude-3-opus"))      return 0.015;

        return 0.003; // Default to a common price
    }

    // -----------------------------------------------------------------------
    // Output prices (USD / 1,000 tokens)
    // -----------------------------------------------------------------------
    public static double outputPricePer1kTokens(String modelId) {
        if (modelId == null) return 0.0;
        String m = modelId.toLowerCase();

        // Note: "Claude 4" is speculative. Prices are based on Claude 3.
        if (m.contains("claude-sonnet-4") || m.contains("claude-4-sonnet")) return 0.015;
        if (m.contains("claude-opus-4")   || m.contains("claude-4-opus"))   return 0.075;
        if (m.contains("claude-haiku-4")  || m.contains("claude-4-haiku"))  return 0.00125; // Corrected Price

        if (m.contains("claude-3-5-sonnet"))  return 0.015;
        if (m.contains("claude-3-5-haiku"))   return 0.00125; // Corrected Price
        if (m.contains("claude-3-sonnet"))    return 0.015;
        if (m.contains("claude-3-haiku"))     return 0.00125;
        if (m.contains("claude-3-opus"))      return 0.075;

        return 0.015; // Default to a common price
    }

    public static TokenUsage calculate(String modelId, int inputTokens, int outputTokens) {
        double inputCost  = (inputTokens  / 1_000.0) * inputPricePer1kTokens(modelId);
        double outputCost = (outputTokens / 1_000.0) * outputPricePer1kTokens(modelId);
        return new TokenUsage(inputTokens, outputTokens, inputCost, outputCost);
    }
}
