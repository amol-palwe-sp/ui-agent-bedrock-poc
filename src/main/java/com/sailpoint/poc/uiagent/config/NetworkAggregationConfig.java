package com.sailpoint.poc.uiagent.config;

/**
 * Typed configuration for the NETWORK aggregation mode.
 *
 * <p>All fields have safe defaults via {@link #defaults()} so callers that only set
 * {@code aggregation.mode=NETWORK} in {@code application.properties} get sensible behaviour
 * without specifying every property.
 *
 * <p>Obtain a configured instance via {@link com.sailpoint.poc.uiagent.PocConfig#networkAggregation()}.
 */
public record NetworkAggregationConfig(

        /** Pipe-separated URL substrings that hint at user-data endpoints. */
        String urlKeywords,

        /** Pipe-separated JSON field names that hint at identity data. */
        String identityFields,

        /** Minimum number of records in a JSON array to qualify as user data. */
        int minRecords,

        /** Maximum pages to fetch during pagination. */
        int maxPages,

        /** Whether to automatically fall back to LLM_DOM when no qualifying payload is found. */
        boolean fallbackToLlmDom,

        /** Pipe-separated query-parameter names used to detect page-number pagination. */
        String paginationParams,

        /** Minimum payload body length in characters to be considered for scoring. */
        int minBodyLength,

        /** Score threshold — payloads below this are discarded. */
        int qualifyingScoreThreshold,

        /** HTTP connection timeout in milliseconds for direct pagination requests. */
        int httpConnectTimeoutMs,

        /** HTTP request timeout in milliseconds for direct pagination requests. */
        int httpRequestTimeoutMs,

        /**
         * Milliseconds to wait after {@code AgentLoop.run()} returns before stopping
         * the sniffer. SPAs (React/Angular/Vue) fire their user-data XHR calls
         * asynchronously after the page shell renders — the agent sees the page as
         * "done" before those calls complete. A short settle window (2–4 s) gives
         * them time to fire and be captured.
         */
        int settleAfterDoneMs

) {

    /** Returns a {@code NetworkAggregationConfig} populated with default values. */
    public static NetworkAggregationConfig defaults() {
        return new NetworkAggregationConfig(
                "users|accounts|members|identities|principals|people",
                "email|username|displayName|userId|user_id|login|firstName|lastName",
                5,
                50,
                true,
                "page|offset|cursor|startIndex|start|pageToken",
                100,
                10,
                15_000,
                30_000,
                3_000
        );
    }
}
