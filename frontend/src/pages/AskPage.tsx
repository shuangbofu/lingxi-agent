import type { ClipboardEvent, CSSProperties } from 'react';
import { useEffect, useMemo, useRef, useState } from 'react';
import '../styles/task-workspace.css';
import { Button, DatePicker, Empty, Form, Input, Modal, Segmented, Select, Switch, TimePicker, Tooltip, message } from 'antd';
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import { ArrowsInSimple, ArrowsOutSimple, CaretDown, CheckCircle, MagnifyingGlass, Play, Plus, SlidersHorizontal } from '@phosphor-icons/react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { createTask, getScenario, listAvailableAnalysisPremises, listEnabledScenarios, listUserVisibleScenarios, pageTasks } from '../api/lingxi';
import type { AgentDefinitionParameter, AgentRuntimeDescriptor, AgentScenario, AnalysisPremiseOption, CreateTaskRequest, RuntimeModelOption, RuntimeProviderTypeOption, TaskAttachment, TaskInputValue, TaskItem, TaskRoundSummary } from '../types/api';
import { DefinitionIcon } from '../components/DefinitionIcon';
import { RuntimeIcon } from '../components/RuntimeIcon';
import { ProviderTypeIcon } from '../components/ProviderTypeIcon';
import { TaskModelTag } from '../components/TaskModelTag';
import { TaskAttachmentPicker, TaskAttachmentPreviewList } from '../components/TaskAttachmentPicker';
import { neutralScenarioCssVariables, scenarioCssVariables, scenarioPaletteForScenario } from '../utils/scenarioVisual';
import { displayTaskTitle, displayTaskType, formatRelativeTime } from '../utils/format';
import { taskDefinitionIconUrl } from '../utils/taskVisual';
import { isLongInput, mergePastedText, prepareLongInput } from '../utils/longInput';
import { usePageTransitionNavigate } from '../hooks/usePageTransitionNavigate';
import { useRuntimeModes } from '../hooks/useRuntimeModes';

const LAST_ASK_SCENARIO_KEY = 'workbench:ask:last-scenario-code';
const LAST_ASK_SCENARIO_COLOR_KEY = 'workbench:ask:last-scenario-color';
const LAST_ASK_PREMISE_KEY = 'workbench:ask:last-premise-id';
const LAST_ASK_RUNTIME_KEY = 'workbench:ask:last-runtime-code';
const LAST_ASK_MODEL_KEY_PREFIX = 'workbench:ask:last-model:';
const ASK_DRAFT_KEY = 'workbench:ask:draft';
const RECENT_TASK_SIZE = 5;

type FormValueBag = Partial<Omit<CreateTaskRequest, 'runtimeCode' | 'modelProfileId'>> & Record<string, any>;

interface AskDraft {
  userInput: string;
  attachments: TaskAttachment[];
}

interface AskPageProps {
  /**
   * 嵌入模式：作为对话页的新会话内容渲染，隐藏最近提问列表。
   */
  embedded?: boolean;
  /**
   * 提交成功回调：嵌入模式下由外层接管导航（不传时保持原有跳转运行页行为）。
   */
  onCreated?: (task: TaskItem) => void;
}

