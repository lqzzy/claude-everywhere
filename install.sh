#!/usr/bin/env bash
# Claude Everywhere —— 电脑端一键部署。
# 装依赖 → 生成 token(首次)→ 探测 Tailscale/局域网 IP → 打印手机扫码二维码 → 启动服务。
# 重复运行安全(幂等):token 只在首次生成,之后复用。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT/server"

echo "▶ 安装依赖(首次稍慢)…"
npm install --silent

# ---- AUTH_TOKEN:复用 .env 里已有的,否则生成一个并写入 ----
touch .env
if grep -q '^AUTH_TOKEN=.\+' .env; then
  TOKEN="$(grep '^AUTH_TOKEN=' .env | head -1 | cut -d= -f2-)"
else
  TOKEN="$(openssl rand -hex 32)"
  # 删掉可能存在的空 AUTH_TOKEN= 行,再追加
  if grep -q '^AUTH_TOKEN=' .env; then
    grep -v '^AUTH_TOKEN=' .env > .env.tmp && mv .env.tmp .env
  fi
  echo "AUTH_TOKEN=$TOKEN" >> .env
  echo "  已生成新的 AUTH_TOKEN 并写入 server/.env"
fi

PORT="$(grep '^PORT=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)"
PORT="${PORT:-4000}"

# ---- 探测对手机可达的 IP:优先 Tailscale(任意网络可达且加密),否则局域网 ----
IP=""
if command -v tailscale >/dev/null 2>&1; then
  IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
fi
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
echo "  📱 用手机扫码连接(手机需与电脑在同一 Tailscale / 局域网):"
echo "════════════════════════════════════════════════"
node connect-qr.mjs "$URL" "$TOKEN"

echo "▶ 启动服务: ws://0.0.0.0:$PORT  (Ctrl+C 退出)"
exec npm start
