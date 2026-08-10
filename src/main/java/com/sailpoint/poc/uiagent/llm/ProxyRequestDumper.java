package com.sailpoint.poc.uiagent.llm;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.config.LlmProxyConfig;
import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.eval.benchmark.EvalCase;
import com.sailpoint.poc.uiagent.video.VideoFrameExtractor;
import com.sailpoint.poc.uiagent.video.VideoToGoalPrompt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Writes the exact GenAI gateway request body the POC would send for one video, without sending it.
 *
 * <p>Built to investigate why the gateway parks large video prompts in {@code ACCEPTED} forever
 * rather than rejecting them. Reproducing that by hand needs the real body, and the real body is
 * several megabytes of base64 frames that cannot be typed out — so this dumps it to a file that can
 * be replayed through Postman or curl.
 *
 * <p>Fidelity is the point: frames come from {@link VideoFrameExtractor} and the body from
 * {@link AnthropicMessages#buildBody}, the same code the live path uses. What lands in the file is
 * what {@link LlmProxyClient} would have put on the wire, so a manual reproduction proves something
 * about the POC rather than about the dumper.
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew dumpProxyRequest -Pargs="--case=eval_001"
 *   ./gradlew dumpProxyRequest -Pargs="--video=/path/to.mp4 --max-frames=30"
 * </pre>
 */
public final class ProxyRequestDumper {

    private static final String DEFAULT_OUTPUT_DIR = "./proxy-requests";
    private static final String DEFAULT_BENCHMARKS = "./src/main/resources/eval/stage1-dataset.json";

    private ProxyRequestDumper() {}

    public static void main(String[] args) throws Exception {
        String caseId = arg(args, "--case", "");
        String videoPath = arg(args, "--video", "");
        String targetUrl = arg(args, "--url", "");
        String outputDir = arg(args, "--out", DEFAULT_OUTPUT_DIR);
        String benchmarks = arg(args, "--benchmarks", DEFAULT_BENCHMARKS);
        int maxFramesOverride = Integer.parseInt(arg(args, "--max-frames", "0"));
        boolean placeholderMode = true;
        String label = caseId;

        if (caseId.isBlank() && videoPath.isBlank()) {
            System.err.println("Give --case=<id> to reproduce an eval case, or --video=<path> for any video.");
            System.err.println("  ./gradlew dumpProxyRequest -Pargs=\"--case=eval_001\"");
            System.exit(2);
        }

        PocConfig config = new PocConfig();

        if (!caseId.isBlank()) {
            EvalCase evalCase = EvalCase.loadAll(benchmarks).stream()
                    .filter(c -> c.id().equals(caseId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No case \"" + caseId + "\" in " + benchmarks));
            videoPath = evalCase.videoPath();
            targetUrl = evalCase.targetUrl();
            placeholderMode = evalCase.isPlaceholderMode();
            System.out.println("case      : " + caseId + " — " + evalCase.description());
        } else {
            label = Paths.get(videoPath).getFileName().toString().replaceAll("\\.[^.]+$", "");
        }

        if (!Files.exists(Paths.get(videoPath))) {
            System.err.println("Video not found: " + videoPath);
            System.exit(1);
        }

        // Debug frame dumping writes thousands of JPEGs per run and is pure noise here.
        VideoConfig videoConfig = config.video().withDebugFramesDir("");
        if (maxFramesOverride > 0) {
            videoConfig = videoConfig.withMaxFrames(maxFramesOverride);
        }

        System.out.println("video     : " + videoPath);
        List<byte[]> frames = new VideoFrameExtractor(videoConfig).extractFrames(videoPath);
        if (frames.isEmpty()) {
            System.err.println("No frames extracted.");
            System.exit(1);
        }

        String systemPrompt = VideoToGoalPrompt.systemPrompt(placeholderMode);
        String userPrompt = targetUrl == null || targetUrl.isBlank()
                ? VideoToGoalPrompt.USER_PROMPT
                : VideoToGoalPrompt.userPromptWithUrl(targetUrl);

        LlmProxyConfig proxy = config.llmProxy();
        String requestId = "manual-" + (label.isBlank() ? "probe" : label) + "-" + UUID.randomUUID();

        JSONObject modelParams = AnthropicMessages.buildBody(
                systemPrompt, userPrompt, frames, config.maxTokens(), config.temperature());

        JSONObject body = new JSONObject().put("prompts", new JSONArray().put(new JSONObject()
                .put("requestId", requestId)
                .put("reply", new JSONObject().put("type", "db"))
                .put("model", proxy.modelId())
                .put("modelParams", modelParams)));

        String json = body.toString();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        Path file = dir.resolve("request_" + (label.isBlank() ? "probe" : label) + "_"
                + frames.size() + "frames.json");
        Files.write(file, bytes);

        long rawFrameBytes = frames.stream().mapToLong(f -> f.length).sum();

        System.out.println();
        System.out.println("frames    : " + frames.size());
        System.out.println("raw frames: " + mib(rawFrameBytes));
        System.out.println("body size : " + mib(bytes.length) + "   ← this is what the gateway receives");
        System.out.println("requestId : " + requestId);
        System.out.println("model     : " + proxy.modelId());
        System.out.println("written   : " + file.toAbsolutePath());
        System.out.println();
        System.out.println("Send it:");
        System.out.println("  POST " + proxy.completionsUrl());
        System.out.println("  Authorization: Bearer <token from " + proxy.tokenUrl() + ">");
        System.out.println("  X-SailPoint-Experimental: true");
        System.out.println("  Content-Type: application/json");
        System.out.println("  body: the file above");
        System.out.println();
        System.out.println("Then poll it:");
        System.out.println("  ./scripts/gateway-poll.sh " + requestId);
    }

    private static String mib(long bytes) {
        return String.format("%.2f MB (%,d bytes)", bytes / (1024.0 * 1024.0), bytes);
    }

    private static String arg(String[] args, String name, String defaultValue) {
        String prefix = name + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) return a.substring(prefix.length());
        }
        return defaultValue;
    }
}
