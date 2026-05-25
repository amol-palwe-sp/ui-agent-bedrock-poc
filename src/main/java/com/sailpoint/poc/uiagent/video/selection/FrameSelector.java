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
        pool.sort(Comparator.comparingDouble(ScoredFrame::finalScore).reversed());

        for (ScoredFrame f : pool) {
            if (selected.size() >= budget) {
                break;
            }
            if (selectedIndices.add(f.frameIndex())) {
                selected.add(f);
            }
        }

        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
        enforceCoverage(candidates, selected, selectedIndices, allowance);
        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
        deduplicate(selected);
        applyLastFrameRules(candidates, selected, selectedIndices);

        selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
        maxGapSeconds = computeMaxGap(selected);

        if (selected.size() > allowance) {
            trimToAllowance(selected, mandatory, allowance);
        }

        return new SelectionResult(selected, duplicatesRemoved, maxGapSeconds);
    }

    private void enforceCoverage(
            List<ScoredFrame> candidates,
            List<ScoredFrame> selected,
            Set<Integer> selectedIndices,
            int allowance) {
        if (selected.size() < 2) {
            return;
        }
        double maxGap = config.selectionMaxGapSeconds();
        boolean added = true;
        while (added && selected.size() < allowance) {
            added = false;
            selected.sort(Comparator.comparingDouble(ScoredFrame::frameTime));
            for (int i = 0; i < selected.size() - 1; i++) {
                double gap = selected.get(i + 1).frameTime() - selected.get(i).frameTime();
                if (gap <= maxGap) {
                    continue;
                }
                double t0 = selected.get(i).frameTime();
                double t1 = selected.get(i + 1).frameTime();
                ScoredFrame best = null;
                for (ScoredFrame c : candidates) {
                    if (c.frameTime() <= t0 || c.frameTime() >= t1) {
                        continue;
                    }
                    if (selectedIndices.contains(c.frameIndex())) {
                        continue;
                    }
                    if (best == null || c.finalScore() > best.finalScore()) {
                        best = c;
                    }
                }
                if (best != null) {
                    selectedIndices.add(best.frameIndex());
                    selected.add(best);
                    added = true;
                }
            }
            selected.sort(Comparator.comparingInt(ScoredFrame::frameIndex));
        }
    }

    private void deduplicate(List<ScoredFrame> selected) {
        if (selected.size() < 2) {
            return;
        }
        List<ScoredFrame> toRemove = new ArrayList<>();
        for (int i = 0; i < selected.size() - 1; i++) {
            ScoredFrame a = selected.get(i);
            ScoredFrame b = selected.get(i + 1);
            if (a.isMandatory() || b.isMandatory()) {
                continue;
            }
            double similarity = diffCalculator.similarity(
                    a.diff().cellChanges(), a.diff().cellChanged(),
                    b.diff().cellChanges(), b.diff().cellChanged());
            if (similarity >= config.selectionSimilarityThreshold()) {
                if (a.finalScore() >= b.finalScore()) {
                    toRemove.add(b);
                } else {
                    toRemove.add(a);
                }
                duplicatesRemoved++;
            }
        }
        selected.removeAll(toRemove);
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
