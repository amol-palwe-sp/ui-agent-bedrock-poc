package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.List;

/** REQ-FS-3.5 */
final class CursorPatternDetector implements PatternDetector {

    private static final double CURSOR_MAX_CELL_CHANGE = 0.10;

    CursorPatternDetector(VideoConfig config) {
    }

    @Override
    public void apply(List<ScoredFrame> frames) {
        for (int i = 0; i < frames.size(); i++) {
            ScoredFrame f = frames.get(i);
            if (f.changedCells() == 1 && f.maxCellChange() < CURSOR_MAX_CELL_CHANGE) {
                if (i > 0) {
                    ScoredFrame prev = frames.get(i - 1);
                    if (prev.changedCells() == 1
                            && !GridCellPatternHelper.sameChangedCells(prev.diff(), f.diff())) {
                        f.setPatternType(PatternType.CURSOR_ONLY);
                        f.multiplyPattern(0.0);
                        continue;
                    }
                }
                if (f.changedCells() == 1 && f.maxCellChange() < CURSOR_MAX_CELL_CHANGE) {
                    f.setPatternType(PatternType.CURSOR_ONLY);
                    f.multiplyPattern(0.0);
                }
            }
        }
    }
}
