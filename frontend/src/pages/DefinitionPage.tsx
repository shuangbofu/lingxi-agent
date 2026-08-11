import { useEffect, useLayoutEffect, useMemo, useRef, useState, type DragEvent, type KeyboardEvent, type MouseEvent } from 'react';
import { Button, Descriptions, Empty, Modal, Pagination, Popconfirm, Segmented, Select, Spin, Switch, Table, Tabs, Tag, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowClockwise, CaretLeft, CheckCircle, DotsSixVertical, Eye, GearSix, Package as PackageIcon, Power, Prohibit, SquaresFour, Table as TableIcon, Trash } from '@phosphor-icons/react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  getCapability,
  getScenario,
  listAllCapabilities,
  listAllScenarios,
  pageAllCapabilities,
  pageAllScenarios,
  reorderScenarios,
  uninstallCapability,
  uninstallScenario,
  updateCapability,
  updateScenario,
} from '../api/lingxi';
import { DefinitionIcon } from '../components/DefinitionIcon';
import { MarkdownText } from '../components/MarkdownText';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { AppTag, EnabledTag } from '../components/AppTag';
import { CapabilityConfigPage } from './CapabilityConfigPage';
import { CapabilityInstallModal } from '../components/CapabilityInstallModal';
import { DemoDefinitionImportModal, type DemoDefinitionImportOffer } from '../components/DemoDefinitionImportModal';
import { ScenarioInstallModal } from '../components/ScenarioInstallModal';
import { RuntimeActionIconView } from '../components/RuntimeActionIconView';
import type {
  AgentCapability,
  AgentCapabilityStateUpdateRequest,
  AgentDefinitionGuide,
  AgentDefinitionParameter,
  AgentScenario,
  AgentScenarioStateUpdateRequest,
} from '../types/api';
import { formatTime } from '../utils/format';

type DefinitionKind = 'scenario' | 'capability';
type DefinitionMode = 'list' | 'view';
type DefinitionViewMode = 'card' | 'table';
type DefinitionItem = AgentScenario | AgentCapability;
type DefinitionStateUpdateRequest = AgentScenarioStateUpdateRequest | AgentCapabilityStateUpdateRequest;

interface DefinitionPageProps {
  kind: DefinitionKind;
  mode?: DefinitionMode;
  embedded?: boolean;
}

export function DefinitionPage({ kind, mode = 'list', embedded = false }: DefinitionPageProps) {
  if (mode === 'list') {
    return <DefinitionListPage kind={kind} embedded={embedded} />;
  }
  return <DefinitionDetailPage kind={kind} />;
}

