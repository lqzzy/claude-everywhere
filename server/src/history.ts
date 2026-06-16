// 按需读取单个 claude 会话 transcript:~/.claude/projects/<编码cwd>/<sessionId>.jsonl。
// 手机打开某会话时调用,拿到电脑端 `claude --resume` 后的最新全量消息。
// 全程对 IO 兜底,任何失败都返回 [],绝不抛异常。
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import type { ContentBlock, Message } from "./protocol";

const ROOT = join(homedir(), ".claude", "projects");

// transcript 里一行 message.content 的块 → 我们的 ContentBlock
// (从 transcriptMirror.ts 原样复制,该文件即将删除,故不 import)
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

// jsonl 一行对象 → Message(从 transcriptMirror.ts 原样复制)
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

// 定位会话文件:首选按 cwd 编码拼路径;不存在则遍历所有项目目录兜底找同名 jsonl。
// 导出供 server.ts 的 fs.watch 实时监听用。
export function locateFile(claudeSessionId: string, cwd: string): string | null {
  const fileName = claudeSessionId + ".jsonl";
  try {
    const preferred = join(ROOT, cwd.replace(/\//g, "-"), fileName);
    if (existsSync(preferred)) return preferred;
  } catch {}
  // 兜底:遍历 ~/.claude/projects/ 下所有子目录,找同名文件
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

// 系统机器消息(命令包装 / caveat / system-reminder / 续接摘要前言等),不是真实对话,渲染时过滤。
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

// 从一行已解析的 jsonl 对象,累计"当前上下文 / 历史峰值"。
//   当前上下文 current = 最近一次 assistant 回合的 input+cache 总量;
//                        或最近一次 /compact 的 postTokens(谁在文件里更靠后取谁)。
//   → 这才是真相:/compact 后会回落到 postTokens,后续回合再据实增长。
//   峰值 peak 用于推断窗口大小(见 contextLimitFromPeak)。
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

// 由历史峰值推断上下文窗口:曾占用 >200k ⇒ 必是 1M 窗口(Claude 会在溢出前自动压缩,
// 且没有 500k 档),否则按默认 200k。窗口真值在 transcript 里没有结构化字段,只能这样推断;
// app 端真正跑一轮时 handleResult 会用 modelUsage.contextWindow 精确校正。
export function contextLimitFromPeak(peak: number): number {
  return peak > 200000 ? 1000000 : 200000;
}

export interface HistoryResult {
  messages: Message[];
  contextTokens: number; // 当前真实上下文占用(读自磁盘,含 /compact 后回落 + 终端续聊)
  contextLimit: number; //  推断的上下文窗口(0=未找到 transcript,调用方应跳过更新)
}

// 读取某会话的最新全量消息 + 当前上下文占用。文件找不到/读失败一律返回空结果(contextLimit=0)。
// 处理 /compact:压缩边界 → 合成"系统分隔"消息;续接摘要/命令包装等机器消息 → 过滤。
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
      try { o = JSON.parse(ln); } catch { continue; } // 坏行跳过

      accumContext(o, acc); // 顺带累计上下文占用(同一遍扫描,零额外 IO)

      // /compact 边界 → 一条居中分隔提示
      if (o.type === "system" && o.subtype === "compact_boundary") {
        messages.push({
          id: o.uuid || `compact-${messages.length}`,
          role: "system",
          blocks: [{ type: "text", text: "上下文已压缩 · 早前对话已自动总结" }],
          ts: o.timestamp ? Date.parse(o.timestamp) : Date.now(),
        });
        continue;
      }
      // 巨大的"续接摘要"是机器生成的,不展示
      if (o.isCompactSummary) continue;

      const m = lineToMessage(o);
      if (!m) continue;
      // 单一文本块且内容是命令包装/caveat/system-reminder 等 → 过滤
      const firstText = m.blocks.find((b) => b.type === "text")?.text ?? "";
      if (m.blocks.length === 1 && m.blocks[0].type === "text" && isNoiseText(firstText)) continue;
      // 压缩后机器应答的占位
      if (m.role === "assistant" && m.blocks.length === 1 && firstText.trim() === "No response requested.") continue;

      messages.push(m);
    }
    return { messages, contextTokens: acc.current, contextLimit: contextLimitFromPeak(acc.peak) };
  } catch {
    return empty;
  }
}
