package com.sailpoint.poc.uiagent.llm;

import com.sailpoint.poc.uiagent.config.LlmProxyConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.json.JSONObject;

/**
 * Supplies OAuth2 bearer tokens for the GenAI gateway, caching one until shortly before it expires.
 *
 * <p>Tokens are valid for around twelve hours, so a fresh one per request would be pure waste on an
 * eval run that issues dozens of calls. The cached token is refreshed early by
 * {@link #EXPIRY_SKEW} to avoid handing out a token that expires mid-flight — which matters here
 * because a video completion can take two minutes between submit and final poll.
 *
 * <p>Thread-safe: {@link #bearerToken()} is synchronised so concurrent callers share one refresh.
 */
public final class LlmProxyTokenProvider {

    /** Refresh this far ahead of expiry so a long-running request cannot outlive its token. */
    private static final Duration EXPIRY_SKEW = Duration.ofMinutes(5);

    private final LlmProxyConfig config;
    private final HttpClient http;

    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    public LlmProxyTokenProvider(LlmProxyConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .build();
    }

    /**
     * Returns a valid bearer token, fetching a new one only when the cached token is missing or stale.
     *
     * @return the raw access token, without the {@code Bearer } prefix
     * @throws IllegalStateException if the token endpoint rejects the credentials or is unreachable
     */
    public synchronized String bearerToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
            return cachedToken;
        }
        fetchToken();
        return cachedToken;
    }

    private void fetchToken() {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(config.clientSecret(), StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.tokenUrl()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not reach the GenAI gateway token endpoint at " + config.tokenUrl()
                            + ". Check network access and llm.proxy.base.url. Cause: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching a GenAI gateway token", e);
        }

        if (response.statusCode() != 200) {
            // The body of a token failure can echo the submitted credentials, so it is never logged.
            throw new IllegalStateException(
                    "GenAI gateway token request failed with HTTP " + response.statusCode()
                            + ". Verify llm.proxy.client.id and llm.proxy.client.secret hold a valid personal "
                            + "access token pair for " + config.baseUrl());
        }

        JSONObject json = new JSONObject(response.body());
        String token = json.optString("access_token", "");
        if (token.isBlank()) {
            throw new IllegalStateException("GenAI gateway token response contained no access_token");
        }

        long expiresInSeconds = json.optLong("expires_in", 3600L);
        this.cachedToken = token;
        this.expiresAt = Instant.now().plusSeconds(expiresInSeconds).minus(EXPIRY_SKEW);
    }
}
