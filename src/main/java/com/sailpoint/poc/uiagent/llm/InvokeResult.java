package com.sailpoint.poc.uiagent.llm;

import com.sailpoint.poc.uiagent.TokenUsage;

/**
 * Result of one completion: the assistant text and the token usage / cost it consumed.
 *
 * <p>Carrying usage alongside the text lets callers accumulate per-run cost without a second
 * network call, and keeps the number identical whichever {@link LlmClient} produced it.
 */
public record InvokeResult(String text, TokenUsage usage) {}
