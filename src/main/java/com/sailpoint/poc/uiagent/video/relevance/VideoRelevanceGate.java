package com.sailpoint.poc.uiagent.video.relevance;

import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.config.RelevanceConfig;
import com.sailpoint.poc.uiagent.llm.InvokeResult;
import com.sailpoint.poc.uiagent.llm.LlmClient;
import com.sailpoint.poc.uiagent.llm.LlmClientFactory;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-flight gate that rejects videos which cannot yield meaningful navigation steps.
 *
 * <p>Runs between frame extraction and the main step-generation call. It re-uses a small
 * evenly spaced sample of the frames that were already extracted — roughly a tenth of the
 * images the main call sends — so triage costs a small fraction of the work it can avoid.
 *
 * <p>The gate fails open. Any error reaching the model, or any response it cannot parse,
 * yields an accept: an outage must degrade the guardrail, not the product.
 */
public final class VideoRelevanceGate {

    private static final String CATEGORY_USABLE = "USABLE";

    private final RelevanceConfig config;
    private final LlmClientFactory clients;

    public VideoRelevanceGate(RelevanceConfig config, LlmClientFactory clients) {
        this.config = config;
        this.clients = clients;
    }

    /**
     * Classifies a video from its extracted frames.
     *
     * @param frames the frames already extracted for step generation, in chronological order
     * @return the verdict; never null, and never a rejection when the classifier was unreachable
     */
    public RelevanceResult evaluate(List<byte[]> frames) {
        if (!config.enabled()) {
            return RelevanceResult.skipped();
        }
        if (frames == null || frames.isEmpty()) {
            return RelevanceResult.unavailable("no frames to classify");
        }

        List<byte[]> sample = sampleEvenly(frames, config.sampleFrames());
        String modelId = config.resolveModelId(clients.defaultModelId());

        try (LlmClient client = clients.create("triage", modelId, config.maxTokens(), 0.0)) {

            InvokeResult response = client.invokeWithMultipleImages(
                    VideoRelevancePrompt.SYSTEM_PROMPT, VideoRelevancePrompt.USER_PROMPT, sample);

            return interpret(response.text(), response.usage());

        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return RelevanceResult.unavailable(detail);
        }
    }

    /**
     * Picks {@code count} frames spread evenly across the timeline, always including the first
     * and last so the classifier sees both where the recording started and where it ended.
     */
    static List<byte[]> sampleEvenly(List<byte[]> frames, int count) {
        int target = Math.max(1, count);
        if (frames.size() <= target) {
            return List.copyOf(frames);
        }
        List<byte[]> sample = new ArrayList<>(target);
        if (target == 1) {
            sample.add(frames.get(0));
            return sample;
        }
        double stride = (frames.size() - 1) / (double) (target - 1);
        for (int i = 0; i < target; i++) {
            sample.add(frames.get((int) Math.round(i * stride)));
        }
        return sample;
    }

    /**
     * Turns the classifier's raw JSON into a verdict, applying the confidence floor and the
     * out-of-domain policy. Anything unparseable is an accept, not a rejection.
     */
    private RelevanceResult interpret(String rawText, TokenUsage usage) {
        JSONObject json;
        try {
            json = BedrockAnthropicClient.parseModelJson(rawText);
        } catch (Exception e) {
            return RelevanceResult.unavailable("could not parse classifier response");
        }

        String category = json.optString("category", "").trim().toUpperCase();
        int confidence = clampConfidence(json.optInt("confidence", 0));
        String reason = json.optString("reason", "").trim();
        String taskType = json.optString("detectedTaskType", "UNKNOWN").trim().toUpperCase();

        if (category.isEmpty() || CATEGORY_USABLE.equals(category)) {
            return RelevanceResult.accepted(confidence, reason, taskType, usage);
        }

        RejectionCategory rejection = RejectionCategory.from(category);
        if (rejection == RejectionCategory.NONE) {
            return RelevanceResult.accepted(confidence, reason, taskType, usage);
        }

        // A consumer web app is still a real, analysable UI. Whether that counts as
        // out of scope is a product decision, so it stays configurable.
        if (rejection == RejectionCategory.OUT_OF_DOMAIN && !config.rejectOutOfDomain()) {
            return RelevanceResult.uncertain(confidence,
                    reason.isBlank() ? rejection.defaultReason() : reason, taskType, usage);
        }

        if (confidence < config.minConfidence()) {
            return RelevanceResult.uncertain(confidence,
                    "Possibly unusable (" + rejection.name() + ", confidence " + confidence
                            + "): " + (reason.isBlank() ? rejection.defaultReason() : reason),
                    taskType, usage);
        }

        return RelevanceResult.rejected(rejection, confidence, reason, usage);
    }

    private static int clampConfidence(int raw) {
        return Math.max(0, Math.min(100, raw));
    }
}
