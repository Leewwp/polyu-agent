import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { FeedShell } from "./FeedShell";
import { FeedLangProvider } from "./feedLang";
import { feedDateLabels } from "@/services/newsMapping";
import { formatStarCount } from "@/hooks/useGitHubStars";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useChatStore } from "@/stores/chatStore";
import { useEngineStore } from "@/stores/engineStore";

/**
 * MobileTopbar 补 LangPill——移动端顶栏与桌面顶栏同一全局语言值，
 * 任一 pill 切换两处同步（feed/hot/topics/detail 五页共用本壳）。
 * 2026-09-13：顶栏日期改实时 HKT 值（feedDateLabels）——断言改为
 * 同源计算（同一 Date 喂给组件与断言，jsdom 下 toLocaleDateString timeZone 可用）。
 * #136（2026-09-26）：移动顶栏去日期（五元素防挤占），日期断言收归桌面顶栏 long 档。
 * 注：jsdom 30 不暴露 window.localStorage（feedLang.test 同款内存桩经验）。
 */

const STORAGE_KEY = "polyu.feed.lang";

function installLocalStorageStub(): Map<string, string> {
  const mem = new Map<string, string>();
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

// 全文件共用渲染 helper（参数化合一，星钮 describe 不再局部同名遮蔽）：
// 默认资讯壳；shareView 供星钮 describe 的分享视图用例
function renderShell(shareView?: { title: string | null }) {
  return render(
    <MemoryRouter>
      <FeedLangProvider>
        <FeedShell title={{ zh: "精选", en: "Featured" }} shareView={shareView}>
          <p>shell-content</p>
        </FeedShell>
      </FeedLangProvider>
    </MemoryRouter>
  );
}

/**
 * #337 GitHub 星钮 seam：渲染结果/链接语义/降级态经 mock fetch 断言，
 * 星数形态直测 formatStarCount 纯函数（useGitHubStars.ts 同文件导出），
 * 不断言 hook 内部缓存实现。壳挂星钮（useGitHubStars 发起 api.github.com
 * 请求）后，全文件用例统一桩掉 fetch 保持封闭；星钮用例自行覆写桩值。
 */
beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("FeedShell global language pills", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    mem = installLocalStorageStub();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("renders a LangPill in both desktop and mobile topbars, kept in sync", async () => {
    renderShell();

    // 桌面 + 移动两个顶栏各一组 中/EN
    expect(screen.getAllByRole("button", { name: "中" })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "EN" })).toHaveLength(2);
    // 顶栏日期只在桌面顶栏（long 档实时 HKT）；移动顶栏去日期（#136），短日期不得再现
    expect(screen.getByText(feedDateLabels(new Date(), "zh").long)).toBeTruthy();
    expect(screen.queryByText(feedDateLabels(new Date(), "zh").short)).toBeNull();

    const user = userEvent.setup();
    await user.click(screen.getAllByRole("button", { name: "EN" })[1]);

    // 两组 pill 同步切红、日期转英文、localStorage 记忆
    for (const pill of screen.getAllByRole("button", { name: "EN" })) {
      expect(pill.getAttribute("aria-pressed")).toBe("true");
    }
    expect(screen.getByText(feedDateLabels(new Date(), "en").long)).toBeTruthy();
    expect(mem.get(STORAGE_KEY)).toBe("en");
  });

  it("topbar date is real-time, not the retired 2026-09-10 mock constant", () => {
    renderShell();
    // 日期回归断言：写死常量「9月10日 · 周四」不得再现（任何日期的实时值都不含「9月10日」，
    // 除非真实跨到 9 月 10 日——用不含该子串的形状断言替代脆弱的日期排除法：
    // 直接断言渲染值与 feedDateLabels 当日计算一致已在上一用例覆盖，此处守常量删除回归）
    expect(screen.queryByText("9月10日 · 周四")).toBeNull();
    expect(screen.queryByText("9月10日 · 周四 · 2026")).toBeNull();
  });
});

