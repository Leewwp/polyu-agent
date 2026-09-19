import * as React from "react";
import { Share2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import { useFeedLang } from "@/components/feed/feedLang";
import { useAgentChatStore } from "@/stores/agentChatStore";
import { useEngineStore } from "@/stores/engineStore";
import { createAgentShare } from "@/services/agentShareService";

/**
 * Agent 会话「分享对话」钮（issue #82）：挂在聊天壳当前会话头部动作区。
 * 点击→双语隐私确认弹窗→创建快照→复制公开链接→成功 toast；生成中 loading 防重。
 * 仅 agent 引擎且有当前会话时渲染；后端 flag 默认关，未启用时请求 404 → 提示功能未开启。
 */
export function AgentSessionShareButton() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const engineType = useEngineStore((state) => state.engineType);
  const currentSessionId = useAgentChatStore((state) => state.currentSessionId);
  const [confirmOpen, setConfirmOpen] = React.useState(false);
  const [creating, setCreating] = React.useState(false);

  if (engineType !== "agent" || !currentSessionId) {
    return null;
  }

  const handleCreate = async () => {
    if (creating) return;
    setCreating(true);
    try {
      const created = await createAgentShare(currentSessionId);
      const url = `${window.location.origin}/share/c/${created.token}`;
      await navigator.clipboard.writeText(url);
      toast.success(zh ? "分享链接已复制" : "Share link copied");
      setConfirmOpen(false);
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      if (/404|Cannot|Not Found|Failed to fetch/i.test(message) || !message) {
        toast.error(zh ? "分享功能未开启" : "Sharing is not enabled");
      } else {
        toast.error(message);
      }
    } finally {
      setCreating(false);
    }
  };

  return (
    <>
      <button
        type="button"
        aria-label={zh ? "分享对话" : "Share conversation"}
        title={zh ? "分享这段对话" : "Share this conversation"}
        onClick={() => setConfirmOpen(true)}
        className="flex h-8 w-8 items-center justify-center rounded-lg border border-[var(--feed-line)] bg-white text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--feed-text-secondary)]"
      >
        <Share2 className="h-4 w-4" />
      </button>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent className="max-w-[400px]">
          <DialogHeader>
            <DialogTitle>{zh ? "分享这段对话？" : "Share this conversation?"}</DialogTitle>
            <DialogDescription className="pt-1 text-left leading-relaxed">
              {zh
                ? "任何获得链接的人都可以查看这次对话的完整内容（提问与回答），链接默认 90 天有效。请确认其中不含敏感个人信息。"
                : "Anyone with the link can view the full conversation (questions and answers); the link stays valid for 90 days by default. Please make sure it contains no sensitive personal information."}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setConfirmOpen(false)} disabled={creating}>
              {zh ? "取消" : "Cancel"}
            </Button>
            <Button size="sm" onClick={handleCreate} disabled={creating} className={cn(creating && "opacity-70")}>
              {creating ? (zh ? "生成中…" : "Creating…") : zh ? "创建并复制链接" : "Create & copy link"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
