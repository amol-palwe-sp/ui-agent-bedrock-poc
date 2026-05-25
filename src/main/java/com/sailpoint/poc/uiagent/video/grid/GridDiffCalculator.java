package com.sailpoint.poc.uiagent.video.grid;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.EnumMap;
import java.util.Map;

/**
 * Grid-based frame differential analysis (REQ-FS-1).
 */
public final class GridDiffCalculator {

    private final VideoConfig config;
    private final ZoneWeightMap zoneMap;

    public GridDiffCalculator(VideoConfig config) {
        this.config = config;
        this.zoneMap = new ZoneWeightMap(config);
    }

    public ZoneWeightMap zoneMap() {
        return zoneMap;
    }

    /**
     * First frame in a sequence — no previous reference.
     */
    public GridDiffResult firstFrame() {
        int n = config.gridSize();
        double[][] changes = new double[n][n];
        boolean[][] changed = new boolean[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                changes[r][c] = 1.0;
                changed[r][c] = true;
            }
        }
        Map<ScreenZone, Double> zones = new EnumMap<>(ScreenZone.class);
        for (ScreenZone z : ScreenZone.values()) {
            zones.put(z, 1.0);
        }
        double raw = 1.0;
        return new GridDiffResult(changes, changed, n * n, n * n, 1.0, raw, raw, zones);
    }

    /**
     * Compares consecutive grayscale frames.
     */
    public GridDiffResult compute(Mat prevGray, Mat currGray) {
        int gridSize = config.gridSize();
        int rows = prevGray.rows();
        int cols = prevGray.cols();
        int cellH = Math.max(1, rows / gridSize);
        int cellW = Math.max(1, cols / gridSize);

        Mat diff = new Mat();
        try {
            Core.absdiff(prevGray, currGray, diff);

            double[][] cellChanges = new double[gridSize][gridSize];
            boolean[][] cellChanged = new boolean[gridSize][gridSize];
            int changedCount = 0;
            double maxChange = 0.0;
            Map<ScreenZone, Double> zoneSums = new EnumMap<>(ScreenZone.class);
            for (ScreenZone z : ScreenZone.values()) {
                zoneSums.put(z, 0.0);
            }
            double weightedSum = 0.0;
            double weightTotal = zoneMap.totalWeight();

            for (int gr = 0; gr < gridSize; gr++) {
                int y = gr * cellH;
                int h = gr == gridSize - 1 ? rows - y : cellH;
                for (int gc = 0; gc < gridSize; gc++) {
                    int x = gc * cellW;
                    int w = gc == gridSize - 1 ? cols - x : cellW;
                    Rect roi = new Rect(x, y, w, h);
                    Mat cellDiff = new Mat(diff, roi);
                    double cellPixels = w * (double) h;
                    double changedPixels = Core.countNonZero(cellDiff);
                    double fraction = cellPixels == 0 ? 0.0 : changedPixels / cellPixels;
                    cellChanges[gr][gc] = fraction;
                    boolean isChanged = fraction >= config.gridCellThreshold();
                    cellChanged[gr][gc] = isChanged;
                    if (isChanged) {
                        changedCount++;
                    }
                    maxChange = Math.max(maxChange, fraction);

                    ScreenZone zone = zoneMap.zone(gr, gc);
                    double zw = zoneMap.weight(gr, gc);
                    if (isChanged) {
                        weightedSum += fraction * zw;
                        zoneSums.merge(zone, fraction, Double::sum);
                    }
                }
            }

            int totalCells = gridSize * gridSize;
            double breadth = (double) changedCount / totalCells;
            double presence = changedCount > 0 ? 1.0 : 0.0;
            double rawScore = config.scoreWeightBreadth() * breadth
                    + config.scoreWeightIntensity() * maxChange
                    + config.scoreWeightPresence() * presence;

            double weightedScore = weightTotal == 0 ? rawScore : weightedSum / weightTotal;

            return new GridDiffResult(
                    cellChanges, cellChanged, changedCount, totalCells,
                    maxChange, rawScore, weightedScore, zoneSums);
        } finally {
            diff.release();
        }
    }

    /**
     * Grid diff between two encoded JPEG frames (deduplication).
     */
    public double similarity(double[][] changesA, boolean[][] changedA, double[][] changesB, boolean[][] changedB) {
        int n = changesA.length;
        int same = 0;
        int total = n * n;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                boolean a = changedA[r][c];
                boolean b = changedB[r][c];
                if (a == b) {
                    same++;
                }
            }
        }
        return (double) same / total;
    }
}
