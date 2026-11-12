import { api } from "@/services/api";

export interface AgentToolCallAuditItem {
  id: string;
  conversationId: string;
  userId: string;
  role?: string | null;
  messageId: string;
  blockIndex: number;
  runId?: string | null;
  toolName: string;
  toolCallId?: string | null;
  batchId?: string | null;
  decision: string;
  status: string;
  inputDigest?: string | null;
  inputExcerpt?: string | null;
  durationMs?: number | null;
  createTime?: string | null;
}

export interface AgentToolCallAuditPageResult {
  records: AgentToolCallAuditItem[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface AgentToolCallAuditQuery {
  current?: number;
  size?: number;
  userId?: string;
  toolName?: string;
  decision?: string;
  beginTime?: string;
  endTime?: string;
}

export async function getAgentToolCallAuditPage(
  query: AgentToolCallAuditQuery = {}
): Promise<AgentToolCallAuditPageResult> {
  return api.get<AgentToolCallAuditPageResult, AgentToolCallAuditPageResult>("/admin/agent-tool-calls", {
    params: {
      current: query.current ?? 1,
      size: query.size ?? 10,
      userId: query.userId || undefined,
      toolName: query.toolName || undefined,
      decision: query.decision || undefined,
      beginTime: query.beginTime || undefined,
      endTime: query.endTime || undefined
    }
  });
}
