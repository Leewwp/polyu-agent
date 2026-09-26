import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

const listSampleQuestions = vi.fn();

vi.mock("@/services/sampleQuestionService", () => ({
  listSampleQuestions: (...args: unknown[]) => listSampleQuestions(...args)
}));

let mockLang = "zh";
vi.mock("@/components/feed/feedLang", () => ({
  useOptionalFeedLang: () => ({ lang: mockLang }),
  useFeedLang: () => {
    throw new Error("useFeedLang 必须在 FeedLangProvider（FeedShell）内使用");
  }
}));

import { AgentWelcomeScreen } from "./AgentWelcomeScreen";
import { useAgentChatStore } from "@/stores/agentChatStore";

/** #137 统一视口入口直桩：matchMedia 返回可控 matches（jsdom 无真实布局） */
function stubViewport(matches: boolean) {
  const mql = {
    matches,
    addEventListener: () => undefined,
    removeEventListener: () => undefined
  };
  vi.stubGlobal("matchMedia", () => mql);
}

describe("AgentWelcomeScreen 双语（T20 A 步）", () => {
  beforeEach(() => {
    // 模块级缓存按 lang 分键：每例用全新模块隔离更稳，退而求其次换语言各键互不污染
    vi.resetModules();
    mockLang = "zh";
    stubViewport(false);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it("zh：渲染图书馆演示卡中文版与中文图注/引导语，chips 请求带 lang=zh", async () => {
    listSampleQuestions.mockResolvedValue([
      { id: "1", question: "图书馆的开放时间是怎样的？" }
    ]);

    render(<AgentWelcomeScreen />);

    expect(screen.getByText("图书馆的开放时间是怎样的？周末有区别吗？24 小时自习区在哪里？")).toBeTruthy();
    expect(screen.getByText("提问")).toBeTruthy();
    await waitFor(() => {
      expect(listSampleQuestions).toHaveBeenCalledWith(4, "zh");
    });
    await waitFor(() => {
      expect(screen.getByText("试试这些")).toBeTruthy();
    });
    expect(screen.getByText("图书馆的开放时间是怎样的？", { selector: ".agent-empty-chip span" })).toBeTruthy();
  });

  it("en：演示卡/图注/引导语/chips 全跟随英文", async () => {
    mockLang = "en";
    listSampleQuestions.mockResolvedValue([
      { id: "2", question: "What are the Library opening hours?" }
    ]);

    render(<AgentWelcomeScreen />);

    expect(
      screen.getByText(
        "What are the Library opening hours? Are weekends different? Where is the 24-hour study area?"
      )
    ).toBeTruthy();
    expect(screen.getByText("Ask")).toBeTruthy();
    await waitFor(() => {
      expect(listSampleQuestions).toHaveBeenCalledWith(4, "en");
    });
    await waitFor(() => {
      expect(screen.getByText("Try these")).toBeTruthy();
    });
    expect(
      screen.getByText("What are the Library opening hours?", { selector: ".agent-empty-chip span" })
    ).toBeTruthy();
    // 中文版内容不残留
    expect(screen.queryByText("试试这些")).toBeNull();
  });
});

describe("AgentWelcomeScreen 移动分流（#137）", () => {
  beforeEach(() => {
    mockLang = "zh";
    useAgentChatStore.setState({ draft: null });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    vi.unstubAllGlobals();
  });

  it("mobile：Demo DOM 不挂载，只剩能力说明+示例问题（zh）", async () => {
    stubViewport(true);
    // 注：模块级示例缓存按 lang 分键，本例不重设 mock 返回值——断言芯片在场即可
    // （缓存里是此前用例取回的中文问句），点击预填行为由下一例专项断言。
    listSampleQuestions.mockResolvedValue([{ id: "1", question: "学费什么时候截止缴纳？" }]);

    render(<AgentWelcomeScreen />);

    // 一句话能力说明（#137 新文案，走 feedLang）
    expect(screen.getByText("我可以帮你查询 PolyU 的课程、缴费、图书馆、校园服务等信息。")).toBeTruthy();
    // 完整 Demo 与图注带不进移动 DOM（JS 分流非 CSS 隐藏）
    expect(document.querySelector(".agent-empty-figure")).toBeNull();
    expect(screen.queryByText("提问")).toBeNull();
    await waitFor(() => {
      expect(document.querySelector(".agent-empty-chip")).not.toBeNull();
    });
  });

  it("mobile：能力说明跟随英文（en 例）", () => {
    stubViewport(true);
    mockLang = "en";
    listSampleQuestions.mockResolvedValue([]);

    render(<AgentWelcomeScreen />);

    expect(
      screen.getByText("I can help with PolyU courses, fees, the Library, campus services and more.")
    ).toBeTruthy();
    expect(screen.queryByText("我可以帮你查询 PolyU 的课程、缴费、图书馆、校园服务等信息。")).toBeNull();
  });

  it("mobile：示例问题点击仍预填输入框（setDraft 行为不回归）", async () => {
    stubViewport(true);
    listSampleQuestions.mockResolvedValue([{ id: "1", question: "图书馆的开放时间是怎样的？" }]);

    render(<AgentWelcomeScreen />);
    await waitFor(() => {
      expect(screen.getByText("图书馆的开放时间是怎样的？", { selector: ".agent-empty-chip span" })).toBeTruthy();
    });

    const user = userEvent.setup();
    await user.click(screen.getByText("图书馆的开放时间是怎样的？", { selector: ".agent-empty-chip span" }));
    expect(useAgentChatStore.getState().draft?.text).toBe("图书馆的开放时间是怎样的？");
  });

  it("desktop：完整 Demo 照常挂载（分流不误伤桌面）", () => {
    stubViewport(false);
    listSampleQuestions.mockResolvedValue([]);

    render(<AgentWelcomeScreen />);

    expect(document.querySelector(".agent-empty-figure")).not.toBeNull();
    expect(screen.getByText("提问")).toBeTruthy();
    expect(screen.queryByText("我可以帮你查询 PolyU 的课程、缴费、图书馆、校园服务等信息。")).toBeNull();
  });
});
