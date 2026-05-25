package com.sailpoint.poc.uiagent.video.scoring;

import com.sailpoint.poc.uiagent.video.grid.GridDiffResult;
import com.sailpoint.poc.uiagent.video.grid.ScreenZone;

import java.util.EnumMap;
import java.util.Map;

/**
 * Frame with scoring metadata through the extraction pipeline (REQ-FS-4.3).
 */
public final class ScoredFrame {

    private final int frameIndex;
    private final double frameTime;
    private final GridDiffResult diff;
    private double rawScore;
    private double patternMultiplier;
    private double finalScore;
    private PatternType patternType;
    private boolean mandatory;
    private byte[] jpegBytes;

    public ScoredFrame(int frameIndex, double frameTime, GridDiffResult diff) {
        this.frameIndex = frameIndex;
        this.frameTime = frameTime;
        this.diff = diff;
        this.rawScore = diff.weightedScore();
        this.patternMultiplier = 1.0;
        this.finalScore = rawScore;
        this.patternType = PatternType.NORMAL;
        this.mandatory = false;
    }

    public int frameIndex() {
        return frameIndex;
    }

    public double frameTime() {
        return frameTime;
    }

    public GridDiffResult diff() {
        return diff;
    }

    public double rawScore() {
        return rawScore;
    }

    public void setRawScore(double rawScore) {
        this.rawScore = rawScore;
    }

    public double finalScore() {
        return finalScore;
    }

    public void setFinalScore(double finalScore) {
        this.finalScore = finalScore;
    }

    public PatternType patternType() {
        return patternType;
    }

    public void setPatternType(PatternType patternType) {
        this.patternType = patternType;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public byte[] jpegBytes() {
        return jpegBytes;
    }

    public void setJpegBytes(byte[] jpegBytes) {
        this.jpegBytes = jpegBytes;
    }

    public int changedCells() {
        return diff.changedCells();
    }

    public double maxCellChange() {
        return diff.maxCellChange();
    }

    public Map<ScreenZone, Double> zoneBreakdown() {
        return new EnumMap<>(diff.zoneBreakdown());
    }

    public double patternMultiplier() {
        return patternMultiplier;
    }

    /** REQ-FS-4.1: cumulative pattern multipliers × base score. */
    public void multiplyPattern(double multiplier) {
        patternMultiplier *= multiplier;
        finalScore = rawScore * patternMultiplier;
    }

    public void refreshFinalScore() {
        finalScore = rawScore * patternMultiplier;
    }
}
