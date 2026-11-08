#!/usr/bin/env bash
# Installs Loopback Desktop for the current user (no root): binary in ~/.local/bin, icons in the
# hicolor theme, and a desktop entry so it shows up in the app launcher.
#
#   ./install-linux.sh             build a release binary, then install it
#   ./install-linux.sh --no-build  install the binary already in src-tauri/target/release
#   ./install-linux.sh --uninstall remove what this script installed (keeps your settings)
set -euo pipefail

cd "$(dirname "$0")"
NAME=loopback-desktop
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
BIN_DIR="$HOME/.local/bin"
APPS="$DATA/applications"
ICONS="$DATA/icons/hicolor"

refresh_caches() {
  command -v update-desktop-database >/dev/null && update-desktop-database -q "$APPS" || true
  command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$ICONS" || true
}

if [[ "${1:-}" == "--uninstall" ]]; then
  rm -f "$BIN_DIR/$NAME" "$APPS/$NAME.desktop"
  rm -f "$ICONS"/{32x32,64x64,128x128,256x256}/apps/$NAME.png
  refresh_caches
  echo "Uninstalled. Settings are left in ~/.config/name.gornostal.loopback.desktop/."
  exit 0
fi

if [[ "${1:-}" != "--no-build" ]]; then
  bun install
  bun run tauri build --no-bundle
fi

BIN=src-tauri/target/release/$NAME
[[ -x "$BIN" ]] || { echo "No release binary at $BIN; run without --no-build." >&2; exit 1; }

install -Dm755 "$BIN" "$BIN_DIR/$NAME"
install -Dm644 src-tauri/icons/32x32.png "$ICONS/32x32/apps/$NAME.png"
install -Dm644 src-tauri/icons/64x64.png "$ICONS/64x64/apps/$NAME.png"
install -Dm644 src-tauri/icons/128x128.png "$ICONS/128x128/apps/$NAME.png"
install -Dm644 src-tauri/icons/128x128@2x.png "$ICONS/256x256/apps/$NAME.png"

mkdir -p "$APPS"
cat >"$APPS/$NAME.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Loopback
GenericName=Agent Inbox
Comment=Answer your AI agents' questions and read their notifications
Exec=$BIN_DIR/$NAME
Icon=$NAME
Terminal=false
Categories=Utility;
Keywords=AI;agent;HITL;notifications;claude;codex;
StartupNotify=true
StartupWMClass=$NAME
SingleMainWindow=true
X-GNOME-UsesNotifications=true
Actions=Hidden;

[Desktop Action Hidden]
Name=Start in tray
Exec=$BIN_DIR/$NAME --hidden
EOF

command -v desktop-file-validate >/dev/null && desktop-file-validate "$APPS/$NAME.desktop"
refresh_caches
echo "Installed $BIN_DIR/$NAME and $APPS/$NAME.desktop"
