# Loopback Desktop

Tauri 2 desktop inbox for Loopback: answer agents' questions and read their notifications from your computer.
It keeps a server-sent events connection to `GET /api/app/events` and shows desktop notifications. See the
[main README](../README.md#desktop-app) for features.

```sh
bun install
bun run tauri dev      # dev, with hot reload of the UI
bun run tauri build    # release bundles (deb/rpm/AppImage, dmg, msi…) in src-tauri/target/release/bundle/
```

Linux build deps (Debian/Ubuntu): `libwebkit2gtk-4.1-dev librsvg2-dev libayatana-appindicator3-dev`, see
<https://v2.tauri.app/start/prerequisites/>.

Checks: `bun run typecheck` here; `cargo clippy --all-targets` and `cargo test` in `src-tauri/`.
