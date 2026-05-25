package com.sailpoint.poc.uiagent.video;

/**
 * Metadata for a {@code {Token}} placeholder found in a {@code navigationGoal}.
 *
 * <p>Example: the placeholder {@code {Email}} in
 * {@code enter "{Email}" in the Email field} produces
 * {@code TokenDefinition("Email", "Email field", "email")}.
 *
 * <p>Part of REQ-1.2 (Token placeholder handling) — see {@link VideoAnalysisResult}.
 */
public record TokenDefinition(
        /** The placeholder name without braces, e.g. {@code "Email"} or {@code "Password"}. */
        String name,

        /** Human-readable field label as observed in the UI, e.g. {@code "Email field"}. */
        String label,

        /**
         * Input type hint — one of {@code email}, {@code password}, {@code text},
         * {@code username}.
         */
        String type) {}
