import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Empty, Popconfirm, Space, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { demoApi } from '../api/demo';
import { PageHeading } from '../components/PageHeading';
import type { Project } from '../types';

export function ProjectsPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setProjects(await demoApi.projects());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const removeProject = async (project: Project) => {
    await demoApi.deleteProject(project.id);
    message.success('项目已删除');
    await load();
  };

  const columns: ColumnsType<Project> = [
    {
      title: '项目', key: 'name', width: 250,
      render: (_, record) => (
        <div>
          <div className="font-medium text-slate-800">{record.name}</div>
          <div className="mt-1 text-xs text-slate-500">{record.code}</div>
        </div>
      ),
    },
    { title: '说明', dataIndex: 'description', ellipsis: true },
    { title: '环境', width: 86, render: (_, record) => <span className="status-label">{record.environments.length} 个</span> },
    {
      title: '仓库', width: 86,
      render: (_, record) => <span className="status-label">{record.environments.reduce((sum, item) => sum + item.repositories.length, 0)} 个</span>,
    },
    {
      title: '数据库', width: 92,
      render: (_, record) => <span className="status-label">{record.environments.reduce((sum, item) => sum + item.databases.length, 0)} 个</span>,
    },
    {
      title: '更新时间', dataIndex: 'updatedAt', width: 168,
      render: (value) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作', key: 'actions', width: 92, fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Tooltip title="编辑项目">
            <Button type="text" icon={<EditOutlined />} aria-label="编辑项目" onClick={() => navigate(`/projects/${record.id}`)} />
          </Tooltip>
          <Popconfirm title="删除这个项目及其文档和日志？" onConfirm={() => void removeProject(record)}>
            <Tooltip title="删除项目">
              <Button type="text" danger icon={<DeleteOutlined />} aria-label="删除项目" />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeading title="项目" description="项目的环境、仓库和数据库资源在详情页统一维护。"
                   actions={<Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/projects/new')}>新增项目</Button>} />
      <div className="toolbar">
        <span className="text-sm text-slate-500">共 {projects.length} 个项目</span>
        <Tooltip title="刷新"><Button icon={<ReloadOutlined />} aria-label="刷新项目" onClick={() => void load()} loading={loading} /></Tooltip>
      </div>
      <Table<Project> rowKey="id" columns={columns} dataSource={projects} loading={loading} pagination={false}
                      scroll={{ x: 980 }} locale={{ emptyText: <Empty description="还没有项目" /> }} />
    </>
  );
}
