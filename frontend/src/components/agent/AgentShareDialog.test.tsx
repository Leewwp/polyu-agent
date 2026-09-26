import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

const createAgentShareMock = vi.hoisted(() => vi.fn());
vi.mock("@/services/agentShareService", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/services/agentShareService")>();
  return { ...actual, createAgentShare: createAgentShareMock };
});
const toastSuccess = vi.hoisted(() => vi.fn());
const toastError = vi.hoisted(() => vi.fn());
vi.mock("sonner", () => ({
  toast: { success: toastSuccess, error: toastError }
}));

import { AgentShareDialog } from "./AgentShareDialog";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import type { AgentMessage } from "@/types/agent";

/**
 * #139 Scoped Share 弹窗：票面测试清单——Header full 无需 anchor / turn+through
 * 必先单选且只列 Shareable Turn / 答案入口默认 turn / 成功态不自动关不自动复制 /
 * 复制分享链接 / expireTime=null 文案 / 游客登录引导不创建 / 预览与后端选段对拍
 * （含确认续跑）/ 双语 zh+en。
 */

function userMsg(id: string, content: string): AgentMessage {
  return { id, role: "user", content, createdAt: "2026-01-01T09:41:03" };
}

function assistantMsg(id: string, content: string, over: Partial<AgentMessage> = {}): AgentMessage {
  return {
    id,
    role: "assistant",
    content,
    status: "done",
    messageStatus: "NORMAL",
    createdAt: "2026-01-01T09:41:10",
    ...over
  };
}

/** 两轮完成 + 一轮流式：Shareable=turn1/turn2，turn3 streaming 不可锚 */
function seedConversation() {
  const messages: AgentMessage[] = [
    userMsg("u-1", "图书馆几点开门？"),
    assistantMsg("2103590757771956001", "学期内周一至周六 08:30 开门。"),
    userMsg("u-2", "学费什么时候截止？"),
    assistantMsg("2103590757771956002", "本学期缴费截止日为 10 月 5 日。"),
    userMsg("u-3", "再讲讲退费"),
    { id: "assistant-1770000000000", role: "assistant", content: "正在生成", status: "streaming", createdAt: "2026-01-01T09:42:00" }
  ];
  useAgentChatStore.setState({
    messages,
    currentSessionId: "conv-1",
    isLoading: false,
    isStreaming: false,
    shareDialog: null
  });
  return messages;
}

function stubClipboard(writeText: ReturnType<typeof vi.fn>) {
  Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
}

function installLangStorage(lang: "zh" | "en") {
  const mem = new Map<string, string>([["polyu.feed.lang", lang]]);
  Object.defineProperty(window, "localStorage", {
    value: {
      getItem: (key: string) => mem.get(key) ?? null,
      setItem: (key: string, value: string) => void mem.set(key, value),
      removeItem: (key: string) => void mem.delete(key)
    },
    configurable: true
  });
  return mem;
}

function setupDialog(
  shareDialog: { defaultScope: "full" | "turn" | "through"; anchorAssistantMessageId?: string } | null,
  lang: "zh" | "en" = "zh"
) {
  installLangStorage(lang);
  useAgentChatStore.setState({ shareDialog });
  return render(
    <MemoryRouter>
      <FeedLangProvider>
        <AgentShareDialog />
      </FeedLangProvider>
    </MemoryRouter>
  );
}

