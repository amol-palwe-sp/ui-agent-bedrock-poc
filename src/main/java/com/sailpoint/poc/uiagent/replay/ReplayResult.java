package com.sailpoint.poc.uiagent.replay;

import com.sailpoint.poc.uiagent.TokenUsage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a script replay run (REQ-RR-4.6).
 */
public final class ReplayResult {

    private final boolean success;
    private final int stepsTotal;
    private final int stepsSucceeded;
    private final int stepsFailed;
    private final Map<ReplayStrategy, Integer> strategiesUsed;
    private final int claudeCallsUsed;
    private final TokenUsage totalCost;
    private final List<FailedStep> failedSteps;

    public ReplayResult(
            boolean success,
            int stepsTotal,
            int stepsSucceeded,
            int stepsFailed,
            Map<ReplayStrategy, Integer> strategiesUsed,
            int claudeCallsUsed,
            TokenUsage totalCost,
            List<FailedStep> failedSteps) {
        this.success = success;
        this.stepsTotal = stepsTotal;
        this.stepsSucceeded = stepsSucceeded;
        this.stepsFailed = stepsFailed;
        this.strategiesUsed = strategiesUsed;
        this.claudeCallsUsed = claudeCallsUsed;
        this.totalCost = totalCost;
        this.failedSteps = failedSteps;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean success() { return success; }
    public int stepsTotal() { return stepsTotal; }
    public int stepsSucceeded() { return stepsSucceeded; }
    public int stepsFailed() { return stepsFailed; }
    public Map<ReplayStrategy, Integer> strategiesUsed() { return strategiesUsed; }
    public int claudeCallsUsed() { return claudeCallsUsed; }
    public TokenUsage totalCost() { return totalCost; }
    public List<FailedStep> failedSteps() { return failedSteps; }

    public record FailedStep(int stepIndex, String reason) {}

    public static final class Builder {
        private boolean success = true;
        private int stepsTotal;
        private int stepsSucceeded;
        private int stepsFailed;
        private final Map<ReplayStrategy, Integer> strategies = new EnumMap<>(ReplayStrategy.class);
        private int claudeCalls;
        private TokenUsage cost = TokenUsage.ZERO;
        private final List<FailedStep> failed = new ArrayList<>();

        public Builder stepsTotal(int n) { stepsTotal = n; return this; }
        public Builder stepsSucceeded(int n) { stepsSucceeded = n; return this; }
        public Builder stepsFailed(int n) { stepsFailed = n; return this; }
        public Builder success(boolean s) { success = s; return this; }
        public Builder addStrategy(ReplayStrategy s) {
            strategies.merge(s, 1, Integer::sum);
            return this;
        }
        public Builder claudeCalls(int n) { claudeCalls = n; return this; }
        public Builder totalCost(TokenUsage u) { cost = u; return this; }
        public Builder addFailed(int index, String reason) {
            failed.add(new FailedStep(index, reason));
            success = false;
            return this;
        }

        /** Snapshot of per-strategy step counts (for summary logging). */
        public Map<ReplayStrategy, Integer> strategiesUsed() {
            return Map.copyOf(strategies);
        }

        public ReplayResult build() {
            return new ReplayResult(success, stepsTotal, stepsSucceeded, stepsFailed,
                    strategies, claudeCalls, cost, List.copyOf(failed));
        }
    }
}
