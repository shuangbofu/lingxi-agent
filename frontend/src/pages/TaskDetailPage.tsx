import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import '../styles/task-workspace.css';
import { Button, Descriptions, Tabs } from 'antd';
import { ArrowClockwise, ArrowCounterClockwise, CaretLeft, ChartBar, Stop } from '@phosphor-icons/react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { cancelTask, continueTaskRound, getTask, listAllScenarios, listEnabledScenarios, listTaskInteractions, listTaskRounds, resumeTask, retryTask } from '../api/lingxi';
import type { AgentRuntimeDescriptor, AgentScenario, TaskItem, TaskRoundSummary } from '../types/api';
import { displayTaskTitle, displayTaskType, formatDuration, formatTime } from '../utils/format';
import { AgentOutput } from '../components/AgentOutput';
import { StructuredResult } from '../components/StructuredResult';
import { TaskShareButton } from '../components/TaskShareButton';
import { TaskDownloadPdfButton } from '../components/TaskDownloadPdfButton';
import { TaskUserFloat } from '../components/TaskUserFloat';
import { TaskResumeModal } from '../components/TaskResumeModal';
import { DefinitionIcon } from '../components/DefinitionIcon';
import { ExpandableTitle } from '../components/ExpandableTitle';
import { PremiseSnapshotView } from '../components/PremiseSnapshotView';
import { TaskStatusTag } from '../components/AppTag';
import { TaskRecommendedScenarios } from '../components/TaskRecommendedScenarios';
import { TaskRoundUsage } from '../components/TaskRoundUsage';
import { TaskDetailLoading, TaskProcessLoading } from '../components/TaskDetailLoading';
import { TaskFailureNotice } from '../components/TaskFailureNotice';
import { RuntimeModeTag } from '../components/RuntimeModeTag';
import { TaskModelTag } from '../components/TaskModelTag';
import { cleanGeneratedText } from '../utils/text';
import { mergeTaskEventState, useTaskEventStream } from '../hooks/useTaskEventStream';
import { useTaskProcessLoader } from '../hooks/useTaskProcessLoader';
import { useRuntimeModes } from '../hooks/useRuntimeModes';
import { taskOwnerText } from '../utils/task';
import { isActiveTaskStatus } from '../utils/taskStatus';
import { taskDefinitionIconUrl } from '../utils/taskVisual';
import { taskDisplayParameters, taskInputLabel, taskInputLabelMap } from '../utils/taskInput';

