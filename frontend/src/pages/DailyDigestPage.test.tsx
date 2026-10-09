import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import { DailyDigestPage } from "./DailyDigestPage";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { resetPageTitleForTests } from "@/hooks/usePageTitle";
import { dailyDigestRssUrl, fetchDailyDigest, fetchDailyDigestList } from "@/services/newsService";
import type { NewsDailyDigest, NewsDailyDigestActivity, NewsDailyDigestItem, NewsDailyDigestKeyDate, NewsDailyDigestSummary } from "@/types/news";

/**
 * 公开日报页（#241 报刊范式，原型 proto/238 转正）：
 * - 头版：头条放大+今日看点（2-4 名）+本期版面目录+统计条；9 类目固定版序、
 *   空版消失、每版 >8 溢出快讯 ≤12、头条不在版面内重复；
 * - 导航：桌面月分组 rail（首条标题两行预览=目录接口 firstTitle 字段）+
 *   移动日期条+本期目录抽屉+上一期/下一期（全量目录推导，最新期无 next）；
 * - 路由：/daily=最新一期（canonical=/daily）、/daily/:date 深链（canonical=自身）、
 *   key 不合式 404；日期切换=真实路由导航改写地址栏；
 * - 三态（#234 吸收）：休刊（rail 灰化+0 徽章+说明仅一次+查看热点引导）、
 *   未出刊、加载失败+页内重试；越界日期（存档外）与失败态可区分；
 * - 头条与目录 firstTitle 同源一致；document.title 按期（骑 #231 壳 title 机制）；
 *   透明口径 chips；匿名渲染零 /auth、零引擎探测、零 LLM 端点。
 */

vi.mock("@/services/newsService", () => ({
  fetchDailyDigestList: vi.fn(),
  fetchDailyDigest: vi.fn(),
  dailyDigestRssUrl: vi.fn((date: string) => `/public/news/daily/${date}/rss`),
  dailyIssuesFeedUrl: vi.fn(() => "/daily/feed.xml"),
  DAILY_MISSING_MESSAGE: "日报不存在"
}));

/** 目录（日期倒序）：最新期 12 条（快讯溢出形态）、10-01 休刊（firstTitle=null） */
const SUMMARIES: NewsDailyDigestSummary[] = [
  { digestDate: "2026-10-03", itemCount: 13, introSource: "llm", buildTime: "2026-10-03T08:40:00+08:00", firstTitleZh: "研究突破甲", firstTitleEn: "Research A" },
  { digestDate: "2026-10-02", itemCount: 2, introSource: "fallback", buildTime: "2026-10-02T08:40:00+08:00", firstTitleZh: "研究突破乙", firstTitleEn: "Research B" },
  { digestDate: "2026-10-01", itemCount: 0, introSource: "empty", buildTime: "2026-10-01T08:40:00+08:00", firstTitleZh: null, firstTitleEn: null },
  { digestDate: "2026-09-30", itemCount: 1, introSource: "llm", buildTime: "2026-09-30T08:40:00+08:00", firstTitleZh: "校园动态丙", firstTitleEn: "Campus C" }
];

function item(id: number, seq: number, category: NewsDailyDigestItem["category"], title: string): NewsDailyDigestItem {
  return {
    itemId: id,
    seq,
    url: "https://www.polyu.edu.hk/a",
    titleZh: title,
    titleEn: `Title ${id}`,
    summaryZh: `摘要${id}`,
    summaryEn: `Summary ${id}`,
    category,
    topics: [],
    publishTime: `2026-10-02T${String(9 + (seq % 12)).padStart(2, "0")}:00:00+08:00`,
    source: {
      sourceKey: "official-media-release",
      platform: "official",
      official: true,
      displayName: "理大官网",
      displayNameEn: "PolyU official"
    }
  };
}

/**
 * 2026-10-03 详情：13 条=头条（research）+research 11+campus 1——
 * research 版面 8 件+快讯 3 条（>8 溢出），campus 版面 1 件。
 */
function digestFixture(overrides: Partial<NewsDailyDigest> = {}): NewsDailyDigest {
  const items: NewsDailyDigestItem[] = [item(11, 1, "research", "研究突破甲")];
  for (let i = 2; i <= 12; i++) {
    items.push(item(10 + i, i, "research", `科研条目${i}`));
  }
  items.push(item(30, 13, "campus", "校园活动乙"));
  return {
    digestDate: "2026-10-03",
    windowStart: "2026-10-02T08:00:00+08:00",
    windowEnd: "2026-10-03T08:00:00+08:00",
    introZh: "本期两件事值得留意。",
    introEn: "Two things worth noting today.",
    storedIntroSource: "llm",
    introDegraded: false,
    itemCount: 13,
    visibleCount: 13,
    disqualifiedCount: 0,
    items,
    buildTime: "2026-10-03T08:40:00+08:00",
    ...overrides
  };
}

