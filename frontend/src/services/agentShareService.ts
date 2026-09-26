import { api } from "@/services/api";

/**
 * 会话分享创建结果（issue #82）
 */
export interface AgentShareCreated {
  token: string;
  expireTime?: string | null;
}

/** Scoped Share 范围（issue #138 后端合同，#139 前端消费）：full=完整对话快照 */
export type AgentShareScope = "full" | "turn" | "through";

/**
 * 快照来源条目（issue #91 v2 可选字段）：SourceRef 等价投影，
 * v1 旧快照无此字段——前端缺省即不渲染来源徽章（优雅降级）
 */
export interface AgentShareSnapshotSource {
  index?: number | null;
  docId: string;
  docName?: string | null;
  excerpt?: string | null;
  url?: string | null;
  sourceType?: string | null;
}

/**
 * 快照消息条目：role/content/createTime 白名单 +（v2）assistant 条目可选 sources
 * （隐私负面清单外字段不存在）
 */
export interface AgentShareSnapshotItem {
  role: string;
  content: string;
  createTime?: string | null;
  sources?: AgentShareSnapshotSource[] | null;
}

/** 会话分享公开载荷（匿名可读，字段白名单） */
export interface PublicAgentShare {
  title: string;
  messages: AgentShareSnapshotItem[];
  lang?: string | null;
  contentVersion?: string | null;
  createTime?: string | null;
  expireTime?: string | null;
}

/** 「我的分享」列表项 */
export interface AgentShareMineItem {
  token: string;
  titlePreview: string;
  messageCount?: number | null;
  status: string;
  expireTime?: string | null;
  createTime?: string | null;
}

/**
 * 创建会话只读分享快照（flag 默认关，未启用时后端 404）；
 * conversationId 为当前 agent 会话业务 ID。
 * #139 Scoped Share 扩参（#138 已部署的后端合同，向后兼容）：scope 缺省=full
 * （旧客户端形态不变）；turn/through 必带 anchorAssistantMessageId——
 * **全链 String**（t_agent_message.id VARCHAR(20) 雪花，超 JS Number 安全整数，
 * 禁 Number/parseInt 转换，原样透传）。
 */
export async function createAgentShare(
  conversationId: string,
  scope?: AgentShareScope,
  anchorAssistantMessageId?: string
): Promise<AgentShareCreated> {
  return api.post<AgentShareCreated, AgentShareCreated>("/agent/share", {
    conversationId,
    ...(scope ? { scope } : {}),
    ...(anchorAssistantMessageId ? { anchorAssistantMessageId } : {})
  });
}

/** 撤销本人会话分享 */
export async function revokeAgentShare(token: string): Promise<void> {
  return api.delete(`/agent/share/${token}`);
}

/** 本人会话分享列表 */
export async function listMyAgentShares(): Promise<AgentShareMineItem[]> {
  return api.get<AgentShareMineItem[], AgentShareMineItem[]>("/agent/share/mine");
}

/** 匿名读取会话分享公开载荷 */
export async function getPublicAgentShare(token: string): Promise<PublicAgentShare> {
  return api.get<PublicAgentShare, PublicAgentShare>(`/public/share/agent/${token}`);
}
