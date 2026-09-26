import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

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
      messagesSessionId: null,
      isLoading: false,
      isStreaming: false,
      isCreatingNew: false,
      sessionsError: null,
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

/**
 * #140 深链四态（agent 链，与 ChatPage 同语义）+旧内容清零（N6）。
 * AgentMessageList 已 mock 为锚点：S1/S2/归属未换期间锚点不得出现（旧正文零渲染）。
 */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderAgentDeepLink(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <LocationProbe />
      <Routes>
        <Route path="/chat/:sessionId" element={<AgentChatPage />} />
        <Route path="/chat" element={<AgentChatPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("AgentChatPage 深链四态（#140）", () => {
  beforeEach(() => {
    useAgentChatStore.setState({
      sessions: [],
      currentSessionId: null,
      messages: [],
      messagesSessionId: null,
      isLoading: false,
      isStreaming: false,
      isCreatingNew: false,
      sessionsError: null,
      loadSessions: vi.fn(async () => undefined),
      loadMessages: vi.fn(async () => undefined),
      startNewChat: vi.fn()
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("S1：列表未落定不加载目标；旧会话正文不渲染（旧内容清零）", async () => {
    // 上一会话残留：store 里有旧消息但归属不是深链目标
    useAgentChatStore.setState({
      messages: [
        { id: "m-old", role: "user", content: "上一会话的正文", createdAt: "2026-01-01T09:00:00" }
      ],
      messagesSessionId: "s-old",
      loadSessions: vi.fn(
        () => new Promise<void>(() => undefined) // 永不落定
      )
    });

    renderAgentDeepLink("/chat/s-bad");

    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe("/chat/s-bad");
    });
    expect(useAgentChatStore.getState().loadMessages).not.toHaveBeenCalled();
    expect(screen.queryByTestId("agent-message-list")).toBeNull();
    expect(screen.queryByText("上一会话的正文")).toBeNull();
    expect(screen.queryByText(/无法打开此会话/)).toBeNull();
  });

  it("S2：列表失败保 URL+重试条，不加载目标（L32 保留）", async () => {
    useAgentChatStore.setState({
      loadSessions: vi.fn(async () => {
        useAgentChatStore.setState({ sessionsError: "加载会话失败" });
      })
    });

    renderAgentDeepLink("/chat/s-bad");
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "重试" })).toBeTruthy();
    });
    expect(screen.getByTestId("location").textContent).toBe("/chat/s-bad");
    expect(useAgentChatStore.getState().loadMessages).not.toHaveBeenCalled();
    expect(screen.queryByText(/无法打开此会话/)).toBeNull();
  });

  it("S3：列表确认含目标才 loadMessages", async () => {
    useAgentChatStore.setState({
      loadSessions: vi.fn(async () => {
        useAgentChatStore.setState({
          sessions: [{ id: "s-deep", title: "目标会话", lastTime: "2026-09-01" }],
          sessionsError: null
        });
      })
    });

    renderAgentDeepLink("/chat/s-deep");
    await waitFor(() => {
      expect(useAgentChatStore.getState().loadMessages).toHaveBeenCalledWith("s-deep");
    });
    expect(useAgentChatStore.getState().startNewChat).not.toHaveBeenCalled();
  });

  it("S4：确认不存在→统一错误卡（zh），URL 保持；「开始新对话」点击才新建+导航", async () => {
    useAgentChatStore.setState({
      loadSessions: vi.fn(async () => undefined) // 列表成功但不含 s-bad
    });

    renderAgentDeepLink("/chat/s-bad");

    await waitFor(() => {
      expect(screen.getByText("无法打开此会话")).toBeTruthy();
    });
    expect(screen.getByText(/该会话可能不存在、已删除，或属于其他账号/)).toBeTruthy();
    expect(screen.getByText(/让对方使用对话页面中的「分享」功能/)).toBeTruthy();
    // 不泄露 session ID
    expect(document.querySelector(".agent-app")?.textContent).not.toContain("s-bad");
    expect(screen.getByTestId("location").textContent).toBe("/chat/s-bad");
    expect(useAgentChatStore.getState().startNewChat).not.toHaveBeenCalled();
    expect(useAgentChatStore.getState().loadMessages).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "开始新对话" }));
    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe("/chat");
    });
    expect(useAgentChatStore.getState().startNewChat).toHaveBeenCalled();
  });

  it("S4 错误卡随英文（en 例，双语合同）", async () => {
    const mem = new Map<string, string>([["polyu.feed.lang", "en"]]);
    Object.defineProperty(window, "localStorage", {
      value: {
        getItem: (key: string) => mem.get(key) ?? null,
        setItem: (key: string, value: string) => void mem.set(key, value),
        removeItem: (key: string) => void mem.delete(key)
      },
      configurable: true
    });
    useAgentChatStore.setState({
      loadSessions: vi.fn(async () => undefined)
    });

    renderAgentDeepLink("/chat/s-bad");
    await waitFor(() => {
      expect(screen.getByText("Can't open this conversation")).toBeTruthy();
    });
    expect(screen.getByRole("button", { name: "Start a new chat" })).toBeTruthy();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });
});
