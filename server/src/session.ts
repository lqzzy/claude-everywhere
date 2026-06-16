// 重构后的会话层:server 无状态,每条消息 = 一次性 query() 跑完即退;
// 会话元数据指针落地 ~/.claude-remote/sessions.json,真相留在磁盘 jsonl。
import { query } from "@anthropic-ai/claude-agent-sdk";
import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { ContentBlock, Message, ServerEvent, SessionSummary, TurnInfo, UsageInfo } from "./protocol";

export function emptyUsage(): UsageInfo {
  return { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheCreationTokens: 0, costUsd: 0 };
}

const STORE_DIR = join(homedir(), ".claude-remote");
const STORE_FILE = join(STORE_DIR, "sessions.json");

// appId → SessionSummary 指针表,落地 JSON。server 重启不丢。
export class SessionStore {
  private sessions = new Map<string, SessionSummary>();

  constructor(private defaults: { contextLimit: number }) {}

  // 从磁盘读入内存;文件不存在则空。载入后清理瞬时态(没有 live 轮次在跑)。
  load(): void {
    try {
      if (!existsSync(STORE_FILE)) return;
      const raw = readFileSync(STORE_FILE, "utf8");
      const arr = JSON.parse(raw) as SessionSummary[];
      this.sessions.clear();
      for (const s of arr) {
        s.status = "idle";
        s.currentActivity = undefined;
        this.sessions.set(s.id, s);
      }
    } catch {
      // 坏文件:保持空,不让进程崩
    }
  }

  private persist(): void {
    try {
      if (!existsSync(STORE_DIR)) mkdirSync(STORE_DIR, { recursive: true });
      writeFileSync(STORE_FILE, JSON.stringify([...this.sessions.values()], null, 2), "utf8");
    } catch {
      // 写失败不让进程崩
    }
  }

  get(id: string): SessionSummary | undefined {
    return this.sessions.get(id);
  }

  create(init: { id: string; cwd: string; model: string; title: string }): SessionSummary {
    const s: SessionSummary = {
      id: init.id,
      claudeSessionId: undefined,
      source: "app",
      title: init.title,
      cwd: init.cwd,
      model: init.model,
      status: "idle",
      currentActivity: undefined,
      usage: emptyUsage(),
      contextTokens: 0,
      contextLimit: this.defaults.contextLimit,
      toolCounts: {},
      permissionMode: "bypassPermissions",
      preview: "",
      previewRole: "assistant",
      archived: false,
      updatedAt: Date.now(),
    };
    this.sessions.set(s.id, s);
    this.persist();
    return s;
  }

  update(id: string, patch: Partial<SessionSummary>): SessionSummary | undefined {
    const cur = this.sessions.get(id);
    if (!cur) return undefined;
    const merged: SessionSummary = { ...cur, ...patch };
    if (patch.updatedAt === undefined) merged.updatedAt = Date.now();
    this.sessions.set(id, merged);
    this.persist();
    return merged;
  }

  delete(id: string): void {
    this.sessions.delete(id);
    this.persist();
  }

  // 直接放入一条完整 summary(导入历史会话用)
  put(summary: SessionSummary): SessionSummary {
    this.sessions.set(summary.id, summary);
    this.persist();
    return summary;
  }

  list(): SessionSummary[] {
    return [...this.sessions.values()].sort((a, b) => b.updatedAt - a.updatedAt);
  }
}

// 跨语言粗略 token 估计:CJK ~0.6 token/字,其他(英文/代码)~0.25 token/字。
// 流式期间没有权威 token,用它做平滑心跳;末尾 message_delta 的真实 output_tokens 会取 max 校正。
function estTokens(s: string): number {
  let cjk = 0;
  let other = 0;
  for (const ch of s) {
    const c = ch.codePointAt(0) ?? 0;
    if ((c >= 0x3040 && c <= 0x9fff) || (c >= 0xac00 && c <= 0xd7af) || (c >= 0xf900 && c <= 0xfaff)) cjk++;
    else other++;
  }
  return cjk * 0.6 + other * 0.25;
}

function toBlock(b: any): ContentBlock {
  switch (b?.type) {
    case "text":
      return { type: "text", text: b.text };
    case "thinking":
      return { type: "thinking", text: b.thinking };
    case "tool_use":
      return { type: "tool_use", id: b.id, name: b.name, input: b.input };
    case "tool_result":
      return { type: "tool_result", toolUseId: b.tool_use_id, content: b.content, isError: b.is_error };
    default:
      return { type: "text", text: typeof b === "string" ? b : JSON.stringify(b) };
  }
}

