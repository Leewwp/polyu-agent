import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { toast } from "sonner";

import { FeedSidebar } from "./FeedSidebar";
import { FeedLangContext } from "./feedLang";
import { guestLogin } from "@/services/authService";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import { useEngineStore } from "@/stores/engineStore";

/**
 * FeedSidebar 的 GuestStatusBadge 复用验证 +
 * 匿名零 /auth 请求（公开页红线）+ 内容导航四条目 + 登录态最近会话渲染。
 * 「新对话」游客直通三态（已登录直达/铸号成功/铸号失败引导登录）。
 */

// authService 整体打桩：GuestStatusBadge 的 fetchGuestQuota 返回可控余量；
// authStore 引用的 login/logout/getCurrentUser/guestLogin 一并补空桩防缺导出
vi.mock("@/services/authService", () => ({
  getCurrentUser: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  guestLogin: vi.fn(),
  fetchGuestQuota: vi.fn(async () => ({ role: "guest", dailyLimit: 3, remaining: 1 }))
}));

// sonner 打桩：toast 本体可调用 + success/error 方法（authStore.guestLogin 调 toast.success）
vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), message: vi.fn() })
}));

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

function renderSidebar(props: { open?: boolean; initialEntries?: string[] } = {}) {
  const sidebar = <FeedSidebar open={props.open ?? false} onClose={() => {}} />;
  return render(
    <MemoryRouter initialEntries={props.initialEntries ?? ["/"]}>
      <FeedLangContext.Provider value={{ lang: "zh", setLang: () => {} }}>
        <Routes>
          <Route path="*" element={sidebar} />
          <Route path="/chat" element={<div>CHAT_PAGE_MARK</div>} />
          <Route path="/login" element={<div>LOGIN_PAGE_MARK</div>} />
        </Routes>
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("FeedSidebar", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false });
    useChatStore.setState({ sessions: [], sessionsLoaded: false });
    // 会话源随引擎档位：默认预置 workflow（等价旧行为，探测不进用例网络面）
    useEngineStore.setState({ engineType: "workflow", loading: false, error: null });
    useAgentChatStore.setState({ sessions: [], sessionsLoaded: false });
    vi.mocked(toast).mockClear();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renders content nav (4 entries) with featured/all/topics links and guest card for anonymous visitors", () => {
    const { requestedUrls } = instrumentNetwork();
    renderSidebar();

    // 内容导航四条目：精选/全部资讯/主题/热点榜均为真实路由
    // （导航名含 emoji 前缀，用正则匹配可访问名）
    expect(screen.getByRole("link", { name: /精选/ }).getAttribute("href")).toBe("/");
    expect(screen.getByRole("link", { name: /全部资讯/ }).getAttribute("href")).toBe("/?view=all");
    expect(screen.getByRole("link", { name: /热点榜/ }).getAttribute("href")).toBe("/hot");
    expect(screen.getByRole("link", { name: /主题/ }).getAttribute("href")).toBe("/topics");

    // 匿名游客卡：静态文案（无假余量数字）+ 登录/注册双按钮
    expect(screen.getByText(/游客身份 · 每日 3 次免登录 Agent 对话/)).toBeTruthy();
    expect(screen.getAllByRole("link", { name: "登录" }).length).toBeGreaterThan(0);
    expect(screen.getByRole("link", { name: "注册" }).getAttribute("href")).toBe("/register");
    expect(screen.getByText("登录后可同步全部历史对话")).toBeTruthy();

    // 匿名态不渲染 GuestStatusBadge（role=status），也不触发任何 /auth 请求
    expect(screen.queryByRole("status")).toBeNull();
    expect(requestedUrls.filter((url) => url.includes("/auth"))).toEqual([]);
  });

  it("view-switch links strip search params but keep category (doc 32 decision B)", () => {
    // 检索态（q+sort+order+category 齐全）切版式：q/sort/order 剥除、category 随行
    renderSidebar({
      initialEntries: ["/?q=%E6%AF%95%E4%B8%9A&sort=relevance&order=asc&category=research"]
    });
    expect(screen.getByRole("link", { name: /精选/ }).getAttribute("href")).toBe(
      "/?category=research"
    );
    expect(screen.getByRole("link", { name: /全部资讯/ }).getAttribute("href")).toBe(
      "/?view=all&category=research"
    );
  });

  it("reuses GuestStatusBadge to show live guest quota when signed in as guest", async () => {
    useAuthStore.setState({
      user: { userId: "g-1", username: "guest-1", role: "guest" },
      isAuthenticated: true
    });
    renderSidebar();

    // 复用验证：GuestStatusBadge（role=status）出现在侧栏游客卡内
    await waitFor(() => {
      expect(screen.getByRole("status", { name: "游客试用状态" })).toBeTruthy();
    });
    expect(screen.getByText(/今日剩余 1\/3 次/)).toBeTruthy();
    // 徽章自带注册入口；游客卡主按钮=登录
    expect(screen.getByRole("link", { name: /注册/ }).getAttribute("href")).toBe("/register");
    expect(screen.getByRole("link", { name: "登录" }).getAttribute("href")).toBe("/login");
    // 匿名静态文案不再出现
    expect(screen.queryByText(/游客身份 · 每日 3 次免登录/)).toBeNull();
  });

  it("renders real recent sessions for signed-in users and hides the guest card", () => {
    const fetchSessions = vi.fn(async () => {});
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user" },
      isAuthenticated: true
    });
    useChatStore.setState({
      sessions: [
        { id: "s-1", title: "2026/27 硕士申请材料清单" },
        { id: "s-2", title: "宿舍申请时间线与截止日" },
        { id: "s-3", title: "跨学院选课规则咨询" },
        { id: "s-4", title: "第四条不该展示（超出 3 条上限）" }
      ],
      sessionsLoaded: true,
      fetchSessions
    });
    renderSidebar();

    // 最近对话：真实会话（上限 3 条，原型形态），点击进 /chat/:id
    expect(screen.getByRole("link", { name: "2026/27 硕士申请材料清单" }).getAttribute("href")).toBe("/chat/s-1");
    expect(screen.getByRole("link", { name: "宿舍申请时间线与截止日" }).getAttribute("href")).toBe("/chat/s-2");
    expect(screen.getByRole("link", { name: "跨学院选课规则咨询" }).getAttribute("href")).toBe("/chat/s-3");
    expect(screen.queryByText("第四条不该展示（超出 3 条上限）")).toBeNull();
    // 已登录普通用户：游客卡整体隐藏、同步提示不再出现
    expect(screen.queryByText(/游客身份/)).toBeNull();
    expect(screen.queryByText("登录后可同步全部历史对话")).toBeNull();
  });

  it("fetches sessions lazily only when authenticated (anonymous stays silent)", async () => {
    const { requestedUrls } = instrumentNetwork();
    const fetchSessions = vi.fn(async () => {});
    useChatStore.setState({ sessions: [], sessionsLoaded: false, fetchSessions });

    renderSidebar();
    expect(fetchSessions).not.toHaveBeenCalled();
    expect(requestedUrls).toEqual([]);

    await act(async () => {
      useAuthStore.setState({
        user: { userId: "u-2", username: "bob", role: "user" },
        isAuthenticated: true
      });
    });
    expect(fetchSessions).toHaveBeenCalled();
  });

  it("highlights the topics nav entry on /topics and /topics/:slug (real route)", () => {
    const { unmount } = renderSidebar({ initialEntries: ["/topics"] });
    let topicsLink = screen.getByRole("link", { name: /主题/ });
    // active 态类含 --polyu-red-50 底（原型 .nav-item.active）
    expect(topicsLink.getAttribute("class")).toContain("polyu-red-50");
    unmount();

    renderSidebar({ initialEntries: ["/topics/ai"] });
    topicsLink = screen.getByRole("link", { name: /主题/ });
    expect(topicsLink.getAttribute("class")).toContain("polyu-red-50");
    // 精选导航在主题视图不点亮
    const featuredLink = screen.getByRole("link", { name: /精选/ });
    expect(featuredLink.getAttribute("class")).not.toContain("polyu-red-50");
  });

  it("navigates straight to /chat for signed-in users without casting a guest", async () => {
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user" },
      isAuthenticated: true
    });
    renderSidebar();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "新对话" }));

    expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
    expect(vi.mocked(guestLogin)).not.toHaveBeenCalled();
    // 热点榜为真实路由，此处仅存导航 Link（不再有占位按钮）
    expect(screen.queryByRole("button", { name: /热点榜/ })).toBeNull();
  });

  it("casts a guest account then enters chat for anonymous visitors", async () => {
    vi.mocked(guestLogin).mockResolvedValue({
      userId: "g-9",
      role: "guest",
      token: "token-x",
      avatar: ""
    });
    renderSidebar();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "新对话" }));

    await waitFor(() => {
      expect(screen.getByText("CHAT_PAGE_MARK")).toBeTruthy();
    });
    expect(vi.mocked(guestLogin)).toHaveBeenCalledTimes(1);
  });

  it("guides anonymous visitors to /login when guest casting is rejected (flag off, no crash)", async () => {
    vi.mocked(guestLogin).mockRejectedValue(new Error("匿名试用未开启"));
    renderSidebar();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "新对话" }));

    await waitFor(() => {
      expect(screen.getByText("LOGIN_PAGE_MARK")).toBeTruthy();
    });
  });

  it("share view: replaces recent chats with the single shared entry and stays offline (issue #91)", async () => {
    // 已登录用户打开分享视图：不探测引擎档位、不拉会话列表（零网络，含登录态）
    const { requestedUrls } = instrumentNetwork();
    const initializeEngine = vi.fn(async () => {});
    const fetchSessions = vi.fn(async () => {});
    const agLoadSessions = vi.fn(async () => {});
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice", role: "user" },
      isAuthenticated: true
    });
    useEngineStore.setState({ engineType: null, loading: false, error: null, initialize: initializeEngine });
    useChatStore.setState({ sessions: [], sessionsLoaded: false, fetchSessions });
    useAgentChatStore.setState({ sessions: [], sessionsLoaded: false, loadSessions: agLoadSessions });

    render(
      <MemoryRouter initialEntries={["/share/c/TOKEN"]}>
        <FeedLangContext.Provider value={{ lang: "zh", setLang: () => {} }}>
          <Routes>
            <Route
              path="/share/c/:token"
              element={<FeedSidebar open={false} onClose={() => {}} shareView={{ title: "宿舍申请咨询" }} />}
            />
          </Routes>
        </FeedLangContext.Provider>
      </MemoryRouter>
    );

    // 内容导航原样保留；「最近对话」=被分享会话单条目（只读徽标、非链接不可切换）
    expect(screen.getByRole("link", { name: /精选/ }).getAttribute("href")).toBe("/");
    expect(screen.getByText("最近对话")).toBeTruthy();
    expect(screen.getByText("宿舍申请咨询")).toBeTruthy();
    expect(screen.getByText("分享 · 只读")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "宿舍申请咨询" })).toBeNull();
    expect(screen.queryByText("登录后可同步全部历史对话")).toBeNull();

    // 零网络：不探引擎档位、两 store 均不拉会话（公开页红线在分享视图态对登录态同样守）
    await act(async () => {});
    expect(initializeEngine).not.toHaveBeenCalled();
    expect(fetchSessions).not.toHaveBeenCalled();
    expect(agLoadSessions).not.toHaveBeenCalled();
    expect(requestedUrls).toEqual([]);

    // 已登录非游客：游客卡不渲染（正常口径不变）
    expect(screen.queryByText(/游客身份/)).toBeNull();
  });

  it("share view without a loaded title (loading/invalid) renders no shared entry", () => {
    render(
      <MemoryRouter initialEntries={["/share/c/BAD"]}>
        <FeedLangContext.Provider value={{ lang: "zh", setLang: () => {} }}>
          <Routes>
            <Route
              path="/share/c/:token"
              element={<FeedSidebar open={false} onClose={() => {}} shareView={{ title: null }} />}
            />
          </Routes>
        </FeedLangContext.Provider>
      </MemoryRouter>
    );
    expect(screen.queryByText("分享 · 只读")).toBeNull();
    // 内容导航仍在（壳不因加载/无效态退场）
    expect(screen.getByRole("link", { name: /精选/ })).toBeTruthy();
  });
});
