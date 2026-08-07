import { useEffect, useState } from 'react';
import { Button, Form, Input, InputNumber, Modal, Select, Skeleton, Switch, Table, Upload, message } from 'antd';
import '../styles/profile.css';
import type { UploadProps } from 'antd';
import { ChartBar, PencilSimple, Plus } from '@phosphor-icons/react';
import { createUser, getUserUsageQuota, pageUsers, updateUser, updateUserUsageSettings, uploadUserAvatar } from '../api/lingxi';
import type { UserItem, UserRole, UserSaveRequest, UserUsageQuota, UserUsageQuotaPeriod } from '../types/api';
import { formatTime, formatTokenCount } from '../utils/format';
import { UserAvatar } from '../components/UserAvatar';
import { useAuth } from '../context/AuthContext';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { AppTag, EnabledTag } from '../components/AppTag';

export function UserPage() {
  const { user, reload } = useAuth();
  const [records, setRecords] = useState<UserItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [editing, setEditing] = useState<UserItem>();
  const [usageUser, setUsageUser] = useState<UserItem>();
  const [modalOpen, setModalOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [usageSaving, setUsageSaving] = useState(false);
  const [usageLoading, setUsageLoading] = useState(false);
  const [usageQuota, setUsageQuota] = useState<UserUsageQuota>();
  const [form] = Form.useForm<UserSaveRequest>();
  const [usageForm] = Form.useForm<{
    dailyTokenLimit?: number;
    weeklyTokenLimit?: number;
    monthlyTokenLimit?: number;
  }>();
  const avatarUrl = Form.useWatch('avatarUrl', form);
  const pageSize = 10;

  useEffect(() => {
    load(1);
  }, []);

  async function load(nextPage = page) {
    setLoading(true);
    try {
      const result = await pageUsers(nextPage, pageSize);
      setRecords(result.records);
      setTotal(result.total);
      setPage(result.page);
    } finally {
      setLoading(false);
    }
  }

  function openCreate() {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({ role: 'USER', enabled: true });
    setModalOpen(true);
  }

  function openEdit(record: UserItem) {
    setEditing(record);
    form.setFieldsValue({
      username: record.username,
      displayName: record.displayName,
      avatarUrl: record.avatarUrl,
      role: record.role,
      enabled: record.enabled,
      password: undefined,
    });
    setModalOpen(true);
  }

  async function save() {
    const values = await form.validateFields();
    setSaving(true);
    try {
      if (editing) {
        await updateUser(editing.id, values);
        message.success('用户已更新');
      } else {
        await createUser(values);
        message.success('用户已创建');
      }
      if (editing?.id === user?.id) {
        await reload();
      }
      setModalOpen(false);
      await load(editing ? page : 1);
    } finally {
      setSaving(false);
    }
  }

  function openUsageSettings(record: UserItem) {
    setUsageUser(record);
    setUsageQuota(undefined);
    usageForm.setFieldsValue({
      dailyTokenLimit: record.dailyTokenLimit,
      weeklyTokenLimit: record.weeklyTokenLimit,
      monthlyTokenLimit: record.monthlyTokenLimit,
    });
    setUsageLoading(true);
    getUserUsageQuota(record.id)
      .then(setUsageQuota)
      .finally(() => setUsageLoading(false));
  }

  async function saveUsageSettings() {
    if (!usageUser) {
      return;
    }
    const values = await usageForm.validateFields();
    setUsageSaving(true);
    try {
      await updateUserUsageSettings(usageUser.id, {
        dailyTokenLimit: values.dailyTokenLimit ?? null,
        weeklyTokenLimit: values.weeklyTokenLimit ?? null,
        monthlyTokenLimit: values.monthlyTokenLimit ?? null,
      });
      message.success('用量限制已保存');
      setUsageUser(undefined);
      await load(page);
    } finally {
      setUsageSaving(false);
    }
  }

  const avatarUploadProps: UploadProps = {
    accept: 'image/png,image/jpeg,image/webp,image/gif',
    maxCount: 1,
    showUploadList: false,
    beforeUpload: async (file) => {
      try {
        const result = await uploadUserAvatar(file);
        form.setFieldValue('avatarUrl', result.url);
        message.success('头像已上传');
      } catch {
        message.error('头像上传失败');
      }
      return Upload.LIST_IGNORE;
    },
  };

  const columns = [
    {
      title: '头像',
      dataIndex: 'avatarUrl',
      width: 80,
      render: (_: string | undefined, record: UserItem) => (
        <UserAvatar avatarUrl={record.avatarUrl} name={record.displayName || record.username} size={32} />
      ),
    },
    { title: '账号', dataIndex: 'username', width: 160 },
    { title: '姓名', dataIndex: 'displayName', width: 160 },
    { title: '角色', dataIndex: 'role', width: 120, render: (value: UserRole) => <AppTag tone={value === 'ADMIN' ? 'violet' : 'neutral'}>{roleText(value)}</AppTag> },
    { title: '状态', dataIndex: 'enabled', width: 100, render: (value: boolean) => <EnabledTag enabled={value} /> },
    { title: '最近登录', dataIndex: 'lastLoginAt', width: 180, render: formatTime },
    { title: '更新时间', dataIndex: 'updatedAt', width: 180, render: formatTime },
    {
      title: '操作',
      width: 190,
      render: (_: unknown, record: UserItem) => (
        <div className="flex gap-2">
          <Button size="small" icon={<PencilSimple size={14} weight="fill" />} onClick={() => openEdit(record)}>编辑</Button>
          <Button size="small" icon={<ChartBar size={14} weight="fill" />} onClick={() => openUsageSettings(record)}>用量</Button>
        </div>
      ),
    },
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <section className="flex min-h-0 flex-1 flex-col rounded-md border border-neutral-200 bg-white p-4">
        <div className="mb-3 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback="用户管理" path="/admin/users" icon="user" tone="rose" />
          </div>
          <div className="flex gap-2">
            <Button onClick={() => load(page)}>刷新</Button>
            <Button type="primary" icon={<Plus size={16} weight="fill" />} onClick={openCreate}>
              新增用户
            </Button>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <Table
            rowKey="id"
            columns={columns}
            dataSource={records}
            loading={loading}
            size="middle"
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: false,
              showTotal: (value) => `共 ${value} 条`,
              onChange: (nextPage) => load(nextPage),
            }}
          />
        </div>
      </section>
      <Modal
        title={editing ? '编辑用户' : '新增用户'}
        open={modalOpen}
        confirmLoading={saving}
        onOk={save}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item label="账号" name="username" rules={[{ required: true, message: '请输入账号' }]}>
            <Input />
          </Form.Item>
          <Form.Item label="姓名" name="displayName" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input />
          </Form.Item>
          <Form.Item label="头像">
            <div className="user-avatar-field">
              <UserAvatar avatarUrl={avatarUrl} name={form.getFieldValue('displayName') || form.getFieldValue('username')} size={48} />
              <Upload {...avatarUploadProps}>
                <Button>上传头像</Button>
              </Upload>
            </div>
          </Form.Item>
          <Form.Item name="avatarUrl" hidden>
            <Input />
          </Form.Item>
          <Form.Item label="角色" name="role" rules={[{ required: true, message: '请选择角色' }]}>
            <Select options={roleOptions} />
          </Form.Item>
          <Form.Item label={editing ? '重置密码' : '初始密码'} name="password">
            <Input.Password placeholder={editing ? '不填写则不修改' : '不填写则使用默认密码'} />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title={`用量设置 · ${usageUser?.displayName || usageUser?.username || ''}`}
        open={!!usageUser}
        confirmLoading={usageSaving}
        onOk={saveUsageSettings}
        onCancel={() => setUsageUser(undefined)}
        okText="保存"
        destroyOnClose
      >
        <Form form={usageForm} layout="vertical">
          <div className="user-quota-form-grid">
            <Form.Item label="每日配额" name="dailyTokenLimit">
              <InputNumber min={1} precision={0} controls={false} placeholder="继承全局" className="w-full" />
            </Form.Item>
            <Form.Item label="每周配额" name="weeklyTokenLimit">
              <InputNumber min={1} precision={0} controls={false} placeholder="继承全局" className="w-full" />
            </Form.Item>
            <Form.Item label="每月配额" name="monthlyTokenLimit">
              <InputNumber min={1} precision={0} controls={false} placeholder="继承全局" className="w-full" />
            </Form.Item>
          </div>
          <div className="user-quota-form-hint">留空时继承系统全局配额；全局也未配置则不限额。</div>
        </Form>
        <div className="user-usage-summary">
          {usageLoading ? (
            <Skeleton active title={false} paragraph={{ rows: 2 }} />
          ) : (
            <div className="user-usage-quota-list">
              <QuotaProgress label="今日" quota={usageQuota?.daily} />
              <QuotaProgress label="本周" quota={usageQuota?.weekly} />
              <QuotaProgress label="本月" quota={usageQuota?.monthly} />
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
}

const roleOptions = [
  { label: '管理员', value: 'ADMIN' },
  { label: '普通用户', value: 'USER' },
];

function roleText(value: UserRole) {
  return value === 'ADMIN' ? '管理员' : '普通用户';
}

function QuotaProgress({ label, quota }: { label: string; quota?: UserUsageQuotaPeriod }) {
  return (
    <div className="user-usage-quota-row">
      <div className="user-usage-line">
        <span>{label}</span>
        <strong>{formatTokenCount(quota?.tokenUsed || 0)}</strong>
        <small>{quota?.inherited ? '继承全局' : quota?.limited ? '个人配额' : '未限制'}</small>
        <em>{quotaPercentText(quota)}</em>
      </div>
      <div className="user-usage-progress"><span style={{ width: `${quotaProgressWidth(quota)}%` }} /></div>
    </div>
  );
}

function quotaPercentText(quota?: UserUsageQuotaPeriod) {
  return quota?.limited ? `${quota.usagePercent || 0}%` : '不限额';
}

function quotaProgressWidth(quota?: UserUsageQuotaPeriod) {
  return quota?.limited ? Math.min(Math.max(quota.usagePercent || 0, 0), 100) : 0;
}
