import { Navigate, createBrowserRouter } from "react-router-dom";

import { LoginPage } from "@/pages/LoginPage";
import { RegisterPage } from "@/pages/RegisterPage";
import { ForgotPasswordPage } from "@/pages/ForgotPasswordPage";
import { EngineGate } from "@/components/common/EngineGate";
import { ChangeLogsPage } from "@/pages/ChangeLogsPage";
import { DocPreviewPage } from "@/pages/DocPreviewPage";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { SharePage } from "@/pages/SharePage";
import { AgentSharePage } from "@/pages/AgentSharePage";
import { PrivacyPage } from "@/pages/PrivacyPage";
import { TermsPage } from "@/pages/TermsPage";
import { DisclaimerPage } from "@/pages/DisclaimerPage";
import { FeedPage } from "@/pages/FeedPage";
import { HotRankPage } from "@/pages/HotRankPage";
import { TopicsPage } from "@/pages/TopicsPage";
import { TopicDetailPage } from "@/pages/TopicDetailPage";
import { NewsDetailPage } from "@/pages/NewsDetailPage";
import { AdminLayout } from "@/pages/admin/AdminLayout";
import { DashboardPage } from "@/pages/admin/dashboard/DashboardPage";
import { KnowledgeListPage } from "@/pages/admin/knowledge/KnowledgeListPage";
import { KnowledgeDocumentsPage } from "@/pages/admin/knowledge/KnowledgeDocumentsPage";
import { KnowledgeChunksPage } from "@/pages/admin/knowledge/KnowledgeChunksPage";
import { KnowledgeGraphPage } from "@/pages/admin/knowledge-graph/KnowledgeGraphPage";
import { BizChangeLogPage } from "@/pages/admin/change-logs/BizChangeLogPage";
import { IntentTreePage } from "@/pages/admin/intent-tree/IntentTreePage";
import { IntentListPage } from "@/pages/admin/intent-tree/IntentListPage";
import { IntentEditPage } from "@/pages/admin/intent-tree/IntentEditPage";
import { IngestionPage } from "@/pages/admin/ingestion/IngestionPage";
import { RagTracePage } from "@/pages/admin/traces/RagTracePage";
import { RagTraceDetailPage } from "@/pages/admin/traces/RagTraceDetailPage";
import { SystemSettingsPage } from "@/pages/admin/settings/SystemSettingsPage";
import { SampleQuestionPage } from "@/pages/admin/sample-questions/SampleQuestionPage";
import { FeedbackAdminPage } from "@/pages/admin/feedback/FeedbackAdminPage";
import { AboutAdminPage } from "@/pages/admin/about/AboutAdminPage";
import { AboutPage } from "@/pages/AboutPage";
import { QueryTermMappingPage } from "@/pages/admin/query-term-mapping/QueryTermMappingPage";
import { AgentProfilePage } from "@/pages/admin/agents/AgentProfilePage";
import { AgentPromptPage } from "@/pages/admin/agents/AgentPromptPage";
import { AgentSkillPage } from "@/pages/admin/agent-skills/AgentSkillPage";
import { AgentSkillEditPage } from "@/pages/admin/agent-skills/AgentSkillEditPage";
import { UserListPage } from "@/pages/admin/users/UserListPage";
import { useAuthStore } from "@/stores/authStore";

function RequireAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return children;
}

function RequireAdmin({ children }: { children: JSX.Element }) {
  const user = useAuthStore((state) => state.user);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (user?.role !== "admin") {
    return <Navigate to="/chat" replace />;
  }

  return children;
}

export function RedirectIfAuth({ children }: { children: JSX.Element }) {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isGuest = useAuthStore((state) => state.isGuest);
  // 游客会话不算「已登录」（2026-09-14 修复）：游客铸号后
  // isAuthenticated=true，若照旧重定向 /chat，登录/注册/找回三页对游客永远不可达。
  // 正式用户会话维持原语义（访问登录页自动跳 /chat）。
  if (isAuthenticated && !isGuest) {
    return <Navigate to="/chat" replace />;
  }
  return children;
}

