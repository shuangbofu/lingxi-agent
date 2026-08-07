import { useEffect, useMemo, useRef, useState } from 'react';
import { AutoComplete, Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Switch, message } from 'antd';
import '../styles/settings.css';
import '../styles/profile.css';
import { ArrowClockwise, CaretLeft, Cpu, PencilSimple, Plus, Trash } from '@phosphor-icons/react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  checkModelEndpoint,
  createModelProfile,
  createModelProvider,
  createPricingPlan,
  deleteModelProfile,
  deleteModelProvider,
  deletePricingPlan,
  getAgentRuntimes,
  getModelCatalog,
  updateModelProfile,
  updateModelProvider,
  updatePricingPlan,
} from '../api/lingxi';
import type {
  AgentRuntimeDescriptor,
  ModelCatalog,
  ModelConfigScope,
  RuntimeModelProfile,
  RuntimeModelProtocol,
  RuntimeModelProvider,
  RuntimePricingPlan,
  RuntimeProviderType,
  RuntimeProviderTypeOption,
} from '../types/api';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { ProviderTypeIcon } from '../components/ProviderTypeIcon';
import { invalidateRuntimeModes } from '../hooks/useRuntimeModes';

export function ModelManagementPage({ scope }: { scope: ModelConfigScope }) {
  const navigate = useNavigate();
  const location = useLocation();
  const personal = scope === 'PERSONAL';
  const returnState = modelPageReturnState(location.state);
  const autoCreateProviderType = modelPageProviderType(location.state);
  const autoCreateHandled = useRef(false);
  const [catalog, setCatalog] = useState<ModelCatalog>();
  const [runtimes, setRuntimes] = useState<AgentRuntimeDescriptor[]>([]);
  const [selectedProviderId, setSelectedProviderId] = useState<string>();
  const [providerModalOpen, setProviderModalOpen] = useState(false);
  const [modelModalOpen, setModelModalOpen] = useState(false);
  const [pricingModalOpen, setPricingModalOpen] = useState(false);
  const [editingProviderId, setEditingProviderId] = useState<string>();
  const [editingModelId, setEditingModelId] = useState<string>();
  const [editingPricingPlanId, setEditingPricingPlanId] = useState<string>();
  const [togglingModelId, setTogglingModelId] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [checking, setChecking] = useState(false);
  const [quickSetup, setQuickSetup] = useState(false);
  const [detectedModels, setDetectedModels] = useState<Record<string, string[]>>({});
  const [providerForm] = Form.useForm<RuntimeModelProvider>();
  const [modelForm] = Form.useForm<RuntimeModelProfile>();
  const [pricingForm] = Form.useForm<RuntimePricingPlan>();
  const providerDraftType = Form.useWatch('providerType', providerForm);
  const modelProviderId = Form.useWatch('providerId', modelForm);
  const modelReasoningEffort = Form.useWatch('reasoningEffort', modelForm);

  const providers = catalog?.providers || [];
  const models = catalog?.models || [];
  const pricingPlans = catalog?.pricingPlans || [];
  const providerTypes = catalog?.providerTypes || [];
  const selectedProvider = providers.find((item) => item.id === selectedProviderId);
  const selectedModels = models.filter((item) => item.providerId === selectedProviderId);
  const selectedPricingPlans = pricingPlans.filter((item) => item.providerId === selectedProviderId);
  const providerDraftDefinition = providerTypes.find((item) => item.value === providerDraftType);
  const modelProvider = providers.find((item) => item.id === modelProviderId);
  const modelProviderDefinition = providerTypes.find((item) => item.value === modelProvider?.providerType);
  const reasoningOptions = modelProviderDefinition?.reasoningEffortOptions || modelProvider?.reasoningEffortOptions || [];
  const reasoningDisableOption = reasoningOptions.find((item) => item.disablesReasoning);
  const reasoningDisabled = Boolean(reasoningDisableOption && modelReasoningEffort === reasoningDisableOption.value);

  useEffect(() => {
    void loadCatalog(scope);
  }, [scope]);

  useEffect(() => {
    getAgentRuntimes().then(setRuntimes);
  }, []);

  async function loadCatalog(nextScope = scope) {
    const result = await getModelCatalog(nextScope);
    setCatalog(result);
    setSelectedProviderId((current) => result.providers.some((item) => item.id === current)
      ? current
      : result.providers[0]?.id);
    if (personal && autoCreateProviderType && !autoCreateHandled.current) {
      autoCreateHandled.current = true;
      setQuickSetup(true);
      openCreateProvider(autoCreateProviderType, result.providerTypes);
    }
    return result;
  }

  function openCreateProvider(providerType?: RuntimeProviderType, definitions = providerTypes) {
    const definition = definitions.find((item) => item.value === providerType);
    setEditingProviderId(undefined);
    providerForm.resetFields();
    providerForm.setFieldsValue({
      providerType,
      name: definition?.label || '',
      baseUrl: definition?.defaultBaseUrl || '',
      apiKey: '',
      enabled: true,
    });
    setProviderModalOpen(true);
  }

  function openEditProvider(provider: RuntimeModelProvider) {
    setEditingProviderId(provider.id);
    providerForm.resetFields();
    providerForm.setFieldsValue({ ...provider, apiKey: undefined });
    setProviderModalOpen(true);
  }

  function applyProviderType(providerType: RuntimeProviderType) {
    const definition = providerTypes.find((item) => item.value === providerType);
    if (definition) {
      providerForm.setFieldsValue({
        providerType,
        name: definition.label,
        baseUrl: definition.defaultBaseUrl,
      });
    }
  }

  async function saveProvider() {
    const values = await providerForm.validateFields();
    setSaving(true);
    try {
      let savedProvider: RuntimeModelProvider;
      if (editingProviderId) {
        savedProvider = await updateModelProvider(scope, editingProviderId, { ...values, id: editingProviderId });
      } else {
        savedProvider = await createModelProvider(scope, values);
      }
      const nextCatalog = await loadCatalog();
      invalidateRuntimeModes();
      setProviderModalOpen(false);
      message.success(editingProviderId ? '供应商已更新' : '供应商已添加');
      if (!editingProviderId && quickSetup) {
        const suggestedModel = detectedModels[values.baseUrl]?.[0];
        setQuickSetup(false);
        setSelectedProviderId(savedProvider.id);
        openCreateModel(savedProvider, suggestedModel, nextCatalog.providerTypes);
      }
    } finally {
      setSaving(false);
    }
  }

  async function removeProvider(provider: RuntimeModelProvider) {
    await deleteModelProvider(scope, provider.id);
    await loadCatalog();
    invalidateRuntimeModes();
    message.success('供应商已删除');
  }

  async function checkProvider() {
    await providerForm.validateFields(['baseUrl', 'apiKey']);
    const values = providerForm.getFieldsValue(true);
    setChecking(true);
    try {
      const result = await checkModelEndpoint({
        providerId: editingProviderId,
        baseUrl: values.baseUrl,
        apiKey: values.apiKey,
      });
      if (!result.success) {
        Modal.error({ title: '检测失败', content: result.message || '无法访问模型服务' });
        return;
      }
      const key = editingProviderId || values.baseUrl;
      setDetectedModels((current) => ({ ...current, [key]: result.models || [] }));
      message.success(result.models?.length ? `检测通过，获取到 ${result.models.length} 个模型` : '连接成功，供应商未返回模型列表');
    } finally {
      setChecking(false);
    }
  }

  function openCreateModel(provider = selectedProvider, suggestedModel?: string,
                           definitions: RuntimeProviderTypeOption[] = providerTypes) {
    if (!provider) return;
    const definition = definitions.find((item) => item.value === provider.providerType);
    setEditingModelId(undefined);
    modelForm.resetFields();
    modelForm.setFieldsValue({
      providerId: provider.id,
      name: suggestedModel || '',
      model: suggestedModel || '',
      protocol: definition?.defaultProtocol || 'CHAT_COMPLETIONS',
      contextWindowTokens: 128000,
      enabled: true,
    } as RuntimeModelProfile);
    setModelModalOpen(true);
  }

  function openEditModel(model: RuntimeModelProfile) {
    setEditingModelId(model.id);
    modelForm.resetFields();
    modelForm.setFieldsValue(model);
    setModelModalOpen(true);
  }

  async function saveModel() {
    const values = await modelForm.validateFields();
    setSaving(true);
    try {
      if (editingModelId) {
        await updateModelProfile(scope, editingModelId, { ...values, id: editingModelId });
      } else {
        await createModelProfile(scope, values);
      }
      await loadCatalog();
      invalidateRuntimeModes();
      setModelModalOpen(false);
      message.success(editingModelId ? '模型已更新' : '模型已添加');
    } finally {
      setSaving(false);
    }
  }

  async function removeModel(model: RuntimeModelProfile) {
    await deleteModelProfile(scope, model.id);
    await loadCatalog();
    invalidateRuntimeModes();
    message.success('模型已删除');
  }

  async function toggleModel(model: RuntimeModelProfile) {
    setTogglingModelId(model.id);
    try {
      await updateModelProfile(scope, model.id, { ...model, enabled: !model.enabled });
      await loadCatalog();
      invalidateRuntimeModes();
      message.success(model.enabled ? '模型已关闭' : '模型已启用');
    } finally {
      setTogglingModelId(undefined);
    }
  }

  async function refreshModelOptions() {
    if (!modelProviderId) return;
    setChecking(true);
    try {
      const result = await checkModelEndpoint({ providerId: modelProviderId });
      if (!result.success) {
        Modal.error({ title: '模型列表获取失败', content: result.message || '无法获取模型列表' });
        return;
      }
      setDetectedModels((current) => ({ ...current, [modelProviderId]: result.models || [] }));
      message.success(result.models?.length ? `已刷新 ${result.models.length} 个模型` : '供应商未返回模型列表');
    } finally {
      setChecking(false);
    }
  }

  function openCreatePricingPlan() {
    if (!selectedProvider) return;
    setEditingPricingPlanId(undefined);
    pricingForm.resetFields();
    pricingForm.setFieldsValue({
      name: `${selectedProvider.name} 默认价格`,
      providerId: selectedProvider.id,
      modelProfileId: undefined,
      pricing: { currency: 'CNY', timeZone: 'Asia/Shanghai', scheduleRules: [] },
    });
    setPricingModalOpen(true);
  }

  function openEditPricingPlan(plan: RuntimePricingPlan) {
    setEditingPricingPlanId(plan.id);
    pricingForm.resetFields();
    pricingForm.setFieldsValue(plan);
    setPricingModalOpen(true);
  }

  async function savePricing() {
    const values = await pricingForm.validateFields();
    setSaving(true);
    try {
      if (editingPricingPlanId) {
        await updatePricingPlan(scope, editingPricingPlanId, { ...values, id: editingPricingPlanId });
      } else {
        await createPricingPlan(scope, values);
      }
      await loadCatalog();
      setPricingModalOpen(false);
      message.success(editingPricingPlanId ? '计价方案已更新' : '计价方案已添加');
    } finally {
      setSaving(false);
    }
  }

  async function removePricing(plan: RuntimePricingPlan) {
    await deletePricingPlan(scope, plan.id);
    await loadCatalog();
    message.success('计价方案已删除');
  }

  const detectedKey = editingProviderId || providerForm.getFieldValue('baseUrl');
  const detectedProviderModels = detectedModels[detectedKey] || [];
  const modelOptions = detectedModels[modelProviderId || ''] || [];

  return (
    <div className={personal ? 'profile-page personal-model-page' : 'settings-page platform-model-page'}>
      <section className={personal ? 'profile-content personal-model-content' : 'settings-section platform-model-content rounded-md bg-white p-4'}>
        {personal ? (
          <div className="profile-headline">
            <Cpu size={20} weight="fill" />
            <div className="min-w-0"><h1>模型配置</h1></div>
            <div className="profile-actions">
              <Button icon={<ArrowClockwise size={16} weight="bold" />} onClick={() => loadCatalog()}>刷新</Button>
              <Button
                icon={<CaretLeft size={16} />}
                onClick={() => navigate(returnState.path, { replace: true, state: returnState.state })}
              >返回</Button>
            </div>
          </div>
        ) : (
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 text-base font-semibold">
              <PageHeaderTitle fallback="平台模型管理" path="/admin/models" icon="model" tone="cyan" />
            </div>
            <Button icon={<ArrowClockwise size={16} weight="bold" />} onClick={() => loadCatalog()}>刷新</Button>
          </div>
        )}
        <section className={personal ? 'personal-model-section' : 'settings-config-section platform-model-section'}>
          <div className="settings-model-manager">
            <aside className="settings-provider-panel">
              <div className="settings-manager-panel-heading">
                <span>供应商</span>
                <Button type="text" icon={<Plus size={16} weight="bold" />} title="添加供应商" aria-label="添加供应商" onClick={() => openCreateProvider()} />
              </div>
              <div className="settings-provider-list">
                {providers.map((provider) => (
                  <div
                    className={provider.id === selectedProviderId ? 'settings-provider-item settings-provider-item-active' : 'settings-provider-item'}
                    key={provider.id}
                    role="button"
                    tabIndex={0}
                    onClick={() => setSelectedProviderId(provider.id)}
                    onKeyDown={(event) => event.key === 'Enter' && setSelectedProviderId(provider.id)}
                  >
                    <div className={`settings-provider-type-icon settings-provider-type-icon-${provider.providerType.toLowerCase()}`}>
                      <ProviderTypeIcon
                        icon={providerTypes.find((item) => item.value === provider.providerType)?.icon}
                        darkIcon={providerTypes.find((item) => item.value === provider.providerType)?.darkIcon}
                        size={36}
                      />
                    </div>
                    <div className="settings-provider-item-main">
                      <div className="settings-model-name-row">
                        <span className="settings-provider-name">{provider.name}</span>
                        <span className={provider.enabled ? 'settings-model-state settings-model-state-on' : 'settings-model-state'}>
                          {provider.enabled ? '已启用' : '已停用'}
                        </span>
                      </div>
                      <div className="settings-provider-meta">{models.filter((item) => item.providerId === provider.id).length} 个模型 · {provider.maxConcurrency ? `并行度 ${provider.maxConcurrency}` : '继承平台'}</div>
                    </div>
                    <div className="settings-provider-actions" onClick={(event) => event.stopPropagation()}>
                      <Button type="text" icon={<PencilSimple size={15} weight="bold" />} title="编辑供应商" onClick={() => openEditProvider(provider)} />
                      <Popconfirm title="确认删除该供应商？" onConfirm={() => removeProvider(provider)}>
                        <Button type="text" danger icon={<Trash size={15} weight="bold" />} title="删除供应商" />
                      </Popconfirm>
                    </div>
                  </div>
                ))}
                {!providers.length && <div className="settings-manager-empty">暂无供应商</div>}
              </div>
            </aside>
            <div className="settings-provider-model-panel">
              <div className="settings-manager-panel-heading settings-provider-model-heading">
                <div>
                  <div className="settings-provider-model-title">{selectedProvider?.name || '模型'}</div>
                  {selectedProvider && <div className="settings-provider-model-caption">{selectedProvider.baseUrl}</div>}
                </div>
                <Button icon={<Plus size={16} weight="bold" />} disabled={!selectedProvider} onClick={() => openCreateModel()}>添加模型</Button>
              </div>
              <div className="settings-provider-model-list">
                {selectedModels.map((model) => (
                  <div className="settings-model-row" key={model.id}>
                    <div className="settings-model-main">
                      <div className="settings-model-name-row">
                        <span className="settings-model-name">{model.name}</span>
                        <span className={model.enabled ? 'settings-model-state settings-model-state-on' : 'settings-model-state'}>{model.enabled ? '已启用' : '已停用'}</span>
                      </div>
                      <div className="settings-model-identifier">{model.model}</div>
                    </div>
                    <div className="settings-model-facts">
                      <span>{protocolText(model.protocol)}</span>
                      <span>{formatTokenCapacity(model.contextWindowTokens)}</span>
                      <span>{compatibleRuntimeNames(model.protocol, runtimes)}</span>
                      <span>{model.imageInputSupported ? '支持图片' : '仅文本'}</span>
                    </div>
                    <div className="settings-model-actions">
                      <Switch
                        size="small"
                        checked={model.enabled}
                        loading={togglingModelId === model.id}
                        aria-label={model.enabled ? '关闭模型' : '启用模型'}
                        onChange={() => toggleModel(model)}
                      />
                      <Button type="text" icon={<PencilSimple size={16} weight="bold" />} title="编辑模型" onClick={() => openEditModel(model)} />
                      <Popconfirm title="确认删除该模型？" onConfirm={() => removeModel(model)}>
                        <Button type="text" danger icon={<Trash size={16} weight="bold" />} title="删除模型" />
                      </Popconfirm>
                    </div>
                  </div>
                ))}
                {selectedProvider && !selectedModels.length && <div className="settings-manager-empty">该供应商下暂无模型</div>}
                {!selectedProvider && <div className="settings-manager-empty">请先添加供应商</div>}
              </div>
              <div className="settings-provider-pricing">
                <div className="settings-model-toolbar">
                  <div>
                    <div className="settings-config-section-heading">供应商计价</div>
                    <div className="settings-config-section-caption">未选择模型时作为该供应商默认价格；模型专属价格优先。</div>
                  </div>
                  <Button icon={<Plus size={16} weight="bold" />} disabled={!selectedProvider} onClick={openCreatePricingPlan}>添加价格</Button>
                </div>
                <div className="settings-pricing-list">
                  {selectedPricingPlans.map((plan) => (
                    <div className="settings-model-row" key={plan.id}>
                      <div className="settings-model-main">
                        <div className="settings-model-name">{plan.name}</div>
                        <div className="settings-model-identifier">{plan.modelProfileId ? models.find((item) => item.id === plan.modelProfileId)?.name || '模型已删除' : '供应商默认价格'}</div>
                      </div>
                      <div className="settings-model-facts">
                        <span>命中 {formatPrice(plan.pricing.cacheHitInputPerMillion)}</span>
                        <span>未命中 {formatPrice(plan.pricing.cacheMissInputPerMillion)}</span>
                        <span>输出 {formatPrice(plan.pricing.outputPerMillion)}</span>
                        {plan.pricing.scheduleRules?.map((rule) => (
                          <span key={`${rule.name}-${rule.multiplier}`}>{pricingRuleText(rule)}</span>
                        ))}
                      </div>
                      <div className="settings-model-actions">
                        <Button type="text" icon={<PencilSimple size={16} weight="bold" />} title="编辑价格" onClick={() => openEditPricingPlan(plan)} />
                        <Popconfirm title="确认删除该价格？" onConfirm={() => removePricing(plan)}>
                          <Button type="text" danger icon={<Trash size={16} weight="bold" />} title="删除价格" />
                        </Popconfirm>
                      </div>
                    </div>
                  ))}
                  {selectedProvider && !selectedPricingPlans.length && <div className="settings-pricing-empty">该供应商暂无计价配置</div>}
                  {!selectedProvider && <div className="settings-pricing-empty">请先添加供应商</div>}
                </div>
              </div>
            </div>
          </div>
        </section>
      </section>

      <Modal title={editingProviderId ? '编辑供应商' : '添加供应商'} open={providerModalOpen} width={620} confirmLoading={saving} onCancel={() => setProviderModalOpen(false)} onOk={saveProvider} okText="保存" cancelText="取消">
        <Form form={providerForm} layout="vertical" className="settings-model-form">
          <div className="settings-form-grid">
            <Form.Item label="供应商类型" name="providerType" rules={[{ required: true, message: '请选择供应商类型' }]}>
              <Select placeholder="请选择供应商类型" onChange={applyProviderType} options={providerTypes.map((item) => ({
                value: item.value,
                label: <span className="settings-provider-type-option"><ProviderTypeIcon icon={item.icon} darkIcon={item.darkIcon} /><span>{item.label}</span></span>,
              }))} />
            </Form.Item>
            <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入供应商名称' }]}><Input placeholder="例如：OpenAI" /></Form.Item>
            <Form.Item className="settings-field-wide" label="服务地址" name="baseUrl" rules={[{ required: true, message: '请输入服务地址' }]}><Input placeholder="https://api.example.com/v1" /></Form.Item>
            <Form.Item
              className="settings-field-wide"
              label="API Key"
              name="apiKey"
              rules={editingProviderId && providers.find((item) => item.id === editingProviderId)?.apiKeyConfigured ? [] : [{ required: true, message: '请输入 API Key' }]}
              extra={editingProviderId ? '留空表示不修改当前凭证。' : undefined}
            ><Input.Password placeholder={editingProviderId ? '留空不修改' : '请输入 API Key'} /></Form.Item>
            {providerDraftDefinition && <div className="settings-field-wide settings-provider-capability">支持协议：{providerDraftDefinition.supportedProtocols.map(protocolText).join('、')} · {providerDraftDefinition.imageInputSupported ? '支持图片输入' : '仅文本输入'}</div>}
            <Form.Item label="并行度" name="maxConcurrency" extra="留空时继承平台并行度。"><InputNumber className="w-full" min={1} max={20} precision={0} /></Form.Item>
            <Form.Item label="启用" name="enabled" valuePropName="checked"><Switch /></Form.Item>
            <Form.Item className="settings-field-wide" label="公共提示词" name="instructionPrompt"><Input.TextArea rows={4} maxLength={10000} showCount /></Form.Item>
            <div className="settings-field-wide settings-model-check-action"><Button loading={checking} onClick={checkProvider}>检测连接并获取模型</Button></div>
            {!!detectedProviderModels.length && <div className="settings-field-wide settings-provider-model-result"><div className="settings-provider-model-result-heading">已获取模型（{detectedProviderModels.length}）</div><div className="settings-provider-model-result-list">{detectedProviderModels.map((item) => <span key={item}>{item}</span>)}</div></div>}
          </div>
        </Form>
      </Modal>

      <Modal title={editingModelId ? '编辑模型' : '添加模型'} open={modelModalOpen} width={640} confirmLoading={saving} onCancel={() => setModelModalOpen(false)} onOk={saveModel} okText="保存" cancelText="取消">
        <Form form={modelForm} layout="vertical" className="settings-model-form">
          <Form.Item name="providerId" hidden><Input /></Form.Item>
          <div className="settings-form-grid">
            <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入模型名称' }]}><Input placeholder="例如：GPT-5" /></Form.Item>
            <Form.Item label="协议" name="protocol" rules={[{ required: true, message: '请选择协议' }]}><Select options={(modelProviderDefinition?.supportedProtocols || []).map((item) => ({ label: protocolText(item), value: item }))} /></Form.Item>
            <Form.Item className="settings-field-wide" label="说明" name="description"><Input placeholder="模型用途或特点" /></Form.Item>
            <Form.Item label="模型标识" name="model" rules={[{ required: true, message: '请输入模型标识' }]}>
              <AutoComplete options={uniqueModelOptions(modelOptions)} placeholder="例如：gpt-5" filterOption />
            </Form.Item>
            <Form.Item label="上下文窗口" name="contextWindowTokens" rules={[{ required: true, message: '请输入上下文窗口' }]}><InputNumber className="w-full" min={16000} max={10000000} precision={0} addonAfter="Token" /></Form.Item>
            <div className="settings-field-wide"><Button icon={<ArrowClockwise size={16} />} loading={checking} onClick={refreshModelOptions}>刷新供应商模型列表</Button></div>
            {reasoningDisableOption && <Form.Item label="思考模式"><Switch checked={!reasoningDisabled} onChange={(checked) => modelForm.setFieldValue('reasoningEffort', checked ? reasoningOptions.find((item) => !item.disablesReasoning)?.value : reasoningDisableOption.value)} /></Form.Item>}
            <Form.Item label="推理强度" name="reasoningEffort"><Select allowClear disabled={reasoningDisabled || !reasoningOptions.length} options={reasoningOptions.filter((item) => !item.disablesReasoning)} placeholder="使用模型默认值" /></Form.Item>
            <Form.Item label="并行度" name="maxConcurrency" extra="留空时继承供应商并行度。"><InputNumber className="w-full" min={1} max={20} precision={0} /></Form.Item>
            <Form.Item className="settings-field-wide" label="附加提示词" name="instructionPrompt"><Input.TextArea rows={4} maxLength={10000} showCount /></Form.Item>
            <Form.Item label="启用" name="enabled" valuePropName="checked"><Switch /></Form.Item>
          </div>
        </Form>
      </Modal>

      <Modal title={editingPricingPlanId ? '编辑计价方案' : '添加计价方案'} open={pricingModalOpen} width={760} confirmLoading={saving} onCancel={() => setPricingModalOpen(false)} onOk={savePricing} okText="保存" cancelText="取消">
        <Form form={pricingForm} layout="vertical" className="settings-model-form">
          <Form.Item name="providerId" hidden><Input /></Form.Item>
          <Form.Item name={['pricing', 'currency']} hidden><Input /></Form.Item>
          <Form.Item name={['pricing', 'timeZone']} hidden><Input /></Form.Item>
          <div className="settings-form-grid">
            <Form.Item className="settings-field-wide" label="方案名称" name="name" rules={[{ required: true, message: '请输入方案名称' }]}><Input placeholder="例如：官方标准价格" /></Form.Item>
            <Form.Item className="settings-field-wide" label="适用模型" name="modelProfileId" extra="不选择时作为该供应商下未单独计价模型的默认价格。">
              <Select allowClear placeholder="供应商默认价格" options={selectedModels.map((item) => ({ label: item.name, value: item.id }))} />
            </Form.Item>
            <Form.Item label="缓存命中输入" name={['pricing', 'cacheHitInputPerMillion']} rules={[{ required: true, message: '请输入单价' }]}><InputNumber className="w-full" min={0} precision={6} controls={false} addonAfter="元/百万" /></Form.Item>
            <Form.Item label="缓存未命中输入" name={['pricing', 'cacheMissInputPerMillion']} rules={[{ required: true, message: '请输入单价' }]}><InputNumber className="w-full" min={0} precision={6} controls={false} addonAfter="元/百万" /></Form.Item>
            <Form.Item label="输出" name={['pricing', 'outputPerMillion']} rules={[{ required: true, message: '请输入单价' }]}><InputNumber className="w-full" min={0} precision={6} controls={false} addonAfter="元/百万" /></Form.Item>
          </div>
          <Form.List name={['pricing', 'scheduleRules']}>
            {(ruleFields, { add: addRule, remove: removeRule }) => (
              <section className="settings-pricing-rules">
                <div className="settings-pricing-rules-heading">
                  <div>
                    <strong>时段规则</strong>
                    <span>命中时段后，基础单价统一乘以对应倍率。</span>
                  </div>
                  <Button icon={<Plus size={15} weight="bold" />} onClick={() => addRule({ name: '时段价格', multiplier: 1, timeRanges: [{}] })}>添加规则</Button>
                </div>
                {ruleFields.map((ruleField) => (
                  <div className="settings-pricing-rule" key={ruleField.key}>
                    <div className="settings-pricing-rule-main">
                      <Form.Item label="规则名称" name={[ruleField.name, 'name']} rules={[{ required: true, message: '请输入规则名称' }]}>
                        <Input placeholder="例如：高峰价" />
                      </Form.Item>
                      <Form.Item label="价格倍率" name={[ruleField.name, 'multiplier']} rules={[{ required: true, message: '请输入价格倍率' }]}>
                        <InputNumber className="w-full" min={0.000001} precision={6} controls={false} addonAfter="倍" />
                      </Form.Item>
                      <Button type="text" danger className="settings-pricing-remove" icon={<Trash size={16} weight="bold" />} title="删除规则" onClick={() => removeRule(ruleField.name)} />
                    </div>
                    <Form.List name={[ruleField.name, 'timeRanges']}>
                      {(rangeFields, { add: addRange, remove: removeRange }) => (
                        <div className="settings-pricing-ranges">
                          {rangeFields.map((rangeField) => (
                            <div className="settings-pricing-range" key={rangeField.key}>
                              <Form.Item label="开始时间" name={[rangeField.name, 'start']} rules={[{ required: true, message: '请选择开始时间' }]}>
                                <Select options={PRICE_TIME_OPTIONS} />
                              </Form.Item>
                              <Form.Item label="结束时间" name={[rangeField.name, 'end']} rules={[{ required: true, message: '请选择结束时间' }]}>
                                <Select options={PRICE_TIME_OPTIONS} />
                              </Form.Item>
                              <Button type="text" danger icon={<Trash size={15} />} title="删除时段" disabled={rangeFields.length === 1} onClick={() => removeRange(rangeField.name)} />
                            </div>
                          ))}
                          <Button type="dashed" icon={<Plus size={14} />} onClick={() => addRange({})}>添加时段</Button>
                        </div>
                      )}
                    </Form.List>
                  </div>
                ))}
                {!ruleFields.length && <div className="settings-pricing-rules-empty">未配置时段规则，全天使用基础单价</div>}
              </section>
            )}
          </Form.List>
        </Form>
      </Modal>
    </div>
  );
}

