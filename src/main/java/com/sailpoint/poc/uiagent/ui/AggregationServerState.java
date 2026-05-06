package com.sailpoint.poc.uiagent.ui;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable shared state for the Aggregation UI tab.
 *
 * <p>Kept intentionally separate from {@link AgentUIServer.ServerState} so that the
 * Agent Run tab and the Aggregation tab operate fully independently with no cross-talk.
 */
public final class AggregationServerState {

    /** SSE log messages pushed by background handlers to the aggregation stream. */
    public final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

    /** The currently running aggregation background thread (null when idle). */
    public final AtomicReference<Thread> agentThread = new AtomicReference<>();

    /** {@code true} while an aggregation pipeline is executing. */
    public final AtomicBoolean agentRunning = new AtomicBoolean(false);

    /** Absolute path of the last successfully written CSV file, or {@code null}. */
    public final AtomicReference<String> lastCsvPath = new AtomicReference<>();

    /**
     * JSON string of up to 10 preview rows from the last aggregation run.
     * Shape: {@code [ {"col1":"val1", ...}, ... ]}
     * {@code null} when no run has completed yet.
     */
    public final AtomicReference<String> lastPreviewJson = new AtomicReference<>();

    /**
     * JSON string of summary statistics from the last aggregation run.
     * Shape: {@code {"totalRows":N, "pagesScraped":N, "columns":["c1","c2"], "inputTokens":N, "outputTokens":N, "costUsd":N}}
     * {@code null} when no run has completed yet.
     */
    public final AtomicReference<String> lastStatsJson = new AtomicReference<>();
}
