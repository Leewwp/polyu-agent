import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { useAdminUiTheme } from "./useAdminUiTheme";
import { ChangeLogsPage } from "@/pages/ChangeLogsPage";
import { DocPreviewPage } from "@/pages/DocPreviewPage";

/**
 * #228 Portal 弹层双轨的路由级主题标记：
 * - dialog/alert-dialog/dropdown/select 经 Radix Portal 直挂 document.body，
 *   不在 .admin-layout 根内——后台路由（AdminLayout 根、/change-logs、/preview/doc
 *   两个无布局根的独立路由）挂载期给 body 打 data-ui-theme="admin"，使 body 直挂
 *   子树与后台同主题；卸载/路由离开即清理。
 * - 用户面页面永不打标（弹窗保持 :root 品牌红）。
 * jsdom css:false 不加载样式表，本文件只锚标记行为；标记→紫色的 CSS 语义由
 * globals.primaryTheme.test.ts 的源断言覆盖。
 */

// ChangeLogsPage 主体为审计表格（数据面与本合同无关），桩掉避免服务打桩面扩大
vi.mock("@/pages/admin/change-logs/BizChangeLogPage", () => ({
  BizChangeLogPage: () => <div>biz-change-log</div>
}));
vi.mock("@/services/knowledgeService", () => ({
  getDocument: vi.fn(() =>
    Promise.resolve({ docId: "d1", docName: "样本文档", fileType: "md", content: "x" })
  )
}));
// 预览内部件会继续拉文件内容（fetchDocumentFile/previewDocument 等），与本合同无关
vi.mock("@/components/document/DocumentPreview", () => ({
  DocumentPreview: () => <div>doc-preview</div>
}));

function themeAttr(): string | null {
  return document.body.getAttribute("data-ui-theme");
}

beforeEach(() => {
  document.body.removeAttribute("data-ui-theme");
});

afterEach(() => {
  cleanup();
  document.body.removeAttribute("data-ui-theme");
  vi.restoreAllMocks();
});

describe("useAdminUiTheme 标记生命周期", () => {
  it("挂载打 admin 标记，卸载即清理（无残留）", () => {
    function Probe() {
      useAdminUiTheme();
      return <div>probe</div>;
    }
    const { unmount } = render(<Probe />);
    expect(themeAttr()).toBe("admin");
    unmount();
    expect(themeAttr()).toBeNull();
  });

  it("用户面页面不打标（弹窗保持 :root 品牌红）", () => {
    render(
      <MemoryRouter initialEntries={["/login"]}>
        <Routes>
          <Route path="/login" element={<div>login-page</div>} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByText("login-page")).toBeTruthy();
    expect(themeAttr()).toBeNull();
  });
});

describe("后台独立路由接入（无 AdminLayout 根）", () => {
  it("/change-logs 挂载打标、卸载清理", () => {
    const { unmount } = render(
      <MemoryRouter initialEntries={["/change-logs"]}>
        <Routes>
          <Route path="/change-logs" element={<ChangeLogsPage />} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByText("biz-change-log")).toBeTruthy();
    expect(themeAttr()).toBe("admin");
    unmount();
    expect(themeAttr()).toBeNull();
  });

  it("/preview/doc/:docId 挂载打标、卸载清理", async () => {
    const { unmount } = render(
      <MemoryRouter initialEntries={["/preview/doc/d1"]}>
        <Routes>
          <Route path="/preview/doc/:docId" element={<DocPreviewPage />} />
        </Routes>
      </MemoryRouter>
    );
    await waitFor(() => {
      expect(screen.getByText("样本文档")).toBeTruthy();
    });
    expect(themeAttr()).toBe("admin");
    unmount();
    expect(themeAttr()).toBeNull();
  });
});

describe("路由往返导航", () => {
  it("后台 → 用户面（应用内导航）标记随路由清理，再回后台重新打标", async () => {
    render(
      <MemoryRouter initialEntries={["/change-logs"]}>
        <Routes>
          <Route path="/change-logs" element={<ChangeLogsPage />} />
          <Route path="/chat" element={<div>chat-page</div>} />
        </Routes>
      </MemoryRouter>
    );
    expect(themeAttr()).toBe("admin");

    // ChangeLogsPage 顶栏自带「返回聊天」入口，走真实路由跳转而非重挂载树
    fireEvent.click(screen.getByRole("link", { name: /返回聊天/ }));
    await waitFor(() => {
      expect(screen.getByText("chat-page")).toBeTruthy();
    });
    expect(themeAttr()).toBeNull();
  });

  it("后台卸载后用户面渲染无残留（跨 render 树往返）", () => {
    const first = render(
      <MemoryRouter initialEntries={["/change-logs"]}>
        <Routes>
          <Route path="/change-logs" element={<ChangeLogsPage />} />
        </Routes>
      </MemoryRouter>
    );
    expect(themeAttr()).toBe("admin");
    first.unmount();

    render(
      <MemoryRouter initialEntries={["/account"]}>
        <Routes>
          <Route path="/account" element={<div>account-page</div>} />
        </Routes>
      </MemoryRouter>
    );
    expect(screen.getByText("account-page")).toBeTruthy();
    expect(themeAttr()).toBeNull();
  });
});
