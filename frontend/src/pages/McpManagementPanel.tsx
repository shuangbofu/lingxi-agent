import { useEffect, useMemo, useState } from 'react';
import { Button, Form, Input, Modal, Pagination, Popconfirm, Popover, Segmented, Select, Spin, Switch, Table, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowClockwise, CheckCircle, PencilSimple, Plus, Prohibit, SquaresFour, Table as TableIcon, Trash, Wrench, X } from '@phosphor-icons/react';
import {
  createMcpServer,
  deleteMcpServer,
  getAgentRuntimes,
  pageMcpServers,
  updateMcpServer,
} from '../api/lingxi';
import { AppTag, EnabledTag } from '../components/AppTag';
import type {
  AgentRuntimeDescriptor,
  McpServer,
  McpServerRequest,
  McpTransport,
  RuntimeActionIcon,
} from '../types/api';
import { RuntimeActionIconView } from '../components/RuntimeActionIconView';
import { formatTime } from '../utils/format';

type McpViewMode = 'card' | 'table';

export function McpManagementPanel() {
  const [mcpServers, setMcpServers] = useState<McpServer[]>([]);
  const [agentRuntimes, setAgentRuntimes] = useState<AgentRuntimeDescriptor[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingMcp, setEditingMcp] = useState<McpServer>();
  const [toolPresentationEntries, setToolPresentationEntries] = useState<ToolPresentationEntry[]>([]);
  const [saving, setSaving] = useState(false);
  const [viewMode, setViewMode] = useState<McpViewMode>(() => mcpViewMode());
  const [form] = Form.useForm<McpFormValues>();
  const transport = Form.useWatch('transport', form) as McpTransport | undefined;
  const mcpRuntimes = useMemo(
    () => agentRuntimes.filter((runtime) => runtime.mcpSupported),
    [agentRuntimes],
  );
  const pageSize = 15;

  useEffect(() => {
    void loadAll(1);
  }, []);

  async function loadAll(nextPage = page) {
    setLoading(true);
    try {
      const [result, runtimes] = await Promise.all([pageMcpServers(nextPage, pageSize), getAgentRuntimes()]);
      setMcpServers(result.records);
      setTotal(result.total);
      setPage(result.page);
      setAgentRuntimes(runtimes);
      return true;
    } catch {
      return false;
    } finally {
      setLoading(false);
    }
  }

  function openEditor(server?: McpServer) {
    setEditingMcp(server);
    setToolPresentationEntries(toolPresentationEntriesFrom(server?.toolPresentations));
    form.resetFields();
    form.setFieldsValue(mcpFormValues(server));
    setModalOpen(true);
  }

  async function save() {
    const values = await form.validateFields();
    if (toolPresentationEntries.some((item) => !item.toolName?.trim() || !item.label?.trim() || !item.icon)) {
      message.error('请完整填写工具名称、展示名称和图标');
      return;
    }
    const toolNames = toolPresentationEntries.map((item) => item.toolName!.trim());
    if (new Set(toolNames).size !== toolNames.length) {
      message.error('工具名称不能重复');
      return;
    }
    const payload: McpServerRequest = {
      code: editingMcp?.code,
      name: values.name.trim(),
      instructions: values.instructions?.trim() || undefined,
      transport: values.transport,
      command: values.transport === 'STDIO' ? values.command?.trim() : undefined,
      arguments: values.transport === 'STDIO' ? values.arguments || [] : [],
      url: values.transport === 'STREAMABLE_HTTP' ? values.url?.trim() : undefined,
      environment: entryRecord(values.environmentEntries),
      headers: entryRecord(values.headerEntries),
      runtimeCodes: values.runtimeCodes || [],
      activationFeatures: editingMcp?.activationFeatures || [],
      toolAllowlist: values.toolAllowlist || [],
      toolPresentations: toolPresentationRecord(toolPresentationEntries),
      enabled: values.enabled ?? true,
    };
    setSaving(true);
    try {
      if (editingMcp) {
        await updateMcpServer(editingMcp.id, payload);
      } else {
        await createMcpServer(payload);
      }
      if (!(await loadAll(editingMcp ? page : 1))) return;
      setModalOpen(false);
      message.success(editingMcp ? 'MCP 配置已更新' : 'MCP 配置已新增');
    } catch {
      return;
    } finally {
      setSaving(false);
    }
  }

  async function remove(server: McpServer) {
    try {
      await deleteMcpServer(server.id);
      if (!(await loadAll(mcpServers.length === 1 && page > 1 ? page - 1 : page))) return;
      message.success('MCP 配置已删除');
    } catch {
      return;
    }
  }

  async function toggleEnabled(server: McpServer) {
    try {
      await updateMcpServer(server.id, mcpRequest(server, !server.enabled));
      if (!(await loadAll(page))) return;
      message.success(server.enabled ? 'MCP 已停用' : 'MCP 已启用');
    } catch {
      return;
    }
  }

  function changeViewMode(nextMode: McpViewMode) {
    setViewMode(nextMode);
    localStorage.setItem('mcp-view-mode', nextMode);
  }

  const columns: ColumnsType<McpServer> = [
    {
      title: '名称',
      width: 220,
      render: (_, server) => (
        <div className="mcp-table-name">
          <strong>{server.name}</strong>
          <small>{server.code}</small>
        </div>
      ),
    },
    { title: '连接方式', dataIndex: 'transport', width: 110, render: mcpTransportText },
    { title: '服务端点', ellipsis: true, render: (_, server) => server.command || server.url || '-' },
    { title: '执行模式', width: 210, render: (_, server) => runtimeNames(server.runtimeCodes, agentRuntimes) },
    { title: '状态', dataIndex: 'enabled', width: 90, render: (enabled) => <EnabledTag enabled={enabled} /> },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
    {
      title: '操作',
      width: 120,
      fixed: 'right',
      render: (_, server) => (
        <div className="definition-table-actions">
          <Tooltip title={server.enabled ? '停用' : '启用'}>
            <Button
              size="small"
              icon={server.enabled ? <Prohibit size={14} weight="fill" /> : <CheckCircle size={14} weight="fill" />}
              aria-label={server.enabled ? `停用${server.name}` : `启用${server.name}`}
              onClick={() => toggleEnabled(server)}
            />
          </Tooltip>
          <Tooltip title="编辑">
            <Button size="small" icon={<PencilSimple size={14} weight="fill" />} aria-label={`编辑${server.name}`} onClick={() => openEditor(server)} />
          </Tooltip>
          {!server.builtin ? (
            <Popconfirm title="删除 MCP 配置？" description="删除后，所有执行模式都无法再使用它。" okText="删除" cancelText="取消" onConfirm={() => remove(server)}>
              <Tooltip title="删除">
                <Button danger size="small" icon={<Trash size={14} weight="fill" />} aria-label={`删除${server.name}`} />
              </Tooltip>
            </Popconfirm>
          ) : null}
        </div>
      ),
    },
  ];

  return (
    <section className="mcp-management-panel flex min-h-0 flex-1 flex-col bg-white">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="text-base font-semibold text-neutral-800">MCP 服务</div>
        <div className="flex items-center gap-2">
          <Segmented<McpViewMode>
            className="app-view-switch management-view-switch"
            value={viewMode}
            onChange={changeViewMode}
            options={[
              { value: 'card', label: <span><SquaresFour size={13} weight="fill" />卡片</span> },
              { value: 'table', label: <span><TableIcon size={13} weight="fill" />表格</span> },
            ]}
          />
          <Button icon={<ArrowClockwise size={16} weight="fill" />} loading={loading} onClick={() => loadAll(page)}>刷新</Button>
          <Button type="primary" icon={<Plus size={16} weight="bold" />} onClick={() => openEditor()}>新增 MCP</Button>
        </div>
      </div>

      <Spin spinning={loading} wrapperClassName="mcp-management-spin min-h-0 flex-1">
        {viewMode === 'card' ? (
          <div className="mcp-management-grid">
            {mcpServers.map((server) => (
              <article className="mcp-management-card" key={server.id}>
                <div className="settings-mcp-title-row">
                  <div className="mcp-management-card-title">
                    <span className="settings-mcp-name">{server.name}</span>
                    <small>{server.code}</small>
                  </div>
                  <div className="flex shrink-0 gap-1">
                    <EnabledTag enabled={server.enabled} />
                    {server.builtin ? <AppTag tone="violet">内置</AppTag> : <AppTag>自定义</AppTag>}
                  </div>
                </div>
                <div className="settings-mcp-meta">
                  <span>{mcpTransportText(server.transport)}</span>
                  <span>{runtimeNames(server.runtimeCodes, agentRuntimes)}</span>
                </div>
                <div className="settings-mcp-endpoint" title={server.command || server.url}>
                  {server.command || server.url || '未配置服务端点'}
                </div>
                <div className="mcp-management-card-footer">
                  <span>更新时间：{formatTime(server.updatedAt)}</span>
                  <div className="settings-mcp-actions">
                    <Tooltip title={server.enabled ? '停用' : '启用'}>
                      <Button
                        size="small"
                        icon={server.enabled ? <Prohibit size={14} weight="fill" /> : <CheckCircle size={14} weight="fill" />}
                        aria-label={server.enabled ? `停用${server.name}` : `启用${server.name}`}
                        onClick={() => toggleEnabled(server)}
                      />
                    </Tooltip>
                    <Tooltip title="编辑">
                      <Button size="small" icon={<PencilSimple size={14} weight="fill" />} aria-label={`编辑${server.name}`} onClick={() => openEditor(server)} />
                    </Tooltip>
                    {!server.builtin ? (
                      <Popconfirm title="删除 MCP 配置？" description="删除后，所有执行模式都无法再使用它。" okText="删除" cancelText="取消" onConfirm={() => remove(server)}>
                        <Tooltip title="删除"><Button danger size="small" aria-label={`删除${server.name}`} icon={<Trash size={14} weight="fill" />} /></Tooltip>
                      </Popconfirm>
                    ) : null}
                  </div>
                </div>
              </article>
            ))}
            {!mcpServers.length && !loading ? <div className="settings-mcp-empty">暂未配置 MCP 服务</div> : null}
          </div>
        ) : (
          <Table rowKey="id" columns={columns} dataSource={mcpServers} pagination={false} size="middle" scroll={{ x: 1080 }} />
        )}
      </Spin>
      {total > 0 ? (
        <div className="mt-2 flex shrink-0 justify-end">
          <Pagination current={page} pageSize={pageSize} total={total} showSizeChanger={false} showTotal={(value) => `共 ${value} 条`} onChange={loadAll} />
        </div>
      ) : null}

      <Modal
        title={editingMcp ? '编辑 MCP' : '新增 MCP'}
        open={modalOpen}
        width={680}
        okText="保存"
        cancelText="取消"
        confirmLoading={saving}
        onOk={save}
        onCancel={() => setModalOpen(false)}
        forceRender
      >
        <Form className="settings-mcp-form" form={form} layout="vertical" preserve={false}>
          <div className="settings-form-grid">
            <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入 MCP 名称' }]}>
              <Input placeholder="例如 Codebase Memory" />
            </Form.Item>
            <Form.Item label="连接方式" name="transport" rules={[{ required: true }]}>
              <Segmented block options={[
                { label: '本地进程', value: 'STDIO' },
                { label: '远程服务', value: 'STREAMABLE_HTTP' },
              ]} />
            </Form.Item>
            {transport === 'STDIO' ? (
              <>
                <Form.Item className="settings-field-wide" label="启动命令" name="command" rules={[{ required: true, message: '请输入启动命令' }]}>
                  <Input placeholder="例如 ./.tools/codebase-memory-mcp" />
                </Form.Item>
                <Form.Item className="settings-field-wide" label="命令参数" name="arguments">
                  <Select mode="tags" tokenSeparators={[',']} placeholder="输入一个参数后回车" />
                </Form.Item>
              </>
            ) : (
              <Form.Item className="settings-field-wide" label="服务地址" name="url" rules={[{ required: true, type: 'url', message: '请输入有效的 HTTP 地址' }]}>
                <Input placeholder="https://example.com/mcp" />
              </Form.Item>
            )}
            <Form.Item className="settings-field-wide" label="启用的执行模式" name="runtimeCodes">
              <Select mode="multiple" placeholder="未选择时不向任何执行模式提供" options={mcpRuntimes.map((runtime) => ({ label: runtime.name, value: runtime.code }))} />
            </Form.Item>
            <Form.Item className="settings-field-wide" label="工具白名单" name="toolAllowlist">
              <Select mode="tags" tokenSeparators={[',']} placeholder="留空表示允许 MCP 暴露的全部工具" />
            </Form.Item>
            <div className="settings-field-wide">
              <ToolPresentationFields values={toolPresentationEntries} onChange={setToolPresentationEntries} />
            </div>
            <Form.Item className="settings-field-wide" label="使用说明" name="instructions">
              <Input.TextArea
                rows={8}
                maxLength={20000}
                showCount
                placeholder="描述该 MCP 的推荐调用流程、参数传递和结果核查规则"
              />
            </Form.Item>
            <Form.Item label="启用状态" name="enabled" valuePropName="checked"><Switch /></Form.Item>
          </div>
          {transport === 'STDIO' && <KeyValueFields name="environmentEntries" title="环境变量" />}
          {transport === 'STREAMABLE_HTTP' && <KeyValueFields name="headerEntries" title="请求头" secret />}
        </Form>
      </Modal>
    </section>
  );
}

