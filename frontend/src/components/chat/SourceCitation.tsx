import { ArrowUpRight } from "lucide-react";

import { SourceIcon } from "@/components/chat/SourceIcon";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { displayExcerpt, displayName, openSource, sourceSite } from "@/lib/source";
import { useChatStore } from "@/stores/chatStore";
import type { SourceRef } from "@/types";

interface SourceCitationProps {
  index: number;
  messageId?: string;
  source?: SourceRef;
}

/**
 * 回答正文中的文档引用角标
 * <p>
 * 角标本身点击展开右侧来源面板；悬浮卡按「站点行 → 标题 → 摘要」自上而下收敛信息，
 * 整卡点击直达原文（外链新窗口 / 本地文件预览页），右上角箭头是该行为的提示
 */
export function SourceCitation({ index, messageId, source }: SourceCitationProps) {
  const openSourcesPanel = useChatStore((state) => state.openSourcesPanel);
  const interactive = Boolean(source && messageId);

  const trigger = (
    <button
      type="button"
      data-source-citation=""
      disabled={!interactive}
      onClick={() => {
        if (messageId && source) {
          openSourcesPanel(messageId);
        }
      }}
      aria-label={source ? `查看来源 ${index}：${source.docName || "未命名文档"}` : `来源 ${index}`}
      className={cn(
        // align-middle 让胶囊中线咬住正文（flex 容器默认拿盒子底边当基线 会整体悬高）
        // 再抬 2px 补上西文 x-height 中线与汉字中线的差
        // 红线公报：正文引用角标=核验标记——红 wash 底 + 深砖红字（DESIGN.md Verified Wash）
        "relative -top-[2px] ml-[1px] mr-[1px] inline-flex h-[17px] min-w-[17px] items-center justify-center align-middle",
        "rounded-full bg-[var(--polyu-red-50)] px-[5px] font-sans text-[10px] font-medium leading-none text-[var(--polyu-red-dark)]",
        "outline-none transition-colors duration-150",
        interactive &&
          "cursor-pointer hover:bg-[var(--polyu-red-100)] focus-visible:ring-2 focus-visible:ring-[var(--polyu-red)] focus-visible:ring-offset-1",
        !interactive && "cursor-default"
      )}
    >
      {index}
    </button>
  );

  if (!source) {
    return trigger;
  }

  const { title, detail } = displayName(source);
  const excerpt = displayExcerpt(source.excerpt);

  return (
    <TooltipProvider delayDuration={180}>
      <Tooltip>
        <TooltipTrigger asChild>{trigger}</TooltipTrigger>
        <TooltipContent
          side="top"
          align="start"
          sideOffset={8}
          className={cn(
            "w-[360px] max-w-[calc(100vw-32px)] rounded-2xl border border-[var(--feed-line)] bg-white p-0",
            "text-[var(--feed-text-primary)] shadow-[0_16px_48px_-12px_rgba(0,0,0,0.18)]"
          )}
        >
          <div
            role="link"
            title={source.docName || "查看来源"}
            onClick={() => openSource(source)}
            className={cn(
              "group cursor-pointer rounded-2xl p-4 transition-colors",
              "hover:bg-[var(--feed-bg)]"
            )}
          >
            <div className="flex items-center gap-2">
              <SourceIcon source={source} className="h-4 w-4 shrink-0" />
              <span className="min-w-0 flex-1 truncate text-[12px] text-[var(--feed-text-tertiary)]">
                {sourceSite(source)}
              </span>
              <span className="flex h-[17px] min-w-[17px] shrink-0 items-center justify-center rounded-full bg-[var(--polyu-red-50)] px-1 text-[10px] font-medium text-[var(--polyu-red-dark)]">
                {index}
              </span>
              <ArrowUpRight className="h-3.5 w-3.5 shrink-0 text-[var(--feed-text-tertiary)] transition-colors group-hover:text-[var(--polyu-red)]" />
            </div>

            <p className="mt-2.5 line-clamp-2 text-[13.5px] font-semibold leading-[1.5]">
              {title}
            </p>
            {detail ? (
              <p className="mt-1 truncate text-[11px] text-[var(--feed-text-tertiary)]">
                {detail}
              </p>
            ) : null}

            {excerpt ? (
              <p className="mt-3 line-clamp-4 border-t border-[var(--feed-line-soft)] pt-3 text-[12.5px] leading-[1.75] text-[var(--feed-text-secondary)]">
                {excerpt}
              </p>
            ) : null}
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
