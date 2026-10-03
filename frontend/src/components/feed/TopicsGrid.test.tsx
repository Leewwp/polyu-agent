import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { TopicsGrid } from "./TopicsGrid";
import { FeedLangContext } from "./feedLang";
import type { FeedLang } from "./feedLang";
import { NEWS_TOPICS, NEWS_TOPIC_GROUPS } from "@/services/newsMockData";
import { NEWS_FOLLOWED_TOPICS_KEY, useNewsLocalStore } from "@/stores/newsLocalStore";

/**
 * 主题目录分组卡阵（三维分组目录卡——名称+一句话简介+条目计数）。
 * #215 目录触点：每卡右上角本地关注钮——点击写穿 localStorage（存 slug），
 * 未关注/已关注两态即时回显；不影响卡主体链接与计数。
 */

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

function renderGrid(groupIndex: 0 | 1 | 2, lang: FeedLang = "zh") {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <TopicsGrid group={NEWS_TOPIC_GROUPS[groupIndex]} topics={NEWS_TOPICS.filter((topic) => topic.group === groupIndex)} />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("TopicsGrid", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    mem = installLocalStorageStub();
    useNewsLocalStore.getState().hydrate();
  });

  afterEach(() => {
    cleanup();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("renders faculty group (g=0) cards with name/description/count meta and detail links", () => {
    const { container } = renderGrid(0);

    // 学院与部门 6 卡（原型 g=0 无图标：纯名称）；每卡保持单一详情链（关注钮非链接）
    const cards = container.querySelectorAll("a[href^='/topics/']");
    expect(cards).toHaveLength(6);
    expect(screen.getByText("工学院")).toBeTruthy();
    expect(screen.getByText("工学院及旗下学系的科研、课程与活动动态")).toBeTruthy();
    expect(screen.getByText("查看 28 条 →")).toBeTruthy();
    expect(screen.getByRole("link", { name: /工学院/ }).getAttribute("href")).toBe("/topics/eng");
    // 分组头与副题
    expect(screen.getByText("学院与部门")).toBeTruthy();
    expect(screen.getByText("按学院视角聚合的科研、课程与活动动态")).toBeTruthy();
  });

  it("prefixes the topic icon for research/student-affairs groups (原型 icon 字段)", () => {
    renderGrid(1);

    // 分组 1（研究领域与话题）名称带图标前缀；EN 取 nameEn 不带图标
    expect(screen.getByText("🤖 人工智能")).toBeTruthy();
    expect(screen.getByText("查看 74 条 →")).toBeTruthy();
    expect(screen.getByRole("link", { name: /人工智能/ }).getAttribute("href")).toBe("/topics/ai");
  });

  it("renders en labels when the feed lang is en", () => {
    renderGrid(0, "en");

    expect(screen.getByText("Faculty of Engineering")).toBeTruthy();
    expect(screen.getByText("Research, programmes and events from FENG and its departments")).toBeTruthy();
    expect(screen.getByText("28 items →")).toBeTruthy();
    expect(screen.getByText("Faculties & Departments")).toBeTruthy();
  });

  it("#215 renders a local follow toggle per card: 关注→已关注 writes slug to localStorage", async () => {
    renderGrid(1);
    const user = userEvent.setup();

    // 分组 1 共 7 卡，每卡一枚未关注钮
    expect(screen.getAllByRole("button", { name: "☆ 关注" })).toHaveLength(7);

    // 在「人工智能」卡内点关注：仅该卡回显已关注，其余维持未关注
    const aiCard = screen.getByText("🤖 人工智能").closest("a")?.parentElement;
    expect(aiCard).toBeTruthy();
    await user.click(within(aiCard as HTMLElement).getByRole("button", { name: "☆ 关注" }));

    expect(within(aiCard as HTMLElement).getByRole("button", { name: "★ 已关注" })).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "☆ 关注" })).toHaveLength(6);
    // 写穿 localStorage：存主题 slug 稳定标识
    expect(JSON.parse(mem.get(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual(["ai"]);

    // 再点取关：状态与持久层同步回落
    await user.click(within(aiCard as HTMLElement).getByRole("button", { name: "★ 已关注" }));
    expect(within(aiCard as HTMLElement).getByRole("button", { name: "☆ 关注" })).toBeTruthy();
    expect(JSON.parse(mem.get(NEWS_FOLLOWED_TOPICS_KEY) ?? "[]")).toEqual([]);
  });

  it("#215 follows in en lang (Follow/Following labels)", async () => {
    renderGrid(1, "en");
    const user = userEvent.setup();

    // EN 名称不带图标前缀（icon 仅 zh 前缀）
    const aiCard = screen.getByText("Artificial Intelligence").closest("a")?.parentElement as HTMLElement;
    await user.click(within(aiCard).getByRole("button", { name: "☆ Follow" }));
    expect(within(aiCard).getByRole("button", { name: "★ Following" })).toBeTruthy();
  });
});
