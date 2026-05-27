package com.sailpoint.poc.uiagent.pipeline;

/**
 * High-level pipeline operation mode (REQ-RR-6.1).
 */
public enum PipelineMode {
    /** Video → Claude → goal string (existing default). */
    GENERATE,
    /** Goal → AgentLoop → script JSON. */
    RECORD,
    /** Script JSON → ScriptExecutor (Claude only on fingerprint miss). */
    REPLAY,
    /** List scripts in output directory. */
    LIST
}