interface McpFormValues extends McpServerRequest {
  environmentEntries?: KeyValueEntry[];
  headerEntries?: KeyValueEntry[];
}

interface KeyValueEntry {
  key?: string;
  value?: string;
}

interface ToolPresentationEntry {
  toolName?: string;
  label?: string;
  icon?: RuntimeActionIcon;
  executionMode?: 'SERIAL' | 'READ_ONLY';
}

const toolIconOptions: Array<{ value: RuntimeActionIcon; label: string }> = [
  { value: 'PLUGS_CONNECTED', label: '插头' },
  { value: 'WRENCH', label: '扳手' },
  { value: 'TERMINAL', label: '终端' },
  { value: 'BOOK_OPEN', label: '书本' },
  { value: 'FILE_TEXT', label: '文档' },
  { value: 'FILE_PLUS', label: '新增文档' },
  { value: 'FILE_MAGNIFYING_GLASS', label: '文件搜索' },
  { value: 'MAGNIFYING_GLASS', label: '放大镜' },
  { value: 'BRACKETS_CURLY', label: '花括号' },
  { value: 'CODE', label: '代码' },
  { value: 'GRAPH', label: '关系图' },
  { value: 'GIT_DIFF', label: '差异' },
  { value: 'TREE_STRUCTURE', label: '树状结构' },
  { value: 'QUESTION', label: '问号' },
  { value: 'LIST_BULLETS', label: '列表' },
  { value: 'CHECK_CIRCLE', label: '确认' },
  { value: 'CALENDAR', label: '日历' },
  { value: 'CLOCK', label: '时钟' },
  { value: 'DATABASE', label: '数据库' },
  { value: 'FOLDER', label: '文件夹' },
  { value: 'GIT_BRANCH', label: '分支' },
  { value: 'GIT_COMMIT', label: '提交' },
  { value: 'SHIELD_CHECK', label: '安全确认' },
  { value: 'ARROW_COUNTER_CLOCKWISE', label: '回退' },
  { value: 'PLAY_CIRCLE', label: '执行' },
  { value: 'VIDEO_CAMERA', label: '视频' },
  { value: 'IMAGE_SQUARE', label: '图片' },
  { value: 'EYE', label: '查看' },
  { value: 'HISTORY', label: '历史' },
  { value: 'BROWSER', label: '浏览器' },
  { value: 'HEAD_CIRCUIT', label: '思考' },
  { value: 'WARNING_CIRCLE', label: '警告' },
  { value: 'X_CIRCLE', label: '失败' },
];

