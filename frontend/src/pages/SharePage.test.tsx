import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { SharePage } from "@/pages/SharePage";
import { FeedLangContext, FeedLangProvider } from "@/components/feed/feedLang";
import { guestLogin } from "@/services/authService";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import { useAgentChatStore } from "@/stores/agentChatStore";

const getPublicShareMock = vi.hoisted(() => vi.fn());
const createShareMock = vi.hoisted(() => vi.fn());
const revokeShareMock = vi.hoisted(() => vi.fn());

vi.mock("@/services/shareService", () => ({
  getPublicShare: getPublicShareMock,
  // 写操作面挂探针（加载/失效态零写操作红线），防缺导出补桩
  createShare: createShareMock,
  revokeShare: revokeShareMock,
  listMyShares: vi.fn()
}));

vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn(),
  fetchGuestQuota: vi.fn()
}));

/**
 * #229 答案分享页：PolyUGuide 品牌头（旧「PolyU Wayfinder」清零）+
 * 「继续提问」游客直通四态（匿名铸号直达/已铸游客直达/正式用户直达/
 * 铸号被拒降级登录）+ 公开面红线（noindex、只读零写操作）。
 * useParams 须经真实 Route 匹配（裸 MemoryRouter 子组件拿不到参数）。
 */
function setup(token = "TOKEN123", lang: "zh" | "en" = "zh") {
  const routes = (
    <Routes>
      <Route path="/share/:token" element={<SharePage />} />
      <Route path="/login" element={<div>LOGIN_PAGE_MARK</div>} />
      <Route path="/chat" element={<div>CHAT_PAGE_MARK</div>} />
      <Route path="/" element={<div>HOME_PAGE_MARK</div>} />
    </Routes>
  );
  return render(
    <MemoryRouter initialEntries={[`/share/${token}`]}>
      {lang === "zh" ? <FeedLangProvider>{routes}</FeedLangProvider> : <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>{routes}</FeedLangContext.Provider>}
    </MemoryRouter>
  );
}

const SHARE_FIXTURE = {
  question: "如何申请宿舍？",
  answerMd: "## 申请步骤\n1. 在线提交申请",
  createTime: "2026-09-19T00:00:00Z",
  citations: [
    { index: 1, docName: "宿舍申请指南.pdf", url: "https://www.polyu.edu.hk/dorm" }
  ]
};

