// Verify the refactor: two conversation turns (the second goes through resume) + the persisted jsonl can be resumed from the terminal.
import "dotenv/config";
import { WebSocket } from "ws";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const TOKEN = process.env.AUTH_TOKEN || "";
const ws = new WebSocket(`ws://127.0.0.1:4000?token=${TOKEN}`);

let appId = "";
let claudeId = "";
let cwd = "";
let started = false;
let doneCount = 0;
let stream1 = 0;
let stream2 = 0;

const log = (...a: any[]) => console.log("[verify]", ...a);

ws.on("open", () => log("connected"));
ws.on("error", (e) => { console.error("ws error", e); process.exit(1); });

ws.on("message", (data) => {
  const e = JSON.parse(data.toString());
  switch (e.t) {
    case "session.list":
      if (!started) {
        started = true;
        log("Starting session (turn 1)…");
        ws.send(JSON.stringify({ t: "session.start", prompt: "Answer in one sentence: what is the capital of China? Answer directly, don't use any tools." }));
      }
      break;
    case "session.created":
      appId = e.session.id;
      cwd = e.session.cwd;
      log(`Session created id=${appId.slice(0, 8)} cwd=${cwd}`);
      break;
    case "session.updated":
      if (e.session.claudeSessionId && !claudeId) {
        claudeId = e.session.claudeSessionId;
        log(`Got claudeSessionId=${claudeId.slice(0, 8)} (==appId? ${claudeId === appId})`);
      }
      break;
    case "message.delta":
      if (doneCount === 0) stream1 += e.text.length; else stream2 += e.text.length;
      break;
    case "turn":
      if (e.turn.phase === "done") {
        doneCount++;
        log(`Turn ${doneCount} done (out≈${e.turn.outputTokens} tok)`);
        if (doneCount === 1) {
          log("Sending turn 2 (via resume)…");
          ws.send(JSON.stringify({ t: "session.input", id: appId, text: "Again in one sentence: and what about the capital of the United States? Same as before, don't use tools." }));
        } else if (doneCount === 2) {
          setTimeout(finish, 800); // wait for disk flush
        }
      }
      break;
    case "error":
      console.error("[verify] server error:", e.message);
      break;
  }
});

function finish() {
  log(`Streamed chars: turn1=${stream1} turn2=${stream2}`);
  const file = join(homedir(), ".claude", "projects", cwd.replace(/\//g, "-"), `${claudeId}.jsonl`);
  const ok = existsSync(file);
  log(`Disk transcript: ${file}`);
  log(ok ? "✓ File exists → terminal can continue the chat with `claude --resume " + claudeId.slice(0, 8) + "…`" : "✗ File does not exist!");
  console.log("\n========== Conclusion ==========");
  console.log(`Both turns completed: ${doneCount === 2 ? "✓" : "✗"}`);
  console.log(`Both turns streamed output: ${stream1 > 0 && stream2 > 0 ? "✓" : "✗"}`);
  console.log(`claudeSessionId==appId (first-turn sessionId took effect): ${claudeId === appId ? "✓" : "✗"}`);
  console.log(`Persisted and resumable: ${ok ? "✓" : "✗"}`);
  console.log(`APPID=${appId}`); // for later manual resume verification
  ws.close();
  process.exit(ok && doneCount === 2 && stream1 > 0 && stream2 > 0 ? 0 : 1);
}

setTimeout(() => { console.error("[verify] timeout"); process.exit(1); }, 90000);
