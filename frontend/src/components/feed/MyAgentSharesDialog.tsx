import * as React from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { useFeedLang } from "@/components/feed/feedLang";
import { listMyAgentShares, revokeAgentShare } from "@/services/agentShareService";
import type { AgentShareMineItem } from "@/services/agentShareService";

/**
 * 「我的分享」最小管理面（issue #82）：UserMenu 入口→对话框式列表
 * （标题预览/状态/过期时间/消息条数）+ 行内撤销钮。flag 关时列表 404 → 空态文案。
 */
export function MyAgentSharesDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [items, setItems] = React.useState<AgentShareMineItem[] | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [revoking, setRevoking] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setItems(null);
    listMyAgentShares()
      .then((data) => {
        if (!cancelled) setItems(data);
      })
      .catch(() => {
        if (!cancelled) setItems([]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  const handleRevoke = async (token: string) => {
    if (revoking) return;
    setRevoking(token);
    try {
      await revokeAgentShare(token);
      setItems((prev) =>
        prev ? prev.map((item) => (item.token === token ? { ...item, status: "REVOKED" } : item)) : prev
      );
      toast.success(zh ? "已撤销" : "Revoked");
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      if (/404|Cannot|Not Found/i.test(message) || !message) {
        toast.error(zh ? "分享功能未开启" : "Sharing is not enabled");
      } else {
        toast.error(message);
      }
    } finally {
      setRevoking(null);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => (!next ? onClose() : undefined)}>
      <DialogContent className="max-w-[440px]">
        <DialogHeader>
          <DialogTitle>{zh ? "我的分享" : "My shares"}</DialogTitle>
          <DialogDescription>
            {zh ? "你分享出去的对话快照；撤销后链接立即失效。" : "Conversation snapshots you have shared; revoking invalidates the link immediately."}
          </DialogDescription>
        </DialogHeader>
        <div className="max-h-[320px] space-y-2 overflow-y-auto">
          {loading ? (
            <div className="py-6 text-center text-[12.5px] text-[var(--feed-text-tertiary)]">
              {zh ? "加载中…" : "Loading…"}
            </div>
          ) : !items || items.length === 0 ? (
            <div className="py-6 text-center text-[12.5px] text-[var(--feed-text-tertiary)]">
              {zh ? "还没有分享过对话" : "No shared conversations yet"}
            </div>
          ) : (
            items.map((item) => {
              const revoked = item.status === "REVOKED";
              return (
                <div
                  key={item.token}
                  className="flex items-center gap-2.5 rounded-xl border border-[var(--feed-line-soft)] p-3"
                >
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-[13px] font-medium text-[var(--feed-text-primary)]">
                      {item.titlePreview || (zh ? "新对话" : "New chat")}
                    </div>
                    <div className="mt-0.5 text-[11.5px] text-[var(--feed-text-tertiary)]">
                      {revoked
                        ? zh ? "已撤销" : "Revoked"
                        : item.expireTime
                          ? `${zh ? "过期于" : "Expires"} ${new Date(item.expireTime).toLocaleDateString()}`
                          : zh ? "永不过期" : "Never expires"}
                    </div>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={revoked || revoking === item.token}
                    onClick={() => handleRevoke(item.token)}
                    className="h-7 px-2.5 text-[12px]"
                  >
                    {revoking === item.token
                      ? zh ? "撤销中…" : "Revoking…"
                      : revoked
                        ? zh ? "已撤销" : "Revoked"
                        : zh ? "撤销" : "Revoke"}
                  </Button>
                </div>
              );
            })
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