export const router = createBrowserRouter([
  {
    // 公开资讯首页：匿名直见当日理大资讯流，裸路由无守卫
    // （法务页范式）；不触发 engineStore 探测。/chat 与守卫路由原样不动。
    path: "/",
    element: <FeedPage />
  },
  {
    // 公开热点榜：Top10 排行+标签+信源名单气泡+方法论注脚，
    // 裸路由无守卫（FeedPage 范式）
    path: "/hot",
    element: <HotRankPage />
  },
  {
    // 公开主题地图：三维分组目录卡，裸路由无守卫（FeedPage 范式）
    path: "/topics",
    element: <TopicsPage />
  },
  {
    // 公开主题详情：近期焦点+最新动态+空态
    path: "/topics/:slug",
    element: <TopicDetailPage />
  },
  {
    // 公开资讯详情：AI 摘要档+轻量分享；裸路由无守卫（FeedPage 范式）
    path: "/news/:id",
    element: <NewsDetailPage />
  },
  {
    path: "/login",
    element: (
      <RedirectIfAuth>
        <LoginPage />
      </RedirectIfAuth>
    )
  },
  {
    // 自助注册 + 邮箱验证：公开页，后端 flag 关闭时提交报「注册通道当前未开放」
    path: "/register",
    element: (
      <RedirectIfAuth>
        <RegisterPage />
      </RedirectIfAuth>
    )
  },
  {
    // 忘记密码 / 密码重置：与注册端点同挂 flag
    path: "/forgot-password",
    element: (
      <RedirectIfAuth>
        <ForgotPasswordPage />
      </RedirectIfAuth>
    )
  },
  {
    // 公开分享页：匿名可访问（后端 flag 默认关时显示无效链接态）
    path: "/share/:token",
    element: <SharePage />
  },
  {
    // 会话分享公开页（issue #82）：匿名可访问，按时间序只读渲染对话快照；
    // 既有 /share/:token 单条路由零改动（两段路径形状不同无冲突）
    path: "/share/c/:token",
    element: <AgentSharePage />
  },
  {
    // 法务静态页：公开无守卫，匿名可访问
    path: "/privacy",
    element: <PrivacyPage />
  },
  {
    path: "/terms",
    element: <TermsPage />
  },
  {
    path: "/disclaimer",
    element: <DisclaimerPage />
  },
  {
    // 关于页：公开无守卫（doc 25；flag 关/未配置时页内出空态）
    path: "/about",
    element: <AboutPage />
  },
  {
    path: "/chat",
    element: (
      <RequireAuth>
        <EngineGate />
      </RequireAuth>
    )
  },
  {
    path: "/chat/:sessionId",
    element: (
      <RequireAuth>
        <EngineGate />
      </RequireAuth>
    )
  },
  {
    path: "/change-logs",
    element: (
      <RequireAuth>
        <ChangeLogsPage />
      </RequireAuth>
    )
  },
  {
    path: "/preview/doc/:docId",
    element: (
      <RequireAuth>
        <DocPreviewPage />
      </RequireAuth>
    )
  },
  {
    path: "/admin",
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [
      {
        index: true,
        element: <Navigate to="/admin/dashboard" replace />
      },
      {
        path: "dashboard",
        element: <DashboardPage />
      },
      {
        path: "knowledge",
        element: <KnowledgeListPage />
      },
      {
        path: "knowledge/:kbId",
        element: <KnowledgeDocumentsPage />
      },
      {
        path: "knowledge/:kbId/docs/:docId",
        element: <KnowledgeChunksPage />
      },
      {
        path: "knowledge-graph",
        element: <KnowledgeGraphPage />
      },
      {
        path: "intent-tree",
        element: <IntentTreePage />
      },
      {
        path: "intent-list",
        element: <IntentListPage />
      },
      {
        path: "intent-list/:id/edit",
        element: <IntentEditPage />
      },
      {
        path: "ingestion",
        element: <IngestionPage />
      },
      {
        path: "traces",
        element: <RagTracePage />
      },
      {
        path: "traces/:traceId",
        element: <RagTraceDetailPage />
      },
      {
        path: "change-logs",
        element: <BizChangeLogPage />
      },
      {
        path: "settings",
        element: <SystemSettingsPage />
      },
      {
        path: "sample-questions",
        element: <SampleQuestionPage />
      },
      {
        path: "feedback",
        element: <FeedbackAdminPage />
      },
      {
        path: "about",
        element: <AboutAdminPage />
      },
      {
        path: "mappings",
        element: <QueryTermMappingPage />
      },
      {
        path: "agents",
        element: <AgentProfilePage />
      },
      {
        path: "agents/:agentId",
        element: <AgentPromptPage />
      },
      {
        path: "agent-skills",
        element: <AgentSkillPage />
      },
      {
        path: "agent-skills/:skillId",
        element: <AgentSkillEditPage />
      },
      {
        path: "users",
        element: <UserListPage />
      }
    ]
  },
  {
    path: "*",
    element: <NotFoundPage />
  }
]);
