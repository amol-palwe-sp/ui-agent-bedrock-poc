# Frame Selection Optimizations

This document summarizes the changes made to the video keyframe-selection pipeline
(`VideoFrameExtractor` → `FrameScorer` → pattern detectors → `FrameSelector`) used to pick which
browser-recording frames get sent to Claude for aggregation/navigation analysis.

## Why this mattered

The pipeline sends selected frames as images to Claude (Bedrock) for URL/navigation/pagination
extraction. Every extra or near-duplicate frame directly increases:

- **LLM cost & latency** — more image tokens per Bedrock call.
- **Noise** — redundant or low-information frames (mid-animation, mid-typing) can distract the
  model from the frames that actually matter (settled pages, filled forms, final list views).

The goal of this pass was to select **fewer, more informative frames** without losing any
information Claude needs (target URL, navigation steps, pagination pattern).

## What changed

### 1. Fixed a bug that limited "settled page" detection to once per entire video

`NavigationPatternDetector` marks the frame right after a big screen change ("navigation spike")
as `PAGE_SETTLED` and forces it into the selection — this is normally the most valuable frame
after a page load (login page loaded, list page loaded, etc.).

The old code `break`-ed out of its loop the first time it found a settled frame **anywhere in the
whole video**, silently skipping this detection for every subsequent navigation. It now marks a
settle point for **every** navigation spike in the recording.

**Benefit:** every page-load / navigation event in a multi-step flow (login → 2FA → list page,
etc.) now reliably surfaces its "page fully loaded" frame, instead of only the first one.

### 2. Replaced the duplicate-detection logic with a real frame-vs-frame comparison

Each frame previously only stored a diff **against its immediate predecessor** (how much did the
screen change since the last frame?). Deduplication compared these predecessor-diffs between two
candidate frames — which measures "were both frames busy in the same area", not "do these two
frames actually look the same." Real duplicates could be missed, and unrelated busy frames could be
wrongly treated as duplicates.

Added a lightweight per-frame **fingerprint** (average brightness per grid cell, independent of any
other frame) computed once during the initial scan. Deduplication now:

- Compares fingerprints of any two candidate frames directly, so similarity actually reflects
  visual similarity.
- Scans the **entire** selected pool (not just adjacent frames in time order), so duplicates aren't
  missed just because a different frame ended up sorted between them.
- Processes frames highest-score-first, keeping the best representative of each "looks the same"
  cluster and dropping the rest.

**Benefit:** more accurate, more thorough duplicate removal → fewer redundant frames sent to
Claude, without accidentally discarding genuinely distinct frames.

### 3. Collapsed URL-bar-change bursts into a single mandatory frame

Any frame where the browser's URL bar zone changed significantly was marked **mandatory** (always
kept, never trimmed) — used to make sure the address bar is visible when the target URL changes.
During address-bar typing/animation this could mark a whole run of consecutive frames mandatory,
with **no cap**, silently inflating the frame count past the configured budget.

Now, consecutive URL-bar-significant frames are treated as one **run**, and only the **last** frame
of the run (the settled URL) is marked mandatory.

**Benefit:** captures the same information (the final URL) using far fewer mandatory frames,
directly reducing frame count/cost. Also added a visible warning log if mandatory frames alone ever
exceed the frame budget, instead of silently overshooting it.

### 4. Added temporal spacing ("non-max suppression") to greedy frame selection

Frame selection previously picked the top-N highest-scoring frames globally. A single burst of
activity (e.g., a loading spinner or scroll) could dominate the score ranking and consume most of
the frame budget with frames clustered in a few seconds, starving other parts of the recording.

Selection now enforces a minimum time gap (default 0.5s, configurable via
`video.selection.min.gap.seconds`) between non-mandatory frames during the initial greedy pick. The
existing gap-filling step still runs afterward to backfill any resulting large gaps in time
coverage.

**Benefit:** frame budget is spread more evenly across the whole recording instead of being
consumed by one busy moment — better coverage of distinct UI states for the same frame count.

### 5. Uniform temporal coverage floor (typing / low-visual-delta activity)

The grid diff scores a frame by how many grid cells changed beyond a per-cell threshold. Typing a
few characters into a field changes only a tiny sub-cell region, so with a coarse grid it registers
as **zero changed cells → score 0.00**, is classified `NORMAL` (never `TYPING`), and gets dropped.
On some pages an entire form-fill + submit sequence scored `0.00` across hundreds of consecutive
frames, so the model received only the initial page-load frames and never saw the data entry.

