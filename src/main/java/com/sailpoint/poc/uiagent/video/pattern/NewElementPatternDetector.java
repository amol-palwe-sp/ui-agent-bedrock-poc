package com.sailpoint.poc.uiagent.video.pattern;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.grid.ZoneWeightMap;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.List;

/** REQ-FS-3.4 */
final class NewElementPatternDetector implements PatternDetector {

    private final VideoConfig config;
    private final ZoneWeightMap zoneMap;

    NewElementPatternDetector(VideoConfig config) {
        this.config = config;
        this.zoneMap = new ZoneWeightMap(config);
    }

    @Override
    public void apply(List<ScoredFrame> frames) {
        for (int i = 1; i < frames.size(); i++) {
            ScoredFrame prev = frames.get(i - 1);
            ScoredFrame curr = frames.get(i);
            if (prev.diff().changedCellFraction() > config.patternNavSpikeThreshold()) {
                continue;
            }
            if (!GridCellPatternHelper.hasClusterInMain(
                    curr.diff(), zoneMap, config.patternElementMinClusterSize())) {
                continue;
            }
            int persist = 1;
            for (int j = i + 1; j < frames.size() && j < i + config.patternElementPersistFrames(); j++) {
                if (GridCellPatternHelper.hasClusterInMain(
                        frames.get(j).diff(), zoneMap, config.patternElementMinClusterSize())) {
                    persist++;
                }
            }
            if (persist >= config.patternElementPersistFrames()) {
                curr.setPatternType(PatternType.NEW_ELEMENT);
                curr.multiplyPattern(config.patternElementBonus());
            }
        }
    }
}