function DefinitionListPage({ kind, embedded }: { kind: DefinitionKind; embedded?: boolean }) {
  const navigate = useNavigate();
  const meta = getDefinitionMeta(kind);
  const [items, setItems] = useState<DefinitionItem[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [deletingCode, setDeletingCode] = useState<string>();
  const [reordering, setReordering] = useState(false);
  const [draggedId, setDraggedId] = useState<string>();
  const [capabilityModules, setCapabilityModules] = useState<AgentCapability[]>([]);
  const [configCapability, setConfigCapability] = useState<AgentCapability>();
  const [runtimeConfigScenario, setRuntimeConfigScenario] = useState<AgentScenario>();
  const [installOpen, setInstallOpen] = useState(false);
  const [demoImportOffer, setDemoImportOffer] = useState<DemoDefinitionImportOffer>();
  const [demoImportOrigin, setDemoImportOrigin] = useState<string>();
  const [viewMode, setViewMode] = useState<DefinitionViewMode>(() => definitionViewMode(kind));
  const itemsRef = useRef(items);
  const draggedIdRef = useRef<string>();
  const dragOriginRef = useRef<DefinitionItem[]>();
  const dragTargetRef = useRef<string>();
  const dragCompletedRef = useRef(false);
  const cardPositionsRef = useRef<Map<string, DOMRect>>();
  const capabilityNameMap = useMemo(() => new Map(capabilityModules.map((capability) => [capability.code, capability.name])), [capabilityModules]);
  const pageSize = 15;

  itemsRef.current = items;

  useLayoutEffect(() => {
    const previousPositions = cardPositionsRef.current;
    cardPositionsRef.current = undefined;
    if (!previousPositions) {
      return;
    }
    document.querySelectorAll<HTMLElement>('.definition-card[data-definition-id]').forEach((card) => {
      const previous = previousPositions.get(card.dataset.definitionId || '');
      if (!previous) {
        return;
      }
      const current = card.getBoundingClientRect();
      const deltaX = previous.left - current.left;
      const deltaY = previous.top - current.top;
      if (deltaX || deltaY) {
        card.animate(
          [{ transform: `translate(${deltaX}px, ${deltaY}px)` }, { transform: 'translate(0, 0)' }],
          { duration: 180, easing: 'cubic-bezier(0.2, 0, 0, 1)' },
        );
      }
    });
  }, [items]);

  function setItemsWithLayoutAnimation(nextItems: DefinitionItem[]) {
    cardPositionsRef.current = new Map(
      Array.from(document.querySelectorAll<HTMLElement>('.definition-card[data-definition-id]')).map((card) => [
        card.dataset.definitionId || '',
        card.getBoundingClientRect(),
      ]),
    );
    itemsRef.current = nextItems;
    setItems(nextItems);
  }

  useEffect(() => {
    setViewMode(definitionViewMode(kind));
    loadDefinitions(1);
    if (kind === 'scenario') {
      listAllCapabilities().then(setCapabilityModules);
    } else {
      setCapabilityModules([]);
    }
  }, [kind]);

  useEffect(() => {
    if (kind !== 'scenario' || !window.opener) {
      return;
    }
    function notifyReady(origin = '*') {
      window.opener?.postMessage({ type: 'LINGXI_DEFINITION_IMPORT_READY' }, origin);
    }
    function receiveDemoImport(event: MessageEvent) {
      if (event.source !== window.opener) {
        return;
      }
      if (event.data?.type === 'LINGXI_DEFINITION_IMPORT_PROBE') {
        notifyReady(event.origin);
        return;
      }
      if (!isDemoDefinitionImportOffer(event.data)) {
        return;
      }
      setDemoImportOffer(event.data);
      setDemoImportOrigin(event.origin);
    }
    window.addEventListener('message', receiveDemoImport);
    notifyReady();
    return () => window.removeEventListener('message', receiveDemoImport);
  }, [kind]);

  async function loadDefinitions(nextPage = page) {
    setLoading(true);
    try {
      const result = kind === 'scenario'
        ? await pageAllScenarios(nextPage, pageSize)
        : await pageAllCapabilities(nextPage, pageSize);
      setItems(result.records);
      setTotal(result.total);
      setPage(result.page);
    } finally {
      setLoading(false);
    }
  }

  async function persistScenarioOrder(nextItems: DefinitionItem[], previousItems: DefinitionItem[]) {
    itemsRef.current = nextItems;
    setItems(nextItems);
    setReordering(true);
    try {
      // 分页只展示当前页，保存排序时将当前页的新顺序合并回完整场景顺序。
      const allScenarios = await listAllScenarios();
      const fullOrder = allScenarios.map((item) => item.code);
      const pagePositions = previousItems.map((item) => fullOrder.indexOf(item.code));
      if (pagePositions.some((position) => position < 0)) {
        throw new Error('场景列表已变化');
      }
      nextItems.forEach((item, index) => {
        fullOrder[pagePositions[index]] = item.code;
      });
      await reorderScenarios(fullOrder);
      await loadDefinitions(page);
      message.success('场景顺序已保存');
    } catch {
      setItemsWithLayoutAnimation(previousItems);
      message.error('排序保存失败，请重试');
    } finally {
      setReordering(false);
    }
  }

  function moveScenario(sourceId: string, targetId: string) {
    if (kind !== 'scenario' || reordering || sourceId === targetId) {
      return;
    }
    const currentItems = itemsRef.current;
    const sourceIndex = currentItems.findIndex((item) => item.code === sourceId);
    const targetIndex = currentItems.findIndex((item) => item.code === targetId);
    if (sourceIndex < 0 || targetIndex < 0) {
      return;
    }
    const nextItems = [...currentItems];
    const [moved] = nextItems.splice(sourceIndex, 1);
    nextItems.splice(targetIndex, 0, moved);
    setItemsWithLayoutAnimation(nextItems);
    void persistScenarioOrder(nextItems, currentItems);
  }

  function moveScenarioByOffset(id: string, offset: -1 | 1) {
    const sourceIndex = itemsRef.current.findIndex((item) => item.code === id);
    const target = itemsRef.current[sourceIndex + offset];
    if (target) {
      moveScenario(id, target.code);
    }
  }

  function previewScenarioMove(sourceId: string, targetId: string) {
    if (reordering || sourceId === targetId || dragTargetRef.current === targetId) {
      return;
    }
    const currentItems = itemsRef.current;
    const sourceIndex = currentItems.findIndex((item) => item.code === sourceId);
    const targetIndex = currentItems.findIndex((item) => item.code === targetId);
    if (sourceIndex < 0 || targetIndex < 0) {
      return;
    }
    dragTargetRef.current = targetId;
    const nextItems = [...currentItems];
    const [moved] = nextItems.splice(sourceIndex, 1);
    nextItems.splice(targetIndex, 0, moved);
    setItemsWithLayoutAnimation(nextItems);
  }

  function finishScenarioDrag() {
    const previousItems = dragOriginRef.current;
    const nextItems = itemsRef.current;
    dragCompletedRef.current = true;
    draggedIdRef.current = undefined;
    dragOriginRef.current = undefined;
    dragTargetRef.current = undefined;
    setDraggedId(undefined);
    if (previousItems && previousItems.map((item) => item.code).join(',') !== nextItems.map((item) => item.code).join(',')) {
      void persistScenarioOrder(nextItems, previousItems);
    }
  }

  function cancelScenarioDrag() {
    if (!dragCompletedRef.current && dragOriginRef.current) {
      setItemsWithLayoutAnimation(dragOriginRef.current);
    }
    dragCompletedRef.current = false;
    draggedIdRef.current = undefined;
    dragOriginRef.current = undefined;
    dragTargetRef.current = undefined;
    setDraggedId(undefined);
  }

  async function handleInstalled() {
    setInstallOpen(false);
    await loadDefinitions(1);
  }

  async function handleDemoInstalled() {
    await loadDefinitions(1);
    setCapabilityModules(await listAllCapabilities());
  }

  async function toggleEnabled(item: DefinitionItem) {
    await updateDefinition(kind, item.code, toToggleRequest(item, kind));
    message.success(item.enabled ? `${meta.name}已停用` : `${meta.name}已启用`);
    await loadDefinitions(page);
  }

  async function uninstall(item: DefinitionItem) {
    setDeletingCode(item.code);
    try {
      await uninstallDefinition(kind, item.code);
      message.success(`${meta.name}已卸载`);
      await loadDefinitions(items.length === 1 && page > 1 ? page - 1 : page);
    } finally {
      setDeletingCode(undefined);
    }
  }

  function changeViewMode(nextMode: DefinitionViewMode) {
    setViewMode(nextMode);
    localStorage.setItem(`definition-view-mode-${kind}`, nextMode);
  }

  const columns: ColumnsType<DefinitionItem> = [
    {
      title: '名称',
      width: 240,
      render: (_, item) => (
        <button type="button" className="definition-table-name" onClick={() => navigate(`${meta.basePath}/${item.code}`)}>
          <DefinitionIcon src={item.iconUrl} label={item.name} size={30} />
          <span>
            <strong>{item.name}</strong>
            <small>{item.code}</small>
          </span>
        </button>
      ),
    },
    { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '暂无说明' },
    {
      title: kind === 'scenario' ? '关联能力' : '定义内容',
      width: 250,
      render: (_, item) => kind === 'scenario' ? (
        <div className="flex flex-wrap gap-1">
          {capabilityLabelList((item as AgentScenario).capabilities, capabilityNameMap).slice(0, 2).map((label) => <AppTag key={label}>{label}</AppTag>)}
          {(item as AgentScenario).capabilities.length > 2 ? <AppTag>+{(item as AgentScenario).capabilities.length - 2}</AppTag> : null}
          {(item as AgentScenario).capabilities.length === 0 ? <AppTag>无能力</AppTag> : null}
        </div>
      ) : (
        <div className="flex flex-wrap gap-1">
          <AppTag>参数 {item.parameters?.length || 0}</AppTag>
          <AppTag>配置 {(item as AgentCapability).configParameters?.length || 0}</AppTag>
          <AppTag>说明 {item.guides?.length || 0}</AppTag>
        </div>
      ),
    },
    {
      title: '来源',
      width: 110,
      render: (_, item) => {
        const capability = kind === 'capability' ? item as AgentCapability : undefined;
        return (
          <AppTag tone="blue">
            {item.uninstallable
              ? `安装${(capability?.packageVersion || (item as AgentScenario).packageVersion) ? ` v${capability?.packageVersion || (item as AgentScenario).packageVersion}` : ''}`
              : '内置'}
          </AppTag>
        );
      },
    },
    { title: '状态', dataIndex: 'enabled', width: 90, render: (enabled) => <EnabledTag enabled={enabled} /> },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
    {
      title: '操作',
      width: kind === 'scenario' ? 250 : 190,
      fixed: 'right',
      render: (_, item) => (
        <div className="definition-table-actions">
          <Tooltip title="查看">
            <Button size="small" icon={<Eye size={14} weight="fill" />} aria-label={`查看${item.name}`} onClick={() => navigate(`${meta.basePath}/${item.code}`)} />
          </Tooltip>
          <Tooltip title={item.enabled ? '停用' : '启用'}>
            <Button
              size="small"
              icon={item.enabled ? <Prohibit size={14} weight="fill" /> : <CheckCircle size={14} weight="fill" />}
              aria-label={item.enabled ? `停用${item.name}` : `启用${item.name}`}
              onClick={() => toggleEnabled(item)}
            />
          </Tooltip>
          {kind === 'scenario' ? (
            <Tooltip title="能力配置">
              <Button size="small" icon={<GearSix size={14} weight="fill" />} aria-label={`${item.name}能力配置`} onClick={() => setRuntimeConfigScenario(item as AgentScenario)} />
            </Tooltip>
          ) : null}
          {kind === 'capability' && (item as AgentCapability).configParameters?.length ? (
            <Tooltip title="接入配置">
              <Button size="small" icon={<GearSix size={14} weight="fill" />} aria-label={`${item.name}接入配置`} onClick={() => setConfigCapability(item as AgentCapability)} />
            </Tooltip>
          ) : null}
          {item.uninstallable ? (
            <Popconfirm
              title={`卸载${meta.name}“${item.name}”？`}
              description={kind === 'capability' ? '安装包、接入配置和场景授权将被删除。' : '安装包及分析情境中的引用将被删除。'}
              okText="卸载"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              disabled={item.enabled}
              onConfirm={() => uninstall(item)}
            >
              <Tooltip title={item.enabled ? `请先停用${meta.name}` : `卸载${meta.name}`}>
                <Button danger size="small" disabled={item.enabled} loading={deletingCode === item.code} icon={<Trash size={14} weight="fill" />} aria-label={`卸载${item.name}`} />
              </Tooltip>
            </Popconfirm>
          ) : null}
        </div>
      ),
    },
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <section className={`flex min-h-0 flex-1 flex-col ${embedded ? 'bg-white' : 'rounded-md border border-neutral-200 bg-white p-3'}`}>
        <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback={meta.listTitle} path={meta.basePath} icon={meta.icon} tone={meta.tone} />
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <Button type="primary" icon={<PackageIcon size={16} weight="fill" />} onClick={() => setInstallOpen(true)}>
              {kind === 'scenario' ? '安装场景' : '安装能力'}
            </Button>
            <Segmented<DefinitionViewMode>
              className="app-view-switch management-view-switch"
              value={viewMode}
              onChange={changeViewMode}
              options={[
                { value: 'card', label: <span><SquaresFour size={13} weight="fill" />卡片</span> },
                { value: 'table', label: <span><TableIcon size={13} weight="fill" />表格</span> },
              ]}
            />
            <Button icon={<ArrowClockwise size={16} weight="fill" />} onClick={() => loadDefinitions(page)}>刷新</Button>
          </div>
        </div>
        <Spin spinning={loading} wrapperClassName="definition-list-spin min-h-0 flex-1">
          <div className="min-h-0 flex-1 overflow-y-auto pr-1">
            {items.length === 0 && !loading ? (
              <Empty className="pt-20" description={`暂无${meta.name}`} />
            ) : viewMode === 'card' ? (
              <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
                {items.map((item) => (
                  <DefinitionCard
                    key={item.code}
                    item={item}
                    kind={kind}
                    basePath={meta.basePath}
                    capabilityNameMap={capabilityNameMap}
                    onConfig={setConfigCapability}
                    onToggleEnabled={toggleEnabled}
                    onUninstall={uninstall}
                    deleting={deletingCode === item.code}
                    onRuntimeConfig={(scenario) => setRuntimeConfigScenario(scenario)}
                    reorderable={kind === 'scenario'}
                    dragging={draggedId === item.code}
                    reorderDisabled={reordering}
                    onDragStart={(event) => {
                      dragOriginRef.current = [...itemsRef.current];
                      dragCompletedRef.current = false;
                      draggedIdRef.current = item.code;
                      setDraggedId(item.code);
                      event.dataTransfer.effectAllowed = 'move';
                      event.dataTransfer.setData('text/plain', item.code);
                      const card = event.currentTarget.closest('.definition-card') as HTMLElement | null;
                      if (card) {
                        const bounds = card.getBoundingClientRect();
                        event.dataTransfer.setDragImage(
                          card,
                          Math.max(0, Math.min(bounds.width, event.clientX - bounds.left)),
                          Math.max(0, Math.min(bounds.height, event.clientY - bounds.top)),
                        );
                      }
                    }}
                    onDragOver={(event) => {
                      const sourceId = draggedIdRef.current;
                      if (sourceId !== undefined) {
                        event.preventDefault();
                        event.dataTransfer.dropEffect = 'move';
                        if (sourceId !== item.code) {
                          previewScenarioMove(sourceId, item.code);
                        }
                      }
                    }}
                    onDrop={(event) => {
                      event.preventDefault();
                      finishScenarioDrag();
                    }}
                    onDragEnd={cancelScenarioDrag}
                    onMove={(offset) => moveScenarioByOffset(item.code, offset)}
                  />
                ))}
              </div>
            ) : (
              <Table
                rowKey="code"
                columns={columns}
                dataSource={items}
                pagination={false}
                size="middle"
                scroll={{ x: 1180 }}
              />
            )}
          </div>
        </Spin>
        {total > 0 ? (
          <div className="mt-2 flex shrink-0 justify-end">
            <Pagination current={page} pageSize={pageSize} total={total} showSizeChanger={false} showTotal={(value) => `共 ${value} 条`} onChange={loadDefinitions} />
          </div>
        ) : null}
      </section>
      <Modal
        title={configCapability ? `${configCapability.name}服务接入配置` : '服务接入配置'}
        open={!!configCapability}
        footer={null}
        onCancel={() => setConfigCapability(undefined)}
        destroyOnClose
        width={920}
      >
        {configCapability ? <div className="h-[560px]"><CapabilityConfigPage embedded capability={configCapability} /></div> : null}
      </Modal>
      <ScenarioRuntimeConfigModal
        scenario={runtimeConfigScenario}
        capabilities={capabilityModules}
        onClose={() => setRuntimeConfigScenario(undefined)}
        onSaved={async () => {
          setRuntimeConfigScenario(undefined);
          await loadDefinitions(page);
        }}
      />
      {kind === 'capability' ? (
        <CapabilityInstallModal open={installOpen} onClose={() => setInstallOpen(false)} onInstalled={handleInstalled} />
      ) : (
        <ScenarioInstallModal open={installOpen} onClose={() => setInstallOpen(false)} onInstalled={handleInstalled} />
      )}
      {kind === 'scenario' ? (
        <DemoDefinitionImportModal
          offer={demoImportOffer}
          sourceOrigin={demoImportOrigin}
          onClose={() => {
            setDemoImportOffer(undefined);
            setDemoImportOrigin(undefined);
          }}
          onInstalled={handleDemoInstalled}
        />
      ) : null}
    </div>
  );
}

function isDemoDefinitionImportOffer(value: unknown): value is DemoDefinitionImportOffer {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const offer = value as Partial<DemoDefinitionImportOffer>;
  return offer.type === 'LINGXI_DEFINITION_IMPORT_OFFER'
    && typeof offer.sourceName === 'string'
    && Array.isArray(offer.capabilities)
    && Array.isArray(offer.configurations)
    && Array.isArray(offer.scenarios)
    && offer.capabilities.every((item) => typeof item?.code === 'string' && typeof item?.name === 'string' && typeof item?.packageUrl === 'string')
    && offer.configurations.every((item) => typeof item?.capabilityCode === 'string' && typeof item?.name === 'string' && !!item?.config && typeof item.config === 'object')
    && offer.scenarios.every((item) => typeof item?.code === 'string' && typeof item?.name === 'string' && typeof item?.packageUrl === 'string');
}

function ScenarioRuntimeConfigModal({
  scenario,
  capabilities,
  onClose,
  onSaved,
}: {
  scenario?: AgentScenario;
  capabilities: AgentCapability[];
  onClose: () => void;
  onSaved: (updated: AgentScenario) => void | Promise<void>;
}) {
  const [selectedCodes, setSelectedCodes] = useState<string[]>([]);
  const [capabilityCommands, setCapabilityCommands] = useState<Record<string, string[]>>({});
  const [userVisible, setUserVisible] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setSelectedCodes(scenario?.capabilities || []);
    setCapabilityCommands(scenario?.capabilityCommands || {});
    setUserVisible(scenario?.userVisible !== false);
  }, [scenario]);

  function changeCapabilities(nextCodes: string[]) {
    const previousCodes = new Set(selectedCodes);
    const nextCommands: Record<string, string[]> = {};
    for (const code of nextCodes) {
      const capability = capabilities.find((item) => item.code === code);
      if (previousCodes.has(code)) {
        nextCommands[code] = capabilityCommands[code] || [];
      } else {
        nextCommands[code] = (capability?.commands || []).map((command) => command.code);
      }
    }
    setSelectedCodes(nextCodes);
    setCapabilityCommands(nextCommands);
  }

  async function save() {
    if (!scenario) {
      return;
    }
    setSaving(true);
    try {
      const commands = Object.fromEntries(selectedCodes.map((code) => [code, capabilityCommands[code] || []]));
      const updated = await updateScenario(scenario.code, {
        enabled: scenario.enabled,
        userVisible,
        capabilities: selectedCodes,
        capabilityCommands: commands,
      });
      message.success('场景能力配置已更新');
      await onSaved(updated);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title={scenario ? `${scenario.name}能力配置` : '场景能力配置'}
      open={!!scenario}
      onCancel={onClose}
      onOk={save}
      confirmLoading={saving}
      okText="保存"
      cancelText="取消"
      width={680}
      destroyOnClose
    >
      <div className="space-y-4 py-1">
        <div className="flex items-center justify-between rounded-md border border-neutral-200 px-3 py-2">
          <div>
            <div className="text-sm font-medium text-neutral-800">用户可见</div>
            <div className="mt-0.5 text-xs text-neutral-500">控制该场景是否出现在用户可选列表中</div>
          </div>
          <Switch checked={userVisible} onChange={setUserVisible} />
        </div>
        <div>
          <div className="mb-1.5 text-sm font-medium text-neutral-800">Skill</div>
          <Select
            className="w-full"
            mode="multiple"
            value={selectedCodes}
            onChange={changeCapabilities}
            optionFilterProp="label"
            options={capabilities.map((capability) => ({
              value: capability.code,
              label: `${capability.name} (${capability.code})`,
            }))}
          />
        </div>
        {selectedCodes.map((code) => {
          const capability = capabilities.find((item) => item.code === code);
          const commands = capability?.commands || [];
          if (!commands.length) {
            return null;
          }
          return (
            <div key={code}>
              <div className="mb-1.5 text-sm font-medium text-neutral-800">{capability?.name || code}命令</div>
              <Select
                className="w-full"
                mode="multiple"
                value={capabilityCommands[code] || []}
                onChange={(values) => setCapabilityCommands((current) => ({ ...current, [code]: values }))}
                optionFilterProp="label"
                options={commands.map((command) => ({ value: command.code, label: command.name || command.code }))}
              />
            </div>
          );
        })}
      </div>
    </Modal>
  );
}

