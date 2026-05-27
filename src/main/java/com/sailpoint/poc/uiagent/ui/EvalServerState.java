package com.sailpoint.poc.uiagent.ui;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared mutable state for the Eval UI run pipeline.
 * Passed between {@link EvalRunHandler} and {@link EvalStreamHandler}.
 */
public final class EvalServerState {

    /** SSE message queue consumed by {@link EvalStreamHandler}. */
    public final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>();

    /** True while an eval run is in progress. */
    public final AtomicBoolean isRunning = new AtomicBoolean(false);

    /** Handle to the running eval thread so it can be interrupted on stop. */
    public final AtomicReference<Thread> evalThread = new AtomicReference<>();
}
