import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { useAgentChatStore } from "@/stores/agentChatStore";

vi.mock("@/services/agentService", () => ({
  listAgentSessions: vi.fn().mockResolvedValue([]),
  listAgentMessages: vi.fn().mockResolvedValue([]),
  renameAgentSession: vi.fn().mockResolvedValue(undefined),
  deleteAgentSession: vi.fn().mockResolvedValue(undefined),
  batchDeleteAgentSessions: vi.fn().mockResolvedValue(undefined),
  getAgentMeta: vi.fn().mockRejectedValue(new Error("offline")),
  stopAgentTask: vi.fn()
}));

import {
  deleteAgentSession,
  renameAgentSession,
  batchDeleteAgentSessions
} from "@/services/agentService";

// RecentChatsSection 未从模块导出——经 FeedSidebar 文件内组件直接导出后此处引用
import { RecentChatsSection } from "./FeedSidebar";

const SESSIONS = [
  { id: "s-1", title: "奖学金申请" },
  { id: "s-2", title: "宿舍申请" },
  { id: "s-3", title: "选课问题" },
  { id: "s-4", title: "奖学金续期" },
  { id: "id-5-en", title: "Library hours" }
];

function setup(manageable = true, isAgentEngine = true) {
  useAgentChatStore.setState({ sessions: SESSIONS as never, currentSessionId: null });
  render(
    <MemoryRouter>
      <RecentChatsSection
        sessions={SESSIONS}
        isAgentEngine={isAgentEngine}
        manageable={manageable}
        zh
        onNavigate={() => null}
      />
    </MemoryRouter>
  );
}

describe("RecentChatsSection 会话管理（T17）", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("匿名/游客态不渲染任何管理入口（无搜索框/选择钮/行菜单）", () => {
    setup(false);

    expect(screen.queryByLabelText("搜索对话")).toBeNull();
    expect(screen.queryByText("选择")).toBeNull();
    expect(screen.queryByLabelText("会话操作")).toBeNull();
    // 只读列表照常渲染
    expect(screen.getByText("奖学金申请")).toBeTruthy();
  });

  it("搜索即时过滤：命中词只留匹配行，且不受默认 3 条上限约束", () => {
    setup();

    const search = screen.getByLabelText("搜索对话");
    fireEvent.change(search, { target: { value: "奖学金" } });

    expect(screen.getByText("奖学金申请")).toBeTruthy();
    expect(screen.getByText("奖学金续期")).toBeTruthy();
    expect(screen.queryByText("宿舍申请")).toBeNull();
    expect(screen.queryByText("无匹配对话")).toBeNull();

    fireEvent.change(search, { target: { value: "不存在词" } });
    expect(screen.getByText("无匹配对话")).toBeTruthy();
  });

  it("多选批量删：选择→勾两条→确认弹窗→批删 API+store 同步收敛", async () => {
    setup();

    fireEvent.click(screen.getByText("选择"));
    fireEvent.click(screen.getByText("奖学金申请"));
    fireEvent.click(screen.getByText("宿舍申请"));
    fireEvent.click(screen.getByText("删除所选"));

    expect(screen.getByText("删除选中的 2 个会话？")).toBeTruthy();
    fireEvent.click(screen.getByText("删除", { selector: "button" }));

    await waitFor(() => {
      expect(batchDeleteAgentSessions).toHaveBeenCalledWith(["s-1", "s-2"]);
    });
    // store 状态同步：会话列表只剩 3 条
    await waitFor(() => {
      expect(useAgentChatStore.getState().sessions.map((s) => s.id)).toEqual(["s-3", "s-4", "id-5-en"]);
    });
  });

  it("行菜单重命名：改名提交走 rename API 且 store 标题同步", async () => {
    setup();

    fireEvent.pointerDown(screen.getAllByLabelText("会话操作")[0], { button: 0 });
    fireEvent.click(screen.getAllByLabelText("会话操作")[0]);
    const renameItem = await screen.findByText("重命名");
    fireEvent.click(renameItem);

    const input = screen.getByLabelText("会话标题");
    fireEvent.change(input, { target: { value: "奖学金政策十问" } });
    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() => {
      expect(renameAgentSession).toHaveBeenCalledWith("s-1", "奖学金政策十问");
    });
    await waitFor(() => {
      expect(
        useAgentChatStore.getState().sessions.find((s) => s.id === "s-1")?.title
      ).toBe("奖学金政策十问");
    });
  });

  it("行菜单单删：确认弹窗→删除 API+store 移除", async () => {
    setup();

    fireEvent.pointerDown(screen.getAllByLabelText("会话操作")[1], { button: 0 });
    fireEvent.click(screen.getAllByLabelText("会话操作")[1]);
    fireEvent.click(await screen.findByText("删除"));
    fireEvent.click(screen.getByText("删除", { selector: "button" }));

    await waitFor(() => {
      expect(deleteAgentSession).toHaveBeenCalledWith("s-2");
    });
    await waitFor(() => {
      expect(useAgentChatStore.getState().sessions.map((s) => s.id)).toEqual([
        "s-1",
        "s-3",
        "s-4",
        "id-5-en"
      ]);
    });
  });
});
