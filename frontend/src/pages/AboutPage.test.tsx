import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

const fetchSiteAbout = vi.fn();

vi.mock("@/services/siteService", () => ({
  fetchSiteAbout: (...args: unknown[]) => fetchSiteAbout(...args),
  submitSiteFeedback: vi.fn()
}));

import { AboutPage } from "./AboutPage";

describe("AboutPage", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("渲染后台配置的 markdown 内容", async () => {
    fetchSiteAbout.mockResolvedValue({
      content: "## 关于 PolyUGuide\n\n这是测试内容。",
      qrImageUrl: null,
      qrImageUrlAlt: null
    });

    render(
      <MemoryRouter>
        <AboutPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("关于 PolyUGuide")).toBeTruthy();
    });
    expect(screen.getByText("这是测试内容。")).toBeTruthy();
    // 两码皆空：赞赏区含标题整块不渲染（附录 A 渲染契约）
    expect(screen.queryByText("请作者喝杯咖啡")).toBeNull();
  });

  it("接口失败或空内容出「内容暂未配置」空态", async () => {
    fetchSiteAbout.mockRejectedValue(new Error("404"));

    render(
      <MemoryRouter>
        <AboutPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/内容暂未配置/)).toBeTruthy();
    });
  });

  it("有二维码时渲染赞赏区与图片", async () => {
    fetchSiteAbout.mockResolvedValue({
      content: "正文内容足够渲染。",
      qrImageUrl: "https://assets/qr-main.png",
      qrImageUrlAlt: null
    });

    render(
      <MemoryRouter>
        <AboutPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText("请作者喝杯咖啡")).toBeTruthy();
    });
    const img = screen.getByAltText("赞赏二维码");
    expect(img.getAttribute("src")).toBe("https://assets/qr-main.png");
    // 只有主码：第二码不渲染
    expect(screen.queryByAltText("赞赏二维码（二）")).toBeNull();
  });
});
