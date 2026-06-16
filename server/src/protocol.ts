// 服务端 ↔ App 之间的 WebSocket 线协议(单一事实来源)。
// 重构后:删除了所有 host.*(PTY 注入已废弃);SessionSummary 增 claudeSessionId(终端 resume 用)。

export interface UsageInfo {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheCreationTokens: number;
  costUsd: number;
}

export type SessionStatus =
  | "idle" //               空闲,等待输入(轮次之间的常态)
  | "thinking" //           正在生成回复
  | "tool" //               正在执行工具(Bash/Edit/...)
  | "waiting_permission"; // 等待手机批准权限(默认 bypass 时不会出现)

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
  role: "user" | "assistant" | "system"; // system:压缩分隔等系统提示(居中分隔条渲染)
  blocks: ContentBlock[];
  ts: number;
}

export interface SessionSummary {
  id: string; //               appId(创建时即用作 claude 会话的初始 id)
  claudeSessionId?: string; //  最新 claude session id;终端 `claude --resume <此值>` 即可续聊
  source: "app";
  title: string;
  cwd: string;
  model: string;
  status: SessionStatus;
  currentActivity?: { tool: string; input: unknown };
  usage: UsageInfo;
  contextTokens: number; //    当前上下文占用(最近一轮的 input+cache)
  contextLimit: number; //     模型上下文窗口大小,用于算 Context %
  toolCounts: Record<string, number>; // 各工具调用次数,如 { Bash: 8, Write: 7 }
  permissionMode: string;
  preview: string; //          列表卡片用:最后一条消息文本预览
  previewRole: "user" | "assistant"; // 预览来自谁
  archived: boolean; //        已归档(列表默认隐藏)
  updatedAt: number;
}

// 订阅额度利用率(5h / 7d 滚动窗口)。utilization 0-100,resetsAt 为 epoch 毫秒(0=未知)。
export interface UsageWindow {
  utilization: number;
  resetsAt: number;
}
export interface UsageQuota {
  fiveHour: UsageWindow;
  sevenDay: UsageWindow;
}

// 可导入的历史会话(磁盘上 ~/.claude/projects 里、尚未在列表中的 claude 会话)。
export interface ImportableItem {
  claudeSessionId: string;
  cwd: string;
  title: string;
  updatedAt: number;
}

// 当前这一"轮"对话的实时状态。outputTokens 在生成过程中持续上涨,
// App 据此判断"没卡死":即使还没出文字,token 在涨 / 耗时在走,就是活的。
export interface TurnInfo {
  phase: "sent" | "generating" | "done";
  sentAt: number; //      用户发出这句话的时间戳
  inputTokens: number; // 本轮输入(含 cache)token,message_start 时拿到
  outputTokens: number; // 本轮已生成的 output token,边生成边涨
}

// ---- 服务端 → App ----
export type ServerEvent =
  | { t: "session.list"; sessions: SessionSummary[] }
  | { t: "session.created"; session: SessionSummary }
  | { t: "session.updated"; session: SessionSummary }
  | { t: "session.removed"; id: string }
  | { t: "session.history"; id: string; messages: Message[]; from: number; total: number; mode: "replace" | "prepend" } // 分页:from=本页首条在全量中的下标,total=全量条数
  | { t: "message.delta"; id: string; messageId: string; text: string } // 流式逐字输出
  | { t: "message.complete"; id: string; message: Message }
  | { t: "activity"; id: string; tool: string | null; input?: unknown } // 当前在干什么
  | { t: "usage"; id: string; usage: UsageInfo } // 累计 token 仪表
  | { t: "turn"; id: string; turn: TurnInfo } // 本轮实时心跳(token 边涨边推)
  | { t: "permission.request"; id: string; requestId: string; tool: string; input: unknown }
  | { t: "importable.list"; items: ImportableItem[] } // 可导入的历史会话清单
  | { t: "usage.quota"; quota: UsageQuota } //          订阅额度利用率(5h/7d)
  | { t: "error"; message: string };

// ---- App → 服务端 ----
export type ClientCommand =
  | { t: "auth"; token: string }
  | { t: "session.start"; cwd?: string; model?: string; prompt?: string }
  | { t: "session.input"; id: string; text: string }
  | { t: "session.interrupt"; id: string }
  | { t: "session.subscribe"; id: string }
  | { t: "session.more"; id: string; before: number } // 往前翻:加载 [.., before) 的上一页
  | { t: "session.delete"; id: string }
  | { t: "session.archive"; id: string }
  | { t: "session.listImportable" } //                         请求:列出磁盘上可导入的历史会话
  | { t: "session.import"; claudeSessionId: string; cwd?: string } // 导入指定会话进列表
  | { t: "usage.get" } //                                      请求:订阅额度利用率
  | { t: "permission.respond"; requestId: string; allow: boolean };