export function TaskDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const analysisRecordDetail = location.pathname.startsWith('/admin/tasks/records')
    || location.pathname.startsWith('/admin/analysis-records');
  const taskDetailPath = (taskId: number) => analysisRecordDetail
    ? `/admin/tasks/records/${taskId}`
    : location.pathname.startsWith('/admin') ? `/admin/tasks/${taskId}` : `/runs/${taskId}`;
  const [task, setTask] = useState<TaskItem>();
  const [rounds, setRounds] = useState<TaskRoundSummary[]>([]);
  const [scenarios, setScenarios] = useState<AgentScenario[]>([]);
  const [retrying, setRetrying] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<string>();
  const runtimeModes = useRuntimeModes();
  const taskRequestRef = useRef(0);
  const manualRoundSelectionRef = useRef(false);
  const handleTaskStreamComplete = useCallback(() => {
    loadTask();
  }, [id]);
  const taskProcess = useTaskProcessLoader(task, activeTab, setTask);
  useTaskEventStream(task, setTask, handleTaskStreamComplete, activeTab === 'process' && taskProcess.ready);

  useEffect(() => {
    setTask(undefined);
    setRounds([]);
    setActiveTab(undefined);
    loadTask();
    loadScenarios();
  }, [id]);

  async function loadScenarios() {
    setScenarios(await (location.pathname.startsWith('/admin') ? listAllScenarios() : listEnabledScenarios()));
  }

  useEffect(() => {
    if (!task || !isTaskRunning(task)) {
      return;
    }
    const timer = window.setInterval(loadTask, 3000);
    return () => window.clearInterval(timer);
  }, [task?.id, task?.status]);

  async function loadTask() {
    if (!id) {
      return;
    }
    const openLatestRound = !manualRoundSelectionRef.current;
    manualRoundSelectionRef.current = false;
    await loadTaskById(Number(id), openLatestRound);
  }

  async function loadTaskById(taskId: number, openLatestRound = false) {
    const requestId = taskRequestRef.current + 1;
    taskRequestRef.current = requestId;
    const [result, interactions] = await Promise.all([
      getTask(taskId),
      listTaskInteractions(taskId),
    ]);
    if (taskRequestRef.current !== requestId) {
      return;
    }
    const nextResult = { ...result, interactions };
    const nextRounds = await loadRounds(nextResult);
    const latest = latestRound(nextRounds);
    if (openLatestRound && latest && latest.id !== nextResult.id && isConversationRoot(nextResult)) {
      navigate(taskDetailPath(latest.id), { replace: true });
      return;
    }
    setActiveTab((current) => {
      const preferred = defaultTaskTab(nextResult);
      if (!current || task?.id !== nextResult.id) {
        return preferred;
      }
      if (current === 'process' && nextResult.status === 'SUCCESS' && preferred === 'output') {
        return 'output';
      }
      return current;
    });
    setTask((previous) => {
      return mergeTaskDetail(previous, nextResult);
    });
  }

  async function handleCancel() {
    if (!task) {
      return;
    }
    await cancelTask(task.id);
    await loadTask();
  }

  async function handleRestart(resume: boolean, userInput?: string) {
    if (!task) {
      return;
    }
    setRetrying(true);
    try {
      const result = await (resume ? resumeTask(task.id, { userInput }) : retryTask(task.id));
      if (resume) {
        setResumeOpen(false);
      }
      taskProcess.reset(result.id);
      setTask(result);
      setRounds(await listTaskRounds(result.id));
      setActiveTab('process');
    } finally {
      setRetrying(false);
    }
  }

  async function loadRounds(currentTask: TaskItem) {
    const result = await listTaskRounds(currentTask.id);
    const merged = mergeRoundList(result, currentTask);
    setRounds(merged);
    return merged;
  }

  async function handleContinue(content: string, attachmentIds: string[]) {
    if (!task) {
      return;
    }
    const result = await continueTaskRound(task.id, { userInput: content, attachmentIds });
    navigate(taskDetailPath(result.id));
  }

  const backPath = analysisRecordDetail ? '/admin/tasks/records'
    : location.pathname.startsWith('/admin') ? '/admin/tasks' : '/';

  if (!task) {
    return <TaskDetailLoading variant="admin" onBack={() => navigate(backPath)} />;
  }

  const canCancel = isTaskRunning(task);
  const outputView = buildTaskOutput(task);
  const running = isTaskRunning(task);
  const hasResult = hasTaskResult(task, outputView.result);
  const failureText = task.status === 'FAILED' && !hasResult ? taskFailureText(task, outputView) : '';
  const showErrorOutput = !running && Boolean(task.stderrText) && !failureText;
  const canShare = task.status === 'SUCCESS' && hasResult;
  const metaItems = [
    displayTaskType(task.scenario, task.scenarioName),
    task.startedAt ? `耗时：${formatDuration(task.startedAt, task.endedAt)}` : undefined,
  ].filter(Boolean);
  const tabItems = [
    {
      key: 'overview',
      label: '基础信息',
      children: <TaskTabPane><TaskOverview task={task} runtimeModes={runtimeModes} /><TaskRoundUsage rounds={mergeRoundList(rounds, task)} activeId={task.id} /><TaskInputView task={task} /></TaskTabPane>,
    },
    ...(failureText ? [{
      key: 'failure',
      label: '失败原因',
      children: <TaskTabPane><TaskFailureNotice text={failureText} /></TaskTabPane>,
    }] : []),
    ...(!running && hasResult ? [{
      key: 'output',
      label: '分析结果',
      children: (
        <TaskTabPane>
          <div className="agent-output-document agent-output-fill">
            <StructuredResult
              data={task.resultData}
              fallback={outputView.result}
              title={displayTaskQuestion(task)}
              renderer={task.resultRenderer}
              reportName={displayTaskType(task.scenario, task.scenarioName)}
              embedded
            />
            <TaskRecommendedScenarios
              task={task}
              scenarios={scenarios}
              admin
            />
          </div>
        </TaskTabPane>
      ),
    }] : []),
    {
      key: 'process',
      label: '思考过程',
      children: (
        <TaskTabPane className="task-detail-tab-pane-output">
          {taskProcess.ready ? (
            <AgentOutput taskId={task.id} entries={task.eventEntries} events={task.events} liveMessages={task.liveAgentMessages} emptyText="暂无过程输出" fill autoScroll running={running} />
          ) : (
            <TaskProcessLoading failed={taskProcess.failed} onRetry={taskProcess.reload} />
          )}
        </TaskTabPane>
      ),
    },
    ...(showErrorOutput ? [{
      key: 'error',
      label: '错误输出',
      children: <TaskTabPane><pre className="code-panel min-h-80 overflow-auto rounded-md border border-neutral-200 bg-neutral-50 p-4 text-sm text-neutral-700">{task.stderrText}</pre></TaskTabPane>,
    }] : []),
  ];

  return (
    <div className="task-admin-detail-page">
      <TaskUserFloat taskId={task.id} taskStatus={task.status} userInput={task.userInput} attachments={task.attachments} interactions={task.interactions} inputLabel="用户输入" onAnswered={loadTask} onContinue={handleContinue} />
      <section className="task-admin-detail-main">
        <div className="task-detail-head">
          <div className="task-detail-title-block">
            <span className="task-detail-icon"><DefinitionIcon src={taskDefinitionIconUrl(task)} label={displayTaskType(task.scenario, task.scenarioName)} size={34} /></span>
            <div className="min-w-0">
              <ExpandableTitle text={displayTaskQuestion(task)} className="task-detail-title-wrap" textClassName={taskQuestionTitleClass('task-detail-title', displayTaskQuestion(task))} />
              <div className="task-detail-meta">
                {metaItems.map((item, index) => (
                  <span key={item}>{index > 0 ? `· ${item}` : item}</span>
                ))}
              </div>
            </div>
          </div>
          <div className="task-detail-actions">
            <Button size="small" icon={<CaretLeft size={14} />} onClick={() => navigate(backPath)}>返回</Button>
            <Button size="small" icon={<ChartBar size={14} weight="fill" />} onClick={() => navigate(`/admin/tasks/${task.id}/report?returnTo=${encodeURIComponent(location.pathname + location.search)}`)}>分析报告</Button>
            <Button size="small" icon={<ArrowClockwise size={14} weight="fill" />} onClick={loadTask}>刷新</Button>
            <TaskShareButton size="small" task={task} disabled={!canShare} label="分享" />
            <TaskDownloadPdfButton size="small" task={task} disabled={!canShare} />
            {task.status === 'FAILED' && (
              <Button size="small" loading={retrying} icon={<ArrowCounterClockwise size={14} weight="bold" />} onClick={() => handleRestart(false)}>重试</Button>
            )}
            {task.status === 'CANCELED' && (
              <Button size="small" loading={retrying} icon={<ArrowCounterClockwise size={14} weight="bold" />} onClick={() => setResumeOpen(true)}>恢复</Button>
            )}
            <Button size="small" danger disabled={!canCancel} icon={<Stop size={14} weight="fill" />} onClick={handleCancel}>取消</Button>
          </div>
        </div>

        <div className="task-detail-body">
          {rounds.length > 1 && <RoundSwitcher rounds={rounds} activeId={task.id} onSelect={(taskId) => {
            manualRoundSelectionRef.current = true;
            navigate(taskDetailPath(taskId));
          }} />}
          <div className="task-detail-tabs">
            <Tabs
              activeKey={activeTab}
              onChange={setActiveTab}
              destroyOnHidden
              items={tabItems}
            />
          </div>
        </div>
      </section>
      <TaskResumeModal
        open={resumeOpen}
        task={task}
        loading={retrying}
        onCancel={() => setResumeOpen(false)}
        onSubmit={(userInput) => handleRestart(true, userInput)}
      />
    </div>
  );
}

