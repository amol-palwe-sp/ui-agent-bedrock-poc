# UI Agent Bedrock POC

> **Natural-language browser automation** — give a URL and a plain-English goal; the agent drives Chromium using AWS Bedrock (Claude vision) without any hand-written CSS/XPath selectors.

---

## How it works

```
User: --url + --goal
        │
   ┌────▼─────────────────────────────────────────────────────────────────────┐
   │  OBSERVE                                                                  │
   │  Playwright loads page → JS scrapes visible controls →                   │
   │  assigns data-skyvern-id="N" per element → builds numbered list + PNG   │
   └────┬─────────────────────────────────────────────────────────────────────┘
        │
   ┌────▼──────────────────────────────────────────────────────────────────────┐
   │  PLAN (AWS Bedrock — Claude vision)                                        │
   │  Receives: goal + URL + element list + screenshot                          │
   │  Returns:  JSON actions e.g. CLICK element_id=2 / TYPE element_id=1       │
   └────┬──────────────────────────────────────────────────────────────────────┘
        │
   ┌────▼──────────────────────────────────────────────────────────────────────┐
   │  ACT (Playwright)                                                          │
   │  Maps element_id → [data-skyvern-id='N'] locator                          │
   │  Robust action ladder: Playwright → coordinate click → JS click           │
   │  Type ladder: fill → pressSequentially → keyboard.type → JS setter        │
   │  Waits for load / navigation to settle before continuing                  │
   └────┬──────────────────────────────────────────────────────────────────────┘
        │
        └── loop back to OBSERVE (until DONE / max steps)
```

**No author-written selectors.** The scraper dynamically discovers every visible interactive element on each observation turn and assigns a fresh numeric id. The LLM grounds its actions to that list + screenshot — so the same agent code works on any page.

---

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| **JDK 17** | Must be discoverable by Gradle toolchain |
| **AWS account** | Bedrock `InvokeModel` permission for the chosen model |
| **Bedrock model access** | Vision-capable Claude model or inference profile enabled in your account |
| **AWS credentials** | Default profile chain, named profile, or IAM role |
| **SSO users** | Run `aws sso login` before starting |
| **Network** | Outbound to Bedrock endpoints for your region |
| **Disk** | Playwright downloads Chromium on first run (~300 MB) |

---

## Quick start

### 1. Configure

Edit `src/main/resources/application.properties`:

```properties
aws.region=us-east-1
aws.profile=default           # or leave blank for default chain

# On-demand model (no inference profile needed):
bedrock.model.id=anthropic.claude-3-5-sonnet-20241022-v2:0

# Claude 4.x via inference profile ARN:
# bedrock.model.id=arn:aws:bedrock:us-east-1:YOUR_ACCOUNT:inference-profile/global.anthropic.claude-sonnet-4-6

anthropic.max_tokens=4096
anthropic.temperature=0
agent.max_steps=20
browser.headless=false
browser.slow_mo_ms=0
```

> `BEDROCK_MODEL_ID` env var overrides `bedrock.model.id`.

### 2. Run

```bash
# From the repo root:
./gradlew run --args='--url=https://example.com --goal=Click the "Sign in" button'

# Multi-word goals with special chars (Gradle splits on spaces — POC merges them back):
./gradlew run --args='--url=https://admin.google.com/ac/users --goal=enter "user@domain.com" in the email field then click Next'
```

On first run Gradle downloads dependencies and Playwright downloads Chromium.

---

## Project layout

```
ui-agent-bedrock-poc/
├── build.gradle                          # Standalone Gradle project
├── settings.gradle
├── src/main/
│   ├── java/com/sailpoint/poc/uiagent/
│   │   ├── UiAgentPocApplication.java    # Entry point + CLI parsing
│   │   ├── AgentLoop.java                # Observe → Plan → Act loop
│   │   ├── PocConfig.java                # application.properties reader
│   │   ├── JsonUtil.java                 # JSON extraction helpers
│   │   ├── BedrockModelHints.java        # Model-id routing heuristics
│   │   ├── bedrock/
│   │   │   └── BedrockAnthropicClient.java  # Bedrock InvokeModel + vision
│   │   └── browser/
│   │       └── BrowserSession.java       # Playwright: scrape + actions
│   └── resources/
│       └── application.properties        # Local config (not committed with real creds)
└── README.md
```

