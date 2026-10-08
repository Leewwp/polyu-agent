import { SourceIcon } from "@/components/chat/SourceIcon";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import type { SourceRef } from "@/types";

interface SourcesButtonProps {
  messageId: string;
  sources: SourceRef[];
}

export function SourcesButton({ messageId, sources }: SourcesButtonProps) {
  const openedSourceMessageId = useChatStore((state) => state.openedSourceMessageId);
  const toggleSourcesPanel = useChatStore((state) => state.toggleSourcesPanel);

  if (!sources || sources.length === 0) {
    return null;
  }

  const active = openedSourceMessageId === messageId;
  const preview = sources.slice(0, 3);

  return (
    <button
      type="button"
      onClick={() => toggleSourcesPanel(messageId)}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full py-1 pl-1.5 pr-2.5 text-xs font-medium transition-colors",
        // 签名来源徽章（DESIGN.md）：砖红系——核验标记职能，激活/悬停红 wash + 深砖红
        active
          ? "bg-[var(--polyu-red-50)] text-[var(--polyu-red-dark)]"
          : "text-[var(--feed-text-secondary)] hover:bg-[var(--polyu-red-50)] hover:text-[var(--polyu-red-dark)]"
      )}
    >
      <span className="flex items-center">
        {preview.map((source, idx) => (
          <span
            key={`${source.docId}-${idx}`}
            className={cn(
              "flex h-5 w-5 items-center justify-center rounded-md bg-white ring-1 ring-[var(--feed-line)]",
              idx > 0 && "-ml-1.5"
            )}
          >
            <SourceIcon source={source} className="h-3 w-3" />
          </span>
        ))}
      </span>
      {sources.length} 篇来源
    </button>
  );
}
