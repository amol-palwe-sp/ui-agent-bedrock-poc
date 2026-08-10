package com.sailpoint.poc.uiagent.llm;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.config.BedrockConfig;
import com.sailpoint.poc.uiagent.config.LlmProxyConfig;

/**
 * Builds the {@link LlmClient} the {@code llm.provider} setting selects.
 *
 * <p>This exists so the video-to-goal path and the Stage 1 eval can move to the GenAI gateway by
 * configuration rather than by code change, and move back just as cheaply if the gateway
 * misbehaves. Paths still outside that scope — the agent loop, aggregation, replay — construct
 * {@link BedrockAnthropicClient} directly and are unaffected by the setting.
 */
public final class LlmClientFactory {

    public static final String PROVIDER_BEDROCK = "bedrock";
    public static final String PROVIDER_PROXY = "proxy";

    private final String provider;
    private final BedrockConfig bedrock;
    private final LlmProxyConfig proxy;
    private final int defaultMaxTokens;
    private final double defaultTemperature;

    private LlmClientFactory(
            String provider, BedrockConfig bedrock, LlmProxyConfig proxy, int maxTokens, double temperature) {
        this.provider = provider;
        this.bedrock = bedrock;
        this.proxy = proxy;
        this.defaultMaxTokens = maxTokens;
        this.defaultTemperature = temperature;
    }

    /**
     * Reads the provider selection and both transports' settings off the loaded configuration.
     *
     * @param config the loaded application configuration
     * @return a factory that hands out clients for the selected provider
     */
    public static LlmClientFactory from(PocConfig config) {
        return new LlmClientFactory(
                config.llmProvider(),
                config.bedrock(),
                config.llmProxy(),
                config.maxTokens(),
                config.temperature());
    }

    public String provider() {
        return provider;
    }

    public boolean usesProxy() {
        return PROVIDER_PROXY.equals(provider);
    }

    /** The model the selected provider targets when a call site has no override. */
    public String defaultModelId() {
        return usesProxy() ? proxy.modelId() : bedrock.modelId();
    }

    /**
     * Creates a client using the configured defaults for token ceiling and temperature.
     *
     * @param requestIdPrefix identifies the call site in gateway request ids; ignored by Bedrock
     * @return a client the caller owns and must close
     */
    public LlmClient create(String requestIdPrefix) {
        return create(requestIdPrefix, "", defaultMaxTokens, defaultTemperature);
    }

    /**
     * Creates a client for a call site that overrides the model or sampling settings.
     *
     * @param requestIdPrefix identifies the call site in gateway request ids; ignored by Bedrock
     * @param modelIdOverride a model id to use instead of the provider default, or blank for none
     * @param maxTokens       the response token ceiling
     * @param temperature     the sampling temperature
     * @return a client the caller owns and must close
     */
    public LlmClient create(String requestIdPrefix, String modelIdOverride, int maxTokens, double temperature) {
        String modelId = (modelIdOverride == null || modelIdOverride.isBlank())
                ? defaultModelId()
                : modelIdOverride.trim();

        if (usesProxy()) {
            LlmProxyConfig scoped = new LlmProxyConfig(
                    proxy.baseUrl(),
                    proxy.clientId(),
                    proxy.clientSecret(),
                    modelId,
                    proxy.connectTimeoutMs(),
                    proxy.requestTimeoutMs(),
                    proxy.pollIntervalMs(),
                    proxy.pollTimeoutMs());
            return new LlmProxyClient(scoped, requestIdPrefix, maxTokens, temperature);
        }

        return new BedrockAnthropicClient(
                bedrock.region(), bedrock.profile(), modelId, maxTokens, temperature);
    }

    /** One-line provider summary for run banners, with no credentials in it. */
    public String describe() {
        return usesProxy()
                ? "GenAI gateway " + proxy.baseUrl() + " → " + proxy.modelId()
                : "Bedrock " + bedrock.region() + " → " + bedrock.modelId();
    }
}
