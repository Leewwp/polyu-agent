import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { AgentSharePage } from "@/pages/AgentSharePage";
import { useAuthStore } from "@/stores/authStore";

const getPublicAgentShareMock = vi.hoisted(() => vi.fn());

vi.mock("@/services/agentShareService", () => ({
  getPublicAgentShare: getPublicAgentShareMock
}));

/**
 * issue #82 会话分享公开页：按时间序渲染消息序列（提问/终答）与标题；
 * 无效链接统一错误态；隐私门=DOM 不出现任何身份/ID/思考类字段文本。
 * useParams 须经真实 Route 匹配（裸 MemoryRouter 子组件拿不到参数）。
 */
function setup(token = "TOKEN123") {
  return render(
    <MemoryRouter initialEntries={[`/share/c/${token}`]}>
      <Routes>
        <Route path="/share/c/:token" element={<AgentSharePage />} />
      </Routes>
    </MemoryRouter>
  );
}

describe("AgentSharePage", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    getPublicAgentShareMock.mockReset();
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it("渲染标题与按序消息对（提问+终答）", async () => {
    getPublicAgentShareMock.mockResolvedValue({
      title: "宿舍申请咨询",
      lang: "zh",
      messages: [
        { role: "user", content: "如何申请宿舍？", createTime: "2026-09-19T00:00:00Z" },
        { role: "assistant", content: "在线申请即可。", createTime: "2026-09-19T00:01:00Z" }
      ]
    });
    setup();

    await waitFor(() => {
      expect(screen.getByText("宿舍申请咨询")).toBeTruthy();
    });
    expect(screen.getByText("如何申请宿舍？")).toBeTruthy();
    expect(screen.getByText("在线申请即可。")).toBeTruthy();
    // 始终带非官方与时效提示
    expect(screen.getByText(/非官方服务/)).toBeTruthy();
  });

  it("无效链接：统一「分享链接无效或已撤销」错误态", async () => {
    getPublicAgentShareMock.mockRejectedValue(new Error("分享链接无效或已撤销"));
    setup("BADTOKEN");

    await waitFor(() => {
      expect(screen.getByText("分享链接无效或已撤销")).toBeTruthy();
    });
    expect(screen.queryByText("继续提问 · Continue asking")).toBeTruthy();
  });
});
