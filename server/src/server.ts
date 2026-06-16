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

// Safety net: when a query turn is interrupted / the connection drops, the SDK's internal read
// loop throws "Query closed before response received" inside some microtask. This is a detached
// rejection — it never reaches our for-await try/catch, and by default Node escalates it to an
// uncaught exception and kills the process outright, so interrupting a single turn from the phone
// would take down the whole server.
// The fallback here: log a line and never let the server die over an async error from one turn.
process.on("unhandledRejection", (reason) => {
  console.warn("⚠️  unhandledRejection (ignored, server keeps running):", String(reason).split("\n")[0]);
});
process.on("uncaughtException", (err) => {
  console.warn("⚠️  uncaughtException (ignored, server keeps running):", String(err).split("\n")[0]);
});

const PORT = Number(process.env.PORT || 4000);
const HOST = process.env.HOST || "0.0.0.0";
const AUTH_TOKEN = process.env.AUTH_TOKEN || "";
const DEFAULT_CWD = process.env.DEFAULT_CWD || homedir();
const DEFAULT_MODEL = process.env.DEFAULT_MODEL || undefined;
const CONTEXT_LIMIT = Number(process.env.CONTEXT_LIMIT || 200000); // set to 1000000 for Opus 1M context

if (!AUTH_TOKEN) {
  console.warn("⚠️  AUTH_TOKEN is not set — the service runs without authentication, so anyone who can reach the port can control claude. Local debugging only!");
}

const store = new SessionStore({ contextLimit: CONTEXT_LIMIT });
store.load(); // restore the pointer table from ~/.claude-remote/sessions.json (survives restarts)

const clients = new Set<WebSocket>();
const broadcast = (e: ServerEvent) => {
  const s = JSON.stringify(e);
  for (const c of clients) if (c.readyState === WebSocket.OPEN) c.send(s);
};

// appId → handle to the query turn currently in flight (for interrupt; cleared automatically when the turn ends)
const activeTurns = new Map<string, { interrupt: () => void }>();
// appId → queued follow-up messages (anything sent while generating is queued and processed in order
// after the current turn ends — nothing is dropped, nothing forks)
const pending = new Map<string, string[]>();
// ws → function to "stop watching the file of the currently subscribed session" (each connection watches only one session at a time)
const subs = new Map<WebSocket, () => void>();

// Run one turn; when it ends, if anything is queued, automatically run the next message.
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
// The source of truth for session history is the on-disk jsonl: read on demand, paginated. No `before` = last page.
// Also returns contextTokens/contextLimit (the real context usage read from disk, supplied to subscribe for correction).
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

// Watch a session's transcript file: whenever it changes (the desktop terminal continuing the chat via
// `claude --resume`, a background turn, /compact, etc.), push the latest page of history to this connection
// as a replace — so even if the phone is "sitting on the session page", it sees new messages in near real time
// without having to leave and re-enter. Uses watchFile to poll mtime (most robust for append writes, doesn't
// depend on inode/rename), with an acceptable ~1.5s latency.
// Key point: skip while this end is running the turn (activeTurns hit), to avoid duplicating/jittering against
// runTurn's live streaming.
function watchSession(id: string, send: (e: ServerEvent) => void): () => void {
  const meta = store.get(id);
  if (!meta?.claudeSessionId) return () => {}; // no claudeSessionId yet (before a new session's first turn) → no file to watch
  const file = locateFile(meta.claudeSessionId, meta.cwd);
  if (!file) return () => {};
  const listener = (curr: Stats, prev: Stats) => {
    if (curr.mtimeMs === prev.mtimeMs) return; // no actual write
    if (activeTurns.has(id)) return; //          this end is streaming live, no file fallback needed
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
  if (!AUTH_TOKEN) authed = true; //                                  no token → open access (already warned)
  else if (url.searchParams.get("token") === AUTH_TOKEN) authed = true; // also support connecting via ?token=

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
    subs.get(ws)?.(); // stop this connection's file watcher to prevent leaks
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
        title: cmd.prompt?.trim().slice(0, 40) || "New session",
      });
      broadcast({ t: "session.created", session });
      // If there's a first message, run the first turn immediately (without resume); otherwise just create the session and wait for later input
      if (cmd.prompt?.trim()) startTurn(id, cmd.prompt);
      break;
    }
    case "session.input": {
      if (!store.get(cmd.id)) {
        send({ t: "error", message: "Session does not exist (it may have been deleted)." });
        break;
      }
      // Sent again while generating → queue it (processed in order after this turn ends); otherwise start running immediately
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
      // Read the latest truth from disk (including content from desktop resume continuations / terminal /compact) → return the last page
      const p = historyPage(cmd.id);
      // Use the disk truth to correct the preview + context usage (key: after /compact, contextTokens drops back down and no longer sticks at 100%)
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
        // Only overwrite when the transcript was actually read (contextLimit=0 means the file wasn't found, keep the old values)
        patch.contextTokens = p.contextTokens;
        patch.contextLimit = p.contextLimit;
      }
      if (Object.keys(patch).length) {
        const updated = store.update(cmd.id, patch);
        if (updated) broadcast({ t: "session.updated", session: updated });
      }
      send({ t: "session.history", id: cmd.id, messages: p.messages, from: p.from, total: p.total, mode: "replace" });
      // Subscribing = keep watching this session's file, so later writes from elsewhere (desktop terminal/background) are pushed in real time and the phone refreshes even while sitting still
      subs.get(ws)?.(); // first stop watching the previous session
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
      broadcast({ t: "session.removed", id: cmd.id }); // idempotent: even if it's already gone locally, tell clients to clear the stale card
      break;
    case "session.archive": {
      const updated = store.update(cmd.id, { archived: true });
      if (updated) broadcast({ t: "session.updated", session: updated });
      else broadcast({ t: "session.removed", id: cmd.id }); // stale session: just treat it as removed
      break;
    }
    case "usage.get":
      getUsageQuota().then((quota) => { if (quota) send({ t: "usage.quota", quota }); }).catch(() => {});
      break;
    case "session.listImportable": {
      // Exclude those already in the list (deduplicate by both claudeSessionId and id)
      const have = new Set<string>();
      for (const s of store.list()) { have.add(s.id); if (s.claudeSessionId) have.add(s.claudeSessionId); }
      send({ t: "importable.list", items: listImportable(have) });
      break;
    }
    case "session.import": {
      if (store.get(cmd.claudeSessionId)) break; // already imported, idempotent
      const summary = buildSummary(cmd.claudeSessionId, cmd.cwd);
      if (!summary) {
        send({ t: "error", message: "Import failed: could not find the transcript for this session" });
        break;
      }
      store.put(summary);
      broadcast({ t: "session.created", session: summary }); // pushed to all clients in real time, no restart needed
      break;
    }
    case "permission.respond":
      // With the default bypassPermissions, everything is auto-allowed, so no permission.request is ever produced; this command is a no-op in this version.
      break;
  }
}

console.log(`✅ Claude Everywhere relay service started: ws://${HOST}:${PORT}  (default cwd=${DEFAULT_CWD})`);