function DefinitionCard({
  item,
  kind,
  basePath,
  capabilityNameMap,
  onConfig,
  onToggleEnabled,
  onUninstall,
  deleting = false,
  onRuntimeConfig,
  reorderable = false,
  dragging = false,
  reorderDisabled = false,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  onMove,
}: {
  item: DefinitionItem;
  kind: DefinitionKind;
  basePath: string;
  capabilityNameMap: Map<string, string>;
  onConfig: (capability: AgentCapability) => void;
  onToggleEnabled: (item: DefinitionItem) => Promise<void>;
  onUninstall: (item: DefinitionItem) => Promise<void>;
  deleting?: boolean;
  onRuntimeConfig: (scenario: AgentScenario) => void;
  reorderable?: boolean;
  dragging?: boolean;
  reorderDisabled?: boolean;
  onDragStart?: (event: DragEvent<HTMLElement>) => void;
  onDragOver?: (event: DragEvent<HTMLElement>) => void;
  onDrop?: (event: DragEvent<HTMLElement>) => void;
  onDragEnd?: () => void;
  onMove?: (offset: -1 | 1) => void;
}) {
  const navigate = useNavigate();
  const capabilityLabels = kind === 'scenario'
    ? capabilityLabelList((item as AgentScenario).capabilities, capabilityNameMap)
    : [];
  const visibleCapabilityLabels = capabilityLabels.slice(0, 3);
  const detailPath = `${basePath}/${item.code}`;

  function openDetail() {
    navigate(detailPath);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      openDetail();
    }
  }

  function stopCardClick(event: MouseEvent<HTMLElement>) {
    event.stopPropagation();
  }

  function stopCardKeyDown(event: KeyboardEvent<HTMLElement>) {
    event.stopPropagation();
  }

  return (
    <article
      className={`definition-card${dragging ? ' definition-card-dragging' : ''}`}
      role="button"
      data-definition-id={item.code}
      tabIndex={0}
      onClick={openDetail}
      onKeyDown={handleKeyDown}
      onDragOver={onDragOver}
      onDrop={onDrop}
      onDragEnd={onDragEnd}
    >
      <div className="mb-2 flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2.5">
          {reorderable ? (
            <button
              type="button"
              className="definition-card-drag-handle"
              draggable={!reorderDisabled}
              aria-label={`调整${item.name}的顺序`}
              title="拖动排序，也可使用上下方向键"
              onClick={stopCardClick}
              onDragStart={onDragStart}
              onKeyDown={(event) => {
                stopCardKeyDown(event);
                if (reorderDisabled || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) {
                  return;
                }
                event.preventDefault();
                onMove?.(event.key === 'ArrowUp' ? -1 : 1);
              }}
            >
              <DotsSixVertical size={20} weight="bold" />
            </button>
          ) : null}
          <DefinitionIcon src={item.iconUrl} label={item.name} size={30} />
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold text-neutral-800">{item.name}</div>
            <div className="mt-0.5 truncate text-xs text-neutral-500">{item.code}</div>
          </div>
        </div>
        <div className="flex shrink-0 gap-1">
          <EnabledTag enabled={item.enabled} />
          <AppTag tone="blue">
            {item.uninstallable
              ? `安装${((item as AgentCapability).packageVersion || (item as AgentScenario).packageVersion) ? ` v${(item as AgentCapability).packageVersion || (item as AgentScenario).packageVersion}` : ''}`
              : '内置'}
          </AppTag>
        </div>
      </div>
      <p className="mb-2 line-clamp-1 text-sm leading-5 text-neutral-600">{item.description || '暂无说明'}</p>
      <div className="mb-2 flex flex-wrap gap-1.5 text-xs">
        <AppTag>参数 {item.parameters?.length || 0}</AppTag>
        {kind === 'capability' && item.guides?.length ? <AppTag>说明 {item.guides.length}</AppTag> : null}
        {visibleCapabilityLabels.map((label) => (
          <AppTag key={label}>{label}</AppTag>
        ))}
        {capabilityLabels.length > visibleCapabilityLabels.length && <AppTag>+{capabilityLabels.length - visibleCapabilityLabels.length}</AppTag>}
        {kind === 'scenario' && capabilityLabels.length === 0 && <AppTag>无能力</AppTag>}
      </div>
      <div className="definition-card-footer">
        <div className="definition-card-updated">更新时间：{formatTime(item.updatedAt)}</div>
        <div className="definition-card-actions" onClick={stopCardClick} onKeyDown={stopCardKeyDown}>
          <Button
            size="small"
            icon={item.enabled ? <Prohibit size={14} weight="fill" /> : <CheckCircle size={14} weight="fill" />}
            onClick={() => onToggleEnabled(item)}
          >
            {item.enabled ? '停用' : '启用'}
          </Button>
          {kind === 'scenario' ? (
            <Button size="small" icon={<GearSix size={14} weight="fill" />} onClick={() => onRuntimeConfig(item as AgentScenario)}>能力配置</Button>
          ) : null}
          {kind === 'capability' && (item as AgentCapability).configParameters?.length ? (
            <Button size="small" icon={<GearSix size={14} weight="fill" />} onClick={() => onConfig(item as AgentCapability)}>接入配置</Button>
          ) : null}
          {item.uninstallable ? (
            <Popconfirm
              title={`卸载${kind === 'scenario' ? '场景' : '能力'}“${item.name}”？`}
              description={kind === 'capability' ? '安装包、接入配置和场景授权将被删除。' : '安装包及分析情境中的引用将被删除。'}
              okText="卸载"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              disabled={item.enabled}
              onConfirm={() => onUninstall(item)}
            >
              <Button danger size="small" disabled={item.enabled} loading={deleting} icon={<Trash size={14} weight="fill" />} title={item.enabled ? `请先停用${kind === 'scenario' ? '场景' : '能力'}` : `卸载${kind === 'scenario' ? '场景' : '能力'}`}>卸载</Button>
            </Popconfirm>
          ) : null}
        </div>
      </div>
    </article>
  );
}

