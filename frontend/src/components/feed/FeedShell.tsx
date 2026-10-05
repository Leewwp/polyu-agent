import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";

import { FeedSidebar } from "./FeedSidebar";
import { MobileTabbar } from "./MobileTabbar";
import { UserMenu } from "./UserMenu";
import { useFeedLang } from "./feedLang";
import { usePageTitle } from "@/hooks/usePageTitle";
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
 * - 语言 Provider 已提根（#227）：本壳只消费应用根 FeedLangProvider 的全局
 *   语言，壳内不再挂第二层独立语言状态；ChatPage/AgentChatPage
 *   外层壳也从 MainLayout/AgentLayout 换成本壳。
 * - 2026-09-13：顶栏日期改 HKT 实时值；登录态身份区/登出
 *   一并补齐；fluid 聊天档标题位显示当前会话标题（#233 信息精简：
 *   内部引擎/模型徽章移除——AI 提示与来源证据不受影响）。
 * - #231 标题单源与页面级 h1 均落本壳：document.title 消费 title prop 与
 *   shareView/fluid 标题优先值（与顶栏同一次序）全局一处驱动；正文无内容头
 *   的栏目页经 pageHeading 由壳渲染 h1（桌面 sr-only 去重复视觉页名、移动显示）。
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
  /**
   * 页面级 h1（#231/N2）：页面正文没有自己的内容头时由壳渲染标题为 h1——
   * 首页/热点/日报/关键日期/关于等；桌面 sr-only（clip 法，不摘出无障碍树）
   * 去与顶栏重复的视觉页名，移动端显示为正文页名。默认 false：正文已有
   * 页面级头（主题地图/主题详情的 h1、资讯详情的新闻标题 h1）的页面不开，
   * 避免双 h1/同屏重复视觉页名。做在壳上而非页面正文——日报页 #241 将整页
   * 替换，页面正文里的 h1 会被替换丢掉，壳上的机制自动继承。
   */
  pageHeading?: boolean;
}

export function FeedShell({ title, children, fluid = false, shareView, pageHeading = false }: FeedShellProps) {
  // 语言由应用根 FeedLangProvider 供给（#227 提根），本壳不再包裹 Provider——
  // 壳外轻量页（404/法务/登录系等）与壳内共享同一全局语言状态。
  return (
    <FeedShellInner title={title} fluid={fluid} shareView={shareView} pageHeading={pageHeading}>
      {children}
    </FeedShellInner>
  );
}

