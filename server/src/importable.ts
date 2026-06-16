// 列出磁盘上可导入的历史会话 + 从 transcript 构建完整 SessionSummary(供导入进 store)。
import { readFileSync, readdirSync, existsSync, statSync, openSync, readSync, closeSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import type { ImportableItem, SessionSummary } from "./protocol";
import { accumContext, contextLimitFromPeak } from "./history";

const ROOT = join(homedir(), ".claude", "projects");
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// 读文件前 N 字节(避免为了首条标题把整份大 transcript 读进内存)
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

// /goal 的 Stop-hook 首条会包一层,抽出引号里的真实目标作标题
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

// 扫 ~/.claude/projects,列出 UUID 命名(排除 agent-*/journal)、未在列表中的会话,按最近活跃排序。
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
      if (!UUID_RE.test(id)) continue; // 跳过 agent-*/journal 等非顶层会话
      if (excludeIds.has(id)) continue; // 已在列表里的不重复
      const file = join(ROOT, d, fn);
      let mtime = 0;
      try { mtime = Math.floor(statSync(file).mtimeMs); } catch { continue; }
      const { cwd, title } = parseHead(readHead(file));
      if (!title) continue; // 无可读首条的跳过
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

// 从完整 transcript 构建一条 SessionSummary(真正导入用)。
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
  const acc = { current: 0, peak: 0 }; // 真实"当前上下文"(含 /compact 回落),而非历次 input 累加
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
    title: titleFrom(firstUser) || "(导入会话)",
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
