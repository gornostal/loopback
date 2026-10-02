# About

Loopback is an app that receives requests from AI agents for human confirmation & comments. Helpful for building HITL in agentic workflows.

Flow: an agent `POST`s a request (title, markdown context, options, optional free-text) to the server → the server stores it in SQLite and pushes it via FCM → the Android app shows a notification → the user picks an option and/or types "my answer" → the agent gets the answer by long-polling.

# Dir structure

- `LoopbackAndroid` - Android app in Kotlin (Jetpack Compose, Material 3, OkHttp, kotlinx.serialization, Firebase Messaging).
  - `app/src/main/java/name/gornostal/loopback/`
    - `MainActivity.kt`, `MainViewModel.kt` — single-activity app; navigation is a `Screen` state in the ViewModel (Inbox / Request / Settings).
    - `api/` — REST client + models mirroring the server's JSON.
    - `push/` — FCM service, notification builder, device-token registration.
    - `ui/` — Compose screens, a small markdown renderer, time formatting.
  - `app/google-services.json` is gitignored and optional; the Firebase Gradle plugin is applied only when it exists.
- `LoopbackServer` - Server in TypeScript with Bun runtime. No framework, no runtime deps.
  - `src/index.ts` — `Bun.serve` routes, bearer-token auth, long-poll waiters.
  - `src/db.ts` — `bun:sqlite` store (`requests`, `devices`).
  - `src/fcm.ts` — FCM HTTP v1 client (service-account JWT → OAuth token, no SDK).
- `LoopbackCLI` - Bun/TypeScript CLI (`loopback`) that installs the agent skill and doubles as a terminal client.
  - `skill/SKILL.md` — the skill template; `{{SKILL_DIR}}` is replaced with the install path. Installed to `~/.claude/skills/loopback` and `~/.codex/skills/loopback` (or `./.claude|.codex/skills` with `--project`).
  - `skill/scripts/loopback.mjs` — the tiny dependency-free Node script agents run (`ask` / `wait` / `status`). `src/cli.ts` imports it, so there is one HTTP implementation.
  - Config: `~/.config/loopback/config.json` or `LOOPBACK_URL` + `LOOPBACK_AGENT_KEY` (`{"url","agentKey"}`).

# Conventions

- Server: `bun run dev` (watch), `bun run typecheck`. Config via `.env` (see `.env.example`).
- CLI: `bun run typecheck`; `bun link` in `LoopbackCLI` puts `loopback` on PATH; `bun run build` makes a standalone binary.
- Skill script must stay plain Node ≥ 18 with zero deps (it is copied verbatim into agents' skill dirs).
- Android: `./gradlew assembleDebug`. Versions live in `gradle/libs.versions.toml`; AGP 9 with built-in Kotlin.
- API shape is documented in `README.md`; keep `api/Models.kt`, `src/types.ts` and `skill/scripts/loopback.mjs` in sync when changing it.
- API is split: `/api/agent/*` (`LOOPBACK_AGENT_KEY`; create + follow by id, no listing) and `/api/app/*` (`LOOPBACK_APP_KEY`; the phone). Request ids are unguessable base58 and act as the agent's capability; keep them that way.
- Single user, one app key + one shared agent key for v1. Don't add multi-tenancy without discussing.
