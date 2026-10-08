import * as React from "react";
import { ArrowUpRight, BookOpen, Brain, Check, Lightbulb, Send, Square } from "lucide-react";

import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { composerText } from "@/components/chat/composerText";
import { cn } from "@/lib/utils";
import { listSampleQuestions } from "@/services/sampleQuestionService";
import { feedDateLabels } from "@/services/newsMapping";
import { useChatStore } from "@/stores/chatStore";

type PromptPreset = {
  id?: string;
  title: string;
  description: string;
  prompt: string;
  icon: React.ComponentType<{ className?: string }>;
};

const PRESET_ICONS = [BookOpen, Check, Lightbulb];

const DEFAULT_PRESETS: PromptPreset[] = [
  {
    title: "内容总结",
    description: "提炼 3-5 条关键信息与行动点",
    prompt: "请帮我总结以下内容，并列出3-5条要点：",
    icon: BookOpen
  },
  {
    title: "任务拆解",
    description: "把目标拆成可执行步骤与优先级",
    prompt: "请把下面需求拆解为步骤，并给出优先级和里程碑：",
    icon: Check
  },
  {
    title: "灵感扩展",
    description: "给出多个方案并比较优缺点",
    prompt: "围绕以下主题给出5-8个方案，并注明优缺点：",
    icon: Lightbulb
  }
];

