import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { TopicsGrid } from "./TopicsGrid";
import { FeedLangContext } from "./feedLang";
import type { FeedLang } from "./feedLang";
import { NEWS_TOPICS, NEWS_TOPIC_GROUPS } from "@/services/newsMockData";

/**
 * 主题目录分组卡阵（三维分组目录卡——名称+一句话简介+条目计数）。
 */

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
  afterEach(() => {
    cleanup();
  });

  it("renders faculty group (g=0) cards with name/description/count meta and detail links", () => {
    const { container } = renderGrid(0);

    // 学院与部门 6 卡（原型 g=0 无图标：纯名称）
    const cards = container.querySelectorAll("a");
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
});
