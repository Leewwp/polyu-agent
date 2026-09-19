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
 * M17 agent 链对称面：切会话（loadMessages）须立即断流+全量清场，
 * 迟到的 onMeta 不得把 currentSessionId 拉回旧会话。
 */

function resetStore() {
  useAgentChatStore.setState({
    sessions: [],
    currentSessionId: null,
    messages: [],
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
    draft: null
  });
}

describe("agentChatStore M17 切会话×在途流竞态", () => {
  beforeEach(() => {
    toastError.mockClear();
    resetStore();
    vi.mocked(listAgentMessages).mockReset();
    vi.mocked(createAgentStreamResponse).mockReset();
    vi.mocked(createAgentStreamResponse).mockImplementation(
      () => ({ start: vi.fn(), cancel: vi.fn() }) as never
    );
  });

  it("切会话：立即断流（排队期 abort）+全量清场", async () => {
    const cancel = vi.fn();
    useAgentChatStore.setState({
      currentSessionId: "s-old",
      messages: [],
      isStreaming: true,
      streamingMessageId: "assistant-x",
      streamAbort: cancel
    });
    vi.mocked(listAgentMessages).mockResolvedValue([]);
    await useAgentChatStore.getState().loadMessages("s-new");
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(useAgentChatStore.getState().currentSessionId).toBe("s-new");
    expect(useAgentChatStore.getState().isStreaming).toBe(false);
    expect(useAgentChatStore.getState().streamingMessageId).toBeNull();
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
    // 旧流（新会话首问，originConversationId=null）的 meta 迟到：不得回写
    holder.handlers?.onMeta?.({ conversationId: "s-old-new", taskId: "t1" });
    expect(useAgentChatStore.getState().currentSessionId).toBe("s-new");
    expect(agentCancel).toHaveBeenCalled();
    void pending;
  });

  it("startNewChat（新建/换号清场入口）立即断流", () => {
    const cancel = vi.fn();
    useAgentChatStore.setState({
      currentSessionId: "s-old",
      messages: [
        { id: "m1", role: "user", content: "hi", status: "done", createdAt: "2026-09-19T00:00:00Z" } as never
      ],
      isStreaming: true,
      streamAbort: cancel
    });
    useAgentChatStore.getState().startNewChat();
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(useAgentChatStore.getState().currentSessionId).toBeNull();
    expect(useAgentChatStore.getState().isStreaming).toBe(false);
    expect(useAgentChatStore.getState().isCreatingNew).toBe(true);
  });
});
