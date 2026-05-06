package com.sailpoint.poc.uiagent.aggregation;

/**
 * Result of the unified aggregation video analysis prompt.
 *
 * <p>Unlike {@link AggregationVideoAnalysis}, the {@code navigationGoal} here contains
 * {@code {Token}} placeholders (e.g. {@code {Email}}, {@code {Password}}) in place of
 * literal credential values observed in the recording.  The user fills in real values
 * before running the aggregation pipeline.
 *
 * <p>{@code targetUrl} is the URL of the user/account list page extracted from the video
 * (e.g. from the browser address bar). It may be blank if Claude could not determine it.
 */
public record AggregationUIAnalysis(
        String targetUrl,
        String navigationGoal,
        PaginationPattern paginationPattern) {}
