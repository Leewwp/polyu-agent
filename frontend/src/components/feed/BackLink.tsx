import { ArrowLeft } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";

import { useOptionalFeedLang } from "./feedLang";
import { cn } from "@/lib/utils";

export interface BackLinkProps {
  /** 直链/新标签（无站内历史）时的自然父级回退路由（如 /topics、/） */
  fallbackTo: string;
  /** 追加类名（详情页 mb-2.5 间距锚点等，经 cn 并入基类） */
  className?: string;
}

/**
 * feed 域共享返回出口（#342）：history 语义优先——有站内历史
 * （react-router BrowserHistory 逐跳写入的 window.history.state.idx > 0）
 * 时 navigate(-1) 精准回用户上一页（从日报进详情返回必须回日报，被导向
 * 首页即视为功能设计问题）；直链/新标签无站内历史时 replace 到自然父级
 * fallbackTo（replace 不把中间态留进历史栈，避免死循环）。
 * 文案=通用「返回 / Back」：精准返回语义下目的地不可静态预知，固定目的
 * 地文案会撒谎；语言走全局 FeedLang（useOptionalFeedLang，脱离 Provider
 * 的单测场景回落 zh）。触控契约 #232：min-h-[44px] 命中区外扩（px-2 -ml-2
 * 视觉左对齐不缩进），主清单档源锚定见 touchTargets.contract.test.ts。
 */
export function BackLink({ fallbackTo, className }: BackLinkProps) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const navigate = useNavigate();

  const handleClick: React.MouseEventHandler<HTMLAnchorElement> = (event) => {
    // 修饰键/非左键交浏览器原生（新标签打开 href=fallbackTo，返回语义仅限本标签）
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    const idx = window.history.state?.idx;
    if (typeof idx === "number" && idx > 0) {
      navigate(-1);
    } else {
      navigate(fallbackTo, { replace: true });
    }
  };

  return (
    <Link
      to={fallbackTo}
      onClick={handleClick}
      className={cn(
        "inline-flex min-h-[44px] items-center gap-1.5 px-2 -ml-2 text-[13px] text-[var(--feed-text-tertiary)] transition-colors hover:text-[var(--polyu-red)]",
        className
      )}
    >
      <ArrowLeft className="h-4 w-4" aria-hidden="true" />
      {zh ? "返回" : "Back"}
    </Link>
  );
}
