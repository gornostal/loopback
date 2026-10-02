---
name: loopback
description: Ask the human a question, get a confirmation, or collect a free-text comment on their phone. Prefer over asking in the terminal. Use when a decision is the user's to make. Run in the background; the script exits when they answer.
---

# Loopback: human-in-the-loop

Loopback sends your question to the user (phone notification and other methods). They see your title, context,
a list of options you propose (like a Claude Code `AskUserQuestion` prompt), and an optional
"my answer" free-text field. Their reply comes back to you as JSON on stdout.

Script: `python3 {{SKILL_DIR}}/loopback.py`.
Use it as described below without reading its code or running `--help`; check those only if it misbehaves.

Config comes from `LOOPBACK_URL` / `LOOPBACK_AGENT_KEY` or `~/.config/loopback/config.json`.

## When to use

- You need approval before something hard to reverse (deploy, delete, pay, send, force-push).
- Two or more reasonable approaches exist and the choice is the user's, not yours.
- A requirement is ambiguous and guessing wrong would waste real work.
- You finished and want sign-off or a comment before closing out.

Don't use it for questions you can answer from the code, docs, or sensible defaults.

## Ask

**Run this command in the background with an option to be notified when it's done.** It blocks until the
user responds (humans can take minutes or hours), so never run it in the foreground with a short shell
timeout. While it runs, do work that doesn't depend on the answer, or simply wait for the notification.

```sh
python3 {{SKILL_DIR}}/loopback.py ask "Deploy api v2.3 to production?" \
  --context "CI is green on main. 1 migration, ~2 min downtime." \
  --option "Deploy now :: run the migration and roll out" \
  --option "Deploy tonight :: 02:00 UTC window" \
  --option "Hold :: I'll follow up" \
  --source "my-project"
```

- `--option "Label :: description"` — repeat for each choice; description is optional. Order them by
  how likely they are; put your recommendation first and say so in its description.
- `--multi` — let the user pick several options.
- `--no-text` — hide the free-text field (default is to show it so they can answer in their own words).
- `--context` — markdown. Give the facts they need to decide in a few lines; headers, lists, code and
  quotes render.
- `--source` — who is asking; shown in the inbox and notification. Use the project name (e.g. the repo
  or directory you are working in) so the user can tell requests from different projects apart.
- `--timeout <seconds>` — stop waiting after this long (default 43200 = 12 hours). Rarely needed.

When the process exits, read its stdout:

```json
{ "id": "…", "status": "answered", "title": "…", "answer": { "selected": ["Deploy now"], "text": "watch error rates after" } }
```

`answer.selected` holds the labels they chose (may be empty if they only typed), `answer.text` holds
their free-text comment or `null`. Treat text as overriding or refining the selection.

Exit codes: **0** answered · **2** cancelled (the user dismissed it, or the process was killed) ·
**3** timed out, request still open · **1** error or bad usage.

If the process is killed, the script withdraws the request so the notification leaves the user's phone.
On timeout the request stays answerable: check it later with `status <id>`, or `cancel` it if it no longer
matters. A `cancelled` or `pending` result means no decision was made: don't act on it and don't
fabricate an answer; tell the user you're still waiting.

## Status and cancel

```sh
python3 {{SKILL_DIR}}/loopback.py status <id>   # instant; exit 3 if still pending
python3 {{SKILL_DIR}}/loopback.py cancel <id>   # withdraw; dismisses the notification
```

Use `status` if you lost track of a background `ask` (the id is printed on stderr when it starts).
Use `cancel` when the question no longer matters: you found the answer yourself, or the task changed.

## Writing a good question

- Title: one short question, as the notification headline ("Merge PR #42?").
- Context: what happened, what's at stake, what you recommend. Basically, your usual output to the terminal.
- Options: 2–4, mutually exclusive unless `--multi`, each a concrete action. Avoid a bare "Yes/No"
  when "Deploy now / Hold" says more.
- One request per decision. Don't bundle unrelated questions.
