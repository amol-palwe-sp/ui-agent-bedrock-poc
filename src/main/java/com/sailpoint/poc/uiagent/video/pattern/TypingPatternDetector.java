package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.List;

/** REQ-FS-3.2 */
final class TypingPatternDetector implements PatternDetector {

    private final VideoConfig config;

    TypingPatternDetector(VideoConfig config) {
        this.config = config;
    }

    @Override
    public void apply(List<ScoredFrame> frames) {
        int i = 0;
        while (i < frames.size()) {
            int start = i;
            while (i < frames.size() && isTypingFrame(frames, start, i)) {
                i++;
            }
            int len = i - start;
            if (len >= config.patternTypingMinFrames()) {
                for (int j = start; j < i - 1; j++) {
                    ScoredFrame f = frames.get(j);
                    f.setPatternType(PatternType.TYPING_MID);
                    f.multiplyPattern(config.patternTypingMidPenalty());
                }
                ScoredFrame last = frames.get(i - 1);
                last.setPatternType(PatternType.TYPING_END);
                last.multiplyPattern(config.patternTypingEndBonus());
            }
            if (i == start) {
                i++;
            }
        }
    }

    private boolean isTypingFrame(List<ScoredFrame> frames, int start, int index) {
        ScoredFrame f = frames.get(index);
        if (f.changedCells() > config.patternTypingMaxCells()) {
            return false;
        }
        if (f.maxCellChange() > config.patternTypingMaxCellChange()) {
            return false;
        }
        if (index == start) {
            return f.changedCells() > 0;
        }
        return GridCellPatternHelper.overlapChangedCells(
                frames.get(start).diff(), f.diff()) >= 1
                || GridCellPatternHelper.sameChangedCells(frames.get(index - 1).diff(), f.diff());
    }
}
