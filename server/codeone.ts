import "dotenv/config";
import { WebSocket } from "ws";
const ws = new WebSocket(`ws://127.0.0.1:4000?token=${process.env.AUTH_TOKEN}`);
ws.on("open", () => {
  ws.send(
    JSON.stringify({
      t: "session.start",
      cwd: "/Users/qili/remote-claude",
      prompt:
        '只用一个 TypeScript 代码块回复(不要任何额外文字):export async function login(u, p) 里 const r = await api("/login", { u, p }); if (!r.ok) throw new Error(r.msg); return r.token;',
    })
  );
  console.log("started code session");
});
setTimeout(() => process.exit(0), 3000);