export function AskPage({ embedded = false, onCreated }: AskPageProps = {}) {
  const navigate = useNavigate();
  const location = useLocation();
  const transitionNavigate = usePageTransitionNavigate();
  const [searchParams] = useSearchParams();
  const [initialDraft] = useState<AskDraft>(() => readAskDraft());
  const [form] = Form.useForm<FormValueBag>();
  const sourceTaskId = readSearchPositiveNumber(searchParams, 'sourceTaskId');
  const prefillInput = searchParams.get('prefill') || '';
  const [scenarios, setScenarios] = useState<AgentScenario[]>([]);
  const [allScenarios, setAllScenarios] = useState<AgentScenario[]>([]);
  const [premises, setPremises] = useState<AnalysisPremiseOption[]>([]);
  const [premisesReady, setPremisesReady] = useState(false);
  const [selectedPremiseId, setSelectedPremiseId] = useState<number>();
  const [selectedScenarioCode, setSelectedScenarioCode] = useState<string | undefined>(() => readInitialScenarioCode(searchParams, LAST_ASK_SCENARIO_KEY));
  const [selectedScenario, setSelectedScenario] = useState<AgentScenario>();
  const [storedScenarioColor, setStoredScenarioColor] = useState<Record<string, string> | undefined>(() => readStoredScenarioColor());
  const [submitting, setSubmitting] = useState(false);
  const [convertingLongInput, setConvertingLongInput] = useState(false);
  const [launching, setLaunching] = useState(false);
  const [attachments, setAttachments] = useState<TaskAttachment[]>(initialDraft.attachments);
  const [inputExpanded, setInputExpanded] = useState(false);
  const [parametersOpen, setParametersOpen] = useState(false);
  const [recentTasks, setRecentTasks] = useState<TaskItem[]>([]);
  const runtimeModes = useRuntimeModes();
  const [selectedRuntimeCode, setSelectedRuntimeCode] = useState<string>();
  const [selectedModelProfileId, setSelectedModelProfileId] = useState<string>();
  const [selectedProviderType, setSelectedProviderType] = useState<string>();
  const [scenarioModalOpen, setScenarioModalOpen] = useState(false);
  const [runtimeModalOpen, setRuntimeModalOpen] = useState(false);
  const parameterButtonRef = useRef<HTMLButtonElement>(null);
  const parameterPopoverRef = useRef<HTMLDivElement>(null);
  const scenarioRequestRef = useRef(0);
  const scenarioListRequestRef = useRef(0);
  const availableScenarioCodes = useMemo(() => new Set(scenarios.map((item) => item.code)), [scenarios]);
  const orderedScenarios = useMemo(() => {
    const items = allScenarios.length > 0 ? allScenarios : scenarios;
    return [...items].sort((left, right) =>
      Number(availableScenarioCodes.has(right.code)) - Number(availableScenarioCodes.has(left.code)));
  }, [allScenarios, availableScenarioCodes, scenarios]);
  const selectedParameters = useMemo(() => visibleParameters(uniqueParameters(selectedScenario?.parameters || [])), [selectedScenario]);
  const formMode = selectedScenario?.inputMode === 'FORM';
  const secondaryParameters = useMemo(() => selectedParameters.filter((parameter) => parameter.key !== 'userInput'), [selectedParameters]);
  const watchedValues = Form.useWatch([], form) as FormValueBag | undefined;
  const secondaryDefaultValues = useMemo(() => {
    const result: Record<string, unknown> = {};
    secondaryParameters.forEach((parameter) => {
      result[parameter.key] = normalizeDefaultValue(parameter);
    });
    return result;
  }, [secondaryParameters]);
  const hasSecondaryParameterValue = useMemo(
    () => secondaryParameters.some((parameter) => hasUserEditedAdvancedParameter(watchedValues?.[parameter.key], secondaryDefaultValues[parameter.key], parameter.type)),
    [secondaryDefaultValues, secondaryParameters, watchedValues],
  );
  const inputPlaceholder = useMemo(() => scenarioInputPlaceholder(selectedScenario), [selectedScenario]);
  const entrySlogan = selectedScenario?.slogan || '你想分析什么？';
  const submitShortcutLabel = /Mac|iPhone|iPad|iPod/i.test(window.navigator.platform) ? '⌘ + Enter' : 'Ctrl + Enter';
  const selectedScenarioForDisplay = selectedScenario || scenarios.find((item) => item.code === selectedScenarioCode);
  const selectedRuntime = runtimeModes.find((item) => item.code === selectedRuntimeCode);
  const availableModels = selectedRuntime?.models.filter((item) => item.available) || [];
  const selectedModel = selectedRuntime?.models.find((item) => item.id === selectedModelProfileId);
  const modelProviderTypes = useMemo(
    () => collectModelProviderTypes(selectedRuntime?.models || [], selectedRuntime?.modelProviderTypes || []),
    [selectedRuntime],
  );
  const displayedModels = (selectedRuntime?.models || []).filter(
    (item) => !selectedProviderType || modelProviderTypeKey(item) === selectedProviderType,
  );
  const selectedColor = useMemo(
    () => selectedScenarioForDisplay ? scenarioColor(selectedScenarioForDisplay, scenarios) : storedScenarioColor || neutralScenarioCssVariables(),
    [selectedScenarioForDisplay, scenarios, storedScenarioColor],
  );

  useEffect(() => {
    loadPremises();
    loadAllScenarios();
    loadRecentTasks();
  }, []);

  useEffect(() => {
    if (!runtimeModes.length) {
      return;
    }
    setSelectedRuntimeCode((current) => {
      if (current && runtimeModes.some((item) => item.code === current && item.available)) {
        return current;
      }
      const remembered = window.localStorage.getItem(LAST_ASK_RUNTIME_KEY);
      const selected = runtimeModes.find((item) => item.code === remembered && item.available)?.code
        || runtimeModes.find((item) => item.defaultSelected && item.available)?.code
        || runtimeModes.find((item) => item.available)?.code;
      if (selected) {
        window.localStorage.setItem(LAST_ASK_RUNTIME_KEY, selected);
      }
      return selected;
    });
  }, [runtimeModes]);

  useEffect(() => {
    if (!selectedRuntimeCode || !selectedRuntime) {
      setSelectedModelProfileId(undefined);
      return;
    }
    setSelectedModelProfileId((current) => {
      if (current && availableModels.some((item) => item.id === current)) {
        return current;
      }
      const remembered = window.localStorage.getItem(`${LAST_ASK_MODEL_KEY_PREFIX}${selectedRuntimeCode}`);
      const selected = availableModels.find((item) => item.id === remembered)?.id
        || availableModels[0]?.id;
      if (selected) {
        window.localStorage.setItem(`${LAST_ASK_MODEL_KEY_PREFIX}${selectedRuntimeCode}`, selected);
      }
      return selected;
    });
  }, [selectedRuntimeCode, selectedRuntime, availableModels.map((item) => item.id).join('|')]);

  useEffect(() => {
    setSelectedProviderType((current) => {
      const selectedType = selectedRuntime?.models.find((item) => item.id === selectedModelProfileId);
      if (selectedType) {
        return modelProviderTypeKey(selectedType);
      }
      if (current && modelProviderTypes.some((item) => item.value === current)) {
        return current;
      }
      return modelProviderTypes[0]?.value;
    });
  }, [selectedModelProfileId, selectedRuntime, modelProviderTypes.map((item) => item.value).join('|')]);

  useEffect(() => {
    if (formMode) {
      return;
    }
    const userInput = String(watchedValues?.userInput === undefined ? initialDraft.userInput : watchedValues.userInput || '');
    persistAskDraft({ userInput, attachments });
  }, [attachments, formMode, watchedValues?.userInput]);

  useEffect(() => {
    if (!premisesReady) {
      return;
    }
    loadScenarios(selectedPremiseId);
  }, [premisesReady, selectedPremiseId]);

  useEffect(() => {
    const taskId = Number(searchParams.get('taskId'));
    if (taskId) {
      transitionNavigate(`/runs/${taskId}?from=ask`, { direction: 'forward', replace: true });
    }
  }, [searchParams.get('taskId')]);

  useEffect(() => {
    if (prefillInput && !form.getFieldValue('userInput')) {
      form.setFieldValue('userInput', prefillInput);
    }
  }, [form, prefillInput]);

  useEffect(() => {
    if (selectedScenarioCode && scenarios.some((item) => item.code === selectedScenarioCode)) {
      loadScenario(selectedScenarioCode);
    }
  }, [selectedScenarioCode, scenarios]);

  useEffect(() => {
    applyAskScenarioColor(selectedColor);
    return clearAskScenarioColor;
  }, [selectedColor]);

  useEffect(() => {
    if (!parametersOpen) {
      return;
    }
    function closeParametersOnOtherClick(event: MouseEvent) {
      const target = event.target as Node;
      if (
        parametersOpen
        && !parameterButtonRef.current?.contains(target)
        && !parameterPopoverRef.current?.contains(target)
        && !isAntdPopupTarget(target)
      ) {
        setParametersOpen(false);
      }
    }
    document.addEventListener('mousedown', closeParametersOnOtherClick);
    return () => document.removeEventListener('mousedown', closeParametersOnOtherClick);
  }, [parametersOpen]);

  function isAntdPopupTarget(target: Node) {
    return target instanceof Element && Boolean(target.closest('.ant-picker-dropdown, .ant-select-dropdown, .ant-popover, .ant-tooltip'));
  }

  function closeAskFloatingPanels() {
    setScenarioModalOpen(false);
    setRuntimeModalOpen(false);
    setParametersOpen(false);
    blurActiveElement();
  }

  async function loadScenarios(premiseId?: number) {
    const requestId = scenarioListRequestRef.current + 1;
    scenarioListRequestRef.current = requestId;
    setScenarios([]);
    const result = await listEnabledScenarios(premiseId);
    if (scenarioListRequestRef.current !== requestId) {
      return;
    }
    setScenarios(result);
    const urlScenarioCode = searchParams.get('scenarioCode') || undefined;
    const rememberedScenarioCode = window.localStorage.getItem(LAST_ASK_SCENARIO_KEY) || undefined;
    const current = selectedScenarioCode ? result.find((item) => item.code === selectedScenarioCode) : undefined;
    const next = current || result.find((item) => item.code === urlScenarioCode) || result.find((item) => item.code === rememberedScenarioCode) || result[0];
    if (next) {
      persistScenarioColor(next, result);
      setStoredScenarioColor(scenarioColor(next, result));
      window.localStorage.setItem(LAST_ASK_SCENARIO_KEY, next.code);
      setSelectedScenarioCode(next.code);
    } else {
      setSelectedScenarioCode(undefined);
      setSelectedScenario(undefined);
    }
  }

  async function loadAllScenarios() {
    const result = await listUserVisibleScenarios();
    setAllScenarios(result);
  }

  async function loadPremises() {
    try {
      const result = await listAvailableAnalysisPremises();
      setPremises(result);
      setSelectedPremiseId((current) => {
        if (current && result.some((item) => item.id === current)) {
          return current;
        }
        const remembered = Number(window.localStorage.getItem(LAST_ASK_PREMISE_KEY));
        const rememberedPremise = result.find((item) => item.id === remembered);
        return rememberedPremise?.id || result[0]?.id;
      });
    } finally {
      setPremisesReady(true);
    }
  }

  async function loadRecentTasks() {
    try {
      const result = await pageTasks({ page: 1, size: RECENT_TASK_SIZE, scope: 'mine', recordType: 'analysis', sortBy: 'updatedAt' });
      setRecentTasks(result.records || []);
    } catch {
      setRecentTasks([]);
    }
  }

  function selectRuntimeMode(runtimeCode: string) {
    window.localStorage.setItem(LAST_ASK_RUNTIME_KEY, runtimeCode);
    setSelectedRuntimeCode(runtimeCode);
  }

  function selectModel(modelProfileId: string) {
    if (selectedRuntimeCode) {
      window.localStorage.setItem(`${LAST_ASK_MODEL_KEY_PREFIX}${selectedRuntimeCode}`, modelProfileId);
    }
    setSelectedModelProfileId(modelProfileId);
    setRuntimeModalOpen(false);
  }

  function selectPremise(premiseId: number) {
    window.localStorage.setItem(LAST_ASK_PREMISE_KEY, String(premiseId));
    setSelectedPremiseId(premiseId);
  }

  function selectScenario(scenarioCode: string) {
    window.localStorage.setItem(LAST_ASK_SCENARIO_KEY, scenarioCode);
    const scenario = scenarios.find((item) => item.code === scenarioCode);
    if (scenario) {
      persistScenarioColor(scenario, scenarios);
      setStoredScenarioColor(scenarioColor(scenario, scenarios));
    }
    setSelectedScenarioCode(scenarioCode);
    setScenarioModalOpen(false);
  }

  async function loadScenario(code: string) {
    const requestId = scenarioRequestRef.current + 1;
    scenarioRequestRef.current = requestId;
    const cached = scenarios.find((item) => item.code === code);
    setSelectedScenario(cached);
    const detail = await getScenario(code);
    if (scenarioRequestRef.current === requestId) {
      setSelectedScenario(detail);
      resetForm(detail, form);
      if (detail.inputMode === 'FORM') {
        setAttachments([]);
      }
    }
  }

  async function submit() {
    if (!selectedScenario || submitting || convertingLongInput) {
      return;
    }
    if (!selectedRuntimeCode) {
      message.warning('当前没有可用的执行模式');
      return;
    }
    if (!selectedModelProfileId) {
      message.warning('请选择模型');
      return;
    }
    closeAskFloatingPanels();
    setSubmitting(true);
    try {
      const values = await form.validateFields();
      const userInput = formMode ? '' : String(values.userInput || '').trim();
      const currentAttachments = formMode ? [] : attachments;
      if (isParameterRequired(selectedParameters, 'userInput') && !userInput && currentAttachments.length === 0) {
        message.warning('请输入你要分析的问题或上传附件');
        return;
      }
      const prepared = await prepareLongInput(userInput, currentAttachments);
      if (prepared.textAttachment) {
        setAttachments(prepared.attachments);
      }
      const result = await createTask({
        runtimeCode: selectedRuntimeCode,
        modelProfileId: selectedModelProfileId,
        scenarioCode: selectedScenario.code,
        premiseId: selectedPremiseId,
        sourceTaskId,
        userInput: prepared.userInput,
        inputValues: collectInputValues(selectedParameters, values).map((item) =>
          item.key === 'userInput' ? { ...item, value: prepared.userInput } : item
        ),
        attachmentIds: prepared.attachmentIds,
      });
      clearAskDraft();
      if (onCreated) {
        onCreated(result);
        return;
      }
      setLaunching(true);
      message.success('已开始处理');
      const transitionDuration = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 560;
      await new Promise((resolve) => window.setTimeout(resolve, transitionDuration));
      navigate(`/runs/${result.id}?from=ask`, { state: { animateLaunch: true } });
    } finally {
      setSubmitting(false);
    }
  }

  async function handleUserInputPaste(event: ClipboardEvent<HTMLTextAreaElement>) {
    if (convertingLongInput) {
      event.preventDefault();
      return;
    }
    const pastedText = event.clipboardData.getData('text');
    const nextText = mergePastedText(
      event.currentTarget.value,
      pastedText,
      event.currentTarget.selectionStart,
      event.currentTarget.selectionEnd,
    );
    if (!isLongInput(nextText)) {
      return;
    }
    event.preventDefault();
    setConvertingLongInput(true);
    try {
      const prepared = await prepareLongInput(nextText, attachments);
      form.setFieldValue('userInput', prepared.userInput);
      setAttachments(prepared.attachments);
      persistAskDraft({ userInput: prepared.userInput, attachments: prepared.attachments });
    } catch {
      form.setFieldValue('userInput', nextText);
      persistAskDraft({ userInput: nextText, attachments });
    } finally {
      setConvertingLongInput(false);
    }
  }

  function handleRemoveAttachment(attachmentId: string) {
    const attachment = attachments.find((item) => item.id === attachmentId);
    const nextAttachments = attachments.filter((item) => item.id !== attachmentId);
    const userInput = attachment?.inputKind === 'user-input'
      ? ''
      : String(form.getFieldValue('userInput') || '');
    if (attachment?.inputKind === 'user-input') {
      form.setFieldValue('userInput', '');
    }
    setAttachments(nextAttachments);
    persistAskDraft({ userInput, attachments: nextAttachments });
  }

  return (
    <div
      className={`${launching ? 'ask-entry-page ask-entry-page-launching' : submitting ? 'ask-entry-page ask-entry-page-requesting' : 'ask-entry-page'}${embedded ? ' ask-entry-page-embedded' : ''}`}
      style={selectedColor as CSSProperties}
    >
      <div className="ask-entry-main">
        <Form
          form={form}
          layout="vertical"
          className={formMode ? 'ask-entry-form ask-entry-form-form' : 'ask-entry-form'}
          initialValues={{ userInput: initialDraft.userInput }}
          onValuesChange={formMode
            ? undefined
            : (_, values) => persistAskDraft({ userInput: String(values.userInput || ''), attachments })}
          preserve
        >
          <div className="ask-entry-title">{entrySlogan}</div>
          <div className={`${askSearchShellClass(inputExpanded, attachments.length > 0)}${formMode ? ' ask-search-shell-form' : ''}`}>
            <div className="ask-input-area">
              {formMode ? (
                <div className="ask-form-fields">
                  {selectedParameters.map((parameter) => renderAskParameter(parameter))}
                </div>
              ) : (
                <Form.Item name="userInput" noStyle>
                  <Input.TextArea
                    className="ask-search-input ask-search-textarea"
                    autoSize={false}
                    placeholder={inputPlaceholder}
                    disabled={convertingLongInput}
                    onPaste={handleUserInputPaste}
                    onPressEnter={(event) => {
                      if (event.metaKey || event.ctrlKey) {
                        event.preventDefault();
                        submit();
                      }
                    }}
                  />
                </Form.Item>
              )}
              {!formMode && attachments.length > 0 && (
                <div className="ask-attachment-floating">
                  <TaskAttachmentPreviewList
                    attachments={attachments}
                    onRemove={handleRemoveAttachment}
                  />
                </div>
              )}
              <div className="ask-input-footer">
                <div className="ask-footer-left">
                  <button
                    type="button"
                    className={scenarioModalOpen ? 'ask-scenario-trigger ask-scenario-trigger-open' : 'ask-scenario-trigger'}
                    onClick={() => {
                      setScenarioModalOpen(true);
                      setRuntimeModalOpen(false);
                      setParametersOpen(false);
                    }}
                    aria-haspopup="dialog"
                    aria-expanded={scenarioModalOpen}
                  >
                    <span className="ask-scenario-selection">
                      {selectedScenarioForDisplay ? (
                        <DefinitionIcon src={selectedScenarioForDisplay.iconUrl} label={selectedScenarioForDisplay.name} size={22} />
                      ) : null}
                      {premises.length > 0 && (
                        <>
                          <span className="ask-scenario-selection-text ask-scenario-selection-premise">
                            {premises.find((item) => item.id === selectedPremiseId)?.name || '选择情境'}
                          </span>
                          <span className="ask-scenario-selection-separator">·</span>
                        </>
                      )}
                      <span className="ask-scenario-selection-text">{selectedScenarioForDisplay?.name || '选择场景'}</span>
                    </span>
                  </button>
                  {runtimeModes.length > 0 && (
                    <button
                      type="button"
                      className={runtimeModalOpen ? 'ask-scenario-trigger ask-runtime-trigger ask-scenario-trigger-open' : 'ask-scenario-trigger ask-runtime-trigger'}
                      onClick={() => {
                        setRuntimeModalOpen(true);
                        setScenarioModalOpen(false);
                        setParametersOpen(false);
                    }}
                    aria-haspopup="dialog"
                    aria-expanded={runtimeModalOpen}
                  >
                      {selectedRuntime && selectedModel ? (
                        <span className="ask-runtime-selection">
                          <span className="ask-runtime-selection-part">
                            <RuntimeIcon iconUrl={selectedRuntime.iconUrl} size={18} />
                            <span className="ask-runtime-selection-text">{selectedRuntime.name}</span>
                          </span>
                          <span className="ask-runtime-selection-separator">·</span>
                          <span className="ask-runtime-selection-part">
                            <ProviderTypeIcon icon={selectedModel.providerIcon} darkIcon={selectedModel.providerDarkIcon} size={18} />
                            <span className="ask-runtime-selection-text">{selectedModel.name}</span>
                          </span>
                        </span>
                      ) : (
                        <span className="ask-runtime-selection">
                          <RuntimeIcon iconUrl={selectedRuntime?.iconUrl} size={18} />
                          <span className="ask-runtime-selection-text">选择运行方式</span>
                        </span>
                      )}
                    </button>
                  )}
                </div>
                <div className="ask-input-actions">
                  {!formMode && secondaryParameters.length > 0 && (
                    <Tooltip title="高级参数" placement="top">
                      <button
                        ref={parameterButtonRef}
                        type="button"
                        className={toolButtonClassName(parametersOpen, hasSecondaryParameterValue)}
                        onMouseDown={(event) => {
                          event.preventDefault();
                          event.stopPropagation();
                          setScenarioModalOpen(false);
                          setRuntimeModalOpen(false);
                          setParametersOpen((value) => !value);
                        }}
                        aria-label="高级参数"
                      >
                        <SlidersHorizontal size={20} weight="bold" />
                      </button>
                    </Tooltip>
                  )}
                  {!formMode && (
                    <>
                      <TaskAttachmentPicker
                        attachments={attachments}
                        onChange={setAttachments}
                        disabled={submitting || convertingLongInput}
                        floating
                      />
                      <Tooltip title={inputExpanded ? '收起输入框' : '展开输入框'} placement="top">
                        <button
                          type="button"
                          className={inputExpanded ? 'ask-tool-button ask-tool-button-active' : 'ask-tool-button'}
                          onMouseDown={(event) => {
                            event.preventDefault();
                            event.stopPropagation();
                            closeAskFloatingPanels();
                            setInputExpanded((value) => !value);
                          }}
                          aria-label={inputExpanded ? '收起输入框' : '展开输入框'}
                        >
                          {inputExpanded ? <ArrowsInSimple size={20} weight="bold" /> : <ArrowsOutSimple size={20} weight="bold" />}
                        </button>
                      </Tooltip>
                    </>
                  )}
                  <Tooltip title={formMode ? '执行任务' : `发起分析（${submitShortcutLabel}）`} placement="top">
                    <button
                      type="button"
                      className={launching ? 'ask-submit-button ask-submit-button-launching' : 'ask-submit-button'}
                      disabled={submitting || convertingLongInput || !selectedScenario || !selectedRuntimeCode || !selectedModelProfileId}
                      onMouseDown={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        submit();
                      }}
                      aria-label={formMode ? '执行任务' : '发起分析'}
                      aria-keyshortcuts="Meta+Enter Control+Enter"
                    >
                      {formMode ? <Play size={23} weight="fill" /> : <MagnifyingGlass size={24} />}
                    </button>
                  </Tooltip>
                </div>
              </div>
              {!formMode && secondaryParameters.length > 0 && (
                <div className={parametersOpen ? 'ask-parameter-popover' : 'ask-parameter-popover ask-parameter-popover-hidden'} ref={parameterPopoverRef}>
                  <div className="ask-parameter-popover-title">高级参数</div>
                  <div className="ask-parameter-row">
                    {secondaryParameters.map((parameter) => renderAskParameter(parameter))}
                  </div>
                </div>
              )}
            </div>
          </div>
          {!embedded && !formMode && <RecentQuestionList tasks={recentTasks} inputExpanded={inputExpanded} runtimeModes={runtimeModes} />}
        </Form>
      </div>
      <Modal
        className="ask-scenario-modal"
        title="选择情境与场景"
        open={scenarioModalOpen}
        footer={null}
        width={960}
        centered
        maskClosable
        onCancel={() => setScenarioModalOpen(false)}
      >
        {premises.length > 0 && (
          <div className="ask-runtime-modal-section">
            <div className="ask-runtime-modal-label">情境</div>
            <Segmented<number>
              className="ask-runtime-modal-switch ask-scenario-premise-tabs"
              value={selectedPremiseId}
              onChange={selectPremise}
              options={premises.map((item) => ({
                value: item.id,
                label: (
                  <span className="ask-scenario-premise-option">
                    <strong>{item.name}</strong>
                    <span>{item.description || '暂无说明'}</span>
                  </span>
                ),
              }))}
            />
          </div>
        )}
        <div className="ask-runtime-modal-section ask-scenario-modal-scenes">
          <div className="ask-runtime-modal-label">场景</div>
          <div className="ask-scenario-grid" role="listbox" aria-label="场景列表">
            {orderedScenarios.map((item) => {
              const selected = item.code === selectedScenarioCode;
              const available = availableScenarioCodes.has(item.code);
              return (
                <button
                  type="button"
                  className={selected ? 'ask-scenario-card ask-scenario-card-selected' : 'ask-scenario-card'}
                  style={scenarioCssVariables(scenarioPaletteForScenario(item, allScenarios.length > 0 ? allScenarios : scenarios)) as CSSProperties}
                  role="option"
                  aria-selected={selected}
                  aria-disabled={!available}
                  disabled={!available}
                  key={item.code}
                  onClick={() => selectScenario(item.code)}
                >
                  <span className="ask-scenario-card-icon">
                    <DefinitionIcon src={item.iconUrl} label={item.name} size="css" />
                  </span>
                  <span className="ask-scenario-card-content">
                    <strong>{item.name}</strong>
                  </span>
                  <span className="ask-scenario-card-description" title={item.description || '暂无说明'}>
                    {item.description || '暂无说明'}
                  </span>
                  {selected && <CheckCircle className="ask-scenario-card-check" size={18} weight="fill" aria-hidden="true" />}
                </button>
              );
            })}
          </div>
        </div>
      </Modal>
      <Modal
        className="ask-runtime-modal"
        title="选择运行方式"
        open={runtimeModalOpen}
        footer={null}
        width={620}
        centered
        maskClosable
        onCancel={() => setRuntimeModalOpen(false)}
      >
        <div className="ask-runtime-modal-section">
          <div className="ask-runtime-modal-label">运行引擎</div>
          <Segmented<string>
            className="ask-runtime-modal-switch"
            block
            value={selectedRuntimeCode}
            onChange={selectRuntimeMode}
            options={runtimeModes.map((item) => ({
              value: item.code,
              disabled: !item.available,
              label: (
                <span className="ask-runtime-modal-option">
                  <span className="ask-runtime-modal-heading">
                    <span className="ask-runtime-modal-icon"><RuntimeIcon iconUrl={item.iconUrl} size={26} /></span>
                    <strong>{item.name}</strong>
                  </span>
                  <span className="ask-runtime-modal-description">
                    {item.available ? item.description || '可用' : item.unavailableReason || '当前不可用'}
                  </span>
                </span>
              ),
            }))}
          />
        </div>
        <div className="ask-runtime-modal-section">
          <div className="ask-runtime-modal-label-row">
            <div className="ask-runtime-modal-label">模型</div>
            <Button
              type="link"
              className="ask-runtime-add-model"
              icon={<Plus size={16} weight="bold" />}
              disabled={!selectedProviderType}
              onClick={() => {
                setRuntimeModalOpen(false);
                navigate('/profile/models', {
                  state: {
                    from: `${location.pathname}${location.search}`,
                    createProviderType: selectedProviderType,
                  },
                });
              }}
            >添加专属模型</Button>
          </div>
          {modelProviderTypes.length > 1 && (
            <Segmented<string>
              className="ask-model-provider-switch"
              block
              value={selectedProviderType}
              onChange={setSelectedProviderType}
              options={modelProviderTypes.map((provider) => ({
                value: provider.value,
                label: (
                  <span className="ask-model-provider-option">
                    <ProviderTypeIcon icon={provider.icon} darkIcon={provider.darkIcon} size={18} />
                    <span>{provider.label}</span>
                  </span>
                ),
              }))}
            />
          )}
          {displayedModels.length ? (
            <Segmented<string>
              className="ask-model-modal-switch"
              block
              vertical
              value={selectedModelProfileId}
              onChange={selectModel}
              options={displayedModels.map((item) => ({
                value: item.id,
                disabled: !item.available,
                label: (
                  <span
                    className="ask-model-modal-option"
                    onClick={item.id === selectedModelProfileId ? () => setRuntimeModalOpen(false) : undefined}
                  >
                    <span className="ask-model-modal-main">
                      <span className="ask-model-modal-title">
                        <ProviderTypeIcon icon={item.providerIcon} darkIcon={item.providerDarkIcon} size={18} />
                        <strong>{item.name}</strong>
                        {item.providerName && <span className="ask-model-modal-provider">{item.providerName}</span>}
                      </span>
                      <span className={item.available ? 'ask-model-modal-description' : 'ask-model-modal-description ask-model-modal-description-error'}>
                        {item.available ? item.description || '暂未配置模型说明' : item.unavailableReason || '当前不可用'}
                      </span>
                    </span>
                    <span className="ask-model-modal-side">
                      <span className="ask-model-modal-meta">
                        {formatModelContext(item.contextWindowTokens)} · {formatReasoningEffort(item.reasoningEffort, item.reasoningEffortLabel)}
                      </span>
                      <span className="ask-model-modal-identifier">{item.model}</span>
                    </span>
                  </span>
                ),
              }))}
            />
          ) : (
            <div className="ask-model-modal-empty">
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该类型暂无可用模型" />
            </div>
          )}
        </div>
      </Modal>
    </div>
  );
}

