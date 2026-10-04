import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { SiteFooter } from "./SiteFooter";
import { FeedLangContext } from "@/components/feed/feedLang";

/**
 * 站点常驻页脚（#227 语言总闸消费面）：
 * - zh 态单语中文、「隐私声明」为唯一叫法（不再「隐私声明 · Privacy」硬拼双语）；
 * - en 态单语英文（Privacy Notice / Terms / Disclaimer / About / Feedback）。
 */

function renderFooter(lang: "zh" | "en" = "zh") {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <SiteFooter />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

describe("SiteFooter", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders all three legal links in Chinese when lang=zh (privacy wording unified)", () => {
    renderFooter("zh");

    expect(screen.getByRole("link", { name: "隐私声明" }).getAttribute("href")).toBe("/privacy");
    expect(screen.getByRole("link", { name: "服务条款" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "非官方声明" }).getAttribute("href")).toBe(
      "/disclaimer"
    );
    expect(screen.getByRole("link", { name: "关于" }).getAttribute("href")).toBe("/about");
    expect(screen.getByRole("button", { name: "反馈" })).toBeTruthy();
    // 硬拼双语形态不再出现（#227 拆分）
    expect(screen.queryByText("隐私声明 · Privacy")).toBeNull();
  });

  it("renders single-language English labels when lang=en", () => {
    renderFooter("en");

    expect(
      screen.getByRole("link", { name: "Privacy Notice" }).getAttribute("href")
    ).toBe("/privacy");
    expect(screen.getByRole("link", { name: "Terms" }).getAttribute("href")).toBe("/terms");
    expect(screen.getByRole("link", { name: "Disclaimer" }).getAttribute("href")).toBe(
      "/disclaimer"
    );
    expect(screen.getByRole("link", { name: "About" }).getAttribute("href")).toBe("/about");
    expect(screen.getByRole("button", { name: "Feedback" })).toBeTruthy();
    // 中文形态不残留
    expect(screen.queryByText("隐私声明")).toBeNull();
  });
});