---

## Action reference

The LLM returns a JSON plan; supported actions:

| Action | Fields | Purpose |
|--------|--------|---------|
| `CLICK` | `element_id` | Click a button, link, checkbox … |
| `TYPE` | `element_id`, `text` | Fill an input / textarea / contenteditable |
| `SELECT_OPTION` | `element_id`, `label` or `value` | Select from `<select>` or custom dropdown |
| `KEYPRESS` | `key` | Press Enter, Escape, Tab … |
| `SCROLL` | `direction`, `amount` | Scroll the viewport |
| `GOTO` | `url` | Navigate to a different URL |
| `WAIT` | `ms` | Pause (max 10 s) |
| `DONE` | — | Goal achieved, stop |
| `TERMINATE` | `message` | Goal cannot be achieved |

---

## Robustness features

- **Click ladder:** Playwright locator click → coordinate mouse click → JS `el.click()`
- **Type ladder:** `fill` → `pressSequentially` (per-char delay) → `keyboard.type` → JS native value setter (React-friendly)
- **Navigation guard:** `safeguardBeforeAction()` waits for `DOMContentLoaded` before every action so a prior navigation never leaves the context destroyed
- **Batch cut on URL change:** stops the current action batch when navigation is detected; re-scrapes fresh element ids
- **Auto-dismiss dialogs:** `alert`/`confirm`/`prompt` are auto-dismissed so they never block
- **Popup / new-tab tracking:** OAuth/SSO flows that open new tabs are automatically followed
- **Retry on scrape failure:** `listInteractables` retries once if the document is mid-replacement

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `ValidationException: on-demand throughput isn't supported` | Model needs inference profile ARN — see `bedrock.model.id` comments above |
| `ResourceNotFoundException: end of its life` | Model id is retired — use `anthropic.claude-3-5-sonnet-20241022-v2:0` or newer |
| `ClassNotFoundException: sso…` | Run `aws sso login`; SSO SDK modules are on the classpath |
| Chromium won't start | Try `browser.headless=true`; or install deps: `npx playwright install-deps chromium` |
| No elements found | Page may use web components / shadow DOM — try scrolling or waiting via a WAIT action |


runVideo - Video to script
```
./gradlew runVideo --args='--video=/path/to/recording.mp4'

# With options:
./gradlew runVideo --args='--video=/path/to/recording.mp4 --debug-frames=./debug_frames --max-frames=60 --url=https://example.com'
```

run - Script to end automation
```
./gradlew run --args='--url=https://admin.google.com/ac/users --goal=click Sign in with Google button, then enter "amol@sptechdev.com" in the Email or phone field, then click Next button, then enter "GOOGLE@S09u@M09u" in the Password field, then click Next button, then click Admin icon, then click Directory, then click Users, then click Add user, then enter "John Doe" in the Name field, then click on Primary email field and then click ADD NEW USER button, then this completes all steps — do not perform any further actions'
```

Run POC Application
```
./gradlew runUI
# opens http://localhost:8080 automatically
```

---

## Account Aggregation — Terminal Workflow

The aggregation pipeline runs in **two steps** from the terminal.  All output files land in `./output/` (created automatically).

### Prerequisites

- `application.properties` configured (region, model id, browser settings).
- AWS credentials active (`aws sso login` if using SSO).
- Video file: an MP4 screen recording showing:
  1. Navigation to the login page → authentication → reaching the user/account list page.
  2. At least one pagination click on that table (so Claude can identify the pattern).

---

### Step 1 — Analyse video → save plan JSON