function ToolIconPicker({
  value,
  onChange,
}: {
  value?: RuntimeActionIcon;
  onChange?: (value: RuntimeActionIcon) => void;
}) {
  const [open, setOpen] = useState(false);
  const content = (
    <div className="settings-mcp-icon-grid">
      {toolIconOptions.map((option) => (
        <Tooltip title={option.label} key={option.value} mouseEnterDelay={0.5}>
          <button
            type="button"
            className={value === option.value ? 'is-active' : undefined}
            aria-label={option.label}
            onClick={() => {
              onChange?.(option.value);
              setOpen(false);
            }}
          >
            <RuntimeActionIconView icon={option.value} size={18} weight="regular" />
          </button>
        </Tooltip>
      ))}
    </div>
  );
  return (
    <Popover content={content} trigger="click" open={open} onOpenChange={setOpen} placement="bottomRight">
      <Button
        className="settings-mcp-icon-picker"
        icon={value ? <RuntimeActionIconView icon={value} size={18} /> : <Wrench size={18} />}
        aria-label="选择工具图标"
      />
    </Popover>
  );
}

function ToolPresentationFields({
  values,
  onChange,
}: {
  values: ToolPresentationEntry[];
  onChange: (values: ToolPresentationEntry[]) => void;
}) {
  function update(index: number, value: Partial<ToolPresentationEntry>) {
    onChange(values.map((item, itemIndex) => itemIndex === index ? { ...item, ...value } : item));
  }

  return (
    <div className="settings-mcp-pairs">
      <div className="settings-mcp-pairs-heading">
        <span>工具展示</span>
        <Button type="link" size="small" icon={<Plus size={14} />} onClick={() => onChange([...values, {}])}>新增</Button>
      </div>
      {values.map((entry, index) => (
        <div className="settings-mcp-pair settings-mcp-tool-presentation" key={index}>
          <Form.Item>
            <Input value={entry.toolName} placeholder="Tool 名称" onChange={(event) => update(index, { toolName: event.target.value })} />
          </Form.Item>
          <Form.Item>
            <Input value={entry.label} placeholder="展示名称" onChange={(event) => update(index, { label: event.target.value })} />
          </Form.Item>
          <Form.Item>
            <ToolIconPicker value={entry.icon} onChange={(icon) => update(index, { icon })} />
          </Form.Item>
          <Tooltip title="只读工具可与其他只读工具并行执行">
            <Switch
              checked={entry.executionMode === 'READ_ONLY'}
              checkedChildren="只读"
              unCheckedChildren="串行"
              onChange={(checked) => update(index, {
                executionMode: checked ? 'READ_ONLY' : 'SERIAL',
              })}
            />
          </Tooltip>
          <Button
            type="text"
            danger
            aria-label="删除工具展示"
            title="删除工具展示"
            icon={<X size={16} />}
            onClick={() => onChange(values.filter((_, itemIndex) => itemIndex !== index))}
          />
        </div>
      ))}
    </div>
  );
}

