import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

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

describe("AgentWelcomeScreen 双语（T20 A 步）", () => {
  beforeEach(() => {
    // 模块级缓存按 lang 分键：每例用全新模块隔离更稳，退而求其次换语言各键互不污染
    vi.resetModules();
    mockLang = "zh";
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
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
