import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { NewsCard } from "./NewsCard";
import { FeedLangContext } from "./feedLang";
import type { FeedLang } from "./feedLang";
import { MOCK_NEWS_ITEMS } from "@/services/newsMockData";

/**
 * 卡片主体（元信息+标题+摘要）可点入 /news/:id 详情页，
 * 「查看原文 ↗」保持外链新开标签；卡片级「中 / EN」小钮已随全局唯一语言开关移除
 * （语言入口收归顶栏 LangPill，全局切换语义不再有单卡覆盖档）。
 */

function renderCard(itemIndex = 0, lang: FeedLang = "zh") {
  return render(
    <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
      <MemoryRouter>
        <NewsCard item={MOCK_NEWS_ITEMS[itemIndex]} />
      </MemoryRouter>
    </FeedLangContext.Provider>
  );
}

describe("NewsCard", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders zh fields by default (title/summary/source/category/cluster badges + original link)", () => {
    const item = MOCK_NEWS_ITEMS[0];
    const { container } = renderCard(0);

    expect(screen.getByText(item.titleZh)).toBeTruthy();
    expect(screen.getByText(item.summaryZh)).toBeTruthy();
    expect(screen.getByText(item.source.labelZh)).toBeTruthy();
    expect(screen.getByText("科研")).toBeTruthy();
    expect(screen.getByText("热点 · 另有 2 个来源")).toBeTruthy();

    const link = screen.getByRole("link", { name: "查看原文 ↗" });
    expect(link.getAttribute("href")).toBe(item.url);
    expect(link.getAttribute("target")).toBe("_blank");
    expect(link.getAttribute("rel")).toBe("noopener noreferrer");
    // 信源徽章圆点用信源色（jsdom 将 #hex 规范化为 rgb 表达）
    const dot = container.querySelector("article i") as HTMLElement | null;
    expect(dot?.style.backgroundColor).toBe("rgb(166, 25, 46)");
  });

  it("renders en fields when the global feed lang is en", () => {
    const item = MOCK_NEWS_ITEMS[0];
    renderCard(0, "en");

    expect(screen.getByText(item.titleEn)).toBeTruthy();
    expect(screen.getByText(item.summaryEn)).toBeTruthy();
    expect(screen.getByText(item.source.labelEn)).toBeTruthy();
    expect(screen.getByText("Research")).toBeTruthy();
    expect(screen.getByText("Hot · 2 more sources")).toBeTruthy();
    expect(screen.getByRole("link", { name: "Source ↗" })).toBeTruthy();
  });

  it("links the card body to /news/:id detail with no per-card language chip", () => {
    const item = MOCK_NEWS_ITEMS[0];
    renderCard(0);

    const detailLink = screen.getByRole("link", { name: new RegExp(item.titleZh) });
    expect(detailLink.getAttribute("href")).toBe(`/news/${item.id}`);
    // 原文外链与详情链接并存且互不嵌套
    expect(screen.getByRole("link", { name: "查看原文 ↗" }).getAttribute("href")).toBe(item.url);
    // 卡片级「中 / EN」小钮移除，语言=顶栏全局唯一开关
    expect(screen.queryByRole("button", { name: "中 / EN" })).toBeNull();
  });
});
