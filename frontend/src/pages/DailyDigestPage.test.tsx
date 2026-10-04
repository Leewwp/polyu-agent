import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { DailyDigestPage } from "./DailyDigestPage";
import { FeedLangProvider } from "@/components/feed/feedLang";
import { dailyDigestRssUrl, fetchDailyDigest, fetchDailyDigestList } from "@/services/newsService";
import type { NewsDailyDigest, NewsDailyDigestSummary } from "@/types/news";

/**
 * 公开日报页（#212）。
 * - 目录→最新刊详情渲染（刊头/生效导语/条目卡）；日期 chip 切换重取详情；
 * - 下架降级口径：introDegraded 时「部分内容已下架」注记+模板导语（无 LLM 导语残留）；
 * - 空刊/目录为空/加载失败三态；
 * - RSS 订阅外链指向 /public/news/daily/{date}/rss；
 * - 匿名渲染零 /auth、零引擎探测、零 LLM 端点（XHR spy：请求只落 /public/news/daily/**）。
 */

vi.mock("@/services/newsService", () => ({
  fetchDailyDigestList: vi.fn(),
  fetchDailyDigest: vi.fn(),
  dailyDigestRssUrl: vi.fn((date: string) => `/public/news/daily/${date}/rss`)
}));

const SUMMARIES: NewsDailyDigestSummary[] = [
  { digestDate: "2026-10-03", itemCount: 2, introSource: "llm", buildTime: "2026-10-03T08:40:00+08:00" },
  { digestDate: "2026-10-02", itemCount: 1, introSource: "fallback", buildTime: "2026-10-02T08:40:00+08:00" }
];

function digestFixture(overrides: Partial<NewsDailyDigest> = {}): NewsDailyDigest {
  return {
    digestDate: "2026-10-03",
    windowStart: "2026-10-02T08:00:00+08:00",
    windowEnd: "2026-10-03T08:00:00+08:00",
    introZh: "本期两件事值得留意。",
    introEn: "Two things worth noting today.",
    storedIntroSource: "llm",
    introDegraded: false,
    itemCount: 2,
    visibleCount: 2,
    disqualifiedCount: 0,
    items: [
      {
        itemId: 11,
        seq: 1,
        url: "https://www.polyu.edu.hk/a",
        titleZh: "研究突破甲",
        titleEn: "Research A",
        summaryZh: "摘要甲",
        summaryEn: "Summary A",
        category: "research",
        topics: ["ai"],
        publishTime: "2026-10-02T09:00:00+08:00",
        source: {
          sourceKey: "news-sitemap",
          platform: "official",
          official: true,
          displayName: "理大官网",
          displayNameEn: "PolyU official"
        }
      },
      {
        itemId: 12,
        seq: 2,
        url: "https://www.polyu.edu.hk/b",
        titleZh: "校园活动乙",
        titleEn: "Campus B",
        summaryZh: "摘要乙",
        summaryEn: "Summary B",
        category: "campus",
        topics: [],
        publishTime: "2026-10-02T10:00:00+08:00",
        source: null
      }
    ],
    buildTime: "2026-10-03T08:40:00+08:00",
    ...overrides
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/daily"]}>
      <FeedLangProvider>
        <DailyDigestPage />
      </FeedLangProvider>
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

describe("DailyDigestPage", () => {
  beforeEach(() => {
    vi.mocked(fetchDailyDigestList).mockReset();
    vi.mocked(fetchDailyDigest).mockReset();
    vi.mocked(dailyDigestRssUrl).mockClear();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("renders latest digest head, intro and snapshot item cards", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();

    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy());
    expect(screen.getByText(/理大资讯日报 · 2026-10-03/)).toBeTruthy();
    expect(screen.getByText("本期两件事值得留意。")).toBeTruthy();
    expect(screen.getByText("校园活动乙")).toBeTruthy();
    // 刊头只请求目录+详情，默认选中最新一期
    expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-03");
  });

  it("switches to another date chip and refetches the detail", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockImplementation(async (date: string) =>
      digestFixture({ digestDate: date, items: [], visibleCount: 0, itemCount: date === "2026-10-02" ? 1 : 0 })
    );
    renderPage();
    await waitFor(() => expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-03"));

    fireEvent.click(screen.getByRole("button", { name: "10-02" }));
    await waitFor(() => expect(fetchDailyDigest).toHaveBeenCalledWith("2026-10-02"));
  });

  it("marks degraded digest with removal note and drops LLM intro residue", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({
        introZh: "本期日报覆盖 2026-10-02 至 2026-10-03 的公开动态，共 1 条，以下按发布时间倒序排列。",
        introDegraded: true,
        disqualifiedCount: 1,
        visibleCount: 1,
        items: [digestFixture().items[0]]
      })
    );
    renderPage();
    await waitFor(() => expect(screen.getByText("部分内容已下架")).toBeTruthy());
    expect(screen.getByText(/共 1 条，以下按发布时间倒序排列/)).toBeTruthy();
    expect(screen.queryByText("本期两件事值得留意。")).toBeNull();
  });

  it("shows empty-digest state when the digest has no visible items", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(
      digestFixture({ introZh: "本期日报（2026-10-03 覆盖窗口）内暂无公开动态。", items: [], visibleCount: 0 })
    );
    renderPage();
    await waitFor(() => expect(screen.getByText("本期窗口内暂无公开动态")).toBeTruthy());
  });

  it("shows not-yet-generated state when the catalog is empty", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText("日报尚未生成（每日 08:40 HKT 出刊）")).toBeTruthy());
    expect(fetchDailyDigest).not.toHaveBeenCalled();
  });

  it("shows error state when loading fails", async () => {
    vi.mocked(fetchDailyDigestList).mockRejectedValue(new Error("boom"));
    renderPage();
    await waitFor(() => expect(screen.getByText("日报加载失败，请稍后刷新重试")).toBeTruthy());
  });

  it("exposes the RSS subscribe link for the selected date", async () => {
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();
    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy());
    const rss = screen.getByText("RSS ↗").closest("a");
    expect(rss?.getAttribute("href")).toBe("/public/news/daily/2026-10-03/rss");
  });

  it("issues only public digest requests while rendering (no auth, no engine probe, no LLM endpoints)", async () => {
    const { requestedUrls } = instrumentNetwork();
    vi.mocked(fetchDailyDigestList).mockResolvedValue(SUMMARIES);
    vi.mocked(fetchDailyDigest).mockResolvedValue(digestFixture());
    renderPage();
    await waitFor(() => expect(screen.getByText("研究突破甲")).toBeTruthy());
    // 页面数据全走 service mock（USE_MOCK 分支），XHR 只可能出现于组件内部的
    // 引擎/会话探测——断言这些探测一个都没发生（公开页红线：零 /auth、零 /rag、零 LLM）
    const offender = requestedUrls.find(
      (url) => url.includes("/auth") || url.includes("/rag/settings") || url.includes("/chat") || url.includes("mcp")
    );
    expect(offender).toBeUndefined();
  });
});
