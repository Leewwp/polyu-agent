import { Brain, Loader2 } from "lucide-react";

import { useOptionalFeedLang } from "@/components/feed/feedLang";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
}

export function ThinkingIndicator({ content, duration }: ThinkingIndicatorProps) {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  return (
    <div className="rounded-lg border border-[var(--feed-line)] bg-[var(--feed-bg)] p-4">
      <div className="flex items-center gap-2 text-[var(--feed-text-secondary)]">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm font-medium">{zh ? "正在深度思考..." : "Deep thinking in progress..."}</span>
        {duration ? (
          <span className="rounded-full bg-[var(--feed-card)] px-2 py-0.5 text-xs tabular-nums text-[var(--feed-text-secondary)] ring-1 ring-[var(--feed-line)]">
            {zh ? `${duration}秒` : `${duration}s`}
          </span>
        ) : null}
      </div>
      <div className="mt-3 flex items-start gap-2 text-sm text-[var(--feed-text-secondary)]">
        <Brain className="mt-0.5 h-4 w-4 shrink-0 text-[var(--feed-text-secondary)]" />
        <p className="whitespace-pre-wrap leading-relaxed">
          {content || ""}
          <span className="ml-1 inline-block h-4 w-1.5 animate-pulse bg-[var(--feed-text-secondary)] align-middle" />
        </p>
      </div>
    </div>
  );
}
