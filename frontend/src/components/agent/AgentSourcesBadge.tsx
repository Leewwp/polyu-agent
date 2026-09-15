import * as React from "react";

import { SourceIcon } from "@/components/chat/SourceIcon";
import { cn } from "@/lib/utils";
import type { SourceRef } from "@/types";

/**
 * agent 链工具块的来源徽章：自包含折叠态 + 内联展开列表。
 * 刻意不复用 workflow 的 SourcesButton——它绑 chatStore 侧栏面板，
 * 拖进 agent 页等于跨引擎引 store；同形态自包含是改动更小的那条路。
 *
 * 来源两态（doc 32 决策一，字段与 workflow 侧 SourceRef 对齐）：
 * - url 型（source.url=官网原始页）：「查看原文」外链直跳，不再进站内预览；
 * - file 型（source.url=官网下载页，回填 SQL 维护）：摘录=命中段落全文（后端放宽），
 *   底部「获取《文档名》」按钮外链官网下载——站内不要求下载 PDF 才能看来源；
 * - 无 url（file 型未回填）：回落站内 /preview 预览（旧行为，含「暂不支持预览」形态）。
 */
export function AgentSourcesBadge({ sources }: { sources?: SourceRef[] }) {
  const [open, setOpen] = React.useState(false);

  if (!sources || sources.length === 0) {
    return null;
  }
  const preview = sources.slice(0, 3);

  return (
    <div className="agent-sources">
      <button
        type="button"
        className="agent-sources-badge"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
      >
        <span className="flex items-center">
          {preview.map((source, idx) => (
            <span
              key={`${source.docId}-${idx}`}
              className={cn(
                "flex h-5 w-5 items-center justify-center rounded-md bg-white ring-1 ring-[#EAEAEA]",
                idx > 0 && "-ml-1.5"
              )}
            >
              <SourceIcon source={source} className="h-3 w-3" />
            </span>
          ))}
        </span>
        {sources.length} 篇来源
        <span className="agent-caret" aria-hidden="true">{open ? "▾" : "▸"}</span>
      </button>
      {open ? (
        <ul className="agent-sources-list">
          {sources.map((source, idx) => {
            const isFileDoc = source.sourceType === "file";
            const externalUrl = source.url || null;
            const docLabel = source.docName || `文档 ${source.docId}`;
            const docTitle = docLabel.replace(/\.[a-z]+$/i, "");
            return (
              <li key={`${source.docId}-${idx}`} className="agent-sources-item">
                <span className="agent-sources-icon">
                  <SourceIcon source={source} className="h-3.5 w-3.5" />
                </span>
                <div className="agent-sources-body">
                  <div className="agent-sources-name">{docLabel}</div>
                  {source.excerpt ? (
                    <div className="agent-sources-excerpt">{source.excerpt}</div>
                  ) : null}
                  {externalUrl ? (
                    isFileDoc ? (
                      <a
                        className="agent-sources-link agent-sources-download"
                        href={externalUrl}
                        target="_blank"
                        rel="noreferrer"
                      >
                        获取《{docTitle}》↗
                      </a>
                    ) : (
                      <a
                        className="agent-sources-link"
                        href={externalUrl}
                        target="_blank"
                        rel="noreferrer"
                      >
                        查看原文 ↗
                      </a>
                    )
                  ) : (
                    <a className="agent-sources-link" href={`/preview/doc/${source.docId}`}>
                      查看原文
                    </a>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}
