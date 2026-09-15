import { useEffect } from "react";
import { Link, useLocation, useSearchParams } from "react-router-dom";

import { GuestStatusBadge } from "@/components/chat/GuestStatusBadge";
import { useEnterChat } from "@/hooks/useEnterChat";
import { cn } from "@/lib/utils";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import { useEngineStore } from "@/stores/engineStore";
import { useFeedLang } from "./feedLang";

/**
 * 资讯流侧栏（原型 .sidebar 全新实现——上游 Sidebar.tsx 不动）：
 * 品牌 → 新对话 → 内容导航四条目（精选·全部资讯·热点榜·主题）→ 最近对话 → 底部游客卡。
 * - 「新对话」走 useEnterChat 游客直通（已登录直达 /chat，未登录铸游客号）；
 *   「热点榜」为真实路由（/hot）；「主题」为真实路由（/topics 主题地图 +
 *   /topics/:slug 详情，含 active 态）；
 * - 游客卡复用 GuestStatusBadge 呈现真实余量；匿名态零网络请求
 *   （公开页红线：不触发任何 /auth 探测；例外仅一条——已登录用户探测引擎档位
 *   /rag/settings/engine，登录态可读、匿名零请求口径不变）；
 * - 已登录用户按登录态渲染真实最近会话；会话源随引擎档位切换
 *   （agent→agentChatStore / workflow→chatStore；/chat/:id 两档同路由）。
 */

const RECENT_SESSION_LIMIT = 3;

function navItemClass(active: boolean): string {
  return cn(
    "flex cursor-pointer items-center gap-2.5 rounded-[9px] px-2.5 py-[7.5px] text-[13.5px] transition-colors",
    active
      ? "bg-[var(--polyu-red-50)] font-semibold text-[var(--polyu-red-dark)]"
      : "text-[var(--feed-text-secondary)] hover:bg-[var(--feed-bg)]"
  );
}

function navTitleClass(): string {
  return "mb-1 mt-0.5 px-2.5 text-[11.5px] font-semibold tracking-[0.5px] text-[var(--feed-text-tertiary)]";
}

function navEmojiClass(): string {
  return "w-[18px] flex-none text-center text-sm";
}

function chatItemClass(): string {
  return "block truncate rounded-[9px] px-2.5 py-[7px] text-[13px] text-[var(--feed-text-secondary)] hover:bg-[var(--feed-bg)]";
}

