import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntdApp } from 'antd';
import { AuthProvider } from './context/AuthContext';
import { ThemeProvider } from './context/ThemeContext';
import { AppRouter } from './router/AppRouter';
import { APP_BROWSER_TITLE } from './constants/app';
import './styles.css';

document.title = APP_BROWSER_TITLE;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeProvider>
      <AntdApp>
        <AuthProvider>
          <AppRouter />
        </AuthProvider>
      </AntdApp>
    </ThemeProvider>
  </React.StrictMode>,
);
