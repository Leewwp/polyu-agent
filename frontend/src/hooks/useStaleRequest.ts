import { useCallback, useMemo, useRef } from "react";

/**
 * M19：admin 列表页请求序号守卫（抽取 traces 页 runsRequestRef 模式）。
 *
 * 快速翻页/切换筛选时在途慢响应后到，旧数据回写进新视图（图谱页会重建出
 * 错误实体的整张图）。用法：`const requestId = begin();` 领号，每个 await
 * 之后 `if (!isCurrent(requestId)) return;` 丢弃过期响应。
 *
 * L43（#94 附带发现）：begin/isCurrent 必须身份稳定（只读写 ref）——
 * 多个列表页把它们写进 useCallback/useEffect 依赖，若每次渲染返回新闭包，
 * 每次 setState 都会重触发加载 effect，实测形成每渲染一发的无限请求循环。
 * 消费方依赖数组从此不再变化（除非 keyword/pageNo 等真实输入变化）。
 */
export function useStaleRequest() {
  const seqRef = useRef(0);
  const begin = useCallback(() => ++seqRef.current, []);
  const isCurrent = useCallback((requestId: number) => seqRef.current === requestId, []);
  return useMemo(() => ({ begin, isCurrent }), [begin, isCurrent]);
}
