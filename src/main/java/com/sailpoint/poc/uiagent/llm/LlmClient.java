package com.sailpoint.poc.uiagent.llm;

import java.util.List;

/**
 * A blocking Claude completion, independent of how the request reaches the model.
 *
 * <p>Two implementations exist: {@code BedrockAnthropicClient} calls Bedrock Runtime directly, and
 * {@link LlmProxyClient} goes through the SailPoint GenAI gateway. Both speak the Anthropic Messages
 * API and return the same {@link InvokeResult}, so callers never learn which transport they hold.
 *
 * <p>Implementations must block until the completion is available. The gateway is asynchronous
 * underneath, but it hides its submit-and-poll cycle behind these methods rather than leaking a
 * future or a batch id to callers.
 */
public interface LlmClient extends AutoCloseable {

    /**
     * Completes a prompt with an optional single image.
     *
     * @param systemPrompt the system prompt, or null/blank to send none
     * @param userText     the user text, appended after the image
     * @param image        PNG or JPEG bytes, or null for a text-only call
     * @return the assistant text and the token usage it consumed
     */
    InvokeResult invokeWithVision(String systemPrompt, String userText, byte[] image);

    /**
     * Completes a prompt with an ordered set of images, such as frames sampled from a recording.
     *
     * @param systemPrompt the system prompt, or null/blank to send none
     * @param userText     the user text, appended after the images
     * @param images       ordered PNG or JPEG frames; null and empty entries are skipped
     * @return the assistant text and the token usage it consumed
     */
    InvokeResult invokeWithMultipleImages(String systemPrompt, String userText, List<byte[]> images);

    /** The model this client resolves to, as used for cost attribution. */
    String modelId();

    /** Releases transport resources. Narrowed from {@link AutoCloseable} so callers need no catch. */
    @Override
    void close();
}
