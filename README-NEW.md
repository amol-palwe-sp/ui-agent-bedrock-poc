# UI Agent Bedrock POC — New User Guide

This document is the **onboarding README** for the project. It does not replace `README.md`; use this file for tech stack, how pieces fit together, configuration, and **both CLI and web UI** workflows.

---

## What this project does

**Natural-language browser automation:** you supply a starting URL and a plain-English goal. The system loads the page in **Chromium (Playwright)**, scrapes visible interactive elements, sends a **screenshot + numbered element list** to **AWS Bedrock (Claude with vision)**, executes returned actions (click, type, etc.), and repeats until the model says **DONE** or a step limit is reached.

There is also a **video → goal** pipeline: upload or point at an **MP4** screen recording; frames are extracted (OpenCV), sent to Claude, and a suggested `./gradlew run ...` style **goal line** is produced for reuse in the agent.

**No hand-written CSS/XPath selectors** for the main loop: the scraper assigns `data-skyvern-id` per element each turn; the model grounds actions on ids + screenshot.

---

## Tech stack

| Technology | Role in this repo |
|------------|-------------------|
| **Java 17** | Language; Gradle toolchain pins JDK 17. |
| **Gradle** | Build, dependency management, `run` / `runUI` / `runVideo` tasks. |
| **AWS SDK v2 (BOM)** | `bedrockruntime`, `auth`, `regions`, `apache-client` — invoke Bedrock `InvokeModel`. |
| **AWS SSO modules** | `sso`, `ssooidc` — so `~/.aws/config` with `sso_start_url` works without extra setup. |
| **Playwright (Java)** | Chromium automation: navigate, scrape interactables, screenshots, clicks/types, popups. |
| **org.json** | Lightweight JSON handling in the POC. |
| **OpenCV (org.openpnp:opencv)** | Video frame decoding and scene-change–based frame sampling for `runVideo` / UI generate. |
| **Apache Commons FileUpload + Commons IO** | Multipart MP4 upload in the web UI (`/api/generate`). |
| **javax.servlet-api (compileOnly)** | Satisfies compile-time references from Commons FileUpload 1.x. |
| **`com.sun.net.httpserver.HttpServer`** | Embedded HTTP server for the POC web UI (port **8080**), static assets + JSON/SSE APIs. |
| **Vanilla HTML/CSS/JS** | UI under `src/main/resources/ui/` (`index.html`, `style.css`, `app.js`). |

---

## How the main agent loop works

1. **Observe** — Playwright opens the URL; in-page JavaScript lists visible controls; each gets `data-skyvern-id="N"`; the app builds a numbered list and captures a screenshot (JPEG or PNG per config).
2. **Plan** — `BedrockAnthropicClient` sends goal, URL, element list, and image(s) to Claude on Bedrock; response is JSON actions (`CLICK`, `TYPE`, …).
3. **Act** — `BrowserSession` maps `element_id` → `[data-skyvern-id='N']` and runs actions with fallbacks (click ladder, type ladder), navigation guards, optional inter-action delay.

The loop is implemented in `AgentLoop`; CLI entry is `UiAgentPocApplication`; the web server delegates runs to the same `AgentLoop` in a background thread (`RunHandler`).

---

## Entry points (three ways to run)

| Gradle task | Main class | Purpose |
|-------------|------------|---------|
| `./gradlew run` | `UiAgentPocApplication` | **CLI agent** — `--url` + `--goal` only. |
| `./gradlew runUI` | `AgentUIServer` | **Web UI** — http://localhost:8080 (browser may open automatically). |
| `./gradlew runVideo` | `VideoToGoalRunner` | **CLI video → goal** — `--video=/path/to/file.mp4` (+ options). |

---

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| **JDK 17** | Required by `build.gradle` toolchain. |
| **AWS account** | `bedrock:InvokeModel` (and model access) for your chosen model / inference profile. |
| **AWS credentials** | Default chain, or `aws.profile` in `application.properties`. **SSO:** run `aws sso login` before runs. |
| **Network** | Outbound to Bedrock in the configured region. |
| **Disk** | First Playwright run downloads Chromium (~300 MB). |

---

## Configuration (`application.properties`)

