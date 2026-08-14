import { lazy, Suspense } from 'react';
import type { ReactNode } from 'react';
import { HashRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { AdminLayout } from '../components/AdminLayout';
import { UserLayout } from '../components/UserLayout';
import { loginPathWithRedirect } from '../utils/authRedirect';

const AskPage = lazy(() => import('../pages/AskPage').then((module) => ({ default: module.AskPage })));
const ChatPage = lazy(() => import('../pages/ChatPage').then((module) => ({ default: module.ChatPage })));
const DashboardPage = lazy(() => import('../pages/DashboardPage').then((module) => ({ default: module.DashboardPage })));
const AnalysisPremisePage = lazy(() => import('../pages/AnalysisPremisePage').then((module) => ({ default: module.AnalysisPremisePage })));
const CapabilityManagementPage = lazy(() => import('../pages/CapabilityManagementPage').then((module) => ({ default: module.CapabilityManagementPage })));
const DefinitionPage = lazy(() => import('../pages/DefinitionPage').then((module) => ({ default: module.DefinitionPage })));
const SystemSettingsPage = lazy(() => import('../pages/SystemSettingsPage').then((module) => ({ default: module.SystemSettingsPage })));
const TaskDetailPage = lazy(() => import('../pages/TaskDetailPage').then((module) => ({ default: module.TaskDetailPage })));
const TaskRecordPage = lazy(() => import('../pages/TaskRecordPage').then((module) => ({ default: module.TaskRecordPage })));
const LoginPage = lazy(() => import('../pages/LoginPage').then((module) => ({ default: module.LoginPage })));
const UserPage = lazy(() => import('../pages/UserPage').then((module) => ({ default: module.UserPage })));
const TaskSharePage = lazy(() => import('../pages/TaskSharePage').then((module) => ({ default: module.TaskSharePage })));
const TaskHistoryPage = lazy(() => import('../pages/TaskHistoryPage').then((module) => ({ default: module.TaskHistoryPage })));
const TaskRunPage = lazy(() => import('../pages/TaskRunPage').then((module) => ({ default: module.TaskRunPage })));
const ProfilePage = lazy(() => import('../pages/ProfilePage').then((module) => ({ default: module.ProfilePage })));
const TaskExecutionReportPage = lazy(() => import('../pages/TaskExecutionReportPage').then((module) => ({ default: module.TaskExecutionReportPage })));
const ModelManagementPage = lazy(() => import('../pages/ModelManagementPage').then((module) => ({ default: module.ModelManagementPage })));

export function AppRouter() {
  return (
    <HashRouter>
      <Routes>
        <Route path="/login" element={defer(<LoginPage />)} />
        <Route path="/share/:shareCode" element={defer(<TaskSharePage />)} />
        <Route path="/" element={defer(<ChatPage />)} />
        <Route path="/chat" element={defer(<ChatPage />)} />
        <Route path="/chat/:rootTaskId" element={defer(<ChatPage />)} />
        <Route element={<UserLayout />}>
          <Route path="/ask" element={defer(<AskPage />)} />
          <Route path="/history" element={defer(<TaskHistoryPage />)} />
          <Route path="/analysis" element={<Navigate to="/" replace />} />
          <Route path="/runs/:id" element={defer(<TaskRunPage />)} />
          <Route path="/runs/:id/analysis" element={<RequirePermission permission="TASK_ADMIN">{defer(<TaskExecutionReportPage />)}</RequirePermission>} />
          <Route path="/profile" element={defer(<ProfilePage />)} />
          <Route path="/profile/models" element={<RequirePermission permission="TASK_CREATE">{defer(<ModelManagementPage scope="PERSONAL" />)}</RequirePermission>} />
        </Route>
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<DefaultMenuRedirect />} />
          <Route path="dashboard" element={<RequirePermission permission="DASHBOARD_ADMIN">{defer(<DashboardPage />)}</RequirePermission>} />
          <Route path="premises" element={<RequirePermission permission="ANALYSIS_PREMISE_ADMIN">{defer(<AnalysisPremisePage />)}</RequirePermission>} />
          <Route path="analysis-records" element={<Navigate to="/admin/tasks/records" replace />} />
          <Route path="analysis-records/:id" element={<LegacyAnalysisRecordRedirect />} />
          <Route path="tasks" element={<RequirePermission permission="TASK_ADMIN">{defer(<TaskRecordPage tab="tasks" />)}</RequirePermission>} />
          <Route path="tasks/records" element={<RequirePermission permission="TASK_ADMIN">{defer(<TaskRecordPage tab="records" />)}</RequirePermission>} />
          <Route path="tasks/records/:id" element={<RequirePermission permission="TASK_ADMIN">{defer(<TaskDetailPage />)}</RequirePermission>} />
          <Route path="tasks/:id/report" element={<RequirePermission permission="TASK_ADMIN">{defer(<TaskExecutionReportPage />)}</RequirePermission>} />
          <Route path="tasks/:id" element={<RequirePermission permission="TASK_ADMIN">{defer(<TaskDetailPage />)}</RequirePermission>} />
          <Route path="scenarios" element={<RequirePermission permission="DEFINITION_ADMIN">{defer(<DefinitionPage kind="scenario" />)}</RequirePermission>} />
          <Route path="scenarios/new" element={<Navigate to="/admin/scenarios" replace />} />
          <Route path="scenarios/:code" element={<RequirePermission permission="DEFINITION_ADMIN">{defer(<DefinitionPage kind="scenario" mode="view" />)}</RequirePermission>} />
          <Route path="scenarios/:code/edit" element={<Navigate to="/admin/scenarios" replace />} />
          <Route path="capabilities" element={<RequirePermission permission="DEFINITION_ADMIN">{defer(<CapabilityManagementPage />)}</RequirePermission>} />
          <Route path="capabilities/configs" element={<Navigate to="/admin/capabilities?tab=skill" replace />} />
          <Route path="capabilities/new" element={<Navigate to="/admin/capabilities?tab=skill" replace />} />
          <Route path="capabilities/:code" element={<RequirePermission permission="DEFINITION_ADMIN">{defer(<DefinitionPage kind="capability" mode="view" />)}</RequirePermission>} />
          <Route path="capabilities/:code/edit" element={<Navigate to="/admin/capabilities?tab=skill" replace />} />
          <Route path="skills" element={<Navigate to="/admin/capabilities?tab=skill" replace />} />
          <Route path="skills/new" element={<Navigate to="/admin/capabilities?tab=skill" replace />} />
          <Route path="skills/:id" element={<Navigate to="/admin/capabilities?tab=skill" replace />} />
          <Route path="skills/:id/edit" element={<Navigate to="/admin/capabilities?tab=skill" replace />} />
          <Route path="users" element={<RequirePermission permission="USER_ADMIN">{defer(<UserPage />)}</RequirePermission>} />
          <Route path="models" element={<RequirePermission permission="SYSTEM_ADMIN">{defer(<ModelManagementPage scope="PLATFORM" />)}</RequirePermission>} />
          <Route path="settings" element={<RequirePermission permission="SYSTEM_ADMIN">{defer(<SystemSettingsPage />)}</RequirePermission>} />
        </Route>
        <Route>
          <Route path="/dashboard" element={<Navigate to="/admin/dashboard" replace />} />
          <Route path="/tasks" element={<Navigate to="/admin/tasks" replace />} />
          <Route path="/settings" element={<Navigate to="/admin/settings" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </HashRouter>
  );
}

function defer(content: ReactNode) {
  return <Suspense fallback={<div className="route-loading" role="status" aria-label="页面加载中"><span /></div>}>{content}</Suspense>;
}

function DefaultMenuRedirect() {
  const { user } = useAuth();
  const firstAdminMenu = user?.menus?.find((menu) => menu.path !== '/')?.path;
  return <Navigate to={firstAdminMenu || '/'} replace />;
}

function LegacyAnalysisRecordRedirect() {
  const location = useLocation();
  const id = location.pathname.split('/').filter(Boolean).pop();
  return <Navigate to={`/admin/tasks/records/${id || ''}`} replace />;
}

function RequirePermission({ permission, children }: { permission: string; children: JSX.Element }) {
  const location = useLocation();
  const { authenticated, hasPermission, user } = useAuth();
  if (!authenticated) {
    return <Navigate to={loginPathWithRedirect(location)} replace />;
  }
  if (!hasPermission(permission)) {
    return <Navigate to={user?.menus?.[0]?.path || '/'} replace />;
  }
  return children;
}
