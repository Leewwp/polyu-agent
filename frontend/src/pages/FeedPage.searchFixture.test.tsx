import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { FeedPage } from "./FeedPage";
import { MOCK_NEWS_ITEMS } from "@/services/newsMockData";

/**
 * 检索 mock fixture 真分支（USE_MOCK）：中英关键词分别命中标题路与摘要路。
 * 独立文件不 vi.mock 服务层（paging.test 经验：同文件 mock 会波及真实 fixture 用例）。
 */

function renderFeed(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <FeedPage />
    </MemoryRouter>
  );
}

describe("FeedPage search against mock fixture", () => {
  afterEach(() => {
    cleanup();
  });

  it("matches a zh keyword on the title path", async () => {
    const { container } = renderFeed("/?q=钙钛矿");
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(1);
    });
    expect(screen.getByText(MOCK_NEWS_ITEMS[0].titleZh)).toBeTruthy();
  });

  it("matches a summary-only zh needle (钝化 not present in any title)", async () => {
    const { container } = renderFeed("/?q=钝化");
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(1);
    });
    expect(container.querySelector("article")?.textContent).toContain(MOCK_NEWS_ITEMS[0].summaryZh);
  });

  it("matches an en keyword case-insensitively", async () => {
    const { container } = renderFeed("/?q=perovskite");
    await waitFor(() => {
      expect(container.querySelectorAll("article")).toHaveLength(1);
    });
  });
});
