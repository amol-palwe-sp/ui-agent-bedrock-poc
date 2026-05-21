package com.sailpoint.poc.uiagent.video;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Extracts meaningful keyframes from an MP4 video file using OpenCV.
 * Frames are selected based on visual change detection to capture significant UI state changes.
 */
public final class VideoFrameExtractor {

    static {
        nu.pattern.OpenCV.loadLocally();
    }

    private final int maxFrames;
    private final double changeThreshold;
    private final double minGapSeconds;
    /**
     * Maximum seconds allowed between consecutively kept frames before a frame is forced through
     * regardless of pixel-change magnitude. Catches subtle UI changes (checkbox toggles, radio
     * button selections, small highlights) that fall below {@code changeThreshold}.
     * Set to {@code Double.MAX_VALUE} to disable forced capture.
     */
    private final double maxForcedGapSeconds;
    /**
     * Maximum frame width in pixels before downscaling. Frames wider than this are scaled down
     * proportionally before encoding. Reduces both HTTP payload size and Claude token cost
     * (tokens scale with pixel count). Set to {@code 0} to disable resizing.
     */
    private final int frameMaxWidth;
    /**
     * JPEG quality for frame encoding (1–100). Lower values shrink payload at some visual cost.
     * 75 is a good balance for UI screenshots. Replaces the previous lossless PNG encoding.
     */
    private final int jpegQuality;
    private final String debugOutputDir;

    public VideoFrameExtractor(int maxFrames, double changeThreshold, double minGapSeconds,
                               double maxForcedGapSeconds, int frameMaxWidth, int jpegQuality,
                               String debugOutputDir) {
        this.maxFrames = maxFrames;
        this.changeThreshold = changeThreshold;
        this.minGapSeconds = minGapSeconds;
        this.maxForcedGapSeconds = maxForcedGapSeconds;
        this.frameMaxWidth = frameMaxWidth;
        this.jpegQuality = jpegQuality;
        this.debugOutputDir = debugOutputDir;
    }

    public VideoFrameExtractor() {
        this(80, 0.02, 0.5, 3.0, 1280, 75, null);
    }

    /**
     * Extracts keyframes from the specified video file.
     *
     * @param videoPath path to the MP4 video file
     * @return ordered list of JPEG-encoded frames as byte arrays
     * @throws IOException if the video cannot be read or frames cannot be processed
     */
    public List<byte[]> extractFrames(String videoPath) throws IOException {
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found: " + videoPath);
        }
        if (!videoPath.toLowerCase().endsWith(".mp4")) {
            throw new IOException("Only MP4 files are supported: " + videoPath);
        }

        VideoCapture capture = new VideoCapture(videoPath);
        if (!capture.isOpened()) {
            throw new IOException("Failed to open video: " + videoPath);
        }

