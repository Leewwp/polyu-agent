import { beforeEach, describe, expect, it, vi } from "vitest";
import { AxiosError } from "axios";

const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }));
vi.mock("sonner", () => ({
  toast: { error: toastError, success: vi.fn() }
}));

vi.mock("@/services/sessionService", () => ({
  listSessions: vi.fn(),
  listMessages: vi.fn(),
  deleteSession: vi.fn(),
  renameSession: vi.fn()
}));
vi.mock("@/services/chatService", () => ({
  stopTask: vi.fn(),
  submitFeedback: vi.fn(),
  cancelFeedback: vi.fn(),
  generateRecommendedQuestions: vi.fn()
}));
vi.mock("@/hooks/useStreamResponse", () => ({
  createStreamResponse: vi.fn(() => ({ start: vi.fn(), cancel: vi.fn() }))
}));

import { listMessages, listSessions } from "@/services/sessionService";
import { useChatStore } from "@/stores/chatStore";
import { markToastShown } from "@/utils/requestError";

/**
 * chatStore 调用层硬化——网络层错误（拦截器已弹中文文案并打标）不再
 * 重复弹 toast；selectSession 失败置 messagesError 供行内错误态 + 重试入口（替代空白回落）。
 */

function networkErrorAlreadyToasted(): AxiosError {
  const err = new AxiosError("Network Error", "ERR_NETWORK");
  markToastShown(err);
  return err;
}

function resetStore() {
  useChatStore.setState({
    sessions: [],
    currentSessionId: null,
    messages: [],
    messagesError: null,
    isLoading: false,
    sessionsLoaded: false,
    isStreaming: false,
    isCreatingNew: false
  });
}

describe("chatStore selectSession/fetchSessions", () => {
  beforeEach(() => {
    toastError.mockClear();
    resetStore();
    vi.mocked(listMessages).mockReset();
    vi.mocked(listSessions).mockReset();
  });

  it("网络层失败：不再重复弹 toast，messagesError 置中文文案", async () => {
    vi.mocked(listMessages).mockRejectedValue(networkErrorAlreadyToasted());
    await useChatStore.getState().selectSession("s1");
    expect(toastError).not.toHaveBeenCalled();
    expect(useChatStore.getState().messagesError).toBe("网络异常，请稍后重试");
    expect(useChatStore.getState().messages).toEqual([]);
  });

  it("业务失败：照常弹一条后端文案，messagesError 置同一文案", async () => {
    vi.mocked(listMessages).mockRejectedValue(new Error("会话不存在"));
    await useChatStore.getState().selectSession("s1");
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(toastError).toHaveBeenCalledWith("会话不存在");
    expect(useChatStore.getState().messagesError).toBe("会话不存在");
  });

  it("失败后重试成功：messagesError 清空、消息填充", async () => {
    vi.mocked(listMessages).mockRejectedValueOnce(networkErrorAlreadyToasted());
    await useChatStore.getState().selectSession("s1");
    expect(useChatStore.getState().messagesError).toBe("网络异常，请稍后重试");

    vi.mocked(listMessages).mockResolvedValue([
      {
        id: 1,
        conversationId: "s1",
        role: "user",
        content: "hi",
        vote: null
      }
    ]);
    await useChatStore.getState().selectSession("s1");
    expect(useChatStore.getState().messagesError).toBeNull();
    expect(useChatStore.getState().messages).toHaveLength(1);
  });

  it("加载成功时不留历史错误态", async () => {
    useChatStore.setState({ messagesError: "网络异常，请稍后重试" });
    vi.mocked(listMessages).mockResolvedValue([]);
    await useChatStore.getState().selectSession("s1");
    expect(useChatStore.getState().messagesError).toBeNull();
  });

  it("fetchSessions 网络层失败：不重复弹（拦截器已弹）", async () => {
    vi.mocked(listSessions).mockRejectedValue(networkErrorAlreadyToasted());
    await useChatStore.getState().fetchSessions();
    expect(toastError).not.toHaveBeenCalled();
    expect(useChatStore.getState().sessionsLoaded).toBe(true);
  });

  it("fetchSessions 业务失败：弹一条后端文案", async () => {
    vi.mocked(listSessions).mockRejectedValue(new Error("加载受限"));
    await useChatStore.getState().fetchSessions();
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(toastError).toHaveBeenCalledWith("加载受限");
  });
});
