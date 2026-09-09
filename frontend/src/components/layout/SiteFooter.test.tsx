import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { SiteFooter } from "./SiteFooter";

describe("SiteFooter", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders all three legal links with bilingual labels", () => {
    render(
      <MemoryRouter>
        <SiteFooter />
      </MemoryRouter>
    );

    expect(
      screen.getByRole("link", { name: "隐私声明 · Privacy" }).getAttribute("href")
    ).toBe("/privacy");
    expect(screen.getByRole("link", { name: "服务条款 · Terms" }).getAttribute("href")).toBe(
      "/terms"
    );
    expect(
      screen.getByRole("link", { name: "非官方声明 · Disclaimer" }).getAttribute("href")
    ).toBe("/disclaimer");
  });
});
