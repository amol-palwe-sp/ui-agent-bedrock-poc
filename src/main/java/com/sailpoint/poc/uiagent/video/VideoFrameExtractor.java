package com.sailpoint.poc.uiagent.video;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
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
    private final String debugOutputDir;

    public VideoFrameExtractor(int maxFrames, double changeThreshold, double minGapSeconds, String debugOutputDir) {
        this.maxFrames = maxFrames;
        this.changeThreshold = changeThreshold;
        this.minGapSeconds = minGapSeconds;
        this.debugOutputDir = debugOutputDir;
    }

    public VideoFrameExtractor() {
        this(80, 0.02, 0.5, null);
    }

    /**
     * Extracts keyframes from the specified video file.
     *
     * @param videoPath path to the MP4 video file
     * @return ordered list of PNG-encoded frames as byte arrays
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
                    keepFrame = changePercent >= changeThreshold;
                }

                if (keepFrame) {
                    byte[] pngBytes = matToPng(currentFrame);
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

    private byte[] matToPng(Mat frame) {
        MatOfByte buffer = new MatOfByte();
        Imgcodecs.imencode(".png", frame, buffer);
        byte[] bytes = buffer.toArray();
        buffer.release();
        return bytes;
    }

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
        List<FrameData> middleFrames = keptFrames.subList(1, keptFrames.size() - 1);
        middleFrames.sort(Comparator.comparingDouble((FrameData fd) -> fd.changePercent).reversed());

        List<FrameData> selected = new ArrayList<>(middleFrames.subList(0, Math.min(middleSlots, middleFrames.size())));
        selected.sort(Comparator.comparingInt(fd -> fd.frameIndex));

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
                    .filter(p -> p.toString().endsWith(".png"))
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
            String filename = String.format("%sframe%04d_%.2fs.png", prefix, fd.frameIndex, fd.frameTime);
            Path filePath = debugDir.resolve(filename);
            Files.write(filePath, fd.pngBytes);
            index++;
        }

        System.out.printf("Debug frames saved to: %s (%d files)%n", debugDir.toAbsolutePath(), index);
    }

    private record FrameData(int frameIndex, double frameTime, double changePercent, byte[] pngBytes) {}
}
