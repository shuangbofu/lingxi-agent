import { useEffect, useMemo, useState } from 'react';
import { Button, Dropdown, Layout, Menu } from 'antd';
import { ClockCounterClockwise, Cpu, Sidebar, SignOut, Sparkle, SquaresFour, UserCircle } from '@phosphor-icons/react';
import type { ItemType } from 'antd/es/menu/interface';
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useThemeMode } from '../context/ThemeContext';
import type { MenuItem } from '../types/api';
import { loginPathWithRedirect } from '../utils/authRedirect';
import {
  ADMIN_MENU_LAYOUT_EVENT,
  ADMIN_MENU_LAYOUT_KEY,
  type AdminMenuLayout,
} from '../utils/adminMenuLayout';
import { AppBrandText } from './AppBrandText';
import { AppLogo } from './AppLogo';
import { NavIcon, type NavIconName, type NavIconTone } from './NavIcon';
import { ThemeControl } from './ThemeControl';
import { UserAvatar } from './UserAvatar';

/**
 * 管理端独立布局，负责管理路由菜单、管理顶栏和管理内容区。
 *
 * @return 管理端页面布局
 */
export function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const { mode } = useThemeMode();
  const [collapsed, setCollapsed] = useState(() => window.localStorage.getItem('workbench:sider-collapsed') !== 'false');
  const [menuLayout, setMenuLayout] = useState<AdminMenuLayout>(
    () => window.localStorage.getItem(ADMIN_MENU_LAYOUT_KEY) === 'top' ? 'top' : 'side',
  );
  const menuItems = (user?.menus || []).filter((item) => item.path !== '/');
  const selectedKey = activeMenuKey(location.pathname, menuItems);
  const displayName = user?.displayName || user?.username || '未登录';
  const sideWidth = collapsed ? 88 : 236;
  const topMenu = menuLayout === 'top';
  const accountMenuItems: ItemType[] = [
    { key: 'history', label: '历史记录', icon: <ClockCounterClockwise size={16} weight="fill" /> },
    { key: 'models', label: '模型配置', icon: <Cpu size={16} weight="fill" /> },
    { key: 'profile', label: '个人中心', icon: <UserCircle size={16} weight="fill" /> },
    { key: 'logout', label: '退出登录', icon: <SignOut size={16} weight="fill" /> },
  ];
  const topRouteItems = useMemo<ItemType[]>(() => menuItems.map((item) => ({
    key: item.path,
    label: item.shortLabel,
    icon: <NavIcon name={item.icon as NavIconName} tone={item.tone as NavIconTone} />,
  })), [menuItems]);
  const sideRouteItems = useMemo<ItemType[]>(() => menuItems.map((item) => {
    const navIcon = <NavIcon name={item.icon as NavIconName} tone={item.tone as NavIconTone} />;
    if (!collapsed) {
      return { key: item.path, label: item.label, icon: navIcon };
    }
    return {
      key: item.path,
      icon: null,
      label: (
        <span className="portal-side-nav-collapsed-item" title={item.label}>
          {navIcon}
          <span>{item.shortLabel}</span>
        </span>
      ),
    };
  }), [collapsed, menuItems]);

  useEffect(() => {
    const syncMenuLayout = () => {
      setMenuLayout(window.localStorage.getItem(ADMIN_MENU_LAYOUT_KEY) === 'top' ? 'top' : 'side');
    };
    window.addEventListener(ADMIN_MENU_LAYOUT_EVENT, syncMenuLayout);
    return () => window.removeEventListener(ADMIN_MENU_LAYOUT_EVENT, syncMenuLayout);
  }, []);

  if (!user?.authenticated) {
    return <Navigate to={loginPathWithRedirect(location)} replace />;
  }

  function toggleCollapsed() {
    setCollapsed((current) => {
      window.localStorage.setItem('workbench:sider-collapsed', String(!current));
      return !current;
    });
  }

  async function handleAccountMenu(key: string) {
    if (key === 'history') {
      navigate('/history');
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

  return (
    <Layout
      className={`portal-shell ${topMenu ? 'portal-shell--admin-top' : `portal-shell--side ${collapsed ? 'portal-shell--side-collapsed' : ''}`} min-h-screen bg-app`}
    >
      {!topMenu && (
        <Layout.Sider width={sideWidth} className="portal-sider">
          <div className="portal-sider-brand">
            <div className="app-brand">
              <div className="app-logo"><AppLogo /></div>
              <div className="min-w-0"><AppBrandText className="app-brand-name" /></div>
            </div>
          </div>
          <Button
            type="text"
            className="portal-sider-collapse-button"
            onClick={toggleCollapsed}
            title={collapsed ? '展开菜单' : '折叠菜单'}
          >
            <Sidebar className="portal-sider-collapse-icon" size={17} />
          </Button>
          <Menu
            mode="inline"
            theme={mode}
            items={sideRouteItems}
            selectedKeys={selectedKey ? [selectedKey] : []}
            onClick={({ key }) => navigate(key)}
            className="portal-side-nav"
          />
        </Layout.Sider>
      )}
      <Layout className={topMenu ? 'portal-admin-main min-h-0 flex-1 bg-transparent' : 'portal-admin-main min-h-screen bg-transparent'}>
        <header className={topMenu ? 'top-lingxi-header top-lingxi-header--admin top-lingxi-header--admin-top' : 'top-lingxi-header top-lingxi-header--admin'}>
          {topMenu ? (
            <button className="top-lingxi-brand portal-admin-top-brand" type="button" onClick={() => navigate('/')}>
              <span className="top-lingxi-logo"><AppLogo /></span>
              <AppBrandText className="portal-admin-top-name" englishName="管理控制台 · LINGXI ADMIN" />
            </button>
          ) : (
            <div className="portal-admin-title">
              <SquaresFour size={19} weight="fill" />
              <span>管理端</span>
            </div>
          )}
          {topMenu && (
            <Menu
              mode="horizontal"
              theme={mode}
              items={topRouteItems}
              selectedKeys={selectedKey ? [selectedKey] : []}
              onClick={({ key }) => navigate(key)}
              className="portal-top-route-nav"
            />
          )}
          <div className="top-user-actions">
            <Button type="text" className="portal-mode-switch" icon={<Sparkle size={17} weight="fill" />} onClick={() => navigate('/')}>
              返回分析入口
            </Button>
            <ThemeControl placement="bottomRight" />
            <Dropdown menu={{ items: accountMenuItems, onClick: ({ key }) => handleAccountMenu(String(key)) }} placement="bottomRight" trigger={['click']}>
              <button className="top-user-panel top-user-panel--avatar-only" type="button" title={displayName}>
                <UserAvatar className="portal-user-avatar" avatarUrl={user.avatarUrl} name={displayName} />
              </button>
            </Dropdown>
          </div>
        </header>
        <Layout.Content className={topMenu ? 'app-content app-content--top app-content--admin-top' : 'app-content app-content--top'}>
          <div className="app-page"><Outlet /></div>
        </Layout.Content>
      </Layout>
    </Layout>
  );
}

function activeMenuKey(pathname: string, menus: MenuItem[]) {
  const exact = menus.find((menu) => menu.path === pathname);
  if (exact) return exact.path;
  return menus
    .filter((menu) => pathname.startsWith(`${menu.path}/`))
    .sort((left, right) => right.path.length - left.path.length)[0]?.path || null;
}
