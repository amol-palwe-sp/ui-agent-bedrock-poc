package com.sailpoint.poc.uiagent.aggregation;

import java.util.List;

/**
 * Result of Phase 3 (table detection).
 *
 * <ul>
 *   <li>{@code selector} — CSS selector that locates the accounts table.</li>
 *   <li>{@code headers} — ordered list of column header labels (may be empty if none found).</li>
 *   <li>{@code detectedByJs} — {@code true} if JS detected the table;
 *       {@code false} if Claude vision was used as fallback.</li>
 *   <li>{@code rawClaudeResponse} — raw text returned by Claude (null when JS succeeded).</li>
 * </ul>
 */
public record TableDetectionResult(
        String selector,
        List<String> headers,
        boolean detectedByJs,
        String rawClaudeResponse) {}
