import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { AgentSessionShareButton } from "@/components/agent/AgentSessionShareButton";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import { useEngineStore } from "@/stores/engineStore";

vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() })
}));
import { toast } from "sonner";

/**
 * #139 顶栏入口重构：稳定态开 AgentShareDialog（defaultScope=full）；
 * 加载门/不稳定态门只 toast 不开窗；游客可见（点击走弹窗登录引导，不调创建）。
 * 创建/预览/成功态语义在 AgentShareDialog.test 覆盖。
 */

function setup() {
  render(
    <FeedLangProvider>
      <AgentSessionShareButton />
    </FeedLangProvider>
  );
}

describe("AgentSessionShareButton（#139 入口门）", () => {
  beforeEach(() => {
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({
      currentSessionId: "conv-1",
      isLoading: false,
      isStreaming: false,
      messages: [],
      shareDialog: null
    });
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user" },
      isAuthenticated: true
    });
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    useEngineStore.setState({ engineType: null });
    useAgentChatStore.setState({ currentSessionId: null, shareDialog: null });
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it("稳定态：点击开 Scoped Share 弹窗（defaultScope=full）", async () => {
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    expect(useAgentChatStore.getState().shareDialog).toEqual({ defaultScope: "full" });
  });

  it("不稳定态门：流式中可见但只 toast，不开窗不创建", async () => {
    useAgentChatStore.setState({ isStreaming: true });
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/回答完成后即可分享|once the answer completes/));
    expect(useAgentChatStore.getState().shareDialog).toBeNull();
  });

  it("不稳定态门：停在写操作确认（AWAITING_CONFIRM）同门", async () => {
    useAgentChatStore.setState({
      messages: [
        { id: "m-1", role: "assistant", content: "", status: "done", messageStatus: "AWAITING_CONFIRM", createdAt: "2026-01-01T09:00:00" }
      ]
    });
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/回答完成后即可分享|once the answer completes/));
    expect(useAgentChatStore.getState().shareDialog).toBeNull();
  });

  it("消息加载门：isLoading 只提示稍候，不开窗", async () => {
    useAgentChatStore.setState({ isLoading: true });
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    expect(toast).toHaveBeenCalledWith(expect.stringMatching(/正在加载对话|still loading/));
    expect(useAgentChatStore.getState().shareDialog).toBeNull();
  });

  it("消息加载门：loading→ready 后恢复正常开窗", async () => {
    useAgentChatStore.setState({ isLoading: true });
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    expect(useAgentChatStore.getState().shareDialog).toBeNull();

    useAgentChatStore.setState({ isLoading: false });
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    expect(useAgentChatStore.getState().shareDialog).toEqual({ defaultScope: "full" });
  });

  it("游客：入口可见，点击仍开弹窗（弹窗内走登录引导，不调创建端点）", async () => {
    useAuthStore.setState({
      user: { userId: "g-1", username: "guest-abc", role: "guest" },
      isAuthenticated: true
    });
    const user = userEvent.setup();
    setup();
    await user.click(screen.getByRole("button", { name: /分享对话|Share conversation/ }));
    expect(useAgentChatStore.getState().shareDialog).toEqual({ defaultScope: "full" });
  });

  it("无当前会话（新对话未落会话）时不渲染", () => {
    useAgentChatStore.setState({ currentSessionId: null });
    setup();
    expect(screen.queryByRole("button", { name: /分享对话|Share conversation/ })).toBeNull();
  });

  it("非 agent 引擎不渲染", () => {
    useEngineStore.setState({ engineType: "workflow" });
    setup();
    expect(screen.queryByRole("button", { name: /分享对话|Share conversation/ })).toBeNull();
  });
});