describe("FeedShell fluid topbar session title", () => {
  beforeEach(() => {
    installLocalStorageStub();
    useEngineStore.setState({ engineType: null, loading: false, error: null });
    useChatStore.setState({ sessions: [], currentSessionId: null, sessionsLoaded: true });
    useAgentChatStore.setState({ sessions: [], currentSessionId: null, sessionsLoaded: true });
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  /** 顶栏标题位=class 含 text-[17px] 的 div（会话标题与侧栏最近对话同文，按位置区分） */
  function topbarTitle(): string | null {
    const hits = screen.getAllByText(/./).filter(
      (el) => el.tagName === "DIV" && el.className.includes("text-[17px]")
    );
    return hits.length === 1 ? hits[0].textContent : null;
  }

  function renderFluidShell() {
    return render(
      <MemoryRouter>
        <FeedLangProvider>
          <FeedShell title={{ zh: "智能问答", en: "Smart Q&A" }} fluid>
            <p>chat-body</p>
          </FeedShell>
        </FeedLangProvider>
      </MemoryRouter>
    );
  }

  it("falls back to the page name while no current session exists", () => {
    renderFluidShell();
    expect(topbarTitle()).toBe("智能问答");
  });

  it("shows the current agent-engine session title once a session is active", () => {
    useEngineStore.setState({ engineType: "agent" });
    useAgentChatStore.setState({
      currentSessionId: "ag-1",
      sessions: [{ id: "ag-1", title: "博士申请材料清单", lastTime: "2026-09-13" }]
    });
    renderFluidShell();
    expect(topbarTitle()).toBe("博士申请材料清单");
  });

  it("reads the workflow store when the engine is workflow", () => {
    useEngineStore.setState({ engineType: "workflow" });
    useChatStore.setState({
      currentSessionId: "wf-1",
      sessions: [{ id: "wf-1", title: "图书馆开放时间咨询" }]
    });
    renderFluidShell();
    expect(topbarTitle()).toBe("图书馆开放时间咨询");
  });

  it("#233 renders no engine/model badge in the desktop chat topbar (no /agent/v1/meta probe)", () => {
    // 旧 EngineBadge 挂载即拉 /agent/v1/meta——徽章移除后 fluid agent 档零探测请求
    const requestedUrls: string[] = [];
    const openSpy = vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation(
      (...args: Parameters<XMLHttpRequest["open"]>) => {
        requestedUrls.push(String(args[1]));
      }
    );
    useEngineStore.setState({ engineType: "agent" });
    renderFluidShell();

    expect(topbarTitle()).toBe("智能问答");
    expect(requestedUrls.filter((url) => url.includes("/agent/v1/meta"))).toEqual([]);
    // 徽章三态文案（探测中/框架名/离线）不再有渲染面
    expect(screen.queryByText("探测中")).toBeNull();
    expect(screen.queryByText("离线")).toBeNull();
    openSpy.mockRestore();
  });

  it("#292 Escape closes the mobile session drawer (self-built aside, no radix dialog)", async () => {
    useEngineStore.setState({ engineType: "agent" });
    renderFluidShell();

    // 抽屉开=FeedSidebar 在壳根下渲染遮罩 div（aria-hidden）；关=遮罩卸载
    const shellRoot = screen.getByRole("complementary").parentElement as HTMLElement;
    const overlay = () =>
      Array.from(shellRoot.children).find((el) => el.getAttribute("aria-hidden") === "true");

    expect(overlay()).toBeUndefined();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "打开菜单" }));
    expect(overlay()).toBeTruthy();

    fireEvent.keyDown(window, { key: "Escape" });
    expect(overlay()).toBeUndefined();
  });
});

describe("FeedShell GitHub star button (#337)", () => {
  beforeEach(() => {
    installLocalStorageStub();
    useEngineStore.setState({ engineType: null, loading: false, error: null });
    useChatStore.setState({ sessions: [], currentSessionId: null, sessionsLoaded: true });
    useAgentChatStore.setState({ sessions: [], currentSessionId: null, sessionsLoaded: true });
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  /** 星数 seam 桩：GitHub API 形状（stargazers_count）——好测试只看这个外部形态 */
  function stubStarsResponse(stargazersCount: number) {
    return vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ stargazers_count: stargazersCount })
    });
  }

  it("renders the repo link in both topbars with new-window semantics and the switched repo URL", async () => {
    const fetchMock = stubStarsResponse(321);
    vi.stubGlobal("fetch", fetchMock);
    renderShell();

    // 桌面胶囊 + 移动 icon-only 各一枚（jsdom 无 CSS 布局，双顶栏均在树中）
    const links = screen.getAllByRole("link", { name: "打开 GitHub 仓库" });
    expect(links).toHaveLength(2);
    for (const link of links) {
      expect(link.getAttribute("href")).toBe("https://github.com/Leewwp/polyu-agent");
      expect(link.getAttribute("target")).toBe("_blank");
      expect(link.getAttribute("rel")).toContain("noreferrer");
    }
    // 切仓在 seam 上可见：请求打到本项目仓库（非上游 nageoffer/ragent）
    expect(fetchMock).toHaveBeenCalledWith(
      "https://api.github.com/repos/Leewwp/polyu-agent",
      expect.anything()
    );
  });

  // 星数形态断言直测 formatStarCount 纯函数（组件 seam 只保留链接/降级语义，
  // 避免同一份格式化断言在组件与纯函数两侧双跑）
  it("formatStarCount: null→--, <1000 raw, >=1000 one-decimal x.k with trailing .0 trimmed", () => {
    expect(formatStarCount(null)).toBe("--");
    expect(formatStarCount(0)).toBe("0");
    expect(formatStarCount(321)).toBe("321");
    expect(formatStarCount(999)).toBe("999");
    expect(formatStarCount(12345)).toBe("12.3k");
    expect(formatStarCount(2000)).toBe("2k");
  });

  it("degrades the chip to -- when the API is unreachable, without throwing", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
    renderShell();
    // 失败退避不报错；chip 落 "--"（仅桌面胶囊有 chip，移动 icon-only 无文案）
    expect(await screen.findByText("--")).toBeTruthy();
  });

  it("keeps the button in share views and switches aria-label with the global language", async () => {
    vi.stubGlobal("fetch", stubStarsResponse(8));
    renderShell({ title: null });

    expect(screen.getAllByRole("link", { name: "打开 GitHub 仓库" })).toHaveLength(2);
    const user = userEvent.setup();
    await user.click(screen.getAllByRole("button", { name: "EN" })[0]);
    expect(screen.getAllByRole("link", { name: "Open GitHub repository" })).toHaveLength(2);
  });
});
