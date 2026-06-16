// 造几个不同状态/目录的会话,用于对照 sessions.html 设计稿。
import "dotenv/config";
import { WebSocket } from "ws";
const TOKEN = process.env.AUTH_TOKEN || "";
const ws = new WebSocket(`ws://127.0.0.1:4000?token=${TOKEN}`);
const starts = [
  { cwd: "/Users/qili/remote-claude", prompt: "只回复一句话:崩溃来自 null 解引用,已加空值守卫并补了回归测试。不要使用任何工具。" },
  { cwd: "/Users/qili/remote-claude/server", prompt: "只回复一句话:给 ServerEvent 加了 turn 心跳事件,App 据此判断没卡死。不要使用任何工具。" },
  { cwd: "/Users/qili/remote-claude/app", prompt: "只回复一句话:把这周的提交整理成三段周报草稿。不要使用任何工具。" },
  { cwd: "/Users/qili/remote-claude", prompt: "请用 Write 工具在 /tmp/seed_perm.txt 写入 hello(必须真的调用 Write 工具)" },
];
ws.on("open", () => {
  for (const s of starts) ws.send(JSON.stringify({ t: "session.start", cwd: s.cwd, prompt: s.prompt }));
  console.log("seeded", starts.length, "sessions");
});
ws.on("message", (d) => {
  const e = JSON.parse(d.toString());
  if (e.t === "permission.request") console.log("permission pending (留作 danger 卡片):", e.tool);
});
setTimeout(() => process.exit(0), 20000);
