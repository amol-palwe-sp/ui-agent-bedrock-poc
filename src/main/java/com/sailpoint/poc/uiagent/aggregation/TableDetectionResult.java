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
 *   <li>{@code columnIndexMap} — ordered list parallel to {@code headers}: element {@code i} is
 *       the physical {@code <td>} index in each data row that holds data for {@code headers.get(i)}.
 *       {@code -1} means the header could not be matched to a physical column (cell will be empty).
 *       Empty list when physical header texts were unavailable (Claude-only fallback or ARIA grid):
 *       the scraper then falls back to positional alignment with structural-cell filtering.</li>
 * </ul>
 */
public record TableDetectionResult(
        String selector,
        List<String> headers,
        boolean detectedByJs,
        String rawClaudeResponse,
        List<Integer> columnIndexMap) {}