File: **`src/main/resources/application.properties`** (classpath). If missing, the app fails fast with a message referencing a copy-from-example flow (see `PocConfig`).

**Environment override:** `BEDROCK_MODEL_ID` overrides `bedrock.model.id` when set (non-blank).

### AWS / Bedrock

| Property | Purpose |
|----------|---------|
| `aws.region` | Bedrock region (required). |
| `aws.profile` | Named AWS profile; empty = default credential chain. |
| `bedrock.model.id` | On-demand model id **or** inference profile ARN/id for newer Claude tiers. |

### LLM

| Property | Default idea |
|----------|----------------|
| `anthropic.max_tokens` | Max tokens for Bedrock Anthropic messages. |
| `anthropic.temperature` | Sampling temperature. |

### Agent

| Property | Purpose |
|----------|---------|
| `agent.max_steps` | Max Observe→Plan→Act iterations. |
| `agent.log.file` | JSONL log path, or `none` / blank to disable. |
| `agent.no_progress_limit` | Consecutive no-progress steps before a stuck warning is injected. |

### Browser / screenshots

| Property | Purpose |
|----------|---------|
| `browser.headless` | Headless vs headed Chromium. |
| `browser.slow_mo_ms` | Playwright slow-mo for debugging. |
| `browser.start.maximized` | Maximize window on headed runs. |
| `browser.fullscreen.width` / `browser.fullscreen.height` | Used with fullscreen-style sizing (e.g. headless). |
| `browser.viewport.width` / `browser.viewport.height` | Viewport when not maximized. |
| `screenshot.format` | `jpeg` or `png`. |
| `screenshot.jpeg.quality` | JPEG quality 0–100. |
| `browser.action.timeout.click.ms` / `type.ms` / `navigate.ms` | Per-action timeouts. |
| `browser.inter.action.delay.ms` | Upper bound of random delay between actions (0 = off). |

### Video pipeline (`runVideo` / UI generate)

| Property | Purpose |
|----------|---------|
| `video.max.frames` | Cap on frames sent to the model. |
| `video.change.threshold` | Min pixel change ratio to treat a frame as new. |
| `video.min.gap.seconds` | Min time between sampled frames. |
| `video.debug.frames.dir` | Optional folder to write extracted frames (CLI `runVideo` can override with `--debug-frames=`). |

---

## Run manually (CLI)

### 1. Configure AWS and properties

1. Ensure `application.properties` exists under `src/main/resources/` with at least `aws.region` and `bedrock.model.id`.
2. For **Claude 4.x** and similar, use an **inference profile ARN** (or id) if on-demand model id is rejected — see Bedrock console → Inference profiles.
3. SSO: `aws sso login` before `./gradlew …`.

### 2. Run the browser agent (URL + goal)

From the project root:

```bash
./gradlew run --args='--url=https://example.com --goal=Click the first obvious link'
```

**Goal text and Gradle:** tokens after `--goal=` (or after `--goal`) are merged until the next argument starting with `--`, so multi-word goals survive Gradle’s splitting.

```bash
./gradlew run --args='--url=https://example.com --goal=enter user@example.com in the email field then click Next'
```

**Required flags:** `--url` (must start with `http://` or `https://`) and `--goal` (non-empty).

### 3. Run video → goal (no browser automation)

```bash
./gradlew runVideo --args='--video=/absolute/path/to/recording.mp4'
```

**Optional CLI flags:**

- `--debug-frames=/path/to/dir` — save extracted frames.
- `--max-frames=60` — override frame cap.
- `--url=https://...` — force URL context in the prompt (skips URL detection in the default prompt).

Properties in `application.properties` still apply for defaults (threshold, gap, max frames, etc.).

---

## Run via the web UI

### Start the server

```bash
./gradlew runUI
```

- Listens on **http://localhost:8080**.
- May open your default browser automatically (`Desktop` / `open` / `xdg-open` / Windows `start`).

### What the UI does (conceptually)

