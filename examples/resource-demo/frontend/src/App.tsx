import { ApiOutlined, CloudUploadOutlined, FileTextOutlined, FolderOpenOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { Button, Menu } from 'antd';
import { useState } from 'react';
import { HashRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { ConnectionInfo } from './components/ConnectionInfo';
import { DocumentsPage } from './pages/DocumentsPage';
import { LogsPage } from './pages/LogsPage';
import { ProjectDetailPage } from './pages/ProjectDetailPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { SetupPage } from './pages/SetupPage';

function Shell() {
  const navigate = useNavigate();
  const location = useLocation();
  const [connectionOpen, setConnectionOpen] = useState(false);

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-brand">
          <span className="brand-mark">LX</span>
          <span>示例资源服务</span>
        </div>
        <Menu
          className="app-nav"
          mode="horizontal"
          selectedKeys={[location.pathname.startsWith('/projects') ? '/projects' : location.pathname]}
          onClick={({ key }) => navigate(key)}
          items={[
            { key: '/projects', icon: <FolderOpenOutlined />, label: '项目' },
            { key: '/documents', icon: <FileTextOutlined />, label: 'Markdown 文档' },
            { key: '/logs', icon: <UnorderedListOutlined />, label: '日志' },
            { key: '/setup', icon: <CloudUploadOutlined />, label: '接入 Lingxi' },
          ]}
        />
        <Button icon={<ApiOutlined />} onClick={() => setConnectionOpen(true)}>
          接入信息
        </Button>
      </header>
      <main className="app-content">
        <Routes>
          <Route path="/projects" element={<ProjectsPage />} />
          <Route path="/projects/new" element={<ProjectDetailPage />} />
          <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
          <Route path="/documents" element={<DocumentsPage />} />
          <Route path="/logs" element={<LogsPage />} />
          <Route path="/setup" element={<SetupPage />} />
          <Route path="*" element={<Navigate to="/projects" replace />} />
        </Routes>
      </main>
      <ConnectionInfo open={connectionOpen} onClose={() => setConnectionOpen(false)} />
    </div>
  );
}

export default function App() {
  return (
    <HashRouter>
      <Shell />
    </HashRouter>
  );
}
