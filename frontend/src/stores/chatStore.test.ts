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
import { stopTask } from "@/services/chatService";
import { createStreamResponse } from "@/hooks/useStreamResponse";
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
    sessionsLoading: false,
    sessionsError: null,
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

describe("chatStore M17 会话切换×在途流竞态", () => {
  beforeEach(() => {
    toastError.mockClear();
    resetStore();
    vi.mocked(listMessages).mockReset();
    vi.mocked(listSessions).mockReset();
    vi.mocked(createStreamResponse).mockReset();
    vi.mocked(createStreamResponse).mockImplementation(
      () => ({ start: vi.fn(), cancel: vi.fn() }) as never
    );
    vi.mocked(stopTask).mockReset();
  });

  it("切换会话：立即断流（排队期 abort）+全量清场+isLoading 收敛", async () => {
    const cancel = vi.fn();
    useChatStore.setState({
      sessions: [],
      currentSessionId: "s-old",
      messages: [],
      isStreaming: true,
      streamingMessageId: "assistant-x",
      streamAbort: cancel
    });
    vi.mocked(listMessages).mockResolvedValue([]);
    await useChatStore.getState().selectSession("s-new");
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(useChatStore.getState().currentSessionId).toBe("s-new");
    expect(useChatStore.getState().isStreaming).toBe(false);
    expect(useChatStore.getState().streamingMessageId).toBeNull();
    expect(useChatStore.getState().isLoading).toBe(false);
  });

  it("在途流的迟到 meta 不把切换后的会话拉回旧会话", async () => {
    const holder: {
      handlers?: { onMeta?: (payload: { conversationId: string; taskId: string }) => void };
    } = {};
    vi.mocked(createStreamResponse).mockImplementationOnce(
      ((
        _options: unknown,
        handlers: { onMeta?: (payload: { conversationId: string; taskId: string }) => void }
      ) => {
        holder.handlers = handlers;
        // start 永不落定：模拟流仍在途
        return { start: () => new Promise<void>(() => {}), cancel: vi.fn() };
      }) as never
    );
    const pending = useChatStore.getState().sendMessage("问题");
    expect(useChatStore.getState().isStreaming).toBe(true);
    expect(holder.handlers).toBeDefined();
    vi.mocked(listMessages).mockResolvedValue([]);
    await useChatStore.getState().selectSession("s-new");
    // 旧流（新会话首问，originConversationId=null）的 meta 迟到：
    // currentSessionId 已是 s-new，非空且不等于本流所属会话——不得采纳
    holder.handlers?.onMeta?.({ conversationId: "s-old-new", taskId: "t1" });
    expect(useChatStore.getState().currentSessionId).toBe("s-new");
    void pending;
  });

  it("新建会话（createSession）同样立即断流", async () => {
    const cancel = vi.fn();
    useChatStore.setState({
      currentSessionId: "s-old",
      messages: [{ id: "m1", role: "user", content: "hi" } as never],
      isStreaming: true,
      streamAbort: cancel
    });
    await useChatStore.getState().createSession();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(useChatStore.getState().currentSessionId).toBeNull();
    expect(useChatStore.getState().isStreaming).toBe(false);
  });
});

describe("chatStore L32/L33：列表态与消息态拆分", () => {
  beforeEach(() => {
    toastError.mockClear();
    resetStore();
    vi.mocked(listMessages).mockReset();
    vi.mocked(listSessions).mockReset();
  });

  it("L32：fetchSessions 失败置 sessionsError（sessionsLoaded 仍 true 防侧栏重拉循环），成功清空", async () => {
    vi.mocked(listSessions).mockRejectedValueOnce(new Error("boom"));
    await useChatStore.getState().fetchSessions();
    const failed = useChatStore.getState();
    expect(failed.sessionsError).toBe("boom");
    expect(failed.sessionsLoaded).toBe(true);
    expect(failed.sessions).toEqual([]);

    vi.mocked(listSessions).mockResolvedValueOnce([
      { conversationId: "s1", title: "会话一", lastTime: "2026-09-20T10:00:00Z" }
    ]);
    await useChatStore.getState().fetchSessions();
    const ok = useChatStore.getState();
    expect(ok.sessionsError).toBeNull();
    expect(ok.sessions.map((session) => session.id)).toEqual(["s1"]);
    expect(ok.sessionsLoaded).toBe(true);
  });

  it("L33：fetchSessions 全程不碰 isLoading（消息加载专用），走独立 sessionsLoading", async () => {
    let release: (value: []) => void = () => {};
    vi.mocked(listSessions).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve as (value: []) => void;
      })
    );
    const pending = useChatStore.getState().fetchSessions();
    expect(useChatStore.getState().sessionsLoading).toBe(true);
    // 拆分核心：列表加载中不得染指消息加载态（否则切会话/首屏瞬间误闪 Welcome）
    expect(useChatStore.getState().isLoading).toBe(false);
    release([]);
    await pending;
    expect(useChatStore.getState().sessionsLoading).toBe(false);
    expect(useChatStore.getState().isLoading).toBe(false);
  });

  it("L33：selectSession 仍以 isLoading 表达消息加载（拆分后语义不变）", async () => {
    let release: (value: []) => void = () => {};
    vi.mocked(listMessages).mockReturnValueOnce(
      new Promise((resolve) => {
        release = resolve as (value: []) => void;
      })
    );
    const pending = useChatStore.getState().selectSession("s1");
    expect(useChatStore.getState().isLoading).toBe(true);
    release([]);
    await pending;
    expect(useChatStore.getState().isLoading).toBe(false);
  });
});

describe("chatStore L34：问题全文走 POST body", () => {
  beforeEach(() => {
    toastError.mockClear();
    resetStore();
    vi.mocked(listMessages).mockReset();
    vi.mocked(listSessions).mockReset();
  });

  it("sendMessage 以无查询串 URL + body 携带 question/conversationId/deepThinking", async () => {
    useChatStore.setState({ currentSessionId: "c-95" });
    await useChatStore.getState().sendMessage("图书馆开放时间？");
    expect(createStreamResponse).toHaveBeenCalledTimes(1);
    const [options] = vi.mocked(createStreamResponse).mock.calls[0];
    expect((options as { url: string }).url).not.toContain("?");
    expect((options as { url: string }).url).toContain("/rag/v3/chat");
    expect((options as { body: unknown }).body).toEqual({
      question: "图书馆开放时间？",
      conversationId: "c-95",
      deepThinking: undefined
    });
  });

  it("深思考开启时 body 携带 deepThinking=true", async () => {
    useChatStore.setState({ currentSessionId: null, deepThinkingEnabled: true });
    await useChatStore.getState().sendMessage("深思考问题");
    const [options] = vi.mocked(createStreamResponse).mock.calls[0];
    expect((options as { body: unknown }).body).toEqual({
      question: "深思考问题",
      conversationId: undefined,
      deepThinking: true
    });
  });
});
