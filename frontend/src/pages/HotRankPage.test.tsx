import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { HotRankPage } from "./HotRankPage";
import { fetchHotRank } from "@/services/newsService";
import { MOCK_HOT_RANK } from "@/services/newsMockData";

/**
 * 公开热点榜页（原型 #viewHot）。
 * - 排行渲染 mock 热度降序（排名 1..10，前 3 红名次=class 含 polyu-red）；
 * - 爆/新/发酵中标签文案（原型 TAG_ZH）+「N 源」信源徽章 → 悬停/点击弹信源名单
 *   （jsdom 断言气泡开合用 fireEvent，userEvent+假钟勿用经验）；
 * - 「榜单说明」两段方法论注脚逐字（与后端算法一致口径）；
 * - 匿名渲染零 /auth 请求、零 /rag/settings 引擎探测（XHR spy 经验）；
 * - 双标题收敛——页内无「热点榜」heading，顶栏标题唯一承载；
 *   浮层 mouseleave 延迟关+移入本体不关+页内单例（多行悬停零残留）。
 */

vi.mock("@/services/newsService", () => ({
  fetchHotRank: vi.fn()
}));

function instrumentNetwork(): { requestedUrls: string[] } {
  const requestedUrls: string[] = [];
  vi.spyOn(XMLHttpRequest.prototype, "open").mockImplementation((...args: Parameters<XMLHttpRequest["open"]>) => {
    requestedUrls.push(String(args[1]));
  });
  return { requestedUrls };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/hot"]}>
      <HotRankPage />
    </MemoryRouter>
  );
}

