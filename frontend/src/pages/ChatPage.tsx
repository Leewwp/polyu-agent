import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { ChatInput } from "@/components/chat/ChatInput";
import { ChatQuotaModal } from "@/components/chat/ChatQuotaModal";
import { GuestStatusBadge } from "@/components/chat/GuestStatusBadge";
import { MessageList } from "@/components/chat/MessageList";
import { SourcesPanel } from "@/components/chat/SourcesPanel";
import { FeedShell } from "@/components/feed/FeedShell";
import { SessionUnavailableCard } from "@/components/feed/SessionUnavailableCard";
import { resolveDeepLinkPhase } from "@/lib/deepLinkGuard";
import { useChatStore } from "@/stores/chatStore";

export function ChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    messagesSessionId,
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

  // #140 深链四态状态机（doc44 D-10，与 Agent 链同语义同隐私 UX；S4 不再静默踢回）
  const phase = resolveDeepLinkPhase({ sessionId, sessions, listReady: sessionsReady, listError: sessionsError });
  // 旧内容清零（N6）：归属未换前不渲染上一会话正文
  const sessionMatched = !sessionId || messagesSessionId === sessionId;

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

  // S3：列表确认归属后才加载目标（S1/S2/S4 均不拉不猜）
  React.useEffect(() => {
    if (phase === "target" && sessionId) {
      selectSession(sessionId).catch(() => null);
    }
  }, [phase, sessionId, selectSession]);

  // 无深链的新会话路径（原语义保留）
  React.useEffect(() => {
    if (phase !== "no-target") {
      return;
    }
    if (!sessionsReady || isCreatingNew || currentSessionId) {
      return;
    }
    createSession().catch(() => null);
  }, [phase, sessionsReady, isCreatingNew, currentSessionId, createSession]);

  // 与 AgentChatPage 同款修复（2026-09-14）：原实现拿渲染闭包里的
  // currentSessionId 与 URL 互比，chatStore.selectSession 开头同步写 store——切会话时
  // 本效应持落后一步旧值把 URL replace 回上一会话，两效应无限互搏（请求风暴）。
  // 改读实时 store 值后一次收敛。#140：S4 不回跳（坏深链保持原 URL 由错误卡接手）。
  React.useEffect(() => {
    if (phase === "not-found") {
      return;
    }
    const liveSessionId = useChatStore.getState().currentSessionId;
    if (liveSessionId && liveSessionId !== sessionId) {
      navigate(`/chat/${liveSessionId}`, { replace: true });
    }
  }, [phase, currentSessionId, sessionId, navigate]);

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
          {phase === "not-found" ? (
            // S4：三态归一错误卡（不泄露存在性/属主/内容/ID）；用户点击才开始新对话
            <SessionUnavailableCard
              onStartNew={() => {
                createSession().catch(() => null);
                navigate("/chat", { replace: true });
              }}
            />
          ) : (
            <>
              <div className="flex-1 min-h-0">
                {sessionMatched ? (
                  <MessageList
                    messages={messages}
                    isLoading={isLoading}
                    isStreaming={isStreaming}
                    sessionKey={currentSessionId}
                    loadError={messagesError}
                    onRetry={currentSessionId ? () => selectSession(currentSessionId) : undefined}
                  />
                ) : (
                  <div data-testid="message-list-loading" className="h-full" />
                )}
              </div>
              {showWelcome || !sessionMatched ? null : (
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
            </>
          )}
        </div>
        <SourcesPanel />
      </div>
    </FeedShell>
  );
}
