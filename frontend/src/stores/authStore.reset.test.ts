import { beforeEach, describe, expect, it, vi } from "vitest";

const { toastSuccess } = vi.hoisted(() => ({ toastSuccess: vi.fn() }));
vi.mock("sonner", () => ({
  toast: { success: toastSuccess, error: vi.fn() }
}));

vi.mock("@/services/authService", () => ({
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn(),
  getCurrentUser: vi.fn()
}));

import { getCurrentUser, guestLogin, login, logout } from "@/services/authService";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";

/**
 * M15 跨账号清场：login/logout/guestLogin 必须与 chatStore 对称地重置
 * agentChatStore——zustand 单例跨页面存活，A 的 currentSessionId/messages/sessions
 * 残留会让同 tab 换号的 B 直接看到 A 的 agent 对话（AgentChatPage 的
 * currentSessionId 早退使拉取 B 的会话被跳过）。
 */

function seedAccountA() {
  useAgentChatStore.setState({
    sessions: [{ id: "a-s1", title: "A 的会话", lastTime: "2026-09-19T00:00:00Z", turns: 1 }],
    currentSessionId: "a-s1",
    messages: [
      { id: "m1", role: "user", content: "A 的提问", status: "done", createdAt: "2026-09-19T00:00:00Z" }
    ] as never,
    isCreatingNew: false,
    isStreaming: false,
    isLoading: false,
    sessionsLoaded: true,
    frames: [],
    quotaError: null,
    draft: null
  });
  useChatStore.setState({
    sessions: [{ id: "c-a1", title: "A 的 workflow 会话", lastTime: "2026-09-19T00:00:00Z" }],
    currentSessionId: "c-a1",
    messages: [
      { id: "cm1", role: "user", content: "A 的提问", status: "done", createdAt: "2026-09-19T00:00:00Z" } as never
    ],
    messagesError: "残留错误",
    isStreaming: false,
    isCreatingNew: false,
    openedSourceMessageId: "cm1"
  });
}

function expectBothStoresCleared(isCreatingNew: boolean) {
  const agent = useAgentChatStore.getState();
  expect(agent.currentSessionId).toBeNull();
  expect(agent.messages).toEqual([]);
  expect(agent.sessions).toEqual([]);
  expect(agent.isCreatingNew).toBe(isCreatingNew);
  expect(agent.frames).toEqual([]);
  expect(agent.quotaError).toBeNull();
  const chat = useChatStore.getState();
  expect(chat.currentSessionId).toBeNull();
  expect(chat.messages).toEqual([]);
  expect(chat.sessions).toEqual([]);
  expect(chat.messagesError).toBeNull();
  expect(chat.openedSourceMessageId).toBeNull();
  expect(chat.isCreatingNew).toBe(isCreatingNew);
}

describe("authStore M15 换号双引擎清场", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isGuest: false, isLoading: false });
    vi.mocked(login).mockReset();
    vi.mocked(logout).mockReset();
    vi.mocked(guestLogin).mockReset();
    vi.mocked(getCurrentUser).mockReset();
    vi.mocked(getCurrentUser).mockResolvedValue({ id: 2, role: "user" } as never);
  });

  it("login：A 的 agent 与 workflow 会话数据全部清空（isCreatingNew=true）", async () => {
    seedAccountA();
    vi.mocked(login).mockResolvedValue({} as never);
    await useAuthStore.getState().login("b", "pw");
    expect(useAuthStore.getState().isAuthenticated).toBe(true);
    expectBothStoresCleared(true);
  });

  it("logout：同样双引擎清空（isCreatingNew=false）", async () => {
    seedAccountA();
    vi.mocked(logout).mockResolvedValue({} as never);
    await useAuthStore.getState().logout();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expectBothStoresCleared(false);
  });

  it("guestLogin：铸号成功后同样清空（真实账号→游客不残留）", async () => {
    seedAccountA();
    // fetchCurrentUser 异步回填用户：游客场景须回 guest 角色，否则 isGuest 被覆写
    vi.mocked(getCurrentUser).mockResolvedValue({ id: 3, role: "guest" } as never);
    vi.mocked(guestLogin).mockResolvedValue({} as never);
    await useAuthStore.getState().guestLogin();
    expect(useAuthStore.getState().isGuest).toBe(true);
    expectBothStoresCleared(true);
  });
});
