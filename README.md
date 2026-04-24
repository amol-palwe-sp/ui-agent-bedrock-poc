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
