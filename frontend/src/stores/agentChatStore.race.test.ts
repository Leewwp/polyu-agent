import { beforeEach, describe, expect, it, vi } from "vitest";

const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }));
vi.mock("sonner", () => ({
  toast: { error: toastError, success: vi.fn() }
}));

vi.mock("@/services/agentService", () => ({
  listAgentSessions: vi.fn(),
  listAgentMessages: vi.fn(),
  deleteAgentSession: vi.fn(),
  batchDeleteAgentSessions: vi.fn(),
  renameAgentSession: vi.fn(),
  stopAgentTask: vi.fn()
}));
vi.mock("@/hooks/useAgentStream", () => ({
  createAgentStreamResponse: vi.fn(() => ({ start: vi.fn(), cancel: vi.fn() }))
}));

import { listAgentMessages } from "@/services/agentService";
import { createAgentStreamResponse } from "@/hooks/useAgentStream";
import { useAgentChatStore } from "@/stores/agentChatStore";

/**
 * M17 agent 链对称面（视图架构版）：切会话/新建不断流——旧会话在途流写自己的
 * 视图槽位，顶层（当前页面）只反映激活视图；迟到 meta 落旧视图，不把当前会话拉回。
 */

function resetStore() {
  useAgentChatStore.setState({
    sessions: [],
    currentViewKey: "draft:test",
    conversationStates: {},
    currentSessionId: null,
    messages: [],
    messagesSessionId: null,
    isLoading: false,
    sessionsLoaded: false,
    isStreaming: false,
    isCreatingNew: false,
    streamTaskId: null,
    streamAbort: null,
    streamingMessageId: null,
    streamOpenBlockId: null,
    cancelRequested: false,
    frames: [],
    quotaError: null,
    shareDialog: null,
    draft: null
  });
}

