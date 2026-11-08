# About

Loopback is an app that receives requests from AI agents for human confirmation & comments. Helpful for building HITL in agentic workflows.

Flow: an agent `POST`s a request (title, markdown context, options, optional free-text) to the server → the server stores it in SQLite, pushes it via FCM and broadcasts it on the SSE stream → the Android app (FCM) or desktop app (SSE) shows a notification → the user picks an option and/or types "my answer" → the agent gets the answer by long-polling.

# Dir structure

- `LoopbackAndroid` - Android app in Kotlin (Jetpack Compose, Material 3, OkHttp, kotlinx.serialization, Firebase Messaging).
  - `app/src/main/java/name/gornostal/loopback/`
    - `MainActivity.kt`, `MainViewModel.kt` — single-activity app; navigation is a `Screen` state in the ViewModel (Inbox / Request / Settings).
    - `api/` — REST client + models mirroring the server's JSON.
    - `push/` — FCM service, notification builder, device-token registration.
    - `ui/` — Compose screens, a small markdown renderer, time formatting.
  - `app/google-services.json` is gitignored and optional; the Firebase Gradle plugin is applied only when it exists.
- `LoopbackDesktop` - Desktop app with Tauri 2 (Rust + vanilla TypeScript/Vite, no UI framework).
  - `src-tauri/src/` — `lib.rs` (state, commands, tray, hide-on-close), `api.rs` (`/api/app` client, reqwest), `events.rs` (SSE loop → webview events + notifications), `notify.rs` (desktop notifications: notify-rust on Linux with click/quick-answer/close, Tauri plugin elsewhere), `config.rs`, `models.rs`.
  - `src/` — `main.ts` (UI: sidebar list + detail pane + settings), `backend.ts` (typed `invoke`/`listen`), `markdown.ts` (marked + DOMPurify; links open in the browser), `format.ts`, `types.ts`.
  - All HTTP goes through Rust; the webview only talks IPC. Agent-supplied markdown is sanitized and the CSP blocks remote scripts.
- `LoopbackServer` - Server in TypeScript with Bun runtime. No framework, no runtime deps.
  - `src/index.ts` — `Bun.serve` routes, bearer-token auth, long-poll waiters, `/api/app/events` SSE broadcast.
  - `src/db.ts` — `bun:sqlite` store (`requests`, `devices`).
  - `src/fcm.ts` — FCM HTTP v1 client (service-account JWT → OAuth token, no SDK).
- `LoopbackCLI` - Python skill installer and the two skills. Standard library only, no deps.
  - `install.py` — interactive: prompts for server URL, agent key, skills (`loopback` / `loopback-notify`, default both), agents (Claude / Codex), scope (global / current dir); writes the config and copies each chosen skill.
  - `skill/loopback/SKILL.md` — the `ask` skill (question, wait for an answer); `skill/loopback-notify/SKILL.md` — the one-way `notify` skill. `{{SKILL_DIR}}` is replaced with the install path. Installed to `~/.claude/skills/<skill>` and `~/.codex/skills/<skill>` (or `./.claude|.codex/skills/<skill>` for project scope).
  - `skill/loopback.py` — the single script both skills run (`ask` / `notify` / `wait` / `status` / `cancel`); copied into every installed skill dir. No args → help on stderr, exit 1.
  - Config: `~/.config/loopback/config.json` or `LOOPBACK_URL` + `LOOPBACK_AGENT_KEY` (`{"url","agentKey"}`).

# Conventions

- Server: `bun run dev` (watch), `bun run typecheck`. Config via `.env` (see `.env.example`).
- CLI: `./install.py` in `LoopbackCLI`. Both scripts must stay plain Python ≥ 3.8, standard library only (`loopback.py` is copied verbatim into agents' skill dirs).
- Android: `./gradlew assembleDebug`. Versions live in `gradle/libs.versions.toml`; AGP 9 with built-in Kotlin.
- Desktop: `bun run tauri dev` / `bun run tauri build` in `LoopbackDesktop`; `bun run typecheck`, `cargo clippy` and `cargo test` in `src-tauri`.
- API shape is documented in `README.md`; keep `api/Models.kt`, `src/types.ts`, `skill/loopback.py` and the desktop app's `src-tauri/src/models.rs` + `src/types.ts` in sync when changing it. Every state change to a request must also `broadcast()` an SSE event.
- Requests have a `kind`: `ask` (pending until answered/cancelled) or `notify` (stored as `notified`, nothing to wait on; sits in the app's Pending tab until opened, which sets `readAt`). Notifications use their own Android channel.
- API is split: `/api/agent/*` (`LOOPBACK_AGENT_KEY`; create + follow by id, no listing) and `/api/app/*` (`LOOPBACK_APP_KEY`; the phone). Request ids are unguessable base58 and act as the agent's capability; keep them that way.
- Single user, one app key + one shared agent key for v1. Don't add multi-tenancy without discussing.
