# Claude Everywhere

Use the **Claude Code** running on your computer from your phone, as if you were sitting at your desk: live streaming output, token-usage heartbeat, current activity, collapsible tool blocks, and seamless hand-off of the same session between phone and computer. Self-hosted, fully open source and hackable.

- **One-command setup on your computer**: `./install.sh` — installs deps, generates a token, prints a QR code, and starts the service.
- **One-tap connect on your phone**: scan the QR code in your terminal with the camera; the app fills in the address and token and connects automatically.
- **Your data stays on your devices**: the service runs on your own machine and traffic goes over [Tailscale](https://tailscale.com) (a private, encrypted WireGuard network). No third-party cloud.

```
Phone app  ──WebSocket──>  relay service (Node/TS, Agent SDK)  ──resume per turn──>  claude
 (Kotlin/Compose)          stateless: source of truth is ~/.claude/projects/*.jsonl
                                      │
                          terminal `claude --resume <id>` continues the same session, no fork
```

---

## Prerequisites

- **Node ≥ 20** on the computer.
- **[Claude Code](https://claude.com/claude-code)** installed and signed in on the computer (the relay drives it via the Agent SDK).
- **[Tailscale](https://tailscale.com)** on both the computer and the phone — this is how the phone reaches your computer from any network (cellular, another Wi-Fi, …). It's free for personal use. See the next section.

> Without Tailscale you can still use it, but only when the phone and computer are on the **same Wi-Fi** (the installer falls back to your LAN IP).

---

## Set up Tailscale

Tailscale puts your computer and phone on the same private, encrypted network. Each device gets a stable `100.x.y.z` address, and the phone can reach the computer from anywhere — without exposing any port to the public internet.

### 1. On the computer

**macOS**
```bash
brew install --cask tailscale     # or install "Tailscale" from the Mac App Store
```
Open the Tailscale app and sign in (Google / GitHub / email). That's it — it now runs in the menu bar.

> The App Store/cask build keeps its CLI inside the app bundle at
> `/Applications/Tailscale.app/Contents/MacOS/Tailscale`. `install.sh` looks there automatically.
> To use `tailscale` directly in your shell, symlink it once:
> `sudo ln -sf /Applications/Tailscale.app/Contents/MacOS/Tailscale /usr/local/bin/tailscale`

**Linux**
```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up                 # prints a login URL — open it and sign in
```

### 2. On the phone (Android)

1. Install **Tailscale** from Google Play.
2. Sign in with the **same account** you used on the computer.
3. Toggle the VPN **on** (you'll see a key icon in the status bar).

### 3. Verify

On the computer:
```bash
tailscale status          # should list your phone as a device
tailscale ip -4           # your computer's Tailscale IP (the 100.x the installer uses)
```
If your phone shows up here, you're ready. (Keep Tailscale enabled on the phone whenever you want to connect.)

---

## Quick start

### 1. On your computer

```bash
git clone <this-repo> claude-everywhere
cd claude-everywhere
./install.sh
```

The script checks prerequisites, then: installs dependencies → generates an `AUTH_TOKEN` on first run (saved to `server/.env`) → detects your Tailscale (or LAN) IP → **prints a QR code in the terminal** → starts the service (`ws://0.0.0.0:4000`).

### 2. On your phone (Android)

Install the app (see "Building the app" below). On first launch you'll see the **Connect** screen — **scan the QR code in your terminal with the phone camera**, and the app fills in the server address and token and connects. If scanning doesn't work, paste them manually on that screen.

---

## Building the app

For now you build from source (no prebuilt APK is published yet):

```bash
cd kotlin-app
# Open in Android Studio and run, or install to a connected device from the CLI:
./gradlew installDebug
```

Native Kotlin + Jetpack Compose, minSdk 31. No Node/Metro required.

---

## How it works

- **server/** — the relay service. Each message = a one-shot `query()` (`@anthropic-ai/claude-agent-sdk`) that runs to completion and exits; session state lives not in memory but on disk as the standard transcript (`~/.claude/projects/.../<id>.jsonl`). Session metadata pointers are kept in `~/.claude-remote/sessions.json` (survives restarts). It maps the SDK event stream into a compact WebSocket protocol, and `fs.watch`-es the currently subscribed session file so that terminal hand-offs / background turns are pushed to the phone in near real time.
- **kotlin-app/** — the phone app (native Kotlin/Compose). OkHttp WebSocket (events backed by a `Channel`, never dropped), a Compose "liquid glass" UI, conversation rendered from structured `ContentBlock`s, and automatic refresh of the current session on foreground/reconnect.

**Seamless phone ⇄ computer hand-off**: a session started on the phone can be continued with `claude --resume <id>` on the computer; conversely, reopening it on the phone (or just leaving it open) shows what the computer continued and lets you keep going — both sides agree on the same on-disk transcript (see `server/verify.ts`).

## Configuration

`server/.env` (created by `install.sh`; see also `server/.env.example`):

| Key | Description |
|---|---|
| `AUTH_TOKEN` | Required; the phone sends it to connect. Generated automatically by `install.sh` on first run. |
| `DEFAULT_CWD` | Default working directory for sessions started from the app (defaults to the user's home). |
| `CONTEXT_LIMIT` | Denominator for the Context% gauge; set `1000000` for Opus 1M context, default 200000 (corrected at runtime using the model's real window). |
| `PORT` / `HOST` | Listen port/address, default `4000` / `0.0.0.0`. |

## Security

Traffic runs over Tailscale, which is already encrypted end-to-end (WireGuard), so the relay speaks plain `ws://` inside that private tunnel and relies on a single bearer `AUTH_TOKEN`. Don't expose port 4000 to the public internet directly — keep it on your tailnet (or LAN). The token lives in `server/.env`, which is git-ignored; rotate it by deleting the `AUTH_TOKEN` line and re-running `./install.sh` (then re-scan on the phone).

## Permissions

Defaults to `permissionMode: "bypassPermissions"`: the phone is you, controlling your own computer, so every tool is auto-approved without prompting. The protocol reserves a `permission.request` / `permission.respond` channel; switch back to `default` mode + `canUseTool` if you want dangerous operations to prompt on the phone.

## License

[MIT](./LICENSE)