function RoundSwitcher({ rounds, activeId, onSelect }: { rounds: TaskRoundSummary[]; activeId: number; onSelect: (taskId: number) => void }) {
  return (
    <div className="task-round-switcher">
      {rounds.map((round) => (
        <button
          key={round.id}
          type="button"
          className={round.id === activeId ? 'task-round-chip task-round-chip-active' : 'task-round-chip'}
          onClick={() => onSelect(round.id)}
        >
          第 {round.roundNo || 1} 轮
        </button>
      ))}
    </div>
  );
}

function mergeRoundList(rounds: TaskRoundSummary[], currentTask: TaskItem) {
  const map = new Map<number, TaskRoundSummary>();
  rounds.forEach((round) => map.set(round.id, round));
  map.set(currentTask.id, {
    id: currentTask.id,
    roundNo: currentTask.roundNo,
    userInput: currentTask.userInput,
    status: currentTask.status,
    requestCount: currentTask.requestCount,
    inputTokens: currentTask.inputTokens,
    cachedInputTokens: currentTask.cachedInputTokens,
    cacheCreationInputTokens: currentTask.cacheCreationInputTokens,
    outputTokens: currentTask.outputTokens,
    reasoningOutputTokens: currentTask.reasoningOutputTokens,
    totalTokens: currentTask.totalTokens,
    resourceMemoryMetrics: currentTask.resourceMemoryMetrics,
    executionMetrics: currentTask.executionMetrics,
    startedAt: currentTask.startedAt,
    endedAt: currentTask.endedAt,
    createdAt: currentTask.createdAt,
    updatedAt: currentTask.updatedAt,
  });
  return Array.from(map.values()).sort((left, right) => (left.roundNo || 1) - (right.roundNo || 1) || left.id - right.id);
}

