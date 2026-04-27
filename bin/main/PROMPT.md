UI Agent Bedrock POC — LLM Prompt Generation Standard
The Core Problem
You need a consistent, structured prompt that:

Takes a video as input
Extracts the workflow steps
Outputs a reliable, parseable --args string every time
Recommended LLM Prompt (Feed with Video)
SYSTEM PROMPT:
==============


```
You are a UI workflow analyzer for an automated browser agent. 
Your job is to watch a screen recording and produce a precise, 
structured goal string for a Playwright + Bedrock UI agent.

The agent accepts this exact CLI format:
  --url=<starting_url> --goal=

RULES FOR URL:
- Extract the exact starting URL from the first frame of the video
- Include full path (e.g. https://admin.google.com/ac/users)
- Never truncate or guess the URL

RULES FOR GOAL STRING:
- Each distinct user action becomes one clause
- Clauses are joined by ", then "  (comma-space-then-space)
- Never use newlines inside the goal string
- Never number the steps
- String must be wrapped in single quotes in the final output

ACTION VOCABULARY (use ONLY these patterns):
┌─────────────────────────────────────────────────────────────────┐
│ TYPING   → enter "" in the  field      │
│ CLICKING → click  button                         │
│           click  link                       │
│           click                                   │
│ SELECTING→ click  option                          │
│           select "" from the  dropdown   │
│ CHECKING → check the  checkbox                  │
│ HOVERING → hover over                            │
│ WAITING  → wait for                      │
│ UPLOADING→ upload "" to the  field      │
└─────────────────────────────────────────────────────────────────┘

FIELD LABEL RULES:
- Use the EXACT visible label text from the UI, not your interpretation
- If label has placeholder text, prefer placeholder over surrounding label
- Capitalize as seen on screen
- If a button has an icon only, describe it as: click  icon button

SENSITIVE DATA RULES:
- Transcribe passwords, emails, and usernames EXACTLY as performed in the video
- Do NOT redact or mask any values
- If text is not clearly visible, write:  as the placeholder

CONFIRMATION DIALOGS:
- Always include confirmation button clicks (SUSPEND, CONFIRM, YES, OK, DONE etc.)
- Treat multi-step dialogs as separate "then" clauses

OUTPUT FORMAT (strict):
========================
You must return ONLY the following block, nothing else:

```goal
./gradlew run --args='--url= --goal='
No explanation. No preamble. No markdown outside the code block. No line breaks inside --args value.

============== USER PROMPT:
Watch the attached screen recording carefully.

Identify:

The starting URL (first frame / browser address bar)
Every user interaction in chronological order
Then generate the goal string following all rules above.

Output only the ```goal code block.

---

## Output Example (What the LLM Should Return)

./gradlew run --args='--url=https://admin.google.com/ac/users --goal=enter "amol@sptechdev.com" in the Email or Phone field, then click Next button, then enter "GOOGLE@S09u@M09u" in the Enter your Password field, then click Next button, then enter Anand in the ADD a filter field, then click First name starts with, then click Anand Joshi user Button, then click SUSPEND USER button, then click SUSPEND and DONE'
---

## Prompt Variants by Scenario

### Variant A — When You Already Know the URL
```
Pre-filled context: The starting URL is https://admin.google.com/ac/users
Focus only on extracting the goal steps from the video.
```

### Variant B — When Credentials Should Be Parameterized
```
Replace any sensitive values (passwords, emails) with
placeholders in {{VARIABLE_NAME}} format.
Example: enter "{{ADMIN_EMAIL}}" in the Email or Phone field
```

### Variant C — When Video Has Multiple Workflows
```
If the video shows more than one independent workflow
(separate page loads / logout-login cycles),
output one ```goal block per workflow, labeled:
### Workflow 1
### Workflow 2
```

---

## Validation Checklist (Run After LLM Output)

```
□ Starts with ./gradlew run --args='
□ Contains --url= with a valid http/https URL  
□ Contains --goal= with steps separated by ", then "
□ No newlines inside the --args value
□ No numbered steps (1. 2. 3.)
□ All typed values are in double quotes
□ Ends with single quote '
□ Action verbs match vocabulary (enter/click/select/check/hover)
□ Confirmation dialogs are included as steps
```

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────────────┐
│           GOAL STRING ANATOMY                               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  enter "value" in the FIELD field                           │
│  │       │              │                                   │
│  │       │              └── exact label text from UI        │
│  │       └── exact text typed (always double-quoted)        │
│  └── always "enter" for typing                              │
│                                                             │
│  click ELEMENT button                                       │
│  │     │                                                    │
│  │     └── exact button text from UI                        │
│  └── always "click" for clicking                            │
│                                                             │
│  SEPARATOR between steps: ", then "                         │
│                            ↑    ↑                           │
│                         comma  space                        │
└─────────────────────────────────────────────────────────────┘
```

---

## Integration Tips

```bash
# 1. Feed to Claude via API (with video attachment)
# Model: claude-3-5-sonnet or claude-3-opus (vision capable)

# 2. Parse output programmatically
grep -oP "(?<=--args=').*(?=')" llm_output.txt

# 3. Pipe directly to gradle
eval $(grep -oP "(?<=\`\`\`goal\n).*(?=\n\`\`\`)" llm_output.txt)

# 4. Validate before running
echo "$GOAL_STRING" | python3 validate_goal.py
```

---

## Why This Works Consistently

| Design Choice | Reason |
|---|---|
| `ONLY these patterns` vocabulary | Prevents LLM from inventing new syntax |
| `Output ONLY the code block` | Eliminates preamble variance |
| Explicit separator `", then "` | Single source of truth for parsing |
| Double quotes around typed values | Clear tokenization boundary |
| Exact label text rule | Matches what Playwright's scraper sees |
| Confirmation dialog rule | Prevents incomplete workflow capture |


```
