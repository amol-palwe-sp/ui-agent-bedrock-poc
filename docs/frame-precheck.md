# Frame Pre-Checks Before Sending to the LLM

This document inventories every validation, filter, gate, and transformation applied
to image frames in `ui-agent-bedrock-poc` **before** they reach Bedrock or the LLM proxy.

There are three independent paths that produce images for the LLM:

| Path | Source | Amount of pre-checking |
|------|--------|------------------------|
| A. Video | Uploaded/loaded MP4 | Heavy — scoring, dedup, coverage, resize, triage |
| B. Agent loop | Live Playwright screenshots | Light — action gating only |
| C. Relevance triage | Subsample of Path A output | A gate, not a filter |

All three converge on a shared encoding layer (`AnthropicMessages.buildBody`).

---

## Path A — Video Frames (MP4 → JPEG)

### A1. Upload / file validation

`ui/GenerateHandler.java`, `ui/AggregationGenerateHandler.java`

| Check | Rule | On failure |
|-------|------|------------|
| Content-Type | must be `multipart/form-data` | HTTP 400 |
| Field name | `video` | HTTP 400 |
| Extension | `.mp4` only | HTTP 400 |
| Size | ≤ 500 MB (`MAX_UPLOAD_BYTES`) | HTTP 400 |
| Non-empty | bytes present | HTTP 400 |

Temp file is always deleted in a `finally` block.

`video/VideoFrameExtractor.java` (L72–90) re-validates:

- file exists → `IOException`
- path ends in `.mp4` → `IOException`
- `VideoCapture.isOpened()` → `IOException`
- FPS fallback: if reported FPS ≤ 0, assume **30.0**

### A2. Scan and score — every frame is read

`scanAndScore()` (L140–174). There is **no read-time frame-rate stride**; the full
video is decoded and every frame is evaluated. Per frame:

1. BGR → grayscale (`Imgproc.cvtColor`)
2. Grid diff vs. previous frame (`GridDiffCalculator.compute`); frame 0 is treated
   as "all changed"
3. Fingerprint: 12×12 grid of mean cell intensities (0..1), used later for dedup
4. Score: `FrameScorer.score()` → `rawScore` / `finalScore` from the weighted diff

A grid cell counts as "changed" when its non-zero fraction of `Core.absdiff`
reaches `video.grid.cell.threshold` (default **0.08**). Zone weights bias the
score toward the URL bar and main content.

### A3. Mandatory-frame marking

`markMandatory()` (L183–225):

- First frame is always mandatory.
- URL-bar change runs: consecutive frames whose URL-bar zone exceeds
  `video.url.bar.mandatory.threshold` (default **0.30**). Only the **last frame of
  each run** is kept mandatory (the settled navigation state).

### A4. Pattern detectors (score adjustment / suppression)

`PatternAnalysisPipeline` runs six detectors in fixed order:

| Detector | Effect |
|----------|--------|
| Navigation | Penalizes the spike frame, boosts the settled frame, marks it mandatory |
| Typing | Runs ≥ 3 frames: penalizes mid-run frames, boosts the final one |
| Animation | Same cells changing with low variance → heavy penalty |
| New element | Cluster appearing in main content → bonus |
| Cursor | 1 changed cell with max change < 0.10 → `multiplyPattern(0.0)`, score zeroed |
| Scroll | High-change runs → mid penalty, end bonus |

Cursor-only frames are the one case where a frame's score is driven to exactly
zero, which effectively removes it from consideration.

### A5. Selection — the real filter

`FrameSelector.select()` (L36–124), in order:

1. **Empty candidates** → return empty list
2. **Mandatory frames** collected first
3. **Greedy fill** by `finalScore` up to `video.max.frames` (default **80**)
4. **Temporal NMS**: non-mandatory picks must be ≥ `video.selection.min.gap.seconds`
   (default **0.5s**) apart, else skipped silently
5. **Deduplication**: fingerprint similarity ≥ `video.selection.similarity.threshold`
   (default **0.92**) drops the lower-scoring frame; counted in `duplicatesRemoved`
6. **Coverage enforcement**: any temporal gap larger than
   `min(coverageIntervalSeconds, maxGapSeconds)` gets a frame inserted; the budget
   is allowed to grow to `ceil(maxFrames × 1.1)`
7. **Blank fallback**: if the best frame in a coverage gap scores < 0.05, the
   midpoint frame is used instead
8. **Last-frame rule**: the final video frame is added if it scores ≥ 0.05, is
   within 2s of a mandatory frame, or nothing was selected in the last 10s
