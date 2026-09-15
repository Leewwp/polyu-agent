import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

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
