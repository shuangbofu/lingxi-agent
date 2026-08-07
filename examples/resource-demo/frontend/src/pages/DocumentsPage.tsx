import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { App, Button, Empty, Form, Input, Modal, Popconfirm, Select, Space, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { demoApi } from '../api/demo';
import { PageHeading } from '../components/PageHeading';
import type { DocumentInput, MarkdownDocument, Project } from '../types';

export function DocumentsPage() {
  const { message } = App.useApp();
  const [searchParams, setSearchParams] = useSearchParams();
  const [projects, setProjects] = useState<Project[]>([]);
  const [documents, setDocuments] = useState<MarkdownDocument[]>([]);
  const [projectCode, setProjectCode] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<MarkdownDocument>();
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<DocumentInput>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [projectItems, documentItems] = await Promise.all([
        demoApi.projects(),
        demoApi.documents(projectCode),
      ]);
      setProjects(projectItems);
      setDocuments(documentItems);
      const documentId = Number(searchParams.get('documentId'));
      if (documentId && !modalOpen) {
        const target = documentItems.find((item) => item.id === documentId);
        if (target) {
          openEdit(target);
        }
      }
    } finally {
      setLoading(false);
    }
  }, [projectCode, searchParams, modalOpen]);

  useEffect(() => {
    void load();
  }, [load]);

  const openCreate = () => {
    setEditing(undefined);
    form.setFieldsValue({ projectCode: projectCode || projects[0]?.code, title: '', path: '', category: '', content: '' });
    setModalOpen(true);
  };

  const openEdit = (document: MarkdownDocument) => {
    setEditing(document);
    form.setFieldsValue(document);
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    if (searchParams.has('documentId')) {
      setSearchParams({}, { replace: true });
    }
  };

  const save = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) {
        await demoApi.updateDocument(editing.id, values);
      } else {
        await demoApi.createDocument(values);
      }
      message.success('Markdown 文档已保存');
      closeModal();
      await load();
    } finally {
      setSaving(false);
    }
  };

  const remove = async (document: MarkdownDocument) => {
    await demoApi.deleteDocument(document.id);
    message.success('Markdown 文档已删除');
    await load();
  };

  const columns: ColumnsType<MarkdownDocument> = [
    {
      title: '文档',
      key: 'title',
      width: 280,
      render: (_, record) => (
        <div>
          <div className="font-medium text-slate-800">{record.title}</div>
          <div className="mt-1 text-xs text-slate-500">{record.path}</div>
        </div>
      ),
    },
    {
      title: '所属项目',
      dataIndex: 'projectCode',
      width: 170,
      render: (value) => projects.find((item) => item.code === value)?.name || value,
    },
    {
      title: '分类',
      dataIndex: 'category',
      width: 140,
      render: (value) => value ? <span className="status-label">{value}</span> : '-',
    },
    {
      title: '版本',
      dataIndex: 'revision',
      width: 80,
      render: (value) => `第 ${value} 版`,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 168,
      render: (value) => dayjs(value).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 92,
      render: (_, record) => (
        <Space size={4}>
          <Tooltip title="编辑文档">
            <Button type="text" icon={<EditOutlined />} aria-label="编辑文档" onClick={() => openEdit(record)} />
          </Tooltip>
          <Popconfirm title="删除这篇文档？" onConfirm={() => void remove(record)}>
            <Tooltip title="删除文档">
              <Button type="text" danger icon={<DeleteOutlined />} aria-label="删除文档" />
            </Tooltip>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <PageHeading
        title="Markdown 文档"
        description="文档保存后即可由 wiki-ingest 按项目检索，编辑会自动保留历史版本。"
        actions={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate} disabled={!projects.length}>新增文档</Button>}
      />
      <div className="toolbar">
        <Select
          allowClear
          placeholder="全部项目"
          value={projectCode}
          onChange={setProjectCode}
          style={{ width: 220 }}
          options={projects.map((project) => ({ label: project.name, value: project.code }))}
        />
        <Tooltip title="刷新">
          <Button icon={<ReloadOutlined />} aria-label="刷新文档" onClick={() => void load()} loading={loading} />
        </Tooltip>
      </div>
      <Table<MarkdownDocument>
        rowKey="id"
        columns={columns}
        dataSource={documents}
        loading={loading}
        pagination={{ pageSize: 20, showSizeChanger: false, showTotal: (total) => `共 ${total} 篇` }}
        scroll={{ x: 900 }}
        locale={{ emptyText: <Empty description="还没有 Markdown 文档" /> }}
      />

      <Modal
        title={editing ? '编辑 Markdown 文档' : '新增 Markdown 文档'}
        open={modalOpen}
        onCancel={closeModal}
        onOk={() => void save()}
        confirmLoading={saving}
        okText="保存"
        width={920}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <div className="grid grid-cols-1 gap-x-3 md:grid-cols-2">
            <Form.Item name="projectCode" label="所属项目" rules={[{ required: true, message: '请选择项目' }]}>
              <Select options={projects.map((project) => ({ label: project.name, value: project.code }))} />
            </Form.Item>
            <Form.Item name="category" label="分类">
              <Input placeholder="例如：业务规则" />
            </Form.Item>
            <Form.Item name="title" label="文档标题" rules={[{ required: true, message: '请填写标题' }]}>
              <Input placeholder="例如：Codex 项目阅读线索" />
            </Form.Item>
            <Form.Item name="path" label="文档路径" rules={[{ required: true, message: '请填写文档路径' }]}>
              <Input placeholder="例如：开源项目/Codex.md" />
            </Form.Item>
          </div>
          <Form.Item name="content" label="Markdown 正文" rules={[{ required: true, message: '请填写 Markdown 正文' }]}>
            <Input.TextArea rows={18} placeholder="# 标题&#10;&#10;在这里维护文档正文。" className="font-mono" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