```bash
./gradlew runAggregationPlan \
  --args='--video=/absolute/path/to/recording.mp4 \
          --url=https://admin.google.com/ac/users'
```

**Arguments:**

| Argument | Required | Description |
|----------|----------|-------------|
| `--video=<path>` | Yes | Absolute path to the MP4 recording |
| `--url=<url>` | Yes | Target URL of the user/account list page |

**What it does:**
1. Extracts key frames from the video (OpenCV).
2. Sends frames to Claude — extracts navigation steps + pagination pattern.
3. Opens a browser, navigates to `--url`, and runs the extracted steps.
4. Saves a plan JSON to `./output/aggregation-plan_YYYYMMDD_HHmmss.json`.

**Note the plan file path** printed at the end — you need it for Step 2.

---

### Step 2 — Scrape with real credentials → write CSV

```bash
./gradlew runAggregation \
  --args='--plan=./output/aggregation-plan_YYYYMMDD_HHmmss.json \
          --url=https://admin.google.com/ac/users \
          --goal=enter "you@example.com" in the Email field, then click Next, then enter "your-password" in the password field, then click Next'
```

**Arguments:**

| Argument | Required | Description |
|----------|----------|-------------|
| `--plan=<path>` | Yes | Plan JSON from Step 1 |
| `--url=<url>` | Yes | Same URL as Step 1 |
| `--goal=<text>` | Recommended | Navigation steps with **real credentials** (overrides Claude-extracted goal) |

**`--goal` format** — comma-separated `then` phrases, credentials in double quotes:
```
enter "user@example.com" in the Email field, then click Next, then enter "password" in the password field, then click Next
```

**What it does:**
1. Opens a browser and navigates to `--url`.
2. Runs `--goal` via AgentLoop to reach the list page (login, menu clicks, etc.).
3. Detects the accounts table / ARIA grid (JS + Claude vision).
4. Paginates through all pages (up to `aggregation.max.pages` in `application.properties`).
5. Writes `./output/accounts_YYYYMMDD_HHmmss.csv`.

**Output summary** printed on completion:
```
ACCOUNT AGGREGATION COMPLETE
Pages scraped    : 10
Total accounts   : 247
Columns          : Name, Email, Status, Last sign in
Output file      : /absolute/path/to/output/accounts_20260505_143022.csv
```

---

### Optional: one-shot (no plan file)

Skips saving a plan — useful for a quick test:

```bash
./gradlew runAggregation \
  --args='--video=/absolute/path/to/recording.mp4 \
          --url=https://admin.google.com/ac/users \
          --goal=enter "you@example.com" in the Email field, then click Next, then enter "your-password" in the password field, then click Next'
```

---

### Configuration knobs (`application.properties`)

| Property | Default | Effect |
|----------|---------|--------|
| `aggregation.max.pages` | `50` | Safety ceiling on pages scraped |
| `aggregation.output.dir` | `./output` | Directory for CSV files |
| `browser.headless` | `false` | Set `true` for headless scraping |
| `video.max.frames` | `80` | Max frames sent to Claude per video |

---

### UI workflow (after terminal validation)

Once Step 2 completes and you have a valid CSV:

```bash
./gradlew runUI
# opens http://localhost:8080 automatically
```

Navigate to **`http://localhost:8080/aggregation`** → upload the same MP4 → fill in credentials → click **▶ Run Aggregation**.

---

Run Aggregation (quick reference)
```bash
# Step 1
./gradlew runAggregationPlan \
  --args='--video=/path/to/recording.mp4 --url=https://admin.google.com/ac/users'

# Step 2 (replace plan filename and credentials)
./gradlew runAggregation \
  --args='--plan=./output/aggregation-plan_20260430_140000.json \
          --url=https://admin.google.com/ac/users \
          --goal=enter "amol@sptechdev.com" in the Email field, then click Next, then enter "GOOGLE@S09u@M09u" in the password field, then click Next'
```