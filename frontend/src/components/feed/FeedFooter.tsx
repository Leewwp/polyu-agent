import { useState } from "react";
import { Link } from "react-router-dom";

import { FeedbackDialog } from "@/components/site/FeedbackDialog";
import { useFeedLang } from "./feedLang";

const CONTACT_EMAIL = "ppp@polyuguide.com";

/**
 * 资讯流页脚（自 FeedPage 提取共用；原型 .proto-footer）：
 * 三行极简、文档流末尾滚动到底自然出现、无分隔线。
 * - full（默认，首页口径）：三法务链+关于+反馈、非官方/AI 摘要声明、邮箱·©；
 * - compact（topics/热点榜/主题详情视图口径）：省中段声明行，仅链 + 邮箱·©。
 */
export function FeedFooter({ compact = false }: { compact?: boolean }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  return (
    <footer className="mt-7 flex flex-col items-center gap-1 pt-3.5 text-center text-[11.5px] text-[var(--feed-text-tertiary)]">
      <div className="flex gap-3.5">
        <Link className="font-semibold hover:text-[var(--polyu-red-dark)]" to="/privacy">
          {zh ? "隐私政策" : "Privacy"}
        </Link>
        <Link className="font-semibold hover:text-[var(--polyu-red-dark)]" to="/terms">
          {zh ? "服务条款" : "Terms"}
        </Link>
        <Link className="font-semibold hover:text-[var(--polyu-red-dark)]" to="/disclaimer">
          {zh ? "非官方声明" : "Disclaimer"}
        </Link>
        <Link className="font-semibold hover:text-[var(--polyu-red-dark)]" to="/about">
          {zh ? "关于" : "About"}
        </Link>
        <button
          type="button"
          className="font-semibold hover:text-[var(--polyu-red-dark)]"
          onClick={() => setFeedbackOpen(true)}
        >
          {zh ? "反馈" : "Feedback"}
        </button>
        {/* GitHub 外链（2026-09-11 定「现在挂」；repo 转公开为部署方动作，
            转公开前先清理 backup/*——404 窗口已知情）；full/compact 两口径均显示 */}
        <a
          className="font-semibold hover:text-[var(--polyu-red-dark)]"
          href="https://github.com/Leewwp/polyu-agent"
          target="_blank"
          rel="noreferrer"
        >
          GitHub
        </a>
      </div>
      {!compact && (
        <div>
          {zh
            ? "PolyUGuide 为学生自发建设的非官方社区项目，与香港理工大学无隶属关系 · 资讯摘要由 AI 生成，以原文为准"
            : "PolyUGuide is an independent student community project, not affiliated with PolyU · AI-generated summaries, always refer to the source"}
        </div>
      )}
      <div className="text-[11px] text-[#B4B4BC]">{CONTACT_EMAIL} · © 2026 PolyUGuide</div>
      <FeedbackDialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />
    </footer>
  );
}
