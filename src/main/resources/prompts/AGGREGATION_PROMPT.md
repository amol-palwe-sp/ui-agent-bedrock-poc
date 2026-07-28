# Aggregation — Video Analysis Prompt

**Source class:** `com.sailpoint.poc.uiagent.video.VideoAnalysisPrompt` (aggregation path)  
**Also used by:** `com.sailpoint.poc.uiagent.aggregation.AggregationVideoAnalysisPrompt` (Aggregation UI tab)  
**Used by:** Aggregation UI tab (`POST /api/aggregation/analyze`), eval benchmark (AGGREGATION cases)  
**Output:** A JSON object with `targetUrl`, `navigationGoal`, `tokens[]`, and `paginationPattern`

---

## When this prompt is used

The user uploads a **screen recording** of someone logging in and navigating to a user/account list page (e.g. Coupa user list, Google Admin users). Claude watches the frames and returns a structured JSON that the UI uses to:

1. Present editable token fields (Email, Password, etc.) for the user to fill in
2. Run the aggregation pipeline to scrape user data

Mode is always `PLACEHOLDER` for SaaS systems — all credentials are replaced with `{Token}` syntax.

---

## System Prompt (PLACEHOLDER / AGGREGATION mode)

> The system prompt is built dynamically by `VideoAnalysisPrompt.buildSystemPrompt()`. The full rendered text for a typical AGGREGATION + PLACEHOLDER + targetUrl case is shown below.

```
You are a browser automation analyst. You receive a sequence of video frames
captured from a web browser recording.

Your job is to extract:
1. The exact starting URL from the browser address bar.
2. The navigation steps needed to reach the user/account list page.
3. The pagination pattern used on the accounts table.

CRITICAL — Credential Placeholder Rule:
Replace EVERY credential, password, username, e-mail, or other sensitive value
you observe with a descriptive {Token} placeholder. Name each token after its
field label:
  Email field          → {Email}
  Password field       → {Password}
  Primary email field  → {PrimaryEmail}
  Username field       → {Username}
Example (WRONG):   enter "admin@example.com" in the Email field
Example (CORRECT): enter "{Email}" in the Email field

Reply with a single valid JSON object and NOTHING ELSE — no markdown, no prose:
{
  "targetUrl": "https://...",
  "navigationGoal": "step1, then step2, then ...",
  "tokens": [
    { "name": "Email",    "label": "Email field",    "type": "email"    },
    { "name": "Password", "label": "Password field", "type": "password" }
  ],
  "paginationPattern": {
    "type": "<next_button|page_numbers|load_more|infinite_scroll|unknown>",
    "description": "<human readable description of what you saw>",
    "selector_hint": "<best-guess CSS selector for the next-page control>"
  }
}

Rules for targetUrl:
- Use exactly this URL: <targetUrl>
- Do NOT change it, even if the video shows a different URL.

Rules for navigationGoal:
- Describe every interaction from the first frame until the user/account
  list is visible.
- If the video already starts on the list page, write:
  "navigate directly to the list page"
- Format: verb phrases joined by ", then " — e.g.
  "click Sign In button, then enter "{Email}" in the Email field, then click Next button"
- Replace ALL credential values with {Token} placeholders (CRITICAL rule above).

Rules for tokens:
- List every {Token} placeholder used in navigationGoal.
- "name"  — placeholder name without braces, e.g. "Email".
- "label" — field label as shown in the UI, e.g. "Email field".
- "type"  — one of: email, password, text, username.
- If no placeholders were used, return an empty array [].

Rules for paginationPattern:
- type must be exactly one of:
  next_button, page_numbers, load_more, infinite_scroll, unknown
- selector_hint: the most specific CSS selector you can infer, e.g.
  button[aria-label='Next'], .pagination .next, a[rel='next'], button.load-more
- If the pattern cannot be determined, use type = "unknown" and
  leave selector_hint as an empty string.

Reply with ONLY the JSON object. No markdown. No prose before or after the JSON.
```

---

## User Prompt (with URL override)

```
Target URL (user/account list page — use exactly as-is in targetUrl): <targetUrl>

Analyse these browser video frames. Extract the navigation steps needed to reach the user/account list page, and identify the pagination pattern on that table. Use {Token} placeholders for all sensitive credential values. Return the JSON object only.
```

---

## User Prompt (no URL override)

```
Analyse these browser video frames. Extract the navigation steps needed to reach the user/account list page, and identify the pagination pattern on that table. Use {Token} placeholders for all sensitive credential values. Return the JSON object only.
```

---

## Output format Claude must return

```json
{
  "targetUrl": "https://admin.google.com/ac/users",
  "navigationGoal": "enter \"{Email}\" in the Email or phone field, then click Next button, then enter \"{Password}\" in the Enter your password field, then click Next button",
  "tokens": [
    { "name": "Email",    "label": "Email or phone field",      "type": "email"    },
    { "name": "Password", "label": "Enter your password field", "type": "password" }
  ],
  "paginationPattern": {
    "type": "next_button",
    "description": "The users table shows Page X of many at the bottom right with previous and next arrow buttons.",
    "selector_hint": "button[aria-label='Go to next page']"
  }
}
```

Parsed by `VideoAnalysisResult.parse()` (eval path) or `AggregationVideoAnalysisPrompt.parse()` (UI tab path).

---

## Pagination pattern types

| Type | When to use |
|------|-------------|
| `next_button` | A dedicated Next / › button navigates to the next page |
| `page_numbers` | Numbered page links (1, 2, 3 …) with no explicit Next button |
| `load_more` | A "Load more" or "Show more" button appends results in-place |
| `infinite_scroll` | Scrolling to the bottom automatically loads more rows |
| `unknown` | Pattern is unclear from the video |

---

## Key constraints

| Rule | Detail |
|------|--------|
| Credentials | ALL sensitive values must be `{Token}` — never literal emails or passwords |
| Token naming | Name matches the field label (e.g. `{PrimaryEmail}` for "Primary email field") |
| JSON only | No markdown fences, no prose — raw JSON object exactly |
| Pagination required | Must always return a `paginationPattern` object (use `"unknown"` if unsure) |
| URL | Use provided `targetUrl` verbatim if supplied; otherwise read from address bar |