function formatModelContext(tokens: number) {
  if (tokens >= 1_000_000) {
    return `${Number((tokens / 1_000_000).toFixed(1))}M 上下文`;
  }
  return `${Number((tokens / 1_000).toFixed(0))}K 上下文`;
}

function formatReasoningEffort(value?: string, label?: string) {
  return label ? `${label}推理` : value ? `${value} 推理` : '模型默认推理';
}

function collectModelProviderTypes(models: RuntimeModelOption[], definitions: RuntimeProviderTypeOption[]) {
  const groups = new Map<string, { value: string; label: string; icon?: string; darkIcon?: string }>();
  definitions.forEach((definition) => groups.set(definition.value, {
    value: definition.value,
    label: definition.label,
    icon: definition.icon,
    darkIcon: definition.darkIcon,
  }));
  models.forEach((model) => {
    const value = modelProviderTypeKey(model);
    if (!groups.has(value)) {
      groups.set(value, {
        value,
        label: model.providerTypeName || model.providerName || '其他',
        icon: model.providerIcon,
        darkIcon: model.providerDarkIcon,
      });
    }
  });
  return Array.from(groups.values());
}

function modelProviderTypeKey(model: RuntimeModelOption) {
  return model.providerType || model.providerId || model.providerName || 'unclassified';
}

