import "dotenv/config";
import { WebSocketServer, WebSocket } from "ws";
import { homedir } from "node:os";
import { randomUUID } from "node:crypto";
import { watchFile, unwatchFile, type Stats } from "node:fs";
import { SessionStore, runTurn } from "./session";
import { readHistory, locateFile } from "./history";
import { listImportable, buildSummary } from "./importable";
import { getUsageQuota } from "./usage";
import type { ClientCommand, Message, ServerEvent, SessionSummary } from "./protocol";

// 安全网:SDK 的内部读取循环在一轮 query 被 interrupt / 连接中断时,会在某个微任务里抛出
// "Query closed before response received"。这是分离的(detached)rejection,不会进我们 for-await 的
// try/catch,Node 默认会把它升级为未捕获异常直接退进程 —— 手机端中断一轮对话就把整个 server 搞挂。
// 这里兜底:记一行日志,绝不让 server 因为单轮对话的异步异常而死。
process.on("unhandledRejection", (reason) => {
  console.warn("⚠️  unhandledRejection(已忽略,server 继续运行):", String(reason).split("\n")[0]);
});
process.on("uncaughtException", (err) => {
  console.warn("⚠️  uncaughtException(已忽略,server 继续运行):", String(err).split("\n")[0]);
});

const PORT = Number(process.env.PORT || 4000);
const HOST = process.env.HOST || "0.0.0.0";
const AUTH_TOKEN = process.env.AUTH_TOKEN || "";
const DEFAULT_CWD = process.env.DEFAULT_CWD || homedir();
const DEFAULT_MODEL = process.env.DEFAULT_MODEL || undefined;
const CONTEXT_LIMIT = Number(process.env.CONTEXT_LIMIT || 200000); // Opus 1M 上下文则设 1000000

if (!AUTH_TOKEN) {
  console.warn("⚠️  未设置 AUTH_TOKEN —— 服务不鉴权,任何能连到端口的人都能控制 claude。仅限本机调试!");
}

const store = new SessionStore({ contextLimit: CONTEXT_LIMIT });
store.load(); // 从 ~/.claude-remote/sessions.json 恢复指针表(重启不丢)

const clients = new Set<WebSocket>();
const broadcast = (e: ServerEvent) => {
  const s = JSON.stringify(e);
  for (const c of clients) if (c.readyState === WebSocket.OPEN) c.send(s);
};

// appId → 当前正在进行的那一轮 query 句柄(供 interrupt;轮次结束自动清除)
const activeTurns = new Map<string, { interrupt: () => void }>();
// appId → 排队中的后续消息(生成中又发的消息排队,本轮结束后顺序处理,不丢、不分叉)
const pending = new Map<string, string[]>();
// ws → "停止当前订阅会话文件监听"的函数(每个连接同时只看一个会话)
const subs = new Map<WebSocket, () => void>();

// 跑一轮;结束后若有排队消息,自动接着跑下一条。
function startTurn(id: string, text: string) {
  runTurn({ store, activeTurns, id, text, emit: broadcast })
    .catch((e) => broadcast({ t: "error", message: `session ${id}: ${String(e)}` }))
    .finally(() => {
      const q = pending.get(id);
      if (q && q.length) {
        const next = q.shift()!;
        if (!q.length) pending.delete(id);
        startTurn(id, next);
      }
    });
}

const PAGE = 30;
// 会话历史的真相在磁盘 jsonl:按需读、分页。before 未给=最后一页。
// 顺带回传 contextTokens/contextLimit(读自磁盘的真实上下文占用,supplies 给 subscribe 校正)。
function historyPage(
  id: string,
  before?: number
): { messages: Message[]; from: number; total: number; contextTokens: number; contextLimit: number } {
  const meta = store.get(id);
  const h = meta?.claudeSessionId
    ? readHistory(meta.claudeSessionId, meta.cwd)
    : { messages: [] as Message[], contextTokens: 0, contextLimit: 0 };
  const all = h.messages;
  const total = all.length;
  const end = before == null ? total : Math.max(0, Math.min(before, total));
  const from = Math.max(0, end - PAGE);
  return { messages: all.slice(from, end), from, total, contextTokens: h.contextTokens, contextLimit: h.contextLimit };
}

