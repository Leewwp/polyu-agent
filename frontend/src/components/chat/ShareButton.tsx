import * as React from "react";
import { Share2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { createShare } from "@/services/shareService";

interface ShareButtonProps {
  messageId: string;
  className?: string;
}

/**
 * 助手消息"分享"按钮：创建不可变快照并把公开链接复制到剪贴板。
 * 后端 flag（rag.share.enabled）默认关，未启用时请求 404 → 提示功能未开启。
 */
export function ShareButton({ messageId, className }: ShareButtonProps) {
  const [creating, setCreating] = React.useState(false);

  const handleShare = async () => {
    if (creating) return;
    setCreating(true);
    try {
      const created = await createShare(messageId);
      const url = `${window.location.origin}/share/${created.token}`;
      await navigator.clipboard.writeText(url);
      toast.success("分享链接已复制 / Share link copied");
    } catch (error) {
      const message = error instanceof Error ? error.message : "";
      if (/404|Cannot|Not Found/i.test(message) || !message) {
        toast.error("分享功能未开启 / Sharing is not enabled");
      } else {
        toast.error(message);
      }
    } finally {
      setCreating(false);
    }
  };

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={handleShare}
      disabled={creating}
      aria-label="分享 / Share"
      title="分享这次回答 / Share this answer"
      className={cn(
        "h-7 w-7 rounded-md hover:bg-[#F5F5F5]",
        "text-[#999999] hover:text-[#666666]",
        className
      )}
    >
      <Share2 className="h-4 w-4" />
    </Button>
  );
}
