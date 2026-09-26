import { useOptionalFeedLang } from "@/components/feed/feedLang";

/**
 * 私有会话深链 S4 卡（issue #140，doc44 D-10）：三态归一文案——
 * 不泄露会话存在性/属主/标题/内容/session ID（「不存在/已删除/属于其他账号」
 * 并列不可分辨）；「开始新对话」由用户点击才触发导航（不自动 startNewChat）。
 * Agent 与 Workflow 双链共用；/share/c/:token 仍是唯一公开分享路径。
 */
export function SessionUnavailableCard({ onStartNew }: { onStartNew: () => void }) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  return (
    <div className="mx-auto flex h-full w-full max-w-[560px] flex-col items-center justify-center px-6 py-10 text-center">
      <div className="w-full rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] px-7 py-8 shadow-[0_10px_28px_rgba(24,24,27,0.06)]">
        <h2 className="text-[17px] font-bold text-[var(--feed-text-primary)]">
          {zh ? "无法打开此会话" : "Can't open this conversation"}
        </h2>
        <p className="mt-2.5 text-[13px] leading-relaxed text-[var(--feed-text-secondary)]">
          {zh
            ? "该会话可能不存在、已删除，或属于其他账号。"
            : "This conversation may not exist, may have been deleted, or belongs to another account."}
        </p>
        <p className="mt-2 text-[12.5px] leading-relaxed text-[var(--feed-text-tertiary)]">
          {zh
            ? "如果别人想与你分享 PolyUGuide 对话，请让对方使用对话页面中的「分享」功能生成分享链接。"
            : "If someone wants to share a PolyUGuide conversation with you, ask them to use the Share feature in the chat page to create a share link."}
        </p>
        <button
          type="button"
          className="mt-5 rounded-full bg-[var(--polyu-red)] px-[18px] py-2 text-[13px] font-semibold text-white transition-colors hover:bg-[var(--polyu-red-dark)]"
          onClick={onStartNew}
        >
          {zh ? "开始新对话" : "Start a new chat"}
        </button>
      </div>
    </div>
  );
}