describe("agentChatStore M17 切会话×在途流竞态（视图架构）", () => {
  beforeEach(() => {
    toastError.mockClear();
    resetStore();
    vi.mocked(listAgentMessages).mockReset();
    vi.mocked(createAgentStreamResponse).mockReset();
    vi.mocked(createAgentStreamResponse).mockImplementation(
      () => ({ start: vi.fn(), cancel: vi.fn() }) as never
    );
  });

  it("切会话：旧会话在途流隔离进自己的视图槽位，顶层换绑且不断流", async () => {
    const cancel = vi.fn();
    useAgentChatStore.setState({
      currentViewKey: "s-old",
      currentSessionId: "s-old",
      messages: [],
      isStreaming: true,
      streamingMessageId: "assistant-x",
      streamAbort: cancel
    });
    vi.mocked(listAgentMessages).mockResolvedValue([]);
    await useAgentChatStore.getState().loadMessages("s-new");
    // 视图架构：切走不断流（并发运行位由服务端闸门治理），旧流的 abort 不被调用
    expect(cancel).not.toHaveBeenCalled();
    // 顶层=激活视图（s-new 空视图），不被旧会话流污染
    expect(useAgentChatStore.getState().currentSessionId).toBe("s-new");
    expect(useAgentChatStore.getState().isStreaming).toBe(false);
    expect(useAgentChatStore.getState().streamingMessageId).toBeNull();
    // 隔离性：旧会话在途流完整保留在自己的视图槽位里
    const oldView = useAgentChatStore.getState().conversationStates["s-old"];
    expect(oldView?.isStreaming).toBe(true);
    expect(oldView?.streamingMessageId).toBe("assistant-x");
    expect(oldView?.streamAbort).toBe(cancel);
  });

  it("#311 换会话自动关分享窗：挂旧会话的分享上下文不可跨会话存活（沿用门回归）", async () => {
    useAgentChatStore.setState({
      currentViewKey: "s-old",
      currentSessionId: "s-old",
      shareDialog: { defaultScope: "selection", anchorAssistantMessageId: "2103590757771956001" }
    });
    vi.mocked(listAgentMessages).mockResolvedValue([]);
    await useAgentChatStore.getState().loadMessages("s-new");
    expect(useAgentChatStore.getState().currentSessionId).toBe("s-new");
    expect(useAgentChatStore.getState().shareDialog).toBeNull();
  });

  it("在途流的迟到 meta 不把切换后的会话拉回旧会话", async () => {
    const holder: {
      handlers?: { onMeta?: (payload: { conversationId: string; taskId: string }) => void };
    } = {};
    const agentCancel = vi.fn();
    vi.mocked(createAgentStreamResponse).mockImplementationOnce(
      ((
        _options: unknown,
        handlers: { onMeta?: (payload: { conversationId: string; taskId: string }) => void }
      ) => {
        holder.handlers = handlers;
        return { start: () => new Promise<void>(() => {}), cancel: agentCancel };
      }) as never
    );
    const pending = useAgentChatStore.getState().sendMessage("问题");
    expect(useAgentChatStore.getState().isStreaming).toBe(true);
    expect(holder.handlers).toBeDefined();
    vi.mocked(listAgentMessages).mockResolvedValue([]);
    await useAgentChatStore.getState().loadMessages("s-new");
    // 旧流（draft 视图首问，originConversationId=null）的 meta 迟到：
    // moveView 只落 conversationStates 槽位，顶层（当前页面）仍是切换后的会话
    holder.handlers?.onMeta?.({ conversationId: "s-old-new", taskId: "t1" });
    expect(useAgentChatStore.getState().currentSessionId).toBe("s-new");
    // 迟到 meta 的归属落旧视图自己的槽位，不丢不串
    expect(useAgentChatStore.getState().conversationStates["s-old-new"]).toBeDefined();
    // 视图架构下旧流继续在后台跑，不再断流
    expect(agentCancel).not.toHaveBeenCalled();
    void pending;
  });

  it("startNewChat（新建入口）切到全新 draft 视图，旧流隔离不断流", () => {
    const cancel = vi.fn();
    useAgentChatStore.setState({
      currentViewKey: "s-old",
      currentSessionId: "s-old",
      messages: [
        { id: "m1", role: "user", content: "hi", status: "done", createdAt: "2026-09-19T00:00:00Z" } as never
      ],
      isStreaming: true,
      streamAbort: cancel
    });
    useAgentChatStore.getState().startNewChat();
    // 不断流：旧会话流留在后台视图继续
    expect(cancel).not.toHaveBeenCalled();
    // 顶层=全新 draft：干净的新会话态
    expect(useAgentChatStore.getState().currentSessionId).toBeNull();
    expect(useAgentChatStore.getState().messagesSessionId).toBeNull();
    expect(useAgentChatStore.getState().messages).toEqual([]);
    expect(useAgentChatStore.getState().isStreaming).toBe(false);
    expect(useAgentChatStore.getState().isCreatingNew).toBe(true);
    // 隔离性：旧流仍在旧视图槽位
    const oldView = useAgentChatStore.getState().conversationStates["s-old"];
    expect(oldView?.isStreaming).toBe(true);
    expect(oldView?.streamAbort).toBe(cancel);
  });
});

describe("agentChatStore L34：首问问题全文走 POST body", () => {
  beforeEach(() => {
    vi.mocked(createAgentStreamResponse).mockClear();
    useAgentChatStore.setState({
      sessions: [],
      currentViewKey: "draft:l34",
      conversationStates: {},
      currentSessionId: null,
      messages: [],
      isStreaming: false,
      isCreatingNew: false,
      streamingMessageId: null,
      streamOpenBlockId: null,
      streamAbort: null,
      cancelRequested: false
    });
  });

  it("sendMessage 以无查询串 URL + body 携带 question/conversationId", async () => {
    useAgentChatStore.setState({ currentSessionId: "ac-95" });
    await useAgentChatStore.getState().sendMessage("图书馆开放时间？");
    expect(createAgentStreamResponse).toHaveBeenCalledTimes(1);
    const [options] = vi.mocked(createAgentStreamResponse).mock.calls[0];
    expect((options as { url: string }).url).not.toContain("?");
    expect((options as { url: string }).url).toContain("/agent/v1/chat");
    expect((options as { body: unknown }).body).toEqual({
      question: "图书馆开放时间？",
      conversationId: "ac-95"
    });
  });
});
