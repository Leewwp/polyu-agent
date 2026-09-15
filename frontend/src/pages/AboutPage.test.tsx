import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

const fetchSiteAbout = vi.fn();

vi.mock("@/services/siteService", () => ({
  fetchSiteAbout: (...args: unknown[]) => fetchSiteAbout(...args),
  submitSiteFeedback: vi.fn()
}));

import { AboutPage } from "./AboutPage";
import { FeedLangContext } from "@/components/feed/feedLang";

/** jsdom 下 localStorage 需手动装桩（照 NewsDetailPage.test installLocalStorageStub 先例） */
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

function renderAbout(lang: "zh" | "en" = "zh") {
  return render(
    <MemoryRouter>
      <FeedLangContext.Provider value={{ lang, setLang: () => {} }}>
        <AboutPage />
      </FeedLangContext.Provider>
    </MemoryRouter>
  );
}

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

  it("EN 档显示英文内容，contentEn 为空时回落中文（doc 32 双语改造）", async () => {
    // 语言态由 FeedShell 内部自持（读 localStorage 初始化），外部 Provider 会被覆盖——
    // 判例：测 EN 档须落 storage 键而非包 Provider
    const mem = installLocalStorageStub();
    mem.set("polyu.feed.lang", "en");
    fetchSiteAbout.mockResolvedValue({
      content: "## 中文标题\n\n中文正文。",
      contentEn: "## English Title\n\nEnglish body.",
      qrImageUrl: null,
      qrImageUrlAlt: null
    });

    renderAbout("en");
    await waitFor(() => {
      expect(screen.getByText("English Title")).toBeTruthy();
    });
    expect(screen.getByText("English body.")).toBeTruthy();
    expect(screen.queryByText("中文正文。")).toBeNull();
    cleanup();

    // contentEn 未配置：EN 档回落中文内容不空屏
    fetchSiteAbout.mockResolvedValue({
      content: "## 中文标题\n\n中文正文。",
      qrImageUrl: null,
      qrImageUrlAlt: null
    });
    renderAbout("en");
    await waitFor(() => {
      expect(screen.getByText("中文正文。")).toBeTruthy();
    });
  });
});
