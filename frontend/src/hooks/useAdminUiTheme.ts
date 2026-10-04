import { useEffect } from "react";

/**
 * #228 Portal 弹层双轨的后台路由级主题标记：
 * dialog/alert-dialog/dropdown/select 四类弹层经 Radix Portal 直挂 document.body，
 * 不在 .admin-layout 根内、继承不到后台作用域的 CSS 变量覆盖。后台路由挂载期在
 * body 上打 data-ui-theme="admin"（globals.css 的 body[data-ui-theme="admin"]
 * 作用域把 --primary/--ring/--glow 封回紫色），body 子树（含 Portal 弹层与
 * 无后台布局根的独立路由，如 /change-logs、/preview/doc）与后台同主题；
 * 卸载即清理——用户面弹窗保持 :root 品牌红，往返导航无主题标记残留。
 */
const THEME_ATTR = "data-ui-theme";
const ADMIN_THEME = "admin";

export function useAdminUiTheme(): void {
  useEffect(() => {
    document.body.setAttribute(THEME_ATTR, ADMIN_THEME);
    return () => {
      document.body.removeAttribute(THEME_ATTR);
    };
  }, []);
}