- **Upload video → generate goal** — Browser sends **multipart/form-data** to `POST /api/generate` (`video` file + optional `url`, `maxFrames`). Server writes a temp MP4, runs `VideoFrameExtractor` → Bedrock → `GoalExtractor`, returns JSON (`goalLine`, `url`, `steps`, token usage, cost fields, etc.). Progress lines are also pushed to the shared log queue for the stream.
- **Run agent** — Client sends `POST /api/run` with JSON body containing **`goalLine`**: a single string that must include **`--url=...`** and **`--goal=...`** (same shape as a CLI command snippet). The server parses URL and goal, starts **one** background agent thread; only one run at a time (`409` if already running).
- **Live logs** — `GET /api/stream` is **Server-Sent Events**: JSON `data:` lines for logs, status, progress, done.
- **Stop** — `POST /api/stop` interrupts the agent thread.
- **Status** — `GET /api/status` returns `{"status":"running"|"ready"}`.

Static files are served from classpath **`/ui/`** (e.g. `/`, `/style.css`, `/app.js`, favicon).

---

## Web API summary (for integrators)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/stream` | SSE stream of agent/generate progress. |
| POST | `/api/generate` | Multipart: `video` (MP4), optional `url`, `maxFrames`. |
| POST | `/api/run` | JSON: `{ "goalLine": "./gradlew run --args='--url=... --goal=...'" }` — body must contain parsable `--url=` and `--goal=`. |
| POST | `/api/stop` | Stop current agent. |
| GET | `/api/status` | Agent busy or ready. |

**Note:** The UI server is a **POC** — no auth, CORS `*` on APIs, single-user assumptions. Do not expose to untrusted networks without hardening.

---

## Supported agent actions (LLM JSON)

The model returns structured actions consumed by `AgentLoop` / `BrowserSession`, including: **CLICK**, **TYPE**, **SELECT_OPTION**, **KEYPRESS**, **SCROLL**, **GOTO**, **WAIT**, **DONE**, **TERMINATE** (see `README.md` for the full table).

---

## Project layout (high level)

```
ui-agent-bedrock-poc/
├── build.gradle                 # Dependencies + run, runUI, runVideo
├── settings.gradle
├── README.md                    # Original concise README (unchanged)
├── README-NEW.md                # This file
└── src/main/
    ├── java/com/sailpoint/poc/uiagent/
    │   ├── UiAgentPocApplication.java   # CLI agent entry
    │   ├── AgentLoop.java                 # Observe → Plan → Act
    │   ├── PocConfig.java                 # Properties loader
    │   ├── BedrockModelHints.java         # Inference profile heuristics
    │   ├── bedrock/BedrockAnthropicClient.java
    │   ├── browser/BrowserSession.java
    │   ├── video/                         # VideoToGoalRunner, extractor, prompts, goal extraction
    │   └── ui/                            # AgentUIServer, handlers, SSE
    └── resources/
        ├── application.properties
        └── ui/                            # index.html, style.css, app.js, images
```

---

## Troubleshooting (quick reference)

| Symptom | Likely fix |
|---------|------------|
| `ValidationException` / on-demand not supported | Use an **inference profile ARN** for `bedrock.model.id`. |
| Model id “end of life” | Switch to a supported model id or profile from the Bedrock console. |
| SSO / `ClassNotFoundException` around SSO | Run `aws sso login`; ensure SSO SDK deps (already in `build.gradle`). |
| Chromium fails to start | Try `browser.headless=true`; on Linux install OS deps (`npx playwright install-deps chromium`). |
| Empty element list | Heavy shadow DOM / custom widgets; try scrolling or WAIT in the goal. |
| `runVideo` UI: file too large | Server limit **500 MB** per upload in `GenerateHandler`. |

---

## First-time checklist

1. Install **JDK 17** and use `./gradlew` (wrapper) from repo root.
2. Create or edit **`src/main/resources/application.properties`** with region, profile (if needed), and model id / inference profile.
3. Run **`aws sso login`** if your profile uses SSO.
4. Run **`./gradlew run`** with a trivial URL/goal, or **`./gradlew runUI`** and exercise the UI.
5. For video: **`./gradlew runVideo --args='--video=...'`** or use the UI upload flow.

---

## Further reading

- **`README.md`** — Diagram of the observe/plan/act loop, action table, and extra command examples.
- **Usage in code** — `UiAgentPocApplication.printUsage()`, `VideoToGoalRunner` stderr usage text, and `AgentUIServer` for port and routes.
