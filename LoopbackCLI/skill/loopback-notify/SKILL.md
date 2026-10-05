---
name: loopback-notify
description: Send the human a one-way push notification that needs no reply. Use when a long task finished or failed, something noteworthy happened, or the user said "let me know when…". 
---

# Loopback notify: tell the user, don't ask

Sends a push notification to the user's phone with your title and a markdown message. They can read it
and open the full text in the Loopback app, but there is nothing to answer and you don't wait.

Script: `python3 {{SKILL_DIR}}/loopback.py`.
Use the script immediately without checking its code or running `--help`.
Only if it doesn't work, you may choose to check that.

Config comes from `LOOPBACK_URL` / `LOOPBACK_AGENT_KEY` or `~/.config/loopback/config.json`.


## Usage

```sh
python3 {{SKILL_DIR}}/loopback.py notify "Release v2.3 is live" \
  --context "Deployed at 14:02 UTC. **0 errors** in the first 5 minutes. Next: monitor for an hour." \
  --source "my-project"
```

- `title` — one short headline; the notification title ("Build finished", "Deploy failed").
- `--context` — markdown with the message. Keep the first sentence self-contained: the push shows the
  collapsed text, the full markdown renders in the app.
- `--source` — who is notifying; shown in the inbox and notification. Use the project name (the repo
  or directory you are working in) so the user can tell projects apart.

Output on stdout, exit code **0**:

```json
{ "id": "…", "status": "notified", "title": "Release v2.3 is live" }
```

Exit code **1** means an error (unreachable server, bad key, bad usage); the message is on stderr.
There is nothing to poll, wait on or cancel afterwards.


