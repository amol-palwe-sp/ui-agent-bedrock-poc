package com.sailpoint.poc.uiagent.video.selection;

import com.sailpoint.poc.uiagent.config.VideoConfig;
import com.sailpoint.poc.uiagent.video.grid.GridDiffCalculator;
import com.sailpoint.poc.uiagent.video.scoring.PatternType;
import com.sailpoint.poc.uiagent.video.scoring.ScoredFrame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Score-based final frame selection (REQ-FS-5).
 */
public final class FrameSelector {

    /**
     * Below this final score a gap candidate is treated as "blank" for coverage purposes, so the
     * midpoint frame is chosen for even spacing instead of an arbitrary tie-broken top scorer.
     */
    private static final double COVERAGE_MEANINGFUL_SCORE = 0.05;

    private final VideoConfig config;
    private final GridDiffCalculator diffCalculator;

    private int duplicatesRemoved;
    private double maxGapSeconds;

    public FrameSelector(VideoConfig config, GridDiffCalculator diffCalculator) {
        this.config = config;
        this.diffCalculator = diffCalculator;
    }

    public SelectionResult select(List<ScoredFrame> candidates, int maxFrames) {
        duplicatesRemoved = 0;
        maxGapSeconds = 0.0;

        if (candidates.isEmpty()) {
            return new SelectionResult(List.of(), 0, 0.0);
        }

        List<ScoredFrame> mandatory = new ArrayList<>();
        List<ScoredFrame> pool = new ArrayList<>();

        for (ScoredFrame f : candidates) {
            if (f.isMandatory()) {
                mandatory.add(f);
            } else {
                pool.add(f);
            }
        }

        ScoredFrame first = candidates.get(0);
        if (!first.isMandatory()) {
            first.setMandatory(true);
            first.setPatternType(PatternType.MANDATORY);
        }
        if (!mandatory.contains(first)) {
            mandatory.add(first);
        }

        Set<Integer> selectedIndices = new HashSet<>();
        List<ScoredFrame> selected = new ArrayList<>();

        for (ScoredFrame m : mandatory) {
            if (selectedIndices.add(m.frameIndex())) {
                selected.add(m);
            }
        }

        int budget = maxFrames;
        int allowance = (int) Math.ceil(maxFrames * 1.1);
        double minGap = config.selectionMinGapSeconds();
        pool.sort(Comparator.comparingDouble(ScoredFrame::finalScore).reversed());

        // Greedy top-score fill with temporal non-max suppression: a high-score frame blocks
        // lower-score frames within minGap seconds of it, so one busy moment doesn't consume the
        // whole frame budget. enforceCoverage() below is free to add frames back into any gap
        // that ends up too large as a result.
        for (ScoredFrame f : pool) {
            if (selected.size() >= budget) {
                break;
            }
            if (selectedIndices.contains(f.frameIndex())) {
                continue;
            }
            if (minGap > 0 && isTooCloseToSelected(selected, f, minGap)) {
                continue;
            }
            selectedIndices.add(f.frameIndex());
            selected.add(f);
        }

        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
        // Dedup the score-picked frames FIRST, then rebuild the index set so coverage can refill
        // any gap that dedup opened up. Coverage runs AFTER dedup so its evenly-spaced filler
        // frames — which are often near-identical on idle/slow-typing screens — are never removed
        // as "duplicates" (the very coarseness that hides typing from the diff also makes those
        // frames look identical to the fingerprint).
        deduplicate(selected);
        rebuildIndices(selected, selectedIndices);
        enforceCoverage(candidates, selected, selectedIndices, allowance);
        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
        applyLastFrameRules(candidates, selected, selectedIndices);

        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
        maxGapSeconds = computeMaxGap(selected);

        if (selected.size() > allowance) {
            trimToAllowance(selected, mandatory, allowance);
            if (selected.size() > allowance) {
                System.err.printf(
                        "WARNING: %d mandatory frames exceed the frame allowance (%d); keeping all "
                        + "of them since mandatory frames carry information (URL/navigation "
                        + "changes) required for correctness. Consider raising maxFrames or "
                        + "tightening video.url.bar.mandatory.threshold.%n",
                        selected.size(), allowance);
            }
        }

        return new SelectionResult(selected, duplicatesRemoved, maxGapSeconds);
    }

