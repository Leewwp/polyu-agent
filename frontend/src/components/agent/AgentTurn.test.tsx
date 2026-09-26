import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

let mockLang = "zh";
vi.mock("@/components/feed/feedLang", () => ({
  useOptionalFeedLang: () => ({ lang: mockLang }),
  useFeedLang: () => {
    throw new Error("useFeedLang 必须在 FeedLangProvider（FeedShell）内使用");
  }
}));

import { AgentTurnItem } from "./AgentTurn";
import { groupTurns } from "./AgentMessageList";
import { useAgentChatStore } from "@/stores/agentChatStore";
import type { AgentBlockUI, AgentMessage, AgentTurn } from "@/types/agent";

/**
 * #137 AgentTurn 直接组件测试（票面两项：metadata 层级 + groupTurns 一问多答）
 * 与 reasoning open 语义重定义五例。视口维度经统一入口 useIsMobile 的 matchMedia 桩驱动；
 * block.open 只代表用户显式展开，实际展开=派生（block.open || (streaming && !isMobile)）。
 */

function stubViewport(matches: boolean) {
  const mql = {
    matches,
    addEventListener: () => undefined,
    removeEventListener: () => undefined
  };
  vi.stubGlobal("matchMedia", () => mql);
}

function buildTurn(assistants: AgentMessage[], overrides?: Partial<AgentTurn>): AgentTurn {
  return {
    id: "t-1",
    index: 1,
    user: {
      id: "u-1",
      role: "user",
      content: "图书馆的开放时间是怎样的？",
      createdAt: "2026-01-01T09:41:03"
    },
    assistants,
    ...overrides
  };
}

function reasoningBlock(over: Partial<AgentBlockUI> = {}): AgentBlockUI {
  return { id: 1, kind: "reasoning", at: "09:41:03", text: "先检索学期开放时间。", ...over };
}

/** store 里同构放一条消息：toggleBlockOpen 按 messageId 命中这些块 */
function seedStore(assistants: AgentMessage[]) {
  useAgentChatStore.setState({
    messages: [
      {
        id: "u-1",
        role: "user",
        content: "图书馆的开放时间是怎样的？",
        createdAt: "2026-01-01T09:41:03"
      },
      ...assistants
    ]
  });
}

function reasoningToggle(): HTMLElement {
  return document.querySelector(".agent-reasoning-toggle") as HTMLElement;
}

/**
 * live 链路同构：AgentMessageList 每渲染从 store messages 投影 turns——
 * 显式展开要体现到画面必须走这条链，静态 turn prop 不会随 store 更新。
 */
function StoreBackedTurn() {
  const messages = useAgentChatStore((state) => state.messages);
  const turns = groupTurns(messages);
  if (turns.length === 0) return null;
  return <AgentTurnItem turn={turns[0]} />;
}