describe("HotRankPage", () => {
  beforeEach(() => {
    vi.mocked(fetchHotRank).mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    // 延迟关闭用例切过假钟，还原真实定时器防泄漏到后续用例（waitFor 依赖真实时钟）
    vi.useRealTimers();
  });

  it("renders ranked entries in mock heat order with red badges for top 3", async () => {
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    // 数据锚点等待（异步数据页同步 query 须 waitFor）
    await waitFor(() => expect(screen.getByText(MOCK_HOT_RANK[0].titleZh)).toBeTruthy());

    const badges = screen.getAllByRole("listitem").slice(0, 10);
    expect(badges.length).toBe(MOCK_HOT_RANK.length);
    // 前三名次徽章红、第 4 名非红
    expect(badges[0].innerHTML).toContain("polyu-red");
    expect(badges[2].innerHTML).toContain("polyu-red");
    expect(badges[3].innerHTML).not.toContain("bg-[var(--polyu-red)]");
    // 🔥 热度右对齐（存在热度值即可，位置由 flex 类承载）
    expect(screen.getByText(`🔥 ${MOCK_HOT_RANK[0].heat}`)).toBeTruthy();
  });

  it("renders boom/fresh/rise tag labels and source count badges", async () => {
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    await waitFor(() => expect(screen.getByText("爆")).toBeTruthy());
    // 多条目带 rise 标签（mock 中「发酵中」出现 2 次）
    expect(screen.getAllByText("发酵中").length).toBeGreaterThanOrEqual(1);
    // 「N 源」徽章数量=条目数（每条都有信源徽章）
    const badges = screen.getAllByRole("button", { name: /个信源/ });
    expect(badges.length).toBe(MOCK_HOT_RANK.length);
  });

  it("opens source list popover on hover and closes on outside click (fireEvent)", async () => {
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    await waitFor(() => expect(screen.getAllByRole("button", { name: /个信源/ }).length).toBeGreaterThan(0));
    const badge = screen.getAllByRole("button", { name: /个信源/ })[0];
    expect(screen.queryByRole("dialog", { name: /信源名单/ })).toBeNull();

    // 悬停开（fireEvent mouseEnter；原型 showSources 语义）
    fireEvent.mouseEnter(badge);
    const popover = screen.getByRole("dialog", { name: /信源名单/ });
    expect(popover).toBeTruthy();
    expect(popover.textContent).toContain(MOCK_HOT_RANK[0].sources[0]);

    // 点击气泡外关闭
    fireEvent.mouseDown(document.body);
    await waitFor(() => expect(screen.queryByRole("dialog", { name: /信源名单/ })).toBeNull());
  });

  it("renders methodology footnote copy verbatim (matches backend heat model)", async () => {
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    await waitFor(() => expect(screen.getByText("榜单说明")).toBeTruthy());
    expect(
      screen.getByText(
        "榜单热度 = 信源权重（官方主站高、补充源低）+ 覆盖信源数，并按 24 小时半衰期随时间衰减；同一故事线的关联报道在榜单中合并为一条并显示来源数；用户氛围票权重将随互动功能上线后并入。"
      )
    ).toBeTruthy();
    expect(
      screen.getByText(
        "标签含义：爆 = 短时间密集报道 · 新 = 首报 6 小时内 · 发酵中 = 信源仍在增加。悬停或点击来源数字可查看信源名单。"
      )
    ).toBeTruthy();
    // 头部更新时间标签（实时 HKT 值——形状断言防写死回归，「9月10日」常量已删）
    expect(screen.getByText(/数据更新至 \d+月\d+日 \d{2}:\d{2}/)).toBeTruthy();
  });

  it("makes zero /auth and /rag/settings requests for anonymous visitors", async () => {
    const { requestedUrls } = instrumentNetwork();
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    await waitFor(() => expect(screen.getByText("榜单说明")).toBeTruthy());

    // 公开页红线：数据走 newsService 独立实例，零 /auth、零 /rag/settings 引擎探测
    expect(requestedUrls.filter((url) => url.includes("/auth"))).toEqual([]);
    expect(requestedUrls.filter((url) => url.includes("/rag/settings"))).toEqual([]);
  });

  it("keeps a single page title: no in-page 热点榜 heading, topbar carries it", async () => {
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    await waitFor(() => expect(screen.getByText("榜单说明")).toBeTruthy());
    // 页级标题由 FeedShell 顶栏（非 heading 标签）承载；页内不再出现「热点榜」heading
    expect(screen.queryByRole("heading", { name: "热点榜" })).toBeNull();
    // 副标题行（数据时点说明=全页唯一）保留（实时值形状）
    expect(screen.getByText(/今日理大资讯热度排行 · 数据更新至 \d+月\d+日 \d{2}:\d{2}/)).toBeTruthy();
  });

  it("closes the popover with a delay on mouseleave and keeps it open when entering the popover", async () => {
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    await waitFor(() => expect(screen.getAllByRole("button", { name: /个信源/ }).length).toBeGreaterThan(0));
    const badge = screen.getAllByRole("button", { name: /个信源/ })[0];
    fireEvent.mouseEnter(badge);
    expect(screen.getByRole("dialog", { name: /信源名单/ })).toBeTruthy();

    // 数据就绪后切假钟（渲染等待走真实定时器）：移出后延迟 150ms 才关
    vi.useFakeTimers();
    fireEvent.mouseLeave(badge);
    expect(screen.getByRole("dialog", { name: /信源名单/ })).toBeTruthy();
    act(() => {
      vi.advanceTimersByTime(150);
    });
    expect(screen.queryByRole("dialog", { name: /信源名单/ })).toBeNull();

    // 移入浮层本体取消关闭：重开→移出→150ms 内进入浮层→浮层存活；再移出浮层→延迟后关
    vi.useRealTimers();
    fireEvent.mouseEnter(badge);
    const reopened = screen.getByRole("dialog", { name: /信源名单/ });
    vi.useFakeTimers();
    fireEvent.mouseLeave(badge);
    fireEvent.mouseEnter(reopened);
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(screen.getByRole("dialog", { name: /信源名单/ })).toBeTruthy();
    fireEvent.mouseLeave(reopened);
    act(() => {
      vi.advanceTimersByTime(150);
    });
    expect(screen.queryByRole("dialog", { name: /信源名单/ })).toBeNull();
  });

  it("keeps a single popover instance across badges (singleton)", async () => {
    vi.mocked(fetchHotRank).mockResolvedValue(MOCK_HOT_RANK);
    renderPage();

    await waitFor(() => expect(screen.getAllByRole("button", { name: /个信源/ }).length).toBeGreaterThan(0));
    const badges = screen.getAllByRole("button", { name: /个信源/ });

    // 连续悬停多行：任一时刻至多一枚浮层（多开叠加残留根因修复）
    fireEvent.mouseEnter(badges[0]);
    expect(screen.getAllByRole("dialog", { name: /信源名单/ })).toHaveLength(1);
    fireEvent.mouseEnter(badges[1]);
    expect(screen.getAllByRole("dialog", { name: /信源名单/ })).toHaveLength(1);
    fireEvent.mouseEnter(badges[2]);
    expect(screen.getAllByRole("dialog", { name: /信源名单/ })).toHaveLength(1);
    // 最新悬停行的信源名单在位（旧浮层被单例关闭而非共享内容）
    expect(screen.getByRole("dialog", { name: /信源名单/ }).textContent).toContain(MOCK_HOT_RANK[2].sources[0]);
  });
});