function KeyValueFields({ name, title, secret }: { name: 'environmentEntries' | 'headerEntries'; title: string; secret?: boolean }) {
  return (
    <Form.List name={name}>
      {(fields, { add, remove }) => (
        <div className="settings-mcp-pairs">
          <div className="settings-mcp-pairs-heading">
            <span>{title}</span>
            <Button type="link" size="small" icon={<Plus size={14} />} onClick={() => add()}>新增</Button>
          </div>
          {fields.map((field) => (
            <div className="settings-mcp-pair" key={field.key}>
              <Form.Item name={[field.name, 'key']} rules={[{ required: true, message: '请输入名称' }]}><Input placeholder="名称" /></Form.Item>
              <Form.Item name={[field.name, 'value']} rules={[{ required: true, message: '请输入值' }]}>
                {secret ? <Input.Password placeholder="值" /> : <Input placeholder="值" />}
              </Form.Item>
              <Button type="text" danger aria-label={`删除${title}`} title={`删除${title}`} icon={<X size={16} />} onClick={() => remove(field.name)} />
            </div>
          ))}
        </div>
      )}
    </Form.List>
  );
}

function entries(values: Record<string, string>) {
  return Object.entries(values || {}).map(([key, value]) => ({ key, value }));
}

function entryRecord(values?: KeyValueEntry[]) {
  return Object.fromEntries((values || [])
    .filter((item): item is { key: string; value: string } => Boolean(item.key?.trim()) && item.value !== undefined)
    .map((item) => [item.key.trim(), item.value]));
}

