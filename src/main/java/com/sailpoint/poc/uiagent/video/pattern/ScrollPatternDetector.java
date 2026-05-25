package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.List;

/** REQ-FS-3.6 */
final class ScrollPatternDetector implements PatternDetector {

    private final VideoConfig config;

    ScrollPatternDetector(VideoConfig config) {
        this.config = config;
    }

    @Override
    public void apply(List<ScoredFrame> frames) {
        int i = 0;
        while (i < frames.size()) {
            int start = i;
            while (i < frames.size() && frames.get(i).diff().changedCellFraction() > config.patternScrollThreshold()) {
                i++;
            }
            int len = i - start;
            if (len >= 2) {
                for (int j = start; j < i - 1; j++) {
                    ScoredFrame f = frames.get(j);
                    if (f.patternType() == PatternType.NORMAL) {
                        f.setPatternType(PatternType.SCROLL_MID);
                    }
                    f.multiplyPattern(config.patternScrollMidPenalty());
                }
                ScoredFrame last = frames.get(i - 1);
                last.setPatternType(PatternType.SCROLL_END);
                last.multiplyPattern(config.patternScrollEndBonus());
            }
            if (i == start) {
                i++;
            }
        }
    }
}
