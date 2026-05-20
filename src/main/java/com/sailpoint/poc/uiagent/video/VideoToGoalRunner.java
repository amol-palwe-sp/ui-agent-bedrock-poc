package com.sailpoint.poc.uiagent.video;

import com.sailpoint.poc.uiagent.PocConfig;
import com.sailpoint.poc.uiagent.TokenUsage;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient;
import com.sailpoint.poc.uiagent.bedrock.BedrockAnthropicClient.InvokeResult;

import java.io.File;
import java.util.List;

/**
 * CLI entry point for the video → goal pipeline.
 * Extracts frames from a video file and uses Claude to generate a goal command.
 *
 * <p>Usage: {@code ./gradlew runVideo --args='--video=/path/to/recording.mp4'}
 *
 * <p>Optional flags:
 * <ul>
 *   <li>{@code --debug-frames=./debug_frames} — save extracted frames to disk</li>
 *   <li>{@code --max-frames=80} — override frame cap</li>
 *   <li>{@code --url=https://...} — override URL (skips VLM URL detection)</li>
 * </ul>
 */
public final class VideoToGoalRunner {

    public static void main(String[] args) {
        try {
            int exitCode = run(args);
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static int run(String[] args) throws Exception {
        String videoPath = null;
        String debugFramesDir = null;
        Integer maxFrames = null;
        String overrideUrl = null;

        for (String arg : args) {
            if (arg.startsWith("--video=")) {
                videoPath = arg.substring("--video=".length());
            } else if (arg.startsWith("--debug-frames=")) {
                debugFramesDir = arg.substring("--debug-frames=".length());
            } else if (arg.startsWith("--max-frames=")) {
                maxFrames = Integer.parseInt(arg.substring("--max-frames=".length()));
            } else if (arg.startsWith("--url=")) {
                overrideUrl = arg.substring("--url=".length());
            }
        }

        if (videoPath == null || videoPath.isBlank()) {
            System.err.println("Usage: ./gradlew runVideo --args='--video=/path/to/recording.mp4'");
            System.err.println();
            System.err.println("Required:");
            System.err.println("  --video=PATH        Path to .mp4 video file");
            System.err.println();
            System.err.println("Optional:");
            System.err.println("  --debug-frames=DIR  Save extracted frames to directory");
            System.err.println("  --max-frames=N      Maximum frames to extract (default: 80)");
            System.err.println("  --url=URL           Override URL (skips VLM URL detection)");
            return 1;
        }

        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            System.err.println("Video file not found: " + videoPath);
            return 1;
        }
        if (!videoPath.toLowerCase().endsWith(".mp4")) {
            System.err.println("Only MP4 files are supported: " + videoPath);
            return 1;
        }

        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("VIDEO TO GOAL PIPELINE");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("Video: " + videoFile.getAbsolutePath());
        System.out.println();

        PocConfig config = new PocConfig();

        int effectiveMaxFrames = maxFrames != null ? maxFrames : 
                Integer.parseInt(config.optional("video.max.frames", "80"));
        double changeThreshold = Double.parseDouble(config.optional("video.change.threshold", "0.02"));
        double minGapSeconds = Double.parseDouble(config.optional("video.min.gap.seconds", "0.5"));
        String effectiveDebugDir = debugFramesDir != null ? debugFramesDir : 
                config.optional("video.debug.frames.dir", "");

        if (effectiveDebugDir.isBlank()) {
            effectiveDebugDir = null;
        }

        System.out.println("Frame extraction settings:");
        System.out.println("  Max frames: " + effectiveMaxFrames);
        System.out.println("  Change threshold: " + (changeThreshold * 100) + "%");
        System.out.println("  Min gap: " + minGapSeconds + "s");
        if (effectiveDebugDir != null) {
            System.out.println("  Debug output: " + effectiveDebugDir);
        }
        System.out.println();

        System.out.println("Extracting frames from video...");
        VideoFrameExtractor extractor = new VideoFrameExtractor(
                effectiveMaxFrames, changeThreshold, minGapSeconds, effectiveDebugDir);
        
        List<byte[]> frames = extractor.extractFrames(videoPath);
        System.out.println("Extracted " + frames.size() + " frames from video");
        System.out.println();

        if (frames.isEmpty()) {
            System.err.println("No frames extracted from video. The video may be empty or corrupted.");
            return 1;
        }

        System.out.println("Invoking Claude with " + frames.size() + " frames...");
        System.out.println("Model: " + config.bedrockModelId());
        System.out.println();

        String userPrompt = overrideUrl != null ? 
                VideoToGoalPrompt.userPromptWithUrl(overrideUrl) : 
                VideoToGoalPrompt.USER_PROMPT;

        InvokeResult result;
        try (BedrockAnthropicClient client = new BedrockAnthropicClient(
                config.awsRegion(),
                config.awsProfile(),
                config.bedrockModelId(),
                config.maxTokens(),
                config.temperature())) {
            
            result = client.invokeWithMultipleImages(
                    VideoToGoalPrompt.SYSTEM_PROMPT,
                    userPrompt,
                    frames);
        }

        TokenUsage usage = result.usage();
        System.out.println("Token usage: " + usage);
        System.out.println();

        GoalExtractor.ExtractionResult extraction = GoalExtractor.extract(result.text());
        extraction.print();

        return extraction.isValid() ? 0 : 1;
    }
}
