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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Calls Claude through the SailPoint GenAI gateway rather than Bedrock Runtime.
 *
 * <p>The gateway is asynchronous: a POST to {@code /genai-gateway/v1/llm-batch-completions} enqueues
 * the prompt and returns a batch id, and the completion is collected by polling the same path
 * filtered on the request id. This class runs that cycle to completion inside each invoke, so it
 * satisfies the blocking {@link LlmClient} contract and existing callers need no change.
 *
 * <p>Only one prompt is submitted per batch. Batching several would save round trips, but it would
 * also couple unrelated calls into a shared failure and a shared latency floor, and the POC's calls
 * are issued one at a time anyway.
 *
 * <p>Request bodies carry base64 video frames and are never logged.
 */
public final class LlmProxyClient implements LlmClient {

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final int ERROR_BODY_PREVIEW_CHARS = 500;

    private final LlmProxyConfig config;
    private final LlmProxyTokenProvider tokens;
    private final HttpClient http;
    private final String requestIdPrefix;
    private final int maxTokens;
    private final double temperature;

    public LlmProxyClient(LlmProxyConfig config, String requestIdPrefix, int maxTokens, double temperature) {
        config.validate();
        this.config = config;
        this.requestIdPrefix = requestIdPrefix;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.tokens = new LlmProxyTokenProvider(config);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .build();
    }

    @Override
    public String modelId() {
        return config.modelId();
    }

    @Override
    public InvokeResult invokeWithVision(String systemPrompt, String userText, byte[] image) {
        List<byte[]> images = (image == null || image.length == 0) ? List.of() : List.of(image);
        return invokeWithMultipleImages(systemPrompt, userText, images);
    }

    @Override
    public InvokeResult invokeWithMultipleImages(String systemPrompt, String userText, List<byte[]> images) {
        String requestId = newRequestId();
        JSONObject modelParams = AnthropicMessages.buildBody(systemPrompt, userText, images, maxTokens, temperature);

        submit(requestId, modelParams);
        JSONObject completion = awaitCompletion(requestId);
        return AnthropicMessages.toResult(completion, config.modelId());
    }

    /**
     * A request id must be unique per submission, since the gateway is queried by that id and a
     * repeat would collide with an earlier result.
     */
    private String newRequestId() {
        return requestIdPrefix + "-" + UUID.randomUUID();
    }

    private void submit(String requestId, JSONObject modelParams) {
        JSONObject prompt = new JSONObject()
                .put("requestId", requestId)
                .put("reply", new JSONObject().put("type", "db"))
                .put("model", config.modelId())
                .put("modelParams", modelParams);

        JSONObject body = new JSONObject().put("prompts", new JSONArray().put(prompt));

        HttpRequest request = authorized(URI.create(config.completionsUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = send(request, "submit prompt " + requestId);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "GenAI gateway rejected the completion request with HTTP " + response.statusCode()
                            + " for model " + config.modelId() + ". " + preview(response.body()));
        }
    }

    /**
     * Polls until the gateway reports the request finished, or the poll budget runs out.
     *
     * @param requestId the id submitted with the prompt
     * @return the parsed Anthropic response the gateway stored for that request
     * @throws IllegalStateException if the request failed, or did not complete within the budget
     */
    private JSONObject awaitCompletion(String requestId) {
        Instant deadline = Instant.now().plusMillis(config.pollTimeoutMs());
        String lastStatus = "not yet reported";

        while (true) {
            sleep(config.pollIntervalMs());

            JSONObject status = fetchStatus(requestId);
            if (status != null) {
                lastStatus = status.optString("status", "").trim().toUpperCase(Locale.ROOT);

                if (STATUS_COMPLETED.equals(lastStatus)) {
                    return parseResult(requestId, status);
                }
                // Any terminal non-success status ends the wait; the gateway has no fixed
                // vocabulary here, so treat anything failure-shaped as final rather than
                // polling a request that will never complete.
                if (lastStatus.contains("FAIL") || lastStatus.contains("ERROR") || lastStatus.contains("CANCEL")) {
                    String error = status.optString("error", "");
                    throw new IllegalStateException(
                            "GenAI gateway reported " + lastStatus + " for request " + requestId
                                    + (error.isBlank() ? "" : ": " + error));
                }
            }

            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException(
                        "GenAI gateway did not complete request " + requestId + " within "
                                + config.pollTimeoutMs() + "ms (last status: " + lastStatus
                                + "). Raise llm.proxy.poll.timeout.ms if long video analyses are expected.");
            }
        }
    }

    /**
     * Fetches the status entry for one request id.
     *
     * @param requestId the id submitted with the prompt
     * @return the status object, or null when the gateway has not registered the request yet
     */
    private JSONObject fetchStatus(String requestId) {
        String filter = URLEncoder.encode("id eq \"" + requestId + "\"", StandardCharsets.UTF_8);
        URI uri = URI.create(config.completionsUrl() + "?filters=" + filter);

        HttpResponse<String> response = send(authorized(uri).GET().build(), "poll request " + requestId);

        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "GenAI gateway poll for request " + requestId + " failed with HTTP "
                            + response.statusCode() + ". " + preview(response.body()));
        }

        JSONArray statuses = new JSONObject(response.body()).optJSONArray("statuses");
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        return statuses.optJSONObject(0);
    }

    /**
     * Unwraps the stored completion, which the gateway returns as a JSON string rather than an object.
     *
     * @param requestId the id being collected, used only in error text
     * @param status    the status entry reported as completed
     * @return the parsed Anthropic response
     */
    private static JSONObject parseResult(String requestId, JSONObject status) {
        String result = status.optString("result", "");
        if (result.isBlank()) {
            throw new IllegalStateException(
                    "GenAI gateway marked request " + requestId + " completed but returned no result");
        }
        try {
            return new JSONObject(result);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Could not parse the completion the GenAI gateway stored for request " + requestId
                            + ". " + preview(result), e);
        }
    }

    private HttpRequest.Builder authorized(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer " + tokens.bearerToken())
                .header("X-SailPoint-Experimental", "true")
                .timeout(Duration.ofMillis(config.requestTimeoutMs()));
    }

    private HttpResponse<String> send(HttpRequest request, String what) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "GenAI gateway call failed while trying to " + what + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while trying to " + what, e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting on the GenAI gateway", e);
        }
    }

    /** Truncates a response body so an error message stays readable when the gateway echoes the request. */
    private static String preview(String body) {
        if (body == null || body.isBlank()) return "";
        return body.length() <= ERROR_BODY_PREVIEW_CHARS
                ? body
                : body.substring(0, ERROR_BODY_PREVIEW_CHARS) + "… (truncated)";
    }

    @Override
    public void close() {
        // java.net.http.HttpClient holds no resources needing explicit release on Java 17.
    }
}
