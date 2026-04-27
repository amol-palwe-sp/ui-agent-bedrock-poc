package com.sailpoint.poc.uiagent.bedrock;

import com.sailpoint.poc.uiagent.JsonUtil;
import com.sailpoint.poc.uiagent.ModelPricing;
import com.sailpoint.poc.uiagent.TokenUsage;
import java.time.Duration;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

/**
 * Bedrock Runtime invoke for Claude (Anthropic Messages API), with optional PNG vision input.
 *
 * <p>Returns an {@link InvokeResult} that carries both the assistant text and a {@link TokenUsage} snapshot so the
 * caller can accumulate per-run cost without any extra network calls.
 */
public final class BedrockAnthropicClient implements AutoCloseable {

    /**
     * Combined result of one {@link #invokeWithVision} call: the assistant text and the token usage / cost.
     */
    public record InvokeResult(String text, TokenUsage usage) {}

    private final BedrockRuntimeClient client;
    private final String region;
    private final String modelId;
    private final int maxTokens;
    private final double temperature;

    public BedrockAnthropicClient(String region, String awsProfile, String modelId, int maxTokens, double temperature) {
        this.region = region;
        this.modelId = modelId;
        this.maxTokens = maxTokens;
        this.temperature = temperature;

        ApacheHttpClient.Builder httpClientBuilder =
                ApacheHttpClient.builder().socketTimeout(Duration.ofSeconds(180));

        var builder = BedrockRuntimeClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(httpClientBuilder);

        if (awsProfile != null && !awsProfile.isBlank()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(awsProfile));
        } else {
            builder.credentialsProvider(ProfileCredentialsProvider.create());
        }

        this.client = builder.build();
    }

    /**
     * Invoke Claude with an optional viewport screenshot and return both the assistant text and the token usage.
     */
    public InvokeResult invokeWithVision(String systemPrompt, String userText, byte[] screenshotPng) {
        JSONObject body = buildRequestBody(systemPrompt, userText, screenshotPng);

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(body.toString()))
                .build();

        try {
            InvokeModelResponse response = client.invokeModel(request);
            JSONObject responseJson = new JSONObject(response.body().asUtf8String());

            String text = extractAssistantText(responseJson);

            JSONObject usage = responseJson.optJSONObject("usage");
            int inputTokens  = usage != null ? usage.optInt("input_tokens",  0) : 0;
            int outputTokens = usage != null ? usage.optInt("output_tokens", 0) : 0;
            TokenUsage tokenUsage = ModelPricing.calculate(modelId, inputTokens, outputTokens);

            return new InvokeResult(text, tokenUsage);

        } catch (ValidationException e) {
            String msg = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : String.valueOf(e.getMessage());
            if (msg != null && (msg.contains("inference profile") || msg.contains("on-demand throughput"))) {
                throw new IllegalStateException(
                        "Bedrock rejected model id \"" + modelId + "\". Many newer Anthropic models must be called "
                                + "with an inference profile ARN. In the AWS console: Bedrock → Inference profiles, "
                                + "copy the profile ARN and set bedrock.model.id or env BEDROCK_MODEL_ID. AWS: " + msg, e);
            }
            throw e;
        } catch (ResourceNotFoundException e) {
            String msg = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : String.valueOf(e.getMessage());
            if (msg != null && (msg.contains("end of its life") || msg.contains("end of life"))) {
                throw new IllegalStateException(
                        "This Bedrock model id is retired: \"" + modelId + "\". Use a current model or inference "
                                + "profile. Example: anthropic.claude-3-5-sonnet-20241022-v2:0. AWS: " + msg, e);
            }
            throw new IllegalStateException(
                    "Bedrock could not find model id \"" + modelId + "\". Check region and model access. AWS: " + msg, e);
        }
    }

    /**
     * Auto-detects whether {@code bytes} is a JPEG or PNG by inspecting its magic bytes.
     * <ul>
     *   <li>JPEG starts with {@code FF D8 FF}</li>
     *   <li>PNG starts with {@code 89 50 4E 47} ({@code \x89PNG})</li>
     * </ul>
     * Defaults to {@code image/png} when the format cannot be determined.
     */
    private static String detectMediaType(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return "image/png";
        if ((bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private JSONObject buildRequestBody(String systemPrompt, String userText, byte[] screenshotPng) {
        JSONArray userContent = new JSONArray();

        if (screenshotPng != null && screenshotPng.length > 0) {
            String b64 = Base64.getEncoder().encodeToString(screenshotPng);
            String mediaType = detectMediaType(screenshotPng);
            userContent.put(new JSONObject()
                    .put("type", "image")
                    .put("source", new JSONObject()
                            .put("type", "base64")
                            .put("media_type", mediaType)
                            .put("data", b64)));
        }

        userContent.put(new JSONObject().put("type", "text").put("text", userText));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", userContent));

        JSONObject root = new JSONObject()
                .put("anthropic_version", "bedrock-2023-05-31")
                .put("max_tokens", maxTokens)
                .put("temperature", temperature)
                .put("messages", messages);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("system", systemPrompt);
        }

        return root;
    }

    private static String extractAssistantText(JSONObject responseBody) {
        JSONArray content = responseBody.optJSONArray("content");
        if (content == null || content.isEmpty()) return responseBody.toString();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) {
                out.append(block.optString("text"));
            }
        }
        return out.length() > 0 ? out.toString() : responseBody.toString();
    }

    /** Parse model output as JSON after stripping fences and leading prose. */
    public static JSONObject parseModelJson(String rawText) {
        String cleaned = JsonUtil.stripMarkdownFence(rawText);
        String object = JsonUtil.extractFirstJsonObject(cleaned);
        return new JSONObject(object);
    }

    @Override
    public void close() {
        client.close();
    }
}