// 监听某会话的 transcript 文件:文件一变(电脑终端 `claude --resume` 续聊、后台轮次、/compact 等),
// 就把最新一页历史以 replace 推给这个连接 —— 这样手机即使"停在会话页不动"也能近实时看到新消息,
// 不必退出重进。用 watchFile 轮询 mtime(对 append 写最稳,不依赖 inode/rename),~1.5s 延迟可接受。
// 关键:本端正在跑这一轮时(activeTurns 命中)跳过,避免与 runTurn 的实时流式重复/抖动。
function watchSession(id: string, send: (e: ServerEvent) => void): () => void {
  const meta = store.get(id);
  if (!meta?.claudeSessionId) return () => {}; // 还没有 claudeSessionId(新会话首轮前)无文件可监听
  const file = locateFile(meta.claudeSessionId, meta.cwd);
  if (!file) return () => {};
  const listener = (curr: Stats, prev: Stats) => {
    if (curr.mtimeMs === prev.mtimeMs) return; // 无实际写入
    if (activeTurns.has(id)) return; //          本端正实时流,无需文件兜底
    const p = historyPage(id);
    send({ t: "session.history", id, messages: p.messages, from: p.from, total: p.total, mode: "replace" });
  };
  watchFile(file, { interval: 1500 }, listener);
  return () => unwatchFile(file, listener);
}

const wss = new WebSocketServer({ host: HOST, port: PORT });

wss.on("connection", (ws, req) => {
  let authed = false;
  const url = new URL(req.url || "/", "http://localhost");
  if (!AUTH_TOKEN) authed = true; //                                  无 token → 开放(已告警)
  else if (url.searchParams.get("token") === AUTH_TOKEN) authed = true; // 也支持 ?token= 连接

  const send = (e: ServerEvent) => {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(e));
  };

  if (authed) onAuthed();

  ws.on("message", (data) => {
    let cmd: ClientCommand;
    try {
      cmd = JSON.parse(data.toString());
    } catch {
      return;
    }
    if (!authed) {
      if (cmd.t === "auth" && cmd.token === AUTH_TOKEN) {
        authed = true;
        onAuthed();
      } else {
        send({ t: "error", message: "unauthorized" });
        ws.close();
      }
      return;
    }
    handle(cmd, send, ws);
  });

  ws.on("close", () => {
    clients.delete(ws);
    subs.get(ws)?.(); // 停掉该连接的文件监听,防止泄漏
    subs.delete(ws);
  });

  function onAuthed() {
    clients.add(ws);
    send({ t: "session.list", sessions: store.list() });
    getUsageQuota().then((quota) => { if (quota) send({ t: "usage.quota", quota }); }).catch(() => {});
  }
});

