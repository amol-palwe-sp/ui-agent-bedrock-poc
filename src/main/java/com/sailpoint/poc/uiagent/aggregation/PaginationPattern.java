package com.sailpoint.poc.uiagent.aggregation;

import java.util.Set;

/**
 * Pagination metadata extracted from Claude's video analysis.
 *
 * <p>The {@code type} is one of: {@code next_button}, {@code page_numbers},
 * {@code load_more}, {@code infinite_scroll}, {@code unknown}.
 */
public record PaginationPattern(
        String type,
        String description,
        String selectorHint) {

    private static final Set<String> VALID_TYPES = Set.of(
            "next_button", "page_numbers", "load_more", "infinite_scroll", "unknown");

    public boolean isValidType() {
        return type != null && VALID_TYPES.contains(type.toLowerCase());
    }

    public static Set<String> validTypes() {
        return VALID_TYPES;
    }
}
