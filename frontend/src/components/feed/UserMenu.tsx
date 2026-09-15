import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";

import { useFeedLang } from "./feedLang";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";

/**
 * 顶栏身份区（2026-09-13 定稿形态）：
 * - 未登录/游客态 → 登录钮（游客身份与余量由侧栏游客卡承载，口径=游客态回落登录钮）；
 * - 已登录（user/admin）→ 头像+邮箱 chip，点击出下拉：身份信息（username 即邮箱）、
 *   管理后台（仅 admin）、退出登录（authStore.logout 清 cookie 与本地态）；
 * - desktop 档=头像+用户名 chip；mobile 档=仅头像钮，共用同一下拉。
 * 换壳丢掉的上游 Sidebar/AgentSidebar 身份区由此回补（上游文件不动）。
 */
export function UserMenu({ variant = "desktop" }: { variant?: "desktop" | "mobile" }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const logout = useAuthStore((state) => state.logout);

  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  // 外点/Esc 关闭（SourceListPopover 同款语义；下拉无悬停需求，纯点击开关）
  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: MouseEvent | TouchEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("touchstart", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("touchstart", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  // 未登录或游客身份：现状登录钮（游客卡在侧栏，不在此重复身份展示）
  const isGuest = user?.role === "guest";
  if (!isAuthenticated || isGuest || !user) {
    return (
      <Link
        to="/login"
        className={cn(
          "rounded-full border border-[var(--polyu-red)] bg-white font-semibold text-[var(--polyu-red)] transition-colors hover:bg-[var(--polyu-red-50)]",
          variant === "desktop" ? "px-[18px] py-1.5 text-[13px]" : "px-3 py-[5px] text-[12px]"
        )}
      >
        {zh ? "登录" : "Sign in"}
      </Link>
    );
  }

  const isAdmin = user.role === "admin";
  const avatar = user.avatar;
  const initial = (user.username || user.userId || "?").trim().charAt(0).toUpperCase();

  const handleLogout = () => {
    setOpen(false);
    logout().catch(() => null);
  };

  return (
    <div className="relative" ref={menuRef}>
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={zh ? "账号菜单" : "Account menu"}
        onClick={() => setOpen((value) => !value)}
        className={cn(
          "flex items-center gap-2 rounded-full border border-[var(--feed-line)] bg-white transition-colors hover:border-[var(--feed-text-secondary)]",
          variant === "desktop" ? "py-1 pl-1 pr-3.5" : "p-[3px]"
        )}
      >
        {avatar ? (
          <img
            src={avatar}
            alt=""
            className={cn(
              "flex-none rounded-full object-cover",
              variant === "desktop" ? "h-[26px] w-[26px]" : "h-[24px] w-[24px]"
            )}
          />
        ) : (
          <span
            className={cn(
              "flex flex-none items-center justify-center rounded-full bg-[var(--polyu-red)] font-bold text-white",
              variant === "desktop" ? "h-[26px] w-[26px] text-[12.5px]" : "h-[24px] w-[24px] text-[12px]"
            )}
          >
            {initial}
          </span>
        )}
        {variant === "desktop" && (
          <span className="max-w-[180px] truncate text-[12.5px] font-semibold text-[var(--feed-text-primary)]">
            {user.username || user.userId}
          </span>
        )}
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 top-[calc(100%+8px)] z-50 w-[224px] rounded-2xl border border-[var(--feed-line-soft)] bg-white p-2 shadow-[0_12px_32px_rgba(0,0,0,0.12)]"
        >
          <div className="px-3 py-2">
            <div className="truncate text-[13px] font-semibold text-[var(--feed-text-primary)]">
              {user.username || user.userId}
            </div>
            <div className="mt-0.5 flex items-center gap-1.5 text-[11px] text-[var(--feed-text-tertiary)]">
              {isAdmin && (
                <span className="rounded bg-[var(--polyu-red-50)] px-1.5 py-px font-semibold text-[var(--polyu-red)]">
                  admin
                </span>
              )}
              <span>{zh ? "已登录" : "Signed in"}</span>
            </div>
          </div>
          <div className="my-1 h-px bg-[var(--feed-line-soft)]" />
          {isAdmin && (
            <Link
              to="/admin"
              role="menuitem"
              className="block rounded-lg px-3 py-2 text-[13px] text-[var(--feed-text-secondary)] transition-colors hover:bg-[var(--feed-bg)] hover:text-[var(--feed-text-primary)]"
              onClick={() => setOpen(false)}
            >
              {zh ? "管理后台" : "Admin console"}
            </Link>
          )}
          <button
            type="button"
            role="menuitem"
            className="block w-full rounded-lg px-3 py-2 text-left text-[13px] text-[var(--feed-text-secondary)] transition-colors hover:bg-[var(--feed-bg)] hover:text-[var(--feed-text-primary)]"
            onClick={handleLogout}
          >
            {zh ? "退出登录" : "Sign out"}
          </button>
        </div>
      )}
    </div>
  );
}
