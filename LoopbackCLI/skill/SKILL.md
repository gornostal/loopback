---
name: loopback
description: Ask the human a question, get a confirmation, or collect a free-text comment. Prefer over asking in terminal. Use when a decision is the user's to make. Blocks until they answer, or polls for the answer later while you keep working.
---

# Loopback: human-in-the-loop

Loopback sends your question to the user (phone notification and other methods). They see your title, context,
a list of options you propose (like a Claude Code `AskUserQuestion` prompt), and an optional
"my answer" free-text field. Their reply comes back to you as JSON.

Script: `python3 {{SKILL_DIR}}/loopback.py`.
See description below for usage. Use the script immediately without checking it's code or running `--help`.
Only if it doesn't work, you may choose to check that.

Config comes from `LOOPBACK_URL` / `LOOPBACK_AGENT_KEY` or `~/.config/loopback/config.json`.

## When to use

- You need approval before something hard to reverse (deploy, delete, pay, send, force-push).
- Two or more reasonable approaches exist and the choice is the user's, not yours.
- A requirement is ambiguous and guessing wrong would waste real work.
- You finished and want sign-off or a comment before closing out.

Don't use it for questions you can answer from the code, docs, or sensible defaults.

## Ask and wait (default)

```sh
python3 {{SKILL_DIR}}/loopback.py ask "Deploy api v2.3 to production?" \
  --context "CI is green on main. 1 migration, ~2 min downtime." \
  --option "Deploy now :: run the migration and roll out" \
  --option "Deploy tonight :: 02:00 UTC window" \
  --option "Hold :: I'll follow up" \
  --source "claude-code" \
  --timeout 300
```

- `--option "Label :: description"` — repeat for each choice; description is optional. Order them by
  how likely they are; put your recommendation first and say so in its description.
- `--multi` — let the user pick several options.
- `--no-text` — hide the free-text field (default is to show it so they can answer in their own words).
- `--context` — markdown. Give the facts they need to decide in a few lines; headers, lists, code and
  quotes render.
- `--source` — who is asking; shown in the inbox and notification. Keep it short.
- `--timeout <seconds>` — how long to block (default 300). Pass a matching timeout to your shell tool.

Output on stdout:

```json
{ "id": "…", "status": "answered", "title": "…", "answer": { "selected": ["Deploy now"], "text": "watch error rates after" } }
```

`answer.selected` holds the labels they chose (may be empty if they only typed), `answer.text` holds
their free-text comment or `null`. Treat text as overriding or refining the selection.

Exit codes: **0** answered · **2** cancelled · **3** still pending when the timeout hit · **1** error or bad usage.

## Polling instead of blocking

Humans can take minutes or hours. Two ways to avoid tying up your turn:

1. **Timed out (exit 3)?** The request is still live. Re-run `wait` with the id printed in the JSON:

   ```sh
   python3 {{SKILL_DIR}}/loopback.py wait <id> --timeout 300
   ```

   Repeat until exit code is 0 or 2. Between polls, do work that doesn't depend on the answer.

2. **Fire and continue.** Create the request without blocking, keep working, and check back:

   ```sh
   python3 {{SKILL_DIR}}/loopback.py ask "…" --option "…" --no-wait   # prints {id, status: "pending"}
   python3 {{SKILL_DIR}}/loopback.py status <id>                      # instant, exit 3 if still pending
   python3 {{SKILL_DIR}}/loopback.py wait <id> --timeout 120          # block up to 2 min
   ```

Never fabricate an answer when the status is `pending`; either keep polling, do independent work,
or tell the user you're still waiting. A `cancelled` status means the request was withdrawn:
stop waiting and don't act on it.

If the question no longer matters (you found the answer yourself, or the task changed), withdraw it so
the notification disappears from their phone: `python3 {{SKILL_DIR}}/loopback.py cancel <id>`.

## Writing a good question

- Title: one short question, as the notification headline ("Merge PR #42?").
- Context: what happened, what's at stake, what you recommend. Basically, your usual ouput to the terminal.
- Options: 2–4, mutually exclusive unless `--multi`, each a concrete action. Avoid a bare "Yes/No"
  when "Deploy now / Hold" says more.
- One request per decision. Don't bundle unrelated questions.
