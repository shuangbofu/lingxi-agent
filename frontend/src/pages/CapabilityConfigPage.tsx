import { useEffect, useMemo, useState } from 'react';
import { Button, Checkbox, Form, Input, InputNumber, Modal, Popconfirm, Select, Table, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowClockwise, FolderPlus, PencilSimple, Trash } from '@phosphor-icons/react';
import { createCapabilityConfig, deleteCapabilityConfig, listAllCapabilities, pageCapabilityConfigs, updateCapabilityConfig } from '../api/lingxi';
import type { AgentCapability, AgentDefinitionParameter, CapabilityConfigItem, CapabilityConfigSaveRequest, CapabilityConfigValue } from '../types/api';
import { formatTime } from '../utils/format';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { AppTag, EnabledTag } from '../components/AppTag';

interface CapabilityConfigPageProps {
  embedded?: boolean;
  capabilityCode?: string;
  capability?: AgentCapability;
}

interface CapabilityConfigFormValues {
  name: string;
  capabilityCode: string;
  description?: string;
  enabled: boolean;
  config?: CapabilityConfigValue;
}

export function CapabilityConfigPage({ embedded = false, capabilityCode: fixedCapabilityCode, capability: fixedCapability }: CapabilityConfigPageProps) {
  const [configs, setConfigs] = useState<CapabilityConfigItem[]>([]);
  const [capabilities, setCapabilities] = useState<AgentCapability[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<CapabilityConfigItem>();
  const [form] = Form.useForm<CapabilityConfigFormValues>();
  const capabilityCode = Form.useWatch('capabilityCode', form);
  const pageSize = 10;
  const scopedCapabilityCode = fixedCapabilityCode || fixedCapability?.code;

  const selectedCapability = useMemo(
    () => fixedCapability || capabilities.find((item) => item.code === capabilityCode),
    [capabilities, capabilityCode, fixedCapability],
  );
  const configurableCapabilities = useMemo(
    () => capabilities.filter((item) => item.configParameters?.length),
    [capabilities],
  );

  useEffect(() => {
    loadConfigs(1);
    loadCapabilities();
  }, [scopedCapabilityCode]);

  async function loadCapabilities() {
    setCapabilities(await listAllCapabilities());
  }

  async function loadConfigs(nextPage = page) {
    setLoading(true);
    try {
      const result = await pageCapabilityConfigs(nextPage, pageSize, scopedCapabilityCode);
      setConfigs(result.records);
      setTotal(result.total);
      setPage(result.page);
    } finally {
      setLoading(false);
    }
  }

  function openCreate() {
    const capability = fixedCapability || configurableCapabilities.find((item) => item.code === scopedCapabilityCode) || configurableCapabilities[0];
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({
      capabilityCode: capability?.code || scopedCapabilityCode,
      enabled: true,
      config: defaultConfig(capability),
    });
    setModalOpen(true);
  }

  function openEdit(record: CapabilityConfigItem) {
    setEditing(record);
    form.resetFields();
    form.setFieldsValue({
      name: record.name,
      capabilityCode: record.capabilityCode,
      description: record.description,
      enabled: record.enabled,
      config: record.config || {},
    });
    setModalOpen(true);
  }

  async function save() {
    const values = await form.validateFields();
    const payloadCapabilityCode = values.capabilityCode || scopedCapabilityCode;
    if (!payloadCapabilityCode) {
      message.warning('请选择能力');
      return;
    }
    const payload: CapabilityConfigSaveRequest = {
      name: values.name,
      capabilityCode: payloadCapabilityCode,
      description: values.description,
      config: values.config || {},
      enabled: values.enabled,
    };
    if (editing) {
      await updateCapabilityConfig(editing.id, payload);
    } else {
      await createCapabilityConfig(payload);
    }
    message.success(editing ? '服务接入配置已更新' : '服务接入配置已创建');
    setModalOpen(false);
    form.resetFields();
    await loadConfigs(editing ? page : 1);
  }

  async function remove(id: number) {
    await deleteCapabilityConfig(id);
    message.success('服务接入配置已删除');
    await loadConfigs(page);
  }

  function capabilityForRecord(record: CapabilityConfigItem) {
    if (fixedCapability && record.capabilityCode === fixedCapability.code) {
      return fixedCapability;
    }
    return capabilities.find((item) => item.code === record.capabilityCode);
  }

  const columns: ColumnsType<CapabilityConfigItem> = [
    { title: '名称', dataIndex: 'name', width: 180 },
    ...(scopedCapabilityCode ? [] : [{ title: '能力', dataIndex: 'capabilityName', width: 160, render: (value: string) => value || '-' }]),
    { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '-' },
    { title: '状态', dataIndex: 'enabled', width: 90, render: (value) => <EnabledTag enabled={value} /> },
    { title: '更新时间', width: 170, render: (_, record) => formatTime(record.updatedAt) },
    {
      title: '操作',
      width: 150,
      render: (_, record) => (
        <div className="flex gap-2">
          <Button size="small" icon={<PencilSimple size={14} weight="fill" />} onClick={() => openEdit(record)}>编辑</Button>
          <Popconfirm title="确认删除服务接入配置？" onConfirm={() => remove(record.id)}>
            <Button size="small" danger icon={<Trash size={14} weight="fill" />} />
          </Popconfirm>
        </div>
      ),
    },
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <section className={`flex min-h-0 flex-1 flex-col ${embedded ? 'bg-transparent' : 'rounded-md border border-neutral-200 bg-white p-4'}`}>
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2 text-base font-semibold">
            {embedded ? '服务接入配置' : <PageHeaderTitle fallback="服务接入配置管理" path="/admin/capability-configs" icon="capabilityConfig" tone="emerald" />}
          </div>
          <div className="flex gap-2">
            <Button icon={<ArrowClockwise size={16} weight="fill" />} onClick={() => loadConfigs(page)}>刷新</Button>
            <Button type="primary" icon={<FolderPlus size={16} weight="fill" />} disabled={!scopedCapabilityCode && configurableCapabilities.length === 0} onClick={openCreate}>新增接入配置</Button>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <Table
            rowKey="id"
            columns={columns}
            dataSource={configs}
            loading={loading}
            size="middle"
            scroll={{ x: 900 }}
            expandable={{
              expandedRowRender: (record) => (
                <ConfigValueList capability={capabilityForRecord(record)} config={record.config || {}} />
              ),
              rowExpandable: (record) => !!capabilityForRecord(record)?.configParameters?.length,
            }}
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: false,
              showTotal: (value) => `共 ${value} 条`,
              onChange: (nextPage) => loadConfigs(nextPage),
            }}
          />
        </div>
      </section>

      <Modal title={editing ? '编辑服务接入配置' : '新增服务接入配置'} open={modalOpen} onOk={save} onCancel={() => setModalOpen(false)} destroyOnClose width={760}>
        <Form form={form} layout="vertical">
          <div className="grid grid-cols-2 gap-3">
            <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
              <Input />
            </Form.Item>
            {scopedCapabilityCode ? (
              <Form.Item label="能力">
                <Input value={selectedCapability?.name || fixedCapability?.name || '当前能力'} disabled />
              </Form.Item>
            ) : (
              <Form.Item label="能力" name="capabilityCode" rules={[{ required: true, message: '请选择能力' }]}>
                <Select
                  showSearch
                  optionFilterProp="label"
                  onChange={(nextCapabilityCode) => {
                    const capability = configurableCapabilities.find((item) => item.code === nextCapabilityCode);
                    form.setFieldValue('config', defaultConfig(capability));
                  }}
                  options={configurableCapabilities.map((item) => ({ label: item.name, value: item.code }))}
                />
              </Form.Item>
            )}
          </div>

          {selectedCapability?.configParameters?.length ? (
            <div className="grid grid-cols-2 gap-3">
              {selectedCapability.configParameters.map((parameter) => renderConfigParameter(parameter))}
            </div>
          ) : null}

          <Form.Item label="说明" name="description">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item label="启用" name="enabled" rules={[{ required: true, message: '请选择状态' }]}>
            <Select options={[{ label: '启用', value: true }, { label: '停用', value: false }]} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function ConfigValueList({ capability, config }: { capability?: AgentCapability; config: CapabilityConfigValue }) {
  if (!capability?.configParameters?.length) {
    return <div className="text-sm text-neutral-500">无配置参数</div>;
  }
  return (
    <div className="capability-config-values grid grid-cols-1 gap-2 p-3 md:grid-cols-2">
      {capability.configParameters.map((parameter) => (
        <div key={parameter.key} className="capability-config-value px-3 py-2">
          <div className="mb-1 flex items-center gap-2 text-xs text-neutral-500">
            <span>{parameter.name || parameter.key}</span>
            {parameter.required && <AppTag tone="amber">必填</AppTag>}
          </div>
          <div className="break-all text-sm text-neutral-800">{formatConfigValue(parameter, config[parameter.key])}</div>
          {parameter.description && <div className="mt-1 text-xs text-neutral-500">{parameter.description}</div>}
        </div>
      ))}
    </div>
  );
}

function renderConfigParameter(parameter: AgentDefinitionParameter) {
  const rules = parameter.required ? [{ required: true, message: `请输入${parameter.name}` }] : undefined;
  const name = ['config', parameter.key];
  const type = parameter.type?.toLowerCase() || 'text';
  const label = parameter.name || parameter.key;

  if (parameter.options?.length) {
    return (
      <Form.Item key={parameter.key} label={label} name={name} rules={rules} tooltip={parameter.description}>
        <Select options={parameter.options} />
      </Form.Item>
    );
  }
  if (type.includes('textarea') || type.includes('markdown')) {
    return (
      <Form.Item key={parameter.key} className="col-span-2" label={label} name={name} rules={rules} tooltip={parameter.description}>
        <Input.TextArea rows={3} />
      </Form.Item>
    );
  }
  if (type.includes('number') || type.includes('int')) {
    return (
      <Form.Item key={parameter.key} label={label} name={name} rules={rules} tooltip={parameter.description}>
        <InputNumber className="w-full" />
      </Form.Item>
    );
  }
  if (type.includes('bool')) {
    return (
      <Form.Item key={parameter.key} label={label} name={name} valuePropName="checked" tooltip={parameter.description}>
        <Checkbox />
      </Form.Item>
    );
  }
  if (type.includes('password') || parameter.key.toLowerCase().includes('password') || parameter.key.toLowerCase().includes('secret')) {
    return (
      <Form.Item key={parameter.key} label={label} name={name} rules={rules} tooltip={parameter.description}>
        <Input.Password />
      </Form.Item>
    );
  }
  return (
    <Form.Item key={parameter.key} label={label} name={name} rules={rules} tooltip={parameter.description}>
      <Input />
    </Form.Item>
  );
}

function defaultConfig(capability?: AgentCapability): CapabilityConfigValue {
  const config: CapabilityConfigValue = {};
  capability?.configParameters?.forEach((parameter) => {
    if (parameter.defaultValue !== undefined && parameter.defaultValue !== null && parameter.defaultValue !== '') {
      config[parameter.key] = parameter.defaultValue;
    }
  });
  return config;
}

function formatConfigValue(parameter: AgentDefinitionParameter, value: CapabilityConfigValue[string]) {
  if (value === undefined || value === null || value === '') {
    return '未配置';
  }
  const lowerKey = parameter.key.toLowerCase();
  const lowerType = parameter.type?.toLowerCase() || '';
  if (lowerType.includes('password') || lowerKey.includes('password') || lowerKey.includes('secret')) {
    return '******';
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  const option = parameter.options?.find((item) => item.value === String(value));
  return option?.label || String(value);
}