function RecentQuestionList({ tasks, inputExpanded, runtimeModes }: {
  tasks: TaskItem[];
  inputExpanded: boolean;
  runtimeModes: AgentRuntimeDescriptor[];
}) {
  const transitionNavigate = usePageTransitionNavigate();
  const [collapsed, setCollapsed] = useState(true);
  const [expandedTaskIds, setExpandedTaskIds] = useState<Set<number>>(() => new Set());
  if (!tasks.length) {
    return null;
  }
  function toggleRounds(taskId: number) {
    setExpandedTaskIds((current) => {
      const next = new Set(current);
      if (next.has(taskId)) {
        next.delete(taskId);
      } else {
        next.add(taskId);
      }
      return next;
    });
  }
  return (
    <section className={[
      'ask-recent-panel',
      inputExpanded ? 'ask-recent-panel-input-expanded' : '',
      collapsed ? 'ask-recent-panel-collapsed' : '',
    ].filter(Boolean).join(' ')} aria-label="最近问题">
      <div className="ask-recent-head">
        <button className="ask-recent-toggle" type="button" aria-expanded={!collapsed} onClick={() => setCollapsed((current) => !current)}>
          <span>最近提问</span>
          <CaretDown className={collapsed ? 'ask-recent-toggle-icon' : 'ask-recent-toggle-icon ask-recent-toggle-icon-open'} size={13} weight="bold" />
        </button>
        <button type="button" onClick={() => transitionNavigate('/history?from=ask', { direction: 'forward' })}>全部</button>
      </div>
      {!collapsed && <div className="ask-recent-list">
        {tasks.map((task) => {
          const premiseName = task.premiseSnapshotName || task.premiseName;
          const runtimeName = runtimeModes.find((runtime) => runtime.code === task.runtimeCode)?.name || task.runtimeCode;
          return (
            <div
              key={task.id}
              className="ask-recent-item"
              role="button"
              tabIndex={0}
              onClick={() => transitionNavigate(`/runs/${latestRoundId(task)}?from=ask`, { direction: 'forward' })}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  transitionNavigate(`/runs/${latestRoundId(task)}?from=ask`, { direction: 'forward' });
                }
              }}
            >
              <span className="ask-recent-icon">
                <DefinitionIcon src={taskDefinitionIconUrl(task)} label={displayTaskType(task.scenario, task.scenarioName)} size="css" />
              </span>
              <span className="ask-recent-main">
                <span className="ask-recent-title-row">
                  <span className="ask-recent-title">{displayTaskTitle(task.title, task.scenario, task.scenarioName)}</span>
                  <span className={`ask-recent-dot ask-recent-dot-${task.status.toLowerCase()}`} aria-hidden="true" />
                </span>
                <span className="ask-recent-info">
                  <span className="ask-recent-meta">
                    {premiseName && <span>{premiseName}</span>}
                    {runtimeName && <span>{runtimeName}</span>}
                    <TaskModelTag modelProviderName={task.modelProviderName} modelName={task.modelName} modelIdentifier={task.modelIdentifier} />
                    {task.roundCount && task.roundCount > 1 && <span>{task.roundCount} 轮</span>}
                    <span>{formatRelativeTime(task.updatedAt || task.createdAt)}</span>
                  </span>
                  <RecentRoundPreview task={task} expanded={expandedTaskIds.has(task.id)} onToggle={() => toggleRounds(task.id)} />
                </span>
              </span>
              <span className="ask-recent-arrow" aria-hidden="true">›</span>
            </div>
          );
        })}
      </div>}
    </section>
  );
}

