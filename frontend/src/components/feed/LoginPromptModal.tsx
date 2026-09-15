import { Link } from "react-router-dom";

import type { FeedLang } from "./feedLang";

/**
 * 游客超限弹窗（原型 #limitModal 文案照抄）：
 * - 本轮只做展示组件（open 受控），quota 类错误接线（useStreamResponse chatErrors
 *   分类 → 渲染本弹窗替代行内 ChatNotice）不在本组件内；
 * - 底部注册/登录按钮直达 /register 与 /login；
 * - 语言默认中文：弹窗将在聊天壳（FeedShell 之外）被接线，故不走 useFeedLang，
 *   由调用方按需传 lang。
 */
export function LoginPromptModal({
  open,
  onClose,
  lang = "zh"
}: {
  open: boolean;
  onClose: () => void;
  lang?: FeedLang;
}) {
  if (!open) {
    return null;
  }
  const zh = lang === "zh";

  return (
    <div
      className="fixed inset-0 z-[80] flex items-center justify-center bg-[rgba(24,24,27,0.45)] p-5"
      onClick={onClose}
    >
      <div
        className="relative w-[400px] max-w-full rounded-[18px] bg-white px-[26px] pb-[22px] pt-[26px] text-center shadow-[0_24px_60px_rgba(0,0,0,0.25)]"
        onClick={(event) => event.stopPropagation()}
      >
        <button
          type="button"
          aria-label={zh ? "关闭" : "Close"}
          className="absolute right-3 top-3 flex h-7 w-7 items-center justify-center rounded-lg text-base text-[var(--feed-text-tertiary)] hover:bg-[var(--feed-bg)]"
          onClick={onClose}
        >
          ×
        </button>
        <div className="mx-auto mb-3.5 flex h-[52px] w-[52px] items-center justify-center rounded-full bg-[var(--polyu-red-50)] text-2xl">
          💬
        </div>
        <h3 className="mb-2 text-[16.5px] font-bold">{zh ? "已达到游客使用上限" : "Guest limit reached"}</h3>
        <p className="mb-[18px] text-[13px] leading-[1.7] text-[var(--feed-text-secondary)]">
          {zh ? (
            <>
              你今日的 <b className="text-[var(--polyu-red-dark)]">3 次</b> 免登录 Agent 对话已用完。
              <br />
              想要继续使用智能问答功能，请登录或注册账号——浏览资讯始终免费。
            </>
          ) : (
            <>
              You’ve used your <b className="text-[var(--polyu-red-dark)]">3</b> guest Agent chats today.
              <br />
              Sign in or create an account to keep asking — browsing news stays free.
            </>
          )}
        </p>
        <div className="flex gap-2.5">
          <Link
            to="/register"
            className="flex-1 rounded-lg border border-[var(--polyu-red)] bg-white py-[6.5px] text-center text-[13px] font-semibold text-[var(--polyu-red)] transition-colors hover:bg-[var(--polyu-red-50)]"
          >
            {zh ? "注册" : "Sign up"}
          </Link>
          <Link
            to="/login"
            className="flex-1 rounded-lg bg-[var(--polyu-red)] py-[6.5px] text-center text-[13px] font-semibold text-white transition-colors hover:bg-[var(--polyu-red-dark)]"
          >
            {zh ? "登录" : "Sign in"}
          </Link>
        </div>
        <div className="mt-3 text-[11.5px] text-[var(--feed-text-tertiary)]">
          {zh ? "游客数据仅保留 30 天，注册后可同步对话历史与更多功能" : "Guest data is kept for 30 days only; accounts sync history and unlock more"}
        </div>
      </div>
    </div>
  );
}
