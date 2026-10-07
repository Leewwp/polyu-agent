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
 * #311 分享弹窗两档（full/selection）：票面测试清单——Header full 无需勾选 /
 * 仅两档（旧「当前问答/直至此轮」下线）/ Header 切「选择问答」空勾选起步禁用创建
 * 直至 ≥1 / 勾选列表只列 Shareable Turn（确认卡轮与流式轮不出现）/ 答案入口预勾
 * 所点轮可取消可加选 / 多选不连续预览=时间正序并集（与后端 SELECTION 选段对拍，
 * 含确认续跑形态）/ 请求 scope=selection+anchorAssistantMessageIds 列表（单选同样
 * 发列表）/ 成功态不自动关不自动复制 / expireTime=null 文案 / 游客登录引导不创建 /
 * 悬空锚点防线（预勾锚点过期禁用创建、混合集合不放行、换合法预勾恢复）/ 双语 zh+en。
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

/** 两轮完成 + 确认卡轮 + 流式轮：Shareable=turn1/turn2；turn3 确认卡与 turn4 流式不可锚 */
function seedConversation() {
  const messages: AgentMessage[] = [
    userMsg("u-1", "图书馆几点开门？"),
    assistantMsg("2103590757771956001", "学期内周一至周六 08:30 开门。"),
    userMsg("u-2", "学费什么时候截止？"),
    assistantMsg("2103590757771956002", "本学期缴费截止日为 10 月 5 日。"),
    userMsg("u-3", "帮我登记这条日程"),
    assistantMsg("a-await", "", { messageStatus: "AWAITING_CONFIRM" }),
    userMsg("u-4", "再讲讲退费"),
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

/** 三轮全完成种子：多选不连续对拍（预览与后端 SELECTION 物理顺序归一同构） */
function seedThreeShareableTurns() {
  useAgentChatStore.setState({
    messages: [
      userMsg("u-1", "图书馆几点开门？"),
      assistantMsg("2103590757771956001", "学期内周一至周六 08:30 开门。"),
      userMsg("u-2", "学费什么时候截止？"),
      assistantMsg("2103590757771956002", "本学期缴费截止日为 10 月 5 日。"),
      userMsg("u-3", "退费怎么办理？"),
      assistantMsg("2103590757771956003", "退费在注册处窗口办理。")
    ],
    shareDialog: null
  });
}

/** 预览区短预览行文本（按渲染顺序）：与勾选列表区隔的并集投影 */
function previewItemTexts(): string[] {
  return Array.from(document.querySelectorAll(".agent-share-preview-item")).map((el) => el.textContent ?? "");
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
  shareDialog: { defaultScope: "full" | "selection"; anchorAssistantMessageId?: string } | null,
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

  it("Header full：无需勾选直接可创建；请求不带锚点列表", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-FULL", expireTime: "2026-12-25T00:00:00" });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "full", undefined);
    });
  });

  it("弹窗仅两档：「完整对话/选择问答」在场，旧「当前问答/直至此轮」不再出现", () => {
    setupDialog({ defaultScope: "full" });

    const scopeRadios = screen.getAllByRole("radio");
    expect(scopeRadios).toHaveLength(2);
    expect(screen.getByText(/完整对话/)).toBeTruthy();
    expect(screen.getByText(/选择问答/)).toBeTruthy();
    expect(screen.queryByText(/当前问答|This turn/)).toBeNull();
    expect(screen.queryByText(/直至此轮|Through this turn/)).toBeNull();
  });

  it("Header 切「选择问答」：空勾选起步、创建禁用直至勾选 ≥1（无悬空提交态）", async () => {
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("radio", { name: /选择问答|Select turns/ }));
    // 勾选列表在场；创建钮 disabled
    expect(screen.getByText(/选择要分享的问答|Pick turns to share/)).toBeTruthy();
    const createBtn = screen.getByRole("button", { name: /创建分享链接|Create share link/ }) as HTMLButtonElement;
    expect(createBtn.disabled).toBe(true);
    expect(createAgentShareMock).not.toHaveBeenCalled();
  });

  it("勾选列表只列 Shareable Turn：确认卡轮与流式轮不可见不可选", async () => {
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("radio", { name: /选择问答|Select turns/ }));
    // 倒序最新在前：TURN 2（学费）与 TURN 1（图书馆）在场；TURN 3 确认卡与 TURN 4 流式不在
    const options = screen.getAllByRole("checkbox", { name: /TURN/ });
    expect(options).toHaveLength(2);
    const optionLabels = options.map((o) => o.closest("label")?.textContent ?? "");
    expect(optionLabels[0]).toContain("学费什么时候截止？");
    expect(optionLabels.some((t) => t.includes("再讲讲退费"))).toBe(false);
    expect(optionLabels.some((t) => t.includes("帮我登记这条日程"))).toBe(false);
  });

  it("勾选一轮创建：scope=selection + anchorAssistantMessageIds 列表（单选同样发列表，无旧单值字段）", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-SEL", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("radio", { name: /选择问答|Select turns/ }));
    const options = screen.getAllByRole("checkbox", { name: /TURN/ });
    await user.click(options[0]);
    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "selection", ["2103590757771956002"]);
    });
  });

  it("答案入口：默认「选择问答」预勾所点轮直接可创建；取消后禁用、改选换轮", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-A", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "selection", anchorAssistantMessageId: "2103590757771956001" });

    const selectionRadio = screen.getByRole("radio", { name: /选择问答|Select turns/ }) as HTMLInputElement;
    expect(selectionRadio.checked).toBe(true);
    // 倒序：TURN 2（学费）在前、TURN 1（图书馆）在后；预勾 TURN 1
    const boxes = screen.getAllByRole("checkbox", { name: /TURN/ }) as HTMLInputElement[];
    expect(boxes).toHaveLength(2);
    expect(boxes[1].checked).toBe(true);
    expect(boxes[0].checked).toBe(false);
    const createBtn = screen.getByRole("button", { name: /创建分享链接|Create share link/ }) as HTMLButtonElement;
    expect(createBtn.disabled).toBe(false);

    // 取消预勾 → 禁用；改勾 TURN 2 → 可创建且锚点换轮
    await user.click(boxes[1]);
    expect(createBtn.disabled).toBe(true);
    await user.click(boxes[0]);
    expect(createBtn.disabled).toBe(false);
    await user.click(createBtn);
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "selection", ["2103590757771956002"]);
    });
  });

  it("悬空锚点防线：入口预勾锚点不在当前可分享轮集合（会话已变化/过期）→ 创建禁用；换合法预勾重新入口恢复可用", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-STALE", expireTime: null });
    const user = userEvent.setup();
    // 预勾 2103590757771999999 不在 seedConversation（锚 001/002）中：模拟 footer 入口与当前消息脱钩
    const { unmount } = setupDialog({
      defaultScope: "selection",
      anchorAssistantMessageId: "2103590757771999999"
    });

    // 悬空种子只存在于勾选集合：列表内无对应 checkbox（预勾不可见），创建按钮禁用
    const boxes = screen.getAllByRole("checkbox", { name: /TURN/ }) as HTMLInputElement[];
    expect(boxes).toHaveLength(2);
    expect(boxes.every((box) => !box.checked)).toBe(true);
    const createBtn = screen.getByRole("button", { name: /创建分享链接|Create share link/ }) as HTMLButtonElement;
    expect(createBtn.disabled).toBe(true);

    // 防线口径=集合内任一锚点不可解析即拒绝：加选合法轮成混合集合仍禁用，不产出部分选段
    await user.click(boxes[0]);
    expect(createBtn.disabled).toBe(true);
    await user.click(createBtn);
    expect(createAgentShareMock).not.toHaveBeenCalled();

    // 恢复路径=换合法预勾重新入口（ShareForm 以入口键重挂、勾选集合重建为纯合法集合）
    unmount();
    setupDialog({ defaultScope: "selection", anchorAssistantMessageId: "2103590757771956002" });
    const createBtnAgain = screen.getByRole("button", { name: /创建分享链接|Create share link/ }) as HTMLButtonElement;
    expect(createBtnAgain.disabled).toBe(false);
    await user.click(createBtnAgain);
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "selection", ["2103590757771956002"]);
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

  it("多选不连续：预览=勾选轮按会话时间正序的并集，计数与短预览实时联动；payload 同正序（与后端 SELECTION 选段对拍）", async () => {
    seedThreeShareableTurns();
    createAgentShareMock.mockResolvedValue({ token: "TOK-MULTI", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" });

    await user.click(screen.getByRole("radio", { name: /选择问答|Select turns/ }));
    const boxes = screen.getAllByRole("checkbox", { name: /TURN/ });
    expect(boxes).toHaveLength(3); // 倒序：TURN 3 / TURN 2 / TURN 1

    // 勾选顺序仅是 UI 态：先勾 TURN 3 再勾 TURN 1，跳过 TURN 2（不连续）
    await user.click(boxes[0]);
    await user.click(boxes[2]);

    // 并集按会话时间正序：TURN 1 在 TURN 3 之前；TURN 2 不出现
    expect(screen.getByText(/将分享 2 轮对话|2 turn\(s\) will be shared/)).toBeTruthy();
    const previews = previewItemTexts();
    expect(previews).toHaveLength(2);
    expect(previews[0]).toContain("图书馆几点开门？");
    expect(previews[1]).toContain("退费怎么办理？");
    expect(previews.some((t) => t.includes("学费什么时候截止？"))).toBe(false);

    await user.click(screen.getByRole("button", { name: /创建分享链接|Create share link/ }));
    await waitFor(() => {
      expect(createAgentShareMock).toHaveBeenCalledWith("conv-1", "selection", [
        "2103590757771956001",
        "2103590757771956003"
      ]);
    });
  });

  it("确认续跑轮：整轮为一个勾选单位，预览并集含续答轮全部成员（与后端 replyTo 物理窗口对拍）", async () => {
    // 确认续跑形态：turn2 的 assistant 两段（AWAITING_CONFIRM 空正文段 + 续答段）同轮
    useAgentChatStore.setState({
      messages: [
        userMsg("u-1", "图书馆几点开门？"),
        assistantMsg("2103590757771956001", "学期内周一至周六 08:30 开门。"),
        userMsg("u-2", "学费什么时候截止？"),
        assistantMsg("a-await", "", { messageStatus: "AWAITING_CONFIRM" }),
        assistantMsg("2103590757771956002", "本学期缴费截止日为 10 月 5 日。"),
        userMsg("u-3", "退费怎么办理？"),
        assistantMsg("2103590757771956003", "退费在注册处窗口办理。")
      ],
      shareDialog: null
    });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "selection", anchorAssistantMessageId: "2103590757771956002" });

    // 预勾=确认续跑轮：预览「将分享 1 轮对话」，问题一次、续答正文在短预览内
    expect(screen.getByText(/将分享 1 轮对话|1 turn\(s\) will be shared/)).toBeTruthy();
    let previews = previewItemTexts();
    expect(previews).toHaveLength(1);
    expect(previews[0]).toContain("学费什么时候截止？");
    expect(previews[0]).toContain("本学期缴费截止日为 10 月 5 日。");
    expect(previews[0]).not.toContain("图书馆几点开门？");

    // 加选 TURN 3：2 轮并集按时间正序（turn2 → turn3）
    const boxes = screen.getAllByRole("checkbox", { name: /TURN/ });
    await user.click(boxes[0]);
    expect(screen.getByText(/将分享 2 轮对话|2 turn\(s\) will be shared/)).toBeTruthy();
    previews = previewItemTexts();
    expect(previews[1]).toContain("退费怎么办理？");
  });

  it("消息 loading 门（弹窗内兜底）：不渲染表单更不创建", async () => {
    useAgentChatStore.setState({ isLoading: true });
    setupDialog({ defaultScope: "full" });

    expect(screen.getByText(/正在加载对话|still loading/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /创建分享链接|Create share link/ })).toBeNull();
    expect(createAgentShareMock).not.toHaveBeenCalled();
  });

  it("en：两档/勾选列表/创建钮/成功态文案随英文（双语例）", async () => {
    createAgentShareMock.mockResolvedValue({ token: "TOK-EN", expireTime: null });
    const user = userEvent.setup();
    setupDialog({ defaultScope: "full" }, "en");

    expect(screen.getByText("Full conversation")).toBeTruthy();
    expect(screen.getByText("Select turns")).toBeTruthy();
    expect(screen.getByText("Pick one or more turns to share")).toBeTruthy();
    // 切「选择问答」见英文勾选列表，空勾选创建禁用；回 full 直接创建成功
    await user.click(screen.getByRole("radio", { name: /Select turns/ }));
    expect(screen.getByText(/Pick turns to share/)).toBeTruthy();
    expect((screen.getByRole("button", { name: "Create share link" }) as HTMLButtonElement).disabled).toBe(true);
    await user.click(screen.getByRole("radio", { name: /Full conversation/ }));
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