function RecentRoundPreview({ task, expanded, onToggle }: { task: TaskItem; expanded: boolean; onToggle: () => void }) {
  const rounds = visibleRounds(task.roundSummaries);
  if (rounds.length === 0) {
    return <span className="ask-recent-rounds ask-recent-rounds-empty" aria-hidden="true" />;
  }
  const previewRound = rounds[rounds.length - 1];
  return (
    <span className="ask-recent-rounds" onClick={(event) => event.stopPropagation()}>
      {!expanded && <RoundFoldToggle expanded={false} roundNo={previewRound.roundNo || 1} text={previewRound.userInput || '-'} onToggle={onToggle} />}
      {expanded && rounds.map((round) => (
        <span key={round.id}>
          <span>第 {round.roundNo || 1} 轮</span>
          <span>{round.userInput || '-'}</span>
        </span>
      ))}
      {expanded && <RoundFoldClose onToggle={onToggle} />}
    </span>
  );
}

function RoundFoldToggle({ expanded, roundNo, text, onToggle }: { expanded: boolean; roundNo: number; text: string; onToggle: () => void }) {
  return (
    <button className="round-fold-toggle ask-recent-round-toggle" type="button" onClick={onToggle}>
      <span>第 {roundNo} 轮：{text}</span>
      <span>{expanded ? '收起' : '展开'}</span>
    </button>
  );
}

