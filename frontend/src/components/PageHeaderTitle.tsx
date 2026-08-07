import { useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import type { MenuItem } from '../types/api';
import { NavIcon, type NavIconName, type NavIconTone } from './NavIcon';

interface PageHeaderTitleProps {
  fallback: string;
  path?: string;
  icon?: NavIconName;
  tone?: NavIconTone;
}

/**
 * 根据菜单元数据渲染页面标题，避免页面标题和左侧菜单各写一套图标颜色。
 *
 * @param fallback 没有匹配菜单时展示的标题
 * @param path 指定菜单路径；不传时使用当前路由
 * @param icon 没有匹配菜单时使用的图标
 * @param tone 没有匹配菜单时使用的颜色
 * @return 页面标题节点
 */
export function PageHeaderTitle({ fallback, path, icon = 'analysis', tone = 'blue' }: PageHeaderTitleProps) {
  const location = useLocation();
  const { user } = useAuth();
  const menu = findMenuByPath(user?.menus || [], path || location.pathname);
  const iconName = (menu?.icon as NavIconName | undefined) || icon;
  const iconTone = (menu?.tone as NavIconTone | undefined) || tone;
  return (
    <>
      <NavIcon name={iconName} tone={iconTone} />
      <span>{menu?.label || fallback}</span>
    </>
  );
}

function findMenuByPath(menus: MenuItem[], path: string) {
  return menus
    .filter((menu) => menu.path !== '/' && (path === menu.path || path.startsWith(`${menu.path}/`)))
    .sort((left, right) => right.path.length - left.path.length)[0];
}
