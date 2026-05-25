package com.sailpoint.poc.uiagent.video.grid;

import com.sailpoint.poc.uiagent.config.VideoConfig;

/**
 * Precomputed per-cell zone weights for grid scoring (REQ-FS-2).
 */
public final class ZoneWeightMap {

    private final int gridSize;
    private final double[][] weights;
    private final ScreenZone[][] zones;
    private final VideoConfig config;

    public ZoneWeightMap(VideoConfig config) {
        this.config = config;
        this.gridSize = config.gridSize();
        this.weights = new double[gridSize][gridSize];
        this.zones = new ScreenZone[gridSize][gridSize];
        for (int row = 0; row < gridSize; row++) {
            for (int col = 0; col < gridSize; col++) {
                ScreenZone zone = zoneForCell(row, col, gridSize);
                zones[row][col] = zone;
                weights[row][col] = weightForZone(zone, config);
            }
        }
    }

    public int gridSize() {
        return gridSize;
    }

    public double weight(int row, int col) {
        return weights[row][col];
    }

    public ScreenZone zone(int row, int col) {
        return zones[row][col];
    }

    public double totalWeight() {
        double sum = 0.0;
        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) {
                sum += weights[r][c];
            }
        }
        return sum;
    }

    /**
     * REQ-FS-2.1: zone by cell center; overlapping regions → highest weight wins.
     */
    private ScreenZone zoneForCell(int row, int col, int gridSize) {
        double rowFrac = (row + 0.5) / gridSize;
        double colFrac = (col + 0.5) / gridSize;

        ScreenZone bestZone = ScreenZone.MAIN_CONTENT;
        double bestW = config.zoneWeightMainContent();

        if (rowFrac < 0.05 && config.zoneWeightUrlBar() > bestW) {
            bestW = config.zoneWeightUrlBar();
            bestZone = ScreenZone.URL_BAR;
        }
        if (rowFrac > 0.90 && config.zoneWeightBottomBar() > bestW) {
            bestW = config.zoneWeightBottomBar();
            bestZone = ScreenZone.BOTTOM_BAR;
        }
        if (colFrac < 0.10 && config.zoneWeightLeftNav() > bestW) {
            bestW = config.zoneWeightLeftNav();
            bestZone = ScreenZone.LEFT_NAV;
        }
        if (colFrac > 0.85 && config.zoneWeightRightSidebar() > bestW) {
            bestW = config.zoneWeightRightSidebar();
            bestZone = ScreenZone.RIGHT_SIDEBAR;
        }
        boolean mainRow = row >= 1 && row <= gridSize - 2;
        boolean mainCol = colFrac >= 0.10 && colFrac <= 0.85;
        if (mainRow && mainCol && config.zoneWeightMainContent() >= bestW) {
            bestZone = ScreenZone.MAIN_CONTENT;
        }
        return bestZone;
    }

    private static double weightForZone(ScreenZone zone, VideoConfig config) {
        return switch (zone) {
            case URL_BAR -> config.zoneWeightUrlBar();
            case MAIN_CONTENT -> config.zoneWeightMainContent();
            case LEFT_NAV -> config.zoneWeightLeftNav();
            case RIGHT_SIDEBAR -> config.zoneWeightRightSidebar();
            case BOTTOM_BAR -> config.zoneWeightBottomBar();
        };
    }
}
