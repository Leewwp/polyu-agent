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
 * #140 深链四态状态机（doc44 D-10）+L32 语义保留：
 * S1 列表未落定不加载目标；S2 失败保 URL+重试条不误判；S3 确认归属才 selectSession；
 * S4 确认不存在→统一「无法打开此会话」卡，「开始新对话」点击才导航（不再静默踢回）。
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
      messagesSessionId: null,
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

  it("S1：列表未落定不判不存在，也不启动目标加载（selectSession 不被调）", async () => {
    let settle: (() => void) | null = null;
    useChatStore.setState({
      fetchSessions: vi.fn(
        () =>
          new Promise<void>((resolve) => {
            settle = resolve;
          })
      ),
      selectSession: vi.fn(async () => undefined),
      createSession: vi.fn(async () => "")
    });

    renderWithRoutes("/chat/s-deep");

    // 列表挂起期：URL 保持、不判「不存在」、不加载目标、不新建
    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe("/chat/s-deep");
    });
    expect(useChatStore.getState().selectSession).not.toHaveBeenCalled();
    expect(useChatStore.getState().createSession).not.toHaveBeenCalled();
    expect(screen.queryByText(/无法打开此会话/)).toBeNull();
    // 放行列表（仍不含目标）→ 进入 S4（下一例覆盖）
    (settle as unknown as (() => void) | null)?.();
  });

  it("S2：列表失败保深链+重试条，不误判不存在不加载目标（L32 语义保留）", async () => {
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
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "重试" })).toBeTruthy();
    });
    // #140：S2 不加载目标（原实现深链即刻 selectSession，现收紧到 S3）
    expect(useChatStore.getState().selectSession).not.toHaveBeenCalled();
    expect(useChatStore.getState().createSession).not.toHaveBeenCalled();
    expect(screen.queryByText(/无法打开此会话/)).toBeNull();
  });

  it("S4：重试成功确认不存在→统一「无法打开此会话」卡，不自动踢回；点「开始新对话」才导航", async () => {
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
    fireEvent.click(screen.getByRole("button", { name: "重试" }));

    // S4：URL 保持原深链，错误卡在场，不自动新建/导航
    await waitFor(() => {
      expect(screen.getByText("无法打开此会话")).toBeTruthy();
    });
    expect(screen.getByText(/该会话可能不存在、已删除，或属于其他账号/)).toBeTruthy();
    expect(screen.getByText(/让对方使用对话页面中的「分享」功能/)).toBeTruthy();
    expect(screen.getByTestId("location").textContent).toBe("/chat/s-deep");
    expect(useChatStore.getState().createSession).not.toHaveBeenCalled();
    expect(useChatStore.getState().selectSession).not.toHaveBeenCalled();

    // 用户点击「开始新对话」才触发新建+导航
    fireEvent.click(screen.getByRole("button", { name: "开始新对话" }));
    await waitFor(() => {
      expect(screen.getByTestId("location").textContent).toBe("/chat");
    });
    expect(useChatStore.getState().createSession).toHaveBeenCalled();
  });

  it("S3：列表确认含目标才 selectSession", async () => {
    useChatStore.setState({
      fetchSessions: vi.fn(async () => {
        useChatStore.setState({
          sessions: [{ id: "s-deep", title: "目标会话" }],
          sessionsError: null,
          sessionsLoaded: true,
          sessionsLoading: false
        });
      }),
      selectSession: vi.fn(async () => undefined),
      createSession: vi.fn(async () => "")
    });

    renderWithRoutes("/chat/s-deep");
    await waitFor(() => {
      expect(useChatStore.getState().selectSession).toHaveBeenCalledWith("s-deep");
    });
    expect(useChatStore.getState().createSession).not.toHaveBeenCalled();
  });
});
