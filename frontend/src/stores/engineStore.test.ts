import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useEngineStore } from "@/stores/engineStore";
import { api } from "@/services/api";

/**
 * 引擎探测改调登录态可读的迷你端点 /rag/settings/engine，
 * 并删除「失败静默回退 workflow」——403 回退曾使非 admin 全落 workflow、v2 Agent 仅
 * admin 前端可达。失败必须置 error 态（EngineGate 呈现重试），不得伪造 engineType。
 */

vi.mock("@/services/api", () => ({
  api: { get: vi.fn() }
}));

describe("engineStore 探测端点切换", () => {
  beforeEach(() => {
    useEngineStore.setState({ engineType: null, loading: false, error: null });
    vi.mocked(api.get).mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("probes the mini endpoint and maps agent (bare {type} payload shape)", async () => {
    vi.mocked(api.get).mockResolvedValue({ type: "AGENT" });
    await useEngineStore.getState().initialize();
    expect(api.get).toHaveBeenCalledWith("/rag/settings/engine");
    expect(useEngineStore.getState().engineType).toBe("agent");
    expect(useEngineStore.getState().error).toBeNull();
  });

  it("maps anything else to workflow (same contract as before)", async () => {
    vi.mocked(api.get).mockResolvedValue({ type: "workflow" });
    await useEngineStore.getState().initialize();
    expect(useEngineStore.getState().engineType).toBe("workflow");
  });

  it("does NOT silently fall back to workflow on failure — sets error for retry UI", async () => {
    vi.mocked(api.get).mockRejectedValue(new Error("网络错误"));
    await useEngineStore.getState().initialize();
    expect(useEngineStore.getState().engineType).toBeNull();
    expect(useEngineStore.getState().error).toBe("网络错误");
  });

  it("a failed probe can be retried (initialize runs again once error is set)", async () => {
    vi.mocked(api.get).mockRejectedValueOnce(new Error("网络错误"));
    await useEngineStore.getState().initialize();
    expect(useEngineStore.getState().error).toBe("网络错误");

    vi.mocked(api.get).mockResolvedValue({ type: "agent" });
    await useEngineStore.getState().initialize();
    expect(useEngineStore.getState().engineType).toBe("agent");
    expect(useEngineStore.getState().error).toBeNull();
  });
});
