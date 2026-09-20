import * as React from "react";
import { Link, useParams } from "react-router-dom";

import { groupTurns } from "@/components/agent/AgentMessageList";
import { AgentTurnItem } from "@/components/agent/AgentTurn";
import { FeedShell } from "@/components/feed/FeedShell";
import { useFeedLang } from "@/components/feed/feedLang";
import { useEnterChat } from "@/hooks/useEnterChat";
import { toBlockHms } from "@/lib/agentTimeline";
import { getPublicAgentShare } from "@/services/agentShareService";
import type { AgentShareSnapshotItem, PublicAgentShare } from "@/services/agentShareService";
import { useAuthStore } from "@/stores/authStore";
import type { AgentMessage } from "@/types/agent";
import type { SourceRef } from "@/types";

/**
 * 会话分享公开页（issue #91 壳化重写）：/share/c/:token 落真实产品壳的只读
 * 分享视图——FeedShell（fluid 聊天档）+ 侧栏分享视图态（被分享会话单条目、
 * 零网络请求）+ 主体复用 AgentTurnItem 渲染（markdown 正文 + 来源徽章，与
 * 分享者所见同款视觉）。思考过程/工具时间线/耗时维持不外发（#82 隐私收敛）；
 * noindex 与统一无效语义（「分享链接无效或已撤销」）原样保留、在壳内呈现。
 */
export function AgentSharePage() {
  const { token } = useParams<{ token: string }>();
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const [share, setShare] = React.useState<PublicAgentShare | null>(null);
  const [invalid, setInvalid] = React.useState(false);
  const [loading, setLoading] = React.useState(true);
  // 游客直通 CTA（fresh：开新会话，非 fork——「继续聊」显式后置于 #82）
  const enterChat = useEnterChat({ fresh: true });

  // 首发要求搜索引擎不收录（与单条分享页同款双保险之后端同源语义）
  React.useEffect(() => {
    const meta = document.createElement("meta");
    meta.name = "robots";
    meta.content = "noindex, nofollow";
    document.head.appendChild(meta);
    return () => {
      document.head.removeChild(meta);
    };
  }, []);

  React.useEffect(() => {
    let cancelled = false;
    if (!token) {
      setInvalid(true);
      setLoading(false);
      return;
    }
    setLoading(true);
    getPublicAgentShare(token)
      .then((data) => {
        if (!cancelled) setShare(data);
      })
      .catch(() => {
        if (!cancelled) setInvalid(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const turns = React.useMemo(() => (share ? groupTurns(toTimelineMessages(share.messages)) : []), [share]);
  const ready = !loading && !invalid && share !== null;

  return (
    <FeedShell
      title={{ zh: "分享对话", en: "Shared conversation" }}
      fluid
      shareView={{ title: share?.title ?? null }}
    >
      <div className="agent-app agent-embedded h-full">
        <div className="agent-main agent-share-main h-full">
          {ready ? <ShareBanner /> : null}
          {ready ? (
            <div className="agent-share-stream" data-testid="agent-share-stream">
              <div className="agent-stream-rows agent-share-rows">
                {turns.map((turn) => (
                  <div key={turn.id} className="pb-4">
                    <AgentTurnItem turn={turn} />
                  </div>
                ))}
              </div>
            </div>
          ) : (
            <ShareStatus loading={loading} />
          )}
          <ShareCtaBar state={loading ? "loading" : invalid ? "invalid" : isAuthenticated ? "authed" : "anonymous"} onEnterChat={enterChat} />
        </div>
      </div>
    </FeedShell>
  );
}

/** 快照消息条目 → agent 时间线消息视图模型（只读投影） */
function toTimelineMessages(messages: AgentShareSnapshotItem[]): AgentMessage[] {
  return messages.map((message, index) => {
    if (message.role !== "assistant") {
      return {
        id: `m-${index}`,
        role: "user",
        content: message.content,
        createdAt: message.createTime ?? undefined
      };
    }
    // assistant：合成 answer 块承载正文与 sources——与分享者所见同款 markdown
    // 渲染路径；v2 sources 缺省（v1 旧快照）即不渲染来源徽章，优雅降级
    const sources = (message.sources ?? undefined) as SourceRef[] | undefined;
    return {
      id: `m-${index}`,
      role: "assistant",
      content: message.content,
      createdAt: message.createTime ?? undefined,
      status: "done",
      blocks: [
        {
          id: 1,
          kind: "answer",
          at: toBlockHms(message.createTime),
          text: message.content,
          sources
        }
      ]
    };
  });
}

/** 壳内细横幅：非官方与时效提示（收敛自 #82 独立简版页的大块提示） */
function ShareBanner() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="agent-share-banner" role="note">
      {zh
        ? "AI 生成对话快照 · 非官方且可能过期，请以 PolyU 官方信息为准"
        : "AI-generated conversation snapshot · Unofficial and possibly outdated; refer to official PolyU pages"}
    </div>
  );
}

/** 加载/无效态的居中占位（无效态在壳内呈现统一语义，可探测性同 #82） */
function ShareStatus({ loading }: { loading: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  if (loading) {
    return (
      <div className="agent-share-status">
        <p className="text-[13px] text-[var(--agent-muted)]">{zh ? "加载中…" : "Loading…"}</p>
      </div>
    );
  }
  return (
    <div className="agent-share-status">
      <p className="text-[15px] font-semibold text-[var(--agent-ink)]">
        {zh ? "分享链接无效或已撤销" : "This share link is invalid or has been revoked"}
      </p>
      <p className="text-[12.5px] text-[var(--agent-muted)]">
        {zh ? "链接可能已过期或被分享者撤销" : "The link may have expired or been revoked by its owner"}
      </p>
      <Link className="agent-share-cta-secondary" to="/">
        {zh ? "逛逛资讯 · Browse news" : "Browse news"}
      </Link>
    </div>
  );
}

/** 底部 CTA 条三分支：未登录双钮 / 已登录单钮 / 加载与无效态（#91 拍板形态） */
function ShareCtaBar({
  state,
  onEnterChat
}: {
  state: "loading" | "invalid" | "anonymous" | "authed";
  onEnterChat: () => void;
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="agent-share-cta">
      {state === "loading" ? (
        <span className="text-[12.5px] text-[var(--agent-faint)]">{zh ? "加载中…" : "Loading…"}</span>
      ) : state === "invalid" ? (
        <Link className="agent-share-cta-secondary" to="/">
          {zh ? "去首页 · Home" : "Home"}
        </Link>
      ) : state === "anonymous" ? (
        <>
          <Link className="agent-share-cta-primary" to="/login">
            {zh ? "登录后继续提问" : "Sign in to ask more"}
          </Link>
          <button type="button" className="agent-share-cta-secondary" onClick={onEnterChat}>
            {zh ? "以游客身份开始新对话" : "Start a new chat as guest"}
          </button>
        </>
      ) : (
        <Link className="agent-share-cta-primary" to="/chat">
          {zh ? "回到我的对话" : "Back to my chats"}
        </Link>
      )}
    </div>
  );
}
