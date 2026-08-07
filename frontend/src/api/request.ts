import axios from 'axios';
import { message } from 'antd';
import type { ApiResult } from '../types/api';
import { redirectToLogin } from '../utils/authRedirect';

declare module 'axios' {
  export interface AxiosRequestConfig {
    publicRequest?: boolean;
  }
}

const request = axios.create({
  baseURL: '',
  timeout: 30000,
});

request.interceptors.response.use(
  (response) => {
    const result = response.data as ApiResult<unknown>;
    if (isFailureResult(result)) {
      if (isCurrentUserRequest(response.config.url)) {
        return { authenticated: false, permissions: [], menus: [] };
      }
      if (isAuthExpiredResult(result)) {
        if (response.config.publicRequest) {
          return Promise.reject(new Error(result.message || '请先登录'));
        }
        redirectToLogin();
        return Promise.reject(new Error(result.message || '请先登录'));
      }
      if (response.config.publicRequest) {
        return Promise.reject(new Error(result.message || '请求失败'));
      }
      message.error(result.message || '请求失败');
      return Promise.reject(new Error(result.message));
    }
    return result?.data ?? response.data;
  },
  (error) => {
    const result = error.response?.data as ApiResult<unknown> | undefined;
    if (isCurrentUserRequest(error.config?.url)) {
      return { authenticated: false, permissions: [], menus: [] };
    }
    if (error.response?.status === 401 || (result && isAuthExpiredResult(result))) {
      if (error.config?.publicRequest) {
        return Promise.reject(error);
      }
      redirectToLogin();
      return Promise.reject(error);
    }
    if (error.config?.publicRequest) {
      return Promise.reject(error);
    }
    message.error(result?.message || error.message || '请求异常');
    return Promise.reject(error);
  },
);

function isFailureResult(result?: ApiResult<unknown>) {
  if (!result || typeof result !== 'object') {
    return false;
  }
  if (result.success === false) {
    return true;
  }
  return Boolean(result.code && result.code !== 'SUCCESS');
}

function isAuthExpiredResult(result: ApiResult<unknown>) {
  return result.code === 'AUTH_ERROR' && (result.subCode === 'UNAUTHORIZED' || result.message === '请先登录');
}

function isCurrentUserRequest(url?: string) {
  return url === '/api/auth/me';
}

export default request;
