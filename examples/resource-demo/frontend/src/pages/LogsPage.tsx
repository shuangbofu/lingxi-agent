import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  DatePicker,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Table,
  Tooltip,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { type Dayjs } from 'dayjs';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { demoApi } from '../api/demo';
import { PageHeading } from '../components/PageHeading';
import type { LogEntry, LogInput, Project } from '../types';

interface LogFormValues extends Omit<LogInput, 'occurredAt'> {
  occurredAt?: Dayjs;
}

const levelOptions = [
  { label: '跟踪', value: 'TRACE' },
  { label: '调试', value: 'DEBUG' },
  { label: '信息', value: 'INFO' },
  { label: '警告', value: 'WARN' },
  { label: '错误', value: 'ERROR' },
];

const levelNames: Record<string, string> = Object.fromEntries(levelOptions.map((item) => [item.value, item.label]));

export function LogsPage() {
  const { message } = App.useApp();
  const [projects, setProjects] = useState<Project[]>([]);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [projectId, setProjectId] = useState<number>();
  const [environmentCode, setEnvironmentCode] = useState<string>();
  const [mode, setMode] = useState<'single' | 'batch'>('single');
  const [modalOpen, setModalOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<LogFormValues>();

  const environments = useMemo(
    () => projects.find((project) => project.id === projectId)?.environments || [],
    [projects, projectId],
  );

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [projectItems, logItems] = await Promise.all([
        demoApi.projects(),
        demoApi.logs(projectId, environmentCode),
      ]);
      setProjects(projectItems);
      setLogs(logItems);
    } finally {
      setLoading(false);
    }
  }, [projectId, environmentCode]);

  useEffect(() => {
    void load();
  }, [load]);

  const openCreate = () => {
    const selectedProject = projects.find((project) => project.id === projectId) || projects[0];
    form.setFieldsValue({
      projectId: selectedProject?.id,
      environmentCode: environmentCode || selectedProject?.environments[0]?.code,
      serviceName: '',
      occurredAt: dayjs(),
      level: 'INFO',
      traceId: '',
      content: '',
    });
    setMode('single');
    setModalOpen(true);
  };

  const save = async () => {
    const values = await form.validateFields();
    const input: LogInput = {
      ...values,
      occurredAt: mode === 'single' && values.occurredAt ? values.occurredAt.format('YYYY-MM-DDTHH:mm:ss') : undefined,
    };
    setSaving(true);
    try {
      if (mode === 'batch') {
        await demoApi.createLogBatch(input);
      } else {
        await demoApi.createLog(input);
      }
      message.success(mode === 'batch' ? '日志已批量导入' : '日志已添加');
      setModalOpen(false);
      await load();
    } finally {
      setSaving(false);
    }
  };

  const remove = async (log: LogEntry) => {
    await demoApi.deleteLog(log.id);
    message.success('日志已删除');
    await load();
  };

  const columns: ColumnsType<LogEntry> = [
    {
      title: '时间',
      dataIndex: 'occurredAt',
      width: 180,
      render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm:ss.SSS'),
    },
    {
      title: '级别',
      dataIndex: 'level',
      width: 76,
      render: (value) => (
        <span className={`status-label ${value === 'ERROR' ? 'level-error' : value === 'WARN' ? 'level-warn' : ''}`}>
          {levelNames[value] || value}
        </span>
      ),
    },
    { title: '项目', dataIndex: 'projectCode', width: 140 },
    { title: '环境', dataIndex: 'environmentCode', width: 100 },
    { title: '服务', dataIndex: 'serviceName', width: 150 },
    { title: 'TraceId', dataIndex: 'traceId', width: 180, render: (value) => value || '-' },
    {
      title: '日志内容',
      dataIndex: 'content',
      render: (value) => <div className="log-content" title={value}>{value}</div>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 54,
      fixed: 'right',
      render: (_, record) => (
        <Popconfirm title="删除这条日志？" onConfirm={() => void remove(record)}>
          <Tooltip title="删除日志">
            <Button type="text" danger icon={<DeleteOutlined />} aria-label="删除日志" />
          </Tooltip>
        </Popconfirm>
      ),
    },
  ];

  const formProjectId = Form.useWatch('projectId', form);
  const formEnvironments = projects.find((project) => project.id === formProjectId)?.environments || [];

  return (
    <>
      <PageHeading
        title="日志"
        description="日志会按项目、环境和服务名组成虚拟日志文件，供 http-log-read 检索。"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate} disabled={!projects.length}>添加日志</Button>}
      />
      <div className="toolbar">
        <Space wrap>
          <Select
            allowClear
            placeholder="全部项目"
            value={projectId}
            onChange={(value) => {
              setProjectId(value);
              setEnvironmentCode(undefined);
            }}
            style={{ width: 210 }}
            options={projects.map((project) => ({ label: project.name, value: project.id }))}
          />
          <Select
            allowClear
            placeholder="全部环境"
            value={environmentCode}
            onChange={setEnvironmentCode}
            disabled={!projectId}
            style={{ width: 160 }}
            options={environments.map((environment) => ({ label: environment.name, value: environment.code }))}
          />
        </Space>
        <Tooltip title="刷新">
          <Button icon={<ReloadOutlined />} aria-label="刷新日志" onClick={() => void load()} loading={loading} />
        </Tooltip>
      </div>
      <Table<LogEntry>
        rowKey="id"
        columns={columns}
        dataSource={logs}
        loading={loading}
        pagination={{ pageSize: 30, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
        scroll={{ x: 1180 }}
        locale={{ emptyText: <Empty description="还没有日志" /> }}
      />

      <Modal
        title="添加日志"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => void save()}
        confirmLoading={saving}
        okText={mode === 'batch' ? '导入' : '添加'}
        width={760}
        destroyOnClose
      >
        <div className="mb-4">
          <Segmented
            value={mode}
            onChange={(value) => setMode(value as 'single' | 'batch')}
            options={[{ label: '单条', value: 'single' }, { label: '批量粘贴', value: 'batch' }]}
          />
        </div>
        <Form form={form} layout="vertical" preserve={false}>
          <div className="grid grid-cols-1 gap-x-3 md:grid-cols-2">
            <Form.Item name="projectId" label="所属项目" rules={[{ required: true, message: '请选择项目' }]}>
              <Select
                onChange={() => form.setFieldValue('environmentCode', undefined)}
                options={projects.map((project) => ({ label: project.name, value: project.id }))}
              />
            </Form.Item>
            <Form.Item name="environmentCode" label="环境" rules={[{ required: true, message: '请选择环境' }]}>
              <Select options={formEnvironments.map((environment) => ({ label: environment.name, value: environment.code }))} />
            </Form.Item>
            <Form.Item name="serviceName" label="服务名" rules={[{ required: true, message: '请填写服务名' }]}>
              <Input placeholder="例如：order-service" />
            </Form.Item>
            <Form.Item name="level" label="日志级别">
              <Select options={levelOptions} />
            </Form.Item>
            {mode === 'single' && (
              <Form.Item name="occurredAt" label="发生时间" rules={[{ required: true, message: '请选择时间' }]}>
                <DatePicker showTime style={{ width: '100%' }} format="YYYY-MM-DD HH:mm:ss" />
              </Form.Item>
            )}
            <Form.Item name="traceId" label="TraceId">
              <Input placeholder="例如：demo-trace-1001" />
            </Form.Item>
          </div>
          <Form.Item
            name="content"
            label={mode === 'batch' ? '日志文本' : '日志内容'}
            rules={[{ required: true, message: '请填写日志内容' }]}
          >
            <Input.TextArea
              rows={mode === 'batch' ? 12 : 5}
              className="font-mono"
              placeholder={mode === 'batch' ? '每行保存为一条日志' : '填写日志正文'}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
