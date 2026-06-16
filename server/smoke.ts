// 冒烟测试:连服务 → 起一个会话 → 观察流式/活动/token/完成事件,验证整条数据流。
import "dotenv/config";
import { WebSocket } from "ws";

const PORT = process.env.PORT || 4000;
const TOKEN = process.env.AUTH_TOKEN || "";
const url = `ws://127.0.0.1:${PORT}${TOKEN ? `?token=${TOKEN}` : ""}`;

const ws = new WebSocket(url);
let started = false;
let lastStatus = "";
let streamed = 0;

ws.on("open", () => console.log("[smoke] 已连接", url));
ws.on("error", (e) => console.error("[smoke] ws 错误", e));

ws.on("message", (data) => {
  const e = JSON.parse(data.toString());
  switch (e.t) {
    case "session.list":
      console.log(`[smoke] session.list: ${e.sessions.length} 个会话`);
      if (!started) {
        started = true;
        console.log("[smoke] 发起一个测试会话…");
        ws.send(
          JSON.stringify({
            t: "session.start",
            prompt: "请用中文写一段大约 400 字、关于沙漠日落的细腻描写,分三段。直接开始写,不要使用任何工具。",
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
      console.log(`[smoke] activity: ${e.tool ?? "(空)"}`);
      break;
    case "usage":
      console.log(`[smoke] usage(累计): ${JSON.stringify(e.usage)}`);
      break;
    case "turn": {
      const el = ((Date.now() - e.turn.sentAt) / 1000).toFixed(1);
      console.log(
        `[smoke] ⏱ turn ${e.turn.phase} | 耗时 ${el}s | 本轮 in=${e.turn.inputTokens} out=${e.turn.outputTokens}`
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
      console.log(`[smoke] 收到权限请求 tool=${e.tool} → 自动批准`);
      ws.send(JSON.stringify({ t: "permission.respond", requestId: e.requestId, allow: true }));
      break;
    case "error":
      console.error("[smoke] 错误:", e.message);
      break;
  }
});

setTimeout(() => {
  console.log(`\n[smoke] ===== 完成。流式收到 ${streamed} 个字符 =====`);
  ws.close();
  process.exit(0);
}, 60000);
