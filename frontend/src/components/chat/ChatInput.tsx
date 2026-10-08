import * as React from "react";
import { Brain, Lightbulb, Send, Square } from "lucide-react";

import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { Textarea } from "@/components/ui/textarea";
import { composerText } from "@/components/chat/composerText";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

export function ChatInput() {
  const { lang } = useOptionalFeedLang();
  const t = composerText(lang === "zh");
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    deepThinkingEnabled,
    setDeepThinkingEnabled,
    inputFocusKey
  } = useChatStore();

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  React.useEffect(() => {
    if (!inputFocusKey) return;
    focusInput();
  }, [inputFocusKey, focusInput]);

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className="space-y-4">
      <div
        className={cn(
          // 红线公报：输入卡=白卡+发丝线+shadow-sm；聚焦=主色描边+glow（#228 同源主色）
          "relative flex flex-col rounded-2xl border bg-white px-4 pt-3 pb-2 shadow-sm transition-all duration-200",
          isFocused
            ? "border-[hsl(var(--primary)/0.45)] shadow-glow"
            : "border-[var(--feed-line)] hover:border-[#D8B7BC]"
        )}
      >
        <div className="relative">
          <Textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder={deepThinkingEnabled ? t.placeholderDeep : t.placeholderIdle}
            className="max-h-40 min-h-[44px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 pr-2 text-[15px] max-[860px]:text-[16px] text-[var(--feed-text-primary)] shadow-none placeholder:text-[var(--feed-text-tertiary)] focus-visible:ring-0"
            rows={1}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={() => {
              isComposingRef.current = false;
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                const nativeEvent = event.nativeEvent as KeyboardEvent;
                if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                  return;
                }
                event.preventDefault();
                handleSubmit();
              }
            }}
            aria-label={lang === "zh" ? "聊天输入框" : "Chat input"}
          />
          <div className="pointer-events-none absolute bottom-0 left-0 right-0 h-[10px] bg-gradient-to-b from-white/0 via-white/40 to-white/90" />
        </div>
        <div className="relative mt-2 flex items-center">
          <button
            type="button"
            onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
            disabled={isStreaming}
            aria-pressed={deepThinkingEnabled}
            className={cn(
              "absolute left-0 rounded-full border px-3 py-1.5 text-xs font-medium transition-all",
              // 深思考开关=Chips 选中态（DESIGN.md）：红 wash 底 + 深砖红字
              deepThinkingEnabled
                ? "border-[var(--polyu-red-100)] bg-[var(--polyu-red-50)] text-[var(--polyu-red-dark)]"
                : "border-transparent bg-[var(--feed-bg)] text-[var(--feed-text-tertiary)] hover:bg-[var(--feed-line-soft)]",
              isStreaming && "cursor-not-allowed opacity-60"
            )}
          >
            <span className="inline-flex items-center gap-2">
              <Brain className={cn("h-3.5 w-3.5", deepThinkingEnabled && "text-[var(--polyu-red-dark)]")} />
              {t.deepThinking}
              {deepThinkingEnabled ? (
                <span className="h-2 w-2 rounded-full bg-[var(--polyu-red)] animate-pulse" />
              ) : null}
            </span>
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!hasContent && !isStreaming}
            aria-label={isStreaming ? t.stopGenerating : t.sendMessage}
            className={cn(
              "ml-auto flex h-10 w-10 items-center justify-center rounded-full transition-all duration-200",
              isStreaming
                ? "bg-[var(--polyu-red-50)] text-[var(--polyu-red-dark)] hover:bg-[var(--polyu-red-100)]"
                : hasContent
                  ? "bg-[var(--polyu-red)] text-white shadow-glow hover:bg-[var(--polyu-red-dark)]"
                  : "cursor-not-allowed bg-[var(--feed-bg)] text-[var(--feed-text-tertiary)]"
            )}
          >
            {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
          </button>
        </div>
      </div>
      {deepThinkingEnabled ? (
        <p className="text-xs text-[var(--polyu-red-dark)]">
          <span className="inline-flex items-center gap-1.5">
            <Lightbulb className="h-3.5 w-3.5" />
            {t.deepThinkingOn}
          </span>
        </p>
      ) : null}
      <p className="text-center text-xs text-[var(--feed-text-tertiary)]">
        <kbd className="rounded border border-[var(--feed-line)] bg-[var(--feed-bg)] px-1.5 py-0.5 text-[var(--feed-text-secondary)]">Enter</kbd> {t.enterHint}
        <span className="px-1.5">·</span>
        <kbd className="rounded border border-[var(--feed-line)] bg-[var(--feed-bg)] px-1.5 py-0.5 text-[var(--feed-text-secondary)]">
          Shift + Enter
        </kbd>{" "}
        {t.newlineHint}
        {isStreaming ? <span className="ml-2 animate-pulse-soft">{t.generating}</span> : null}
      </p>
    </div>
  );
}
