import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { AgentSharePage } from "@/pages/AgentSharePage";
import { useAuthStore } from "@/stores/authStore";

const getPublicAgentShareMock = vi.hoisted(() => vi.fn());

vi.mock("@/services/agentShareService", () => ({
  getPublicAgentShare: getPublicAgentShareMock,
  // AgentSessionShareButton 同源引用（分享视图态不渲染，防缺导出补桩）
  createAgentShare: vi.fn()
}));

/**
 * issue #91 会话分享壳化视图：/share/c/:token 落 FeedShell 真实产品壳——
 * 侧栏（内容导航+被分享会话单条目+游客卡）、主体 AgentTurnItem 同款渲染
 * （markdown+来源徽章）、CTA 三分支、无效态壳内统一语义、v1 降级、
 * 匿名零网络请求红线（公开页范式）。隐私门=DOM 不出现身份/思考/工具轨迹。
 * useParams 须经真实 Route 匹配（裸 MemoryRouter 子组件拿不到参数）。
 */
function setup(token = "TOKEN123") {
  return render(
    <MemoryRouter initialEntries={[`/share/c/${token}`]}>
      <Routes>
        <Route path="/share/c/:token" element={<AgentSharePage />} />
        <Route path="/login" element={<div>LOGIN_PAGE_MARK</div>} />
        <Route path="/chat" element={<div>CHAT_PAGE_MARK</div>} />
      </Routes>
    </MemoryRouter>
  );
}

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

const SHARE_FIXTURE = {
  title: "宿舍申请咨询",
  lang: "zh",
  contentVersion: "v2",
  createTime: "2026-09-19T00:00:00Z",
  messages: [
    { role: "user", content: "如何申请宿舍？", createTime: "2026-09-19T00:00:00Z" },
    {
      role: "assistant",
      content: "## 申请步骤\n1. 在线提交申请",
      createTime: "2026-09-19T00:01:00Z",
      sources: [
        {
          index: 1,
          docId: "d1",
          docName: "宿舍申请指南.pdf",
          excerpt: "申请人须在截止日前在线提交。",
          sourceType: "file",
          url: "https://www.polyu.edu.hk/dorm"
        },
        {
          index: 2,
          docId: "d2",
          docName: "Student Housing",
          excerpt: "Apply online.",
          sourceType: "url",
          url: "https://www.polyu.edu.hk/housing"
        }
      ]
    }
  ]
};