Two gaps caused this:

- **Score blindness:** the whole data-entry region is invisible to score-based selection.
- **Trailing coverage blind spot:** the old gap-filler only backfilled gaps *between two already
  selected frames*. When nothing after the last "busy" frame scored above zero, there was no
  right-hand anchor, so the trailing region (where slow typing lives) was never covered — and the
  8s max-gap net was silently violated.

Now selection enforces a **uniform coverage floor**: after dedup, any gap larger than the coverage
interval (default 2.5s, configurable via `video.selection.coverage.interval.seconds`) gets a frame
inserted, and the segment **after the last selected frame is bounded by the end of the video**, so
the trailing region is covered too. In blank/idle stretches the frame nearest the gap midpoint is
chosen so repeated insertions space evenly instead of clustering. Coverage runs **after** dedup so
its evenly-spaced filler frames are not removed as "duplicates" (the same coarseness that hides
typing from the diff also makes those frames look identical to the fingerprint).

**Benefit:** slow, low-visual-delta activity such as typing into a field is never dropped; the model
sees every field being filled and the final submit, regardless of how little each keystroke changes
the pixels.

## Net effect

| Area | Before | After |
|---|---|---|
| Settled-page detection | Only the first navigation in the video | Every navigation event |
| Duplicate detection | Adjacent frames only, wrong comparison (predecessor-diff vs predecessor-diff) | Whole pool, direct frame-vs-frame fingerprint comparison |
| URL-bar mandatory frames | One per frame during the whole change (uncapped) | One per change event (the settled frame) |
| Frame budget spread | Greedy top-score only (can cluster) | Top-score + minimum time spacing |
| Typing / trailing coverage | Dropped when score ≈ 0; trailing region uncovered | Uniform coverage floor over the whole timeline, incl. the tail |

Together these changes should reduce the typical number of frames sent to Claude (lower cost/
latency) while improving the odds that the frames actually kept are the informative ones — settled
page states, completed form fills, and final list/table views — rather than mid-animation or
duplicate frames.

## Configuration

Two tunables were added:

```properties
# Minimum seconds between two non-mandatory frames during greedy selection (temporal non-max
# suppression). Prevents one busy moment from consuming the whole frame budget. 0 disables.
video.selection.min.gap.seconds=0.5

# Uniform temporal coverage floor. Guarantees at least one selected frame per this many seconds
# across the whole timeline (including the trailing region after the last high-scoring frame),
# regardless of the visual-change score — so slow / low-visual-delta activity such as typing into
# a field is never dropped. 0 disables (falls back to the score-based max-gap net only).
video.selection.coverage.interval.seconds=2.5
```

All other existing tunables (`video.selection.similarity.threshold`,
`video.url.bar.mandatory.threshold`, `video.max.frames`, etc.) still apply — their *meaning*
is unchanged, but `video.selection.similarity.threshold` now compares real frame fingerprints
instead of predecessor-diffs, so it may be worth re-checking with `video.debug.frames.dir` set on a
representative recording to confirm the dedup aggressiveness still feels right.

## Files touched

- `video/pattern/NavigationPatternDetector.java` — settle-detection bug fix
- `video/grid/GridDiffCalculator.java` — added `fingerprint()`, reworked `similarity()`
- `video/scoring/ScoredFrame.java`, `video/scoring/FrameScorer.java` — carry the fingerprint
- `video/VideoFrameExtractor.java` — compute fingerprints; collapse mandatory URL-bar runs
- `video/selection/FrameSelector.java` — pool-wide dedup, min-gap non-max suppression, mandatory
  overflow warning
- `config/VideoConfig.java`, `PocConfig.java`, `application.properties` — new
  `selectionMinGapSeconds` tunable

## Suggested follow-ups (not yet done)

- Lower the default `video.max.frames` (currently 80) once the above changes have been validated
  on real recordings — tighter selection should naturally need fewer frames for the same quality.
- Consider a single-pass rewrite of `FrameSelector.select()` — it currently makes several
  successive passes (fill → coverage → dedup → last-frame rule → trim) that each nudge the frame
  count, making the final size harder to reason about.
