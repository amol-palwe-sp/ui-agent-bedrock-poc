package com.sailpoint.poc.uiagent.aggregation;

/**
 * Parsed output of Claude's video-frame analysis for the aggregation pipeline.
 *
 * <ul>
 *   <li>{@code navigationGoal} — comma-separated steps to reach the user list page,
 *       formatted the same way as the existing {@code --goal} argument.</li>
 *   <li>{@code paginationPattern} — how the table advances to the next page.</li>
 * </ul>
 */
public record AggregationVideoAnalysis(
        String navigationGoal,
        PaginationPattern paginationPattern) {}