        try {
            double fps = capture.get(Videoio.CAP_PROP_FPS);
            if (fps <= 0) fps = 30.0;

            List<FrameData> keptFrames = new ArrayList<>();
            Mat currentFrame = new Mat();
            Mat previousGray = null;
            int frameIndex = 0;
            double lastKeptTime = -minGapSeconds;

            while (capture.read(currentFrame)) {
                double currentTime = frameIndex / fps;
                
                Mat currentGray = new Mat();
                Imgproc.cvtColor(currentFrame, currentGray, Imgproc.COLOR_BGR2GRAY);

                boolean keepFrame = false;
                double changePercent = 0.0;

                if (previousGray == null) {
                    keepFrame = true;
                } else if (currentTime - lastKeptTime >= minGapSeconds) {
                    changePercent = computeChangePercent(previousGray, currentGray);
                    keepFrame = changePercent >= changeThreshold
                            || (currentTime - lastKeptTime >= maxForcedGapSeconds);
                }

                if (keepFrame) {
                    byte[] pngBytes = matToJpeg(currentFrame);
                    keptFrames.add(new FrameData(frameIndex, currentTime, changePercent, pngBytes));
                    lastKeptTime = currentTime;
                    if (previousGray != null) previousGray.release();
                    previousGray = currentGray;
                } else {
                    currentGray.release();
                }

                frameIndex++;
            }

            if (previousGray != null) previousGray.release();
            currentFrame.release();

            List<byte[]> result = selectFinalFrames(keptFrames);

            if (debugOutputDir != null && !debugOutputDir.isBlank()) {
                saveDebugFrames(keptFrames, result);
            }

            return result;

        } finally {
            capture.release();
        }
    }

    private double computeChangePercent(Mat prev, Mat curr) {
        Mat diff = new Mat();
        Core.absdiff(prev, curr, diff);
        double totalPixels = diff.rows() * diff.cols();
        double changedPixels = Core.countNonZero(diff);
        diff.release();
        return changedPixels / totalPixels;
    }

    /**
     * Encodes a frame as JPEG, optionally downscaling it first.
     *
     * <p>Downscaling reduces both the HTTP payload sent to Bedrock and the Claude token cost
     * (which scales with pixel count). Switching from lossless PNG to JPEG at quality 75
     * cuts payload by ~90% for a typical 1920×1080 browser screenshot with no meaningful
     * loss of UI text legibility.
     */
    private byte[] matToJpeg(Mat frame) {
        Mat toEncode = frame;
        Mat resized  = null;

        if (frameMaxWidth > 0 && frame.cols() > frameMaxWidth) {
            double scale = (double) frameMaxWidth / frame.cols();
            int targetHeight = (int) Math.round(frame.rows() * scale);
            resized = new Mat();
            Imgproc.resize(frame, resized, new Size(frameMaxWidth, targetHeight));
            toEncode = resized;
        }

        try {
            MatOfByte buffer = new MatOfByte();
            MatOfInt params  = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality);
            Imgcodecs.imencode(".jpg", toEncode, buffer, params);
            byte[] bytes = buffer.toArray();
            buffer.release();
            params.release();
            return bytes;
        } finally {
            if (resized != null) resized.release();
        }
    }

    /**
     * Reduces {@code keptFrames} to at most {@code maxFrames} while preserving temporal
     * coverage across the entire recording.
     *
     * <p>When the frame count exceeds the cap the timeline is divided into equal-width time
     * buckets and the highest-change frame within each bucket is kept.  This guarantees that
     * every segment of the recording is represented — a low-change action (checkbox toggle,
     * radio button click) near the end of the video can no longer be crowded out by
     * high-change animated transitions that occurred earlier.
     */
    private List<byte[]> selectFinalFrames(List<FrameData> keptFrames) {
        if (keptFrames.isEmpty()) {
            return new ArrayList<>();
        }

        if (keptFrames.size() <= maxFrames) {
            List<byte[]> result = new ArrayList<>();
            for (FrameData fd : keptFrames) {
                result.add(fd.pngBytes);
            }
            return result;
        }

        List<byte[]> result = new ArrayList<>();
        result.add(keptFrames.get(0).pngBytes);

        int middleSlots = maxFrames - 2;
        List<FrameData> middleFrames = new ArrayList<>(keptFrames.subList(1, keptFrames.size() - 1));

        List<FrameData> selected;
        if (middleFrames.size() <= middleSlots) {
            selected = middleFrames;
        } else {
            // Stratified temporal sampling: pick best frame per equal-width time bucket
            double firstTime  = middleFrames.get(0).frameTime();
            double lastTime   = middleFrames.get(middleFrames.size() - 1).frameTime();
            double bucketSize = (lastTime - firstTime) / middleSlots;

            selected = new ArrayList<>();
            for (int b = 0; b < middleSlots; b++) {
                double bucketStart = firstTime + b * bucketSize;
                double bucketEnd   = (b == middleSlots - 1)
                        ? lastTime + 0.001   // inclusive of the last frame
                        : bucketStart + bucketSize;
                middleFrames.stream()
                        .filter(f -> f.frameTime() >= bucketStart && f.frameTime() < bucketEnd)
                        .max(Comparator.comparingDouble(FrameData::changePercent))
                        .ifPresent(selected::add);
            }
            selected.sort(Comparator.comparingInt(FrameData::frameIndex));
        }

        for (FrameData fd : selected) {
            result.add(fd.pngBytes);
        }

        result.add(keptFrames.get(keptFrames.size() - 1).pngBytes);

        return result;
    }

    private void saveDebugFrames(List<FrameData> allKeptFrames, List<byte[]> finalFrames) throws IOException {
        Path debugDir = Path.of(debugOutputDir);
        
        if (Files.exists(debugDir)) {
            Files.walk(debugDir)
                    .sorted(Comparator.reverseOrder())
                    .filter(p -> p.toString().endsWith(".jpg") || p.toString().endsWith(".png"))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {}
                    });
        } else {
            Files.createDirectories(debugDir);
        }

        int index = 0;
        for (FrameData fd : allKeptFrames) {
            boolean isSelected = false;
            for (byte[] finalFrame : finalFrames) {
                if (finalFrame == fd.pngBytes) {
                    isSelected = true;
                    break;
                }
            }

            String prefix = isSelected ? "SELECTED_" : "dropped_";
            String filename = String.format("%sframe%04d_%.2fs.jpg", prefix, fd.frameIndex, fd.frameTime);
            Path filePath = debugDir.resolve(filename);
            Files.write(filePath, fd.pngBytes);
            index++;
        }

        System.out.printf("Debug frames saved to: %s (%d files)%n", debugDir.toAbsolutePath(), index);
    }

    private record FrameData(int frameIndex, double frameTime, double changePercent, byte[] pngBytes) {}
}
