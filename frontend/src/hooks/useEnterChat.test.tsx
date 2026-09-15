import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { useEnterChat } from "./useEnterChat";
import { guestLogin } from "@/services/authService";
import { useAuthStore } from "@/stores/authStore";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useChatStore } from "@/stores/chatStore";

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn(),
  fetchGuestQuota: vi.fn()
}));

/**
 * 游客直通 hook。
 * 2026-09-12 修复：fresh=true「新对话」须重置聊天 store——
 * zustand 单例 currentSessionId 跨页面残留会让 /chat 续到上一次会话；
 * 「对话」tab/FAB（fresh 缺省 false）维持进最近会话语义。
 */

// 组件探针封装：hook 需 Router 上下文；Routes 探针断言导航落地
function setup(options: { fresh?: boolean }) {
  let enter: (() => void) | null = null;
  const Harness = () => {
    const fn = useEnterChat(options);
    enter = fn;
    return (
      <Routes>
        <Route path="/chat" element={<div>CHAT_PAGE_MARK</div>} />
        <Route path="*" element={<div>HOME_MARK</div>} />
      </Routes>
    );
  };
  render(
    <MemoryRouter initialEntries={["/"]}>
      <Harness />
    </MemoryRouter>
  );
  return () => {
    if (!enter) {
      throw new Error("hook not initialised");
    }
    return enter();
  };
}

describe("useEnterChat", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false });
    // 双引擎残影：/chat 经 EngineGate 落 AgentChatPage（agentChatStore）或 ChatPage（chatStore），
    // fresh 语义须两个 store 都重置（code-review Spec 轴补）
    useAgentChatStore.setState({ currentSessionId: "s-last", messages: [], isLoading: false });
    useChatStore.setState({ currentSessionId: "c-last", messages: [], isStreaming: false, isLoading: false });
    vi.mocked(guestLogin).mockReset();
  });

  afterEach(() => {
    cleanup();
    useAgentChatStore.setState({ currentSessionId: null, messages: [] });
    useChatStore.setState({ currentSessionId: null, messages: [] });
  });

  it("fresh=true resets both chat stores before navigating (opinion 3)", () => {
    const fire = setup({ fresh: true });
    useAuthStore.setState({ user: { id: 1, role: "user" } as never, isAuthenticated: true });
    act(() => fire());
    expect(useAgentChatStore.getState().currentSessionId).toBeNull();
    expect(useChatStore.getState().currentSessionId).toBeNull();
    expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
  });

  it("default (no fresh) keeps the recent-session semantics", () => {
    const fire = setup({});
    useAuthStore.setState({ user: { id: 1, role: "user" } as never, isAuthenticated: true });
    act(() => fire());
    expect(useAgentChatStore.getState().currentSessionId).toBe("s-last");
    expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
  });

  it("guest path: creates guest identity first, then resets and enters with fresh", async () => {
    vi.mocked(guestLogin).mockResolvedValue({} as never);
    const fire = setup({ fresh: true });
    fire();
    await vi.waitFor(() => expect(useAgentChatStore.getState().currentSessionId).toBeNull());
    expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
    expect(vi.mocked(guestLogin)).toHaveBeenCalledTimes(1);
  });
});