function recessFixture(date: string): NewsDailyDigest {
  return {
    digestDate: date,
    windowStart: "2026-09-30T08:00:00+08:00",
    windowEnd: "2026-10-01T08:00:00+08:00",
    introZh: "",
    introEn: "",
    storedIntroSource: "empty",
    introDegraded: false,
    itemCount: 0,
    visibleCount: 0,
    disqualifiedCount: 0,
    items: [],
    buildTime: `${date}T08:40:00+08:00`
  };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}</div>;
}

/** 手动决断的 promise：把 fetch 挂起/放行，驱动乱序竞态与等待期断言 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function railLink(date: string) {
  const link = screen.getAllByRole("link").find((a) => a.getAttribute("href") === `/daily/${date}`);
  expect(link).toBeTruthy();
  return link!;
}

/** 主栏头条标题集（h4>a；rail 目录预览是 p 标签，不在此列） */
const mainHeadlines = () => Array.from(document.querySelectorAll("h4 a")).map((a) => a.textContent ?? "");

function renderPage(initial = "/daily") {
  return render(
    <MemoryRouter initialEntries={[initial]}>
      <FeedLangProvider>
        <LocationProbe />
        <Routes>
          <Route path="/daily" element={<DailyDigestPage />} />
          <Route path="/daily/:date" element={<DailyDigestPage />} />
          <Route path="*" element={<div>route-not-found</div>} />
        </Routes>
      </FeedLangProvider>
    </MemoryRouter>
  );
}

function canonicalHref(): string | null {
  return document.querySelector("link[rel='canonical']")?.getAttribute("href") ?? null;
}

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

