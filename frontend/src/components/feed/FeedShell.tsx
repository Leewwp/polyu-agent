import { useMemo, useState } from "react";
import type { ReactNode } from "react";

import { EngineBadge } from "./EngineBadge";
import { FeedSidebar } from "./FeedSidebar";
import { MobileTabbar } from "./MobileTabbar";
import { UserMenu } from "./UserMenu";
import { FeedLangProvider, useFeedLang } from "./feedLang";
import { feedDateLabels } from "@/services/newsMapping";
import { AgentSessionShareButton } from "@/components/agent/AgentSessionShareButton";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useChatStore } from "@/stores/chatStore";
import { useEngineStore } from "@/stores/engineStore";
import { cn } from "@/lib/utils";

/**
 * 资讯流壳（原型 .app：侧栏 + 主区）：
 * - 桌面 = 300px 粘性侧栏 + 主区（顶栏标题/日期/语言 pill/身份区）；
 * - 移动端（860px 断点）= 顶栏换菜单钮形态、侧栏转抽屉、底部 tab + FAB；
 * - 语言 Provider 挂本壳顶层（数据级双语）；ChatPage/AgentChatPage
 *   外层壳也从 MainLayout/AgentLayout 换成本壳。
 * - 2026-09-13：顶栏日期改 HKT 实时值；登录态身份区/登出
 *   一并补齐；fluid 聊天档标题位显示当前会话标题+引擎徽标。
 */

export interface FeedShellProps {
  /** 主区顶栏标题（当前视图名；HotRankPage/TopicsPage 等传入各自标题） */
  title: { zh: string; en: string };
  children: ReactNode;
  /**
   * 聊天档：ChatPage/AgentChatPage 换壳用——顶栏横贯全宽、
   * 主区满高无 760px 限宽（消息流滚动+输入条贴底由页面主体自理）、
   * 移动端底部 tab 不渲染（聊天页底部为输入条，tab 由顶栏菜单钮替代回资讯）。
   */
  fluid?: boolean;
  /**
   * 分享视图态（issue #91，/share/c/:token 壳化）：presence 即生效——
   * 顶栏标题优先用快照标题（null 回落页面名）、不挂「分享对话」钮；
   * 侧栏「最近对话」区换只读单条目并守零网络请求。title=null 表示
   * 加载中/无效态（无被分享会话可示）。
   */
  shareView?: { title: string | null };
}

export function FeedShell({ title, children, fluid = false, shareView }: FeedShellProps) {
  return (
    <FeedLangProvider>
      <FeedShellInner title={title} fluid={fluid} shareView={shareView}>
        {children}
      </FeedShellInner>
    </FeedLangProvider>
  );
}

function LangPill() {
  const { lang, setLang } = useFeedLang();
  return (
    <div className="flex overflow-hidden rounded-full border border-[var(--feed-line)] bg-white text-[12.5px] font-semibold">
      <button
        type="button"
        aria-pressed={lang === "zh"}
        className={cn("px-3 py-[5px]", lang === "zh" ? "bg-[var(--polyu-red)] text-white" : "text-[var(--feed-text-tertiary)]")}
        onClick={() => setLang("zh")}
      >
        中
      </button>
      <button
        type="button"
        aria-pressed={lang === "en"}
        className={cn("px-3 py-[5px]", lang === "en" ? "bg-[var(--polyu-red)] text-white" : "text-[var(--feed-text-tertiary)]")}
        onClick={() => setLang("en")}
      >
        EN
      </button>
    </div>
  );
}

/**
 * fluid 聊天档的当前会话标题：读当前引擎 store 的 currentSession 对应标题，
 * 无会话回落 null（由调用方回落页面名）。上游 Header.tsx:42 同语义（`currentSession?.title
 * || "新对话"`）；两 store 均零请求订阅，公开页读取无副作用。
 */
function useChatSessionTitle(): string | null {
  const engineType = useEngineStore((state) => state.engineType);
  const wfCurrentId = useChatStore((state) => state.currentSessionId);
  const wfSessions = useChatStore((state) => state.sessions);
  const agCurrentId = useAgentChatStore((state) => state.currentSessionId);
  const agSessions = useAgentChatStore((state) => state.sessions);

  const currentId = engineType === "agent" ? agCurrentId : wfCurrentId;
  const sessions = engineType === "agent" ? agSessions : wfSessions;
  if (!currentId) {
    return null;
  }
  return sessions.find((session) => session.id === currentId)?.title ?? null;
}

