import { useEffect, useId, useRef, useState } from "react";

import { useFeedLang } from "./feedLang";

/**
 * 信源名单气泡（原型 .src-pop）：热点榜「N 源」徽章悬停/点击弹出，
 * 展示该故事线的覆盖信源名单。定位用 fixed + 触发点坐标（原型 showSources(event,this) 同义）。
 * 关闭口径：hover 开+mouseleave 延迟关（移入浮层本体不关，
 * 保悬停预览语义）；打开状态页内单例——任一徽章开启即广播关闭其余，多行悬停零叠加残留；
 * 点击气泡外关闭链路保留。
 */
/** 单例协调事件：detail=开启方徽章 id，其余徽章实例收到即关 */
const SOURCE_POPOVER_OPEN_EVENT = "polyuguide:source-popover-open";
/** A 案：鼠标移出后延迟关闭（ms）——跨过徽章与浮层间 10px 空隙的时间余量 */
const HOVER_CLOSE_DELAY_MS = 150;

export function SourceListPopover({
  sources,
  onClose,
  anchorX,
  anchorY,
  onMouseEnter,
  onMouseLeave
}: {
  sources: string[];
  onClose: () => void;
  anchorX: number;
  anchorY: number;
  /** 移入浮层本体：取消徽章侧挂起的延迟关闭（A 案「移入不关」） */
  onMouseEnter?: () => void;
  onMouseLeave?: () => void;
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const popoverRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleOutsideClick(event: MouseEvent) {
      if (popoverRef.current && !popoverRef.current.contains(event.target as Node)) {
        onClose();
      }
    }
    document.addEventListener("mousedown", handleOutsideClick);
    return () => {
      document.removeEventListener("mousedown", handleOutsideClick);
    };
  }, [onClose]);

  // 原型 .src-pop：fixed 定位，max-width 270px；靠右下角触发点时向内收（防溢出视口）
  const left = Math.min(anchorX, window.innerWidth - 290);
  const top = Math.min(anchorY + 10, window.innerHeight - 160);

  return (
    <div
      ref={popoverRef}
      role="dialog"
      aria-label={zh ? "信源名单" : "Source list"}
      className="fixed z-[95] flex max-w-[270px] flex-wrap gap-1.5 rounded-xl border border-[var(--feed-line)] bg-white px-3 py-2.5 shadow-[0_10px_30px_rgba(24,24,27,0.14)]"
      style={{ left, top }}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <div className="w-full text-[11px] font-semibold text-[var(--feed-text-tertiary)]">
        {zh ? "信源名单" : "Sources"}
      </div>
      {sources.map((source) => (
        <span key={source} className="rounded-full bg-[var(--feed-bg)] px-2.5 py-0.5 text-[11.5px] text-[var(--feed-text-secondary)]">
          {source}
        </span>
      ))}
    </div>
  );
}

/**
 * 「N 源」徽章：悬停/点击开合逻辑收口于此，页面只管渲染行。
 */
export function SourceCountBadge({ sources }: { sources: string[] }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const badgeId = useId();
  const [open, setOpen] = useState(false);
  const [anchor, setAnchor] = useState({ x: 0, y: 0 });
  const closeTimerRef = useRef<number | null>(null);

  // C 案单例：其他徽章开启时本徽章即关（换行悬停残留多枚浮层的根因修复）
  useEffect(() => {
    function handleOtherOpen(event: Event) {
      if ((event as CustomEvent<string>).detail !== badgeId) {
        setOpen(false);
      }
    }
    window.addEventListener(SOURCE_POPOVER_OPEN_EVENT, handleOtherOpen);
    return () => window.removeEventListener(SOURCE_POPOVER_OPEN_EVENT, handleOtherOpen);
  }, [badgeId]);

  // 卸载清挂起定时器（已关状态下再触发 setOpen(false) 是无害 no-op，这里求干净）
  useEffect(() => () => cancelScheduledClose(), []);

  function cancelScheduledClose() {
    if (closeTimerRef.current !== null) {
      window.clearTimeout(closeTimerRef.current);
      closeTimerRef.current = null;
    }
  }

  function scheduleClose() {
    cancelScheduledClose();
    closeTimerRef.current = window.setTimeout(() => setOpen(false), HOVER_CLOSE_DELAY_MS);
  }

  function openAt(clientX: number, clientY: number) {
    cancelScheduledClose();
    setAnchor({ x: clientX, y: clientY });
    setOpen(true);
    window.dispatchEvent(new CustomEvent(SOURCE_POPOVER_OPEN_EVENT, { detail: badgeId }));
  }

  return (
    <>
      <button
        type="button"
        className="rounded-full bg-[var(--feed-bg)] px-2 py-0.5 text-[11px] font-semibold text-[var(--feed-text-tertiary)] transition-colors hover:bg-[var(--feed-line-soft)]"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={zh ? `${sources.length} 个信源` : `${sources.length} sources`}
        onMouseEnter={(event) => openAt(event.clientX, event.clientY)}
        onMouseLeave={scheduleClose}
        onClick={(event) => {
          event.stopPropagation();
          if (open) {
            setOpen(false);
          } else {
            openAt(event.clientX, event.clientY);
          }
        }}
      >
        {zh ? `${sources.length} 源` : `${sources.length} src`}
      </button>
      {open && (
        <SourceListPopover
          sources={sources}
          onClose={() => setOpen(false)}
          anchorX={anchor.x}
          anchorY={anchor.y}
          onMouseEnter={cancelScheduledClose}
          onMouseLeave={scheduleClose}
        />
      )}
    </>
  );
}
