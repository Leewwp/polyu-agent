import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { NewsList } from "./NewsList";
import { FeedLangContext } from "./feedLang";
import { MOCK_NEWS_ITEMS } from "@/services/newsMockData";

/** 日期分组列表——组头带计数、双语日标签、空列表渲染 null。 */

function renderList(lang: "zh" | "en" = "zh", items = MOCK_NEWS_ITEMS) {
  return render(
    <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
      <MemoryRouter>
        <NewsList items={items} />
      </MemoryRouter>
    </FeedLangContext.Provider>
  );
}

describe("NewsList", () => {
  afterEach(() => {
    cleanup();
  });

  it("groups the mock feed into two day groups with counts (15 cards)", () => {
    const { container } = renderList("zh");

    expect(container.querySelectorAll("article")).toHaveLength(15);
    expect(screen.getByText("今天 · 9月10日 周四")).toBeTruthy();
    expect(screen.getByText("12 条")).toBeTruthy();
    expect(screen.getByText("昨天 · 9月9日 周三")).toBeTruthy();
    expect(screen.getByText("3 条")).toBeTruthy();
  });

  it("renders english day labels and counts in en mode", () => {
    const { container } = renderList("en");

    expect(container.querySelectorAll("article")).toHaveLength(15);
    expect(screen.getByText("Today · Thu 10 Sep")).toBeTruthy();
    expect(screen.getByText("12 items")).toBeTruthy();
    expect(screen.getByText("Yesterday · Wed 9 Sep")).toBeTruthy();
  });

  it("renders nothing for an empty item list", () => {
    const { container } = renderList("zh", []);
    expect(container.firstElementChild).toBeNull();
  });
});
