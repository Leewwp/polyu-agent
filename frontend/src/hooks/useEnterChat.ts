import { useCallback } from "react";
import { useNavigate } from "react-router-dom";

import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";

/**
 * 游客直通统一入口：已登录直达 /chat；未登录先铸游客号
 * （POST /auth/guest，cookie 档）再进入。铸号失败（flag 未开/防刷限流等）引导登录
 * 不报错——业务提示由 api 拦截器统一 toast，这里只兜底导航。
 * 调用点=FeedSidebar「新对话」、MobileTabbar「对话」tab 与悬浮 FAB。
 *
 * fresh（2026-09-12 修复）：传 true 时导航前重置两个聊天 store——
 * /chat 经 EngineGate 按 engine 落 AgentChatPage（agentChatStore）或 ChatPage
 * （chatStore），二者都有 currentSessionId 非空即续会话的逻辑，zustand 单例跨页面
 * 残留会让「新对话」续到上一次会话；双 store 都清掉后才落全新会话态。
 * 「对话」tab 与 FAB 维持既有语义（有历史进最近会话），不传 fresh。
 */
export function useEnterChat(options?: { fresh?: boolean }) {
  const navigate = useNavigate();
  const fresh = options?.fresh ?? false;
  return useCallback(() => {
    const go = () => {
      if (fresh) {
        useAgentChatStore.getState().startNewChat();
        const chat = useChatStore.getState();
        if (chat.isStreaming) {
          chat.cancelGeneration();
        }
        useChatStore.setState({
          currentSessionId: null,
          messages: [],
          isCreatingNew: false,
          isStreaming: false
        });
      }
      navigate("/chat");
    };
    if (useAuthStore.getState().isAuthenticated) {
      go();
      return;
    }
    useAuthStore
      .getState()
      .guestLogin()
      .then(go)
      .catch(() => navigate("/login"));
  }, [navigate, fresh]);
}
