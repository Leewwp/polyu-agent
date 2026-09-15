import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { AgentChatPage } from "./AgentChatPage";
import { useAgentChatStore } from "@/stores/agentChatStore";

/**
 * 换壳冒烟：AgentChatPage 外层 AgentLayout → FeedShell（fluid）——
 * RAGENT 品牌/nageoffer GitHub 星钮随 AgentHeader 不再渲染；.agent-app 域以
 * agent-embedded 形态嵌入（高度交给壳），agent-main 网格保留承载事件流+输入条。
 * 主体组件 mock 为锚点，本文件只测壳。
 */

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  fetchGuestQuota: vi.fn(async () => ({ role: "guest", dailyLimit: 3, remaining: 1 }))
}));

vi.mock("sonner", () => ({ toast: vi.fn() }));

vi.mock("@/components/agent/AgentMessageList", () => ({
  AgentMessageList: () => <div data-testid="agent-message-list" />
}));
vi.mock("@/components/agent/AgentChatInput", () => ({
  AgentChatInput: () => <div data-testid="agent-chat-input" />
}));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/chat"]}>
      <AgentChatPage />
    </MemoryRouter>
  );
}

describe("AgentChatPage (FeedShell 换壳)", () => {
  beforeEach(() => {
    useAgentChatStore.setState({
      sessions: [],
      currentSessionId: "s1",
      messages: [],
      isLoading: false,
      isStreaming: false,
      isCreatingNew: false,
      loadSessions: vi.fn(async () => undefined),
      loadMessages: vi.fn(async () => undefined),
      startNewChat: vi.fn()
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renders FeedShell with PolyUGuide brand and no RAGENT/nageoffer branding", () => {
    const { container } = renderPage();
    expect(screen.getAllByText("PolyUGuide").length).toBeGreaterThan(0);
    // AgentHeader 的 RAGENT 品牌与 nageoffer GitHub 星钮随换壳不再渲染
    expect(container.textContent).not.toContain("RAGENT");
    expect(container.textContent).not.toContain("nageoffer");
    expect(screen.getByText("智能体对话")).toBeTruthy();
  });

  it("keeps agent-app domain (agent-embedded) and agent-main grid inside the shell", () => {
    const { container } = renderPage();
    const embedded = container.querySelector(".agent-app.agent-embedded");
    expect(embedded).not.toBeNull();
    expect(embedded?.querySelector(".agent-main")).not.toBeNull();
    expect(screen.getByTestId("agent-message-list")).toBeTruthy();
    expect(screen.getByTestId("agent-chat-input")).toBeTruthy();
  });
});
