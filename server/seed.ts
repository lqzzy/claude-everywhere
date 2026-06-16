// Create a few sessions with different statuses/directories, to compare against the sessions.html design mockup.
import "dotenv/config";
import { WebSocket } from "ws";
const TOKEN = process.env.AUTH_TOKEN || "";
const ws = new WebSocket(`ws://127.0.0.1:4000?token=${TOKEN}`);
const starts = [
  { cwd: "/Users/qili/remote-claude", prompt: "Reply with just one sentence: the crash came from a null dereference; added a null guard and a regression test. Don't use any tools." },
  { cwd: "/Users/qili/remote-claude/server", prompt: "Reply with just one sentence: added a turn heartbeat event to ServerEvent so the App can tell it hasn't hung. Don't use any tools." },
  { cwd: "/Users/qili/remote-claude/app", prompt: "Reply with just one sentence: turn this week's commits into a three-paragraph weekly report draft. Don't use any tools." },
  { cwd: "/Users/qili/remote-claude", prompt: "Use the Write tool to write hello into /tmp/seed_perm.txt (you must actually call the Write tool)" },
];
ws.on("open", () => {
  for (const s of starts) ws.send(JSON.stringify({ t: "session.start", cwd: s.cwd, prompt: s.prompt }));
  console.log("seeded", starts.length, "sessions");
});
ws.on("message", (d) => {
  const e = JSON.parse(d.toString());
  if (e.t === "permission.request") console.log("permission pending (kept as a danger card):", e.tool);
});
setTimeout(() => process.exit(0), 20000);
