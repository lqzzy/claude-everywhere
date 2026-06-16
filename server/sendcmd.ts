// Test helper: send one command to the most recent session (over WS, same path as the App), to trigger the tool/permission flow.
import "dotenv/config";
import { WebSocket } from "ws";

const TOKEN = process.env.AUTH_TOKEN || "";
const text = process.argv[2] || "Run this and show exact output: uname -a";
const ws = new WebSocket(`ws://127.0.0.1:4000?token=${TOKEN}`);

ws.on("open", () => console.log("connected"));
ws.on("message", (d) => {
  const e = JSON.parse(d.toString());
  if (e.t === "session.list") {
    if (!e.sessions.length) {
      console.log("No sessions");
      process.exit(1);
    }
    const id = e.sessions[0].id;
    console.log("Sending to session", id.slice(0, 8), "->", text);
    ws.send(JSON.stringify({ t: "session.input", id, text }));
    setTimeout(() => process.exit(0), 1500);
  }
  if (e.t === "permission.request") console.log("PERMISSION:", e.tool, JSON.stringify(e.input));
});