function toolPresentationEntriesFrom(values?: McpServer['toolPresentations']): ToolPresentationEntry[] {
  return Object.entries(values || {}).map(([toolName, presentation]) => ({ toolName, ...presentation }));
}

function toolPresentationRecord(values?: ToolPresentationEntry[]): McpServer['toolPresentations'] {
  return Object.fromEntries((values || [])
    .filter((item): item is Required<Pick<ToolPresentationEntry, 'toolName' | 'label'>> & ToolPresentationEntry =>
      Boolean(item.toolName?.trim()) && Boolean(item.label?.trim()))
    .map((item) => [item.toolName.trim(), {
      label: item.label.trim(),
      icon: item.icon,
      executionMode: item.executionMode,
    }]));
}

function mcpFormValues(server?: McpServer): Partial<McpFormValues> {
  if (!server) {
    return {
      name: '',
      transport: 'STDIO',
      arguments: [],
      runtimeCodes: [],
      toolAllowlist: [],
      environmentEntries: [],
      headerEntries: [],
      enabled: true,
    };
  }
  return {
    ...server,
    environmentEntries: entries(server.environment),
    headerEntries: entries(server.headers),
  };
}

function mcpViewMode(): McpViewMode {
  return localStorage.getItem('mcp-view-mode') === 'table' ? 'table' : 'card';
}

function mcpTransportText(transport: McpTransport) {
  return transport === 'STDIO' ? '本地进程' : '远程服务';
}

/**
 * 根据现有 MCP 配置生成更新请求，只替换启用状态。
 *
 * @param server 当前 MCP 配置
 * @param enabled 更新后的启用状态
 * @return 可直接提交的 MCP 更新请求
 */
function mcpRequest(server: McpServer, enabled: boolean): McpServerRequest {
  return {
    code: server.code,
    name: server.name,
    instructions: server.instructions,
    transport: server.transport,
    command: server.command,
    arguments: server.arguments,
    url: server.url,
    environment: server.environment,
    headers: server.headers,
    runtimeCodes: server.runtimeCodes,
    activationFeatures: server.activationFeatures,
    toolAllowlist: server.toolAllowlist,
    toolPresentations: server.toolPresentations,
    enabled,
  };
}

function runtimeNames(codes: string[], runtimes: AgentRuntimeDescriptor[]) {
  if (!codes?.length) return '未绑定执行模式';
  return codes.map((code) => runtimes.find((runtime) => runtime.code === code)?.name || code).join('、');
}
