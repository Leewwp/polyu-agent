export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export type PersistedMessageStatus = "NORMAL" | "INTERRUPTED" | "REJECTED";

/**
 * 聊天流内的结构化提示（超限/排队拒绝非裸 toast）：
 * quota=游客配额用尽；busy=排队超时/系统繁忙；concurrent=同会话在途互斥；error=其他失败
 */
export type ChatNoticeKind = "quota" | "busy" | "concurrent" | "error";

export interface ChatNotice {
  kind: ChatNoticeKind;
  text: string;
}

export interface GuestQuotaInfo {
  role: string;
  dailyLimit: number | null;
  remaining: number | null;
}

export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
  /** 注册邮箱（#104 个人中心）；存量/管理员建/游客为 null（页面显示「未设置」） */
  email?: string | null;
  emailVerified?: number | null;
  createTime?: string | null;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface SourceRef {
  index?: number;
  docId: string;
  docName?: string;
  sourceType?: string;
  fileType?: string | null;
  url?: string | null;
  excerpt?: string;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  thinking?: string;
  thinkingDuration?: number;
  isDeepThinking?: boolean;
  isThinking?: boolean;
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
  sources?: SourceRef[];
  recommended?: string[];
  recommendedState?: "loading" | "ready" | "error";
  recommendedOpen?: boolean;
  messageStatus?: PersistedMessageStatus;
  /** 结构化提示（配额用尽/排队拒绝等），渲染为消息内提示块而非 toast */
  notice?: ChatNotice;
  /** 已发出但尚未收到任何流信号（meta/首 delta），此时等待属于排队期 */
  awaitingSignal?: boolean;
}

export type RecommendedQuestionStatus = "SUCCESS" | "EMPTY" | "FAILED";

export interface RecommendedQuestionsPayload {
  status: RecommendedQuestionStatus;
  questions: string[];
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
  sources?: SourceRef[];
  messageStatus?: PersistedMessageStatus;
}
