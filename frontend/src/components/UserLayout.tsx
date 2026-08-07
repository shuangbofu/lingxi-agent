import { Button, Dropdown, Layout } from 'antd';
import { ClockCounterClockwise, Cpu, SignOut, SquaresFour, UserCircle } from '@phosphor-icons/react';
import type { ItemType } from 'antd/es/menu/interface';
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { usePageTransitionNavigate } from '../hooks/usePageTransitionNavigate';
import { loginPathWithRedirect } from '../utils/authRedirect';
import { AppBrandText } from './AppBrandText';
import { AppLogo } from './AppLogo';
import { ThemeControl } from './ThemeControl';
import { UserAvatar } from './UserAvatar';

/**
 * 用户端独立布局，负责分析入口、历史记录和个人中心页面。
 *
 * @return 用户端页面布局
 */
export function UserLayout() {
  const navigate = useNavigate();
  const transitionNavigate = usePageTransitionNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const displayName = user?.displayName || user?.username || '未登录';
  const accountPage = location.pathname.startsWith('/profile');
  const firstAdminMenuPath = user?.menus?.find((item) => item.path !== '/')?.path || '/admin';
  const accountMenuItems: ItemType[] = [
    { key: 'history', label: '历史记录', icon: <ClockCounterClockwise size={16} weight="fill" /> },
    { key: 'models', label: '模型配置', icon: <Cpu size={16} weight="fill" /> },
    { key: 'profile', label: '个人中心', icon: <UserCircle size={16} weight="fill" /> },
    { key: 'logout', label: '退出登录', icon: <SignOut size={16} weight="fill" /> },
  ];

  if (!user?.authenticated) {
    return <Navigate to={loginPathWithRedirect(location)} replace />;
  }

  async function handleAccountMenu(key: string) {
    if (key === 'history') {
      transitionNavigate('/history', { direction: location.pathname.startsWith('/runs/') ? 'backward' : 'forward' });
      return;
    }
    if (key === 'models') {
      navigate('/profile/models', { state: { from: `${location.pathname}${location.search}` } });
      return;
    }
    if (key === 'profile') {
      navigate('/profile', { state: { from: `${location.pathname}${location.search}` } });
      return;
    }
    if (key === 'logout') {
      await logout();
      navigate('/login', { replace: true });
    }
  }

  function handleBrandClick() {
    if (location.pathname !== '/') {
      transitionNavigate('/', { direction: 'backward' });
    }
  }

  return (
    <Layout className="portal-shell portal-shell--top min-h-screen bg-app">
      <header className={accountPage ? 'top-lingxi-header top-lingxi-header--account' : 'top-lingxi-header top-lingxi-header--ask'}>
        <button className="top-lingxi-brand" type="button" onClick={handleBrandClick}>
          <span className="top-lingxi-logo"><AppLogo /></span>
          <AppBrandText className="top-lingxi-name" />
        </button>
        <div className="top-user-actions">
          {user.role === 'ADMIN' && (
            <Button type="text" className="portal-mode-switch" icon={<SquaresFour size={17} weight="fill" />} onClick={() => navigate(firstAdminMenuPath)}>
              进入管理
            </Button>
          )}
          <ThemeControl placement="bottomRight" />
          <Dropdown menu={{ items: accountMenuItems, onClick: ({ key }) => handleAccountMenu(String(key)) }} placement="bottomRight" trigger={['click']}>
            <button className="top-user-panel top-user-panel--avatar-only" type="button" title={displayName}>
              <UserAvatar className="portal-user-avatar" avatarUrl={user.avatarUrl} name={displayName} />
            </button>
          </Dropdown>
        </div>
      </header>
      <Layout className="min-h-0 flex-1 bg-transparent">
        <Layout.Content className="app-content app-content--top">
          <div className="app-page"><Outlet /></div>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
