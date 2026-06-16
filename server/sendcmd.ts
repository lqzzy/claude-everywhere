// 测试辅助:给最近的会话发一条指令(走 WS,和 App 同源),用于触发工具/权限流程。
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
      console.log("没有会话");
      process.exit(1);
    }
    const id = e.sessions[0].id;
    console.log("发到会话", id.slice(0, 8), "->", text);
    ws.send(JSON.stringify({ t: "session.input", id, text }));
    setTimeout(() => process.exit(0), 1500);
  }
  if (e.t === "permission.request") console.log("PERMISSION:", e.tool, JSON.stringify(e.input));
});
