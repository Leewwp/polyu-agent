import { AGENT_STREAM_RESET, useAgentChatStore } from "@/stores/agentChatStore";
import { WORKFLOW_STREAM_RESET, useChatStore } from "@/stores/chatStore";

/**
 * 换号清场（M15/L36/L38 统一入口）：login/logout/guestLogin 与会话过期共用。
 * zustand 单例跨页面存活，login/logout 原先只重置 workflow 链的 chatStore——
 * agentChatStore 残留上一账号的 currentSessionId/messages/sessions，B 同 tab
 * 登录后 AgentChatPage 因 currentSessionId 非空提前返回，直接看到 A 的对话。
 * 两个 store 必须对称清场；在途流先停服务端再生（有 taskId 时）再硬断 fetch，
 * 清场后迟到帧被 streamingMessageId 守卫拦下。
 */

function resetWorkflowChat(isCreatingNew: boolean) {
  const chat = useChatStore.getState();
  if (chat.isStreaming) {
    chat.cancelGeneration();
    chat.streamAbort?.();
  }
  useChatStore.setState({
    sessions: [],
    currentSessionId: null,
    messages: [],
    messagesError: null,
    isLoading: false,
    isCreatingNew,
    deepThinkingEnabled: false,
    openedSourceMessageId: null,
    recommendReveal: null,
    ...WORKFLOW_STREAM_RESET
  });
}

function resetAgentChat(isCreatingNew: boolean) {
  const agent = useAgentChatStore.getState();
  if (agent.isStreaming) {
    agent.cancelGeneration();
    agent.streamAbort?.();
  }
  useAgentChatStore.setState({
    sessions: [],
    currentSessionId: null,
    messages: [],
    isLoading: false,
    sessionsLoaded: false,
    frames: [],
    quotaError: null,
    draft: null,
    isCreatingNew,
    ...AGENT_STREAM_RESET
  });
}

/** isCreatingNew：登录/游客铸号=true（落全新会话态）；登出/会话过期=false（无人续用） */
export function resetChatStoresForAccountSwitch(options: { isCreatingNew: boolean }) {
  resetWorkflowChat(options.isCreatingNew);
  resetAgentChat(options.isCreatingNew);
}
