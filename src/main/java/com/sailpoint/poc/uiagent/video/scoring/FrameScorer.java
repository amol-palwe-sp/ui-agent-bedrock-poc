package com.sailpoint.poc.uiagent.video.scoring;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.grid.GridDiffResult;

/**
 * Builds {@link ScoredFrame} from grid diff results (REQ-FS-4).
 */
public final class FrameScorer {

    private final VideoConfig config;

    public FrameScorer(VideoConfig config) {
        this.config = config;
    }

    public ScoredFrame score(int frameIndex, double frameTime, GridDiffResult diff) {
        ScoredFrame frame = new ScoredFrame(frameIndex, frameTime, diff);
        frame.setRawScore(diff.weightedScore());
        frame.setFinalScore(diff.weightedScore());
        return frame;
    }
}
