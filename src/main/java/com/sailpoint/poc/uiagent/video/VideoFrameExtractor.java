package com.sailpoint.poc.uiagent.video;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.grid.GridDiffCalculator;
import com.sailpoint.poc.uiagent.video.grid.GridDiffResult;
import com.sailpoint.poc.uiagent.video.grid.ScreenZone;
import com.sailpoint.poc.uiagent.video.grid.ZoneWeightMap;
import com.sailpoint.poc.uiagent.video.pattern.PatternAnalysisPipeline;
import com.sailpoint.poc.uiagent.video.scoring.FrameScorer;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;
import com.sailpoint.poc.uiagent.video.selection.ExtractionSummary;
import com.sailpoint.poc.uiagent.video.selection.FrameSelector;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts meaningful keyframes from an MP4 using grid differential analysis,
 * zone weighting, pattern detection, and score-based selection (REQ-FS-7.2).
 */
public final class VideoFrameExtractor {

    static {
        nu.pattern.OpenCV.loadLocally();
    }

    private final VideoConfig config;

    public VideoFrameExtractor(VideoConfig config) {
        this.config = config;
    }

    /** @param maxFramesOverride overrides {@link VideoConfig#maxFrames()} for this run */
    public VideoFrameExtractor(VideoConfig config, int maxFramesOverride) {
        this.config = config.withMaxFrames(maxFramesOverride);
    }

    /** Backward-compatible constructor (REQ-FS-7.3). */
    public VideoFrameExtractor(int maxFrames, double changeThreshold, double minGapSeconds,
                               double maxForcedGapSeconds, int frameMaxWidth, int jpegQuality,
                               String debugOutputDir) {
        this(VideoConfig.legacy(maxFrames, changeThreshold, minGapSeconds, maxForcedGapSeconds,
                frameMaxWidth, jpegQuality, debugOutputDir));
    }

    public VideoFrameExtractor() {
        this(VideoConfig.defaults());
    }

    /**
     * @param videoPath path to the MP4 video file
     * @return ordered list of JPEG-encoded frames as byte arrays
     */
    public List<byte[]> extractFrames(String videoPath) throws IOException {
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found: " + videoPath);
        }
        if (!videoPath.toLowerCase().endsWith(".mp4")) {
            throw new IOException("Only MP4 files are supported: " + videoPath);
        }

        GridDiffCalculator diffCalc = new GridDiffCalculator(config);
        ZoneWeightMap zoneMap = diffCalc.zoneMap();
        FrameScorer scorer = new FrameScorer(config);
        PatternAnalysisPipeline patterns = new PatternAnalysisPipeline(config);
        FrameSelector selector = new FrameSelector(config, diffCalc);

        VideoCapture capture = new VideoCapture(videoPath);
        if (!capture.isOpened()) {
            throw new IOException("Failed to open video: " + videoPath);
        }

