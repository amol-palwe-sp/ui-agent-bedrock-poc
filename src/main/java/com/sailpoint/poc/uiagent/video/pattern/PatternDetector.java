package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.List;

/**
 * Applies pattern classification and score multipliers to a frame sequence (REQ-FS-3).
 */
public interface PatternDetector {

    void apply(List<ScoredFrame> frames);
}
