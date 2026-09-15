import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { NewsDetailPage } from "./NewsDetailPage";
import { MOCK_NEWS_ITEMS } from "@/services/newsMockData";

vi.mock("sonner", () => ({ toast: vi.fn() }));
import { toast } from "sonner";

/**
 * 详情页：
 * - 仅 AI 摘要档：标题/信源/分类/发布时间/热度/主题标签+AI 导读+首尾「查看原文 ↗」；
 * - 主体不渲染原文全文（后端无 content 列口径的站内对偶）；
 * - 轻量分享：navigator.share 缺席时降级复制链接+toast；
 * - 不存在 id → 同形 not-found 态；语言跟随全局 pill（localStorage 记忆）。
 */

const ITEM = MOCK_NEWS_ITEMS[0];

/** jsdom 30 不暴露 window.localStorage（feedLang.test 同款内存桩经验） */
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

function renderDetail(id = ITEM.id) {
  return render(
    <MemoryRouter initialEntries={[`/news/${id}`]}>
      <Routes>
        <Route path="/news/:id" element={<NewsDetailPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("NewsDetailPage", () => {
  let mem: Map<string, string>;

  beforeEach(() => {
    mem = installLocalStorageStub();
  });

  afterEach(() => {
    cleanup();
    // 不用 restoreAllMocks：会把 sonner 工厂 mock 的 vi.fn() 复原成无实现，
    // 本文件无 spyOn 面，clearAllMocks 清调用记录足矣
    vi.clearAllMocks();
    Object.defineProperty(window, "localStorage", { value: undefined, configurable: true });
  });

  it("renders the AI-summary-only detail with meta, topics and both source links", async () => {
    renderDetail();

    await waitFor(() => {
      expect(screen.getByText(ITEM.titleZh)).toBeTruthy();
    });
    expect(screen.getByText(ITEM.summaryZh)).toBeTruthy();
    expect(screen.getByText("AI 导读")).toBeTruthy();
    expect(screen.getByText(ITEM.source.labelZh)).toBeTruthy();
    expect(screen.getByText("科研")).toBeTruthy();
    expect(screen.getByText(/🔥 138/)).toBeTruthy();
    // 主题标签 → 主题详情路由（mock-001 topics: energy/materials/eng）
    expect(screen.getByRole("link", { name: "# 新能源与可持续" }).getAttribute("href")).toBe("/topics/energy");
    // 返回链 + 原文外链首尾两处（顶部+尾部各一，新开标签）
    expect(screen.getByRole("link", { name: "‹ 返回资讯流" }).getAttribute("href")).toBe("/");
    const sourceLinks = screen.getAllByRole("link", { name: "查看原文 ↗" });
    expect(sourceLinks).toHaveLength(2);
    for (const link of sourceLinks) {
      expect(link.getAttribute("href")).toBe(ITEM.url);
      expect(link.getAttribute("target")).toBe("_blank");
    }
    // 顶部动作行带分享小钮，尾部另有分享主钮
    expect(screen.getAllByRole("button", { name: "分享" })).toHaveLength(2);
  });

  it("falls back to copy-link toast when navigator.share is unavailable", async () => {
    renderDetail();

    await waitFor(() => {
      expect(screen.getByText(ITEM.titleZh)).toBeTruthy();
    });
    const user = userEvent.setup();
    // 注意：userEvent.setup() 会自装 navigator.clipboard 桩，须在其后再覆盖方可命中
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    Object.defineProperty(navigator, "share", { value: undefined, configurable: true });
    await user.click(screen.getAllByRole("button", { name: "分享" })[0]);

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/news/${ITEM.id}`);
      expect(toast).toHaveBeenCalledWith("链接已复制");
    });
  });

  it("shows the same not-found state for unknown ids (不存在/已下架同形)", async () => {
    renderDetail("nonexistent-id");

    await waitFor(() => {
      expect(screen.getByText("该资讯不存在或已下架")).toBeTruthy();
    });
    expect(screen.getByRole("link", { name: "‹ 返回资讯流" })).toBeTruthy();
  });

  it("renders en fields when the stored global lang is en", async () => {
    mem.set("polyu.feed.lang", "en");
    renderDetail();

    await waitFor(() => {
      expect(screen.getByText(ITEM.titleEn)).toBeTruthy();
    });
    expect(screen.getByText(ITEM.summaryEn)).toBeTruthy();
    expect(screen.getByText("AI summary")).toBeTruthy();
    expect(screen.getAllByRole("button", { name: "Share" }).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByRole("link", { name: "View source ↗" })).toBeTruthy();
    expect(screen.getAllByRole("link", { name: "Source ↗" }).length).toBeGreaterThanOrEqual(1);
  });
});
