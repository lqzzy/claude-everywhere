// 验证重构:两轮对话(第二轮走 resume)+ 落盘 jsonl 可被终端 resume。
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

ws.on("open", () => log("已连接"));
ws.on("error", (e) => { console.error("ws 错误", e); process.exit(1); });

ws.on("message", (data) => {
  const e = JSON.parse(data.toString());
  switch (e.t) {
    case "session.list":
      if (!started) {
        started = true;
        log("发起会话(第 1 轮)…");
        ws.send(JSON.stringify({ t: "session.start", prompt: "用一句话回答:中国的首都是哪?直接回答,不要用任何工具。" }));
      }
      break;
    case "session.created":
      appId = e.session.id;
      cwd = e.session.cwd;
      log(`会话已建 id=${appId.slice(0, 8)} cwd=${cwd}`);
      break;
    case "session.updated":
      if (e.session.claudeSessionId && !claudeId) {
        claudeId = e.session.claudeSessionId;
        log(`拿到 claudeSessionId=${claudeId.slice(0, 8)} (==appId? ${claudeId === appId})`);
      }
      break;
    case "message.delta":
      if (doneCount === 0) stream1 += e.text.length; else stream2 += e.text.length;
      break;
    case "turn":
      if (e.turn.phase === "done") {
        doneCount++;
        log(`第 ${doneCount} 轮完成 (out≈${e.turn.outputTokens} tok)`);
        if (doneCount === 1) {
          log("发第 2 轮(走 resume)…");
          ws.send(JSON.stringify({ t: "session.input", id: appId, text: "再用一句话回答:那美国的首都呢?同样不要用工具。" }));
        } else if (doneCount === 2) {
          setTimeout(finish, 800); // 等磁盘 flush
        }
      }
      break;
    case "error":
      console.error("[verify] 服务错误:", e.message);
      break;
  }
});

function finish() {
  log(`流式字符: 第1轮=${stream1} 第2轮=${stream2}`);
  const file = join(homedir(), ".claude", "projects", cwd.replace(/\//g, "-"), `${claudeId}.jsonl`);
  const ok = existsSync(file);
  log(`磁盘 transcript: ${file}`);
  log(ok ? "✓ 文件存在 → 终端可 `claude --resume " + claudeId.slice(0, 8) + "…` 续聊" : "✗ 文件不存在!");
  console.log("\n========== 结论 ==========");
  console.log(`两轮均完成: ${doneCount === 2 ? "✓" : "✗"}`);
  console.log(`两轮都有流式输出: ${stream1 > 0 && stream2 > 0 ? "✓" : "✗"}`);
  console.log(`claudeSessionId==appId(首轮 sessionId 生效): ${claudeId === appId ? "✓" : "✗"}`);
  console.log(`落盘可 resume: ${ok ? "✓" : "✗"}`);
  console.log(`APPID=${appId}`); // 供后续手动 resume 验证
  ws.close();
  process.exit(ok && doneCount === 2 && stream1 > 0 && stream2 > 0 ? 0 : 1);
}

setTimeout(() => { console.error("[verify] 超时"); process.exit(1); }, 90000);