function RoundFoldClose({ onToggle }: { onToggle: () => void }) {
  return (
    <button className="round-fold-close" type="button" onClick={onToggle}>
      收起
    </button>
  );
}

function visibleRounds(rounds?: TaskRoundSummary[]) {
  return (rounds || []).filter((round) => (round.roundNo || 1) > 1);
}

function latestRoundId(task: TaskItem) {
  const latest = [...(task.roundSummaries || [])].sort((left, right) => (right.roundNo || 1) - (left.roundNo || 1) || right.id - left.id)[0];
  return latest?.id || task.id;
}

function renderAskParameter(parameter: AgentDefinitionParameter) {
  const type = parameter.type?.toLowerCase() || 'text';
  return (
    <Form.Item
      key={parameter.key}
      className={type === 'switch' ? 'ask-parameter-item ask-parameter-item-switch' : 'ask-parameter-item'}
      label={parameter.name}
      name={parameter.key}
      valuePropName={type === 'switch' ? 'checked' : undefined}
      rules={parameter.required ? [{ required: true, message: `请输入${parameter.name}` }] : []}
    >
      {type === 'switch' ? (
        <Switch checkedChildren="是" unCheckedChildren="否" />
      ) : parameter.options?.length ? (
        <Select
          allowClear={!parameter.required}
          popupClassName="ask-parameter-select-dropdown"
          options={parameter.options.map((item) => ({ label: item.label, value: item.value }))}
        />
      ) : type === 'datetime' || type === 'date-time' ? (
        <DatePicker showTime className="w-full" format="YYYY-MM-DD HH:mm:ss" placeholder={parameter.description || '选择时间'} />
      ) : type === 'date' ? (
        <DatePicker className="w-full" format="YYYY-MM-DD" placeholder={parameter.description || '选择日期'} />
      ) : type === 'time' ? (
        <TimePicker className="w-full" format="HH:mm:ss" placeholder={parameter.description || '选择时间'} />
      ) : type === 'textarea' ? (
        <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} placeholder={parameter.description} />
      ) : (
        <Input placeholder={parameter.description} />
      )}
    </Form.Item>
  );
}

