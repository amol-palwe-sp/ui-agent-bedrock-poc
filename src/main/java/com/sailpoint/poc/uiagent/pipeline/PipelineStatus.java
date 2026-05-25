package com.sailpoint.poc.uiagent.pipeline;

/**
 * Lifecycle status of an {@link AgentPipeline} run, emitted via
 * {@link ProgressListener#onStatusChange(PipelineStatus)}.
 */
public enum PipelineStatus {
    /** Resources are being allocated (Bedrock client, browser, logger). */
    STARTING,
    /** Browser is navigating to {@code startUrl}. */
    NAVIGATING,
    /** AgentLoop is running the LLM-driven action cycle. */
    AGENT_RUNNING,
    /** (Aggregation only) Detecting the accounts table on the page. */
    DETECTING_TABLE,
    /** (Aggregation only) Pagination loop is scraping rows across pages. */
    AGGREGATING,
    /** (Aggregation only) Writing the CSV file to disk. */
    WRITING_CSV,
    /** Pipeline completed successfully. */
    DONE,
    /** Pipeline failed with an unrecoverable error. */
    FAILED,
    /** Pipeline was interrupted by the caller (thread interrupt). */
    INTERRUPTED
}
