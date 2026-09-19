import { useRef } from "react";

/**
 * M19：admin 列表页请求序号守卫（抽取 traces 页 runsRequestRef 模式）。
 *
 * 快速翻页/切换筛选时在途慢响应后到，旧数据回写进新视图（图谱页会重建出
 * 错误实体的整张图）。用法：`const requestId = begin();` 领号，每个 await
 * 之后 `if (!isCurrent(requestId)) return;` 丢弃过期响应。
 */
export function useStaleRequest() {
  const seqRef = useRef(0);
  const begin = () => ++seqRef.current;
  const isCurrent = (requestId: number) => seqRef.current === requestId;
  return { begin, isCurrent };
}
