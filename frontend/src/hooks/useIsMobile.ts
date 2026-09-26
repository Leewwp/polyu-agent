import { useEffect, useState } from "react";

/**
 * Agent JS 响应式行为的唯一视口判断入口（issue #136，doc44 D-1）：
 * 断点=860px（与 FeedShell 壳的 860/861 档同一身份线），≤860 为 mobile identity。
 * 本轮 Agent 组件内禁止另写 matchMedia——Reasoning 真折叠（#137）、Welcome 分流（#137）、
 * Composer 增高上限双档（#136）均读本 hook；资讯/管理端继续用 Tailwind 断点类不受约束。
 * jsdom 无真实布局引擎：matchMedia 缺失时回落 false（desktop 形态），单测自装桩驱动。
 */
const MOBILE_QUERY = "(max-width: 860px)";

function readMobileMatch(): boolean {
  return typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia(MOBILE_QUERY).matches
    : false;
}

export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(readMobileMatch);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") {
      return;
    }
    const mql = window.matchMedia(MOBILE_QUERY);
    const handleChange = (event: MediaQueryListEvent) => {
      setIsMobile(event.matches);
    };
    mql.addEventListener("change", handleChange);
    // 首渲染与监听建立之间断点可能已变（如 SSR 水合/挂载延迟），挂载时再对齐一次
    setIsMobile(mql.matches);
    return () => {
      mql.removeEventListener("change", handleChange);
    };
  }, []);

  return isMobile;
}
