// Smoke test: connect to the server → start a session → observe streaming/activity/token/completion events to verify the whole data flow.
import "dotenv/config";
import { WebSocket } from "ws";

const PORT = process.env.PORT || 4000;
const TOKEN = process.env.AUTH_TOKEN || "";
const url = `ws://127.0.0.1:${PORT}${TOKEN ? `?token=${TOKEN}` : ""}`;

const ws = new WebSocket(url);
let started = false;
let lastStatus = "";
let streamed = 0;

ws.on("open", () => console.log("[smoke] connected", url));
ws.on("error", (e) => console.error("[smoke] ws error", e));

ws.on("message", (data) => {
  const e = JSON.parse(data.toString());
  switch (e.t) {
    case "session.list":
      console.log(`[smoke] session.list: ${e.sessions.length} sessions`);
      if (!started) {
        started = true;
        console.log("[smoke] Starting a test session…");
        ws.send(
          JSON.stringify({
            t: "session.start",
            prompt: "Write a roughly 400-word, vivid description of a desert sunset, in three paragraphs. Start writing directly, don't use any tools.",
          })
        );
      }
      break;
    case "session.created":
      console.log(`[smoke] session.created id=${e.session.id.slice(0, 8)} status=${e.session.status}`);
      break;
    case "message.delta":
      streamed += e.text.length;
      break;
    case "message.complete":
      console.log(`\n[smoke] message.complete role=${e.message.role} blocks=${e.message.blocks.length}`);
      break;
    case "activity":
      console.log(`[smoke] activity: ${e.tool ?? "(none)"}`);
      break;
    case "usage":
      console.log(`[smoke] usage (cumulative): ${JSON.stringify(e.usage)}`);
      break;
    case "turn": {
      const el = ((Date.now() - e.turn.sentAt) / 1000).toFixed(1);
      console.log(
        `[smoke] ⏱ turn ${e.turn.phase} | elapsed ${el}s | this turn in=${e.turn.inputTokens} out=${e.turn.outputTokens}`
      );
      break;
    }
    case "session.updated":
      if (e.session.status !== lastStatus) {
        lastStatus = e.session.status;
        console.log(`[smoke] status → ${lastStatus}`);
      }
      break;
    case "permission.request":
      console.log(`[smoke] Got permission request tool=${e.tool} → auto-approving`);
      ws.send(JSON.stringify({ t: "permission.respond", requestId: e.requestId, allow: true }));
      break;
    case "error":
      console.error("[smoke] error:", e.message);
      break;
  }
});

setTimeout(() => {
  console.log(`\n[smoke] ===== Done. Received ${streamed} streamed chars =====`);
  ws.close();
  process.exit(0);
}, 60000);
