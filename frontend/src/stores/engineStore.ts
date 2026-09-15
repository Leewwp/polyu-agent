import { create } from "zustand";

import { api } from "@/services/api";

export type EngineType = "workflow" | "agent";

interface EngineSettingsVO {
  /** 迷你端点只返 {type}（裸 EngineSettings，非 /rag/settings 整表的 {engine:{...}} 包装） */
  type: string;
}

interface EngineState {
  engineType: EngineType | null;
  loading: boolean;
  error: string | null;
  initialize: () => Promise<void>;
}

// 引擎探测（2026-09-13）：改调登录态可读的迷你端点 /rag/settings/engine
// （只返 {type}），并删除「失败静默回退 workflow」——此前探测走 admin-only 的 /rag/settings，
// 非 admin 必 403 被回退，普通用户全部落在 workflow 引擎、v2 Agent 仅 admin 前端可达。
// 失败改置 error 态由 EngineGate 呈现重试入口；探测只应在鉴权后触发（EngineGate）或
// FeedSidebar 已登录分支（登录用户零越权面；匿名态零网络请求红线不变）。
export const useEngineStore = create<EngineState>((set, get) => ({
  engineType: null,
  loading: false,
  error: null,
  initialize: async () => {
    if (get().engineType || get().loading) return;
    set({ loading: true, error: null });
    try {
      const data = await api.get<EngineSettingsVO, EngineSettingsVO>("/rag/settings/engine");
      const type = data?.type?.toLowerCase();
      set({ engineType: type === "agent" ? "agent" : "workflow", loading: false });
    } catch (error) {
      set({ engineType: null, loading: false, error: (error as Error).message || "引擎探测失败" });
    }
  }
}));
