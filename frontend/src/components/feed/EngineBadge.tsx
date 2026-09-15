import { useEffect, useState } from "react";

import { getAgentMeta } from "@/services/agentService";
import { useFeedLang } from "./feedLang";
import type { AgentEngineMeta } from "@/types/agent";

/**
 * 引擎徽标（2026-09-13）：上游 AgentLayout 顶栏的 agent-badge
 * （进页拉 /agent/v1/meta 点亮，显引擎名+模型）随换壳失联，此处以 feed 皮肤迁入
 * FeedShell 聊天档——仅 engine=agent 时渲染与探测（workflow 档端点未注册）；
 * AgentLayout 原文件不动（其死代码状态另行记录）。
 */
export function EngineBadge() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [state, setState] = useState<
    | { status: "probing" }
    | { status: "online"; meta: AgentEngineMeta }
    | { status: "offline" }
  >({ status: "probing" });

  useEffect(() => {
    let alive = true;
    getAgentMeta()
      .then((meta) => {
        if (alive) setState({ status: "online", meta });
      })
      .catch(() => {
        if (alive) setState({ status: "offline" });
      });
    return () => {
      alive = false;
    };
  }, []);

  const label =
    state.status === "online" ? state.meta.framework : state.status === "probing" ? (zh ? "探测中" : "probing") : zh ? "离线" : "offline";

  return (
    <span className="flex items-center gap-1.5 rounded-full border border-[var(--feed-line)] bg-white px-2.5 py-[5px] text-[11px] font-medium text-[var(--feed-text-secondary)]">
      <span
        aria-hidden="true"
        className={
          state.status === "online"
            ? "h-[7px] w-[7px] rounded-full bg-[#1F8F4D]"
            : state.status === "probing"
              ? "h-[7px] w-[7px] animate-pulse rounded-full bg-[#C7A008]"
              : "h-[7px] w-[7px] rounded-full bg-[#B91C1C]"
        }
      />
      <span className="max-w-[130px] truncate font-mono">{label}</span>
      {state.status === "online" && state.meta.model ? (
        <span className="max-w-[150px] truncate font-mono text-[var(--feed-text-tertiary)]">{state.meta.model}</span>
      ) : null}
    </span>
  );
}
