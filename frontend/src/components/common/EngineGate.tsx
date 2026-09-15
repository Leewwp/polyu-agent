import * as React from "react";

import { Loading } from "@/components/common/Loading";
import { AgentChatPage } from "@/pages/AgentChatPage";
import { ChatPage } from "@/pages/ChatPage";
import { useEngineStore } from "@/stores/engineStore";

export function EngineGate() {
  const engineType = useEngineStore((state) => state.engineType);
  const error = useEngineStore((state) => state.error);
  const initialize = useEngineStore((state) => state.initialize);

  React.useEffect(() => {
    initialize().catch(() => null);
  }, [initialize]);

  if (!engineType) {
    if (error) {
      // 探测失败不再静默回退 workflow（原回退曾使非 admin 全落 workflow），
      // 呈现行内错误态+重试入口，替代永久 Loading
      return (
        <div className="flex h-screen flex-col items-center justify-center gap-3 bg-[var(--feed-bg)] text-[var(--feed-text-secondary)]">
          <p className="text-[13.5px]">{error}</p>
          <button
            type="button"
            className="rounded-full border border-[var(--polyu-red)] bg-white px-4 py-1.5 text-[13px] font-semibold text-[var(--polyu-red)] transition-colors hover:bg-[var(--polyu-red-50)]"
            onClick={() => initialize().catch(() => null)}
          >
            重试
          </button>
        </div>
      );
    }
    return (
      <div className="flex h-screen items-center justify-center bg-white">
        <Loading />
      </div>
    );
  }

  return engineType === "agent" ? <AgentChatPage /> : <ChatPage />;
}
