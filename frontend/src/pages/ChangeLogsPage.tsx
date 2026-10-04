import { Link } from "react-router-dom";
import { ArrowLeft, MessageSquare } from "lucide-react";

import { Button } from "@/components/ui/button";
import { BizChangeLogPage } from "@/pages/admin/change-logs/BizChangeLogPage";
import { useAdminUiTheme } from "@/hooks/useAdminUiTheme";

export function ChangeLogsPage() {
  // #228：无 AdminLayout 根的独立后台路由，Portal 弹层轨同样接后台主题（随路由清理）
  useAdminUiTheme();
  return (
    <div className="admin-layout min-h-screen bg-slate-50">
      <div className="admin-main min-h-screen">
        <header className="admin-topbar">
          <div className="admin-topbar-inner">
            <div className="flex items-center gap-3">
              <Link to="/chat">
                <Button variant="outline" size="sm">
                  <ArrowLeft className="mr-2 h-4 w-4" />
                  返回聊天
                </Button>
              </Link>
              <div>
                <div className="text-sm font-semibold text-slate-900">PolyUGuide</div>
                <div className="text-xs text-slate-500">业务变更审计</div>
              </div>
            </div>
            <Link to="/chat">
              <Button variant="ghost" size="sm">
                <MessageSquare className="mr-2 h-4 w-4" />
                对话
              </Button>
            </Link>
          </div>
        </header>
        <main className="admin-content">
          <BizChangeLogPage />
        </main>
      </div>
    </div>
  );
}
