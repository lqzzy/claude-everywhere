import "dotenv/config";
import { WebSocket } from "ws";
const ws = new WebSocket(`ws://127.0.0.1:4000?token=${process.env.AUTH_TOKEN}`);
let did = false;
ws.on("message", (d) => {
  const e = JSON.parse(d.toString());
  if (e.t === "session.list" && !did) {
    did = true;
    const s = e.sessions.find((x: any) => /c\+\+|hello world|TypeScript|login/i.test(x.title + x.preview)) || e.sessions[0];
    console.log("session:", s.id.slice(0, 8), "|", s.title.slice(0, 36), "| status:", s.status);
    ws.send(JSON.stringify({ t: "session.subscribe", id: s.id }));
  }
  if (e.t === "session.history") {
    console.log("=== messages:", e.messages.length, "===");
    for (const m of e.messages) {
      const desc = m.blocks
        .map((b: any) =>
          b.type === "text" ? `text("${(b.text || "").replace(/\n/g, " ").slice(0, 50)}")` :
          b.type === "thinking" ? `thinking(${(b.text || "").length}c)` :
          b.type === "tool_use" ? `tool_use:${b.name}` :
          b.type === "tool_result" ? "tool_result" : b.type
        )
        .join(", ");
      console.log(`- ${m.role}: ${desc}`);
    }
    process.exit(0);
  }
});
setTimeout(() => process.exit(0), 5000);
