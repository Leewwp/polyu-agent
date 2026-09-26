import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook } from "@testing-library/react";

import { useIsMobile } from "./useIsMobile";

/**
 * #136 统一视口判断入口：jsdom 无真实布局，matchMedia 全程手搓桩驱动——
 * 覆盖初值读取、跨 860 断点 change 事件、挂载对齐、卸载摘监听、无 matchMedia 回落 false。
 * jsdom 环境下 window 即全局对象，stub 全局 matchMedia 即等于 window.matchMedia。
 */

type ChangeListener = (event: { matches: boolean }) => void;

interface MatchMediaStub {
  listeners: Set<ChangeListener>;
  fire: (matches: boolean) => void;
}

function installMatchMedia(initialMatches: boolean): MatchMediaStub {
  const listeners = new Set<ChangeListener>();
  const mql = {
    matches: initialMatches,
    addEventListener: (type: string, listener: ChangeListener) => {
      if (type === "change") {
        listeners.add(listener);
      }
    },
    removeEventListener: (type: string, listener: ChangeListener) => {
      if (type === "change") {
        listeners.delete(listener);
      }
    }
  };
  vi.stubGlobal("matchMedia", () => mql);
  return {
    listeners,
    fire: (matches: boolean) => {
      mql.matches = matches;
      for (const listener of listeners) {
        listener({ matches });
      }
    }
  };
}

describe("useIsMobile", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("reads the initial match state at first render (≤860 = mobile)", () => {
    installMatchMedia(true);
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(true);
  });

  it("starts false on desktop widths (>860)", () => {
    installMatchMedia(false);
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);
  });

  it("updates when the viewport crosses the 860 breakpoint", () => {
    const { fire } = installMatchMedia(false);
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);

    act(() => fire(true));
    expect(result.current).toBe(true);

    act(() => fire(false));
    expect(result.current).toBe(false);
  });

  it("re-aligns on mount if the breakpoint moved before listeners attached", () => {
    // 初次调用（首渲染读初值）返回 false，挂载效应再查一次返回 true：以挂载对齐为准
    let calls = 0;
    const mql = {
      matches: false,
      addEventListener: () => undefined,
      removeEventListener: () => undefined
    };
    vi.stubGlobal("matchMedia", () => {
      calls += 1;
      return calls === 1 ? mql : { ...mql, matches: true };
    });
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(true);
  });

  it("removes the change listener on unmount", () => {
    const { listeners } = installMatchMedia(false);
    const { unmount } = renderHook(() => useIsMobile());
    expect(listeners.size).toBe(1);
    unmount();
    expect(listeners.size).toBe(0);
  });

  it("falls back to desktop (false) when matchMedia is unavailable", () => {
    vi.stubGlobal("matchMedia", undefined);
    const { result } = renderHook(() => useIsMobile());
    expect(result.current).toBe(false);
  });
});
