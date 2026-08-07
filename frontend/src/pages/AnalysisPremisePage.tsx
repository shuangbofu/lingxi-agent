import { useEffect, useMemo, useState } from 'react';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Switch, Table, Tabs, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowClockwise, PencilSimple, Plus, Trash } from '@phosphor-icons/react';
import { createAnalysisPremise, deleteAnalysisPremise, listAnalysisPremiseContextParameters, listUsers, pageAnalysisPremises, updateAnalysisPremise } from '../api/lingxi';
import type { AnalysisPremiseContextParameter, AnalysisPremiseItem, AnalysisPremiseSaveRequest, UserItem } from '../types/api';
import { formatTime } from '../utils/format';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { AppTag, EnabledTag } from '../components/AppTag';

interface PremiseContextRow {
  parameter?: string;
  value?: string;
}

interface PremiseFormValues extends Omit<AnalysisPremiseSaveRequest, 'contextValues'> {
  scenarioContextRows?: Record<string, PremiseContextRow[]>;
}

export function AnalysisPremisePage() {
  const [records, setRecords] = useState<AnalysisPremiseItem[]>([]);
  const [users, setUsers] = useState<UserItem[]>([]);
  const [contextParameters, setContextParameters] = useState<AnalysisPremiseContextParameter[]>([]);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<AnalysisPremiseItem>();
  const [saving, setSaving] = useState(false);
  const [scenarioContextRows, setScenarioContextRows] = useState<Record<string, PremiseContextRow[]>>({});
  const [activeScenarioContextTab, setActiveScenarioContextTab] = useState<string>();
  const [form] = Form.useForm<PremiseFormValues>();
  const pageSize = 10;

  const userOptions = useMemo(
    () => users.map((item) => ({ label: item.displayName ? `${item.displayName}（${item.username}）` : item.username, value: item.id })),
    [users],
  );
  const scenarioGroups = useMemo(() => groupContextParameters(contextParameters), [contextParameters]);
  const visibleScenarioCodes = Form.useWatch('visibleScenarioCodes', form) || [];
  const globalVisible = Form.useWatch('globalVisible', form);
  const enabledScenarioGroups = useMemo(
    () => scenarioGroups.filter((group) => !visibleScenarioCodes.length || visibleScenarioCodes.includes(group.code)),
    [scenarioGroups, visibleScenarioCodes],
  );
  const activeScenarioContextGroup = useMemo(
    () => enabledScenarioGroups.find((group) => group.code === activeScenarioContextTab) || enabledScenarioGroups[0],
    [activeScenarioContextTab, enabledScenarioGroups],
  );

  useEffect(() => {
    load(1);
    loadUsers();
    loadContextParameters();
  }, []);

  useEffect(() => {
    if (modalOpen && editing) {
      setScenarioContextRows(scenarioContextRowsFromValue(editing.scenarioContextValues, scenarioGroups));
    }
    if (modalOpen && !editing) {
      setScenarioContextRows((current) => mergeScenarioContextRows(current, defaultScenarioContextRows(scenarioGroups)));
    }
  }, [scenarioGroups, editing, modalOpen]);

  useEffect(() => {
    if (!enabledScenarioGroups.length) {
      setActiveScenarioContextTab(undefined);
      return;
    }
    if (!activeScenarioContextTab || !enabledScenarioGroups.some((group) => group.code === activeScenarioContextTab)) {
      setActiveScenarioContextTab(enabledScenarioGroups[0].code);
    }
  }, [activeScenarioContextTab, enabledScenarioGroups]);

  async function load(nextPage = page, nextQuery = query) {
    setLoading(true);
    try {
      const result = await pageAnalysisPremises(nextPage, pageSize, nextQuery.trim() || undefined);
      setRecords(result.records);
      setTotal(result.total);
      setPage(result.page);
    } finally {
      setLoading(false);
    }
  }

  async function loadUsers() {
    setUsers(await listUsers());
  }

  async function loadContextParameters() {
    setContextParameters(await listAnalysisPremiseContextParameters());
  }

  function openCreate() {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({
      enabled: true,
      globalVisible: false,
      sortOrder: 0,
      visibleScenarioCodes: [],
      assignedUserIds: [],
    });
    setScenarioContextRows(defaultScenarioContextRows(scenarioGroups));
    setModalOpen(true);
  }

  function openEdit(record: AnalysisPremiseItem) {
    setEditing(record);
    form.resetFields();
    form.setFieldsValue({
      name: record.name,
      description: record.description,
      promptText: record.promptText,
      enabled: record.enabled,
      globalVisible: record.globalVisible,
      sortOrder: record.sortOrder,
      visibleScenarioCodes: record.visibleScenarioCodes || [],
      assignedUserIds: record.assignedUserIds || [],
    });
    setScenarioContextRows(scenarioContextRowsFromValue(record.scenarioContextValues, scenarioGroups));
    setModalOpen(true);
  }

  async function save() {
    const values = await form.validateFields();
    const payload: AnalysisPremiseSaveRequest = {
      name: values.name,
      description: values.description,
      promptText: values.promptText,
      contextValues: {},
      scenarioContextValues: scenarioContextValueFromRows(scenarioContextRows, scenarioGroups),
      visibleScenarioCodes: values.visibleScenarioCodes || [],
      enabled: values.enabled,
      globalVisible: values.globalVisible,
      sortOrder: values.sortOrder,
      assignedUserIds: values.globalVisible ? [] : values.assignedUserIds || [],
    };
    setSaving(true);
    try {
      if (editing) {
        await updateAnalysisPremise(editing.id, payload);
      } else {
        await createAnalysisPremise(payload);
      }
      message.success(editing ? '分析情境已更新' : '分析情境已创建');
      setModalOpen(false);
      await load(editing ? page : 1);
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: number) {
    await deleteAnalysisPremise(id);
    message.success('分析情境已删除');
    await load(page);
  }

  function addScenarioContextRow(scenarioCode: string) {
    setScenarioContextRows((current) => ({
      ...current,
      [scenarioCode]: [...(current[scenarioCode] || []), { parameter: '', value: '' }],
    }));
  }

  function updateScenarioContextRow(scenarioCode: string, index: number, row: PremiseContextRow) {
    setScenarioContextRows((current) => ({
      ...current,
      [scenarioCode]: (current[scenarioCode] || []).map((item, itemIndex) => (itemIndex === index ? row : item)),
    }));
  }

  function removeScenarioContextRow(scenarioCode: string, index: number) {
    setScenarioContextRows((current) => ({
      ...current,
      [scenarioCode]: (current[scenarioCode] || []).filter((_, itemIndex) => itemIndex !== index),
    }));
  }

  const columns: ColumnsType<AnalysisPremiseItem> = [
    { title: '名称', dataIndex: 'name', width: 170 },
    { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '-' },
    { title: '可见场景', dataIndex: 'visibleScenarioCodes', width: 180, render: (value) => visibleScenarioSummary(value, scenarioGroups) },
    { title: '场景参数', dataIndex: 'scenarioContextValues', width: 180, render: (value) => scenarioContextSummary(value) },
    {
      title: '范围',
      width: 150,
      render: (_, record) => (
        <div className="flex flex-wrap gap-1">
          <AppTag tone={record.globalVisible ? 'blue' : 'neutral'}>{record.globalVisible ? '全局可见' : '指定用户'}</AppTag>
        </div>
      ),
    },
    { title: '状态', dataIndex: 'enabled', width: 90, render: (value) => <EnabledTag enabled={value} /> },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
    {
      title: '操作',
      width: 150,
      render: (_, record) => (
        <div className="flex gap-2">
          <Button size="small" icon={<PencilSimple size={14} weight="fill" />} onClick={() => openEdit(record)}>编辑</Button>
          <Popconfirm title="确认删除分析情境？" onConfirm={() => remove(record.id)}>
            <Button size="small" danger icon={<Trash size={14} weight="fill" />} />
          </Popconfirm>
        </div>
      ),
    },
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <section className="flex min-h-0 flex-1 flex-col rounded-md border border-neutral-200 bg-white p-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback="分析情境" path="/admin/premises" icon="premise" tone="amber" />
          </div>
          <div className="flex flex-wrap gap-2">
            <Input.Search
              className="w-64"
              allowClear
              placeholder="搜索情境名称或说明"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onSearch={(value) => load(1, value)}
            />
            <Button
              icon={<ArrowClockwise size={16} weight="fill" />}
              onClick={() => Promise.all([load(page), loadContextParameters()])}
            >
              刷新
            </Button>
            <Button type="primary" icon={<Plus size={16} weight="fill" />} onClick={openCreate}>新增情境</Button>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <Table
            rowKey="id"
            columns={columns}
            dataSource={records}
            loading={loading}
            size="middle"
            scroll={{ x: 980 }}
            expandable={{
              expandedRowRender: (record) => <PremiseDetail record={record} users={users} contextParameters={contextParameters} />,
            }}
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
        title={editing ? '编辑分析情境' : '新增分析情境'}
        open={modalOpen}
        width={820}
        confirmLoading={saving}
        onOk={save}
        onCancel={() => setModalOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <div className="grid grid-cols-2 gap-3">
            <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
              <Input placeholder="如：运营生产环境" />
            </Form.Item>
            <Form.Item label="排序" name="sortOrder">
              <InputNumber className="w-full" min={0} precision={0} />
            </Form.Item>
          </div>
          <Form.Item label="说明" name="description">
            <Input placeholder="给用户看的简短说明" />
          </Form.Item>

          <div className="grid grid-cols-2 gap-3">
            <Form.Item label="启用" name="enabled" valuePropName="checked">
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
            <Form.Item label="全局可见" name="globalVisible" valuePropName="checked">
              <Switch checkedChildren="可见" unCheckedChildren="指定" />
            </Form.Item>
          </div>

          {!globalVisible && (
            <Form.Item label="分配用户" name="assignedUserIds">
              <Select mode="multiple" allowClear optionFilterProp="label" options={userOptions} placeholder="全局不可见时，仅这些用户可选" />
            </Form.Item>
          )}

          <Form.Item label="可见场景" name="visibleScenarioCodes">
            <Select
              mode="multiple"
              allowClear
              optionFilterProp="label"
              placeholder="不选则可见全部场景"
              options={scenarioGroups.map((group) => ({ label: group.name, value: group.code }))}
            />
          </Form.Item>

          <div className="mb-3 rounded-md border border-neutral-200 p-3">
            <div className="mb-2 flex items-center justify-between gap-3">
              <div className="text-sm font-medium text-neutral-700">场景情境参数</div>
              <Button
                size="small"
                icon={<Plus size={14} weight="bold" />}
                disabled={!activeScenarioContextGroup}
                onClick={() => activeScenarioContextGroup && addScenarioContextRow(activeScenarioContextGroup.code)}
              >
                新增参数
              </Button>
            </div>
            <Tabs
              size="small"
              activeKey={activeScenarioContextGroup?.code}
              onChange={setActiveScenarioContextTab}
              items={enabledScenarioGroups.map((group) => ({
                key: group.code,
                label: group.name,
                forceRender: true,
                children: (
                  <div className="space-y-2">
                    {(scenarioContextRows[group.code] || []).map((row, index) => (
                      <PremiseContextRowEditor
                        key={`${group.code}-${index}`}
                        row={row}
                        group={group}
                        onChange={(nextRow) => updateScenarioContextRow(group.code, index, nextRow)}
                        onRemove={() => removeScenarioContextRow(group.code, index)}
                      />
                    ))}
                  </div>
                ),
              }))}
            />
          </div>

          <Form.Item label="情境提示词" name="promptText">
            <Input.TextArea rows={6} placeholder="写给执行 agent 的情境要求。平台只注入，不理解具体业务内容。" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

interface PremiseScenarioGroup {
  code: string;
  name: string;
  parameters: AnalysisPremiseContextParameter[];
}

function PremiseContextRowEditor({
  row,
  group,
  onChange,
  onRemove,
}: {
  row: PremiseContextRow;
  group: PremiseScenarioGroup;
  onChange: (row: PremiseContextRow) => void;
  onRemove: () => void;
}) {
  const parameter = group.parameters.find((item) => contextParameterOptionValue(item) === row.parameter);
  return (
    <div className="grid grid-cols-[minmax(240px,0.8fr)_minmax(260px,1fr)_auto] items-start gap-3 rounded-md border border-neutral-200 bg-neutral-50/60 p-3">
      <div className="min-w-0">
        <Select
          className="w-full"
          popupClassName="premise-context-parameter-dropdown"
          showSearch
          optionFilterProp="label"
          options={contextParameterOptions(group.parameters)}
          placeholder="选择参数"
          value={row.parameter || undefined}
          onChange={(value) => onChange({ ...row, parameter: value })}
        />
        <div className="mt-1 truncate text-xs text-neutral-500">
          {parameter ? contextParameterSource(parameter) : '先选择要预设的参数'}
        </div>
      </div>
      <PremiseContextValueInput parameter={parameter} value={row.value} onChange={(value) => onChange({ ...row, value })} />
      <Button danger onClick={onRemove}>删除</Button>
    </div>
  );
}

function contextRowsFromValue(value?: Record<string, string>): PremiseContextRow[] {
  const rows = Object.entries(value || {}).map(([key, rowValue]) => ({ parameter: key, value: rowValue }));
  return rows.length ? rows : [{ parameter: '', value: '' }];
}

function contextValueFromRows(rows: PremiseContextRow[], parameters: AnalysisPremiseContextParameter[], scenarioCode: string) {
  const result: Record<string, string> = {};
  rows.forEach((row) => {
    const key = contextParameterKey(row.parameter, parameters, scenarioCode);
    const value = normalizePremiseContextValue(row.value);
    if (key && value) {
      result[key] = value;
    }
  });
  return result;
}

function groupContextParameters(parameters: AnalysisPremiseContextParameter[]): PremiseScenarioGroup[] {
  const groupMap = new Map<string, PremiseScenarioGroup>();
  parameters.forEach((parameter) => {
    if (!groupMap.has(parameter.scenarioCode)) {
      groupMap.set(parameter.scenarioCode, { code: parameter.scenarioCode, name: parameter.scenarioName, parameters: [] });
    }
    groupMap.get(parameter.scenarioCode)?.parameters.push(parameter);
  });
  return Array.from(groupMap.values()).map((group) => ({
    ...group,
    parameters: group.parameters.sort((a, b) => (a.sortOrder ?? 1000) - (b.sortOrder ?? 1000)),
  }));
}

function defaultScenarioContextRows(groups: PremiseScenarioGroup[]): Record<string, PremiseContextRow[]> {
  return Object.fromEntries(groups.map((group) => [group.code, [{ parameter: '', value: '' }]]));
}

function mergeScenarioContextRows(current: Record<string, PremiseContextRow[]>, defaults: Record<string, PremiseContextRow[]>) {
  return Object.fromEntries(Object.entries(defaults).map(([scenarioCode, rows]) => [scenarioCode, current[scenarioCode] || rows]));
}

function scenarioContextRowsFromValue(value: Record<string, Record<string, string>> | undefined, groups: PremiseScenarioGroup[]): Record<string, PremiseContextRow[]> {
  const result = defaultScenarioContextRows(groups);
  Object.entries(value || {}).forEach(([scenarioCode, rowValue]) => {
    const group = groups.find((item) => item.code === scenarioCode);
    result[scenarioCode] = contextRowsFromValue(rowValue).map((row) => ({
      ...row,
      parameter: contextParameterOptionValueByKey(row.parameter, group?.parameters || []),
    }));
  });
  return result;
}

function scenarioContextValueFromRows(rowsByScenario: Record<string, PremiseContextRow[]>, groups: PremiseScenarioGroup[]) {
  const result: Record<string, Record<string, string>> = {};
  Object.entries(rowsByScenario).forEach(([scenarioCode, rows]) => {
    const group = groups.find((item) => item.code === scenarioCode);
    const values = contextValueFromRows(rows, group?.parameters || [], scenarioCode);
    if (Object.keys(values).length) {
      result[scenarioCode] = values;
    }
  });
  return result;
}

function contextParameterSource(item: AnalysisPremiseContextParameter) {
  if (item.sourceType === 'SCENARIO') {
    return '场景参数';
  }
  return `能力参数 · ${item.sourceName}`;
}

function contextParameterOptions(parameters: AnalysisPremiseContextParameter[]) {
  const groups = new Map<string, { label: string; options: { label: string; value: string }[] }>();
  parameters.forEach((parameter) => {
    const groupKey = `${parameter.sourceType}:${parameter.sourceCode || parameter.sourceName}`;
    if (!groups.has(groupKey)) {
      groups.set(groupKey, {
        label: contextParameterSource(parameter),
        options: [],
      });
    }
    groups.get(groupKey)?.options.push({
      label: parameter.name || parameter.key,
      value: contextParameterOptionValue(parameter),
    });
  });
  return Array.from(groups.values());
}

function contextParameterOptionValue(parameter: AnalysisPremiseContextParameter) {
  return `${parameter.sourceType}:${parameter.sourceCode}:${parameter.key}`;
}

function contextParameterOptionValueByKey(key: string | undefined, parameters: AnalysisPremiseContextParameter[]) {
  if (!key) {
    return '';
  }
  const parameter = parameters.find((item) => item.key === key);
  return parameter ? contextParameterOptionValue(parameter) : key;
}

function contextParameterKey(value: string | undefined, parameters: AnalysisPremiseContextParameter[], scenarioCode: string) {
  const text = value?.trim();
  if (!text) {
    return '';
  }
  const parameter = parameters.find((item) => contextParameterOptionValue(item) === text);
  if (parameter) {
    return parameter.key;
  }
  return contextParameterKeyFromOptionValue(text, scenarioCode) || text;
}

function contextParameterKeyFromOptionValue(value: string, scenarioCode: string) {
  const parts = value.split(':');
  if (parts.length < 3) {
    return '';
  }
  const [sourceType, sourceCode, ...keyParts] = parts;
  if (!sourceType || !sourceCode || !keyParts.length) {
    return '';
  }
  if (sourceType === 'SCENARIO' && sourceCode !== scenarioCode) {
    return '';
  }
  return keyParts.join(':');
}

function normalizePremiseContextValue(value: unknown) {
  if (value === undefined || value === null) {
    return '';
  }
  return String(value).trim();
}

function PremiseContextValueInput({
  parameter,
  value,
  onChange,
}: {
  parameter?: AnalysisPremiseContextParameter;
  value?: string;
  onChange: (value: string) => void;
}) {
  if (parameter?.options?.length) {
    return <Select className="w-full" allowClear options={parameter.options} placeholder="选择值" value={value || undefined} onChange={(nextValue) => onChange(nextValue || '')} />;
  }
  const type = parameter?.type?.toLowerCase() || '';
  if (type.includes('bool') || type.includes('switch')) {
    return <Select className="w-full" allowClear options={[{ label: '是', value: 'true' }, { label: '否', value: 'false' }]} placeholder="选择值" value={value || undefined} onChange={(nextValue) => onChange(nextValue || '')} />;
  }
  if (type.includes('textarea') || type.includes('markdown')) {
    return <Input.TextArea autoSize={{ minRows: 1, maxRows: 3 }} placeholder={parameter?.description || '值'} value={value} onChange={(event) => onChange(event.target.value)} />;
  }
  return <Input placeholder={parameter?.description || '值'} value={value} onChange={(event) => onChange(event.target.value)} />;
}

function scenarioContextSummary(value?: Record<string, Record<string, string>>) {
  const entries = Object.entries(value || {}).filter(([, values]) => Object.keys(values || {}).length);
  if (!entries.length) {
    return <span className="text-neutral-400">无</span>;
  }
  const total = entries.reduce((sum, [, values]) => sum + Object.keys(values || {}).length, 0);
  return <span className="text-neutral-600">{entries.length} 个场景，{total} 项参数</span>;
}

function visibleScenarioSummary(value: string[] | undefined, groups: PremiseScenarioGroup[]) {
  if (!value?.length) {
    return <span className="text-neutral-400">全部</span>;
  }
  const names = value.map((code) => groups.find((group) => group.code === code)?.name).filter(Boolean);
  return names.length ? <span className="text-neutral-600">{names.join('、')}</span> : <span className="text-neutral-400">-</span>;
}

function PremiseDetail({ record, users, contextParameters }: { record: AnalysisPremiseItem; users: UserItem[]; contextParameters: AnalysisPremiseContextParameter[] }) {
  const assignedNames = userNames(record.assignedUserIds || [], users);
  const scenarioValues = record.scenarioContextValues || {};
  const groups = groupContextParameters(contextParameters);
  return (
    <div className="space-y-3 rounded-md bg-neutral-50 p-3 text-sm text-neutral-700">
      <div>
        <div className="mb-1 text-xs font-medium text-neutral-500">可见场景</div>
        <div>{visibleScenarioSummary(record.visibleScenarioCodes, groups)}</div>
      </div>
      <div>
        <div className="mb-1 text-xs font-medium text-neutral-500">场景情境参数</div>
        <div className="space-y-2">
          {Object.entries(scenarioValues).map(([scenarioCode, values]) => (
            <div key={scenarioCode} className="rounded border border-neutral-200 bg-white px-3 py-2">
              <div className="mb-2 font-medium text-neutral-700">{scenarioNameByCode(scenarioCode, contextParameters)}</div>
              <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
                {Object.entries(values || {}).map(([key, value]) => (
                  <div key={key} className="rounded bg-neutral-50 px-2 py-1">
                    <span className="font-medium text-neutral-700">{parameterNameByKey(scenarioCode, key, contextParameters)}</span>
                    <span className="ml-2 text-neutral-500">{value}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
          {!Object.keys(scenarioValues).length && <span className="text-neutral-400">无</span>}
        </div>
      </div>
      <div>
        <div className="mb-1 text-xs font-medium text-neutral-500">分配</div>
        <div className="text-neutral-600">可用用户：{record.globalVisible ? '全部用户' : assignedNames || '未分配'}</div>
      </div>
      {record.promptText ? (
        <div>
          <div className="mb-1 text-xs font-medium text-neutral-500">情境提示词</div>
          <div className="whitespace-pre-wrap rounded border border-neutral-200 bg-white p-3 text-neutral-700">{record.promptText}</div>
        </div>
      ) : null}
    </div>
  );
}

function userNames(ids: number[], users: UserItem[]) {
  return ids
    .map((id) => users.find((item) => item.id === id))
    .filter(Boolean)
    .map((item) => item?.displayName || item?.username)
    .join('、');
}

function scenarioNameByCode(scenarioCode: string, parameters: AnalysisPremiseContextParameter[]) {
  return parameters.find((item) => item.scenarioCode === scenarioCode)?.scenarioName || scenarioCode;
}

function parameterNameByKey(scenarioCode: string, key: string, parameters: AnalysisPremiseContextParameter[]) {
  const parameter = parameters.find((item) => item.scenarioCode === scenarioCode && item.key === key);
  if (!parameter) {
    return key;
  }
  const source = parameter.sourceType === 'SCENARIO' ? '场景' : parameter.sourceName;
  return `${parameter.name || key}（${source}）`;
}
