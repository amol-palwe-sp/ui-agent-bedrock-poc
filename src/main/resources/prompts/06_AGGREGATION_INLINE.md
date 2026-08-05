# Prompt 06 — Aggregation Inline Prompts (Runtime Vision Fallbacks)

**Purpose:** Two small, narrow-purpose prompts used during live account aggregation (Stage 2)
when CSS-selector-based automation hits its limits. They are called inline inside
`AccountAggregator` as fallbacks, not as primary paths.  
**Stage:** Stage 2 — Browser execution (aggregation phase)  
**Source class:** `AccountAggregator`

---

## 6a — Total Record Count Detection

**When called:** After JS-based attempts to read a "total records" counter fail.  
**Input:** A single JPEG screenshot of the current page.

### System Prompt

```
You are a UI analyst. Look at the screenshot and find any text that shows
the TOTAL number of users/accounts/records in this list — for example:
'1-50 of 324 users', 'Showing 324 results', '324 total accounts'.
If you can find such a total count, return ONLY valid JSON:
{ "found": true, "total": <integer> }.
If no total count is visible, return: { "found": false }.
```

### User Prompt

```
Is there a total record count displayed on this page? Return only JSON.
```

### Output

```json
{ "found": true, "total": 324 }
// or
{ "found": false }
```

---

## 6b — Next-Page Button Detection (Vision Fallback)

**When called:** After CSS-selector attempts to find a "next page" control on the accounts
table all fail. Receives multiple screenshot tiles (full page, scrolled vertically) plus
a complete list of interactable elements on the page.

### System Prompt

```
You are a pagination detector. You are given one or more screenshots that are
vertical tiles from top to bottom of the full page, plus a list of ALL
interactive elements on the page (including those below the visible fold).
Determine if there is a NEXT PAGE button or link for the ACCOUNTS TABLE
(not login form buttons).
Reply ONLY with valid JSON:
{ "hasNext": <bool>, "element_id": <string or null>, "reason": "<string>" }
 — use the exact element_id value shown in the interactable elements list.
```

### User Prompt (constructed at runtime)

```
Is there a next page button for the accounts/users table (not login)?

Interactable elements (full page, including below-fold):
[1] button | aria-label="Next" | id=a1b2c3
[2] button | label="Previous" | id=d4e5f6
...

Return JSON only.
```

### Output

```json
{ "hasNext": true, "element_id": "a1b2c3", "reason": "Next button found with aria-label" }
// or
{ "hasNext": false, "element_id": null, "reason": "No pagination control visible" }
```
