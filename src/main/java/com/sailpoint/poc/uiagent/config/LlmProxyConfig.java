package com.sailpoint.poc.uiagent.config;

/**
 * Settings for reaching Claude through the SailPoint GenAI gateway instead of Bedrock directly.
 *
 * <p>{@code clientId} and {@code clientSecret} come from {@code LLM_PROXY_CLIENT_ID} and
 * {@code LLM_PROXY_CLIENT_SECRET} when those are set, otherwise from {@code application.properties}.
 * The environment takes precedence so a deployed run need not edit a tracked file.
 *
 * @param baseUrl          tenant API root, e.g. {@code https://hworg.api.cloud.sailpoint.com}
 * @param clientId         OAuth2 client id
 * @param clientSecret     OAuth2 client secret
 * @param modelId          the Bedrock inference-profile ARN the gateway should target
 * @param connectTimeoutMs TCP connect budget for gateway calls
 * @param requestTimeoutMs per-request budget; the submit call carries several MB of frames
 * @param pollIntervalMs   delay between polls for a submitted completion
 * @param pollTimeoutMs    total time to wait for a completion before giving up
 */
public record LlmProxyConfig(
        String baseUrl,
        String clientId,
        String clientSecret,
        String modelId,
        int connectTimeoutMs,
        int requestTimeoutMs,
        long pollIntervalMs,
        long pollTimeoutMs) {

    public String tokenUrl() {
        return baseUrl + "/oauth/token";
    }

    public String completionsUrl() {
        return baseUrl + "/genai-gateway/v1/llm-batch-completions";
    }

    /**
     * Fails fast when the gateway is selected but not fully configured.
     *
     * @throws IllegalStateException if a required value is missing or the base URL is not HTTPS
     */
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("llm.proxy.base.url is required when llm.provider=proxy");
        }
        if (!baseUrl.startsWith("https://")) {
            throw new IllegalStateException(
                    "llm.proxy.base.url must be https so the bearer token and prompts are not sent in clear text");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException(
                    "Set llm.proxy.client.id in application.properties, or export LLM_PROXY_CLIENT_ID");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Set llm.proxy.client.secret in application.properties, or export LLM_PROXY_CLIENT_SECRET");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalStateException("llm.proxy.model.id is required when llm.provider=proxy");
        }
    }
}
