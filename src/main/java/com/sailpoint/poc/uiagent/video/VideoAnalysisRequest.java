package com.sailpoint.poc.uiagent.video;

/**
 * Parameters that control how a video analysis prompt is built and how the Claude response
 * is parsed.
 *
 * <p>Implements REQ-2.2 — prompt parameterization via request object.
 * Use the static factory methods instead of a public constructor.
 *
 * <h2>Examples</h2>
 * <pre>
 * // Provisioning: watch video, extract full navigation goal with real values
 * VideoAnalysisRequest.provisioning()
 * VideoAnalysisRequest.provisioning("https://admin.google.com/ac/users")
 *
 * // Aggregation (CLI): real credential values in goal
 * VideoAnalysisRequest.aggregation(CredentialMode.LITERAL)
 *
 * // Aggregation (UI): {Token} placeholders so user fills values later
 * VideoAnalysisRequest.aggregation(CredentialMode.PLACEHOLDER)
 * VideoAnalysisRequest.aggregation(CredentialMode.PLACEHOLDER, "https://...")
 *
 * // Override URL after construction
 * request.withUrl("https://example.com")
 * </pre>
 */
public final class VideoAnalysisRequest {

    /**
     * What the agent is doing after navigating.
     * Drives which fields Claude is asked to return.
     */
    public enum TaskType {
        /** Goal is a single workflow — no table scraping afterward. */
        PROVISIONING,
        /** Goal navigates to a user/account list; pagination pattern is also extracted. */
        AGGREGATION
    }

    /**
     * How credentials observed in the video are represented in {@code navigationGoal}.
     */
    public enum CredentialMode {
        /** Embed real values as typed (e.g. {@code enter "admin@corp.com" in Email field}). */
        LITERAL,
        /**
         * Replace every credential with a named placeholder
         * (e.g. {@code enter "{Email}" in Email field}) so the UI can collect values.
         */
        PLACEHOLDER
    }

    private final TaskType       taskType;
    private final CredentialMode credentialMode;
    private final String         targetUrl;
    private final String         extraInstructions;

    private VideoAnalysisRequest(TaskType taskType, CredentialMode credentialMode,
                                  String targetUrl, String extraInstructions) {
        this.taskType          = taskType;
        this.credentialMode    = credentialMode;
        this.targetUrl         = targetUrl         != null ? targetUrl.trim()         : "";
        this.extraInstructions = extraInstructions != null ? extraInstructions.trim() : "";
    }

    // ── Static factories ──────────────────────────────────────────────────────

    /** Provisioning, URL inferred by Claude from video. */
    public static VideoAnalysisRequest provisioning() {
        return new VideoAnalysisRequest(TaskType.PROVISIONING, CredentialMode.LITERAL, "", "");
    }

    /** Provisioning with a known starting URL (Claude skips URL detection). */
    public static VideoAnalysisRequest provisioning(String targetUrl) {
        return new VideoAnalysisRequest(TaskType.PROVISIONING, CredentialMode.LITERAL, targetUrl, "");
    }

    /** Aggregation, URL inferred from video. */
    public static VideoAnalysisRequest aggregation(CredentialMode mode) {
        return new VideoAnalysisRequest(TaskType.AGGREGATION, mode, "", "");
    }

    /** Aggregation with a known list-page URL. */
    public static VideoAnalysisRequest aggregation(CredentialMode mode, String targetUrl) {
        return new VideoAnalysisRequest(TaskType.AGGREGATION, mode, targetUrl, "");
    }

    // ── Immutable builders ────────────────────────────────────────────────────

    /** Returns a copy of this request with a different target URL. */
    public VideoAnalysisRequest withUrl(String url) {
        return new VideoAnalysisRequest(taskType, credentialMode, url, extraInstructions);
    }

    /** Returns a copy of this request with additional instructions appended to the prompt. */
    public VideoAnalysisRequest withExtraInstructions(String extra) {
        return new VideoAnalysisRequest(taskType, credentialMode, targetUrl, extra);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public TaskType       taskType()          { return taskType; }
    public CredentialMode credentialMode()    { return credentialMode; }
    public String         targetUrl()         { return targetUrl; }
    public String         extraInstructions() { return extraInstructions; }

    public boolean hasTargetUrl()     { return targetUrl != null && !targetUrl.isBlank(); }
    public boolean isAggregation()    { return taskType == TaskType.AGGREGATION; }
    public boolean isProvisioning()   { return taskType == TaskType.PROVISIONING; }
    public boolean isPlaceholderMode(){ return credentialMode == CredentialMode.PLACEHOLDER; }
}
