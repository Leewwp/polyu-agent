import { api } from "@/services/api";
import type { AgentBlock, AgentEngineMeta, AgentPersistedMessageStatus } from "@/types/agent";

export interface AgentConversationVO {
  conversationId: string;
  title: string;
  lastTime?: string;
  turns?: number;
}

export interface AgentMessageVO {
  // #139 Should 收紧：后端 t_agent_message.id=VARCHAR(20) String 雪花，JSON 恒为
  // string——旧 number 联合不再需要（store 的 String() 即时归一同步移除；
  // anchor/share 全链维持 String，禁 Number/parseInt）
  id: string;
  role: string;
  content: string;
  thinkingContent?: string | null;
  // 旧数据为 null 由前端按持久化字段合成
  blocks?: AgentBlock[] | null;
  messageStatus?: AgentPersistedMessageStatus | null;
  // 服务端耗时 assistant 才有
  durationMs?: number | null;
  createTime?: string;
}

export async function listAgentSessions() {
  return api.get<AgentConversationVO[], AgentConversationVO[]>("/agent/v1/conversations");
}

export async function listAgentMessages(conversationId: string) {
  return api.get<AgentMessageVO[], AgentMessageVO[]>(
    `/agent/v1/conversations/${conversationId}/messages`
  );
}

export async function renameAgentSession(conversationId: string, title: string) {
  return api.put<void>(`/agent/v1/conversations/${conversationId}/title`, { title });
}

export async function deleteAgentSession(conversationId: string) {
  return api.delete<void>(`/agent/v1/conversations/${conversationId}`);
}

export async function batchDeleteAgentSessions(conversationIds: string[]) {
  return api.post<void>("/agent/v1/conversations/batch-delete", { ids: conversationIds });
}

export async function getAgentMeta() {
  return api.get<AgentEngineMeta, AgentEngineMeta>("/agent/v1/meta");
}

export async function stopAgentTask(taskId: string) {
  return api.post<void>(`/agent/v1/stop?taskId=${encodeURIComponent(taskId)}`);
}