function collectInputValues(parameters: AgentDefinitionParameter[], values: FormValueBag): TaskInputValue[] {
  return parameters.map((parameter) => ({ key: parameter.key, value: serializeInputValue(values[parameter.key], parameter.type) }));
}

function resetForm(scenario: AgentScenario, form: ReturnType<typeof Form.useForm<FormValueBag>>[0]) {
  const values: FormValueBag = scenario.inputMode === 'FORM' ? {} : { userInput: form.getFieldValue('userInput') };
  uniqueParameters(scenario.parameters).forEach((parameter) => {
    if (parameter.key === 'userInput') {
      return;
    }
    const defaultValue = normalizeDefaultValue(parameter);
    if (defaultValue !== undefined) {
      values[parameter.key] = defaultValue;
    }
  });
  form.resetFields();
  form.setFieldsValue(values);
}

function normalizeDefaultValue(parameter: AgentDefinitionParameter) {
  if (parameter.defaultValue !== undefined && parameter.defaultValue !== null && parameter.defaultValue !== '') {
    const type = parameter.type?.toLowerCase() || '';
    if (type === 'switch') {
      return String(parameter.defaultValue).toLowerCase() === 'true';
    }
    if (type === 'datetime' || type === 'date-time') {
      return dayjs(parameter.defaultValue);
    }
    if (type === 'date') {
      return dayjs(parameter.defaultValue, 'YYYY-MM-DD');
    }
    if (type === 'time') {
      return dayjs(parameter.defaultValue, 'HH:mm:ss');
    }
    return parameter.defaultValue;
  }
  return undefined;
}

function uniqueParameters(parameters: AgentDefinitionParameter[]) {
  const exists = new Set<string>();
  return parameters.filter((parameter) => {
    if (exists.has(parameter.key)) {
      return false;
    }
    exists.add(parameter.key);
    return true;
  });
}

function visibleParameters(parameters: AgentDefinitionParameter[]) {
  return parameters.filter((parameter) => parameter.visible !== false);
}

function isParameterRequired(parameters: AgentDefinitionParameter[], key: string) {
  return parameters.some((parameter) => parameter.key === key && parameter.required);
}