9. **Trim to allowance**: lowest-scoring non-mandatory frames removed; a warning is
   printed to stderr if mandatory frames alone exceed the allowance
10. **Ordering**: sorted chronologically by frame index / time

Similarity metric (`GridDiffCalculator.similarity`, L151–166) is a mean absolute
difference of the 12×12 intensity fingerprints normalized by the cell threshold —
**not** a perceptual hash, SSIM, or raw pixel diff.

### A6. Encode (resize + compress)

`matToJpeg()` (L267–292), applied only to selected frames:

- **Resize**: if `video.frame.max.width` > 0 and width exceeds it, proportional
  downscale (default **1280px**; 0 disables)
- **Compress**: `IMWRITE_JPEG_QUALITY` = `video.jpeg.quality` (default **75**)
- A selected index that fails to encode raises `IOException`

### A7. Checks that do NOT exist on this path

- No blank / black / white frame detection
- No corrupt-image validation beyond the OpenCV read
- No MIME or magic-byte check on extracted bytes (done later, at encode time)
- No per-frame byte cap and no total request payload cap
- No pixel-level redaction or masking of video content

---

## Path B — Agent Loop Screenshots

### B1. Action-based gating

`AgentLoop.shouldTakeScreenshot()` (L355–372) decides whether to capture at all:

- **Capture**: INIT, GOTO, CLICK, TYPE, SELECT_OPTION, KEYPRESS, HOVER, CHECK,
  RELOAD_PAGE, SCROLL
- **Skip**: WAIT (logged as skipped)
- **Unknown action**: capture (safe default)

### B2. Multi-viewport tiling

Used when `agent.multi.viewport.max.frames` > 1 **and** the action changed page
shape (INIT / GOTO / RELOAD_PAGE). `BrowserSession.viewportScrollScreenshotsJpeg`:

- Max tiles: default **6**
- Short page (`totalHeight <= viewportHeight + 50`) → single screenshot, no scroll
- Stops on max frames, unchanged `scrollY` (bottom detected), or page bottom
- 80px overlap between tiles; step = `max(200, viewportHeight - 80)`
- Zero-length frames are not added to the list
- On exception: falls back to a single viewport screenshot

### B3. Capture and failure handling

`BrowserSession.viewportScreenshotJpeg` (L402–418):

- Quality clamped to `[0, 100]`
- On Playwright failure: sleep 500ms and retry once; a second failure returns
  `new byte[0]`, which is later skipped at encode time (call proceeds text-only)

### B4. Secret redaction

`redactSecrets()` (L438–449) replaces registered token values with `{Token}`
placeholders. This applies to **prompt text only**, not to pixels.

### B5. Checks that do NOT exist on this path

- No dimension or aspect-ratio validation
- No deduplication of consecutive screenshots
- No resize beyond the Playwright viewport size

---

## Path C — Relevance Triage Gate

`video/relevance/VideoRelevanceGate.evaluate()` (L42–64). Runs on already-extracted
frames and can stop the main LLM call entirely.

| Condition | Outcome |
|-----------|---------|
| `video.relevance.enabled=false` | Skipped, proceed |
| Null / empty frame list | Fail open — accept |
| Sampling | `sampleEvenly()` picks `video.relevance.sample.frames` (default **8**), always including the first; stride = `(size-1)/(target-1)` |
| Triage LLM call fails | Fail open — accept |
| Unparseable classifier JSON | Fail open — accept |
| Unknown category | Accept |
| OUT_OF_DOMAIN with `reject.out.of.domain=false` | UNCERTAIN — warn and continue |
| Confidence < `video.relevance.min.confidence` (75) | UNCERTAIN — warn and continue |
| High-confidence rejection | **REJECT** — main LLM call is never made |

The gate is deliberately fail-open: every error path accepts the video.

---

## Shared Encoding Layer (all paths)

`llm/AnthropicMessages.buildBody()` (L34–67):

| Check | Behavior |
|-------|----------|
| Null or zero-length bytes | Silently skipped, not added to content |
| Media type | Magic-byte sniff: `FF D8 FF` → `image/jpeg`, else `image/png` |
| Bytes shorter than 3 | Defaults to `image/png` |
| Base64 | Standard encoder; no decode round-trip validation |
| Ordering | All image blocks first, then the single text block |

Identical for `BedrockAnthropicClient.invokeWithMultipleImages()` and
`LlmProxyClient.invokeWithMultipleImages()`. The proxy client never logs request
bodies, so base64 frame data does not reach logs.

---

## Execution Order

### Video → LLM
