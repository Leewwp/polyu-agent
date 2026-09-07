import { api } from "@/services/api";
import type { SourceRef } from "@/types";

/** 分享创建结果 */
export interface ShareCreated {
  token: string;
  expireTime?: string | null;
}

/** 公开分享载荷（匿名可读，字段白名单） */
export interface PublicShare {
  question: string;
  answerMd: string;
  citations?: SourceRef[] | null;
  lang?: string | null;
  contentVersion?: string | null;
  createTime?: string | null;
  expireTime?: string | null;
}

/** 本人分享列表项 */
export interface ShareMineItem {
  token: string;
  questionPreview: string;
  status: string;
  expireTime?: string | null;
  createTime?: string | null;
}

/** 创建单条回答的公开分享快照（E-1；flag 默认关，未启用时后端 404） */
export async function createShare(messageId: string): Promise<ShareCreated> {
  return api.post<ShareCreated, ShareCreated>("/share", { messageId });
}

/** 撤销本人分享 */
export async function revokeShare(token: string): Promise<void> {
  return api.delete(`/share/${token}`);
}

/** 本人分享列表 */
export async function listMyShares(): Promise<ShareMineItem[]> {
  return api.get<ShareMineItem[], ShareMineItem[]>("/share/mine");
}

/** 匿名读取公开分享快照 */
export async function getPublicShare(token: string): Promise<PublicShare> {
  return api.get<PublicShare, PublicShare>(`/public/share/${token}`);
}
