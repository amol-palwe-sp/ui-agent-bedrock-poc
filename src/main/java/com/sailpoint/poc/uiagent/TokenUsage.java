package com.sailpoint.poc.uiagent;

/**
 * Immutable snapshot of token consumption and estimated USD cost for one LLM call (or an accumulated total).
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        double inputCostUsd,
        double outputCostUsd) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0.0, 0.0);

    public double totalCostUsd() {
        return inputCostUsd + outputCostUsd;
    }

    public TokenUsage add(TokenUsage other) {
        return new TokenUsage(
                this.inputTokens + other.inputTokens,
                this.outputTokens + other.outputTokens,
                this.inputCostUsd + other.inputCostUsd,
                this.outputCostUsd + other.outputCostUsd);
    }

    @Override
    public String toString() {
        return String.format(
                "input=%d tokens ($%.6f) | output=%d tokens ($%.6f) | total=$%.6f",
                inputTokens, inputCostUsd,
                outputTokens, outputCostUsd,
                totalCostUsd());
    }
}