        try {
            double fps = capture.get(Videoio.CAP_PROP_FPS);
            if (fps <= 0) {
                fps = 30.0;
            }
            double frameCount = capture.get(Videoio.CAP_PROP_FRAME_COUNT);
            double duration = frameCount > 0 ? frameCount / fps : 0.0;

            ScanResult scan = scanAndScore(capture, fps, diffCalc, scorer);
            List<ScoredFrame> candidates = scan.frames();
            int totalFramesRead = scan.totalFramesRead();

            markMandatory(candidates, zoneMap);
            patterns.analyze(candidates);

            FrameSelector.SelectionResult selection =
                    selector.select(candidates, config.maxFrames());

            encodeSelectedFrames(videoPath, selection.frames());

            if (config.debugFramesDir() != null && !config.debugFramesDir().isBlank()) {
                encodeAllCandidateFrames(videoPath, candidates);
                saveDebugFrames(candidates, selection.frames());
            }

            ExtractionSummary summary = new ExtractionSummary(
                    duration,
                    totalFramesRead,
                    candidates.size(),
                    candidates,
                    selection.frames(),
                    selection.duplicatesRemoved(),
                    selection.maxGapSeconds());
            summary.print(videoPath);

            List<byte[]> result = new ArrayList<>();
            for (ScoredFrame f : selection.frames()) {
                result.add(f.jpegBytes());
            }
            return result;

        } finally {
            capture.release();
        }
    }

    private record ScanResult(List<ScoredFrame> frames, int totalFramesRead) {}

    private ScanResult scanAndScore(
            VideoCapture capture,
            double fps,
            GridDiffCalculator diffCalc,
            FrameScorer scorer) {
        List<ScoredFrame> candidates = new ArrayList<>();
        Mat currentFrame = new Mat();
        Mat previousGray = null;
        int frameIndex = 0;

        while (capture.read(currentFrame)) {
            double frameTime = frameIndex / fps;
            Mat currentGray = new Mat();
            Imgproc.cvtColor(currentFrame, currentGray, Imgproc.COLOR_BGR2GRAY);

            GridDiffResult diff = previousGray == null
                    ? diffCalc.firstFrame()
                    : diffCalc.compute(previousGray, currentGray);

            candidates.add(scorer.score(frameIndex, frameTime, diff));

            if (previousGray != null) {
                previousGray.release();
            }
            previousGray = currentGray;
            frameIndex++;
        }

        if (previousGray != null) {
            previousGray.release();
        }
        currentFrame.release();
        return new ScanResult(candidates, frameIndex);
    }

    private void markMandatory(List<ScoredFrame> frames, ZoneWeightMap zoneMap) {
        if (!frames.isEmpty()) {
            ScoredFrame first = frames.get(0);
            first.setMandatory(true);
            first.setPatternType(PatternType.MANDATORY);
        }
        for (ScoredFrame f : frames) {
            if (urlBarSignificant(f.diff(), zoneMap)) {
                f.setMandatory(true);
            }
        }
    }

    private boolean urlBarSignificant(GridDiffResult diff, ZoneWeightMap zoneMap) {
        boolean[][] changed = diff.cellChanged();
        int n = changed.length;
        int urlTotal = 0;
        int urlChanged = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (zoneMap.zone(r, c) == ScreenZone.URL_BAR) {
                    urlTotal++;
                    if (changed[r][c]) {
                        urlChanged++;
                    }
                }
            }
        }
        if (urlTotal == 0) {
            return false;
        }
        return ((double) urlChanged / urlTotal) >= config.urlBarMandatoryThreshold();
    }

    private void encodeSelectedFrames(String videoPath, List<ScoredFrame> selected) throws IOException {
        if (selected.isEmpty()) {
            return;
        }
        Set<Integer> needed = new HashSet<>();
        for (ScoredFrame f : selected) {
            needed.add(f.frameIndex());
        }
        Map<Integer, byte[]> encoded = new HashMap<>();

        VideoCapture capture = new VideoCapture(videoPath);
        if (!capture.isOpened()) {
            throw new IOException("Failed to re-open video for encoding: " + videoPath);
        }
        try {
            Mat frame = new Mat();
            int index = 0;
            while (capture.read(frame)) {
                if (needed.contains(index)) {
                    encoded.put(index, matToJpeg(frame));
                }
                index++;
                if (encoded.size() == needed.size()) {
                    break;
                }
            }
            frame.release();
        } finally {
            capture.release();
        }

        for (ScoredFrame f : selected) {
            byte[] bytes = encoded.get(f.frameIndex());
            if (bytes == null) {
                throw new IOException("Failed to encode frame index " + f.frameIndex());
            }
            f.setJpegBytes(bytes);
        }
    }

    private byte[] matToJpeg(Mat frame) {
        Mat toEncode = frame;
        Mat resized = null;

        if (config.frameMaxWidth() > 0 && frame.cols() > config.frameMaxWidth()) {
            double scale = (double) config.frameMaxWidth() / frame.cols();
            int targetHeight = (int) Math.round(frame.rows() * scale);
            resized = new Mat();
            Imgproc.resize(frame, resized, new Size(config.frameMaxWidth(), targetHeight));
            toEncode = resized;
        }

        try {
            MatOfByte buffer = new MatOfByte();
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, config.jpegQuality());
            Imgcodecs.imencode(".jpg", toEncode, buffer, params);
            byte[] bytes = buffer.toArray();
            buffer.release();
            params.release();
            return bytes;
        } finally {
            if (resized != null) {
                resized.release();
            }
        }
    }

    private void saveDebugFrames(List<ScoredFrame> allCandidates, List<ScoredFrame> selected)
            throws IOException {
        Path debugDir = Path.of(config.debugFramesDir());
        Set<Integer> selectedIdx = new HashSet<>();
        for (ScoredFrame s : selected) {
            selectedIdx.add(s.frameIndex());
        }

        if (Files.exists(debugDir)) {
            Files.walk(debugDir)
                    .sorted(Comparator.reverseOrder())
                    .filter(p -> p.toString().endsWith(".jpg") || p.toString().endsWith(".png"))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
        } else {
            Files.createDirectories(debugDir);
        }

        int written = 0;
        for (ScoredFrame f : allCandidates) {
            byte[] bytes = f.jpegBytes();
            if (bytes == null && selectedIdx.contains(f.frameIndex())) {
                continue;
            }
            boolean isSelected = selectedIdx.contains(f.frameIndex());
            String prefix = f.isMandatory() ? "MANDATORY_"
                    : isSelected ? "SELECTED_" : "dropped_";
            String filename = String.format(
                    "%sframe%04d_%.2fs_score%.2f_%s.jpg",
                    prefix,
                    f.frameIndex(),
                    f.frameTime(),
                    f.finalScore(),
                    f.patternType());
            if (bytes != null) {
                Files.write(debugDir.resolve(filename), bytes);
                written++;
            }
        }

        System.out.printf("Debug frames saved to: %s (%d files)%n", debugDir.toAbsolutePath(), written);
    }

    private void encodeAllCandidateFrames(String videoPath, List<ScoredFrame> candidates)
            throws IOException {
        Set<Integer> needed = new HashSet<>();
        for (ScoredFrame f : candidates) {
            if (f.jpegBytes() == null) {
                needed.add(f.frameIndex());
            }
        }
        if (needed.isEmpty()) {
            return;
        }
        Map<Integer, byte[]> encoded = new HashMap<>();
        VideoCapture capture = new VideoCapture(videoPath);
        if (!capture.isOpened()) {
            return;
        }
        try {
            Mat frame = new Mat();
            int index = 0;
            while (capture.read(frame)) {
                if (needed.contains(index)) {
                    encoded.put(index, matToJpeg(frame));
                }
                index++;
                if (encoded.size() == needed.size()) {
                    break;
                }
            }
            frame.release();
        } finally {
            capture.release();
        }
        for (ScoredFrame f : candidates) {
            if (f.jpegBytes() == null) {
                f.setJpegBytes(encoded.get(f.frameIndex()));
            }
        }
    }
}