describe("AgentSharePage（issue #91 壳化视图）", () => {
  beforeEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false, isLoading: false });
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    getPublicAgentShareMock.mockReset();
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it("落真实产品壳：侧栏导航+被分享会话单条目+同款渲染（markdown+来源徽章）", async () => {
    getPublicAgentShareMock.mockResolvedValue(SHARE_FIXTURE);
    setup();

    // 壳在场：侧栏品牌/新对话/内容导航条目与游客卡（真实产品观感）
    await waitFor(() => {
      expect(screen.getByText("PolyUGuide")).toBeTruthy();
    });
    expect(screen.getByRole("button", { name: "新对话" })).toBeTruthy();
    expect(screen.getByRole("link", { name: /精选/ }).getAttribute("href")).toBe("/");
    expect(screen.getByRole("link", { name: /热点榜/ }).getAttribute("href")).toBe("/hot");
    expect(screen.getByText(/游客身份 · 每日 3 次免登录 Agent 对话/)).toBeTruthy();

    // 侧栏「最近对话」=被分享会话单条目（固定 active、只读徽标、非链接不可切换）；
    // 快照标题同时出现在桌面顶栏标题位（fluid 档分享视图标题=快照标题）
    expect(screen.getAllByText("宿舍申请咨询").length).toBe(2);
    expect(screen.getByText("分享 · 只读")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "宿舍申请咨询" })).toBeNull();
    expect(screen.queryByText("登录后可同步全部历史对话")).toBeNull();

    // 主体=AgentTurnItem 同款渲染：轮次卡+markdown（标题）逐字还原
    expect(screen.getByText("TURN 1")).toBeTruthy();
    expect(screen.getByText("如何申请宿舍？")).toBeTruthy();
    expect(screen.getByRole("heading", { level: 2, name: "申请步骤" })).toBeTruthy();

    // 来源徽章（v2）：计数在答案下方，展开见 docName/excerpt
    expect(screen.getByText("2 篇来源")).toBeTruthy();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /2 篇来源/ }));
    expect(screen.getByText("宿舍申请指南.pdf")).toBeTruthy();
    expect(screen.getByText(/申请人须在截止日前在线提交/)).toBeTruthy();

    // 壳内细横幅（非官方+时效提示）
    expect(screen.getByText(/非官方且可能过期/)).toBeTruthy();

    // 隐私门：思考/工具轨迹语汇不出现（快照白名单外字段无渲染面）
    expect(screen.queryByText(/thinking|reasoning/i)).toBeNull();
    expect(screen.queryByText(/生成 \d/)).toBeNull();
  });

  it("#139 capability 门：公开分享页零功能变化——不出现 Copy/Share 操作栏", async () => {
    getPublicAgentShareMock.mockResolvedValue(SHARE_FIXTURE);
    setup();
    await waitFor(() => {
      expect(screen.getByText("TURN 1")).toBeTruthy();
    });
    // 只读投影不启用 Answer 操作栏（showAnswerActions 默认 false）
    expect(document.querySelector(".agent-answer-actions")).toBeNull();
    expect(screen.queryByRole("button", { name: /复制回答|Copy answer/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /分享这一轮|Share this turn/ })).toBeNull();
  });

  it("匿名态零网络请求（公开页红线：不 /auth、不拉会话、不探引擎档位）", async () => {
    const { requestedUrls } = instrumentNetwork();
    getPublicAgentShareMock.mockResolvedValue(SHARE_FIXTURE);
    setup();

    await waitFor(() => {
      expect(screen.getByText("TURN 1")).toBeTruthy();
    });
    expect(requestedUrls).toEqual([]);
  });

  it("CTA 三分支：未登录双钮（登录/游客开聊）、已登录单钮（回到我的对话）", async () => {
    getPublicAgentShareMock.mockResolvedValue(SHARE_FIXTURE);
    setup();

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "登录后继续提问" }).getAttribute("href")).toBe("/login");
    });
    expect(screen.getByRole("button", { name: "以游客身份开始新对话" })).toBeTruthy();
    expect(screen.queryByText("回到我的对话")).toBeNull();

    // 已登录：单钮回自己的对话，登录引导退场
    useAuthStore.setState({
      user: { userId: "u-1", username: "alice@example.com", role: "user" },
      isAuthenticated: true
    });
    await waitFor(() => {
      expect(screen.getByRole("link", { name: "回到我的对话" }).getAttribute("href")).toBe("/chat");
    });
    expect(screen.queryByText("登录后继续提问")).toBeNull();
    expect(screen.queryByText(/游客身份 · 每日 3 次/)).toBeNull();
  });

  it("无效链接：壳内统一「分享链接无效或已撤销」+ 首页出口", async () => {
    getPublicAgentShareMock.mockRejectedValue(new Error("分享链接无效或已撤销"));
    setup("BADTOKEN");

    await waitFor(() => {
      expect(screen.getByText("分享链接无效或已撤销")).toBeTruthy();
    });
    expect(screen.getByRole("link", { name: /去首页 · Home/ }).getAttribute("href")).toBe("/");
    // 壳仍在（侧栏导航在场），不退化为外部文档页
    expect(screen.getByRole("button", { name: "新对话" })).toBeTruthy();
  });

  it("v1 旧快照（无 sources）优雅降级：正常渲染只是没有来源徽章", async () => {
    getPublicAgentShareMock.mockResolvedValue({
      ...SHARE_FIXTURE,
      contentVersion: "v1",
      messages: [
        { role: "user", content: "问", createTime: "2026-09-19T00:00:00Z" },
        { role: "assistant", content: "答", createTime: "2026-09-19T00:01:00Z" }
      ]
    });
    setup();

    await waitFor(() => {
      expect(screen.getByText("TURN 1")).toBeTruthy();
    });
    expect(screen.queryByText(/篇来源/)).toBeNull();
  });

  it("noindex meta 在场（首发不收录立场，后端同源双保险）", async () => {
    getPublicAgentShareMock.mockResolvedValue(SHARE_FIXTURE);
    setup();
    await waitFor(() => {
      expect(screen.getByText("TURN 1")).toBeTruthy();
    });
    const robots = document.querySelector('meta[name="robots"]');
    expect(robots?.getAttribute("content")).toBe("noindex, nofollow");
  });
});