function DefinitionDetailPage({ kind }: { kind: DefinitionKind }) {
  const navigate = useNavigate();
  const { code } = useParams();
  const meta = getDefinitionMeta(kind);
  const listPath = kind === 'capability' ? `${meta.basePath}?tab=skill` : meta.basePath;
  const [item, setItem] = useState<DefinitionItem>();
  const [loading, setLoading] = useState(true);
  const [capabilityModules, setCapabilityModules] = useState<AgentCapability[]>([]);
  const [configOpen, setConfigOpen] = useState(false);
  const [runtimeConfigOpen, setRuntimeConfigOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    loadCapabilityModules();
    if (code) {
      setItem(undefined);
      loadDefinition(code);
    }
  }, [code, kind]);

  async function loadCapabilityModules() {
    setCapabilityModules(await listAllCapabilities());
  }

  async function loadDefinition(definitionCode: string) {
    setLoading(true);
    try {
      const result = await getDefinition(kind, definitionCode);
      setItem(result);
    } finally {
      setLoading(false);
    }
  }

  async function toggleEnabled() {
    if (!code || !item) {
      return;
    }
    const updated = await updateDefinition(kind, code, toToggleRequest(item, kind));
    setItem(updated);
    message.success(item.enabled ? `${meta.name}已停用` : `${meta.name}已启用`);
  }

  async function uninstall() {
    if (!code || !item) {
      return;
    }
    setDeleting(true);
    try {
      await uninstallDefinition(kind, code);
      message.success(`${meta.name}已卸载`);
      navigate(listPath, { replace: true });
    } finally {
      setDeleting(false);
    }
  }

  const title = `查看${meta.name}`;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <section className="flex min-h-0 flex-1 flex-col rounded-md border border-neutral-200 bg-white p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback={title} path={meta.basePath} icon={meta.icon} tone={meta.tone} />
          </div>
          <div className="flex gap-2">
            <Button icon={<CaretLeft size={16} />} onClick={() => navigate(listPath)}>返回</Button>
            {kind === 'capability' && item && (item as AgentCapability).configParameters?.length ? (
              <Button icon={<GearSix size={16} weight="fill" />} onClick={() => setConfigOpen(true)}>接入配置</Button>
            ) : null}
            {kind === 'scenario' && item ? (
              <Button icon={<GearSix size={16} weight="fill" />} onClick={() => setRuntimeConfigOpen(true)}>能力配置</Button>
            ) : null}
            {item ? (
              <Button icon={<Power size={16} weight="fill" />} onClick={toggleEnabled}>{item.enabled ? '停用' : '启用'}</Button>
            ) : null}
            {item?.uninstallable ? (
              <Popconfirm
                title={`卸载${meta.name}“${item.name}”？`}
                description={kind === 'capability' ? '安装包、接入配置和场景授权将被删除。' : '安装包及分析情境中的引用将被删除。'}
                okText="卸载"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                disabled={item.enabled}
                onConfirm={uninstall}
              >
                <Button danger disabled={item.enabled} loading={deleting} icon={<Trash size={16} weight="fill" />}>卸载</Button>
              </Popconfirm>
            ) : null}
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-hidden pr-1">
          {loading ? (
            <div className="flex h-full items-center justify-center"><Spin /></div>
          ) : item ? (
            <DefinitionReadonlyView kind={kind} item={item} capabilityModules={capabilityModules} />
          ) : null}
        </div>
      </section>
      <Modal
        title={item ? `${item.name}服务接入配置` : '服务接入配置'}
        open={configOpen}
        footer={null}
        onCancel={() => setConfigOpen(false)}
        destroyOnClose
        width={920}
      >
        {item && kind === 'capability' ? <div className="h-[560px]"><CapabilityConfigPage embedded capability={item as AgentCapability} /></div> : null}
      </Modal>
      <ScenarioRuntimeConfigModal
        scenario={runtimeConfigOpen && kind === 'scenario' ? item as AgentScenario : undefined}
        capabilities={capabilityModules}
        onClose={() => setRuntimeConfigOpen(false)}
        onSaved={(updated) => {
          setItem(updated);
          setRuntimeConfigOpen(false);
        }}
      />
    </div>
  );
}

