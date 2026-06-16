// 把一个已有的 claude 会话(~/.claude/projects 里的 transcript)导入新 App 的会话列表。
// 用法: npx tsx import-session.ts <claudeSessionId> [cwd]
//   - 解析 transcript 生成卡片元数据,写进 ~/.claude-remote/sessions.json
//   - 之后启动 server、打开 App 即可看到;点开按需读全量历史,发消息走 resume 续同一会话(不分叉)
import { readFileSync, readdirSync, existsSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

const id = process.argv[2];
const cwdArg = process.argv[3];
if (!id) {
  console.error("用法: npx tsx import-session.ts <claudeSessionId> [cwd]");
  process.exit(1);
}

const ROOT = join(homedir(), ".claude", "projects");

function locate(): string | null {
  if (cwdArg) {
    const p = join(ROOT, cwdArg.replace(/\//g, "-"), `${id}.jsonl`);
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

const file = locate();
if (!file) {
  console.error(`找不到会话 ${id} 的 transcript(~/.claude/projects 下没有该 jsonl)`);
  process.exit(1);
}

let cwd = cwdArg || "";
let model = "claude";
let firstUser = "";
let lastText = "";
let lastRole: "user" | "assistant" = "assistant";
let lastTs = 0;
const usage = { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheCreationTokens: 0, costUsd: 0 };

for (const ln of readFileSync(file, "utf8").split("\n")) {
  if (!ln.trim()) continue;
  let o: any;
  try { o = JSON.parse(ln); } catch { continue; }
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
// 粗略成本(Opus 量级,仅作仪表)
usage.costUsd = +(((usage.inputTokens + usage.cacheCreationTokens) / 1e6) * 15 + (usage.outputTokens / 1e6) * 75 + (usage.cacheReadTokens / 1e6) * 1.5).toFixed(2);

// 标题:若首条是 /goal 的 Stop-hook 包装,抽出引号里的目标;否则取首条用户文本
let title = firstUser;
const m = firstUser.match(/condition:\s*"([^"]+)"/);
if (m) title = m[1];
title = title.replace(/\s+/g, " ").trim().slice(0, 40) || "(导入会话)";

const summary = {
  id,
  claudeSessionId: id,
  source: "app",
  title,
  cwd: cwd || "/Users/qili",
  model,
  status: "idle",
  usage,
  contextTokens: usage.inputTokens,
  contextLimit: 200000,
  toolCounts: {},
  permissionMode: "bypassPermissions",
  preview: lastText.replace(/\s+/g, " ").trim().slice(0, 200),
  previewRole: lastRole,
  archived: false,
  updatedAt: lastTs || Date.now(),
};

const DIR = join(homedir(), ".claude-remote");
const STORE = join(DIR, "sessions.json");
if (!existsSync(DIR)) mkdirSync(DIR, { recursive: true });
let arr: any[] = [];
if (existsSync(STORE)) { try { arr = JSON.parse(readFileSync(STORE, "utf8")); } catch {} }
arr = arr.filter((s) => s.id !== id);
arr.push(summary);
writeFileSync(STORE, JSON.stringify(arr, null, 2), "utf8");

console.log("✓ 已导入会话列表:");
console.log("  标题:", title);
console.log("  id  :", id);
console.log("  cwd :", summary.cwd);
console.log("  预览:", summary.preview.slice(0, 50));
console.log("  累计:", `${usage.inputTokens + usage.outputTokens} tok · $${usage.costUsd}`);
console.log("现在启动 server(npm start)、打开 App 即可看到并续聊。");