    private boolean isTooCloseToSelected(List<ScoredFrame> selected, ScoredFrame candidate, double minGap) {
        for (ScoredFrame s : selected) {
            if (Math.abs(s.frameTime() - candidate.frameTime()) < minGap) {
                return true;
            }
        }
        return false;
    }

    /** Rebuilds {@code selectedIndices} from the surviving {@code selected} frames. */
    private void rebuildIndices(List<ScoredFrame> selected, Set<Integer> selectedIndices) {
        selectedIndices.clear();
        for (ScoredFrame f : selected) {
            selectedIndices.add(f.frameIndex());
        }
    }

    /**
     * Guarantees uniform temporal coverage. Any gap larger than the coverage threshold gets a
     * frame inserted, and — crucially — the segment AFTER the last selected frame is bounded by
     * the end of the video, so the trailing region (where slow, low-visual-delta activity such as
     * typing into a field commonly lives) is covered too. The threshold is the tighter of the
     * score-based max-gap net and the uniform coverage interval, so coverage is dense enough to
     * catch typing that the grid diff scores as ~0.
     */
    private void enforceCoverage(
            List<ScoredFrame> candidates,
            List<ScoredFrame> selected,
            Set<Integer> selectedIndices,
            int allowance) {
        if (candidates.isEmpty() || selected.isEmpty()) {
            return;
        }
        double maxGap = config.selectionMaxGapSeconds();
        double interval = config.selectionCoverageIntervalSeconds();
        double threshold;
        if (interval > 0 && maxGap > 0) {
            threshold = Math.min(interval, maxGap);
        } else if (interval > 0) {
            threshold = interval;
        } else {
            threshold = maxGap;
        }
        if (threshold <= 0) {
            return;
        }

        double videoEnd = candidates.get(candidates.size() - 1).frameTime();

        boolean added = true;
        while (added && selected.size() < allowance) {
            added = false;
            selected.sort(Comparator.comparingDouble(ScoredFrame::frameTime));
            int n = selected.size();
            for (int i = 0; i < n; i++) {
                double t0 = selected.get(i).frameTime();
                double t1 = (i + 1 < n) ? selected.get(i + 1).frameTime() : videoEnd;
                if (t1 - t0 <= threshold) {
                    continue;
                }
                ScoredFrame pick = pickCoverageFrame(candidates, selectedIndices, t0, t1);
                if (pick != null) {
                    selectedIndices.add(pick.frameIndex());
                    selected.add(pick);
                    added = true;
                    break; // list changed; restart the scan
                }
            }
        }
        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
    }

    /**
     * Picks the frame to insert into a temporal gap {@code (t0, t1)}. Prefers the highest-scoring
     * candidate when the gap actually contains visible activity; when every candidate in the gap
     * is effectively blank (score below {@link #COVERAGE_MEANINGFUL_SCORE}), falls back to the
     * candidate nearest the gap midpoint so repeated insertions produce even spacing rather than
     * clustering against one edge.
     */
    private ScoredFrame pickCoverageFrame(
            List<ScoredFrame> candidates, Set<Integer> selectedIndices, double t0, double t1) {
        ScoredFrame bestScore = null;
        ScoredFrame nearestMid = null;
        double mid = (t0 + t1) / 2.0;
        double bestMidDist = Double.MAX_VALUE;
        for (ScoredFrame c : candidates) {
            if (c.frameTime() <= t0 || c.frameTime() >= t1) {
                continue;
            }
            if (selectedIndices.contains(c.frameIndex())) {
                continue;
            }
            if (bestScore == null || c.finalScore() > bestScore.finalScore()) {
                bestScore = c;
            }
            double d = Math.abs(c.frameTime() - mid);
            if (d < bestMidDist) {
                bestMidDist = d;
                nearestMid = c;
            }
        }
        if (bestScore == null) {
            return null;
        }
        return bestScore.finalScore() >= COVERAGE_MEANINGFUL_SCORE ? bestScore : nearestMid;
    }

