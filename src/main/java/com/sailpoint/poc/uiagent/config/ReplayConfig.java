package com.sailpoint.poc.uiagent.config;

/**
 * Replay execution tuning from {@code application.properties} (REQ-SIV-6).
 */
public record ReplayConfig(
        int scrollSettleMs,
        int progressiveScrollChunkPx,
        int progressiveScrollMaxChunks,
        int progressiveScrollChunkWaitMs) {}
