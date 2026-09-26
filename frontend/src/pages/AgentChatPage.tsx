import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { AgentChatInput } from "@/components/agent/AgentChatInput";
import { AgentMessageList } from "@/components/agent/AgentMessageList";
import { AgentShareDialog } from "@/components/agent/AgentShareDialog";
import { FeedShell } from "@/components/feed/FeedShell";
import { LoginPromptModal } from "@/components/feed/LoginPromptModal";
import { SessionUnavailableCard } from "@/components/feed/SessionUnavailableCard";
import { useFeedLang } from "@/components/feed/feedLang";
import { resolveDeepLinkPhase } from "@/lib/deepLinkGuard";
import { useAgentChatStore } from "@/stores/agentChatStore";

/** 游客超限弹窗桥（agent 链配额 guard 拒绝→quotaError 态；语义同 workflow 链 ChatQuotaModal） */
function AgentQuotaModal() {
  const { lang } = useFeedLang();
  const quotaError = useAgentChatStore((state) => state.quotaError);
  const dismiss = useAgentChatStore((state) => state.dismissQuotaError);
  if (!quotaError) {
    return null;
  }
  return <LoginPromptModal open lang={lang} onClose={dismiss} />;
}

export function AgentChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    messagesSessionId,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    sessionsError,
    isCreatingNew,
    loadSessions,
    loadMessages,
    startNewChat
  } = useAgentChatStore();
  const [sessionsReady, setSessionsReady] = React.useState(false);

  // #140 深链四态状态机（doc44 D-10，与 Workflow 链同语义同隐私 UX）：
  // S1 列表未可信=不判不存在+不启动目标加载+不显示旧正文；S2 列表失败=保 URL+
  // 重试条；S3 列表成功含目标=此刻才加载；S4 不含目标=保 URL+统一「无法打开此
  // 会话」卡，「开始新对话」由用户点击才导航（不再静默踢回）。
  const phase = resolveDeepLinkPhase({ sessionId, sessions, listReady: sessionsReady, listError: sessionsError });
  // 旧内容清零（N6）：仅当消息归属与 URL 会话一致（或无深链）才渲染消息列——
  // 归属确认前与切换加载期间，上一会话正文不可见
  const sessionMatched = !sessionId || messagesSessionId === sessionId;

  React.useEffect(() => {
    let active = true;
    loadSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [loadSessions]);

  // S3：列表确认归属后才启动目标消息加载（S1/S2/S4 均不拉不猜）
  React.useEffect(() => {
    if (phase === "target" && sessionId) {
      loadMessages(sessionId).catch(() => null);
    }
  }, [phase, sessionId, loadMessages]);

  // 无深链的新会话路径（原语义保留；S4 不自动 startNewChat）
  React.useEffect(() => {
    if (phase !== "no-target") {
      return;
    }
    if (!sessionsReady || isCreatingNew || currentSessionId) {
      return;
    }
    startNewChat();
  }, [phase, sessionsReady, isCreatingNew, currentSessionId, startNewChat]);

  // 新会话在 meta 事件产生 conversationId 后同步 URL。
  // 修复（2026-09-14）：原实现拿渲染闭包里的 currentSessionId 与 URL 互比，
  // 而 loadMessages 开头会同步写 store 的 currentSessionId——切会话时本效应执行仍持
  // 落后一步的旧值，把 URL replace 回上一个会话，两效应 A⇄B 无限互搏（单次点击实测
  // 触发 2.3 万条 messages 请求直至浏览器 ERR_INSUFFICIENT_RESOURCES，表现为切换卡死）。
  // 改读实时 store 值后两效应一次收敛，不再回弹。
  // #140：S4（列表确认不含目标）不回跳——坏深链保持原 URL 由错误卡接手。
  React.useEffect(() => {
    if (phase === "not-found") {
      return;
    }
    const liveSessionId = useAgentChatStore.getState().currentSessionId;
    if (liveSessionId && liveSessionId !== sessionId) {
      navigate(`/chat/${liveSessionId}`, { replace: true });
    }
  }, [phase, currentSessionId, sessionId, navigate]);

  // 换壳：外层 AgentLayout → FeedShell（fluid 聊天档）——RAGENT 品牌/nageoffer
  // GitHub 星钮随 AgentHeader 不再渲染；.agent-app 域加 agent-embedded（高度交给壳，
  // 100vh 外壳与 .agent-body 侧栏网格解除，侧栏由 FeedSidebar 承担）；
  // 「原始帧」调试入口随 AgentLayout 下线（AgentRawLog 唯一消费方，已知限制记后续待办）。
  return (
    <FeedShell title={{ zh: "智能体对话", en: "Agent chat" }} fluid>
      <AgentQuotaModal />
      <div className="agent-app agent-embedded h-full">
        {/* L32：会话列表加载失败条——深链不误踢，由这里给重试入口 */}
        {sessionsError ? (
          <div className="mx-auto w-full max-w-[840px] px-6 pt-3">
            <div className="flex items-center justify-between gap-3 rounded-lg border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] px-3.5 py-2 text-[12.5px] text-[var(--feed-text-secondary)]">
              <span>{sessionsError}——左侧最近对话暂不可用</span>
              <button
                type="button"
                className="flex-none rounded-full border border-[var(--feed-line)] px-3 py-1 font-semibold text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red)]"
                onClick={() => {
                  loadSessions().catch(() => null);
                }}
              >
                重试
              </button>
            </div>
          </div>
        ) : null}
        {phase === "not-found" ? (
          // S4：三态归一错误卡（不泄露存在性/属主/内容/ID）；用户点击才开始新对话
          <SessionUnavailableCard
            onStartNew={() => {
              startNewChat();
              navigate("/chat", { replace: true });
            }}
          />
        ) : (
          /* agent-main 网格两行：事件流占满 输入条贴底；S1/S2 与归属未换期间渲染加载态而非旧正文 */
          <div className="agent-main h-full">
            {sessionMatched ? (
              <AgentMessageList
                messages={messages}
                isLoading={isLoading}
                isStreaming={isStreaming}
                sessionKey={currentSessionId}
                showAnswerActions
              />
            ) : (
              <div className="agent-stream h-full" />
            )}
            <AgentChatInput />
          </div>
        )}
      </div>
      {/* #139 Scoped Share 弹窗（顶栏 full / 答案 turn 两入口共用；挂壳内随 FeedLang） */}
      <AgentShareDialog />
    </FeedShell>
  );
}
