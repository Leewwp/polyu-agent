import * as React from "react";
import { Link } from "react-router-dom";
import { LogIn, UserRound } from "lucide-react";

import { useOptionalFeedLang } from "@/components/feed/feedLang";
import { fetchGuestQuota } from "@/services/authService";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import { cn } from "@/lib/utils";

/**
 * 游客试用状态徽章（「游客试用状态可见」）：游客会话在输入区上方常驻，
 * 展示今日剩余次数；次数用尽转灰并引导注册。非游客不渲染。
 * 语言：跟随全局语言 pill（壳外单测场景回退中文）。
 */
export function GuestStatusBadge() {
  const { lang } = useOptionalFeedLang();
  const zh = lang === "zh";
  const role = useAuthStore((state) => state.user?.role);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const [remaining, setRemaining] = React.useState<number | null>(null);
  const [dailyLimit, setDailyLimit] = React.useState<number | null>(null);

  const isGuest = role === "guest";

  const refresh = React.useCallback(() => {
    if (!isGuest) return;
    fetchGuestQuota()
      .then((info) => {
        setRemaining(info?.remaining ?? null);
        setDailyLimit(info?.dailyLimit ?? null);
      })
      .catch(() => null);
  }, [isGuest]);

  React.useEffect(() => {
    refresh();
  }, [refresh]);

  // 每轮问答结束（含被拒/失败）后刷新余量，让计数即时反映
  const wasStreaming = React.useRef(false);
  React.useEffect(() => {
    if (wasStreaming.current && !isStreaming) {
      refresh();
    }
    wasStreaming.current = isStreaming;
  }, [isStreaming, refresh]);

  if (!isGuest) {
    return null;
  }

  const exhausted = remaining !== null && remaining <= 0;
  return (
    <div
      className={cn(
        "flex items-center gap-2 rounded-full border px-3 py-1 text-xs",
        exhausted
          ? "border-amber-200 bg-amber-50 text-amber-700"
          : "border-[#E5E5E5] bg-[#F9F9F9] text-[#666666]"
      )}
      role="status"
      aria-label={zh ? "游客试用状态" : "Guest trial status"}
    >
      <UserRound className="h-3.5 w-3.5 shrink-0" />
      <span className="min-w-0 truncate">
        {zh ? "游客试用" : "Guest trial"}
        {remaining !== null && dailyLimit !== null ? (
          <span className="font-medium">
            {zh ? (
              <>
                {" "}
                · 今日剩余 {remaining}/{dailyLimit} 次
              </>
            ) : (
              <>
                {" "}
                · {remaining}/{dailyLimit} left today
              </>
            )}
          </span>
        ) : null}
        {exhausted ? (zh ? " · 次数已用完" : " · Daily limit reached") : ""}
      </span>
      <Link
        to="/register"
        className="inline-flex shrink-0 items-center gap-1 rounded-full border border-[#D4D4D4] bg-white px-2 py-0.5 text-[11px] font-medium text-[#3B82F6] transition-colors hover:bg-[#F5F5F5]"
      >
        <LogIn className="h-3 w-3" />
        {zh ? "注册" : "Sign up"}
      </Link>
    </div>
  );
}
