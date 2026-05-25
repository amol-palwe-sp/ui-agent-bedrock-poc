package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.List;

/** REQ-FS-3.3 */
final class AnimationPatternDetector implements PatternDetector {

    private final VideoConfig config;

    AnimationPatternDetector(VideoConfig config) {
        this.config = config;
    }

    @Override
    public void apply(List<ScoredFrame> frames) {
        int window = config.patternAnimationWindow();
        for (int start = 0; start + config.patternAnimationMinFrames() <= frames.size(); start++) {
            int end = Math.min(start + window, frames.size());
            if (end - start < config.patternAnimationMinFrames()) {
                continue;
            }
            if (isAnimationRun(frames, start, end)) {
                for (int i = start + 1; i < end; i++) {
                    ScoredFrame f = frames.get(i);
                    if (f.patternType() == PatternType.NORMAL) {
                        f.setPatternType(PatternType.ANIMATION);
                    }
                    f.multiplyPattern(config.patternAnimationPenalty());
                }
            }
        }
    }

    private boolean isAnimationRun(List<ScoredFrame> frames, int start, int end) {
        ScoredFrame first = frames.get(start);
        double[] magnitudes = new double[end - start];
        magnitudes[0] = first.diff().changedCellFraction();
        for (int i = start + 1; i < end; i++) {
            ScoredFrame prev = frames.get(i - 1);
            ScoredFrame curr = frames.get(i);
            if (!GridCellPatternHelper.sameChangedCells(prev.diff(), curr.diff())) {
                return false;
            }
            magnitudes[i - start] = curr.diff().changedCellFraction();
        }
        double mean = 0.0;
        for (double m : magnitudes) {
            mean += m;
        }
        mean /= magnitudes.length;
        if (mean == 0) {
            return false;
        }
        double var = GridCellPatternHelper.variance(magnitudes);
        return (var / (mean * mean)) < config.patternAnimationVarianceThreshold();
    }
}
