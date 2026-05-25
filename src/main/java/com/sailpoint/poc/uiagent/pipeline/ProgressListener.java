package com.sailpoint.poc.uiagent.pipeline;

import com.sailpoint.poc.uiagent.TokenUsage;

/**
 * Callback interface for real-time progress notifications from {@link AgentPipeline}.
 *
 * <p>Implements REQ-3.5 — replaces the {@code System.setOut()} hack previously used by
 * {@code RunHandler} and {@code AggregationRunHandler} to capture agent output.
 *
 * <h2>Usage</h2>
 * <pre>
 * ProgressListener listener = new ProgressListener() {
 *     {@literal @}Override
 *     public void onLog(String level, String message) {
 *         queue.offer("LOG:" + level + ":" + message);
 *     }
 *     {@literal @}Override
 *     public void onStatusChange(PipelineStatus status) {
 *         queue.offer("STATUS:" + status.name().toLowerCase());
 *     }
 *     {@literal @}Override
 *     public void onTokenUsage(TokenUsage usage) {
 *         queue.offer("TOKEN_USAGE:" + usage.inputTokens() + ":" + usage.outputTokens()
 *                 + ":" + usage.totalCostUsd());
 *     }
 * };
 * AgentPipeline.run(config, listener);
 * </pre>
 */
public interface ProgressListener {

    /**
     * Called for each log line produced during the pipeline run.
     *
     * @param level   one of {@code INFO}, {@code SUCCESS}, {@code WARNING}, {@code ERROR},
     *                {@code STEP} — inferred from the message content
     * @param message the log line (may originate from AgentLoop, BrowserSession, etc.)
     */
    void onLog(String level, String message);

    /**
     * Called whenever the pipeline transitions to a new {@link PipelineStatus}.
     *
     * @param status the new status
     */
    void onStatusChange(PipelineStatus status);

    /**
     * Called after each LLM call with the cumulative token usage so far.
     *
     * @param usage accumulated usage since pipeline start
     */
    void onTokenUsage(TokenUsage usage);

    // ── Built-in implementations ──────────────────────────────────────────────

    /**
     * A no-op listener suitable for CLI callers that handle output through
     * {@code System.out} directly (the default behaviour of {@code AgentLoop}).
     */
    ProgressListener SILENT = new ProgressListener() {
        @Override public void onLog(String level, String message)  {}
        @Override public void onStatusChange(PipelineStatus status) {}
        @Override public void onTokenUsage(TokenUsage usage)        {}
    };
}
