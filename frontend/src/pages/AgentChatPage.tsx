import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { AgentChatInput } from "@/components/agent/AgentChatInput";
import { AgentMessageList } from "@/components/agent/AgentMessageList";
import { FeedShell } from "@/components/feed/FeedShell";
import { LoginPromptModal } from "@/components/feed/LoginPromptModal";
import { useFeedLang } from "@/components/feed/feedLang";
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
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    isCreatingNew,
    loadSessions,
    loadMessages,
    startNewChat
  } = useAgentChatStore();
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return sessions.some((session) => session.id === sessionId);
  }, [sessionId, sessions]);

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

  React.useEffect(() => {
    if (sessionId) {
      if (sessionsReady && !sessionExists) {
        startNewChat();
        navigate("/chat", { replace: true });
        return;
      }
      loadMessages(sessionId).catch(() => null);
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
    startNewChat();
  }, [
    sessionId,
    sessionsReady,
    sessionExists,
    isCreatingNew,
    currentSessionId,
    loadMessages,
    startNewChat,
    navigate
  ]);

  // 新会话在 meta 事件产生 conversationId 后同步 URL。
  // 修复（2026-09-14）：原实现拿渲染闭包里的 currentSessionId 与 URL 互比，
  // 而 loadMessages 开头会同步写 store 的 currentSessionId——切会话时本效应执行仍持
  // 落后一步的旧值，把 URL replace 回上一个会话，两效应 A⇄B 无限互搏（单次点击实测
  // 触发 2.3 万条 messages 请求直至浏览器 ERR_INSUFFICIENT_RESOURCES，表现为切换卡死）。
  // 改读实时 store 值后两效应一次收敛，不再回弹。
  React.useEffect(() => {
    const liveSessionId = useAgentChatStore.getState().currentSessionId;
    if (liveSessionId && liveSessionId !== sessionId) {
      navigate(`/chat/${liveSessionId}`, { replace: true });
    }
  }, [currentSessionId, sessionId, navigate]);

  // 换壳：外层 AgentLayout → FeedShell（fluid 聊天档）——RAGENT 品牌/nageoffer
  // GitHub 星钮随 AgentHeader 不再渲染；.agent-app 域加 agent-embedded（高度交给壳，
  // 100vh 外壳与 .agent-body 侧栏网格解除，侧栏由 FeedSidebar 承担）；
  // 「原始帧」调试入口随 AgentLayout 下线（AgentRawLog 唯一消费方，已知限制记后续待办）。
  return (
    <FeedShell title={{ zh: "智能体对话", en: "Agent chat" }} fluid>
      <AgentQuotaModal />
      <div className="agent-app agent-embedded h-full">
        {/* agent-main 网格两行：事件流占满 输入条贴底 */}
        <div className="agent-main h-full">
          <AgentMessageList
            messages={messages}
            isLoading={isLoading}
            isStreaming={isStreaming}
            sessionKey={currentSessionId}
          />
          <AgentChatInput />
        </div>
      </div>
    </FeedShell>
  );
}
