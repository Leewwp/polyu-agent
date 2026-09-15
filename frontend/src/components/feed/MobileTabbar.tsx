import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";

import { FeedbackDialog } from "@/components/site/FeedbackDialog";
import { useEnterChat } from "@/hooks/useEnterChat";
import { cn } from "@/lib/utils";
import { withQuery } from "./FeedSidebar";
import { useFeedLang } from "./feedLang";

/**
 * 移动端底部 4 tab（原型 .tabbar：精选/全部/对话/更多）+ 右下悬浮「问 Agent」钮（.fab）。
 * - 「对话」位与 FAB 走 useEnterChat 游客直通（已登录直达 /chat，未登录铸游客号）；
 * - 「更多」抽屉含法务三链+关于+反馈（doc 25 三入口之一），不含语言切换；
 * - 860px 断点以下才显示（原型断点；整页移动端适配复核待后续）。
 */

function tabClass(active: boolean): string {
  return cn(
    "flex flex-1 cursor-pointer flex-col items-center gap-0.5 py-[3px] text-[10.5px] text-[var(--feed-text-tertiary)]",
    active && "font-bold text-[var(--polyu-red)]"
  );
}

export function MobileTabbar() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";

  const [searchParams] = useSearchParams();
  const isAllView = searchParams.get("view") === "all";
  const [moreOpen, setMoreOpen] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState(false);

  // 游客直通：同 FeedSidebar 新对话一条 enterChat() 路径
  const enterChat = useEnterChat();

  const closeMore = () => setMoreOpen(false);

  return (
    <>
      <nav className="fixed inset-x-0 bottom-0 z-50 hidden max-[860px]:flex border-t border-[var(--feed-line-soft)] bg-[rgba(255,255,255,0.96)] pt-1.5 pb-[max(6px,env(safe-area-inset-bottom))] backdrop-blur">
        {/* 版式切换剥检索三参（doc 32 决策二 B 案，同 FeedSidebar withQuery 口径） */}
        <Link to={withQuery("/", searchParams)} className={tabClass(!isAllView)} onClick={closeMore}>
          <span className="text-[19px] leading-none">⚡</span>
          <span>{zh ? "精选" : "Featured"}</span>
        </Link>
        <Link
          to={withQuery("/?view=all", searchParams)}
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
        <button type="button" className={tabClass(moreOpen)} onClick={() => setMoreOpen(true)}>
          <span className="text-[19px] leading-none">⋯</span>
          <span>{zh ? "更多" : "More"}</span>
        </button>
      </nav>

      <button
        type="button"
        className="fixed bottom-[78px] right-3.5 z-[52] hidden max-[860px]:flex items-center gap-[7px] rounded-full bg-[var(--polyu-red)] px-[18px] py-[11px] text-[13.5px] font-bold text-white shadow-[0_6px_18px_rgba(166,25,46,0.4)] active:scale-95"
        onClick={enterChat}
      >
        💬 <span>{zh ? "问 Agent" : "Ask Agent"}</span>
      </button>

      {moreOpen && (
        <>
          <div className="fixed inset-0 z-[70] bg-[rgba(24,24,27,0.35)]" onClick={closeMore} />
          <div className="fixed inset-x-0 bottom-0 z-[71] rounded-t-[18px] bg-white px-[18px] pt-2 pb-[max(16px,env(safe-area-inset-bottom))]">
            <div className="mx-auto mb-3 mt-1.5 h-1 w-9 rounded-full bg-[var(--feed-line)]" />
            <div className="flex gap-2">
              <Link
                to="/privacy"
                onClick={closeMore}
                className="flex-1 rounded-[10px] border border-[var(--feed-line)] bg-white py-2.5 text-[12.5px] text-[var(--feed-text-secondary)] active:bg-[var(--polyu-red-50)]"
              >
                🔒 {zh ? "隐私政策" : "Privacy"}
              </Link>
              <Link
                to="/terms"
                onClick={closeMore}
                className="flex-1 rounded-[10px] border border-[var(--feed-line)] bg-white py-2.5 text-[12.5px] text-[var(--feed-text-secondary)] active:bg-[var(--polyu-red-50)]"
              >
                📄 {zh ? "服务条款" : "Terms"}
              </Link>
              <Link
                to="/disclaimer"
                onClick={closeMore}
                className="flex-1 rounded-[10px] border border-[var(--feed-line)] bg-white py-2.5 text-[12.5px] text-[var(--feed-text-secondary)] active:bg-[var(--polyu-red-50)]"
              >
                ℹ️ {zh ? "非官方声明" : "Disclaimer"}
              </Link>
            </div>
            <div className="mt-2 flex gap-2">
              <Link
                to="/about"
                onClick={closeMore}
                className="flex-1 rounded-[10px] border border-[var(--feed-line)] bg-white py-2.5 text-[12.5px] text-[var(--feed-text-secondary)] active:bg-[var(--polyu-red-50)]"
              >
                💡 {zh ? "关于" : "About"}
              </Link>
              <button
                type="button"
                onClick={() => {
                  closeMore();
                  setFeedbackOpen(true);
                }}
                className="flex-1 rounded-[10px] border border-[var(--feed-line)] bg-white py-2.5 text-[12.5px] text-[var(--feed-text-secondary)] active:bg-[var(--polyu-red-50)]"
              >
                💬 {zh ? "反馈" : "Feedback"}
              </button>
            </div>
          </div>
        </>
      )}
      <FeedbackDialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />
    </>
  );
}
