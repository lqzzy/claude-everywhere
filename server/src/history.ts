// Read a single claude session transcript on demand: ~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl.
// Called when the phone opens a session, to fetch the latest full set of messages after the desktop's `claude --resume`.
// IO is guarded throughout — any failure returns [], never throws.
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import type { ContentBlock, Message } from "./protocol";

const ROOT = join(homedir(), ".claude", "projects");

// A block from one line's message.content in the transcript → our ContentBlock
// (copied verbatim from transcriptMirror.ts, which is about to be deleted, so it's not imported)
function mapBlock(b: any): ContentBlock | null {
  if (typeof b === "string") return { type: "text", text: b };
  switch (b?.type) {
    case "text": return { type: "text", text: b.text || "" };
    case "thinking": return { type: "thinking", text: b.thinking || b.text || "" };
    case "tool_use": return { type: "tool_use", id: b.id, name: b.name, input: b.input };
    case "tool_result": {
      let content = b.content;
      if (Array.isArray(content)) content = content.map((c: any) => (typeof c === "string" ? c : c?.text ?? "")).join("\n");
      return { type: "tool_result", toolUseId: b.tool_use_id, content, isError: !!b.is_error };
    }
    default: return null;
  }
}

// One jsonl line object → Message (copied verbatim from transcriptMirror.ts)
function lineToMessage(o: any): Message | null {
  if ((o?.type !== "user" && o?.type !== "assistant") || !o.message) return null;
  const c = o.message.content;
  const blocks: ContentBlock[] = [];
  if (typeof c === "string") {
    if (c.trim()) blocks.push({ type: "text", text: c });
  } else if (Array.isArray(c)) {
    for (const b of c) { const mb = mapBlock(b); if (mb) blocks.push(mb); }
  }
  if (blocks.length === 0) return null;
  return {
    id: o.uuid || o.message.id || String(o.timestamp),
    role: o.message.role === "assistant" ? "assistant" : "user",
    blocks,
    ts: o.timestamp ? Date.parse(o.timestamp) : Date.now(),
  };
}

// Locate the session file: first try the path built from the encoded cwd; if it doesn't exist, fall back to scanning every project directory for a jsonl of the same name.
// Exported for use by server.ts's fs.watch live monitoring.
export function locateFile(claudeSessionId: string, cwd: string): string | null {
  const fileName = claudeSessionId + ".jsonl";
  try {
    const preferred = join(ROOT, cwd.replace(/\//g, "-"), fileName);
    if (existsSync(preferred)) return preferred;
  } catch {}
  // Fallback: scan every subdirectory under ~/.claude/projects/ for a file of the same name
  try {
    if (!existsSync(ROOT)) return null;
    const entries = readdirSync(ROOT, { withFileTypes: true });
    for (const e of entries) {
      try {
        const dir = join(ROOT, e.name);
        if (!(e.isDirectory() || statSync(dir).isDirectory())) continue;
        const candidate = join(dir, fileName);
        if (existsSync(candidate)) return candidate;
      } catch {}
    }
  } catch {}
  return null;
}

// System/machine messages (command wrappers / caveats / system-reminders / continuation-summary preambles, etc.) — not real conversation, filtered out during rendering.
const NOISE_PREFIXES = [
  "<local-command-caveat>",
  "<command-name>",
  "<command-message>",
  "<command-args>",
  "<command-stdout>",
  "<command-stderr>",
  "<system-reminder>",
  "<bash-input>",
  "<bash-stdout>",
  "<bash-stderr>",
  "<user-prompt-submit-hook>",
  "Caveat: The messages below",
  "This session is being continued from a previous conversation",
];
function isNoiseText(t: string): boolean {
  const s = t.trimStart();
  return NOISE_PREFIXES.some((p) => s.startsWith(p));
}

// From one parsed jsonl object, accumulate "current context / historical peak".
//   current context `current` = the input+cache total of the most recent assistant turn;
//                        or the postTokens of the most recent /compact (whichever appears later in the file wins).
//   → This is the truth: after /compact it drops back to postTokens, then grows again per actual usage in subsequent turns.
//   the peak `peak` is used to infer the window size (see contextLimitFromPeak).
export function accumContext(o: any, acc: { current: number; peak: number }): void {
  if (o?.type === "system" && o.subtype === "compact_boundary") {
    const pm = o.compactMetadata;
    if (typeof pm?.postTokens === "number") acc.current = pm.postTokens;
    if (typeof pm?.preTokens === "number") acc.peak = Math.max(acc.peak, pm.preTokens);
  } else if (o?.type === "assistant" && o.message?.usage) {
    const u = o.message.usage;
    const tot = (u.input_tokens || 0) + (u.cache_read_input_tokens || 0) + (u.cache_creation_input_tokens || 0);
    if (tot > 0) {
      acc.current = tot;
      acc.peak = Math.max(acc.peak, tot);
    }
  }
}

// Infer the context window from the historical peak: if it ever exceeded 200k ⇒ it must be a 1M window (Claude
// auto-compacts before overflow, and there's no 500k tier), otherwise default to 200k. The true window size has no
// structured field in the transcript, so this is the only way to infer it; when the app actually runs a turn,
// handleResult corrects it precisely using modelUsage.contextWindow.
export function contextLimitFromPeak(peak: number): number {
  return peak > 200000 ? 1000000 : 200000;
}

export interface HistoryResult {
  messages: Message[];
  contextTokens: number; // current real context usage (read from disk, including the post-/compact drop + terminal continuations)
  contextLimit: number; //  inferred context window (0 = transcript not found, the caller should skip the update)
}

// Read a session's latest full set of messages + current context usage. If the file is missing or the read fails, always return an empty result (contextLimit=0).
// Handles /compact: a compaction boundary → synthesize a "system divider" message; continuation summaries / command wrappers and other machine messages → filtered out.
export function readHistory(claudeSessionId: string, cwd: string): HistoryResult {
  const empty: HistoryResult = { messages: [], contextTokens: 0, contextLimit: 0 };
  try {
    const file = locateFile(claudeSessionId, cwd);
    if (!file) return empty;
    const raw = readFileSync(file, "utf8");
    const messages: Message[] = [];
    const acc = { current: 0, peak: 0 };
    for (const ln of raw.split("\n")) {
      if (!ln.trim()) continue;
      let o: any;
      try { o = JSON.parse(ln); } catch { continue; } // skip bad lines

      accumContext(o, acc); // also accumulate context usage along the way (same pass, zero extra IO)

      // /compact boundary → one centered divider notice
      if (o.type === "system" && o.subtype === "compact_boundary") {
        messages.push({
          id: o.uuid || `compact-${messages.length}`,
          role: "system",
          blocks: [{ type: "text", text: "Context compacted · earlier conversation auto-summarized" }],
          ts: o.timestamp ? Date.parse(o.timestamp) : Date.now(),
        });
        continue;
      }
      // The huge "continuation summary" is machine-generated, don't display it
      if (o.isCompactSummary) continue;

      const m = lineToMessage(o);
      if (!m) continue;
      // A single text block whose content is a command wrapper/caveat/system-reminder, etc. → filter out
      const firstText = m.blocks.find((b) => b.type === "text")?.text ?? "";
      if (m.blocks.length === 1 && m.blocks[0].type === "text" && isNoiseText(firstText)) continue;
      // The placeholder for a machine reply after compaction
      if (m.role === "assistant" && m.blocks.length === 1 && firstText.trim() === "No response requested.") continue;

      messages.push(m);
    }
    return { messages, contextTokens: acc.current, contextLimit: contextLimitFromPeak(acc.peak) };
  } catch {
    return empty;
  }
}
