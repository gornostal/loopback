# Loopback

<img src="assets/logo.png" alt="Loopback logo" width="128" align="right">

Human-in-the-loop for AI agents. An agent asks a question, your phone buzzes, you tap an option (or type your own answer), the agent carries on.

```
agent ──POST /api/agent/requests──▶ Loopback server ──FCM push──▶ Android app
agent ◀── long-poll answer ── Loopback server ◀── answer ──  you
```

The prompt the phone shows mirrors Claude Code's `AskUserQuestion`: a title, optional markdown context, a list of options with descriptions (single or multi-select), and an optional free-text "my answer" field.

## Repo

| Dir | What |
|---|---|
| `LoopbackServer/` | Bun + TypeScript API server. SQLite storage, FCM push, long-polling. Zero runtime dependencies. |
| `LoopbackAndroid/` | Kotlin / Jetpack Compose app. Inbox, request detail, settings, push handling. |
| `LoopbackCLI/` | `install.py`: installs the **Loopback skill** (`SKILL.md` + `loopback.py`) into Claude Code and Codex. Plain Python, no dependencies. |

## Server

```sh
cd LoopbackServer
cp .env.example .env          # set LOOPBACK_APP_KEY and LOOPBACK_AGENT_KEY (openssl rand -hex 32 each)
bun install                   # dev deps only (types)
bun run dev                   # http://localhost:8787
```

Push notifications need a Firebase project:

1. Firebase console → create a project → add an Android app with package `name.gornostal.loopback`.
2. Download `google-services.json` into `LoopbackAndroid/app/`.
3. Project settings → Service accounts → *Generate new private key* → save as `LoopbackServer/service-account.json` and point `FIREBASE_SERVICE_ACCOUNT` at it.
   On a host where you can't ship a file (Railway, etc.), set `FIREBASE_SERVICE_ACCOUNT_B64` to `base64 -w0 service-account.json` instead.

Without a service account the server still works; it just logs that it can't push, and the app shows requests when you open or pull-to-refresh it.

### API

The API has two halves, each with its own key:

| Who | Prefix | Key | Can |
|---|---|---|---|
| **Agents** (skill, CLI, curl) | `/api/agent` | `LOOPBACK_AGENT_KEY` | Create requests, then fetch, wait on or cancel them **by id**. Can't list, answer or see devices. |
| **Android app** | `/api/app` | `LOOPBACK_APP_KEY` | List and read every request, answer, register for push. |

Request ids are 128 random bits encoded in base58 (about 22 chars, e.g. `7Xq9KfJ2mVb4nRtYp8LwHc`). An agent can't list requests and the ids can't be guessed, so in practice an agent only reaches the requests it created itself. Send the key as `Authorization: Bearer <key>`. Using one half's key on the other half returns `401`.

#### Agent API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/agent/requests[?wait=N]` | Create a request. Returns `201` with the request, including its `id`. With `wait`, blocks up to N s (max 600) and returns the request as it stands then: answered, cancelled or still pending. |
| `GET` | `/api/agent/requests/:id` | Current state, instantly. |
| `GET` | `/api/agent/requests/:id/wait?timeout=N` | Long-poll until answered or cancelled (default 60 s, max 600). Returns the current state on timeout. |
| `DELETE` | `/api/agent/requests/:id` | Cancel a pending request. Dismisses the phone notification. `409` if it's already answered. |

#### App API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/app/requests?status=pending&limit=100` | List. `status` = `pending` \| `answered` \| `cancelled` \| `all`, `limit` ≤ 500. Returns `{ "requests": [...] }`. |
| `GET` | `/api/app/requests/:id` | Fetch one. |
| `POST` | `/api/app/requests/:id/answer` | `{ "selected": ["Deploy"], "text": "but watch the logs" }`. `selected` must be labels from the request's `options`; more than one only when `multiSelect` is true. Wakes any agent waiting on it. `400` on an unknown label or too many picks, `409` if no longer pending. |
| `POST` | `/api/app/devices` | `{ "token", "platform", "name" }`: register an FCM token. The app does this on its own. |
| `GET` | `/api/app/devices` | List registered devices (tokens truncated). |