function latestRound(rounds: TaskRoundSummary[]) {
  return rounds[rounds.length - 1];
}

function isConversationRoot(task: TaskItem) {
  return !task.conversationRootTaskId || task.conversationRootTaskId === task.id;
}

function defaultTaskTab(task: TaskItem) {
  if (isTaskRunning(task)) {
    return 'process';
  }
  const outputView = buildTaskOutput(task);
  if (task.status === 'FAILED' && !hasTaskResult(task, outputView.result)) {
    return 'failure';
  }
  return hasTaskResult(task, outputView.result) ? 'output' : 'process';
}

function isTaskRunning(task: TaskItem) {
  return isActiveTaskStatus(task.status);
}

function mergeTaskDetail(previous: TaskItem | undefined, next: TaskItem) {
  if (!previous || previous.id !== next.id) {
    return next;
  }
  return {
    ...next,
    ...mergeTaskEventState(previous.events, next.events, previous.liveAgentMessages),
    interactions: next.interactions || previous.interactions,
  };
}

function TaskTabPane({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={['task-detail-tab-pane', className || ''].filter(Boolean).join(' ')}>{children}</div>;
}

function displayTaskQuestion(task: TaskItem) {
  return displayTaskTitle(task.userInput || task.title, task.scenario, task.scenarioName);
}

function taskQuestionTitleClass(baseClassName: string, title: string) {
  const normalizedLength = title.replace(/\s/g, '').length;
  if (normalizedLength > 90) {
    return `${baseClassName} task-question-title-dense`;
  }
  if (normalizedLength > 48) {
    return `${baseClassName} task-question-title-long`;
  }
  return baseClassName;
}

function TaskOverview({ task, runtimeModes }: { task: TaskItem; runtimeModes: AgentRuntimeDescriptor[] }) {
  const parameters = taskDisplayParameters(task);
  const configuredModel = runtimeModes
    .flatMap((runtime) => runtime.models)
    .find((model) => model.id === task.modelProfileId);
  const reasoningEffortLabel = configuredModel?.reasoningEffort === task.modelReasoningEffort
    ? configuredModel?.reasoningEffortLabel
    : undefined;
  return (
    <Descriptions size="small" bordered column={{ xs: 1, md: 2, lg: 3 }}>
      <Descriptions.Item label="任务 ID">#{task.id}</Descriptions.Item>
      <Descriptions.Item label="状态"><TaskStatusTag status={task.status} /></Descriptions.Item>
      <Descriptions.Item label="执行模式"><RuntimeModeTag runtimeCode={task.runtimeCode} runtimeModes={runtimeModes} /></Descriptions.Item>
      <Descriptions.Item label="模型"><TaskModelTag modelProviderName={task.modelProviderName} modelName={task.modelName} modelIdentifier={task.modelIdentifier} /></Descriptions.Item>
      <Descriptions.Item label="模型标识">{task.modelIdentifier || '-'}</Descriptions.Item>
      <Descriptions.Item label="推理强度">{reasoningEffortLabel || task.modelReasoningEffort || '模型默认'}</Descriptions.Item>
      <Descriptions.Item label="任务类型">{displayTaskType(task.scenario, task.scenarioName)}</Descriptions.Item>
      <Descriptions.Item label="提问人">{taskOwnerText(task)}</Descriptions.Item>
      <Descriptions.Item label="分析情境" span={3}><PremiseSnapshotView task={task} /></Descriptions.Item>
      {parameters.map((item) => (
        <Descriptions.Item key={item.key} label={item.label}>{formatInputValue(item.value)}</Descriptions.Item>
      ))}
      <Descriptions.Item label="退出码">{task.exitCode ?? '-'}</Descriptions.Item>
      <Descriptions.Item label="创建时间">{formatTime(task.createdAt)}</Descriptions.Item>
      <Descriptions.Item label="开始时间">{formatTime(task.startedAt)}</Descriptions.Item>
      <Descriptions.Item label="结束时间">{formatTime(task.endedAt)}</Descriptions.Item>
      <Descriptions.Item label="耗时">{formatDuration(task.startedAt, task.endedAt)}</Descriptions.Item>
      <Descriptions.Item label="更新时间">{formatTime(task.updatedAt)}</Descriptions.Item>
    </Descriptions>
  );
}

function TaskInputView({ task }: { task: TaskItem }) {
  const inputValues = taskDisplayParameters(task);
  const inputLabels = taskInputLabelMap(task);
  return (
    <div className="task-input-section space-y-3">
      <div>
        <div className="mb-2 text-sm font-medium">用户输入</div>
        <div className="rounded-md border border-neutral-200 bg-neutral-50 p-3 text-sm leading-6 text-neutral-700 whitespace-pre-wrap">
          {task.userInput || '-'}
        </div>
      </div>
      <div>
        <div className="mb-2 text-sm font-medium">参数</div>
        {inputValues.length === 0 ? (
          <div className="rounded-md border border-neutral-200 bg-neutral-50 p-3 text-sm text-neutral-500">暂无参数</div>
        ) : (
          <Descriptions size="small" bordered column={1}>
            {inputValues.map((item) => (
              <Descriptions.Item key={item.key} label={taskInputLabel(item, inputLabels)}>{formatInputValue(item.value)}</Descriptions.Item>
            ))}
          </Descriptions>
        )}
      </div>
    </div>
  );
}

function buildTaskOutput(task: TaskItem) {
  if (task.status === 'CANCELED') {
    return { process: cleanText(task.stdoutText), result: '' };
  }
  const process = cleanText(task.stdoutText);
  const result = cleanText(task.resultText);
  if (result) {
    return { process, result };
  }
  const fallback = splitLegacyOutput(process);
  return { process: fallback.process, result: fallback.result };
}

function hasTaskResult(task: TaskItem, result: string) {
  return Boolean(task.resultData || (task.status !== 'FAILED' && result));
}

function taskFailureText(task: TaskItem, outputView: { process: string; result: string }) {
  return cleanFailureText(task.stderrText || outputView.process || outputView.result || '请查看思考过程获取失败原因');
}

function cleanFailureText(value: string) {
  return cleanText(value).replace(/^执行失败[:：]\s*/, '') || '请查看思考过程获取失败原因';
}

function splitLegacyOutput(content: string) {
  if (!content) {
    return { process: '', result: '' };
  }
  const markers = ['目前', '关键依据', '根因', '查询结果', '最终', '|'];
  const lines = content.split(/\n/);
  const index = lines.findIndex((line) => markers.some((marker) => line.trim().startsWith(marker) || line.includes(`**${marker}`)));
  if (index <= 0) {
    return { process: content, result: '' };
  }
  return {
    process: lines.slice(0, index).join('\n').trim(),
    result: lines.slice(index).join('\n').trim(),
  };
}

function cleanText(text?: string) {
  return cleanGeneratedText(text);
}

function formatInputValue(value: unknown) {
  if (value === undefined || value === null || value === '') {
    return '-';
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return String(value);
}
