package com.sailpoint.poc.uiagent.config;

/**
 * Typed snapshot of AWS Bedrock / LLM configuration from {@code application.properties}.
 *
 * <p>Obtain via {@link com.sailpoint.poc.uiagent.PocConfig#bedrock()}.
 */
public record BedrockConfig(
        String region,
        String profile,
        String modelId,
        int    maxTokens,
        double temperature) {}
