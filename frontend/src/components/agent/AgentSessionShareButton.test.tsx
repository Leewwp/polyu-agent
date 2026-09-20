import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AgentSessionShareButton } from "@/components/agent/AgentSessionShareButton";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import { useEngineStore } from "@/stores/engineStore";

const createAgentShareMock = vi.hoisted(() => vi.fn());
const toastSuccess = vi.hoisted(() => vi.fn());
const toastError = vi.hoisted(() => vi.fn());

vi.mock("@/services/agentShareService", () => ({
  createAgentShare: createAgentShareMock
}));
vi.mock("sonner", () => ({
  toast: { success: toastSuccess, error: toastError }
}));

/**
 * issue #82 会话分享钮：隐私确认弹窗→创建→复制 /share/c/ 链接；
 * flag 未开启（404）提示「分享功能未开启」；无当前会话不渲染；
 * issue #91 增补：游客身份（role=guest）硬阻断不渲染（后端拒绝为双保险）。
 */
function setup() {
  render(
    <FeedLangProvider>
      <AgentSessionShareButton />
    </FeedLangProvider>
  );
}

describe("AgentSessionShareButton", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    createAgentShareMock.mockReset();
    toastSuccess.mockClear();
    toastError.mockClear();
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({ currentSessionId: null });
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it("确认弹窗出现隐私提醒，创建成功后复制 /share/c/ 链接", async () => {
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({ currentSessionId: "conv-1" });
    createAgentShareMock.mockResolvedValue({ token: "TOKEN123", expireTime: null });

    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));

    // 双语隐私确认文案在场
    expect(screen.getByText(/任何获得链接的人都可以查看这次对话/)).toBeTruthy();

    await user.click(screen.getByRole("button", { name: /创建并复制链接|Create & copy link/ }));

    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1");
    });
    const copied = await navigator.clipboard.readText();
    expect(copied).toContain("/share/c/TOKEN123");
  });

  it("flag 关闭（404）：提示功能未开启，不复制链接", async () => {
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({ currentSessionId: "conv-1" });
    createAgentShareMock.mockRejectedValue(new Error("Request failed with status code 404"));

    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    await user.click(screen.getByRole("button", { name: /创建并复制链接|Create & copy link/ }));

    await waitFor(() => {
      expect(toastError).toHaveBeenCalledWith(expect.stringMatching(/分享功能未开启|not enabled/));
    });
  });

  it("无当前会话（新对话未落会话）时不渲染", () => {
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({ currentSessionId: null });
    setup();
    expect(screen.queryByRole("button", { name: /分享对话|Share conversation/ })).toBeNull();
  });

  it("游客身份（role=guest）硬阻断不渲染（issue #91 增补，后端拒绝为双保险）", () => {
    useAuthStore.setState({
      user: { userId: "g-1", username: "guest-abc", role: "guest" },
      isAuthenticated: true
    });
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({ currentSessionId: "conv-guest" });
    setup();
    expect(screen.queryByRole("button", { name: /分享对话|Share conversation/ })).toBeNull();
    expect(createAgentShareMock).not.toHaveBeenCalled();
  });
});
