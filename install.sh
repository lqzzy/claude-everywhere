#!/usr/bin/env bash
# Claude Everywhere — one-command setup for the computer side.
# Preflight deps -> install npm deps -> generate token (first run) -> detect Tailscale/LAN IP
# -> print the phone QR code -> start the service. Idempotent: the token is generated once and reused.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT/server"
OS="$(uname -s)"

# ---- Preflight: required tools ----
missing=0
require() { # <cmd> <install hint>
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "✗ missing required tool: $1"
    echo "    install: $2"
    missing=1
  fi
}
require node "macOS: brew install node   |   Linux: https://nodejs.org or your package manager"
require claude "install & sign in to Claude Code: https://claude.com/claude-code"
if [ "$missing" = 1 ]; then
  echo
  echo "Install the tool(s) above, then re-run ./install.sh"
  exit 1
fi

# ---- Locate Tailscale (CLI on PATH, or the macOS app's bundled CLI) ----
TS_BIN=""
if command -v tailscale >/dev/null 2>&1; then
  TS_BIN="tailscale"
elif [ -x "/Applications/Tailscale.app/Contents/MacOS/Tailscale" ]; then
  TS_BIN="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
fi

ts_install_hint() {
  case "$OS" in
    Darwin) echo "    macOS: brew install --cask tailscale  (or the Mac App Store), then open it and sign in" ;;
    Linux)  echo "    Linux: curl -fsSL https://tailscale.com/install.sh | sh   then: sudo tailscale up" ;;
    *)      echo "    see https://tailscale.com/download" ;;
  esac
}

TS_IP=""
if [ -n "$TS_BIN" ]; then
  TS_IP="$("$TS_BIN" ip -4 2>/dev/null | head -1 || true)"
  if [ -z "$TS_IP" ]; then
    echo "⚠ Tailscale is installed but not logged in / not up."
    echo "    run:  sudo tailscale up    (then re-run ./install.sh)"
    echo "    see the README \"Set up Tailscale\" section for details."
    echo
  fi
else
  echo "⚠ Tailscale not found — recommended for connecting over the internet."
  echo "  (without it, the phone must be on the same Wi-Fi as this computer.)"
  ts_install_hint
  echo "  See the README \"Set up Tailscale\" section for the full walkthrough."
  echo
fi

echo "▶ Installing server dependencies (first run may be slow)…"
npm install --silent

# ---- AUTH_TOKEN: reuse the one in .env, otherwise generate and persist ----
gen_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    node -e 'console.log(require("crypto").randomBytes(32).toString("hex"))'
  fi
}
touch .env
if grep -q '^AUTH_TOKEN=.\+' .env; then
  TOKEN="$(grep '^AUTH_TOKEN=' .env | head -1 | cut -d= -f2-)"
else
  TOKEN="$(gen_token)"
  if grep -q '^AUTH_TOKEN=' .env; then
    grep -v '^AUTH_TOKEN=' .env > .env.tmp && mv .env.tmp .env
  fi
  echo "AUTH_TOKEN=$TOKEN" >> .env
  echo "  generated a new AUTH_TOKEN and wrote it to server/.env"
fi

PORT="$(grep '^PORT=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)"
PORT="${PORT:-4000}"

# ---- Pick an IP the phone can reach: prefer Tailscale, else LAN ----
IP="$TS_IP"
if [ -z "$IP" ]; then
  if command -v ipconfig >/dev/null 2>&1; then
    IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"   # macOS
  fi
fi
if [ -z "$IP" ]; then
  IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"   # Linux
fi
IP="${IP:-127.0.0.1}"

URL="ws://$IP:$PORT"

echo
echo "════════════════════════════════════════════════"
if [ -n "$TS_IP" ]; then
  echo "  📱 Scan to connect (turn Tailscale on, same account, on the phone):"
else
  echo "  📱 Scan to connect (phone must be on the same Wi-Fi):"
fi
echo "════════════════════════════════════════════════"
node connect-qr.mjs "$URL" "$TOKEN"

echo "▶ Starting service: ws://0.0.0.0:$PORT  (Ctrl+C to stop)"
exec npm start