/** 桌面顶栏（content 档嵌主区列顶、fluid 档横贯；全站检索入口在 FeedPage chips 行，顶栏不设搜索框） */
function DesktopTopbar({
  title,
  fluid,
  shareView
}: {
  title: { zh: string; en: string };
  fluid: boolean;
  shareView?: { title: string | null };
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const engineType = useEngineStore((state) => state.engineType);
  // 实时 HKT 日期（渲染时计算；跨零点长驻由下一次渲染自然纠正）
  const dateLabels = useMemo(() => feedDateLabels(new Date(), lang), [lang]);
  const chatTitle = useChatSessionTitle();
  // 分享视图：标题优先用快照标题（store 里可能残留访客自己会话的标题，不可采信）
  const fluidTitle = shareView ? shareView.title : chatTitle;
  return (
    <header className="sticky top-0 z-30 hidden items-center gap-3.5 border-b border-[var(--feed-line-soft)] bg-[rgba(246,246,247,0.92)] px-7 py-3 backdrop-blur min-[861px]:flex">
      <div className="min-w-0">
        <div className="truncate text-[17px] font-bold">
          {(fluid && fluidTitle) || (zh ? title.zh : title.en)}
        </div>
        <div className="text-[12.5px] text-[var(--feed-text-tertiary)]">{dateLabels.long}</div>
      </div>
      {fluid && !shareView && engineType === "agent" && <EngineBadge />}
      <div className="ml-auto flex items-center gap-2.5">
        <LangPill />
        {!shareView && <AgentSessionShareButton />}
        <UserMenu />
      </div>
    </header>
  );
}

/**
 * 移动端顶栏（菜单钮开侧栏抽屉；品牌字统一 PolyUGuide）。
 * 补 LangPill——卡片级语言小钮移除后，移动端语言入口收归本顶栏
 * （与桌面顶栏同一全局值，feed/hot/topics/detail 五页共用）。
 * 补身份入口（登录钮/头像下拉，与桌面同一 UserMenu mobile 档）。
 */
function MobileTopbar({ onOpenMenu, shareView }: { onOpenMenu: () => void; shareView?: { title: string | null } }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const dateLabels = useMemo(() => feedDateLabels(new Date(), lang), [lang]);
  return (
    <div className="sticky top-0 z-30 flex items-center gap-2.5 border-b border-[var(--feed-line-soft)] bg-[rgba(246,246,247,0.94)] px-3.5 py-[11px] backdrop-blur min-[861px]:hidden">
      <button
        type="button"
        aria-label={zh ? "打开菜单" : "Open menu"}
        className="flex h-8 w-8 flex-none items-center justify-center rounded-lg border border-[var(--feed-line)] bg-white text-[var(--feed-text-secondary)]"
        onClick={onOpenMenu}
      >
        ☰
      </button>
      <div className="text-[15px] font-extrabold">
        PolyU<i className="not-italic text-[var(--polyu-red)]">Guide</i>
      </div>
      <div className="ml-auto text-[11.5px] text-[var(--feed-text-tertiary)]">{dateLabels.short}</div>
      <div className="flex flex-none">
        {!shareView && <AgentSessionShareButton />}
        <LangPill />
      </div>
      <UserMenu variant="mobile" />
    </div>
  );
}

function FeedShellInner({ title, children, fluid, shareView }: FeedShellProps) {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  if (fluid) {
    // 聊天档：满高外壳，主区无 max-w 限宽，底部 tab 不渲染（页面主体输入条贴底）。
    // 2026-09-12 修复：顶栏从横贯全宽改为嵌右列——与资讯页同构（侧栏顶到页顶，
    // 顶栏只盖主区），此前侧栏被顶栏压在下方与全站形态不一致。
    return (
      <div className="flex h-screen bg-[var(--feed-bg)] text-[var(--feed-text-primary)]">
        <FeedSidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} fullHeight shareView={shareView} />
        <div className="flex min-w-0 flex-1 flex-col">
          <DesktopTopbar title={title} fluid shareView={shareView} />
          <MobileTopbar onOpenMenu={() => setSidebarOpen(true)} shareView={shareView} />
          <main className="min-h-0 min-w-0 flex-1 overflow-hidden">{children}</main>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[var(--feed-bg)] text-[var(--feed-text-primary)]">
      <div className="flex">
        <FeedSidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />
        <div className="min-w-0 flex-1">
          <DesktopTopbar title={title} fluid={false} />
          <MobileTopbar onOpenMenu={() => setSidebarOpen(true)} />
          {/* 2026-09-12 修复：限宽 760→1080——内容占页面更多空间；
              1080 仍守住中文阅读舒适行宽，卡片/热点卡自适应变宽 */}
          <main className="mx-auto w-full max-w-[1080px] px-7 pb-10 pt-[22px] max-[860px]:px-3.5 max-[860px]:pb-[110px] max-[860px]:pt-3">
            {children}
          </main>
        </div>
      </div>
      <MobileTabbar />
    </div>
  );
}
