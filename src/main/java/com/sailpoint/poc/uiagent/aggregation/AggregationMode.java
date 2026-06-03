package com.sailpoint.poc.uiagent.aggregation;

/**
 * Selects the aggregation strategy used by {@link com.sailpoint.poc.uiagent.pipeline.AgentPipeline}.
 *
 * <ul>
 *   <li>{@link #LLM_DOM} — existing mode: navigates to the user list page, detects the HTML
 *       table/grid via JavaScript + Claude vision, and paginates by clicking UI controls.</li>
 *   <li>{@link #NETWORK} — new mode: intercepts raw JSON API responses from the browser's
 *       network layer, scores them to identify user data, maps fields via Claude (one call),
 *       and paginates via direct authenticated HTTP requests.</li>
 * </ul>
 *
 * <p>Configured via {@code aggregation.mode} in {@code application.properties}.
 * Default is {@link #LLM_DOM} to preserve backward compatibility.
 */
public enum AggregationMode {

    /** DOM-based scraping using Playwright + Claude vision (original mode). */
    LLM_DOM,

    /** Network interception mode — intercepts XHR/fetch JSON responses. */
    NETWORK
}
