import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { DisclaimerPage } from "./DisclaimerPage";

/**
 * #229 非官方声明：正文旧品牌名「PolyU Wayfinder」清零（中英两处一并换
 * PolyUGuide）+ 头部品牌位随主站系；与 PolyU 非隶属口径原文保持。
 */
describe("DisclaimerPage（#229 品牌清理）", () => {
  afterEach(() => {
    cleanup();
  });

  it("正文以 PolyUGuide 自称，旧品牌名零残留", () => {
    render(
      <MemoryRouter>
        <DisclaimerPage />
      </MemoryRouter>
    );

    const body = document.body.textContent ?? "";
    expect(body).not.toContain("PolyU Wayfinder");
    expect(body).toContain("PolyUGuide 是一个由个人维护的非官方项目");
    expect(body).toContain("PolyUGuide is an unofficial project");
    // 非隶属口径不动：大学全称与标识声明仍在
    expect(body).toContain("The Hong Kong Polytechnic University");
    expect(body).toContain("无隶属、授权或赞助关系");
  });

  it("头部为 PolyUGuide 品牌，品牌位链回主站", () => {
    render(
      <MemoryRouter>
        <DisclaimerPage />
      </MemoryRouter>
    );

    const brand = screen.getByRole("link", { name: "PolyUGuide" });
    expect(brand.getAttribute("href")).toBe("/");
  });
});
