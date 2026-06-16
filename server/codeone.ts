import "dotenv/config";
import { WebSocket } from "ws";
const ws = new WebSocket(`ws://127.0.0.1:4000?token=${process.env.AUTH_TOKEN}`);
ws.on("open", () => {
  ws.send(
    JSON.stringify({
      t: "session.start",
      cwd: "/Users/qili/remote-claude",
      prompt:
        'Reply with a single TypeScript code block only (no extra text): inside export async function login(u, p), const r = await api("/login", { u, p }); if (!r.ok) throw new Error(r.msg); return r.token;',
    })
  );
  console.log("started code session");
});
setTimeout(() => process.exit(0), 3000);
