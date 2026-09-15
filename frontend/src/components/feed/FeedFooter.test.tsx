import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { FeedFooter } from "./FeedFooter";
import { FeedLangContext } from "./feedLang";

/**
 * 品牌收口面：GitHub 外链（full/compact 两口径均显示）+
 * PolyUGuide 命名定案后的页脚品牌字断言（2026-09-11）。
 */

function renderFooter({ compact = false, lang = "zh" }: { compact?: boolean; lang?: "zh" | "en" } = {}) {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <FeedFooter compact={compact} />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("FeedFooter (GitHub 链 + PolyUGuide 命名)", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("shows GitHub external link in both full and compact variants", () => {
    const { unmount } = renderFooter();
    const full = screen.getByRole("link", { name: "GitHub" });
    expect(full.getAttribute("href")).toBe("https://github.com/Leewwp/polyu-agent");
    expect(full.getAttribute("target")).toBe("_blank");
    unmount();

    renderFooter({ compact: true });
    expect(screen.getByRole("link", { name: "GitHub" })).toBeTruthy();
  });

  it("uses PolyUGuide branding in the declaration and copyright line", () => {
    renderFooter();
    expect(screen.getByText(/PolyUGuide 为学生自发建设的非官方社区项目/)).toBeTruthy();
    expect(screen.getByText(/© 2026 PolyUGuide/)).toBeTruthy();
  });

  it("keeps English declaration wording under en lang", () => {
    renderFooter({ lang: "en" });
    expect(screen.getByText(/PolyUGuide is an independent student community project/)).toBeTruthy();
  });
});
