import { ChevronDown, Loader2, Sparkles } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import type { Message } from "@/types";

interface RecommendedQuestionsButtonProps {
  message: Message;
}

export function RecommendedQuestionsButton({ message }: RecommendedQuestionsButtonProps) {
  const toggleRecommended = useChatStore((state) => state.toggleRecommended);

  const open = Boolean(message.recommendedOpen);
  // 生成中转圈；手动触发即展开，loading 必然可见
  const spinning = message.recommendedState === "loading";

  return (
    <button
      type="button"
      onClick={() => toggleRecommended(message.id)}
      disabled={spinning}
      aria-expanded={open}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full py-1 pl-2.5 pr-2 text-xs font-medium transition-colors",
        open
          ? "bg-[var(--polyu-red-50)] text-[var(--polyu-red-dark)]"
          : "text-[var(--feed-text-secondary)] hover:bg-[var(--polyu-red-50)] hover:text-[var(--polyu-red-dark)]",
        spinning && "cursor-wait opacity-80"
      )}
    >
      {spinning ? (
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
      ) : (
        <Sparkles className={cn("h-3.5 w-3.5", open && "text-[var(--polyu-red-dark)]")} />
      )}
      推荐问题
      <ChevronDown
        className={cn("h-3 w-3 transition-transform", open && "rotate-180")}
        aria-hidden="true"
      />
    </button>
  );
}
