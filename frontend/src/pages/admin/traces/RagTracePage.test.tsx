import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { RagTracePage } from "./RagTracePage";
import { getRagTraceRuns, type PageResult, type RagTraceRun } from "@/services/ragTraceService";

/**
 * #294：traces 页对 agent 引擎对话恒零数据（写入端只挂 workflow /rag/v3/chat，
 * StreamChatTraceRunner 唯一调用方）——零数据时页面须明示覆盖范围，
 * 不再呈现为「链路丢失」观感；副标题常驻数据源口径。
 */

vi.mock("@/services/ragTraceService", () => ({
  getRagTraceRuns: vi.fn()
}));
vi.mock("sonner", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

function result(runs: RagTraceRun[]): PageResult<RagTraceRun> {
  return { records: runs, total: runs.length, size: 10, current: 1, pages: 1 };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/admin/traces"]}>
      <RagTracePage />
    </MemoryRouter>
  );
}

describe("RagTracePage 覆盖范围标注（#294）", () => {
  beforeEach(() => {
    vi.mocked(getRagTraceRuns).mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("零数据时展示覆盖范围提示（0 条≠链路丢失）", async () => {
    vi.mocked(getRagTraceRuns).mockResolvedValue(result([]));
    renderPage();

    await waitFor(() => expect(getRagTraceRuns).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByText(/仅收录 workflow 检索引擎/)).toBeTruthy()
    );
  });

  it("有数据时不展示零数据提示；副标题常驻数据源口径", async () => {
    vi.mocked(getRagTraceRuns).mockResolvedValue(
      result([
        {
          traceId: "t-1",
          status: "SUCCESS",
          durationMs: 120,
          startTime: "2026-10-05 10:00:00"
        }
      ])
    );
    renderPage();

    await waitFor(() => expect(screen.getByText("t-1")).toBeTruthy());
    expect(screen.queryByText(/暂无链路记录/)).toBeNull();
    // 副标题无论有无数据都写明数据源范围
    expect(screen.getByText(/数据源为\s*workflow 检索引擎/)).toBeTruthy();
  });
});
