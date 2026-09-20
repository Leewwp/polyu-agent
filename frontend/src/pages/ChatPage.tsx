import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { ChatInput } from "@/components/chat/ChatInput";
import { ChatQuotaModal } from "@/components/chat/ChatQuotaModal";
import { GuestStatusBadge } from "@/components/chat/GuestStatusBadge";
import { MessageList } from "@/components/chat/MessageList";
import { SourcesPanel } from "@/components/chat/SourcesPanel";
import { FeedShell } from "@/components/feed/FeedShell";
import { useChatStore } from "@/stores/chatStore";

export function ChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    messagesError,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    sessionsError,
    isCreatingNew,
    fetchSessions,
    selectSession,
    createSession
  } = useChatStore();
  const showWelcome = messages.length === 0 && !isLoading;
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return sessions.some((session) => session.id === sessionId);
  }, [sessionId, sessions]);

  React.useEffect(() => {
    let active = true;
    fetchSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [fetchSessions]);

  React.useEffect(() => {
    if (sessionId) {
      // L32：仅列表确实加载成功（sessionsError 为空）才允许判「会话不存在」踢回——
      // 列表加载失败时 sessions 为空不可信，保深链由重试条兜底，不误踢不清深链
      if (sessionsReady && !sessionsError && !sessionExists) {
        createSession().catch(() => null);
        navigate("/chat", { replace: true });
        return;
      }
      selectSession(sessionId).catch(() => null);
      return;
    }
    if (!sessionsReady) {
      return;
    }
    if (isCreatingNew) {
      return;
    }
    if (currentSessionId) {
      return;
    }
    createSession().catch(() => null);
  }, [
    sessionId,
    sessionsReady,
    sessionsError,
    sessionExists,
    isCreatingNew,
    currentSessionId,
    selectSession,
    createSession,
    navigate
  ]);

  // 与 AgentChatPage 同款修复（2026-09-14）：原实现拿渲染闭包里的
  // currentSessionId 与 URL 互比，chatStore.loadMessages 开头同步写 store——切会话时
  // 本效应持落后一步旧值把 URL replace 回上一会话，两效应无限互搏（请求风暴）。
  // 改读实时 store 值后一次收敛。
  React.useEffect(() => {
    const liveSessionId = useChatStore.getState().currentSessionId;
    if (liveSessionId && liveSessionId !== sessionId) {
      navigate(`/chat/${liveSessionId}`, { replace: true });
    }
  }, [currentSessionId, sessionId, navigate]);

  // 换壳：外层 MainLayout → FeedShell（fluid 聊天档）——资讯流侧栏/顶栏统一，
  // 上游 MainLayout/Header/Sidebar/SiteFooter（Ragent 品牌/nageoffer 外链/GitHub 星钮）不再渲染
  return (
    <FeedShell title={{ zh: "智能问答", en: "Smart Q&A" }} fluid>
      {/* quota 类错误升级为全局超限弹窗（行内块让位，见 ChatQuotaModal） */}
      <ChatQuotaModal />
      {/* 主列/输入条外层不再自带 bg-white——壳的 --feed-bg 灰底透出，
          消息气泡与 ChatInput 本体的白卡层次保持（灰底白卡与资讯页同构） */}
      <div className="flex h-full">
        <div className="flex h-full min-w-0 flex-1 flex-col">
          {/* L32：会话列表加载失败条——深链不误踢，由这里给重试入口 */}
          {sessionsError ? (
            <div className="mx-auto w-full max-w-[840px] px-6 pt-3">
              <div className="flex items-center justify-between gap-3 rounded-lg border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] px-3.5 py-2 text-[12.5px] text-[var(--feed-text-secondary)]">
                <span>{sessionsError}——左侧最近对话暂不可用</span>
                <button
                  type="button"
                  className="flex-none rounded-full border border-[var(--feed-line)] px-3 py-1 font-semibold text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red)]"
                  onClick={() => {
                    fetchSessions().catch(() => null);
                  }}
                >
                  重试
                </button>
              </div>
            </div>
          ) : null}
          <div className="flex-1 min-h-0">
            <MessageList
              messages={messages}
              isLoading={isLoading}
              isStreaming={isStreaming}
              sessionKey={currentSessionId}
              loadError={messagesError}
              onRetry={currentSessionId ? () => selectSession(currentSessionId) : undefined}
            />
          </div>
          {showWelcome ? null : (
            <div className="relative z-20">
              <div className="mx-auto max-w-[840px] px-6 pt-1 pb-4">
                <div className="flex justify-center pb-2">
                  <GuestStatusBadge />
                </div>
                <ChatInput />
                <p className="pt-2 text-center text-xs leading-relaxed text-[#9AA0A6]">
                  内容由 AI 生成，仅供参考；本服务非香港理工大学官方服务 · AI-generated for reference; not an official PolyU service
                </p>
              </div>
            </div>
          )}
        </div>
        <SourcesPanel />
      </div>
    </FeedShell>
  );
}
