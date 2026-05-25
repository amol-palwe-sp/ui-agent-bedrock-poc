package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.video.grid.GridDiffResult;
import com.sailpoint.poc.uiagent.video.grid.ScreenZone;
import com.sailpoint.poc.uiagent.video.grid.ZoneWeightMap;

/**
 * Shared helpers for comparing grid cell change patterns between frames.
 */
final class GridCellPatternHelper {

    private GridCellPatternHelper() {}

    static boolean sameChangedCells(GridDiffResult a, GridDiffResult b) {
        boolean[][] ca = a.cellChanged();
        boolean[][] cb = b.cellChanged();
        int n = ca.length;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (ca[r][c] != cb[r][c]) {
                    return false;
                }
            }
        }
        return true;
    }

    static int overlapChangedCells(GridDiffResult a, GridDiffResult b) {
        boolean[][] ca = a.cellChanged();
        boolean[][] cb = b.cellChanged();
        int n = ca.length;
        int overlap = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (ca[r][c] && cb[r][c]) {
                    overlap++;
                }
            }
        }
        return overlap;
    }

    static int mainContentChangedCount(GridDiffResult diff, ZoneWeightMap zoneMap) {
        boolean[][] changed = diff.cellChanged();
        int n = changed.length;
        int count = 0;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (changed[r][c] && zoneMap.zone(r, c) == ScreenZone.MAIN_CONTENT) {
                    count++;
                }
            }
        }
        return count;
    }

    static boolean hasClusterInMain(GridDiffResult diff, ZoneWeightMap zoneMap, int minSize) {
        boolean[][] changed = diff.cellChanged();
        int n = changed.length;
        boolean[][] visited = new boolean[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!changed[r][c] || visited[r][c] || zoneMap.zone(r, c) != ScreenZone.MAIN_CONTENT) {
                    continue;
                }
                int size = floodFill(changed, visited, r, c, n, zoneMap);
                if (size >= minSize) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int floodFill(
            boolean[][] changed, boolean[][] visited, int r, int c, int n, ZoneWeightMap zoneMap) {
        if (r < 0 || c < 0 || r >= n || c >= n || visited[r][c] || !changed[r][c]
                || zoneMap.zone(r, c) != ScreenZone.MAIN_CONTENT) {
            return 0;
        }
        visited[r][c] = true;
        int size = 1;
        size += floodFill(changed, visited, r - 1, c, n, zoneMap);
        size += floodFill(changed, visited, r + 1, c, n, zoneMap);
        size += floodFill(changed, visited, r, c - 1, n, zoneMap);
        size += floodFill(changed, visited, r, c + 1, n, zoneMap);
        return size;
    }

    static double variance(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double mean = 0.0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.length;
        double var = 0.0;
        for (double v : values) {
            double d = v - mean;
            var += d * d;
        }
        return var / values.length;
    }
}