function protocolText(protocol: RuntimeModelProtocol) {
  return protocol === 'CHAT_COMPLETIONS' ? '对话补全协议' : 'Responses 协议';
}

function compatibleRuntimeNames(protocol: RuntimeModelProtocol, runtimes: AgentRuntimeDescriptor[]) {
  const names = runtimes.filter((runtime) => runtime.supportedModelProtocols.includes(protocol)).map((runtime) => runtime.name);
  return names.length ? names.join(' / ') : '暂无兼容执行方式';
}

function formatTokenCapacity(value?: number) {
  if (!value) return '-';
  return value >= 1_000_000 ? `${Number((value / 1_000_000).toFixed(1))}M Token` : `${Number((value / 1000).toFixed(1))}K Token`;
}

function uniqueModelOptions(models: string[]) {
  return Array.from(new Set(models)).map((item) => ({ label: item, value: item }));
}

function formatPrice(value?: number) {
  return typeof value === 'number' ? `¥${value.toLocaleString('zh-CN', { maximumFractionDigits: 6 })}` : '-';
}

function pricingRuleText(rule: NonNullable<RuntimePricingPlan['pricing']['scheduleRules']>[number]) {
  const ranges = rule.timeRanges.map((range) => `${range.start}-${range.end}`).join('、');
  return `${rule.name} ${rule.multiplier} 倍 · ${ranges}`;
}

const PRICE_TIME_OPTIONS = Array.from({ length: 48 }, (_, index) => {
  const value = `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`;
  return { label: value, value };
});

function modelPageReturnState(state: unknown) {
  if (!state || typeof state !== 'object') {
    return { path: '/profile', state: undefined };
  }
  const values = state as { from?: unknown; profileFrom?: unknown };
  const from = safeReturnPath(values.from, '/profile');
  const profileFrom = safeReturnPath(values.profileFrom, '/');
  return from === '/profile'
    ? { path: from, state: { from: profileFrom } }
    : { path: from, state: undefined };
}

function safeReturnPath(value: unknown, fallback: string) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')
    ? value
    : fallback;
}

function modelPageProviderType(state: unknown): RuntimeProviderType | undefined {
  if (!state || typeof state !== 'object' || !('createProviderType' in state)) {
    return undefined;
  }
  const value = (state as { createProviderType?: unknown }).createProviderType;
  return value === 'OPENAI' || value === 'DEEPSEEK' || value === 'KIMI' ? value : undefined;
}
