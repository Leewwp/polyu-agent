import { useMemo, useState } from "react";

import { LoginPromptModal } from "@/components/feed/LoginPromptModal";
import { useFeedLang } from "@/components/feed/feedLang";
import { useChatStore } from "@/stores/chatStore";

/**
 * 游客超限弹窗桥：chatStore 已分类的 quota 类错误不再走
 * MessageItem 行内提示块，改为渲染 LoginPromptModal（标题「已达到游客使用上限」+
 * 底部注册/登录按钮）。必须挂在 FeedShell children 内（useFeedLang 依赖壳顶 Provider）。
 * 同一条 quota 消息只弹一次，关闭后若再次撞限（新消息）可再次触发。
 */
export function ChatQuotaModal() {
  const { lang } = useFeedLang();
  const messages = useChatStore((state) => state.messages);

  const quotaMessageId = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const message = messages[i];
      if (message.notice?.kind === "quota") {
        return message.id;
      }
    }
    return null;
  }, [messages]);

  const [dismissedId, setDismissedId] = useState<string | null>(null);

  if (quotaMessageId === null || quotaMessageId === dismissedId) {
    return null;
  }

  return (
    <LoginPromptModal
      open
      lang={lang}
      onClose={() => setDismissedId(quotaMessageId)}
    />
  );
}
