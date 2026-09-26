import type { AgentMessage, AgentTurn } from "@/types/agent";

/**
 * Shareable Turn Anchor（issue #139，doc44 D-7 统一概念）：
 * Turn 内倒序第一个满足「持久化服务端 assistant ID + done + NORMAL + content 非空」的 assistant。
 * 四处共用同一 selector——Answer Turn footer / Header 三档 radio 单选 / Dialog 预览 /
 * 创建请求的 anchorAssistantMessageId——禁止各写一套判定。
 *
 * 类型红线：assistant.id 全链 String（t_agent_message.id VARCHAR(20) 雪花，
 * 18–19 位超 JS Number 安全整数；任何 Number/parseInt 转换都会精度丢失）。
 */

/** 流式占位临时 ID 前缀（agentChatStore 建 `assistant-${Date.now()}`，服务端 meta 事件换真 ID） */
const TEMP_ASSISTANT_ID = /^assistant-/;

/** 单条 assistant 是否可作 Shareable Anchor（streaming/AWAITING_CONFIRM/INTERRUPTED/取消/出错/空正文均不可） */
export function isShareableAssistant(message: AgentMessage): boolean {
  return (
    typeof message.id === "string" &&
    message.id.length > 0 &&
    !TEMP_ASSISTANT_ID.test(message.id) &&
    message.status === "done" &&
    (message.messageStatus ?? "NORMAL") === "NORMAL" &&
    typeof message.content === "string" &&
    message.content.trim().length > 0
  );
}

/** Turn 的 Shareable Anchor：assistants 倒序首个合格者；无则 null（该 Turn 不可作 anchor） */
export function findShareableAnchor(turn: AgentTurn): AgentMessage | null {
  for (let i = turn.assistants.length - 1; i >= 0; i -= 1) {
    const assistant = turn.assistants[i];
    if (isShareableAssistant(assistant)) {
      return assistant;
    }
  }
  return null;
}

/** 会话内全部有 Shareable Anchor 的 Turn（Header turn/through 单选列表数据源，倒序=最新在前） */
export function listShareableTurns(turns: AgentTurn[]): Array<{ turn: AgentTurn; anchor: AgentMessage }> {
  const out: Array<{ turn: AgentTurn; anchor: AgentMessage }> = [];
  for (let i = turns.length - 1; i >= 0; i -= 1) {
    const anchor = findShareableAnchor(turns[i]);
    if (anchor) {
      out.push({ turn: turns[i], anchor });
    }
  }
  return out;
}
