import { describe, expect, it } from "vitest";

import { applyToolFrame, replayBlock } from "./agentTimeline";
import type { AgentBlock, AgentToolProgress } from "@/types/agent";

const sources = [
  { docId: "doc-42", docName: "图书馆服务指南", excerpt: "游泳池开放时间为早七至晚十…" }
];

describe("agentTimeline sources 透传", () => {
  it("applyToolFrame 随终态帧带上 sources 且后续帧不清掉", () => {
    const allocId = (() => {
      let next = 0;
      return () => ++next;
    })();
    const ctx = { allocId, fallbackAt: "12:00:00" };
    const running: AgentToolProgress = {
      toolCallId: "call-1",
      name: "search_knowledge",
      displayName: "知识库检索",
      status: "running"
    };
    const done: AgentToolProgress = {
      toolCallId: "call-1",
      name: "search_knowledge",
      displayName: "知识库检索",
      status: "done",
      result: "开放时间是早七至晚十",
      sources
    };

    const blocks = applyToolFrame(applyToolFrame([], running, ctx), done, ctx);

    expect(blocks).toHaveLength(1);
    expect(blocks[0].sources).toEqual(sources);
  });

  it("replayBlock 落库回放透传 sources 老数据无字段时不渲染来源", () => {
    const withSources: AgentBlock = {
      kind: "tool",
      at: "2026-09-15T12:00:00",
      name: "search_knowledge",
      status: "done",
      toolCallId: "call-1",
      sources
    };
    const legacy: AgentBlock = {
      kind: "tool",
      at: "2026-09-01T12:00:00",
      name: "search_knowledge",
      status: "done"
    };

    expect(replayBlock(withSources, 1).sources).toEqual(sources);
    expect(replayBlock(legacy, 2).sources).toBeUndefined();
  });
});
