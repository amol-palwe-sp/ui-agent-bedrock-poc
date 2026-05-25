package com.sailpoint.poc.uiagent.video.grid;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-frame grid differential analysis output (REQ-FS-1 / REQ-FS-4.3).
 */
public final class GridDiffResult {

    private final double[][] cellChanges;
    private final boolean[][] cellChanged;
    private final int changedCells;
    private final int totalCells;
    private final double maxCellChange;
    private final double rawScore;
    private final double weightedScore;
    private final Map<ScreenZone, Double> zoneBreakdown;

    public GridDiffResult(
            double[][] cellChanges,
            boolean[][] cellChanged,
            int changedCells,
            int totalCells,
            double maxCellChange,
            double rawScore,
            double weightedScore,
            Map<ScreenZone, Double> zoneBreakdown) {
        this.cellChanges = cellChanges;
        this.cellChanged = cellChanged;
        this.changedCells = changedCells;
        this.totalCells = totalCells;
        this.maxCellChange = maxCellChange;
        this.rawScore = rawScore;
        this.weightedScore = weightedScore;
        this.zoneBreakdown = zoneBreakdown;
    }

    public double[][] cellChanges() {
        return cellChanges;
    }

    public boolean[][] cellChanged() {
        return cellChanged;
    }

    public int changedCells() {
        return changedCells;
    }

    public int totalCells() {
        return totalCells;
    }

    public double maxCellChange() {
        return maxCellChange;
    }

    public double rawScore() {
        return rawScore;
    }

    public double weightedScore() {
        return weightedScore;
    }

    public Map<ScreenZone, Double> zoneBreakdown() {
        return zoneBreakdown;
    }

    /** Fraction of grid cells marked changed (REQ-FS-3 thresholds). */
    public double changedCellFraction() {
        return totalCells == 0 ? 0.0 : (double) changedCells / totalCells;
    }

    /** Similarity-oriented score: higher means more different (used for dedup). */
    public double diffScore() {
        return weightedScore;
    }
}