function handle(cmd: ClientCommand, send: (e: ServerEvent) => void, ws: WebSocket) {
  switch (cmd.t) {
    case "session.start": {
      const id = randomUUID();
      const session = store.create({
        id,
        cwd: cmd.cwd || DEFAULT_CWD,
        model: cmd.model || DEFAULT_MODEL || "default",
        title: cmd.prompt?.trim().slice(0, 40) || "新会话",
      });
      broadcast({ t: "session.created", session });
      // 有首条消息就立刻跑第一轮(不带 resume);否则只建会话、等后续 input
      if (cmd.prompt?.trim()) startTurn(id, cmd.prompt);
      break;
    }
    case "session.input": {
      if (!store.get(cmd.id)) {
        send({ t: "error", message: "会话不存在(可能已删除)。" });
        break;
      }
      // 生成中再发 → 排队(本轮结束后顺序处理);否则立即开跑
      if (activeTurns.has(cmd.id)) {
        const q = pending.get(cmd.id) ?? [];
        q.push(cmd.text);
        pending.set(cmd.id, q);
      } else {
        startTurn(cmd.id, cmd.text);
      }
      break;
    }
    case "session.interrupt":
      activeTurns.get(cmd.id)?.interrupt();
      break;
    case "session.subscribe": {
      // 读磁盘最新真相(含电脑端 resume 续聊 / 终端 /compact 的内容)→ 回最后一页
      const p = historyPage(cmd.id);
      // 用磁盘真相校正预览 + 上下文占用(关键:/compact 后 contextTokens 会回落,不再卡 100%)
      const patch: Partial<SessionSummary> = {};
      const last = p.messages[p.messages.length - 1];
      if (last) {
        const txt = last.blocks.find((b) => b.type === "text" && b.text)?.text;
        if (txt) {
          patch.preview = txt.replace(/\s+/g, " ").slice(0, 200);
          patch.previewRole = last.role === "user" ? "user" : "assistant";
        }
      }
      if (p.contextLimit > 0) {
        // 仅当确实读到了 transcript 才覆盖(contextLimit=0 表示文件没找到,保留旧值)
        patch.contextTokens = p.contextTokens;
        patch.contextLimit = p.contextLimit;
      }
      if (Object.keys(patch).length) {
        const updated = store.update(cmd.id, patch);
        if (updated) broadcast({ t: "session.updated", session: updated });
      }
      send({ t: "session.history", id: cmd.id, messages: p.messages, from: p.from, total: p.total, mode: "replace" });
      // 订阅 = 持续监听该会话文件,之后别处(电脑终端/后台)写入也实时推过来,手机停在原地也会刷新
      subs.get(ws)?.(); // 先停掉上一个会话的监听
      subs.set(ws, watchSession(cmd.id, send));
      break;
    }
    case "session.more": {
      const p = historyPage(cmd.id, cmd.before);
      send({ t: "session.history", id: cmd.id, messages: p.messages, from: p.from, total: p.total, mode: "prepend" });
      break;
    }
    case "session.delete":
      pending.delete(cmd.id);
      activeTurns.get(cmd.id)?.interrupt();
      store.delete(cmd.id);
      broadcast({ t: "session.removed", id: cmd.id }); // 幂等:即使本地已无,也让客户端清掉陈旧卡片
      break;
    case "session.archive": {
      const updated = store.update(cmd.id, { archived: true });
      if (updated) broadcast({ t: "session.updated", session: updated });
      else broadcast({ t: "session.removed", id: cmd.id }); // 陈旧会话:直接当移除
      break;
    }
    case "usage.get":
      getUsageQuota().then((quota) => { if (quota) send({ t: "usage.quota", quota }); }).catch(() => {});
      break;
    case "session.listImportable": {
      // 排除已在列表里的(按 claudeSessionId / id 双重去重)
      const have = new Set<string>();
      for (const s of store.list()) { have.add(s.id); if (s.claudeSessionId) have.add(s.claudeSessionId); }
      send({ t: "importable.list", items: listImportable(have) });
      break;
    }
    case "session.import": {
      if (store.get(cmd.claudeSessionId)) break; // 已导入,幂等
      const summary = buildSummary(cmd.claudeSessionId, cmd.cwd);
      if (!summary) {
        send({ t: "error", message: "导入失败:找不到该会话的 transcript" });
        break;
      }
      store.put(summary);
      broadcast({ t: "session.created", session: summary }); // 实时推给所有客户端,无需重启
      break;
    }
    case "permission.respond":
      // 默认 bypassPermissions 自动放行,不会产生 permission.request,此命令此版本为 no-op。
      break;
  }
}

console.log(`✅ Claude Remote 中转服务已启动: ws://${HOST}:${PORT}  (默认 cwd=${DEFAULT_CWD})`);