function DefinitionReadonlyView({ kind, item, capabilityModules }: { kind: DefinitionKind; item: DefinitionItem; capabilityModules: AgentCapability[] }) {
  const capabilityNameMap = useMemo(() => new Map(capabilityModules.map((capability) => [capability.code, capability.name])), [capabilityModules]);
  const guides = (item.guides || []).filter((guide) => guide.key && guide.title && guide.content);
  const scenarioItem = kind === 'scenario' ? item as AgentScenario : undefined;
  const capabilityItem = kind === 'capability' ? item as AgentCapability : undefined;

  const tabs = [
    {
      key: 'basic',
      label: '基础信息',
      children: (
        <div className="definition-tab-scroll space-y-4">
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label="图标"><DefinitionIcon src={item.iconUrl} label={item.name} size={28} /></Descriptions.Item>
            <Descriptions.Item label="名称">{item.name}</Descriptions.Item>
            <Descriptions.Item label="编码">{item.code}</Descriptions.Item>
            {scenarioItem ? <Descriptions.Item label="类型">{scenarioItem.scenario}</Descriptions.Item> : null}
            <Descriptions.Item label="状态">{item.enabled ? '启用' : '停用'}</Descriptions.Item>
            <Descriptions.Item label="来源">
              {item.uninstallable ? '外置安装包' : kind === 'scenario' ? '内置场景' : '内置能力'}
            </Descriptions.Item>
            <Descriptions.Item label="更新时间">{formatTime(item.updatedAt)}</Descriptions.Item>
            {scenarioItem ? (
              <>
                <Descriptions.Item label="用户可见">{scenarioItem.userVisible === false ? '否' : '是'}</Descriptions.Item>
                <Descriptions.Item label="来源去重">{scenarioItem.uniqueBySource ? '是' : '否'}</Descriptions.Item>
                <Descriptions.Item label="结果格式">{scenarioItem.resultFormat || 'markdown-sections'}</Descriptions.Item>
                <Descriptions.Item label="队列优先级">{scenarioItem.queuePriority ?? 100}</Descriptions.Item>
              </>
            ) : null}
            <Descriptions.Item label="说明" span={2}>{item.description || '-'}</Descriptions.Item>
            <Descriptions.Item label="参数" span={2}>
              <ParameterDefinitionList parameters={item.parameters || []} />
            </Descriptions.Item>
            {kind === 'capability' ? (
              <Descriptions.Item label="接入参数" span={2}>
                <ParameterDefinitionList parameters={(item as AgentCapability).configParameters || []} emptyText="无需接入配置" />
              </Descriptions.Item>
            ) : null}
            {capabilityItem ? (
              <Descriptions.Item label="能力命令" span={2}>
                {(capabilityItem.commands || []).length ? (
                  <div className="definition-command-list">
                    {(capabilityItem.commands || []).map((command) => (
                      <div className="definition-command-item" key={command.code}>
                        <span className="definition-command-name">
                          <RuntimeActionIconView icon={command.icon || 'WRENCH'} size={16} />
                          <strong>{command.name || command.code}</strong>
                        </span>
                        <code>{command.command}</code>
                      </div>
                    ))}
                  </div>
                ) : <span className="text-neutral-400">暂无命令</span>}
              </Descriptions.Item>
            ) : null}
            {kind === 'scenario' ? (
              <Descriptions.Item label="能力" span={2}>
                <div className="flex flex-wrap gap-1.5">
                  {(scenarioItem?.capabilities || []).map((code) => (
                    <AppTag key={code}>{capabilityNameMap.get(code) || code}</AppTag>
                  ))}
                  {(!scenarioItem?.capabilities || scenarioItem.capabilities.length === 0) && '-'}
                </div>
              </Descriptions.Item>
            ) : null}
            {scenarioItem && Object.keys(scenarioItem.capabilityCommands || {}).length > 0 ? (
              <Descriptions.Item label="能力命令" span={2}>
                <div className="space-y-2">
                  {Object.entries(scenarioItem.capabilityCommands || {}).map(([capabilityCode, commandCodes]) => {
                    const capability = capabilityModules.find((current) => current.code === capabilityCode);
                    const commandNames = new Map((capability?.commands || []).map((command) => [command.code, command.name || command.code]));
                    return (
                      <div key={capabilityCode} className="flex min-w-0 items-start gap-2">
                        <span className="w-28 flex-none pt-0.5 text-xs text-neutral-500">{capability?.name || capabilityCode}</span>
                        <div className="flex min-w-0 flex-1 flex-wrap gap-1.5">
                          {commandCodes.map((commandCode) => <AppTag key={commandCode}>{commandNames.get(commandCode) || commandCode}</AppTag>)}
                          {commandCodes.length === 0 && <span className="text-neutral-400">未开放命令</span>}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </Descriptions.Item>
            ) : null}
          </Descriptions>
        </div>
      ),
    },
    { key: 'prompt', label: '提示词', children: <MarkdownPanel content={item.promptText || '未配置'} /> },
  ];

  if (kind === 'capability' && guides.length > 0) {
    tabs.push({
      key: 'guides',
      label: '使用说明',
      children: <DefinitionGuideView guides={guides} />,
    });
  }

  return <Tabs className="definition-detail-tabs" items={tabs} />;
}

function DefinitionGuideView({ guides }: { guides: AgentDefinitionGuide[] }) {
  const orderedGuides = useMemo(
    () => [...guides].sort((left, right) => (left.sortOrder ?? 0) - (right.sortOrder ?? 0)),
    [guides],
  );
  const [activeGuideKey, setActiveGuideKey] = useState(orderedGuides[0]?.key);
  const activeGuide = orderedGuides.find((guide) => guide.key === activeGuideKey) || orderedGuides[0];

  useEffect(() => {
    if (!orderedGuides.some((guide) => guide.key === activeGuideKey)) {
      setActiveGuideKey(orderedGuides[0]?.key);
    }
  }, [activeGuideKey, orderedGuides]);

  if (!activeGuide) {
    return null;
  }

  return (
    <div className="definition-guide-layout">
      <nav className="definition-guide-nav" aria-label="使用说明目录">
        {orderedGuides.map((guide) => {
          const active = guide.key === activeGuide.key;
          return (
            <button
              key={guide.key}
              type="button"
              className={`definition-guide-nav-item${active ? ' definition-guide-nav-item-active' : ''}`}
              aria-current={active ? 'page' : undefined}
              onClick={() => setActiveGuideKey(guide.key)}
            >
              <span className="definition-guide-nav-title">{guide.title}</span>
              {guide.description ? <span className="definition-guide-nav-description">{guide.description}</span> : null}
            </button>
          );
        })}
      </nav>
      <article className="definition-guide-content">
        <header className="definition-guide-content-header">
          <h2>{activeGuide.title}</h2>
          {activeGuide.description ? <p>{activeGuide.description}</p> : null}
        </header>
        <div className="definition-guide-content-body">
          <MarkdownText content={activeGuide.content} />
        </div>
      </article>
    </div>
  );
}

function ParameterDefinitionList({ parameters, emptyText = '未配置' }: { parameters: AgentDefinitionParameter[]; emptyText?: string }) {
  if (!parameters.length) {
    return <span className="text-neutral-400">{emptyText}</span>;
  }
  return (
    <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
      {parameters.map((parameter) => (
        <div key={parameter.key} className="rounded-md border border-neutral-200 bg-neutral-50/70 px-3 py-2">
          <div className="flex min-w-0 items-center gap-2">
            <span className="truncate text-sm font-medium text-neutral-800">{parameter.name || parameter.key}</span>
            {parameter.required ? <AppTag tone="rose">必填</AppTag> : null}
            {parameter.visible === false ? <AppTag tone="slate">隐藏</AppTag> : null}
          </div>
          <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-neutral-500">
            <span>{parameter.key}</span>
            <span>{parameter.type || 'text'}</span>
            {parameter.defaultValue ? <span>默认：{parameter.defaultValue}</span> : null}
          </div>
          {parameter.description ? <div className="mt-1 text-xs leading-5 text-neutral-500">{parameter.description}</div> : null}
          {parameter.options?.length ? (
            <div className="mt-2 flex flex-wrap gap-1">
              {parameter.options.map((option) => (
                <Tag key={option.value} className="bg-white text-neutral-600">{option.label}</Tag>
              ))}
            </div>
          ) : null}
        </div>
      ))}
    </div>
  );
}

function MarkdownPanel({ content }: { content: string }) {
  return (
    <div className="definition-tab-scroll rounded-md bg-neutral-50 p-3">
      <MarkdownText content={content} />
    </div>
  );
}

function getDefinitionMeta(kind: DefinitionKind) {
  return kind === 'scenario'
    ? { name: '场景', listTitle: '场景管理', basePath: '/admin/scenarios', icon: 'definition', tone: 'violet' } as const
    : { name: '能力', listTitle: '能力管理', basePath: '/admin/capabilities', icon: 'capabilityConfig', tone: 'teal' } as const;
}

function definitionViewMode(kind: DefinitionKind): DefinitionViewMode {
  return localStorage.getItem(`definition-view-mode-${kind}`) === 'table' ? 'table' : 'card';
}

function capabilityLabelList(codes: string[] | undefined, capabilityNameMap: Map<string, string>) {
  const labels = new Set<string>();
  for (const code of codes || []) {
    const label = capabilityNameMap.get(code) || code;
    if (!label) {
      continue;
    }
    labels.add(label);
  }
  return Array.from(labels);
}

function toToggleRequest(item: DefinitionItem, kind: DefinitionKind): DefinitionStateUpdateRequest {
  return kind === 'scenario'
    ? {
        enabled: !item.enabled,
        userVisible: (item as AgentScenario).userVisible !== false,
        capabilities: (item as AgentScenario).capabilities || [],
        capabilityCommands: (item as AgentScenario).capabilityCommands || {},
      }
    : { enabled: !item.enabled };
}

async function getDefinition(kind: DefinitionKind, code: string): Promise<DefinitionItem> {
  return kind === 'scenario' ? getScenario(code) : getCapability(code);
}

async function updateDefinition(kind: DefinitionKind, code: string,
                                payload: DefinitionStateUpdateRequest): Promise<DefinitionItem> {
  return kind === 'scenario'
    ? updateScenario(code, payload as AgentScenarioStateUpdateRequest)
    : updateCapability(code, payload as AgentCapabilityStateUpdateRequest);
}

async function uninstallDefinition(kind: DefinitionKind, code: string): Promise<void> {
  if (kind === 'scenario') {
    await uninstallScenario(code);
  } else {
    await uninstallCapability(code);
  }
}