describe("DailyDigestPage", () => {
  beforeEach(() => {
    vi.mocked(fetchDailyDigestList).mockReset();
    vi.mocked(fetchDailyDigest).mockReset();
    vi.mocked(dailyDigestRssUrl).mockClear();
    resetPageTitleForTests();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renders the latest issue front page: headline, highlights, sections, flash overflow and stats", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    // 头条放大档：头条徽标+来源徽标（每卡一枚，取多枚）+摘要
    expect(screen.getByText("头条")).toBeTruthy();
    expect(screen.getAllByText("理大官网").length).toBeGreaterThan(0);
    // 今日看点=排序 2-4 名；看点条目同时留在版面内（仅头条不重复）→ 条目3 双现
    expect(screen.getByText("今日看点")).toBeTruthy();
    expect(screen.getAllByText("科研条目3").length).toBe(2);
    // 统计条（全客户端推导）：13 条动态（rail 徽标同为 13）
    expect(screen.getAllByText("13").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("条动态")).toBeTruthy();
    expect(screen.getByText("分钟读完")).toBeTruthy();
    // 版面：research 8 件（>8 溢出，分节头+目录行双现）、campus 1 件；快讯承接溢出 3 条
    expect(screen.getAllByText("8 件").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("1 件").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("3 条 · 版面溢出")).toBeTruthy();
    // 头条不在版面内重复：全页「研究突破甲」只出现在头条卡与 rail 预览（目录 firstTitle 同源）
    expect(screen.getAllByText("研究突破甲").length).toBe(2);
    expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-03");
  });

  it("#292 single-item issue hides the empty SECTIONS block (lead only, no sections/flash)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({
        digestDate: "2026-09-30",
        items: [item(51, 1, "research", "唯一条目甲")],
        itemCount: 1,
        visibleCount: 1
      })
    );
    renderPage("/daily/2026-09-30");

    await waitFor(() => expect(screen.getAllByText("唯一条目甲").length).toBeGreaterThan(0), { timeout: 5000 });
    // 单条期：版面行（sections+快讯）为空——「本期版面」标题与目录整块消失（空版消失）
    expect(screen.queryByText("本期版面")).toBeNull();
    expect(screen.queryByRole("navigation", { name: "本期版面目录" })).toBeNull();
    // 看点区独立于版面区：标题仍在，条目不足三条走回退文案
    expect(screen.getByText("今日看点")).toBeTruthy();
    expect(screen.getByText("本期看点不足三条")).toBeTruthy();
  });

  it("drives document.title per issue through the FeedShell title mechanism and sets canonical=/daily", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    expect(document.title).toBe("理大资讯日报 · 2026-10-03 · PolyUGuide");
    expect(canonicalHref()?.endsWith("/daily")).toBe(true);
  });

  it("renders the deep-linked issue with canonical pointing to itself", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({
        digestDate: "2026-10-02",
        items: [item(41, 1, "research", "研究突破乙"), item(42, 2, "campus", "校园活动丁")],
        itemCount: 2,
        visibleCount: 2
      })
    );
    renderPage("/daily/2026-10-02");

    // 深链期头条（rail 预览同文本出现两次：头条卡+目录 firstTitle 预览）
    await waitFor(() => expect(screen.getAllByText("研究突破乙").length).toBeGreaterThan(0), { timeout: 5000 });
    expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-02");
    expect(document.title).toBe("理大资讯日报 · 2026-10-02 · PolyUGuide");
    expect(canonicalHref()?.endsWith("/daily/2026-10-02")).toBe(true);
    // 深链期不是最新一期：不出现「最新一期」徽标，翻期格有 next
    expect(screen.queryByText("最新一期")).toBeNull();
    expect(screen.getByText("下一期")).toBeTruthy();
  });

  it("renders 404 for a malformed date key without fetching", () => {
    renderPage("/daily/1000");
    expect(screen.getByText("页面不存在")).toBeTruthy();
    expect(screen.queryByText("理大资讯日报")).toBeNull();
    expect(fetchDailyDigestList).not.toHaveBeenCalled();
    expect(canonicalHref()).toBeNull();
  });

  it("distinguishes an out-of-archive date from a load failure", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockRejectedValue(new Error("日报不存在"));
    renderPage("/daily/2026-08-01");

    await waitFor(() => expect(screen.getByText("该日期暂无刊期存档")).toBeTruthy(), { timeout: 5000 });
    expect(screen.getByText(/线上存档自 2026-09-30 起/)).toBeTruthy();
    // 与失败态可区分：不出现失败文案/重试钮
    expect(screen.queryByText("日报加载失败")).toBeNull();
    expect(screen.queryByText("重试")).toBeNull();
  });

  it("recovers from a catalog load failure via in-page retry (no full reload)", async () => {
    vi.mocked(fetchDailyDigestList).mockRejectedValueOnce(new Error("boom")).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    // #272：目录失败与详情失败分开表达——目录失败出目录专属文案
    await waitFor(() => expect(screen.getByText("日报目录加载失败")).toBeTruthy(), { timeout: 5000 });
    expect(screen.getByText("重试")).toBeTruthy();
    expect(fetchDailyDigestList).toHaveBeenCalledTimes(1);

    // 页内重试=状态复位重取（同一挂载内 refetch，非整页刷新）
    fireEvent.click(screen.getByText("重试"));
    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    expect(fetchDailyDigestList).toHaveBeenCalledTimes(2);
  });

  it("renders the recess state: 0 badge rail, explanation exactly once, hot-rank guide", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(recessFixture("2026-10-01"));
    renderPage("/daily/2026-10-01");

    await waitFor(() => expect(screen.getByText("本日休刊")).toBeTruthy(), { timeout: 5000 });
    // 报头「休刊」徽标+说明全页仅一次（rail/翻期格只留短标签）；
    // 月历图例的「休刊」灰点说明另计（#242 rail 档常驻）
    expect(screen.getAllByText("休刊").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText(/不是生成故障/).length).toBe(1);
    // 「查看热点」引导跳热点榜+回到最新一期
    expect(screen.getByText("查看热点 →").closest("a")?.getAttribute("href")).toBe("/hot");
    expect(screen.getByText("查看最新一期 →").closest("a")?.getAttribute("href")).toBe("/daily");
    // rail：休刊行 0 徽章+firstTitle 空期回落文案
    expect(screen.getByText("本日休刊，窗口内无公开发布")).toBeTruthy();
    // 翻期格：上一期/下一期均从目录推导（firstTitle 预览，rail 内同文本再现）
    expect(screen.getAllByText("研究突破乙").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("校园动态丙").length).toBeGreaterThanOrEqual(2);
  });

  it("shows not-yet-generated state when the catalog is empty", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText("日报尚未生成（每日 08:40 HKT 出刊）")).toBeTruthy(), { timeout: 5000 });
    expect(fetchDailyDigest).not.toHaveBeenCalled();
  });

  it("navigates to the real /daily/:date route when a rail date is picked", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockImplementation(async (date: string) =>
      digestFixture({
        digestDate: date,
        items: [item(51, 1, "research", "研究突破乙"), item(52, 2, "campus", "校园活动丁")],
        itemCount: 2,
        visibleCount: 2
      })
    );
    renderPage();

    await waitFor(() => expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-03"), { timeout: 5000 });
    const probe = screen.getByTestId("location-probe");
    expect(probe.textContent).toBe("/daily");

    // rail 里 10-02 那行（首条标题两行预览=目录 firstTitle 字段）
    const link = screen.getAllByRole("link").find((a) => a.getAttribute("href") === "/daily/2026-10-02");
    expect(link).toBeTruthy();
    fireEvent.click(link!);
    await waitFor(() => expect(probe.textContent).toBe("/daily/2026-10-02"), { timeout: 5000 });
    await waitFor(() => expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-02"), { timeout: 5000 });
  });

  it("opens the contents drawer with issue TOC and prev/next entries", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    fireEvent.click(screen.getByRole("button", { name: /本期目录/ }));
    expect(screen.getByText("本期目录 · 2026-10-03")).toBeTruthy();
    // 抽屉目录行=版面锚点（科研版+快讯版均入目录）
    expect(document.querySelectorAll('a[href="#sec-research"]').length).toBeGreaterThan(0);
    expect(document.querySelectorAll('a[href="#sec-flash"]').length).toBeGreaterThan(0);
    // 抽屉内上一期/下一期（最新期无 next → 占位；正文翻期格同名并存）
    expect(screen.getAllByText("上一期").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("已是最新一期")).toBeTruthy();

    fireEvent.keyDown(window, { key: "Escape" });
    await waitFor(() => expect(screen.queryByText("本期目录 · 2026-10-03")).toBeNull(), { timeout: 5000 });
  });

  it("mounts the issue calendar in the desktop rail and the contents drawer (#242)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    // rail 顶=月历（紧凑档），其下才是「往期 · N 期」清单（N=摘要实际期数）
    const railCalendar = screen.getByLabelText("报眼月历");
    expect(railCalendar.parentElement?.nextElementSibling?.textContent).toContain("往期 · 4 期");
    // 最新期所在月（2026-10）=存档上限月：下一月禁用、上一月可用（限存档范围）
    expect(within(railCalendar).getByLabelText("下一月")).toHaveProperty("disabled", true);
    expect(within(railCalendar).getByLabelText("上一月")).toHaveProperty("disabled", false);
    // 本期日格=红底（10-03）+休刊灰点（10-01）
    const states = Array.from(railCalendar.querySelectorAll<HTMLElement>("[data-state]"));
    expect(states.find((el) => el.textContent === "3")?.getAttribute("data-state")).toBe("current");
    expect(states.find((el) => el.textContent === "1")?.getAttribute("data-state")).toBe("recess");

    // 目录抽屉内=第二个月历（spacious 档：日格 ≥44px 触控目标）
    fireEvent.click(screen.getByRole("button", { name: /本期目录/ }));
    const calendars = screen.getAllByLabelText("报眼月历");
    expect(calendars.length).toBe(2);
    const drawerStates = Array.from(calendars[1].querySelectorAll<HTMLElement>("[data-state]"));
    expect(drawerStates.find((el) => el.textContent === "1")?.className).toContain("min-h-[44px]");
  });

  it("keeps transparent chips for template intro and degraded issues", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({
        introZh: "本期日报覆盖 2026-10-02 至 2026-10-03 的公开动态，共 12 条，以下按发布时间倒序排列。",
        storedIntroSource: "fallback",
        introDegraded: true,
        disqualifiedCount: 1
      })
    );
    renderPage();

    await waitFor(() => expect(screen.getByText("部分内容已下架")).toBeTruthy(), { timeout: 5000 });
    expect(screen.getByText("模板导语")).toBeTruthy();
    expect(screen.getByText(/共 12 条，以下按发布时间倒序排列/)).toBeTruthy();
    expect(screen.queryByText("本期两件事值得留意。")).toBeNull();
  });

  it("exposes the issue RSS link for the selected date", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    const rss = screen.getByText("本期 RSS ↗").closest("a");
    expect(rss?.getAttribute("href")).toBe("/public/news/daily/2026-10-03/rss");
  });

  it("exposes the issues feed subscribe link (#243 刊尾订阅出口默认口径)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    const sub = screen.getByText("订阅日报 ↗").closest("a");
    expect(sub?.getAttribute("href")).toBe("/daily/feed.xml");
    // 双出口并存（本期 RSS 不被取代）
    expect(screen.getByText("本期 RSS ↗")).toBeTruthy();
  });

  it("mounts the issues feed autodiscovery link in head and removes it on unmount (#243)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    const view = renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    const links = Array.from(
      document.head.querySelectorAll<HTMLLinkElement>('link[rel="alternate"]')
    );
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute("type")).toBe("application/rss+xml");
    expect(links[0].getAttribute("href")).toBe("http://localhost:3000/daily/feed.xml");
    expect(links[0].getAttribute("title")).toContain("理大资讯日报");

    view.unmount();
    expect(Array.from(document.head.querySelectorAll('link[rel="alternate"]'))).toEqual([]);
  });

  it("issues only public digest requests while rendering (no auth, no engine probe, no LLM endpoints)", async () => {
    const { requestedUrls } = instrumentNetwork();
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    // 页面数据全走 service mock（USE_MOCK 分支），XHR 只可能出现于组件内部的
    // 引擎/会话探测——断言这些探测一个都没发生（公开页红线：零 /auth、零 /rag、零 LLM）
    const offender = requestedUrls.find(
      (url) => url.includes("/auth") || url.includes("/rag/settings") || url.includes("/chat") || url.includes("mcp")
    );
    expect(offender).toBeUndefined();
  });

  it("fetches the archive-wide summary list and renders it in full, with no 30-issue display cap (#267 后续)", async () => {    // 40 期夹具（10-03 起回溯 39 天，跨 10/09/08 三个月）：月历/前后期导航/存档
    // 边界与 rail 往期清单、移动日期条全部吃全量（30 期展示截断已解除）
    const archive: NewsDailyDigestSummary[] = Array.from({ length: 40 }, (_, i) => {
      const ds = new Date(Date.UTC(2026, 9, 3 - i)).toISOString().slice(0, 10);
      return {
        digestDate: ds,
        itemCount: 1,
        introSource: "fallback",
        buildTime: `${ds}T08:40:00+08:00`,
        firstTitleZh: `存档${40 - i}`,
        firstTitleEn: `Archive ${40 - i}`
      };
    });
    vi.mocked(fetchDailyDigestList).mockResolvedValue(archive);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    // 拉取走存档上限（后端 MAX_LIST_LIMIT=400），不再默认 30 截断存档
    expect(vi.mocked(fetchDailyDigestList)).toHaveBeenCalledWith(400);
    // rail 清单不截断：最早期（第 40 期，存档1）也在清单里，标签如实显示总期数
    expect(screen.getByText("存档1")).toBeTruthy();
    expect(screen.getByText("往期 · 40 期")).toBeTruthy();
    // 月历边界由全量摘要派生：最早期落在 8 月 → 「上一月」可翻（10 月起步）
    expect(screen.getByRole("button", { name: "上一月" }).hasAttribute("disabled")).toBe(false);
  });

  // ─────────────── #316 校历关键日期栏目（L1：纯数据零 LLM，空刊降级保底） ───────────────

  function keyDateRow(overrides: Partial<NewsDailyDigestKeyDate> = {}): NewsDailyDigestKeyDate {
    return {
      seq: 1,
      uid: "uid-kd-1",
      titleZh: "期末考试周",
      titleEn: "Final examination period",
      audienceText: null,
      precision: "exact-range",
      dateStart: "2026-09-28",
      dateEnd: "2026-10-06",
      fuzzyHint: null,
      ongoing: true,
      daysUntil: -5,
      ...overrides
    };
  }

  it("#316 renders the academic key-dates band with ongoing badge, countdown and view-all link", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({
        keyDates: [
          keyDateRow(),
          keyDateRow({
            seq: 2,
            uid: "uid-kd-2",
            titleZh: null,
            titleEn: "Add/drop deadline",
            precision: "exact-day",
            dateStart: "2026-10-12",
            dateEnd: null,
            ongoing: false,
            daysUntil: 9
          }),
          keyDateRow({
            seq: 3,
            uid: "uid-kd-3",
            titleZh: "退宿申请开始",
            titleEn: "Hostel checkout application opens",
            audienceText: "住宿生",
            precision: "onwards",
            dateStart: "2026-10-05",
            dateEnd: null,
            ongoing: false,
            daysUntil: null
          })
        ]
      })
    );
    renderPage();

    await waitFor(() => expect(screen.getByText("校历关键日期")).toBeTruthy(), { timeout: 5000 });
    // 进行中徽章（已开始未结束区间）+倒计时徽章；onwards 不伪造截止（无徽章）
    expect(screen.getByText("进行中")).toBeTruthy();
    expect(screen.getByText("9 天后")).toBeTruthy();
    // 词表缺词 zh=null 回退英文；人群限制原文不省略
    expect(screen.getByText("Add/drop deadline")).toBeTruthy();
    expect(screen.getByText(/住宿生/)).toBeTruthy();
    // 日期文案复用 keyDateService（区间/单日/开放起点三形态）
    expect(screen.getByText("9月28日 – 10月6日")).toBeTruthy();
    expect(screen.getByText("10月12日 周一")).toBeTruthy();
    expect(screen.getByText("10月5日 起")).toBeTruthy();
    // 头部入口进 /key-dates 独立页
    expect(screen.getByText("查看全部 ›").closest("a")?.getAttribute("href")).toBe("/key-dates");
    // 条目计数
    expect(screen.getByText("3 项")).toBeTruthy();
  });

  it("#316 hides the key-dates band entirely when the window has no entries", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    expect(screen.queryByLabelText("校历关键日期")).toBeNull();
    expect(screen.queryByText("查看全部 ›")).toBeNull();
  });

  it("#316 keeps the key-dates band on a recess issue as the degraded floor layout", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue({
      ...recessFixture("2026-10-01"),
      keyDates: [keyDateRow({ seq: 1 })]
    });
    renderPage("/daily/2026-10-01");

    await waitFor(() => expect(screen.getByText("本日休刊")).toBeTruthy(), { timeout: 5000 });
    // 空刊降级版式：资讯零条仍出 L1 栏目（保底内容）+休刊说明并存
    expect(screen.getByLabelText("校历关键日期")).toBeTruthy();
    expect(screen.getByText("期末考试周")).toBeTruthy();
    expect(screen.getByText("进行中")).toBeTruthy();
    expect(screen.getByText(/不是生成故障/)).toBeTruthy();
  });

  it("#316 renders the key-dates band bilingually under the English UI language", async () => {
    window.localStorage.setItem("polyu.feed.lang", "en");
    try {
      vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
      vi.mocked(fetchDailyDigest).mockResolvedValue(
        digestFixture({
          keyDates: [
            keyDateRow({ seq: 1, titleZh: null }),
            keyDateRow({
              seq: 2,
              uid: "uid-kd-2",
              titleZh: "增退选截止",
              titleEn: "Add/drop deadline",
              precision: "exact-day",
              dateStart: "2026-10-12",
              dateEnd: null,
              ongoing: false,
              daysUntil: 9
            })
          ]
        })
      );
      renderPage();

      await waitFor(() => expect(screen.getByText("Academic key dates")).toBeTruthy(), { timeout: 5000 });
      // 英文优先、中文缺位回退；徽章/日期文案同源英文
      expect(screen.getByText("Final examination period")).toBeTruthy();
      expect(screen.getByText("Add/drop deadline")).toBeTruthy();
      expect(screen.queryByText("增退选截止")).toBeNull();
      expect(screen.getByText("Ongoing")).toBeTruthy();
      expect(screen.getByText("in 9 days")).toBeTruthy();
      expect(screen.getByText("28 Sep – 6 Oct")).toBeTruthy();
      expect(screen.getByText("Mon 12 Oct")).toBeTruthy();
    } finally {
      window.localStorage.removeItem("polyu.feed.lang");
    }
  });

  // ─────────────── #330 校园活动版面（L2：进行中/即将来临两组，空刊降级保底） ───────────────

  function activityRow(overrides: Partial<NewsDailyDigestActivity> = {}): NewsDailyDigestActivity {
    return {
      seq: 1,
      itemId: 201,
      titleZh: "第32届毕业典礼",
      titleEn: "32nd Congregation",
      url: "https://www.polyu.edu.hk/en/events/congregation",
      dateStart: "2026-10-01",
      dateEnd: "2026-11-21",
      ongoing: true,
      ...overrides
    };
  }

  it("#330 renders the activities band grouped ongoing/upcoming, ordered by snapshot seq, cards linking out", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    // 数组故意乱序（seq 3 在前）——客户端按快照序（date_start 升序）兜底排序
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({
        activities: [
          activityRow({
            seq: 3,
            itemId: 203,
            titleZh: "艺墟",
            titleEn: "Art Fair",
            dateStart: "2026-10-18",
            dateEnd: "2026-10-20",
            ongoing: false
          }),
          activityRow({ seq: 1, itemId: 201, ongoing: true }),
          activityRow({
            seq: 2,
            itemId: 202,
            titleZh: null,
            titleEn: "Information Day 2026",
            dateStart: "2026-10-10",
            dateEnd: "2026-10-10",
            ongoing: false
          })
        ]
      })
    );
    renderPage();

    await waitFor(() => expect(screen.getByLabelText("校园活动")).toBeTruthy(), { timeout: 5000 });
    // 两组分组（ongoing 生成期冻结直映）：进行中（跨区间）+即将来临，组计数如实
    expect(screen.getByText("进行中")).toBeTruthy();
    expect(screen.getByText("即将来临")).toBeTruthy();
    expect(screen.getByText("1 项")).toBeTruthy();
    expect(screen.getByText("2 项")).toBeTruthy();
    expect(screen.getByText("3 项")).toBeTruthy();
    // 词表缺词 zh=null 回退英文标题
    expect(screen.getByText("Information Day 2026")).toBeTruthy();
    // 日期文案：区间+单日两形态（HKT 历日快照直映）
    expect(screen.getByText("10月1日 – 11月21日")).toBeTruthy();
    expect(screen.getByText("10月10日")).toBeTruthy();
    // 卡片外链语义：整卡 <a> 指详情页新窗打开
    const card = screen.getByText("第32届毕业典礼").closest("a");
    expect(card?.getAttribute("href")).toBe("https://www.polyu.edu.hk/en/events/congregation");
    expect(card?.getAttribute("target")).toBe("_blank");
    // 快照序（date_start 升序）决定 DOM 序：毕业典礼 → Info Day → 艺墟
    const first = screen.getByText("第32届毕业典礼");
    const second = screen.getByText("Information Day 2026");
    const third = screen.getByText("艺墟");
    expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(second.compareDocumentPosition(third) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("#330 hides the activities band entirely when the window has no activities", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    expect(screen.queryByLabelText("校园活动")).toBeNull();
    expect(screen.queryByText("即将来临")).toBeNull();
  });

  it("#330 keeps the activities band on a recess issue as the degraded floor layout", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue({
      ...recessFixture("2026-10-01"),
      activities: [activityRow()]
    });
    renderPage("/daily/2026-10-01");

    await waitFor(() => expect(screen.getByText("本日休刊")).toBeTruthy(), { timeout: 5000 });
    // 空刊降级版式：资讯零条仍出 L2 版面（保底内容）+休刊说明并存
    expect(screen.getByLabelText("校园活动")).toBeTruthy();
    expect(screen.getByText("第32届毕业典礼")).toBeTruthy();
    expect(screen.getByText(/不是生成故障/)).toBeTruthy();
  });

  it("#330 renders the activities band bilingually under the English UI language", async () => {
    window.localStorage.setItem("polyu.feed.lang", "en");
    try {
      vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
      vi.mocked(fetchDailyDigest).mockResolvedValue(
        digestFixture({
          activities: [
            activityRow(),
            activityRow({
              seq: 2,
              itemId: 202,
              titleEn: null,
              titleZh: "艺墟2026",
              dateStart: "2026-10-10",
              dateEnd: "2026-10-10",
              ongoing: false
            })
          ]
        })
      );
      renderPage();

      await waitFor(() => expect(screen.getByLabelText("Campus activities")).toBeTruthy(), { timeout: 5000 });
      // 组标题/日期文案同源英文
      expect(screen.getByText("Ongoing")).toBeTruthy();
      expect(screen.getByText("Upcoming")).toBeTruthy();
      expect(screen.getByText("1 Oct – 21 Nov")).toBeTruthy();
      expect(screen.getByText("10 Oct")).toBeTruthy();
      // 英文优先、英文缺位回退中文
      expect(screen.getByText("32nd Congregation")).toBeTruthy();
      expect(screen.getByText("艺墟2026")).toBeTruthy();
    } finally {
      window.localStorage.removeItem("polyu.feed.lang");
    }
  });

  // ─────────────── #272 加载反馈与竞态 ───────────────

  it("shows the catalog-stage skeleton on first entry while the list is pending (#272)", async () => {
    const list = deferred<NewsDailyDigestSummary[]>();
    vi.mocked(fetchDailyDigestList).mockReturnValue(list.promise);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    // 等待期可见（旧实现此分支是空白）：可读加载状态+主区 aria-busy
    const status = screen.getByRole("status");
    expect(status.getAttribute("aria-busy")).toBe("true");
    expect(screen.getByText("正在加载日报，请稍候")).toBeTruthy();
    // 装饰骨架摘出无障碍树；脉动全部走 motion-safe（reduced-motion 下静止）
    const decorative = status.querySelector('[aria-hidden="true"]');
    expect(decorative).toBeTruthy();
    expect(decorative!.querySelectorAll(".motion-safe\\:animate-pulse").length).toBeGreaterThan(0);
    // 数据未到：不渲染任何期内容（报头 h1 属于成功态）
    expect(screen.queryByText("理大资讯日报")).toBeNull();

    list.resolve(SUMMARIES);
    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    // 成功响应后占位消失
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("keeps the rail interactive behind the detail-stage skeleton on a deep link (#272)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    const detail = deferred<NewsDailyDigest>();
    vi.mocked(fetchDailyDigest).mockReturnValue(detail.promise);
    renderPage("/daily/2026-10-02");

    await waitFor(() => expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-02"), { timeout: 5000 });
    // 目录已可用：rail 可操作（可点回 10-03），主栏在等详情
    expect(screen.getByLabelText("往期日报")).toBeTruthy();
    expect(railLink("2026-10-03")).toBeTruthy();
    expect(screen.getByRole("status").getAttribute("aria-busy")).toBe("true");

    detail.resolve(
      digestFixture({
        digestDate: "2026-10-02",
        items: [item(41, 1, "research", "研究突破乙"), item(42, 2, "campus", "校园活动丁")],
        itemCount: 2,
        visibleCount: 2
      })
    );
    await waitFor(() => expect(screen.getAllByText("研究突破乙").length).toBeGreaterThan(0), { timeout: 5000 });
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("never paints the previous issue while switching — skeleton only, then the new issue (#272)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    const slow = deferred<NewsDailyDigest>();
    vi.mocked(fetchDailyDigest)
      .mockResolvedValueOnce(digestFixture())
      .mockReturnValueOnce(slow.promise);
    renderPage();
    await waitFor(() => expect(mainHeadlines()).toContain("研究突破甲"), { timeout: 5000 });

    fireEvent.click(railLink("2026-10-02"));
    // 切期帧：主栏骨架；旧刊头条不进主栏（rail 目录预览属目录数据，不受切期影响）
    const status = await screen.findByRole("status");
    const mainCol = status.parentElement!;
    expect(within(mainCol).queryByText("研究突破甲")).toBeNull();
    expect(mainHeadlines()).toEqual([]);

    slow.resolve(
      digestFixture({
        digestDate: "2026-10-02",
        items: [item(41, 1, "research", "研究突破乙")],
        itemCount: 1,
        visibleCount: 1
      })
    );
    await waitFor(() => expect(mainHeadlines()).toContain("研究突破乙"), { timeout: 5000 });
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("drops a late success from the previous selection after a quick switch back (#272)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    const stale = deferred<NewsDailyDigest>();
    vi.mocked(fetchDailyDigest)
      .mockResolvedValueOnce(digestFixture())
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(digestFixture());
    renderPage();
    await waitFor(() => expect(mainHeadlines()).toContain("研究突破甲"), { timeout: 5000 });

    // 快连点：切到 10-02（挂起）再立刻切回 10-03（快成功）
    fireEvent.click(railLink("2026-10-02"));
    await screen.findByRole("status");
    fireEvent.click(railLink("2026-10-03"));
    await waitFor(() => expect(mainHeadlines()).toContain("研究突破甲"), { timeout: 5000 });

    // 旧选择的成功晚到：被丢弃，主栏不覆盖、不再闪骨架
    stale.resolve(
      digestFixture({
        digestDate: "2026-10-02",
        items: [item(41, 1, "research", "研究突破乙")],
        itemCount: 1,
        visibleCount: 1
      })
    );
    await waitFor(() => expect(fetchDailyDigest).toHaveBeenCalledTimes(3), { timeout: 5000 });
    expect(mainHeadlines()).toContain("研究突破甲");
    expect(mainHeadlines()).not.toContain("研究突破乙");
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("drops a late failure from the previous selection (#272)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    const stale = deferred<NewsDailyDigest>();
    vi.mocked(fetchDailyDigest)
      .mockResolvedValueOnce(digestFixture())
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(digestFixture());
    renderPage();
    await waitFor(() => expect(mainHeadlines()).toContain("研究突破甲"), { timeout: 5000 });

    fireEvent.click(railLink("2026-10-02"));
    await screen.findByRole("status");
    fireEvent.click(railLink("2026-10-03"));
    await waitFor(() => expect(mainHeadlines()).toContain("研究突破甲"), { timeout: 5000 });

    // 旧选择的失败晚到：不吞掉当前成功（无失败卡、无骨架）
    stale.reject(new Error("boom"));
    await waitFor(() => expect(fetchDailyDigest).toHaveBeenCalledTimes(3), { timeout: 5000 });
    expect(mainHeadlines()).toContain("研究突破甲");
    expect(screen.queryByText("日报加载失败")).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("keeps a catalog failure visible alongside a successful deep-linked issue (#272)", async () => {
    vi.mocked(fetchDailyDigestList).mockRejectedValue(new Error("boom"));
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({
        digestDate: "2026-10-02",
        items: [item(41, 1, "research", "研究突破乙")],
        itemCount: 1,
        visibleCount: 1
      })
    );
    renderPage("/daily/2026-10-02");

    // 深链详情成功呈现（目录失败不吞详情成功）
    await waitFor(() => expect(screen.getAllByText("研究突破乙").length).toBeGreaterThan(0), { timeout: 5000 });
    // 目录失败卡同屏保留（详情成功不抹掉目录失败），重试可及
    expect(screen.getByText("日报目录加载失败")).toBeTruthy();
    expect(screen.getByText("重试")).toBeTruthy();
  });

  it("closes the contents drawer on issue switch and does not reopen it on arrival (#272)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    const next = deferred<NewsDailyDigest>();
    vi.mocked(fetchDailyDigest).mockResolvedValueOnce(digestFixture()).mockReturnValueOnce(next.promise);
    renderPage();
    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });

    fireEvent.click(screen.getByRole("button", { name: /本期目录/ }));
    expect(screen.getByText("本期目录 · 2026-10-03")).toBeTruthy();

    // 切期：旧目录抽屉即刻关闭
    fireEvent.click(railLink("2026-10-02"));
    await waitFor(() => expect(screen.queryByText("本期目录 · 2026-10-03")).toBeNull(), { timeout: 5000 });

    next.resolve(
      digestFixture({
        digestDate: "2026-10-02",
        items: [item(41, 1, "research", "研究突破乙")],
        itemCount: 1,
        visibleCount: 1
      })
    );
    await waitFor(() => expect(screen.getAllByText("研究突破乙").length).toBeGreaterThan(0), { timeout: 5000 });
    // 新期到达后抽屉不被重新弹出
    expect(screen.queryByText(/本期目录 · 2026-10-02/)).toBeNull();
  });

  it("recovers from a detail failure via retry while the catalog stays usable (#272)", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockRejectedValueOnce(new Error("boom")).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("日报加载失败")).toBeTruthy(), { timeout: 5000 });
    // 详情失败不吞目录：rail 仍在，且与目录失败文案可区分
    expect(screen.queryByText("日报目录加载失败")).toBeNull();
    expect(screen.getByLabelText("往期日报")).toBeTruthy();

    fireEvent.click(screen.getByText("重试"));
    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy(), { timeout: 5000 });
    expect(screen.queryByRole("status")).toBeNull();
  });
});