export function FeedSidebar({
  open,
  onClose,
  fullHeight = false
}: {
  open: boolean;
  onClose: () => void;
  /** 聊天档：桌面侧栏高度跟随满高外壳（fluid 主区）而非 100vh——顶栏占了一行 */
  fullHeight?: boolean;
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";

  const [searchParams] = useSearchParams();
  const isAllView = searchParams.get("view") === "all";
  // 精选=根路径且非全部资讯态（主题视图下不点亮）；主题导航在 /topics 与
  // /topics/:slug 均高亮（原型 showView('topic') 也点亮 navTopics）；
  // 热点榜高亮 /hot
  const location = useLocation();
  const isFeedHome = location.pathname === "/" && !isAllView;
  const isTopicsView = location.pathname.startsWith("/topics");
  const isHotView = location.pathname.startsWith("/hot");

  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  // 会话源随引擎档位——纯读取零请求；engineType 在用户进 /chat（EngineGate）
  // 或下方已登录分支探测后生效，未探测时回落 workflow store（现行为，保守不空转）
  const engineType = useEngineStore((state) => state.engineType);
  const engineError = useEngineStore((state) => state.error);
  const initializeEngine = useEngineStore((state) => state.initialize);
  const isAgentEngine = engineType === "agent";
  // 引擎已知（或探测失败按 workflow 回落展示）后才拉会话，防 agent 档用户先看到 workflow 空
  const engineResolved = engineType !== null || engineError !== null;
  const wfSessions = useChatStore((state) => state.sessions);
  const wfSessionsLoaded = useChatStore((state) => state.sessionsLoaded);
  const wfFetchSessions = useChatStore((state) => state.fetchSessions);
  const agSessions = useAgentChatStore((state) => state.sessions);
  const agSessionsLoaded = useAgentChatStore((state) => state.sessionsLoaded);
  const agLoadSessions = useAgentChatStore((state) => state.loadSessions);
  const sessions = isAgentEngine ? agSessions : wfSessions;
  const sessionsLoaded = isAgentEngine ? agSessionsLoaded : wfSessionsLoaded;

  useEffect(() => {
    // 已登录用户预探测引擎档位（一次 GET /rag/settings/engine，登录态可读）：
    // 未探测时侧栏只能展示 workflow 会话，agent 档用户会被误导——匿名态零请求红线不变
    if (isAuthenticated && !engineType) {
      initializeEngine().catch(() => null);
    }
  }, [isAuthenticated, engineType, initializeEngine]);

  // 已登录时拉取当前引擎的真实会话；匿名零请求（公开页红线）
  useEffect(() => {
    if (isAuthenticated && engineResolved && !sessionsLoaded) {
      const fetch = isAgentEngine ? agLoadSessions : wfFetchSessions;
      fetch().catch(() => null);
    }
  }, [isAuthenticated, engineResolved, sessionsLoaded, isAgentEngine, agLoadSessions, wfFetchSessions]);

  const role = user?.role;
  const isGuest = role === "guest";
  const showGuestCard = !isAuthenticated || isGuest;

  // 游客直通：已登录直达 /chat，未登录铸游客号（失败引导登录，见 useEnterChat）
  // fresh=true（2026-09-12 修复）：「新对话」强制落全新会话，不续最近会话
  const enterChat = useEnterChat({ fresh: true });

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 z-[55] hidden bg-[rgba(0,0,0,0.35)] max-[860px]:block"
          onClick={onClose}
          aria-hidden="true"
        />
      )}
      <aside
        className={cn(
          "flex w-[300px] flex-none flex-col gap-3.5 overflow-y-auto border-r border-[var(--feed-line-soft)] bg-[var(--feed-card)] px-3 pb-3 pt-3.5",
          // 聊天档满高外壳内 h-screen 会溢出裁底（顶栏占一行），改随父容器高度
          fullHeight ? "min-[861px]:sticky min-[861px]:top-0 min-[861px]:h-full" : "min-[861px]:sticky min-[861px]:top-0 min-[861px]:h-screen",
          "max-[860px]:fixed max-[860px]:left-0 max-[860px]:top-0 max-[860px]:z-[60] max-[860px]:h-screen max-[860px]:transition-transform max-[860px]:duration-200",
          open ? "max-[860px]:translate-x-0 max-[860px]:shadow-2xl" : "max-[860px]:-translate-x-[102%]"
        )}
      >
        <div className="flex items-center gap-2.5 px-1 py-0.5">
          {/* 品牌位「理」文字块 → 新图形标（96px 透明 PNG 裁自 lockup 左侧
              icon，/favicon 现用图形同款speech-bubble shield） */}
          <img
            src="/brand/icon.png"
            alt="PolyUGuide"
            className="h-[34px] w-[34px] flex-none rounded-[9px] object-contain"
          />
          <div className="text-[15.5px] font-bold leading-[1.2]">
            PolyUGuide
            <span className="block text-[11px] font-medium tracking-[0.4px] text-[var(--feed-text-tertiary)]">
              {zh ? "理大资讯与智能问答" : "PolyU News & AI Guide"}
            </span>
          </div>
          <button
            type="button"
            title={zh ? "收起" : "Collapse"}
            aria-label={zh ? "收起侧栏" : "Collapse sidebar"}
            // 2026-09-12 修复：桌面档隐藏——桌面侧栏常驻、onClose 无收起语义，
            // 点击无效果成死钮；移动抽屉档保留作关闭钮
            className="ml-auto flex h-7 w-7 items-center justify-center rounded-[7px] text-[15px] text-[var(--feed-text-tertiary)] hover:bg-[var(--feed-bg)] hover:text-[var(--feed-text-primary)] max-[860px]:flex min-[861px]:hidden"
            onClick={onClose}
          >
            ◧
          </button>
        </div>

        <button
          type="button"
          className="flex items-center gap-2.5 rounded-xl bg-[var(--polyu-red)] px-3.5 py-2.5 font-semibold text-white shadow-[0_2px_8px_rgba(166,25,46,0.28)] transition-colors hover:bg-[var(--polyu-red-dark)]"
          onClick={() => {
            onClose();
            enterChat();
          }}
        >
          <svg
            className="flex-none"
            width="15"
            height="15"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.4"
            strokeLinecap="round"
          >
            <path d="M12 5v14M5 12h14" />
          </svg>
          {zh ? "新对话" : "New chat"}
        </button>

        <nav className="flex flex-col gap-0.5" aria-label={zh ? "内容导航" : "Content navigation"}>
          <div className={navTitleClass()}>{zh ? "内容" : "CONTENT"}</div>
          <Link to="/" className={navItemClass(isFeedHome)} onClick={onClose}>
            <span className={navEmojiClass()}>⚡</span>
            {zh ? "精选" : "Featured"}
          </Link>
          <Link to="/?view=all" className={navItemClass(isAllView)} onClick={onClose}>
            <span className={navEmojiClass()}>📰</span>
            {zh ? "全部资讯" : "All news"}
          </Link>
          {/* 已接线：热点榜页 /hot（真实路由，active 判定照主题模式） */}
          <Link to="/hot" className={navItemClass(isHotView)} onClick={onClose}>
            <span className={navEmojiClass()}>🔥</span>
            {zh ? "热点榜" : "Trending"}
          </Link>
          {/* 已接线：主题地图/主题详情两页落地，真实路由 */}
          <Link to="/topics" className={navItemClass(isTopicsView)} onClick={onClose}>
            <span className={navEmojiClass()}>🧭</span>
            {zh ? "主题" : "Topics"}
          </Link>
        </nav>

        <div className="flex min-h-0 flex-col gap-0.5">
          <div className={navTitleClass()}>{zh ? "最近对话" : "RECENT CHATS"}</div>
          {sessions.slice(0, RECENT_SESSION_LIMIT).map((session) => (
            <Link
              key={session.id}
              to={`/chat/${session.id}`}
              className={chatItemClass()}
              title={session.title}
              onClick={onClose}
            >
              {session.title}
            </Link>
          ))}
          {!isAuthenticated && (
            <div className="px-2.5 pt-0.5 text-[11.5px] text-[var(--feed-text-tertiary)]">
              {zh ? "登录后可同步全部历史对话" : "Sign in to sync full history"}
            </div>
          )}
        </div>

        {showGuestCard && (
          <div className="mt-auto rounded-xl border border-[var(--feed-line)] bg-gradient-to-b from-white to-[#FDF7F8] p-3">
            {isGuest ? (
              <GuestStatusBadge />
            ) : (
              <div className="flex items-center gap-2 px-0.5 py-0.5 text-[12.5px] text-[var(--feed-text-secondary)]">
                👤 {zh ? "游客身份 · 每日 3 次免登录 Agent 对话" : "Guest · 3 free Agent chats per day"}
              </div>
            )}
            <div className="mt-2.5 flex gap-2">
              <Link
                to="/login"
                className="flex-1 rounded-lg bg-[var(--polyu-red)] py-[6.5px] text-center text-[13px] font-semibold text-white transition-colors hover:bg-[var(--polyu-red-dark)]"
              >
                {zh ? "登录" : "Sign in"}
              </Link>
              {!isGuest && (
                <Link
                  to="/register"
                  className="flex-1 rounded-lg border border-[var(--polyu-red)] bg-white py-[6.5px] text-center text-[13px] font-semibold text-[var(--polyu-red)] transition-colors hover:bg-[var(--polyu-red-50)]"
                >
                  {zh ? "注册" : "Sign up"}
                </Link>
              )}
            </div>
          </div>
        )}
      </aside>
    </>
  );
}
