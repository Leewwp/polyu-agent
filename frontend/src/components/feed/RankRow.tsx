import { Link } from "react-router-dom";
import type { ReactNode } from "react";

import type { HotRankEntry } from "@/types/news";

/**
 * 热点条目行（HotPanel/HotRankPage 共享；2026-09-12 修复）：
 * 真数据条目带 itemId → 整行 Link 入 /news/:id 详情（hover 底色提示可点）；
 * mock fixture 无 itemId → 纯文本行（原型纯展示回退）。
 * key 由调用方挂在本组件上（itemId ?? title 幂等键）。
 */
export function RankRow({
  entry,
  className,
  title,
  children
}: {
  entry: HotRankEntry;
  className: string;
  title?: string;
  children: ReactNode;
}) {
  return (
    <li>
      {entry.itemId !== undefined ? (
        <Link
          to={`/news/${entry.itemId}`}
          className={`${className} transition-colors hover:bg-[var(--feed-bg)]`}
          title={title}
        >
          {children}
        </Link>
      ) : (
        <div className={className}>{children}</div>
      )}
    </li>
  );
}