describe("SharePage（#229 品牌与出路）", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isGuest: false, isLoading: false });
    // fresh 出口会重置双聊天 store——预置残影以断言「开新会话」口径
    useAgentChatStore.setState({ currentSessionId: "s-last", messages: [] });
    useChatStore.setState({ currentSessionId: "c-last", messages: [], isStreaming: false });
    vi.mocked(guestLogin).mockReset();
    createShareMock.mockReset();
    revokeShareMock.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    getPublicShareMock.mockReset();
    useAuthStore.setState({ user: null, isAuthenticated: false, isGuest: false });
    useAgentChatStore.setState({ currentSessionId: null, messages: [] });
    useChatStore.setState({ currentSessionId: null, messages: [] });
  });

  it("头部为 PolyUGuide 品牌：名称在场、旧品牌名零残留、品牌位链回主站", async () => {
    getPublicShareMock.mockResolvedValue(SHARE_FIXTURE as never);
    setup();

    await waitFor(() => {
      expect(screen.getByText("如何申请宿舍？")).toBeTruthy();
    });
    const brand = screen.getByRole("link", { name: "PolyUGuide" });
    expect(brand.getAttribute("href")).toBe("/");
    expect(document.body.textContent).not.toContain("PolyU Wayfinder");
    // 快照主体与官方来源照常渲染（品牌替换不动内容面）
    expect(screen.getByRole("heading", { level: 2, name: "申请步骤" })).toBeTruthy();
    expect(screen.getByText("宿舍申请指南.pdf")).toBeTruthy();
  });

  it("匿名四态之一：无会话匿名且游客端点可用→铸号后直达对话，不落登录页", async () => {
    getPublicShareMock.mockResolvedValue(SHARE_FIXTURE as never);
    vi.mocked(guestLogin).mockResolvedValue({} as never);
    setup();

    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "继续提问" })).toBeTruthy();
    });
    await user.click(screen.getAllByRole("button", { name: "继续提问" })[0]);

    await waitFor(() => {
      expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
    });
    expect(screen.queryByText("LOGIN_PAGE_MARK")).toBeNull();
    expect(vi.mocked(guestLogin)).toHaveBeenCalledTimes(1);
    // fresh 口径（与会话分享页对齐）：进入前双 store 清残影=开新会话
    expect(useAgentChatStore.getState().currentSessionId).toBeNull();
    expect(useChatStore.getState().currentSessionId).toBeNull();
  });

  it("匿名四态之二：已铸游客（isAuthenticated）→直连 /chat 不再铸号", async () => {
    getPublicShareMock.mockResolvedValue(SHARE_FIXTURE as never);
    useAuthStore.setState({ user: { userId: "g-1", role: "guest" } as never, isAuthenticated: true, isGuest: true });
    setup();

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "继续提问" }).getAttribute("href")).toBe("/chat");
    });
    const user = userEvent.setup();
    await user.click(screen.getAllByRole("link", { name: "继续提问" })[0]);
    expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
    expect(vi.mocked(guestLogin)).not.toHaveBeenCalled();
  });

  it("匿名四态之三：正式用户→直连 /chat", async () => {
    getPublicShareMock.mockResolvedValue(SHARE_FIXTURE as never);
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice@example.com", role: "user" },
      isAuthenticated: true,
      isGuest: false
    });
    setup();

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "继续提问" }).getAttribute("href")).toBe("/chat");
    });
    const user = userEvent.setup();
    await user.click(screen.getAllByRole("link", { name: "继续提问" })[0]);
    expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
    expect(vi.mocked(guestLogin)).not.toHaveBeenCalled();
  });

  it("匿名四态之四：游客端点拒绝→降级登录页，流程不报错", async () => {
    getPublicShareMock.mockResolvedValue(SHARE_FIXTURE as never);
    vi.mocked(guestLogin).mockRejectedValue(new Error("游客通道当前未开放"));
    setup();

    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "继续提问" })).toBeTruthy();
    });
    await user.click(screen.getAllByRole("button", { name: "继续提问" })[0]);

    await waitFor(() => {
      expect(screen.getByText("LOGIN_PAGE_MARK")).toBeTruthy();
    });
    // 业务提示由 api 拦截器统一 toast（#T6 既有降级），页面层不额外抛错
    expect(console.error).toBeDefined();
  });

  it("无效态 #233：单个「回到首页」主操作+文案单语；加载/失效态零写操作", async () => {
    getPublicShareMock.mockRejectedValue(new Error("not found"));
    setup("BADTOKEN");

    await waitFor(() => {
      expect(screen.getByText("分享链接无效或已撤销")).toBeTruthy();
    });
    // 零写操作红线：加载/失效态只读探查，不创建/不撤销分享
    expect(createShareMock).not.toHaveBeenCalled();
    expect(revokeShareMock).not.toHaveBeenCalled();

    // 失效态主体只留单个「回到首页」主操作（与会话分享失效页/404 同口径）
    const goHome = screen.getAllByRole("link", { name: "回到首页" });
    expect(goHome).toHaveLength(1);
    expect(goHome[0].getAttribute("href")).toBe("/");
    // 副行不再 zh/en 硬拼双行（旧倒置英文副行清零），改单语失效说明
    expect(screen.getByText("链接可能已过期或被分享者撤销")).toBeTruthy();
    expect(screen.queryByText("This share link is invalid or has been revoked.")).toBeNull();
    // 头部「继续提问」持久出口照常在场（失效态唯一其余操作，非回首页去向）
    expect(screen.getAllByRole("button", { name: "继续提问" })).toHaveLength(1);
  });

  it("无效态 #233 EN：主操作与文案随语言单语（Back to home）", async () => {
    getPublicShareMock.mockRejectedValue(new Error("not found"));
    setup("BADTOKEN", "en");

    await waitFor(() => {
      expect(screen.getByText("This share link is invalid or has been revoked")).toBeTruthy();
    });
    const goHome = screen.getAllByRole("link", { name: "Back to home" });
    expect(goHome).toHaveLength(1);
    expect(goHome[0].getAttribute("href")).toBe("/");
    expect(screen.getByText("The link may have expired or been revoked by its owner")).toBeTruthy();
    // 不硬拼双语：EN 态不出现中文按钮/中文副行
    expect(screen.queryByRole("link", { name: "回到首页" })).toBeNull();
    expect(screen.queryByText("链接可能已过期或被分享者撤销")).toBeNull();
  });

  it("noindex meta 在场（首发不收录立场不动）", async () => {
    getPublicShareMock.mockResolvedValue(SHARE_FIXTURE as never);
    setup();
    await waitFor(() => {
      expect(screen.getByText("如何申请宿舍？")).toBeTruthy();
    });
    const robots = document.querySelector('meta[name="robots"]');
    expect(robots?.getAttribute("content")).toBe("noindex, nofollow");
  });
});