beforeEach(() => {
  mockLang = "zh";
  stubViewport(false);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("AgentTurnItem 工具行 metadata 层级", () => {
  it("有中文名的工具行：通道/raw 名 chip/中文名/状态/耗时齐备，行级时刻在后续行显示", () => {
    const tool: AgentBlockUI = {
      id: 2,
      kind: "tool",
      at: "09:41:04",
      name: "search_knowledge",
      displayName: "知识库检索",
      status: "done",
      durationMs: 1200,
      result: "开放时间全文…"
    };
    render(<AgentTurnItem turn={buildTurn([{ id: "a-1", role: "assistant", content: "", status: "done", createdAt: "2026-01-01T09:41:03", blocks: [tool] }])} />);

    const meta = document.querySelector('.agent-row[data-channel="tool"] .agent-meta-line') as HTMLElement;
    expect(meta).toBeTruthy();
    // raw 工具名 chip（移动档由 CSS 收进展开态，DOM 常驻）
    expect(meta.querySelector(".agent-tool-chip")?.textContent).toBe("search_knowledge");
    expect(within(meta).getByText("知识库检索")).toBeTruthy();
    expect(within(meta).getByText("完成")).toBeTruthy();
    expect(within(meta).getByText(/1\.2s/)).toBeTruthy();
  });

  it("data-open 跟随块展开态（一级/详情分层的 CSS 消费锚点）", () => {
    const tool: AgentBlockUI = {
      id: 2,
      kind: "tool",
      at: "09:41:04",
      name: "search_knowledge",
      displayName: "知识库检索",
      status: "done",
      open: true,
      result: "开放时间全文…"
    };
    render(<AgentTurnItem turn={buildTurn([{ id: "a-1", role: "assistant", content: "", status: "done", createdAt: "2026-01-01T09:41:03", blocks: [tool] }])} />);
    expect(document.querySelector('.agent-row[data-channel="tool"]')?.getAttribute("data-open")).toBe("true");
  });

  it("无中文名时渲染通用工具标签（zh 例）", () => {
    mockLang = "zh";
    const tool: AgentBlockUI = {
      id: 2,
      kind: "tool",
      at: "09:41:04",
      name: "search_knowledge",
      status: "done",
      result: "…"
    };
    render(<AgentTurnItem turn={buildTurn([{ id: "a-1", role: "assistant", content: "", status: "done", createdAt: "2026-01-01T09:41:03", blocks: [tool] }])} />);
    const label = document.querySelector(".agent-tool-generic");
    expect(label?.textContent).toBe("工具");
  });

  it("无中文名时渲染通用工具标签（en 例）", () => {
    mockLang = "en";
    const tool: AgentBlockUI = {
      id: 2,
      kind: "tool",
      at: "09:41:04",
      name: "search_knowledge",
      status: "done",
      result: "…"
    };
    render(<AgentTurnItem turn={buildTurn([{ id: "a-1", role: "assistant", content: "", status: "done", createdAt: "2026-01-01T09:41:03", blocks: [tool] }])} />);
    expect(document.querySelector(".agent-tool-generic")?.textContent).toBe("Tool");
  });
});

describe("ReasoningRow open 语义重定义（#137 五例）", () => {
  function seedReasoning(status: "streaming" | "done") {
    const assistant: AgentMessage = {
      id: "a-1",
      role: "assistant",
      content: "",
      status,
      createdAt: "2026-01-01T09:41:03",
      blocks: [reasoningBlock()]
    };
    seedStore([assistant]);
    return assistant;
  }

  function renderReasoning(status: "streaming" | "done") {
    const assistant = seedReasoning(status);
    render(<AgentTurnItem turn={buildTurn([assistant])} />);
  }

  it("例① mobile 流式：默认真折叠（若 store 预置 open:true 则此例必红——即「新建流式块默认无 user-open」的行为证明）", () => {
    stubViewport(true);
    renderReasoning("streaming");
    const toggle = reasoningToggle();
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(document.querySelector(".agent-reasoning-body")).toBeNull();
  });

  it("例② mobile 流式：点击展开（用户显式 open），再点收起", async () => {
    stubViewport(true);
    seedReasoning("streaming");
    render(<StoreBackedTurn />);
    const user = userEvent.setup();

    await user.click(reasoningToggle());
    expect(reasoningToggle().getAttribute("aria-expanded")).toBe("true");
    expect(document.querySelector(".agent-reasoning-body")).not.toBeNull();
    // store 侧：显式态落在 block.open（唯一用户显式开关，无第二套状态机）
    const stored = useAgentChatStore.getState().messages[1]?.blocks?.[0];
    expect(stored?.open).toBe(true);

    await user.click(reasoningToggle());
    expect(reasoningToggle().getAttribute("aria-expanded")).toBe("false");
    expect(document.querySelector(".agent-reasoning-body")).toBeNull();
  });

  it("例③ desktop 流式：由 streaming 派生自动展开（行为不回归）", () => {
    stubViewport(false);
    renderReasoning("streaming");
    expect(reasoningToggle().getAttribute("aria-expanded")).toBe("true");
    expect(document.querySelector(".agent-reasoning-body")).not.toBeNull();
  });

  it("例④ desktop 完成态：默认折叠（现状语义）", () => {
    stubViewport(false);
    renderReasoning("done");
    expect(reasoningToggle().getAttribute("aria-expanded")).toBe("false");
    expect(document.querySelector(".agent-reasoning-body")).toBeNull();
  });

  it("例⑤ mobile 完成态：仍可显式展开（aria 与实际显示一致）", async () => {
    stubViewport(true);
    seedReasoning("done");
    render(<StoreBackedTurn />);
    const user = userEvent.setup();
    await user.click(reasoningToggle());
    expect(reasoningToggle().getAttribute("aria-expanded")).toBe("true");
    expect(document.querySelector(".agent-reasoning-body")).not.toBeNull();
  });

  it("流式空文本折叠摘要=「正在思考…」（zh 例）", () => {
    stubViewport(true);
    mockLang = "zh";
    const assistant: AgentMessage = {
      id: "a-1",
      role: "assistant",
      content: "",
      status: "streaming",
      createdAt: "2026-01-01T09:41:03",
      blocks: [reasoningBlock({ text: "" })]
    };
    seedStore([assistant]);
    render(<AgentTurnItem turn={buildTurn([assistant])} />);
    expect(document.querySelector(".agent-reasoning-peek")?.textContent).toBe("正在思考…");
  });

  it("流式空文本折叠摘要=Thinking…（en 例）", () => {
    stubViewport(true);
    mockLang = "en";
    const assistant: AgentMessage = {
      id: "a-1",
      role: "assistant",
      content: "",
      status: "streaming",
      createdAt: "2026-01-01T09:41:03",
      blocks: [reasoningBlock({ text: "" })]
    };
    seedStore([assistant]);
    render(<AgentTurnItem turn={buildTurn([assistant])} />);
    expect(document.querySelector(".agent-reasoning-peek")?.textContent).toBe("Thinking…");
  });
});

describe("groupTurns 一问多答（确认续跑同轮）", () => {
  it("同轮两条 assistant 归一张 Turn 卡（live 渲染一次 TURN 头）", () => {
    const first: AgentMessage = {
      id: "a-1",
      role: "assistant",
      content: "",
      status: "done",
      createdAt: "2026-01-01T09:41:03",
      blocks: [reasoningBlock()]
    };
    const resumed: AgentMessage = {
      id: "a-2",
      role: "assistant",
      content: "续跑后的终答",
      status: "done",
      elapsedMs: 900,
      createdAt: "2026-01-01T09:41:10",
      blocks: [{ id: 9, kind: "answer", at: "09:41:11", text: "续跑后的终答" }]
    };
    seedStore([first, resumed]);
    render(<AgentTurnItem turn={buildTurn([first, resumed])} />);

    // 一问多答一张卡：TURN 头只出一个，两段 answer 轨迹都在
    expect(screen.getAllByText("TURN 1")).toHaveLength(1);
    expect(screen.getByText("续跑后的终答")).toBeTruthy();
  });

  it("纯函数口径：user 开新轮，assistant 挂最近一轮（无 user 前导则自立一轮）", () => {
    const messages: AgentMessage[] = [
      { id: "u-1", role: "user", content: "问一", createdAt: "2026-01-01T09:41:03" },
      { id: "a-1", role: "assistant", content: "答一", status: "done", createdAt: "2026-01-01T09:41:04" },
      { id: "a-2", role: "assistant", content: "答一续", status: "done", createdAt: "2026-01-01T09:41:05" },
      { id: "u-2", role: "user", content: "问二", createdAt: "2026-01-01T09:42:00" },
      { id: "a-3", role: "assistant", content: "答二", status: "done", createdAt: "2026-01-01T09:42:01" }
    ];
    const turns = groupTurns(messages);
    expect(turns).toHaveLength(2);
    expect(turns[0].assistants.map((a) => a.id)).toEqual(["a-1", "a-2"]);
    expect(turns[1].assistants.map((a) => a.id)).toEqual(["a-3"]);

    const headless = groupTurns([messages[1]]);
    expect(headless).toHaveLength(1);
    expect(headless[0].user).toBeUndefined();
    expect(headless[0].assistants).toHaveLength(1);
  });
});
