import type { ChatNoticeKind } from "@/types";

/**
 * 聊天流错误分类：把后端文案映射为结构化提示类别，
 * 决定消息内提示块的样式与行动建议（注册解锁/稍后重试）。
 * 文案关键词与后端保持同步：AnonymousTrialGuard（配额）、ChatQueueLimiter（排队超时）、
 * IdempotentSubmit（在途互斥）。
 */
export function classifyChatError(message: string | null | undefined): ChatNoticeKind {
  const text = (message || "").trim();
  if (!text) {
    return "error";
  }
  if (text.includes("匿名试用次数已用完") || text.includes("试用次数")) {
    return "quota";
  }
  if (text.includes("系统繁忙") || text.includes("排队") || text.includes("稍后再试")) {
    return "busy";
  }
  if (text.includes("会话处理中") || text.includes("稍后再发起")) {
    return "concurrent";
  }
  return "error";
}

/** 各类提示的展示文案（后端原文优先，这里只兜底空文案与统一补充行动句） */
export function noticeTextFor(kind: ChatNoticeKind, rawMessage: string | null | undefined): string {
  const raw = (rawMessage || "").trim();
  switch (kind) {
    case "quota":
      return raw || "今日游客试用次数已用完";
    case "busy":
      return (raw || "系统繁忙") + "，当前使用人数较多，请稍等片刻再试";
    case "concurrent":
      return raw || "当前会话处理中，请稍后再发起新的对话";
    default:
      return raw || "生成失败，请稍后重试";
  }
}