function LangPill() {
  const { lang, setLang } = useFeedLang();
  // #232 触控清单：中/EN 各为独立导航入口，移动端保 44 实高（视觉字号不变）；
  // 桌面与旁边 UserMenu 身份 chip 同高——chip 外高=26px 头像+py-1×2+边框×2=36px，
  // 内钮 34+容器边框 2 同为 36，顶栏身份区不再被语言钮撑高
  return (
    <div className="flex flex-none whitespace-nowrap overflow-hidden rounded-full border border-[var(--feed-line)] bg-white text-[12.5px] font-semibold">
      <button
        type="button"
        aria-pressed={lang === "zh"}
        className={cn(
          "flex h-11 min-[861px]:h-[34px] items-center px-3",
          lang === "zh" ? "bg-[var(--polyu-red)] text-white" : "text-[var(--feed-text-tertiary)]"
        )}
        onClick={() => setLang("zh")}
      >
        中
      </button>
      <button
        type="button"
        aria-pressed={lang === "en"}
        className={cn(
          "flex h-11 min-[861px]:h-[34px] items-center px-3",
          lang === "en" ? "bg-[var(--polyu-red)] text-white" : "text-[var(--feed-text-tertiary)]"
        )}
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
 * #136 壳地基：去短日期——320px 下五元素（菜单/品牌/分享/语言/登录）全部可读可点，
 * 身份区各钮 flex-none+whitespace-nowrap（「登录」不被压成竖排、IA 不退化为图标），
 * 品牌区 min-w-0 承担压缩；400px 以下仅再收紧 gap/padding（spacing 微调档）。
 * LangPill 与桌面顶栏同一全局值；身份入口=登录钮/头像下拉（UserMenu mobile 档）。
 */
function MobileTopbar({ onOpenMenu, shareView }: { onOpenMenu: () => void; shareView?: { title: string | null } }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="sticky top-0 z-30 flex items-center gap-2 border-b border-[var(--feed-line-soft)] bg-[rgba(246,246,247,0.94)] px-3 py-2 backdrop-blur min-[861px]:hidden max-[400px]:gap-1.5 max-[400px]:px-2.5">
      {/* #232 触控清单：菜单钮 32→44（实高实宽）；顶栏随高 py-[11px]→py-2（54→60px） */}
      <button
        type="button"
        aria-label={zh ? "打开菜单" : "Open menu"}
        className="flex h-11 w-11 flex-none items-center justify-center rounded-lg border border-[var(--feed-line)] bg-white text-[var(--feed-text-secondary)]"
        onClick={onOpenMenu}
      >
        ☰
      </button>
      <div className="min-w-0 text-[15px] font-extrabold">
        PolyU<i className="not-italic text-[var(--polyu-red)]">Guide</i>
      </div>
      <div className="ml-auto flex flex-none items-center gap-2">
        {!shareView && <AgentSessionShareButton />}
        <LangPill />
      </div>
      <UserMenu variant="mobile" />
    </div>
  );
}

function FeedShellInner({ title, children, fluid, shareView, pageHeading }: FeedShellProps) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  // #231 标题单源：document.title 与顶栏同一优先值（fluid 会话标题/分享快照
  // 标题优先，回落双语页面名），由 usePageTitle 统一汇聚成「页面名 · PolyUGuide」
  // ——随路由/语言/会话标题切换自然更新；详情覆写走 useDetailPageTitle 同一调用路径。
  const chatTitle = useChatSessionTitle();
  const fluidTitle = shareView ? shareView.title : chatTitle;
  usePageTitle((fluid && fluidTitle) || title);

  // #292：移动端会话抽屉（≤860px）为自研 aside+遮罩，无内建 Escape——这里补键盘
  // 关闭；桌面档（≥861px）菜单钮隐藏、sidebarOpen 恒 false，监听不会生效
  useEffect(() => {
    if (!sidebarOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setSidebarOpen(false);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [sidebarOpen]);

  // 页面级 h1（#231）：正文无内容头的栏目页由壳渲染；桌面 sr-only（clip，
  // 不摘出无障碍树）去与顶栏重复的视觉页名，移动端（≤860px）显示为正文页名。
  const heading = pageHeading ? (
    <h1 className="mb-[18px] text-[19px] font-extrabold min-[861px]:sr-only">{zh ? title.zh : title.en}</h1>
  ) : null;

  if (fluid) {
    // 聊天档：满高外壳，主区无 max-w 限宽，底部 tab 不渲染（页面主体输入条贴底）。
    // 2026-09-12 修复：顶栏从横贯全宽改为嵌右列——与资讯页同构（侧栏顶到页顶，
    // 顶栏只盖主区），此前侧栏被顶栏压在下方与全站形态不一致。
    // #136：h-screen 换 feed-fluid-shell（globals.css）——100dvh 跟随移动浏览器
    // 动态视口（地址栏收展/软键盘），旧引擎不识 dvh 行自动回落 100vh。
    return (
      <div className="feed-fluid-shell flex bg-[var(--feed-bg)] text-[var(--feed-text-primary)]">
        <FeedSidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} fullHeight shareView={shareView} />
        <div className="flex min-w-0 flex-1 flex-col">
          <DesktopTopbar title={title} fluid shareView={shareView} />
          <MobileTopbar onOpenMenu={() => setSidebarOpen(true)} shareView={shareView} />
          <main className="min-h-0 min-w-0 flex-1 overflow-hidden">
            {heading}
            {children}
          </main>
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
            {heading}
            {children}
          </main>
        </div>
      </div>
      <MobileTabbar />
    </div>
  );
}
