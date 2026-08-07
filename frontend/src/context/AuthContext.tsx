import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Spin } from 'antd';
import {
  getCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  setupInitialAdmin as setupInitialAdminRequest,
} from '../api/lingxi';
import type { CurrentUser, InitialAdminSetupRequest, LoginRequest } from '../types/api';
import { AUTH_EXPIRED_EVENT, resetLoginRedirecting } from '../utils/authRedirect';

interface AuthContextValue {
  user?: CurrentUser;
  loading: boolean;
  authenticated: boolean;
  login: (request: LoginRequest) => Promise<CurrentUser>;
  setupInitialAdmin: (request: InitialAdminSetupRequest) => Promise<CurrentUser>;
  logout: () => Promise<void>;
  reload: () => Promise<void>;
  hasPermission: (permission?: string) => boolean;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

/**
 * 当前登录用户上下文。
 *
 * @param children 页面内容
 * @return 认证上下文 Provider
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser>();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    reload().finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    function clearSession() {
      setUser(anonymousUser());
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, clearSession);
    return () => window.removeEventListener(AUTH_EXPIRED_EVENT, clearSession);
  }, []);

  async function reload() {
    const current = await getCurrentUser();
    setUser(current);
  }

  async function login(request: LoginRequest) {
    const current = await loginRequest(request);
    resetLoginRedirecting();
    setUser(current);
    return current;
  }

  async function setupInitialAdmin(request: InitialAdminSetupRequest) {
    const current = await setupInitialAdminRequest(request);
    resetLoginRedirecting();
    setUser(current);
    return current;
  }

  async function logout() {
    await logoutRequest();
    setUser(anonymousUser());
  }

  function hasPermission(permission?: string) {
    return !permission || !!user?.permissions?.includes(permission);
  }

  const value = useMemo<AuthContextValue>(() => ({
    user,
    loading,
    authenticated: !!user?.authenticated,
    login,
    setupInitialAdmin,
    logout,
    reload,
    hasPermission,
  }), [user, loading]);

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-app">
        <Spin />
      </div>
    );
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

function anonymousUser(): CurrentUser {
  return { authenticated: false, permissions: [], menus: [] };
}

/**
 * 获取当前登录用户上下文。
 *
 * @return 认证上下文
 * @throws Error Provider 未挂载时抛出
 */
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('AuthProvider 未挂载');
  }
  return context;
}