    /**
     * Removes near-duplicate frames by comparing every pair directly via their fingerprints
     * (not just neighbours in the current sort order, and not via each frame's diff-vs-predecessor,
     * which does not describe how two frames compare to EACH OTHER). Processes frames highest-score
     * first so each similarity cluster keeps its best representative; mandatory frames are never
     * removed but do count as cluster representatives so lower-score near-duplicates of a mandatory
     * frame are still dropped.
     */
    private void deduplicate(List<ScoredFrame> selected) {
        if (selected.size() < 2) {
            return;
        }
        List<ScoredFrame> byScoreDesc = new ArrayList<>(selected);
        byScoreDesc.sort(Comparator.comparingDouble(ScoredFrame::finalScore).reversed());

        List<ScoredFrame> kept = new ArrayList<>();
        List<ScoredFrame> removed = new ArrayList<>();
        for (ScoredFrame candidate : byScoreDesc) {
            if (candidate.isMandatory() || candidate.fingerprint() == null) {
                kept.add(candidate);
                continue;
            }
            boolean isDuplicate = false;
            for (ScoredFrame representative : kept) {
                if (representative.fingerprint() == null) {
                    continue;
                }
                double similarity = diffCalculator.similarity(
                        candidate.fingerprint(), representative.fingerprint());
                if (similarity >= config.selectionSimilarityThreshold()) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) {
                removed.add(candidate);
            } else {
                kept.add(candidate);
            }
        }
        duplicatesRemoved += removed.size();
        selected.removeAll(removed);
    }

    private void applyLastFrameRules(
            List<ScoredFrame> candidates,
            List<ScoredFrame> selected,
            Set<Integer> selectedIndices) {
        if (candidates.isEmpty()) {
            return;
        }
        ScoredFrame last = candidates.get(candidates.size() - 1);
        if (selectedIndices.contains(last.frameIndex())) {
            return;
        }
        double minScore = 0.05;
        boolean nearMandatory = selected.stream()
                .anyMatch(s -> Math.abs(s.frameTime() - last.frameTime()) <= 2.0);
        boolean onlyInTail = selected.stream()
                .noneMatch(s -> s.frameTime() > last.frameTime() - 10.0);
        if (last.finalScore() >= minScore || nearMandatory || onlyInTail) {
            selected.add(last);
            selectedIndices.add(last.frameIndex());
        }
    }

    private double computeMaxGap(List<ScoredFrame> selected) {
        if (selected.size() < 2) {
            return 0.0;
        }
        selected.sort(Comparator.comparingDouble(ScoredFrame::frameTime));
        double max = 0.0;
        for (int i = 0; i < selected.size() - 1; i++) {
            max = Math.max(max, selected.get(i + 1).frameTime() - selected.get(i).frameTime());
        }
        return max;
    }

    private void trimToAllowance(List<ScoredFrame> selected, List<ScoredFrame> mandatory, int allowance) {
        Set<Integer> mandatoryIdx = new HashSet<>();
        for (ScoredFrame m : mandatory) {
            mandatoryIdx.add(m.frameIndex());
        }
        selected.sort(Comparator
                .comparing((ScoredFrame f) -> mandatoryIdx.contains(f.frameIndex()) ? 0 : 1)
                .thenComparing(Comparator.comparingDouble(ScoredFrame::finalScore).reversed()));
        while (selected.size() > allowance) {
            ScoredFrame removed = selected.remove(selected.size() - 1);
            if (mandatoryIdx.contains(removed.frameIndex())) {
                selected.add(removed);
                break;
            }
        }
        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
    }

    public record SelectionResult(List<ScoredFrame> frames, int duplicatesRemoved, double maxGapSeconds) {}
}
