import { useState } from "react";
import { Link } from "react-router-dom";

import { FeedbackDialog } from "@/components/site/FeedbackDialog";

const LEGAL_LINKS = [
  { to: "/privacy", label: "隐私声明 · Privacy" },
  { to: "/terms", label: "服务条款 · Terms" },
  { to: "/disclaimer", label: "非官方声明 · Disclaimer" }
] as const;

interface SiteFooterProps {
  className?: string;
}

/**
 * 站点常驻页脚：三法务链接全站可达 + 关于页 + 反馈入口（doc 25）。
 * 挂载面=MainLayout / LoginPage / SharePage / NotFoundPage 与法务页壳（LegalShell）。
 */
export function SiteFooter({ className = "" }: SiteFooterProps) {
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  return (
    <footer
      className={`flex flex-wrap items-center justify-center gap-x-4 gap-y-1 px-4 py-2 text-xs text-muted-foreground ${className}`}
    >
      {LEGAL_LINKS.map((link) => (
        <Link key={link.to} to={link.to} className="hover:underline">
          {link.label}
        </Link>
      ))}
      <Link to="/about" className="hover:underline">
        关于 · About
      </Link>
      <button type="button" className="hover:underline" onClick={() => setFeedbackOpen(true)}>
        反馈 · Feedback
      </button>
      <FeedbackDialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)} />
    </footer>
  );
}
