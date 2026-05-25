package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.List;

/** REQ-FS-3.1 */
final class NavigationPatternDetector implements PatternDetector {

    private static final double STABLE_FRACTION = 0.10;

    private final VideoConfig config;

    NavigationPatternDetector(VideoConfig config) {
        this.config = config;
    }

    @Override
    public void apply(List<ScoredFrame> frames) {
        for (int i = 0; i < frames.size(); i++) {
            ScoredFrame f = frames.get(i);
            if (f.diff().changedCellFraction() > config.patternNavSpikeThreshold()) {
                f.setPatternType(PatternType.NAVIGATION_SPIKE);
                f.multiplyPattern(config.patternNavSpikePenalty());
            }
        }
        for (int i = 1; i < frames.size(); i++) {
            ScoredFrame prev = frames.get(i - 1);
            ScoredFrame curr = frames.get(i);
            if (prev.patternType() == PatternType.NAVIGATION_SPIKE
                    && curr.diff().changedCellFraction() < STABLE_FRACTION) {
                curr.setPatternType(PatternType.PAGE_SETTLED);
                curr.multiplyPattern(config.patternNavStableBonus());
                curr.setMandatory(true);
                break;
            }
        }
    }
}
