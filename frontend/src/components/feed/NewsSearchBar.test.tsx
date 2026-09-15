import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { NewsSearchBar } from "./NewsSearchBar";
import { FeedLangContext } from "./feedLang";

/**
 * 检索框组件面：提交（trim 后）、空提交=退出检索信号、外部回落同步输入框、
 * 双语 placeholder/按钮随全局语言。
 */

function renderBar(onSubmit: (q: string) => void, value = "", lang: "zh" | "en" = "zh") {
  return render(
    <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
      <NewsSearchBar value={value} onSubmit={onSubmit} />
    </FeedLangContext.Provider>
  );
}

describe("NewsSearchBar", () => {
  afterEach(() => {
    cleanup();
  });

  it("submits the trimmed keyword on enter and button click", () => {
    const onSubmit = vi.fn();
    renderBar(onSubmit);

    fireEvent.change(screen.getByLabelText("搜索理大资讯"), { target: { value: "  钙钛矿  " } });
    fireEvent.submit(screen.getByLabelText("搜索理大资讯").closest("form")!);
    expect(onSubmit).toHaveBeenCalledWith("钙钛矿");

    fireEvent.change(screen.getByLabelText("搜索理大资讯"), { target: { value: "奖学金" } });
    fireEvent.click(screen.getByRole("button", { name: "搜索" }));
    expect(onSubmit).toHaveBeenCalledWith("奖学金");
  });

  it("signals fallback with an empty submit", () => {
    const onSubmit = vi.fn();
    renderBar(onSubmit);

    fireEvent.change(screen.getByLabelText("搜索理大资讯"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "搜索" }));
    expect(onSubmit).toHaveBeenCalledWith("");
  });

  it("syncs the input when the outer value falls back to empty", () => {
    const onSubmit = vi.fn();
    const { rerender } = renderBar(onSubmit, "钙钛矿");
    fireEvent.change(screen.getByLabelText("搜索理大资讯"), { target: { value: "改了" } });

    rerender(
      <FeedLangContext.Provider value={{ lang: "zh", setLang: () => {} }}>
        <NewsSearchBar value="" onSubmit={onSubmit} />
      </FeedLangContext.Provider>
    );
    expect((screen.getByLabelText("搜索理大资讯") as HTMLInputElement).value).toBe("");
  });

  it("renders bilingual placeholder and button per global lang", () => {
    renderBar(() => {}, "", "en");
    expect(screen.getByPlaceholderText("Search titles & summaries…")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Search" })).toBeTruthy();
  });
});
