/**
 * 私有会话深链四态状态机（issue #140，doc44 D-10；Agent 与 Workflow 双链同语义）：
 *
 * | 态 | 判据 | 行为 |
 * |---|---|---|
 * | no-target    | URL 无 sessionId | 走新对话路径（页面自理） |
 * | list-loading | 列表未可信加载（S1） | 不判「不存在」、**不启动目标消息加载**、不显示旧会话正文 |
 * | list-error   | 列表加载失败（S2）   | 保留原 URL+重试条，不误判不存在、不显示旧会话内容 |
 * | target       | 列表成功且含目标（S3）| 此刻才加载并正常展示 |
 * | not-found    | 列表成功但不含目标（S4）| 保留 URL+统一「无法打开此会话」卡，「开始新对话」才触发导航 |
 *
 * 纯派生（不触发副作用）：加载/导航动作由页面按 phase 自理。
 */
export type DeepLinkPhase = "no-target" | "list-loading" | "list-error" | "target" | "not-found";

export function resolveDeepLinkPhase(input: {
  sessionId?: string;
  sessions: Array<{ id: string }>;
  /** 列表请求已落定（成功或失败都算落定；失败时列表内容不可信） */
  listReady: boolean;
  listError: string | null;
}): DeepLinkPhase {
  const { sessionId, sessions, listReady, listError } = input;
  if (!sessionId) {
    return "no-target";
  }
  if (!listReady) {
    return "list-loading";
  }
  if (listError) {
    return "list-error";
  }
  return sessions.some((session) => session.id === sessionId) ? "target" : "not-found";
}