export function WelcomeScreen() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const t = composerText(zh);
  // 报眉日期行（红线公报门面）：HKT 实时值，与壳顶栏同源
  const dateLine = feedDateLabels(new Date(), lang).long;
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [promptPresets, setPromptPresets] = React.useState<PromptPreset[]>(DEFAULT_PRESETS);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration, deepThinkingEnabled, setDeepThinkingEnabled } =
    useChatStore();

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
    let active = true;

    // T20：预设卡随全局语言抽样（该语言无行后端回落全量）；语言切换重取
    const loadPresets = async () => {
      const data = await listSampleQuestions(3, zh ? "zh" : "en").catch(() => null);
      if (!active || !data || data.length === 0) {
        return;
      }
      const mapped = data
        .filter((item) => item.question && item.question.trim())
        .slice(0, 3)
        .map((item, index) => {
          const question = item.question.trim();
          const title =
            item.title?.trim() ||
            (question.length > 12 ? `${question.slice(0, 12)}...` : question) ||
            `推荐问法 ${index + 1}`;
          const description = item.description?.trim() || "直接点选即可开始对话";
          return {
            id: item.id,
            title,
            description,
            prompt: question,
            icon: PRESET_ICONS[index % PRESET_ICONS.length]
          };
        });
      if (mapped.length > 0) {
        setPromptPresets(mapped);
      }
    };

    loadPresets();
    return () => {
      active = false;
    };
  }, [zh]);

  const applyPreset = React.useCallback(
    (prompt: string) => {
      if (isStreaming) return;
      setValue(prompt);
      focusInput();
    },
    [isStreaming, focusInput]
  );

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
    // 2026-09-13：矮视口（<663px）下原 overflow-hidden+垂直居中会把第三张
    // 预设卡裁出视口且不可滚——「外层裁剪框 + 中层可滚 + my-auto 居中」保留：
    // 内容超高时 auto 边距归零、从顶部起滚（items-center 居中裁顶是 Flex 滚动
    // 容器的经典陷阱，my-auto 不触发）。
    // 红线公报（DESIGN.md）：门面=报纸头版——纸灰底、红判别线+报眉行，无装饰层。
    <div className="relative h-full overflow-hidden">
      <div className="relative h-full overflow-y-auto">
        <div className="flex min-h-full justify-center px-4 py-16 sm:px-6">
          <div className="relative my-auto w-full max-w-[860px]">
        <div
          className="text-center opacity-0 animate-fade-up"
          style={{ animationFillMode: "both" }}
        >
          <div aria-hidden="true" className="mx-auto h-[3px] w-14 bg-[var(--polyu-red)]" />
          <p className="mt-3 text-[12px] tabular-nums text-[var(--feed-text-tertiary)]">
            {zh ? `香港 · ${dateLine} · 非官方校园信息核验` : `Hong Kong · ${dateLine} · Unofficial campus gazette`}
          </p>
          <h1 className="mt-6 font-display text-4xl leading-tight tracking-tight text-[var(--feed-text-primary)] sm:text-5xl md:text-6xl">
            {zh ? "把问题变成清晰答案" : "Turning questions into clear answers"}
          </h1>
          <p className="mt-4 text-base text-[var(--feed-text-secondary)] sm:text-lg">
            {zh
              ? "结构化提问、知识检索与深度思考，一次对话给出可核验的答案"
              : "Structured prompting, knowledge retrieval and deep reasoning — sourced, verifiable answers in one chat"}
          </p>
        </div>

        <div
          className="mt-10 opacity-0 animate-fade-up"
          style={{ animationDelay: "80ms", animationFillMode: "both" }}
        >
          <div
            className={cn(
              // 白卡+发丝线+shadow-sm；聚焦=主色描边+glow（#228 同源主色）
              "relative flex flex-col rounded-2xl border border-[var(--feed-line)] bg-white px-5 pt-4 pb-3 shadow-sm transition-all duration-200",
              isFocused ? "border-[hsl(var(--primary)/0.45)] shadow-glow" : "hover:border-[#D8B7BC]"
            )}
          >
            <div className="relative">
              <textarea
                ref={textareaRef}
                value={value}
                onChange={(event) => setValue(event.target.value)}
                placeholder={deepThinkingEnabled ? t.placeholderDeep : t.placeholderIdle}
                className="max-h-40 min-h-[52px] w-full resize-none border-0 bg-transparent px-2 pt-2 pb-2 text-[15px] text-[var(--feed-text-primary)] placeholder:text-[var(--feed-text-tertiary)] focus:outline-none sm:text-base"
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
                aria-label={t.sendMessage}
              />
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
                disabled={isStreaming}
                aria-pressed={deepThinkingEnabled}
                className={cn(
                  "rounded-full border px-3 py-1.5 text-xs font-medium transition-all",
                  // 深思考开关=Chips 选中态：红 wash 底 + 深砖红字（DESIGN.md）
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
                  "ml-auto inline-flex h-10 w-10 items-center justify-center rounded-full transition-all duration-200",
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
            <p className="mt-3 text-xs text-[var(--polyu-red-dark)]">
              <span className="inline-flex items-center gap-1.5">
                <Lightbulb className="h-3.5 w-3.5" />
                {t.deepThinkingOn}
              </span>
            </p>
          ) : null}
          <p className="mt-3 text-center text-xs text-[var(--feed-text-tertiary)]">
            <kbd className="rounded border border-[var(--feed-line)] bg-white px-1.5 py-0.5 text-[var(--feed-text-secondary)]">
              Enter
            </kbd>{" "}
            {t.enterHint}
            <span className="px-1.5">·</span>
            <kbd className="rounded border border-[var(--feed-line)] bg-white px-1.5 py-0.5 text-[var(--feed-text-secondary)]">
              Shift + Enter
            </kbd>{" "}
            {t.newlineHint}
            {isStreaming ? <span className="ml-2 animate-pulse-soft">{t.generating}</span> : null}
          </p>
        </div>

        <div
          className="mt-10 opacity-0 animate-fade-up"
          style={{ animationDelay: "160ms", animationFillMode: "both" }}
        >
          <div className="flex items-center justify-center gap-2 text-[12px] text-[var(--feed-text-tertiary)]">
            <span className="h-px w-8 bg-[var(--feed-line)]" />
            {zh ? "试试这些开场" : "Try these starters"}
            <span className="h-px w-8 bg-[var(--feed-line)]" />
          </div>
          <div className="mt-5 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {promptPresets.map((preset) => {
              const Icon = preset.icon;
              return (
                <button
                  key={preset.id ?? preset.title}
                  type="button"
                  onClick={() => applyPreset(preset.prompt)}
                  disabled={isStreaming}
                  className={cn(
                    // 白卡+发丝线；hover=边框迁移暖红调（Border-Shift 规则，不加阴影层）
                    "group rounded-2xl border border-[var(--feed-line)] bg-white p-4 text-left shadow-sm transition-colors duration-200 hover:border-[#D8B7BC]",
                    isStreaming && "cursor-not-allowed opacity-60"
                  )}
                >
                  <div className="flex items-center gap-3">
                    <span className="flex h-10 w-10 items-center justify-center rounded-full bg-[var(--feed-bg)] text-[var(--feed-text-secondary)]">
                      <Icon className="h-4 w-4" />
                    </span>
                    <div>
                      <p className="text-sm font-semibold text-[var(--feed-text-primary)]">{preset.title}</p>
                      <p className="text-xs text-[var(--feed-text-secondary)]">{preset.description}</p>
                    </div>
                  </div>
                  <div className="mt-3 flex items-center gap-2 text-xs text-[var(--feed-text-tertiary)]">
                    <span className="min-w-0 flex-1 truncate">{zh ? "推荐问法：" : "Prompt: "}{preset.prompt}</span>
                    <ArrowUpRight className="h-3.5 w-3.5 text-[var(--feed-text-tertiary)] transition-colors group-hover:text-[var(--polyu-red)]" />
                  </div>
                </button>
              );
            })}
          </div>
        </div>
          </div>
        </div>
      </div>
    </div>
  );
}
