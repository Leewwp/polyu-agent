import { useState } from "react";
import { Link } from "react-router-dom";

import { FeedbackDialog } from "@/components/site/FeedbackDialog";
import { useOptionalFeedLang } from "@/components/feed/feedLang";

/**
 * 站点常驻页脚：三法务链接全站可达 + 关于页 + 反馈入口（doc 25）。
 * 挂载面=MainLayout / LoginPage / SharePage / NotFoundPage 与法务页壳（LegalShell）。
 * #227：链接与反馈文字随全局语言单语呈现（原「隐私声明 · Privacy」硬拼双语拆开）；
 * 「隐私声明 / Privacy Notice」为全站唯一叫法。
 */
export function SiteFooter({ className = "" }: SiteFooterProps) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  const legalLinks = [
    { to: "/privacy", label: zh ? "隐私声明" : "Privacy Notice" },
    { to: "/terms", label: zh ? "服务条款" : "Terms" },
    { to: "/disclaimer", label: zh ? "非官方声明" : "Disclaimer" }
  ];
  return (
    <footer
      className={`flex flex-wrap items-center justify-center gap-x-4 gap-y-1 px-4 py-2 text-xs text-muted-foreground ${className}`}
    >
      {legalLinks.map((link) => (
        <Link key={link.to} to={link.to} className="hover:underline">
          {link.label}
        </Link>
      ))}
      <Link to="/about" className="hover:underline">
        {zh ? "关于" : "About"}
      </Link>
      <button type="button" className="hover:underline" onClick={() => setFeedbackOpen(true)}>
        {zh ? "反馈" : "Feedback"}
      </button>
      <FeedbackDialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />
    </footer>
  );
}

interface SiteFooterProps {
  className?: string;
}