// 跑一轮对话:一次性 query(),把 SDK 事件流映射成线协议,跑完即退。
export async function runTurn(opts: {
  store: SessionStore;
  activeTurns: Map<string, { interrupt: () => void }>; // appId → 当前轮句柄,供 interrupt
  id: string;
  text: string;
  emit: (e: ServerEvent) => void;
}): Promise<void> {
  const { store, activeTurns, id, text, emit } = opts;

  const meta = store.get(id);
  if (!meta) return;

  // 1) 构造并发出用户消息
  const userMessage: Message = {
    id: randomUUID(),
    role: "user",
    blocks: [{ type: "text", text }],
    ts: Date.now(),
  };
  emit({ t: "message.complete", id, message: userMessage });
  store.update(id, { preview: text, previewRole: "user" });

  // 2) 开新一轮(闭包局部状态,代替旧版的 this.xxx)
  let currentTurn: TurnInfo = { phase: "sent", sentAt: Date.now(), inputTokens: 0, outputTokens: 0 };
  let currentMessageId = "";
  let liveTokEst = 0; //   流式累计 token 估计(平滑心跳)
  let lastTurnEmit = 0; // turn 节流时间戳

  emit({ t: "turn", id, turn: { ...currentTurn } });
  store.update(id, { status: "thinking" });
  emit({ t: "session.updated", session: store.get(id)! });

  const emitTurn = (phase: TurnInfo["phase"], force = false) => {
    currentTurn.phase = phase;
    const now = Date.now();
    if (!force && phase === "generating" && now - lastTurnEmit < 150) return; // 最快 ~150ms 一次
    lastTurnEmit = now;
    emit({ t: "turn", id, turn: { ...currentTurn } });
  };

  // 3) 组装 options(复用旧写法,补 allowDangerouslySkipPermissions + resume/sessionId 分流)
  const options: any = {
    cwd: meta.cwd,
    includePartialMessages: true,
    permissionMode: "bypassPermissions",
    allowDangerouslySkipPermissions: true, // bypassPermissions 必须配它,否则 SDK 报错
    canUseTool: (toolName: string, input: any) => {
      store.update(id, { currentActivity: { tool: toolName, input }, status: "tool" });
      emit({ t: "session.updated", session: store.get(id)! });
      return Promise.resolve({ behavior: "allow", updatedInput: input });
    },
    stderr: () => {},
  };
  if (meta.model && meta.model !== "default") options.model = meta.model;
  if (meta.claudeSessionId) options.resume = meta.claudeSessionId; // 续聊:从最新磁盘状态 resume
  else options.sessionId = id; //                                   首轮:用 appId 作为 claude 会话 id

  const handleStream = (event: any) => {
    if (!event) return;
    if (event.type === "message_start") {
      currentMessageId = randomUUID();
      // message_start 立刻带来本轮 input(含 cache)用量 → 上下文占用 + 心跳起点
      const u = event.message?.usage;
      if (u) {
        currentTurn.inputTokens =
          (u.input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0);
        store.update(id, { contextTokens: currentTurn.inputTokens });
        emitTurn("generating");
      }
      store.update(id, { status: "thinking" });
    } else if (event.type === "message_delta") {
      // 权威累计 output_tokens;取 max 不让数字回退
      const u = event.usage;
      if (u && typeof u.output_tokens === "number") {
        currentTurn.outputTokens = Math.max(currentTurn.outputTokens, u.output_tokens);
        emitTurn("generating");
      }
    } else if (event.type === "content_block_delta") {
      const d = event.delta;
      // 每个增量都让 outputTokens 按字符上涨 → 平滑心跳(只有真在产出才会涨)
      if (d?.type === "text_delta" || d?.type === "thinking_delta") {
        liveTokEst += estTokens(d.text ?? d.thinking ?? "");
        currentTurn.outputTokens = Math.max(currentTurn.outputTokens, Math.round(liveTokEst));
        emitTurn("generating");
      }
      if (d?.type === "text_delta") {
        emit({ t: "message.delta", id, messageId: currentMessageId || "live", text: d.text });
      }
    }
  };

  const handleAssistant = (message: any) => {
    const blocks: ContentBlock[] = (message?.content ?? []).map(toBlock);
    const m: Message = {
      id: message?.id || currentMessageId || randomUUID(),
      role: "assistant",
      blocks,
      ts: Date.now(),
    };

    const patch: Partial<SessionSummary> = {};
    const toolCounts = { ...meta.toolCounts, ...(store.get(id)?.toolCounts ?? {}) };
    let toolCountsChanged = false;
    for (const b of blocks) {
      if (b.type === "tool_use" && b.name) {
        toolCounts[b.name] = (toolCounts[b.name] ?? 0) + 1;
        toolCountsChanged = true;
      }
    }
    if (toolCountsChanged) patch.toolCounts = toolCounts;

    const txt = blocks.find((b) => b.type === "text" && b.text)?.text;
    if (txt) {
      patch.preview = txt.trim().replace(/\s+/g, " ").slice(0, 200);
      patch.previewRole = "assistant";
    }

    const toolUse = blocks.find((b) => b.type === "tool_use");
    if (toolUse) {
      patch.currentActivity = { tool: toolUse.name as string, input: toolUse.input };
      patch.status = "tool";
    }

    emit({ t: "message.complete", id, message: m });
    if (toolUse) {
      emit({ t: "activity", id, tool: toolUse.name as string, input: toolUse.input });
    }
    store.update(id, patch);
    emit({ t: "session.updated", session: store.get(id)! });
  };

  const handleUser = (message: any) => {
    const blocks: ContentBlock[] = (message?.content ?? []).map(toBlock);
    if (!blocks.length) return;
    const m: Message = { id: randomUUID(), role: "user", blocks, ts: Date.now() };
    emit({ t: "message.complete", id, message: m });
    // 工具结果回来了 → 当前活动清空
    store.update(id, { currentActivity: undefined });
    emit({ t: "activity", id, tool: null });
  };

  const addUsage = (u: any) => {
    if (!u) return;
    const cur = store.get(id)?.usage ?? meta.usage;
    const usage: UsageInfo = {
      inputTokens: cur.inputTokens + (u.input_tokens ?? 0),
      outputTokens: cur.outputTokens + (u.output_tokens ?? 0),
      cacheReadTokens: cur.cacheReadTokens + (u.cache_read_input_tokens ?? 0),
      cacheCreationTokens: cur.cacheCreationTokens + (u.cache_creation_input_tokens ?? 0),
      costUsd: cur.costUsd,
    };
    store.update(id, { usage });
  };

  const handleResult = (msg: any) => {
    // result 带本轮权威用量(含工具往返),累加为会话累计
    addUsage(msg?.usage);
    // 用真实模型上下文窗口校正 contextLimit(如 Opus 1M),让 Context% 准确
    const mu = msg?.modelUsage;
    if (mu) {
      const cw = (Object.values(mu)[0] as any)?.contextWindow;
      if (typeof cw === "number" && cw > 0) store.update(id, { contextLimit: cw });
    }
    if (typeof msg?.total_cost_usd === "number") {
      const cur = store.get(id)?.usage ?? emptyUsage();
      store.update(id, { usage: { ...cur, costUsd: cur.costUsd + msg.total_cost_usd } });
    }
    emitTurn("done", true);
    store.update(id, { currentActivity: undefined, status: "idle" });
    emit({ t: "usage", id, usage: store.get(id)!.usage });
    emit({ t: "activity", id, tool: null });
    emit({ t: "session.updated", session: store.get(id)! });
  };

  const handle = (msg: any) => {
    switch (msg?.type) {
      case "system":
        if (msg.subtype === "init") {
          // 续聊不分叉的关键:每轮都把 claudeSessionId 指针更新成最新
          store.update(id, {
            claudeSessionId: msg.session_id ?? store.get(id)?.claudeSessionId,
            model: msg.model ?? meta.model,
            cwd: msg.cwd ?? meta.cwd,
          });
          emit({ t: "session.updated", session: store.get(id)! });
        }
        break;
      case "stream_event":
        handleStream(msg.event);
        break;
      case "assistant":
        handleAssistant(msg.message);
        break;
      case "user":
        handleUser(msg.message);
        break;
      case "result":
        handleResult(msg);
        break;
    }
  };

  // 4) 开跑这一轮的一次性 query()
  const q: any = query({ prompt: text, options });
  activeTurns.set(id, q);

  try {
    for await (const msg of q) handle(msg);
  } catch (e) {
    emit({ t: "error", message: `session ${id}: ${String(e)}` });
  } finally {
    activeTurns.delete(id);
    // 兜底:若 status 仍非 idle,复位
    const cur = store.get(id);
    if (cur && cur.status !== "idle") {
      store.update(id, { status: "idle", currentActivity: undefined });
      emit({ t: "session.updated", session: store.get(id)! });
    }
  }
}
