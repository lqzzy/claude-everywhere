// List importable past sessions on disk + build a full SessionSummary from a transcript (for importing into the store).
import { readFileSync, readdirSync, existsSync, statSync, openSync, readSync, closeSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import type { ImportableItem, SessionSummary } from "./protocol";
import { accumContext, contextLimitFromPeak } from "./history";

const ROOT = join(homedir(), ".claude", "projects");
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Read the first N bytes of a file (avoids loading an entire large transcript into memory just for the first-message title)
function readHead(file: string, bytes = 65536): string {
  try {
    const fd = openSync(file, "r");
    const buf = Buffer.alloc(bytes);
    const n = readSync(fd, buf, 0, bytes, 0);
    closeSync(fd);
    return buf.toString("utf8", 0, n);
  } catch {
    return "";
  }
}

// The /goal Stop-hook wraps the first message; extract the real goal inside the quotes to use as the title
function titleFrom(text: string): string {
  let t = text;
  const m = text.match(/condition:\s*"([^"]+)"/);
  if (m) t = m[1];
  return t.replace(/\s+/g, " ").trim().slice(0, 48);
}

function parseHead(raw: string): { cwd: string; title: string } {
  let cwd = "";
  let title = "";
  for (const ln of raw.split("\n")) {
    if (!ln.trim()) continue;
    let o: any;
    try { o = JSON.parse(ln); } catch { continue; }
    if (!cwd && o.cwd) cwd = o.cwd;
    if (!title && o.type === "user" && o.message) {
      const c = o.message.content;
      let t = "";
      if (typeof c === "string") t = c;
      else if (Array.isArray(c)) { const tb = c.find((b: any) => b?.type === "text"); if (tb) t = tb.text || ""; }
      if (t && !t.startsWith("<") && !t.includes("tool_result")) title = titleFrom(t);
    }
    if (cwd && title) break;
  }
  return { cwd, title };
}

function dirToCwd(dir: string): string {
  return dir.replace(/^-/, "/").replace(/-/g, "/");
}

// Scan ~/.claude/projects and list UUID-named sessions (excluding agent-*/journal) that aren't already in the list, sorted by most recently active.
export function listImportable(excludeIds: Set<string>): ImportableItem[] {
  if (!existsSync(ROOT)) return [];
  const out: ImportableItem[] = [];
  let dirs: string[] = [];
  try { dirs = readdirSync(ROOT); } catch { return []; }
  for (const d of dirs) {
    let files: string[] = [];
    try { files = readdirSync(join(ROOT, d)); } catch { continue; }
    for (const fn of files) {
      if (!fn.endsWith(".jsonl")) continue;
      const id = fn.slice(0, -6);
      if (!UUID_RE.test(id)) continue; // skip non-top-level sessions like agent-*/journal
      if (excludeIds.has(id)) continue; // skip duplicates that are already in the list
      const file = join(ROOT, d, fn);
      let mtime = 0;
      try { mtime = Math.floor(statSync(file).mtimeMs); } catch { continue; }
      const { cwd, title } = parseHead(readHead(file));
      if (!title) continue; // skip sessions without a readable first message
      out.push({ claudeSessionId: id, cwd: cwd || dirToCwd(d), title, updatedAt: mtime });
    }
  }
  return out.sort((a, b) => b.updatedAt - a.updatedAt).slice(0, 60);
}

function locate(id: string, cwdHint?: string): string | null {
  if (cwdHint) {
    const p = join(ROOT, cwdHint.replace(/\//g, "-"), `${id}.jsonl`);
    if (existsSync(p)) return p;
  }
  try {
    for (const d of readdirSync(ROOT)) {
      const p = join(ROOT, d, `${id}.jsonl`);
      if (existsSync(p)) return p;
    }
  } catch {}
  return null;
}

// Build a single SessionSummary from a full transcript (used for the actual import).
export function buildSummary(claudeSessionId: string, cwdHint?: string): SessionSummary | null {
  const file = locate(claudeSessionId, cwdHint);
  if (!file) return null;
  let cwd = cwdHint || "";
  let model = "claude";
  let firstUser = "";
  let lastText = "";
  let lastRole: "user" | "assistant" = "assistant";
  let lastTs = 0;
  const usage = { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheCreationTokens: 0, costUsd: 0 };
  const acc = { current: 0, peak: 0 }; // the true "current context" (accounting for /compact drops), not a running sum of every input
  let raw = "";
  try { raw = readFileSync(file, "utf8"); } catch { return null; }
  for (const ln of raw.split("\n")) {
    if (!ln.trim()) continue;
    let o: any;
    try { o = JSON.parse(ln); } catch { continue; }
    accumContext(o, acc);
    if (o.cwd) cwd = o.cwd;
    if (o.message?.model) model = o.message.model;
    if (o.message?.usage) {
      const u = o.message.usage;
      usage.inputTokens += u.input_tokens || 0;
      usage.outputTokens += u.output_tokens || 0;
      usage.cacheReadTokens += u.cache_read_input_tokens || 0;
      usage.cacheCreationTokens += u.cache_creation_input_tokens || 0;
    }
    if ((o.type === "user" || o.type === "assistant") && o.message) {
      const c = o.message.content;
      let t = "";
      if (typeof c === "string") t = c;
      else if (Array.isArray(c)) { const tb = c.find((b: any) => b?.type === "text"); if (tb) t = tb.text || ""; }
      if (t && !t.startsWith("<") && !t.includes("tool_result")) {
        if (o.type === "user" && !firstUser) firstUser = t;
        lastText = t;
        lastRole = o.type === "assistant" ? "assistant" : "user";
      }
    }
    if (o.timestamp) lastTs = Date.parse(o.timestamp) || lastTs;
  }
  usage.costUsd = +(((usage.inputTokens + usage.cacheCreationTokens) / 1e6) * 15 + (usage.outputTokens / 1e6) * 75 + (usage.cacheReadTokens / 1e6) * 1.5).toFixed(2);
  return {
    id: claudeSessionId,
    claudeSessionId,
    source: "app",
    title: titleFrom(firstUser) || "(imported session)",
    cwd: cwd || "/Users/qili",
    model,
    status: "idle",
    usage,
    contextTokens: acc.current,
    contextLimit: contextLimitFromPeak(acc.peak),
    toolCounts: {},
    permissionMode: "bypassPermissions",
    preview: lastText.replace(/\s+/g, " ").trim().slice(0, 200),
    previewRole: lastRole,
    archived: false,
    updatedAt: lastTs || Date.now(),
  };
}
