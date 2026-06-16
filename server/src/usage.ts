// 取 Claude 订阅额度利用率(5h / 7d)。数据源与 claude-hud 插件一致:
//   GET https://api.anthropic.com/api/oauth/usage
//   鉴权:macOS 钥匙串里的 OAuth accessToken(claude 登录后存在那)
// 返回 utilization(0-100)+ resets_at(ISO)。带 60s 缓存,避免频繁打接口。
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { UsageQuota } from "./protocol";

const TTL_MS = 60_000;
let cache: { data: UsageQuota; ts: number } | null = null;

// 从 macOS 钥匙串读 OAuth accessToken;失败则回退到 ~/.claude/.credentials.json(老版本)。
function readToken(): string | null {
  try {
    const raw = execFileSync(
      "/usr/bin/security",
      ["find-generic-password", "-s", "Claude Code-credentials", "-w"],
      { encoding: "utf8", timeout: 3000, stdio: ["pipe", "pipe", "pipe"] }
    );
    const t = JSON.parse(raw.trim())?.claudeAiOauth?.accessToken;
    if (t) return t;
  } catch {
    /* 钥匙串读不到,试文件 */
  }
  try {
    const p = join(homedir(), ".claude", ".credentials.json");
    if (existsSync(p)) {
      const t = JSON.parse(readFileSync(p, "utf8"))?.claudeAiOauth?.accessToken;
      if (t) return t;
    }
  } catch {
    /* ignore */
  }
  return null;
}

function pct(v: any): number {
  const n = Number(v);
  if (!Number.isFinite(n)) return 0;
  return Math.round(Math.max(0, Math.min(100, n)));
}
function ms(v: any): number {
  const t = v ? Date.parse(v) : NaN;
  return Number.isFinite(t) ? t : 0;
}

export async function getUsageQuota(): Promise<UsageQuota | null> {
  if (cache && Date.now() - cache.ts < TTL_MS) return cache.data;
  const token = readToken();
  if (!token) return null;
  try {
    const res = await fetch("https://api.anthropic.com/api/oauth/usage", {
      headers: {
        Authorization: `Bearer ${token}`,
        "anthropic-beta": "oauth-2025-04-20",
        "User-Agent": "claude-code/2.1",
      },
    });
    if (!res.ok) return cache?.data ?? null; // 失败时沿用旧缓存
    const j: any = await res.json();
    const data: UsageQuota = {
      fiveHour: { utilization: pct(j.five_hour?.utilization), resetsAt: ms(j.five_hour?.resets_at) },
      sevenDay: { utilization: pct(j.seven_day?.utilization), resetsAt: ms(j.seven_day?.resets_at) },
    };
    cache = { data, ts: Date.now() };
    return data;
  } catch {
    return cache?.data ?? null;
  }
}
