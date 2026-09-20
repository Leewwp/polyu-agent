import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import { ChatPage } from "./ChatPage";
import { useChatStore } from "@/stores/chatStore";

/**
 * 换壳冒烟：ChatPage 外层 MainLayout → FeedShell（fluid）——
 * 侧栏品牌 PolyUGuide 渲染、上游壳残留（Ragent 品牌）不再出现、聊天主体在壳内挂载。
 * 聊天主体组件 mock 为锚点，本文件只测壳；会话/消息逻辑由上游既有行为保证（无既有测试面）。
 */

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  fetchGuestQuota: vi.fn(async () => ({ role: "guest", dailyLimit: 3, remaining: 1 }))
}));

vi.mock("sonner", () => ({ toast: vi.fn() }));

vi.mock("@/components/chat/MessageList", () => ({ MessageList: () => <div data-testid="message-list" /> }));
vi.mock("@/components/chat/ChatInput", () => ({ ChatInput: () => <div data-testid="chat-input" /> }));
vi.mock("@/components/chat/SourcesPanel", () => ({ SourcesPanel: () => <aside data-testid="sources-panel" /> }));
vi.mock("@/components/chat/GuestStatusBadge", () => ({ GuestStatusBadge: () => null }));

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/chat"]}>
      <ChatPage />
    </MemoryRouter>
  );
}

describe("ChatPage (FeedShell 换壳)", () => {
  beforeEach(() => {
    useChatStore.setState({
      sessions: [],
      currentSessionId: "s1",
      messages: [],
      isLoading: false,
      sessionsLoaded: true,
      isStreaming: false,
      isCreatingNew: false,
      fetchSessions: vi.fn(async () => undefined),
      selectSession: vi.fn(async () => undefined),
      createSession: vi.fn(async () => "s1")
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renders FeedShell sidebar with PolyUGuide brand and no upstream Ragent branding", () => {
    const { container } = renderPage();
    // 侧栏品牌命名：PolyUGuide
    expect(screen.getAllByText("PolyUGuide").length).toBeGreaterThan(0);
    // 上游壳品牌不再渲染（MainLayout/Sidebar/Header 随换壳退出）
    expect(screen.queryByText(/Ragent AI 智能体/)).toBeNull();
    expect(container.textContent).not.toContain("nageoffer");
    // 桌面顶栏标题=智能问答
    expect(screen.getByText("智能问答")).toBeTruthy();
  });

  it("mounts chat body (message list) inside the shell", () => {
    renderPage();
    expect(screen.getByTestId("message-list")).toBeTruthy();
    expect(screen.getByTestId("sources-panel")).toBeTruthy();
  });
});

/**
 * L32（#94）：深链「会话列表加载失败」≠「会话不存在」——
 * 列表失败时保深链给重试条，不踢回 /chat 不清深链；重试成功且会话确实不存在才踢。
 */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderWithRoutes(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <LocationProbe />
      <Routes>
        <Route path="/chat/:sessionId" element={<ChatPage />} />
        <Route path="/chat" element={<ChatPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("ChatPage 深链 × 会话列表失败（L32）", () => {
  beforeEach(() => {
    useChatStore.setState({
      sessions: [],
      currentSessionId: null,
      messages: [],
      isLoading: false,
      sessionsLoaded: false,
      sessionsLoading: false,
      sessionsError: null,
      isStreaming: false,
      isCreatingNew: false
    });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("keeps the deep link and offers retry when the session list fails to load", async () => {
    useChatStore.setState({
      fetchSessions: vi.fn(async () => {
        useChatStore.setState({ sessionsError: "加载会话失败", sessionsLoaded: true, sessionsLoading: false });
      }),
      selectSession: vi.fn(async () => undefined),
      createSession: vi.fn(async () => "")
    });

    renderWithRoutes("/chat/s-deep");

    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe("/chat/s-deep");
    });
    // 列表失败态：重试条出现、不误踢
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "重试" })).toBeTruthy();
    });
    expect(useChatStore.getState().selectSession).toHaveBeenCalledWith("s-deep");
    expect(useChatStore.getState().createSession).not.toHaveBeenCalled();
  });

  it("kicks back to /chat only after a successful list load confirms the session missing", async () => {
    let attempts = 0;
    useChatStore.setState({
      fetchSessions: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          // 首拉失败
          useChatStore.setState({ sessionsError: "加载会话失败", sessionsLoaded: true, sessionsLoading: false });
          return;
        }
        // 重试成功：列表确实没有 s-deep
        useChatStore.setState({ sessions: [], sessionsError: null, sessionsLoaded: true, sessionsLoading: false });
      }),
      selectSession: vi.fn(async () => undefined),
      createSession: vi.fn(async () => "")
    });

    renderWithRoutes("/chat/s-deep");

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "重试" })).toBeTruthy();
    });
    expect(screen.getByTestId("location").textContent).toBe("/chat/s-deep");

    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe("/chat");
    });
    expect(useChatStore.getState().createSession).toHaveBeenCalled();
  });
});
