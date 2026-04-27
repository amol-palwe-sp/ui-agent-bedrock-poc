package com.sailpoint.poc.uiagent;

/**
 * USD pricing table for Anthropic models on Amazon Bedrock (price per 1,000 tokens).
 *
 * Source: https://aws.amazon.com/bedrock/pricing/ — Region: US East (Ohio)
 * Last verified: May 2025
 *
 * AWS lists prices per 1,000,000 tokens — divided by 1,000 here for per-1K-token math.
 * Cross-region / inference-profile model ids are matched by substring so both bare ids
 * and ARNs resolve correctly.
 *
 * Price table (per 1M tokens from AWS):
 * ┌─────────────────────────────┬──────────┬───────────┐
 * │ Model                       │ Input    │ Output    │
 * ├─────────────────────────────┼──────────┼───────────┤
 * │ Claude Opus  4.7            │  $5.00   │  $25.00   │
 * │ Claude Sonnet 4.6           │  $3.00   │  $15.00   │
 * │ Claude Opus  4.6            │  $5.00   │  $25.00   │
 * │ Claude Opus  4.5            │  $5.00   │  $25.00   │
 * │ Claude Haiku 4.5            │  $1.00   │   $5.00   │
 * │ Claude Sonnet 4.5           │  $3.00   │  $15.00   │
 * │ Claude Sonnet 4             │  $3.00   │  $15.00   │
 * │ Claude 3.5 Sonnet           │  $3.00   │  $15.00   │
 * │ Claude 3.5 Haiku            │  $0.80   │   $4.00   │
 * │ Claude 3 Opus               │ $15.00   │  $75.00   │
 * │ Claude 3 Sonnet             │  $3.00   │  $15.00   │
 * │ Claude 3 Haiku              │  $0.25   │   $1.25   │
 * └─────────────────────────────┴──────────┴───────────┘
 */
public final class ModelPricing {

    private ModelPricing() {}

    // -------------------------------------------------------------------------
    // Input prices  (USD / 1,000 tokens)
    // AWS lists per 1M — divided by 1,000 below
    // -------------------------------------------------------------------------
    public static double inputPricePer1kTokens(String modelId) {
        if (modelId == null) return 0.0;
        String m = modelId.toLowerCase();

        // ── Claude 4.x ───────────────────────────────────────────────────────
        // Opus 4.x  → $5.00 / 1M  = $0.005 / 1K
        if (m.contains("claude-opus-4")
                || m.contains("claude-4-opus"))   return 0.005;

        // Sonnet 4.x → $3.00 / 1M = $0.003 / 1K
        // Matches: claude-sonnet-4, claude-sonnet-4.5, claude-sonnet-4.6
        if (m.contains("claude-sonnet-4")
                || m.contains("claude-4-sonnet")) return 0.003;

        // Haiku 4.x  → $1.00 / 1M = $0.001 / 1K
        // Matches: claude-haiku-4, claude-haiku-4.5
        if (m.contains("claude-haiku-4")
                || m.contains("claude-4-haiku"))  return 0.001;

        // ── Claude 3.5.x ─────────────────────────────────────────────────────
        // Sonnet 3.5 → $3.00 / 1M = $0.003 / 1K
        if (m.contains("claude-3-5-sonnet")) return 0.003;

        // Haiku 3.5  → $0.80 / 1M = $0.0008 / 1K
        if (m.contains("claude-3-5-haiku"))  return 0.0008;

        // ── Claude 3.x ───────────────────────────────────────────────────────
        // Opus 3     → $15.00 / 1M = $0.015 / 1K
        if (m.contains("claude-3-opus"))     return 0.015;

        // Sonnet 3   → $3.00 / 1M = $0.003 / 1K
        if (m.contains("claude-3-sonnet"))   return 0.003;

        // Haiku 3    → $0.25 / 1M = $0.00025 / 1K
        if (m.contains("claude-3-haiku"))    return 0.00025;

        // ── Unknown model fallback ────────────────────────────────────────────
        System.err.println("[ModelPricing] WARNING: unknown model id '"
                + modelId + "' — defaulting to Sonnet pricing $0.003/1K input");
        return 0.003;
    }

    // -------------------------------------------------------------------------
    // Output prices (USD / 1,000 tokens)
    // -------------------------------------------------------------------------
    public static double outputPricePer1kTokens(String modelId) {
        if (modelId == null) return 0.0;
        String m = modelId.toLowerCase();

        // ── Claude 4.x ───────────────────────────────────────────────────────
        // Opus 4.x   → $25.00 / 1M = $0.025 / 1K
        if (m.contains("claude-opus-4")
                || m.contains("claude-4-opus"))   return 0.025;

        // Sonnet 4.x → $15.00 / 1M = $0.015 / 1K
        if (m.contains("claude-sonnet-4")
                || m.contains("claude-4-sonnet")) return 0.015;

        // Haiku 4.x  → $5.00 / 1M = $0.005 / 1K
        if (m.contains("claude-haiku-4")
                || m.contains("claude-4-haiku"))  return 0.005;

        // ── Claude 3.5.x ─────────────────────────────────────────────────────
        // Sonnet 3.5 → $15.00 / 1M = $0.015 / 1K
        if (m.contains("claude-3-5-sonnet")) return 0.015;

        // Haiku 3.5  → $4.00 / 1M = $0.004 / 1K
        if (m.contains("claude-3-5-haiku"))  return 0.004;

        // ── Claude 3.x ───────────────────────────────────────────────────────
        // Opus 3     → $75.00 / 1M = $0.075 / 1K
        if (m.contains("claude-3-opus"))     return 0.075;

        // Sonnet 3   → $15.00 / 1M = $0.015 / 1K
        if (m.contains("claude-3-sonnet"))   return 0.015;

        // Haiku 3    → $1.25 / 1M = $0.00125 / 1K
        if (m.contains("claude-3-haiku"))    return 0.00125;

        // ── Unknown model fallback ────────────────────────────────────────────
        System.err.println("[ModelPricing] WARNING: unknown model id '"
                + modelId + "' — defaulting to Sonnet pricing $0.015/1K output");
        return 0.015;
    }

    public static TokenUsage calculate(String modelId,
                                       int inputTokens,
                                       int outputTokens) {
        double inputCost  = (inputTokens  / 1_000.0)
                * inputPricePer1kTokens(modelId);
        double outputCost = (outputTokens / 1_000.0)
                * outputPricePer1kTokens(modelId);
        return new TokenUsage(inputTokens, outputTokens,
                inputCost, outputCost);
    }
}