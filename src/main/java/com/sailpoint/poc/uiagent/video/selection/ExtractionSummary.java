package com.sailpoint.poc.uiagent.video.selection;

import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** REQ-FS-8.2 summary statistics. */
public final class ExtractionSummary {

    private final double videoDurationSeconds;
    private final int totalFramesRead;
    private final int candidateCount;
    private final int finalCount;
    private final int duplicatesRemoved;
    private final double maxGapSeconds;
    private final Map<PatternType, Integer> patternCounts;

    public ExtractionSummary(
            double videoDurationSeconds,
            int totalFramesRead,
            int candidateCount,
            List<ScoredFrame> allCandidates,
            List<ScoredFrame> selected,
            int duplicatesRemoved,
            double maxGapSeconds) {
        this.videoDurationSeconds = videoDurationSeconds;
        this.totalFramesRead = totalFramesRead;
        this.candidateCount = candidateCount;
        this.finalCount = selected.size();
        this.duplicatesRemoved = duplicatesRemoved;
        this.maxGapSeconds = maxGapSeconds;
        this.patternCounts = new EnumMap<>(PatternType.class);
        for (PatternType p : PatternType.values()) {
            patternCounts.put(p, 0);
        }
        for (ScoredFrame f : allCandidates) {
            patternCounts.merge(f.patternType(), 1, Integer::sum);
        }
    }

    public void print(String videoPath) {
        System.out.printf("[FrameExtraction] Video: %.1fs, %d total frames%n",
                videoDurationSeconds, totalFramesRead);
        System.out.printf("[FrameExtraction] Phase 1 candidates: %d frames%n", candidateCount);
        System.out.println("[FrameExtraction] Pattern breakdown:");
        for (Map.Entry<PatternType, Integer> e : patternCounts.entrySet()) {
            if (e.getValue() > 0) {
                System.out.printf("    %s: %d frames%n", e.getKey(), e.getValue());
            }
        }
        System.out.printf("[FrameExtraction] Final selected: %d frames%n", finalCount);
        System.out.printf("[FrameExtraction] Coverage: max gap = %.1fs%n", maxGapSeconds);
        System.out.printf("[FrameExtraction] Duplicates removed: %d%n", duplicatesRemoved);
    }
}
