import { useRef, useState } from "react";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { Link, useLocation, useSearchParams } from "react-router-dom";

import { FeedbackDialog } from "@/components/site/FeedbackDialog";
import { useEnterChat } from "@/hooks/useEnterChat";
import { cn } from "@/lib/utils";
import { withQuery } from "./FeedSidebar";
import { useFeedLang } from "./feedLang";

/**
 * 移动端底部 4 tab（原型 .tabbar：精选/全部/对话/更多）+ 右下悬浮「问 Agent」钮（.fab）。
 * - tab 高亮按当前页面判定（63a Q4，不依赖来源历史）：精选/全部仅在首页对应
 *   版式点亮（view 为版式参数非无关 query）；热点榜/主题（/topics 与 /topics/:slug
 *   详情同归主题）/日报/关键日期/关于归属「更多」，进入即点亮；资讯详情
 *   /news/:id 不强行点亮任何 tab（保留返回资讯流入口）；栏目归属按 pathname
 *   判定忽略 query；
 * - 「对话」位与 FAB 走 useEnterChat 游客直通（已登录直达 /chat，未登录铸游客号）；
 * - 「更多」面板复用 Radix Dialog（bottom sheet 形态，#230 键盘与焦点合同）：
 *   触发器自带 aria-expanded/aria-controls，打开初始焦点落面板容器（Tab 自
 *   首项起遍历）、Escape 可关、关闭后焦点回触发器、modal 背景不可误操作
 *   （遮罩点击关闭）；9 项分「栏目/站点」两组；短高度/横屏视口限高+内部
 *   滚动（max-h dvh，旧引擎 @supports 回落 vh——.feed-fluid-shell 判例，
 *   压缩器会折叠同规则双声明）；不含语言切换（#227 语言状态在应用根，
 *   顶栏 pill 切换）；
 * - 860px 断点以下才显示（原型断点；聊天档 fluid 壳不渲染本组件——聊天页
 *   底部为输入条，不新增底部 tab）。
 */

/** 「更多」归属栏目（pathname 前缀判定；/topics 含 :slug 详情） */
const MORE_SECTION_PATHS = ["/hot", "/topics", "/daily", "/key-dates", "/about"] as const;

