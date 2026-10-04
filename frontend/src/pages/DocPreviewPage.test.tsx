import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { act } from "react";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";

import { DocPreviewPage } from "./DocPreviewPage";
import { getDocument } from "@/services/knowledgeService";

vi.mock("@/services/knowledgeService", () => ({
  getDocument: vi.fn()
}));
vi.mock("@/components/document/DocumentPreview", () => ({
  DocumentPreview: ({ docName }: { docName: string }) => <div data-testid="doc-preview">{docName}</div>
}));

import { getDocument as getDocumentMock } from "@/services/knowledgeService";

/**
 * #231 文档预览标题收编（迁出 DocPreviewPage 直写旧法）：
 * - 加载/失败回落稳定页名「文档预览 · PolyUGuide」，加载成功以文档名作优先值；
 * - 卸载回落（原直写法离开预览后残留旧标题）；
 * - 慢响应在离开页面后不写回旧标题。
 */

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function docMeta(docId: string, docName: string) {
  return { id: docId, kbId: "kb-1", docName, fileType: "pdf" } as Awaited<ReturnType<typeof getDocument>>;
}

function renderPreview(docId: string) {
  return render(
    <MemoryRouter initialEntries={[`/preview/doc/${docId}`]}>
      <Routes>
        <Route path="/preview/doc/:docId" element={<DocPreviewPage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("DocPreviewPage title via the shared mechanism (#231)", () => {
  beforeEach(() => {
    vi.mocked(getDocumentMock).mockReset();
  });

  afterEach(() => {
    cleanup();
    document.body.removeAttribute("data-ui-theme");
  });

  it("falls back to the stable page name while loading and overrides with the doc name on success", async () => {
    const gate = deferred<ReturnType<typeof docMeta>>();
    vi.mocked(getDocumentMock).mockReturnValueOnce(gate.promise);
    renderPreview("doc-1");

    expect(document.title).toBe("文档预览 · PolyUGuide");
    expect(screen.getByText("正在加载…")).toBeTruthy();

    await act(async () => {
      gate.resolve(docMeta("doc-1", "2026 年度宿舍申请指南.pdf"));
    });
    expect(document.title).toBe("2026 年度宿舍申请指南.pdf · PolyUGuide");
    expect(screen.getByTestId("doc-preview")).toBeTruthy();
  });

  it("keeps the stable page name on load failure (加载失败回落，不展示空值)", async () => {
    const gate = deferred<ReturnType<typeof docMeta>>();
    vi.mocked(getDocumentMock).mockReturnValueOnce(gate.promise);
    renderPreview("doc-2");

    await act(async () => {
      gate.reject(new Error("gone"));
    });
    expect(document.title).toBe("文档预览 · PolyUGuide");
    expect(screen.getByText("无法加载该文档，可能已被删除。")).toBeTruthy();
  });

  it("falls back on unmount and a slow response resolving afterwards must not write the old title back", async () => {
    const gate = deferred<ReturnType<typeof docMeta>>();
    vi.mocked(getDocumentMock).mockReturnValueOnce(gate.promise);
    const view = renderPreview("doc-3");
    expect(document.title).toBe("文档预览 · PolyUGuide");

    // 离开预览（回资讯壳）：标题被下一页接管
    view.unmount();
    document.title = "精选 · PolyUGuide";

    // 慢响应此时才返回——cancelled 守卫拦在 setState 前，旧文档名不得写回
    await act(async () => {
      gate.resolve(docMeta("doc-3", "迟到的旧文档名.pdf"));
    });
    expect(document.title).toBe("精选 · PolyUGuide");
  });

  it("does not write the previous doc name back after switching docId (换参数后慢响应不写回)", async () => {
    // 两次调用的 mock 先就位再导航（点击即触发第二个 effect）；
    // 真导航换参（MemoryRouter rerender 不重放 initialEntries，用 Link 走真实路径）
    const slow = deferred<ReturnType<typeof docMeta>>();
    const fastDeferred = deferred<ReturnType<typeof docMeta>>();
    vi.mocked(getDocumentMock).mockReturnValueOnce(slow.promise);
    vi.mocked(getDocumentMock).mockReturnValueOnce(fastDeferred.promise);
    const view = render(
      <MemoryRouter initialEntries={["/preview/doc/doc-a"]}>
        <Link to="/preview/doc/doc-b">to-b</Link>
        <Routes>
          <Route path="/preview/doc/:docId" element={<DocPreviewPage />} />
        </Routes>
      </MemoryRouter>
    );
    expect(document.title).toBe("文档预览 · PolyUGuide");

    await act(async () => {
      screen.getByRole("link", { name: "to-b" }).click();
    });
    await act(async () => {
      fastDeferred.resolve(docMeta("doc-b", "新文档 B.md"));
    });
    expect(document.title).toBe("新文档 B.md · PolyUGuide");

    // doc-a 的慢响应此时才返回——cancelled 守卫拦在 setState 前，旧文档名不得写回
    await act(async () => {
      slow.resolve(docMeta("doc-a", "迟到的旧文档 A.md"));
    });
    expect(document.title).toBe("新文档 B.md · PolyUGuide");
    void view;
  });
});