function toolButtonClassName(active: boolean, filled = false) {
  return ['ask-tool-button', active ? 'ask-tool-button-active' : '', filled ? 'ask-tool-button-filled' : ''].filter(Boolean).join(' ');
}

function hasAdvancedParameterValue(value: unknown): boolean {
  if (value === undefined || value === null) {
    return false;
  }
  if (typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    return Number.isFinite(value);
  }
  if (typeof value === 'string') {
    return value.trim().length > 0;
  }
  if (isDayjs(value)) {
    return value.isValid();
  }
  if (Array.isArray(value)) {
    return value.some(hasAdvancedParameterValue);
  }
  if (typeof value === 'object') {
    return Object.values(value).some(hasAdvancedParameterValue);
  }
  return true;
}

function hasUserEditedAdvancedParameter(value: unknown, defaultValue: unknown, type?: string) {
  if (!hasAdvancedParameterValue(value)) {
    return false;
  }
  if (!hasAdvancedParameterValue(defaultValue)) {
    return true;
  }
  return serializeComparableValue(value, type) !== serializeComparableValue(defaultValue, type);
}

function serializeComparableValue(value: unknown, type?: string): string {
  if (value === undefined || value === null) {
    return '';
  }
  if (isDayjs(value)) {
    return serializeDayjsValue(value, type);
  }
  if (Array.isArray(value)) {
    return value.map((item) => serializeComparableValue(item, type)).join(',');
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function serializeInputValue(value: unknown, type?: string) {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (typeof value === 'object') {
    if (isDayjs(value)) {
      return serializeDayjsValue(value, type);
    }
    return JSON.stringify(value);
  }
  return String(value);
}

function isDayjs(value: unknown): value is Dayjs {
  return dayjs.isDayjs(value);
}

function serializeDayjsValue(value: Dayjs, type?: string) {
  const normalizedType = type?.toLowerCase() || '';
  if (normalizedType === 'time') {
    return value.format('HH:mm:ss');
  }
  if (normalizedType === 'datetime' || normalizedType === 'date-time') {
    return value.format('YYYY-MM-DD HH:mm:ss');
  }
  return value.format('YYYY-MM-DD');
}

function scenarioInputPlaceholder(scenario?: AgentScenario) {
  return scenario?.description || undefined;
}

function askSearchShellClass(expanded: boolean, hasAttachments: boolean) {
  return [
    'ask-search-shell',
    expanded ? 'ask-search-shell-expanded' : '',
    hasAttachments ? 'ask-search-shell-has-attachments' : '',
  ].filter(Boolean).join(' ');
}

function scenarioColor(scenario: AgentScenario, scenarios: AgentScenario[]) {
  return scenarioCssVariables(scenarioPaletteForScenario(scenario, scenarios));
}

/**
 * 读取首帧使用的场景 code。
 *
 * @param searchParams 当前地址栏查询参数
 * @param storageKey 本地缓存中的场景 code key
 * @return URL 或本地缓存中的场景 code，没有时返回 undefined
 */
function readInitialScenarioCode(searchParams: URLSearchParams, storageKey: string) {
  const urlScenarioCode = searchParams.get('scenarioCode')?.trim();
  if (urlScenarioCode) {
    return urlScenarioCode;
  }
  return window.localStorage.getItem(storageKey)?.trim() || undefined;
}

function readSearchPositiveNumber(searchParams: URLSearchParams, key: string) {
  const value = Number(searchParams.get(key));
  return Number.isFinite(value) && value > 0 ? value : undefined;
}

/**
 * 读取上次场景的颜色变量，避免首页首帧落到默认色板。
 *
 * @return 已缓存的颜色变量，没有或解析失败时返回 undefined
 */
function readStoredScenarioColor() {
  const rawValue = window.localStorage.getItem(LAST_ASK_SCENARIO_COLOR_KEY);
  if (!rawValue) {
    return undefined;
  }
  try {
    return JSON.parse(rawValue) as Record<string, string>;
  } catch {
    return undefined;
  }
}

function readAskDraft(): AskDraft {
  const fallback = { userInput: '', attachments: [] };
  const rawValue = window.sessionStorage.getItem(ASK_DRAFT_KEY);
  if (!rawValue) {
    return fallback;
  }
  try {
    const value = JSON.parse(rawValue) as Partial<AskDraft>;
    const attachments = Array.isArray(value.attachments)
      ? value.attachments.filter((item): item is TaskAttachment => Boolean(
        item
        && typeof item.id === 'string'
        && typeof item.name === 'string'
        && typeof item.contentType === 'string'
        && typeof item.size === 'number'
        && typeof item.url === 'string',
      ))
      : [];
    return {
      userInput: typeof value.userInput === 'string' ? value.userInput : '',
      attachments,
    };
  } catch {
    return fallback;
  }
}

function persistAskDraft(draft: AskDraft) {
  if (!draft.userInput && draft.attachments.length === 0) {
    window.sessionStorage.removeItem(ASK_DRAFT_KEY);
    return;
  }
  window.sessionStorage.setItem(ASK_DRAFT_KEY, JSON.stringify(draft));
}

function clearAskDraft() {
  window.sessionStorage.removeItem(ASK_DRAFT_KEY);
}

/**
 * 缓存场景颜色变量。
 *
 * @param scenario 当前选中的场景
 * @param scenarios 当前可选场景列表
 * @return void
 */
function persistScenarioColor(scenario: AgentScenario, scenarios: AgentScenario[]) {
  window.localStorage.setItem(LAST_ASK_SCENARIO_COLOR_KEY, JSON.stringify(scenarioColor(scenario, scenarios)));
}

/**
 * 首页没有拿到场景对象前使用的中性色。
 *
 * @return CSS 变量集合
 */
function applyAskScenarioColor(style: Record<string, string>) {
  Object.entries(style).forEach(([key, value]) => {
    document.documentElement.style.setProperty(key, value);
  });
}

function clearAskScenarioColor() {
  Object.keys(neutralScenarioCssVariables()).forEach((key) => {
    document.documentElement.style.removeProperty(key);
  });
}

function blurActiveElement() {
  const activeElement = document.activeElement;
  if (activeElement instanceof HTMLElement) {
    activeElement.blur();
  }
}
