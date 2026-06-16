#!/usr/bin/env bash
# Claude Everywhere — one-command setup for the computer side.
# Checks prerequisites → installs the relay's dependencies → generates a token →
# prints a QR code for the phone → optionally downloads the APK → starts the relay.
# Safe to re-run: the token is generated once and reused.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT/server"
OS="$(uname -s)"
RELEASES="https://github.com/lqzzy/claude-everywhere/releases"
APK_URL="$RELEASES/latest/download/claude-everywhere.apk"

# --- pretty output (color only when writing to a terminal) ---
if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; C=$'\033[36m'; X=$'\033[0m'
else
  B=; D=; G=; Y=; R=; C=; X=
fi
ok()   { printf "  ${G}✓${X} %s\n" "$1"; }
warn() { printf "  ${Y}!${X} %s\n" "$1"; }
bad()  { printf "  ${R}✗${X} %s\n" "$1"; }
step() { printf "\n${B}%s${X}\n" "$1"; }

printf "\n${B}${C}Claude Everywhere${X} ${D}· setup${X}\n"

# ============ 1. prerequisites ============
step "[1/4] Checking prerequisites"
missing=0
if command -v node   >/dev/null 2>&1; then ok "Node $(node -v)"; else bad "Node not found   →  macOS: brew install node   ·   Linux: https://nodejs.org"; missing=1; fi
if command -v npm    >/dev/null 2>&1; then ok "npm $(npm -v)";   else bad "npm not found (ships with Node)"; missing=1; fi
if command -v claude >/dev/null 2>&1; then ok "Claude Code $(claude --version 2>/dev/null | head -1 || echo '(installed)')"; else bad "Claude Code not found   →  https://claude.com/claude-code"; missing=1; fi
if [ "$missing" = 1 ]; then
  printf "\n${R}Install the missing tool(s) above, then re-run ./install.sh${X}\n"
  exit 1
fi

# Tailscale: optional, but it's how the phone reaches you over the internet.
TS_BIN=""
command -v tailscale >/dev/null 2>&1 && TS_BIN="tailscale"
[ -z "$TS_BIN" ] && [ -x "/Applications/Tailscale.app/Contents/MacOS/Tailscale" ] && TS_BIN="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
TS_IP=""
if [ -n "$TS_BIN" ]; then
  TS_IP="$("$TS_BIN" ip -4 2>/dev/null | head -1 || true)"
  if [ -n "$TS_IP" ]; then ok "Tailscale connected ($TS_IP)"
  else warn "Tailscale installed but not signed in   →  run: sudo tailscale up"; fi
else
  warn "Tailscale not found (recommended; without it the phone must share your Wi-Fi)"
  case "$OS" in
    Darwin) warn "    install: brew install --cask tailscale" ;;
    Linux)  warn "    install: curl -fsSL https://tailscale.com/install.sh | sh" ;;
  esac
fi

# ============ 2. dependencies ============
step "[2/4] Installing server dependencies"
npm install --silent
ok "npm packages installed"

# ============ 3. configuration ============
step "[3/4] Configuration"
gen_token() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 32
  else node -e 'console.log(require("crypto").randomBytes(32).toString("hex"))'; fi
}
touch .env
if grep -q '^AUTH_TOKEN=.\+' .env; then
  TOKEN="$(grep '^AUTH_TOKEN=' .env | head -1 | cut -d= -f2-)"
  ok "AUTH_TOKEN reused (server/.env)"
else
  TOKEN="$(gen_token)"
  grep -q '^AUTH_TOKEN=' .env && { grep -v '^AUTH_TOKEN=' .env > .env.tmp && mv .env.tmp .env; }
  echo "AUTH_TOKEN=$TOKEN" >> .env
  ok "AUTH_TOKEN generated → server/.env"
fi
PORT="$(grep '^PORT=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)"; PORT="${PORT:-4000}"
IP="$TS_IP"
[ -z "$IP" ] && command -v ipconfig >/dev/null 2>&1 && IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
[ -z "$IP" ] && IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
IP="${IP:-127.0.0.1}"
URL="ws://$IP:$PORT"
ok "Server address: $URL"
[ -n "$TS_IP" ] || warn "Using LAN IP — the phone must be on the same Wi-Fi"

# ============ 4. connect your phone ============
step "[4/4] Connect your phone"
printf "\n${B}Scan this with the app:${X}\n\n"
node connect-qr.mjs "$URL" "$TOKEN"
printf "${B}On your phone:${X}\n"
printf "  ${C}1.${X} Install the app — download ${B}claude-everywhere.apk${X} from\n"
printf "     ${C}%s/latest${X}\n" "$RELEASES"
printf "  ${C}2.${X} Open it, tap ${B}\"Scan QR code\"${X}, and point at the QR above.\n"
printf "  ${C}3.${X} Connected. (The app remembers it — next time it just connects.)\n\n"

# Optional: also download the APK to this computer (handy for sideloading via USB / AirDrop).
if [ -t 0 ]; then
  printf "Also download the APK to this computer? [y/N] "
  read -r ans || ans=""
  case "$ans" in
    y|Y|yes|YES)
      def="$HOME/Downloads/claude-everywhere.apk"
      printf "Save to [%s]: " "$def"; read -r dest || dest=""
      dest="${dest:-$def}"
      printf "Downloading…\n"
      if curl -fL --progress-bar -o "$dest" "$APK_URL"; then ok "Saved to $dest"
      else bad "Download failed — grab it from $RELEASES/latest"; fi
      ;;
  esac
fi

step "Starting relay — ws://0.0.0.0:$PORT   (Ctrl+C to stop)"
exec npm start
