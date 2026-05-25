package com.sailpoint.poc.uiagent.config;

/**
 * Typed snapshot of account-aggregation configuration from {@code application.properties}.
 *
 * <p>Obtain via {@link com.sailpoint.poc.uiagent.PocConfig#aggregation()}.
 */
public record AggregationConfig(
        int    maxPages,
        String outputDir) {}
