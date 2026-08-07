import { message } from 'antd';
import type { Location } from 'react-router-dom';

export const AUTH_EXPIRED_EVENT = 'lingxi:auth-expired';

let redirectingToLogin = false;

export function loginPathWithRedirect(location: Location) {
  const current = `${location.pathname}${location.search}`;
  if (!current || current.startsWith('/login')) {
    return '/login';
  }
  return `/login?redirect=${encodeURIComponent(current)}`;
}

export function resetLoginRedirecting() {
  redirectingToLogin = false;
}

export function redirectToLogin() {
  const current = currentHashRoute();
  if (current.startsWith('/login')) {
    redirectingToLogin = false;
    return;
  }
  if (redirectingToLogin) {
    return;
  }
  redirectingToLogin = true;
  window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
  message.warning('请先登录');
  const target = loginUrl(current);
  if (window.location.href === target) {
    return;
  }
  window.location.replace(target);
}

function currentHashRoute() {
  const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : window.location.hash;
  if (!hash || !hash.startsWith('/')) {
    return '/';
  }
  return hash;
}

function loginUrl(redirect: string) {
  return `${window.location.origin}${window.location.pathname}${window.location.search}#/login?redirect=${encodeURIComponent(redirect)}`;
}