describe("AgentShareDialog 范围与锚点", () => {
  beforeEach(() => {
    seedConversation();
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user" },
      isAuthenticated: true
    });
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
    createAgentShareMock.mockReset();
    toastSuccess.mockClear();
    toastError.mockClear();
    useAgentChatStore.setState({ shareDialog: null });
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it("Header full：无需 anchor 直接可创建；请求不带 anchorAssistantMessageId", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-FULL", expireTime: "2026-12-25T00:00:00" });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "full", undefined);
    });
  });

  it("Header turn：必先单选锚点轮，未选时创建钮禁用（无悬空提交态）", async () => {
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("radio", { name: /当前问答|This turn/ }));
    // 单选列表在场；创建钮 disabled
    expect(screen.getByText(/选择一轮对话|Pick a turn/)).toBeTruthy();
    const createBtn = screen.getByRole("button", { name: /创建分享链接|Create share link/ }) as HTMLButtonElement;
    expect(createBtn.disabled).toBe(true);
    expect(createAgentShareMock).not.toHaveBeenCalled();
  });

  it("Header turn 单选列表只列 Shareable Turn（streaming 轮不出现），选中后可创建", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-TURN", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("radio", { name: /当前问答|This turn/ }));
    // 倒序最新在前：TURN 2（学费）与 TURN 1（图书馆）在场；TURN 3（streaming）不在
    const options = screen.getAllByRole("radio", { name: /TURN/ });
    expect(options).toHaveLength(2);
    const optionLabels = options.map((o) => o.closest("label")?.textContent ?? "");
    expect(optionLabels[0]).toContain("学费什么时候截止？");
    expect(optionLabels.some((t) => t.includes("再讲讲退费"))).toBe(false);

    await user.click(options[0]);
    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "turn", "2103590757771956002");
    });
  });

  it("答案入口：默认 turn 且自带 anchor（不出单选列表），直接可创建", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-A", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "turn", anchorAssistantMessageId: "2103590757771956001" });

    // 不出现「选择一轮对话」——anchor 已由入口携带
    expect(screen.queryByText(/选择一轮对话|Pick a turn/)).toBeNull();
    const turnRadio = screen.getByRole("radio", { name: /当前问答|This turn/ }) as HTMLInputElement;
    expect(turnRadio.checked).toBe(true);
    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "turn", "2103590757771956001");
    });
  });

  it("成功态：不自动关、不自动复制；展示 URL 与确切有效期", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-SUCCESS", expireTime: "2026-12-25T08:30:00" });
    const clipboardWrite = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    stubClipboard(clipboardWrite);
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(screen.getByText(/分享链接已创建|Share link created/)).toBeTruthy();
    });
    // 弹窗仍在（标题/取消入口还在 DOM）；未自动复制
    expect(screen.getByText(/TOK-SUCCESS|share\/c\/TOK-SUCCESS/)).toBeTruthy();
    expect(clipboardWrite).not.toHaveBeenCalled();
    // 确切有效期（服务端返回值格式化）
    expect(screen.getByText(/2026-12-25 08:30/)).toBeTruthy();
  });

  it("expireTime=null：显示「不会自动过期」", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-NULL", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });
    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(screen.getByText(/不会自动过期|never expires/)).toBeTruthy();
    });
  });

  it("复制分享链接：成功 toast 与答案 Copy 语义分离", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-COPY", expireTime: null });
    const clipboardWrite = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    stubClipboard(clipboardWrite);
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await user.click(await screen.findByRole("button", { name: /复制分享链接|Copy link/ }));
    await waitFor(() => {
      expect(clipboardWrite).toHaveBeenCalledWith(expect.stringContaining("/share/c/TOK-COPY"));
      expect(toastSuccess).toHaveBeenCalledWith(expect.stringMatching(/分享链接已复制|Share link copied/));
    });
  });

  it("创建前预览=轻量确认：turn 档只列锚点轮；through 含前序轮（与后端选段对拍，含确认续跑形态）", async () => {
    // 确认续跑形态：turn2 的 assistant 两段（AWAITING_CONFIRM 空正文段 + 续答段）同轮
    const confirmResume = [
      ...seedConversation().slice(0, 2),
      userMsg("u-2", "学费什么时候截止？"),
      assistantMsg("a-await", "", { messageStatus: "AWAITING_CONFIRM" }),
      assistantMsg("2103590757771956002", "本学期缴费截止日为 10 月 5 日。"),
      ...seedConversation().slice(4)
    ];
    useAgentChatStore.setState({ messages: confirmResume });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "turn", anchorAssistantMessageId: "2103590757771956002" });

    // turn 档预览=「将分享 1 轮对话」，只含学费轮
    expect(screen.getByText(/将分享 1 轮对话|1 turn\(s\) will be shared/)).toBeTruthy();
    expect(screen.getByText(/学费什么时候截止？/)).toBeTruthy();
    expect(screen.queryByText(/图书馆几点开门？/)).toBeNull();

    // 切 through：从第一轮到锚点轮（2 轮）——与后端 replyTo 物理窗口同构（续答归同轮）
    await user.click(screen.getByRole("radio", { name: /直至此轮|Through this turn/ }));
    expect(screen.getByText(/将分享 2 轮对话|2 turn\(s\) will be shared/)).toBeTruthy();
    expect(screen.getByText(/图书馆几点开门？/)).toBeTruthy();
    expect(screen.queryByText(/再讲讲退费/)).toBeNull();
  });

  it("消息 loading 门（弹窗内兜底）：不渲染表单更不创建", async () => {
    useAgentChatStore.setState({ isLoading: true });
    setupDialog({ defaultScope: "full" });

    expect(screen.getByText(/正在加载对话|still loading/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /创建分享链接|Create share link/ })).toBeNull();
    expect(createAgentShareMock).not.toHaveBeenCalled();
  });

  it("en：范围三档/创建钮/成功态文案随英文（双语例）", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-EN", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" }, "en");

    expect(screen.getByText("Full conversation")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Create share link" })).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "Create share link" }));
    await waitFor(() => {
      expect(screen.getByText("Share link created")).toBeTruthy();
      expect(screen.getByText(/never expires/)).toBeTruthy();
    });
  });
});

describe("AgentShareDialog 游客门", () => {
  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
    createAgentShareMock.mockReset();
    useAgentChatStore.setState({ shareDialog: null });
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it("游客：只出登录引导（治理说明+登录/取消），不调创建端点", async () => {
    seedConversation();
    useAuthStore.setState({
      user: { userId: "g-1", username: "guest-abc", role: "guest" },
      isAuthenticated: true
    });
    setupDialog({ defaultScope: "full" });

    expect(screen.getByText(/登录后分享对话|Sign in to share/)).toBeTruthy();
    expect(screen.getByRole("link", { name: /登录|Sign in/ })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /创建分享链接|Create share link/ })).toBeNull();
    expect(createAgentShareMock).not.toHaveBeenCalled();
  });

  it("未登录（isAuthenticated=false）同登录引导", () => {
    seedConversation();
    setupDialog({ defaultScope: "full" });
    expect(screen.getByText(/登录后分享对话|Sign in to share/)).toBeTruthy();
  });
});