`GET /health` needs no key.

Create body:

```jsonc
{
  "title": "Deploy to production?",            // required; push title
  "context": "Tests **passed**.\n\n- 3 migrations\n- ~2 min downtime",  // markdown, optional
  "options": [                                 // optional; strings or {label, description}
    { "label": "Deploy", "description": "Ship it now" },
    { "label": "Hold" }
  ],
  "multiSelect": false,                        // default false; true → checkboxes, answer.selected may hold several labels
  "allowFreeText": true,                       // default true → shows "My answer" field
  "source": "deploy-bot"                       // shown in the inbox and notification
}
```

Request object (returned everywhere):

```jsonc
{
  "id": "052f…", "createdAt": "2026-10-02T09:53:08.483Z",
  "status": "pending" | "answered" | "cancelled",
  "title": "…", "context": "…", "options": [...], "multiSelect": false, "allowFreeText": true, "source": "…",
  "answer": null | { "selected": ["Deploy"], "text": "…", "answeredAt": "…" }
}
```

### Asking from an agent

The easiest path is the skill. Install it once:

```sh
cd LoopbackCLI && ./install.py
```

It asks for the server base URL, the agent key (`LOOPBACK_AGENT_KEY`), which agents (Claude, Codex or both)
and the scope: **Global** (`~/.claude/skills/loopback`, `~/.codex/skills/loopback`) or **This project**
(`./.claude/skills/loopback`, `./.codex/skills/loopback`, relative to the current directory). The URL and key
go to `~/.config/loopback/config.json` (mode 600), never into the skill dir, so a project-scoped install
is safe to commit. Re-run it to update; it offers the saved values as defaults.

The skill tells the agent when to reach for you and how to call the bundled script, which is plain
Python 3 with no dependencies (run it without arguments for help):

```sh
python3 ~/.claude/skills/loopback/loopback.py ask "Deploy api v2.3?" \
  --context "CI green. 1 migration." \
  --option "Deploy now :: run the migration and roll out" --option "Hold" \
  --source claude-code --timeout 300
# → {"id":"…","status":"answered","title":"…","answer":{"selected":["Deploy now"],"text":"watch error rates"}}
```

Humans can be slow, so the script supports two modes:

- **Blocking**: `ask … --timeout 300` long-polls and exits 0 with the answer, 2 if cancelled, or 3 if still
  pending. On 3 the agent re-runs `wait <id> --timeout 300` until it gets an answer.
- **Polling**: `ask … --no-wait` returns the id immediately; the agent keeps working and calls
  `status <id>` (instant) or `wait <id>` (blocks) later.

`cancel <id>` withdraws a pending request. The same commands work from your shell.

Without the CLI, plain curl works too (blocks up to 5 min):

```sh
curl -s -X POST "http://localhost:8787/api/agent/requests?wait=300" \
  -H "Authorization: Bearer $LOOPBACK_AGENT_KEY" -H "Content-Type: application/json" \
  -d '{"title":"Merge PR #42?","options":["Merge","Request changes"],"source":"claude-code"}' \
  | jq .answer
```

## Android app

```sh
cd LoopbackAndroid
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+, Android SDK 36. The Firebase plugin is applied only if `app/google-services.json` exists, so the app builds without it (push disabled; Settings explains how to enable it).

First launch opens Settings: enter the server URL and the **app** key (`LOOPBACK_APP_KEY`), tap **Save & test connection**. The app registers its FCM token with the server automatically. For a server on your LAN use plain `http://192.168.x.x:8787` (cleartext is allowed); for an emulator, `adb reverse tcp:8787 tcp:8787` and use `http://localhost:8787`.

Screens:

- **Inbox** — Pending / History tabs, pull to refresh, refreshes itself when a push arrives.
- **Request** — source, time, title, markdown context, options (radio or checkbox), "My answer" field, **Send answer**. Already-answered or cancelled requests open read-only.
- **Settings** — server URL, app key, push status, re-register device.

## Status

v0.1: single user, one app key + one shared agent key, Android only. Not yet: iOS, request expiry, answer edits, multiple users, web inbox.
