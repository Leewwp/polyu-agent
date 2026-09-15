import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import { CategoryChips } from "./CategoryChips";
import { FeedLangContext } from "./feedLang";
import type { NewsCategory } from "@/types/news";

/** 固定 8 类 + 全部的筛选 chips——受控值、回调、aria-pressed、双语。 */

function renderChips(value: NewsCategory | "all" = "all", lang: "zh" | "en" = "zh") {
  const onChange = vi.fn();
  const view = render(
    <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
      <CategoryChips value={value} onChange={onChange} />
    </FeedLangContext.Provider>
  );
  return { onChange, ...view };
}

describe("CategoryChips", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders the nine chips (all + 8 fixed categories) with zh labels", () => {
    renderChips("all");
    const names = ["全部", "招生", "科研", "校园", "活动", "就业", "交流", "奖学金", "公告"];
    for (const name of names) {
      expect(screen.getByRole("button", { name })).toBeTruthy();
    }
  });

  it("marks the selected chip via aria-pressed and reports changes", async () => {
    const { onChange } = renderChips("all");
    const user = userEvent.setup();

    expect(screen.getByRole("button", { name: "全部" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "科研" }).getAttribute("aria-pressed")).toBe("false");

    await user.click(screen.getByRole("button", { name: "科研" }));
    expect(onChange).toHaveBeenCalledWith("research");
  });

  it("switches to english labels in en mode", () => {
    renderChips("research", "en");
    expect(screen.getByRole("button", { name: "Research" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "All" })).toBeTruthy();
  });
});
