package com.sailpoint.poc.uiagent.bedrock;

import com.sailpoint.poc.uiagent.JsonUtil;
import com.sailpoint.poc.uiagent.llm.AnthropicMessages;
import com.sailpoint.poc.uiagent.llm.InvokeResult;
import com.sailpoint.poc.uiagent.llm.LlmClient;
import java.time.Duration;
import java.util.List;
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
 * Calls Claude on Bedrock Runtime directly, using the Anthropic Messages API with optional vision input.
 *
 * <p>This is the direct-to-AWS {@link LlmClient}. It signs with local AWS credentials, so it needs the
 * caller to hold Bedrock access itself. {@link com.sailpoint.poc.uiagent.llm.LlmProxyClient} is the
 * alternative that routes through the SailPoint GenAI gateway instead.
 */
public final class BedrockAnthropicClient implements LlmClient {

    private final BedrockRuntimeClient client;
    private final String modelId;
    private final int maxTokens;
    private final double temperature;

    public BedrockAnthropicClient(String region, String awsProfile, String modelId, int maxTokens, double temperature) {
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

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public InvokeResult invokeWithVision(String systemPrompt, String userText, byte[] image) {
        List<byte[]> images = (image == null || image.length == 0) ? List.of() : List.of(image);
        return invokeWithMultipleImages(systemPrompt, userText, images);
    }

    @Override
    public InvokeResult invokeWithMultipleImages(String systemPrompt, String userText, List<byte[]> images) {
        JSONObject body = AnthropicMessages.buildBody(systemPrompt, userText, images, maxTokens, temperature);

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(body.toString()))
                .build();

        try {
            InvokeModelResponse response = client.invokeModel(request);
            JSONObject responseJson = new JSONObject(response.body().asUtf8String());
            return AnthropicMessages.toResult(responseJson, modelId);

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