function isMoreSection(pathname: string): boolean {
  return MORE_SECTION_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

function tabClass(active: boolean): string {
  return cn(
    "flex flex-1 cursor-pointer flex-col items-center gap-0.5 py-[3px] text-[10.5px] text-[var(--feed-text-tertiary)]",
    active && "font-bold text-[var(--polyu-red)]"
  );
}

/** 面板条目（栏目/关于/法务/反馈共用）：44px 触控高度（63a Q5 目标），EN 长标签可换行不出屏 */
function sheetItemClass(): string {
  return (
    "flex min-h-[44px] items-center justify-center rounded-[10px] border border-[var(--feed-line)] " +
    "bg-white px-1.5 py-2.5 text-center text-[12.5px] leading-snug text-[var(--feed-text-secondary)] " +
    "active:bg-[var(--polyu-red-50)]"
  );
}

function sheetGroupTitleClass(): string {
  return "px-0.5 text-[11px] font-semibold tracking-[0.5px] text-[var(--feed-text-tertiary)]";
}

export function MobileTabbar() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";

  const { pathname } = useLocation();
  const [searchParams] = useSearchParams();
  // 版式判定加首页闸（63a Q4 修复：此前栏目页 isAllView 恒 false 会误点亮「精选」）
  const isHome = pathname === "/";
  const isAllView = isHome && searchParams.get("view") === "all";
  const isFeaturedView = isHome && !isAllView;
  const moreLit = isMoreSection(pathname);

  const [moreOpen, setMoreOpen] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  const sheetRef = useRef<HTMLDivElement | null>(null);

  // 游客直通：同 FeedSidebar 新对话一条 enterChat() 路径
  const enterChat = useEnterChat();

  const closeMore = () => setMoreOpen(false);

  return (
    <>
      <nav
        aria-label={zh ? "底部导航" : "Bottom navigation"}
        className="fixed inset-x-0 bottom-0 z-50 hidden max-[860px]:flex border-t border-[var(--feed-line-soft)] bg-[rgba(255,255,255,0.96)] pt-1.5 pb-[max(6px,env(safe-area-inset-bottom))] backdrop-blur"
      >
        {/* 版式切换剥检索三参（doc 32 决策二 B 案，同 FeedSidebar withQuery 口径） */}
        <Link
          to={withQuery("/", searchParams)}
          aria-current={isFeaturedView ? "page" : undefined}
          className={tabClass(isFeaturedView)}
          onClick={closeMore}
        >
          <span className="text-[19px] leading-none">⚡</span>
          <span>{zh ? "精选" : "Featured"}</span>
        </Link>
        <Link
          to={withQuery("/?view=all", searchParams)}
          aria-current={isAllView ? "page" : undefined}
          className={tabClass(isAllView)}
          onClick={closeMore}
        >
          <span className="text-[19px] leading-none">📰</span>
          <span>{zh ? "全部" : "All"}</span>
        </Link>
        <button type="button" className={tabClass(false)} onClick={enterChat}>
          <span className="text-[19px] leading-none">💬</span>
          <span>{zh ? "对话" : "Agent"}</span>
        </button>
        {/* 「更多」= Radix Dialog 触发器（aria-expanded/aria-controls 由 primitive 提供）；
            点亮 = 栏目归属（热点/主题/日报/关键日期/关于）或面板展开中 */}
        <DialogPrimitive.Root open={moreOpen} onOpenChange={setMoreOpen}>
          <DialogPrimitive.Trigger asChild>
            <button type="button" className={tabClass(moreLit || moreOpen)}>
              <span className="text-[19px] leading-none">⋯</span>
              <span>{zh ? "更多" : "More"}</span>
            </button>
          </DialogPrimitive.Trigger>
          <DialogPrimitive.Portal>
            <DialogPrimitive.Overlay className="fixed inset-0 z-[70] bg-[rgba(24,24,27,0.35)]" />
            <DialogPrimitive.Content
              ref={sheetRef}
              tabIndex={-1}
              className={
                "fixed inset-x-0 bottom-0 z-[71] mx-auto flex w-full max-w-[1080px] flex-col gap-2.5 " +
                "overflow-y-auto overscroll-contain rounded-t-[18px] bg-white px-[18px] pt-2 " +
                "pb-[max(16px,env(safe-area-inset-bottom))] shadow-[0_-10px_36px_rgba(24,24,27,0.16)] outline-none " +
                "max-h-[82vh] supports-[height:1dvh]:max-h-[82dvh]"
              }
              aria-describedby={undefined}
              onOpenAutoFocus={(event) => {
                // 初始焦点落面板容器（tabIndex=-1，导航 sheet 惯例）：Radix 默认
                // 会滤掉链接聚焦首个非链接可聚焦元素（本面板为居中的「反馈」钮，
                // 语义弱）；聚焦容器后 Tab 自然自首项「热点榜」起遍历
                event.preventDefault();
                sheetRef.current?.focus({ preventScroll: true });
              }}
            >
              <div className="mx-auto h-1 w-9 flex-none rounded-full bg-[var(--feed-line)]" aria-hidden="true" />
              <DialogPrimitive.Title className="sr-only">{zh ? "更多" : "More"}</DialogPrimitive.Title>

              {/* 组一「栏目」：新增四栏目（63a Q4） */}
              <section aria-label={zh ? "栏目" : "Sections"}>
                <div className={sheetGroupTitleClass()}>{zh ? "栏目" : "SECTIONS"}</div>
                <div className="mt-1 grid grid-cols-2 gap-2">
                  <Link to="/hot" onClick={closeMore} className={sheetItemClass()}>
                    🔥 {zh ? "热点榜" : "Trending"}
                  </Link>
                  <Link to="/topics" onClick={closeMore} className={sheetItemClass()}>
                    🧭 {zh ? "主题" : "Topics"}
                  </Link>
                  <Link to="/daily" onClick={closeMore} className={sheetItemClass()}>
                    📰 {zh ? "日报" : "Daily"}
                  </Link>
                  <Link to="/key-dates" onClick={closeMore} className={sheetItemClass()}>
                    📅 {zh ? "关键日期" : "Key dates"}
                  </Link>
                </div>
              </section>

              {/* 组二「站点」：现状 5 项（关于+法务三链+反馈，doc 25 三入口之一） */}
              <section aria-label={zh ? "站点" : "Site"}>
                <div className={sheetGroupTitleClass()}>{zh ? "站点" : "SITE"}</div>
                <div className="mt-1 grid grid-cols-2 gap-2">
                  <Link to="/about" onClick={closeMore} className={sheetItemClass()}>
                    💡 {zh ? "关于" : "About"}
                  </Link>
                  <button
                    type="button"
                    onClick={() => {
                      closeMore();
                      setFeedbackOpen(true);
                    }}
                    className={sheetItemClass()}
                  >
                    💬 {zh ? "反馈" : "Feedback"}
                  </button>
                </div>
                <div className="mt-2 grid grid-cols-3 gap-2">
                  <Link to="/privacy" onClick={closeMore} className={sheetItemClass()}>
                    🔒 {zh ? "隐私声明" : "Privacy Notice"}
                  </Link>
                  <Link to="/terms" onClick={closeMore} className={sheetItemClass()}>
                    📄 {zh ? "服务条款" : "Terms"}
                  </Link>
                  <Link to="/disclaimer" onClick={closeMore} className={sheetItemClass()}>
                    ℹ️ {zh ? "非官方声明" : "Disclaimer"}
                  </Link>
                </div>
              </section>
            </DialogPrimitive.Content>
          </DialogPrimitive.Portal>
        </DialogPrimitive.Root>
      </nav>

      <button
        type="button"
        className="fixed bottom-[78px] right-3.5 z-[52] hidden max-[860px]:flex items-center gap-[7px] rounded-full bg-[var(--polyu-red)] px-[18px] py-[11px] min-h-[44px] text-[13.5px] font-bold text-white shadow-[0_6px_18px_rgba(166,25,46,0.4)] active:scale-95"
        onClick={enterChat}
      >
        💬 <span>{zh ? "问 Agent" : "Ask Agent"}</span>
      </button>

      <FeedbackDialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />
    </>
  );
}
