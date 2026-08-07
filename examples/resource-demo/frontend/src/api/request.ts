import axios from 'axios';
import { message } from 'antd';
import type { Result } from '../types';

const request = axios.create({
  baseURL: '/api',
  timeout: 20_000,
});

request.interceptors.response.use(
  (response) => {
    const result = response.data as Result<unknown>;
    if (result?.code && result.code !== 'SUCCESS') {
      const error = new Error(result.message || '请求处理失败');
      message.error(error.message);
      return Promise.reject(error);
    }
    return result?.code === 'SUCCESS' ? result.data : response.data;
  },
  (error) => {
    const text = error.response?.data?.message || error.message || '网络请求失败';
    message.error(text);
    return Promise.reject(error);
  },
);

export default request;
