package com.sailpoint.poc.uiagent.llm;

import com.sailpoint.poc.uiagent.ModelPricing;
import com.sailpoint.poc.uiagent.TokenUsage;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Builds Anthropic Messages request bodies and reads the responses back.
 *
 * <p>Both transports share this so a prompt is encoded identically whether it goes to Bedrock
 * Runtime or through the GenAI gateway. The gateway forwards this same object as its
 * {@code modelParams}, which is why the body carries no {@code model} field: Bedrock takes the
 * model on the request envelope, and the gateway takes it as a sibling of {@code modelParams}.
 */
public final class AnthropicMessages {

    private static final String ANTHROPIC_VERSION = "bedrock-2023-05-31";

    private AnthropicMessages() {}

    /**
     * Assembles a single-turn user message from images followed by text.
     *
     * @param systemPrompt the system prompt, omitted from the body when null or blank
     * @param userText     the user text, appended after every image
     * @param images       ordered image bytes; null entries and empty arrays are skipped
     * @param maxTokens    the response token ceiling
     * @param temperature  the sampling temperature
     * @return the Anthropic request body, without a {@code model} field
     */
    public static JSONObject buildBody(
            String systemPrompt, String userText, List<byte[]> images, int maxTokens, double temperature) {

        JSONArray userContent = new JSONArray();

        if (images != null) {
            for (byte[] imageBytes : images) {
                if (imageBytes == null || imageBytes.length == 0) continue;
                userContent.put(new JSONObject()
                        .put("type", "image")
                        .put("source", new JSONObject()
                                .put("type", "base64")
                                .put("media_type", detectMediaType(imageBytes))
                                .put("data", Base64.getEncoder().encodeToString(imageBytes))));
            }
        }

        userContent.put(new JSONObject().put("type", "text").put("text", userText));

        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", userContent));

        JSONObject root = new JSONObject()
                .put("anthropic_version", ANTHROPIC_VERSION)
                .put("max_tokens", maxTokens)
                .put("temperature", temperature)
                .put("messages", messages);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("system", systemPrompt);
        }

        return root;
    }

    /**
     * Concatenates the text blocks of an assistant response.
     *
     * <p>Falls back to the whole response when there is no text to return, so a caller trying to
     * parse the output has the raw payload to report rather than an empty string. Non-text blocks
     * such as {@code tool_use} are skipped; reading those is a separate concern.
     *
     * @param responseBody the parsed Anthropic response
     * @return the assistant text, or the serialised response when it contains none
     */
    public static String extractText(JSONObject responseBody) {
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

    /**
     * Reads the token counts off a response and prices them.
     *
     * @param responseBody the parsed Anthropic response
     * @param modelId      the model to price against
     * @return the usage, zero-valued when the response reported none
     */
    public static TokenUsage extractUsage(JSONObject responseBody, String modelId) {
        JSONObject usage = responseBody.optJSONObject("usage");
        int inputTokens = usage != null ? usage.optInt("input_tokens", 0) : 0;
        int outputTokens = usage != null ? usage.optInt("output_tokens", 0) : 0;
        return ModelPricing.calculate(modelId, inputTokens, outputTokens);
    }

    /**
     * Reads an assistant response into text plus priced usage.
     *
     * @param responseBody the parsed Anthropic response
     * @param modelId      the model to price against
     * @return the combined result
     */
    public static InvokeResult toResult(JSONObject responseBody, String modelId) {
        return new InvokeResult(extractText(responseBody), extractUsage(responseBody, modelId));
    }

    /**
     * Identifies an image by its magic bytes: JPEG starts {@code FF D8 FF}, PNG starts {@code \x89PNG}.
     *
     * @param bytes the image bytes
     * @return the media type, defaulting to {@code image/png} when undetermined
     */
    static String detectMediaType(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return "image/png";
        boolean jpeg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
        return jpeg ? "image/jpeg" : "image/png";
    }
}
