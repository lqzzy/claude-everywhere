// Fetch Claude subscription quota utilization (5h / 7d). Same data source as the claude-hud plugin:
//   GET https://api.anthropic.com/api/oauth/usage
//   Auth: the OAuth accessToken in the macOS keychain (stored there after logging into claude)
// Returns utilization (0-100) + resets_at (ISO). Cached for 60s to avoid hitting the endpoint too often.
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { UsageQuota } from "./protocol";

const TTL_MS = 60_000;
let cache: { data: UsageQuota; ts: number } | null = null;

// Read the OAuth accessToken from the macOS keychain; on failure, fall back to ~/.claude/.credentials.json (older versions).
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
    /* keychain read failed, try the file */
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
    if (!res.ok) return cache?.data ?? null; // on failure, reuse the old cache
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
