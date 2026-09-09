import * as React from "react";
import { AlertTriangle, Brain, ChevronDown, Clock3, Hourglass, LogIn } from "lucide-react";
import { Link } from "react-router-dom";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { RecommendedQuestions } from "@/components/chat/RecommendedQuestions";
import { RecommendedQuestionsButton } from "@/components/chat/RecommendedQuestionsButton";
import { ShareButton } from "@/components/chat/ShareButton";
import { SourcesButton } from "@/components/chat/SourcesButton";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { cn } from "@/lib/utils";
import type { ChatNotice, Message } from "@/types";

interface MessageItemProps {
  message: Message;
}

const NOTICE_STYLE: Record<ChatNotice["kind"], { box: string; icon: React.ReactNode }> = {
  quota: {
    box: "border-amber-200 bg-amber-50 text-amber-800",
    icon: <Hourglass className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
  },
  busy: {
    box: "border-amber-200 bg-amber-50 text-amber-800",
    icon: <Clock3 className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
  },
  concurrent: {
    box: "border-amber-200 bg-amber-50 text-amber-800",
    icon: <Clock3 className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
  },
  error: {
    box: "border-rose-200 bg-rose-50 text-rose-700",
    icon: <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-rose-500" />
  }
};

/**
 * 结构化提示块（U11-⑤）：配额用尽/排队拒绝等以消息内提示呈现，配额场景附注册引导
 */
function ChatNoticeBlock({ notice }: { notice: ChatNotice }) {
  const style = NOTICE_STYLE[notice.kind] ?? NOTICE_STYLE.error;
  return (
    <div className={cn("flex flex-col gap-2 rounded-xl border px-4 py-3 text-sm", style.box)}>
      <div className="flex items-start gap-2">
        {style.icon}
        <p className="min-w-0 flex-1 leading-relaxed">{notice.text}</p>
      </div>
      {notice.kind === "quota" ? (
        <Link
          to="/login"
          className="inline-flex w-fit items-center gap-1.5 rounded-lg border border-amber-300 bg-white px-3 py-1.5 text-xs font-medium text-amber-700 transition-colors hover:bg-amber-100"
        >
          <LogIn className="h-3.5 w-3.5" />
          注册登录，解锁完整使用
        </Link>
      ) : null}
    </div>
  );
}

export const MessageItem = React.memo(function MessageItem({ message }: MessageItemProps) {
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const isThinking = Boolean(message.isThinking);
  const hasSources =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    (message.sources?.length ?? 0) > 0;
  // 推荐追问：完成态且已落库的助手消息（真实 messageId）方可触发 与反馈按钮判据一致
  const canRecommend =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    Boolean(message.id) &&
    (message.messageStatus ?? "NORMAL") === "NORMAL" &&
    !message.id.startsWith("assistant-");
  const [thinkingExpanded, setThinkingExpanded] = React.useState(false);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !isThinking && !hasContent;

  if (isUser) {
    return (
      <div className="flex">
        <div className="user-message">
          <p className="whitespace-pre-wrap break-words">{message.content}</p>
        </div>
      </div>
    );
  }

  const thinkingDuration = message.thinkingDuration ? `${message.thinkingDuration}秒` : "";
  return (
    <div className="group flex">
      <div className="min-w-0 flex-1 space-y-4">
        {isThinking ? (
          <ThinkingIndicator content={message.thinking} duration={message.thinkingDuration} />
        ) : null}
        {!isThinking && hasThinking ? (
          <div className="overflow-hidden rounded-lg border border-[#BFDBFE] bg-[#DBEAFE]">
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className="flex w-full items-center gap-2 px-4 py-3 text-left transition-colors hover:bg-[#BFDBFE]/30"
            >
              <div className="flex flex-1 items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#BFDBFE]">
                  <Brain className="h-4 w-4 text-[#2563EB]" />
                </div>
                <span className="text-sm font-medium text-[#2563EB]">深度思考</span>
                {thinkingDuration ? (
                  <span className="rounded-full bg-[#BFDBFE] px-2 py-0.5 text-xs text-[#2563EB]">
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-[#3B82F6] transition-transform",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div className="border-t border-[#BFDBFE] px-4 pb-4">
                <div className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-[#1E40AF]">
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
        <div className="space-y-2">
          {isWaiting ? (
            <div className="ai-wait" aria-label={message.awaitingSignal ? "排队等待中" : "思考中"}>
              {message.awaitingSignal ? (
                <p className="text-xs text-[#999999]">
                  <span className="inline-flex items-center gap-1.5">
                    <Hourglass className="h-3.5 w-3.5 animate-pulse-soft" />
                    已提交，正在排队等待系统空闲，高峰期可能需要数十秒
                  </span>
                </p>
              ) : null}
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {message.notice ? <ChatNoticeBlock notice={message.notice} /> : null}
          {hasContent ? (
            <MarkdownRenderer
              content={message.content}
              messageId={message.id}
              sources={message.sources}
            />
          ) : null}
          {message.status === "error" && !message.notice ? (
            <p className="text-xs text-rose-500">生成已中断。</p>
          ) : null}
          {showFeedback || hasSources || canRecommend ? (
            <div className="flex items-center gap-2">
              {showFeedback ? (
                <FeedbackButtons
                  messageId={message.id}
                  feedback={message.feedback ?? null}
                  content={message.content}
                  alwaysVisible
                />
              ) : null}
              {showFeedback ? <ShareButton messageId={message.id} /> : null}
              {hasSources ? (
                <SourcesButton messageId={message.id} sources={message.sources!} />
              ) : null}
              {canRecommend ? <RecommendedQuestionsButton message={message} /> : null}
            </div>
          ) : null}
          {canRecommend ? <RecommendedQuestions message={message} /> : null}
        </div>
      </div>
    </div>
  );
});
