package com.sailpoint.poc.uiagent.config;

/**
 * Typed snapshot of video-frame-extraction configuration (REQ-FS-6).
 *
 * <p>Obtain via {@link com.sailpoint.poc.uiagent.PocConfig#video()}.
 */
/**
 * @param changeThreshold legacy global threshold (retained for compatibility)
 * @param minGapSeconds legacy phase-1 min gap (unused by grid pipeline)
 * @param maxForcedGapSeconds legacy force-keep gap (replaced by coverage enforcement)
 */
public record VideoConfig(
        int maxFrames,
        double changeThreshold,
        double minGapSeconds,
        double maxForcedGapSeconds,
        int frameMaxWidth,
        int jpegQuality,
        String debugFramesDir,
        // REQ-FS-1: grid
        int gridSize,
        double gridCellThreshold,
        double scoreWeightBreadth,
        double scoreWeightIntensity,
        double scoreWeightPresence,
        // REQ-FS-2: zones
        double zoneWeightUrlBar,
        double zoneWeightMainContent,
        double zoneWeightLeftNav,
        double zoneWeightRightSidebar,
        double zoneWeightBottomBar,
        // REQ-FS-3.1: navigation
        double patternNavSpikeThreshold,
        double patternNavStableBonus,
        double patternNavSpikePenalty,
        // REQ-FS-3.2: typing
        int patternTypingMaxCells,
        double patternTypingMaxCellChange,
        int patternTypingMinFrames,
        double patternTypingEndBonus,
        double patternTypingMidPenalty,
        // REQ-FS-3.3: animation
        int patternAnimationWindow,
        double patternAnimationVarianceThreshold,
        int patternAnimationMinFrames,
        double patternAnimationPenalty,
        // REQ-FS-3.4: new element
        int patternElementMinClusterSize,
        int patternElementPersistFrames,
        double patternElementBonus,
        // REQ-FS-3.5: scroll
        double patternScrollThreshold,
        double patternScrollEndBonus,
        double patternScrollMidPenalty,
        // REQ-FS-5: selection
        double selectionMaxGapSeconds,
        double selectionSimilarityThreshold,
        double urlBarMandatoryThreshold,
        double selectionMinGapSeconds) {

    public static VideoConfig defaults() {
        return new VideoConfig(
                80, 0.02, 0.5, 3.0, 1280, 75, "",
                12, 0.08, 0.4, 0.4, 0.2,
                3.0, 2.0, 0.5, 0.5, 0.5,
                0.5, 2.5, 0.3,
                3, 0.05, 3, 2.0, 0.2,
                5, 0.20, 4, 0.1,
                4, 2, 2.0,
                0.3, 1.5, 0.3,
                8.0, 0.92, 0.30, 0.5);
    }

    public VideoConfig withDebugFramesDir(String dir) {
        return new VideoConfig(
                maxFrames, changeThreshold, minGapSeconds, maxForcedGapSeconds,
                frameMaxWidth, jpegQuality, dir == null ? "" : dir,
                gridSize, gridCellThreshold, scoreWeightBreadth, scoreWeightIntensity, scoreWeightPresence,
                zoneWeightUrlBar, zoneWeightMainContent, zoneWeightLeftNav, zoneWeightRightSidebar, zoneWeightBottomBar,
                patternNavSpikeThreshold, patternNavStableBonus, patternNavSpikePenalty,
                patternTypingMaxCells, patternTypingMaxCellChange, patternTypingMinFrames,
                patternTypingEndBonus, patternTypingMidPenalty,
                patternAnimationWindow, patternAnimationVarianceThreshold, patternAnimationMinFrames, patternAnimationPenalty,
                patternElementMinClusterSize, patternElementPersistFrames, patternElementBonus,
                patternScrollThreshold, patternScrollEndBonus, patternScrollMidPenalty,
                selectionMaxGapSeconds, selectionSimilarityThreshold, urlBarMandatoryThreshold,
                selectionMinGapSeconds);
    }

    /** Override max frames (UI / CLI). */
    public VideoConfig withMaxFrames(int frames) {
        return new VideoConfig(
                frames, changeThreshold, minGapSeconds, maxForcedGapSeconds,
                frameMaxWidth, jpegQuality, debugFramesDir,
                gridSize, gridCellThreshold, scoreWeightBreadth, scoreWeightIntensity, scoreWeightPresence,
                zoneWeightUrlBar, zoneWeightMainContent, zoneWeightLeftNav, zoneWeightRightSidebar, zoneWeightBottomBar,
                patternNavSpikeThreshold, patternNavStableBonus, patternNavSpikePenalty,
                patternTypingMaxCells, patternTypingMaxCellChange, patternTypingMinFrames,
                patternTypingEndBonus, patternTypingMidPenalty,
                patternAnimationWindow, patternAnimationVarianceThreshold, patternAnimationMinFrames, patternAnimationPenalty,
                patternElementMinClusterSize, patternElementPersistFrames, patternElementBonus,
                patternScrollThreshold, patternScrollEndBonus, patternScrollMidPenalty,
                selectionMaxGapSeconds, selectionSimilarityThreshold, urlBarMandatoryThreshold,
                selectionMinGapSeconds);
    }

    /** Legacy constructor mapping for {@link com.sailpoint.poc.uiagent.video.VideoFrameExtractor}. */
    public static VideoConfig legacy(
            int maxFrames,
            double changeThreshold,
            double minGapSeconds,
            double maxForcedGapSeconds,
            int frameMaxWidth,
            int jpegQuality,
            String debugFramesDir) {
        VideoConfig d = defaults();
        return new VideoConfig(
                maxFrames, changeThreshold, minGapSeconds, maxForcedGapSeconds,
                frameMaxWidth, jpegQuality, debugFramesDir == null ? "" : debugFramesDir,
                d.gridSize(), d.gridCellThreshold(), d.scoreWeightBreadth(), d.scoreWeightIntensity(), d.scoreWeightPresence(),
                d.zoneWeightUrlBar(), d.zoneWeightMainContent(), d.zoneWeightLeftNav(), d.zoneWeightRightSidebar(), d.zoneWeightBottomBar(),
                d.patternNavSpikeThreshold(), d.patternNavStableBonus(), d.patternNavSpikePenalty(),
                d.patternTypingMaxCells(), d.patternTypingMaxCellChange(), d.patternTypingMinFrames(),
                d.patternTypingEndBonus(), d.patternTypingMidPenalty(),
                d.patternAnimationWindow(), d.patternAnimationVarianceThreshold(), d.patternAnimationMinFrames(), d.patternAnimationPenalty(),
                d.patternElementMinClusterSize(), d.patternElementPersistFrames(), d.patternElementBonus(),
                d.patternScrollThreshold(), d.patternScrollEndBonus(), d.patternScrollMidPenalty(),
                d.selectionMaxGapSeconds(), d.selectionSimilarityThreshold(), d.urlBarMandatoryThreshold(),
                d.selectionMinGapSeconds());
    }
}
