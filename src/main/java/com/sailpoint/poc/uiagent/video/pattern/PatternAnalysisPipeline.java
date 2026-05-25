package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs all pattern detectors in order (REQ-FS-3 / REQ-FS-4.1).
 */
public final class PatternAnalysisPipeline {

    private final List<PatternDetector> detectors;

    public PatternAnalysisPipeline(VideoConfig config) {
        detectors = List.of(
                new NavigationPatternDetector(config),
                new TypingPatternDetector(config),
                new AnimationPatternDetector(config),
                new NewElementPatternDetector(config),
                new CursorPatternDetector(config),
                new ScrollPatternDetector(config));
    }

    public void analyze(List<ScoredFrame> frames) {
        for (PatternDetector detector : detectors) {
            detector.apply(frames);
        }
    }

    public static List<PatternDetector> buildDetectors(VideoConfig config) {
        return new ArrayList<>(List.of(
                new NavigationPatternDetector(config),
                new TypingPatternDetector(config),
                new AnimationPatternDetector(config),
                new NewElementPatternDetector(config),
                new CursorPatternDetector(config),
                new ScrollPatternDetector(config)));
    }
}
