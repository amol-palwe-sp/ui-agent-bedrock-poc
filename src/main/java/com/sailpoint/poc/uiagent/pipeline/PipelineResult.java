package com.sailpoint.poc.uiagent.pipeline;

import com.sailpoint.poc.uiagent.TokenUsage;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Unified result returned by {@link AgentPipeline#run(PipelineConfig, ProgressListener)}.
 *
 * <p>Implements REQ-4.1 — a single result type for both PROVISIONING and AGGREGATION tasks.
 * {@link AgentPipeline#run} never throws; errors are expressed via {@link #exitReason()}
 * and {@link #errorMessage()}.
 *
 * <h2>Checking the result</h2>
 * <pre>
 * PipelineResult result = AgentPipeline.run(config, listener);
 * if (result.success()) {
 *     System.out.println("Finished at: " + result.finalUrl());
 *     if (result.taskType() == PipelineConfig.TaskType.AGGREGATION) {
 *         System.out.println("Scraped " + result.rowsScraped() + " rows → " + result.csvPath());
 *     }
 * } else {
 *     System.err.println("Pipeline failed: " + result.errorMessage());
 * }
 * </pre>
 */
public final class PipelineResult {

    /** Why the pipeline stopped. */
    public enum ExitReason {
        /** Agent completed the goal (DONE action). */
        DONE,
        /** Agent issued a TERMINATE action (goal unreachable). */
        TERMINATED,
        /** Step limit reached without DONE or TERMINATE. */
        MAX_STEPS,
        /** Unrecoverable exception during pipeline execution. */
        ERROR,
        /** Thread was interrupted (e.g. user clicked Stop). */
        INTERRUPTED
    }

    // ── Common fields ──────────────────────────────────────────────────────────
    private final PipelineConfig.TaskType     taskType;
    private final boolean                     success;
    private final ExitReason                  exitReason;
    private final TokenUsage                  totalUsage;
    private final String                      errorMessage;
    private final String                      finalUrl;

    // ── Aggregation-only ──────────────────────────────────────────────────────
    private final int                         rowsScraped;
    private final int                         pagesScraped;
    private final String                      csvPath;
    private final List<String>                headers;
    private final List<Map<String,String>>    previewRows;

    private PipelineResult(Builder b) {
        this.taskType     = b.taskType;
        this.success      = b.success;
        this.exitReason   = b.exitReason;
        this.totalUsage   = b.totalUsage   != null ? b.totalUsage : TokenUsage.ZERO;
        this.errorMessage = b.errorMessage != null ? b.errorMessage : "";
        this.finalUrl     = b.finalUrl     != null ? b.finalUrl    : "";
        this.rowsScraped  = b.rowsScraped;
        this.pagesScraped = b.pagesScraped;
        this.csvPath      = b.csvPath      != null ? b.csvPath     : "";
        this.headers      = b.headers      != null
                ? Collections.unmodifiableList(b.headers)
                : Collections.emptyList();
        this.previewRows  = b.previewRows  != null
                ? Collections.unmodifiableList(b.previewRows)
                : Collections.emptyList();
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public PipelineConfig.TaskType    taskType()    { return taskType; }
    public boolean                    success()     { return success; }
    public ExitReason                 exitReason()  { return exitReason; }
    public TokenUsage                 totalUsage()  { return totalUsage; }
    public String                     errorMessage(){ return errorMessage; }
    /** URL the browser was on when the pipeline completed (or failed). */
    public String                     finalUrl()    { return finalUrl; }

    // Aggregation-only (0 / empty for PROVISIONING)
    public int                        rowsScraped()  { return rowsScraped; }
    public int                        pagesScraped() { return pagesScraped; }
    public String                     csvPath()      { return csvPath; }
    public List<String>               headers()      { return headers; }
    public List<Map<String,String>>   previewRows()  { return previewRows; }

    // ── Static factory helpers ────────────────────────────────────────────────

    static Builder provisioningSuccess(TokenUsage usage, String finalUrl) {
        return new Builder()
                .taskType(PipelineConfig.TaskType.PROVISIONING)
                .success(true).exitReason(ExitReason.DONE)
                .totalUsage(usage).finalUrl(finalUrl);
    }

    static Builder aggregationSuccess(TokenUsage usage, String finalUrl,
                                      int rowsScraped, int pagesScraped, String csvPath,
                                      List<String> headers, List<Map<String,String>> previewRows) {
        return new Builder()
                .taskType(PipelineConfig.TaskType.AGGREGATION)
                .success(true).exitReason(ExitReason.DONE)
                .totalUsage(usage).finalUrl(finalUrl)
                .rowsScraped(rowsScraped).pagesScraped(pagesScraped)
                .csvPath(csvPath).headers(headers).previewRows(previewRows);
    }

    static PipelineResult interrupted(PipelineConfig.TaskType taskType, TokenUsage usage) {
        return new Builder()
                .taskType(taskType).success(false).exitReason(ExitReason.INTERRUPTED)
                .totalUsage(usage).errorMessage("Interrupted by user").build();
    }

    static PipelineResult error(PipelineConfig.TaskType taskType, TokenUsage usage,
                                String message) {
        return new Builder()
                .taskType(taskType).success(false).exitReason(ExitReason.ERROR)
                .totalUsage(usage).errorMessage(message).build();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    static final class Builder {
        PipelineConfig.TaskType  taskType     = PipelineConfig.TaskType.PROVISIONING;
        boolean                  success;
        ExitReason               exitReason   = ExitReason.DONE;
        TokenUsage               totalUsage   = TokenUsage.ZERO;
        String                   errorMessage = "";
        String                   finalUrl     = "";
        int                      rowsScraped;
        int                      pagesScraped;
        String                   csvPath      = "";
        List<String>             headers      = Collections.emptyList();
        List<Map<String,String>> previewRows  = Collections.emptyList();

        Builder taskType(PipelineConfig.TaskType t)  { this.taskType = t;     return this; }
        Builder success(boolean s)                   { this.success = s;      return this; }
        Builder exitReason(ExitReason r)             { this.exitReason = r;   return this; }
        Builder totalUsage(TokenUsage u)             { this.totalUsage = u;   return this; }
        Builder errorMessage(String m)               { this.errorMessage = m; return this; }
        Builder finalUrl(String u)                   { this.finalUrl = u;     return this; }
        Builder rowsScraped(int n)                   { this.rowsScraped = n;  return this; }
        Builder pagesScraped(int n)                  { this.pagesScraped = n; return this; }
        Builder csvPath(String p)                    { this.csvPath = p;      return this; }
        Builder headers(List<String> h)              { this.headers = h;      return this; }
        Builder previewRows(List<Map<String,String>> r) { this.previewRows = r; return this; }

        PipelineResult build() { return new PipelineResult(this); }
    }
}
