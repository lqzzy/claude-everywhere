// Import an existing claude session (a transcript under ~/.claude/projects) into the new App's session list.
// Usage: npx tsx import-session.ts <claudeSessionId> [cwd]
//   - Parses the transcript to generate card metadata and writes it into ~/.claude-remote/sessions.json
//   - After that, start the server and open the App to see it; tap to load the full history on demand, and sending a message continues the same session via resume (no forking)
import { readFileSync, readdirSync, existsSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";

const id = process.argv[2];
const cwdArg = process.argv[3];
if (!id) {
  console.error("Usage: npx tsx import-session.ts <claudeSessionId> [cwd]");
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
  console.error(`Could not find transcript for session ${id} (no such jsonl under ~/.claude/projects)`);
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
// Rough cost (Opus-scale rates, for display only)
usage.costUsd = +(((usage.inputTokens + usage.cacheCreationTokens) / 1e6) * 15 + (usage.outputTokens / 1e6) * 75 + (usage.cacheReadTokens / 1e6) * 1.5).toFixed(2);

// Title: if the first message is a /goal Stop-hook wrapper, extract the goal from inside the quotes; otherwise use the first user text
let title = firstUser;
const m = firstUser.match(/condition:\s*"([^"]+)"/);
if (m) title = m[1];
title = title.replace(/\s+/g, " ").trim().slice(0, 40) || "(imported session)";

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

console.log("✓ Imported into the session list:");
console.log("  Title  :", title);
console.log("  id     :", id);
console.log("  cwd    :", summary.cwd);
console.log("  Preview:", summary.preview.slice(0, 50));
console.log("  Total  :", `${usage.inputTokens + usage.outputTokens} tok · $${usage.costUsd}`);
console.log("Now start the server (npm start) and open the App to see it and continue the chat.");
