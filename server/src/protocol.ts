// The WebSocket wire protocol between the server and the app (single source of truth).
// After the refactor: removed all host.* (PTY injection is deprecated); SessionSummary gained claudeSessionId (used for terminal resume).

export interface UsageInfo {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;
  costUsd: number;
}

export type SessionStatus =
  | "idle" //               idle, waiting for input (the normal state between turns)
  | "thinking" //           generating a reply
  | "tool" //               running a tool (Bash/Edit/...)
  | "waiting_permission"; // waiting for the phone to approve a permission (never happens when bypass is the default)

export interface ContentBlock {
  type: "text" | "thinking" | "tool_use" | "tool_result";
  text?: string;
  // tool_use
  id?: string;
  name?: string;
  input?: unknown;
  // tool_result
  toolUseId?: string;
  content?: unknown;
  isError?: boolean;
}

export interface Message {
  id: string;
  role: "user" | "assistant" | "system"; // system: system notices such as compaction separators (rendered as a centered divider)
  blocks: ContentBlock[];
  ts: number;
}

export interface SessionSummary {
  id: string; //               appId (also used as the initial id of the claude session at creation time)
  claudeSessionId?: string; //  latest claude session id; run `claude --resume <this value>` in the terminal to continue the chat
  source: "app";
  title: string;
  cwd: string;
  model: string;
  status: SessionStatus;
  currentActivity?: { tool: string; input: unknown };
  usage: UsageInfo;
  contextTokens: number; //    current context usage (input+cache from the latest turn)
  contextLimit: number; //     the model's context window size, used to compute Context %
  toolCounts: Record<string, number>; // call count per tool, e.g. { Bash: 8, Write: 7 }
  permissionMode: string;
  preview: string; //          for the list card: text preview of the last message
  previewRole: "user" | "assistant"; // who the preview is from
  archived: boolean; //        archived (hidden from the list by default)
  updatedAt: number;
}

// Subscription quota utilization (5h / 7d rolling windows). utilization is 0-100, resetsAt is epoch milliseconds (0 = unknown).
export interface UsageWindow {
  utilization: number;
  resetsAt: number;
}
export interface UsageQuota {
  fiveHour: UsageWindow;
  sevenDay: UsageWindow;
}

// An importable past session (a claude session on disk under ~/.claude/projects that isn't in the list yet).
export interface ImportableItem {
  claudeSessionId: string;
  cwd: string;
  title: string;
  updatedAt: number;
}

// Real-time state of the current conversation "turn". outputTokens keeps climbing during generation,
// which the app uses to tell it "isn't stuck": even before any text appears, if tokens are rising / time is ticking, it's alive.
export interface TurnInfo {
  phase: "sent" | "generating" | "done";
  sentAt: number; //      timestamp when the user sent this message
  inputTokens: number; // this turn's input (including cache) tokens, obtained at message_start
  outputTokens: number; // output tokens generated so far this turn, climbing as it generates
}

// ---- Server → App ----
export type ServerEvent =
  | { t: "session.list"; sessions: SessionSummary[] }
  | { t: "session.created"; session: SessionSummary }
  | { t: "session.updated"; session: SessionSummary }
  | { t: "session.removed"; id: string }
  | { t: "session.history"; id: string; messages: Message[]; from: number; total: number; mode: "replace" | "prepend" } // pagination: from = index of this page's first item within the full set, total = total item count
  | { t: "message.delta"; id: string; messageId: string; text: string } // streaming character-by-character output
  | { t: "message.complete"; id: string; message: Message }
  | { t: "activity"; id: string; tool: string | null; input?: unknown } // what it's currently doing
  | { t: "usage"; id: string; usage: UsageInfo } // cumulative token meter
  | { t: "turn"; id: string; turn: TurnInfo } // real-time heartbeat for this turn (pushes tokens as they climb)
  | { t: "permission.request"; id: string; requestId: string; tool: string; input: unknown }
  | { t: "importable.list"; items: ImportableItem[] } // list of importable past sessions
  | { t: "usage.quota"; quota: UsageQuota } //          subscription quota utilization (5h/7d)
  | { t: "error"; message: string };

// ---- App → Server ----
export type ClientCommand =
  | { t: "auth"; token: string }
  | { t: "session.start"; cwd?: string; model?: string; prompt?: string }
  | { t: "session.input"; id: string; text: string }
  | { t: "session.interrupt"; id: string }
  | { t: "session.subscribe"; id: string }
  | { t: "session.more"; id: string; before: number } // page backward: load the previous page [.., before)
  | { t: "session.delete"; id: string }
  | { t: "session.archive"; id: string }
  | { t: "session.listImportable" } //                         request: list importable past sessions on disk
  | { t: "session.import"; claudeSessionId: string; cwd?: string } // import the specified session into the list
  | { t: "usage.get" } //                                      request: subscription quota utilization
  | { t: "permission.respond"; requestId: string; allow: boolean };
