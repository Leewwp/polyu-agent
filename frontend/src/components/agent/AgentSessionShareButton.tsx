import { Share2 } from "lucide-react";
import { toast } from "sonner";

import { useFeedLang } from "@/components/feed/feedLang";
import { awaitingConfirm, useAgentChatStore } from "@/stores/agentChatStore";
import { useEngineStore } from "@/stores/engineStore";

/**
 * Agent 会话「分享对话」顶栏入口（issue #82 起，#139 重构为 Scoped Share 弹窗入口）：
 * - 渲染条件=agent 引擎+有当前会话（游客也渲染——点击走 AgentShareDialog 登录引导，
 *   不调创建端点；后端 guest 硬阻断为双保险，issue #91 口径不变）；
 * - 不稳定态门（修订 #10）：会话流式中或停在写操作确认时可见但点击只 toast
 *   「回答完成后即可分享」——不让用户误以为正在生成的内容已进公开快照；
 * - 消息加载门（#139）：messages 尚在 loading 时入口可发现、点击提示稍候，
 *   不开弹窗不创建；ready 后恢复；
 * - 稳定态点击→agentChatStore.openShareDialog（默认 full；AgentShareDialog 承载
 *   三档范围/锚点单选/预览/成功态，创建与复制语义全部收进弹窗）。
 */
export function AgentSessionShareButton() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const engineType = useEngineStore((state) => state.engineType);
  const currentSessionId = useAgentChatStore((state) => state.currentSessionId);
  const isLoading = useAgentChatStore((state) => state.isLoading);
  const isStreaming = useAgentChatStore((state) => state.isStreaming);
  const messages = useAgentChatStore((state) => state.messages);
  const openShareDialog = useAgentChatStore((state) => state.openShareDialog);

  // 游客也渲染（登录引导）；仅引擎/会话条件决定可见性
  if (engineType !== "agent" || !currentSessionId) {
    return null;
  }

  const handleClick = () => {
    // 门序=加载→不稳定（先能答完再谈分享）→开窗；均只 toast 不开弹窗不创建
    if (isLoading) {
      toast(zh ? "正在加载对话，请稍候" : "Conversation is still loading — try again shortly");
      return;
    }
    if (isStreaming || awaitingConfirm(messages)) {
      toast(zh ? "回答完成后即可分享" : "You can share once the answer completes");
      return;
    }
    openShareDialog({ defaultScope: "full" });
  };

  return (
    <button
      type="button"
      aria-label={zh ? "分享对话" : "Share conversation"}
      title={zh ? "分享这段对话" : "Share this conversation"}
      onClick={handleClick}
      className="flex h-8 w-8 items-center justify-center rounded-lg border border-[var(--feed-line)] bg-white text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--feed-text-secondary)] max-[860px]:h-10 max-[860px]:w-10"
    >
      <Share2 className="h-4 w-4" />
    </button>
  );
}
