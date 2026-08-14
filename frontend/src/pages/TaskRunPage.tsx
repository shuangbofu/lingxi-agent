import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { UIEvent } from 'react';
import '../styles/task-workspace.css';
import { Button, Segmented } from 'antd';
import { ArrowCounterClockwise, Article, CaretLeft, ChartBar, ChatsCircle, Stop } from '@phosphor-icons/react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { cancelTask, continueTaskRound, getTask, listEnabledScenarios, listTaskInteractions, listTaskRounds, rerunTask, resumeTask, retryTask } from '../api/lingxi';
import { AgentOutput } from '../components/AgentOutput';
import { StructuredResult } from '../components/StructuredResult';
import { TaskDownloadPdfButton } from '../components/TaskDownloadPdfButton';
import { TaskShareButton } from '../components/TaskShareButton';
import { TaskUserFloat } from '../components/TaskUserFloat';
import { TaskResumeModal } from '../components/TaskResumeModal';
import { DefinitionIcon } from '../components/DefinitionIcon';
import { ExpandableTitle } from '../components/ExpandableTitle';
import { AppTag, TaskStatusTag } from '../components/AppTag';
import { TaskRecommendedScenarios } from '../components/TaskRecommendedScenarios';
import { TaskDetailLoading } from '../components/TaskDetailLoading';
import { TaskFailureNotice } from '../components/TaskFailureNotice';
import { RuntimeModeTag } from '../components/RuntimeModeTag';
import { TaskModelTag } from '../components/TaskModelTag';
import { ConversationRound, ProcessDisclosure, buildTaskOutput, displayTaskQuestion, hasTaskOutcome, hasTaskProcess, hasTaskResult, roundElementId, roundSectionElementId, taskFailureText } from '../components/conversation/ConversationRound';
import { useTaskEventStream } from '../hooks/useTaskEventStream';
import { useRuntimeModes } from '../hooks/useRuntimeModes';
import { usePageTransitionNavigate } from '../hooks/usePageTransitionNavigate';
import type { AgentRuntimeDescriptor, AgentScenario, TaskItem, TaskRoundSummary } from '../types/api';
import { displayTaskType, formatDuration, formatTime } from '../utils/format';
import { latestRound, mergeRoundList, mergeTaskDetail } from '../utils/conversation';
import { isActiveTaskStatus } from '../utils/taskStatus';
import { taskDefinitionIconUrl } from '../utils/taskVisual';
import { useAuth } from '../context/AuthContext';

type RunViewMode = 'conversation' | 'reading';

export function TaskRunPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const transitionNavigate = usePageTransitionNavigate();
  const [searchParams] = useSearchParams();
  const { hasPermission } = useAuth();
  const animateLaunch = Boolean((location.state as { animateLaunch?: boolean } | null)?.animateLaunch);
  const [task, setTask] = useState<TaskItem>();
  const [rounds, setRounds] = useState<TaskRoundSummary[]>([]);
  const [roundDetails, setRoundDetails] = useState<Record<number, TaskItem>>({});
  const [scenarios, setScenarios] = useState<AgentScenario[]>([]);
  const runtimeModes = useRuntimeModes();
  const [retrying, setRetrying] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [rerunning, setRerunning] = useState(false);
  const [viewMode, setViewMode] = useState<RunViewMode>('conversation');
  const [activeRoundId, setActiveRoundId] = useState<number>();
  const [readingRoundId, setReadingRoundId] = useState<number>();
  const requestRef = useRef(0);
  const loadTaskRef = useRef<(taskId: number, openLatestRound?: boolean) => Promise<void>>();
  const conversationRef = useRef<HTMLDivElement>(null);
  const locatedConversationRef = useRef(false);
  const scrollingToRoundRef = useRef<number>();
  const shouldFollowLatestRef = useRef(true);
  const observedTaskStateRef = useRef<{ taskId: number; active: boolean }>();
  const active = isActiveTaskStatus(task?.status);
  const currentOutput = task ? buildTaskOutput(task) : { process: '', result: '' };
  const currentHasResult = task ? hasTaskResult(task, currentOutput.result) : false;
  const formMode = scenarios.some((scenario) => scenario.code === task?.scenarioCode && scenario.inputMode === 'FORM');
  const visibleRounds = useMemo(() => task ? mergeRoundList(rounds, task) : rounds, [rounds, task]);
  const conversationTasks = useMemo(() => visibleRounds
    .map((round) => round.id === task?.id ? task : roundDetails[round.id])
    .filter((round): round is TaskItem => Boolean(round)), [visibleRounds, roundDetails, task]);
  const readingTask = (readingRoundId === task?.id ? task : readingRoundId ? roundDetails[readingRoundId] : undefined) || task;
  const defaultRuntime = runtimeModes.find((item) => item.defaultSelected);
  const premiseName = task?.premiseSnapshotName || task?.premiseName;
  const canChangeToDefaultRuntime = task?.status === 'FAILED'
    && Boolean(defaultRuntime)
    && task.runtimeCode !== defaultRuntime?.code;

  const handleComplete = useCallback((taskId: number) => {
    void loadTaskRef.current?.(taskId);
  }, []);
  useTaskEventStream(task, setTask, handleComplete, true);

  useEffect(() => {
    if (!task) {
      return;
    }
    const taskActive = isActiveTaskStatus(task.status);
    const previous = observedTaskStateRef.current;
    observedTaskStateRef.current = { taskId: task.id, active: taskActive };
    if (!previous || previous.taskId !== task.id) {
      setViewMode(taskActive ? 'conversation' : 'reading');
      setReadingRoundId(task.id);
      return;
    }
    if (previous.active && !taskActive && viewMode === 'conversation') {
      setActiveRoundId(task.id);
      shouldFollowLatestRef.current = false;
      window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => scrollToConversationSection('result'));
      });
    }
  }, [task?.id, task?.status, viewMode]);

  useEffect(() => {
    if (formMode && task) {
      setViewMode('reading');
      setReadingRoundId(task.id);
    }
  }, [formMode, task?.id]);

  useEffect(() => {
    setTask(undefined);
    setRounds([]);
    setRoundDetails({});
    setActiveRoundId(undefined);
    setReadingRoundId(undefined);
    locatedConversationRef.current = false;
    scrollingToRoundRef.current = undefined;
    shouldFollowLatestRef.current = true;
    observedTaskStateRef.current = undefined;
    void listEnabledScenarios().then(setScenarios);
    if (id) {
      void loadTaskRef.current?.(Number(id), true);
    }
  }, [id]);

  useEffect(() => {
    if (!task || !isActiveTaskStatus(task.status)) {
      return;
    }
    const timer = window.setInterval(() => {
      void refreshActiveTask(task.id);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [task?.id, task?.status]);

  useEffect(() => {
    if (viewMode !== 'conversation' || locatedConversationRef.current || conversationTasks.length === 0) {
      return;
    }
    locatedConversationRef.current = true;
    const latest = conversationTasks[conversationTasks.length - 1];
    if (active) {
      shouldFollowLatestRef.current = true;
      setActiveRoundId(latest.id);
      window.requestAnimationFrame(scrollConversationToBottom);
      return;
    }
    const section = conversationTasks.some((round) => hasTaskOutcome(round, buildTaskOutput(round).result)) ? 'result' : 'process';
    setActiveRoundId(latest.id);
    window.requestAnimationFrame(() => {
      scrollToConversationSection(section);
    });
  }, [viewMode, conversationTasks, active]);

  useEffect(() => {
    const container = conversationRef.current;
    if (viewMode !== 'conversation' || !active || !container) {
      return;
    }
    shouldFollowLatestRef.current = true;
    const content = container.firstElementChild;
    const followLatest = () => {
      if (!shouldFollowLatestRef.current) {
        return;
      }
      window.requestAnimationFrame(() => {
        if (shouldFollowLatestRef.current) {
          container.scrollTop = container.scrollHeight;
        }
      });
    };
    followLatest();
    const observer = new ResizeObserver(followLatest);
    observer.observe(content || container);
    return () => observer.disconnect();
  }, [viewMode, active, task?.id]);

  async function loadTask(taskId: number, openLatestRound = false) {
    const requestId = requestRef.current + 1;
    requestRef.current = requestId;
    const [requestedDetail, interactions, listedRounds] = await Promise.all([
      getTask(taskId, { includeEvents: true }),
      listTaskInteractions(taskId),
      listTaskRounds(taskId),
    ]);
    if (requestRef.current !== requestId) {
      return;
    }
    const requestedWithInteractions = { ...requestedDetail, interactions };
    const mergedRounds = mergeRoundList(listedRounds, requestedWithInteractions);
    const latest = latestRound(mergedRounds);
    if (openLatestRound && latest && latest.id !== requestedWithInteractions.id) {
      navigate(`/runs/${latest.id}?${searchParams.toString()}`, { replace: true });
      return;
    }
    const details = await Promise.all(mergedRounds.map(async (round) => {
      if (round.id === requestedWithInteractions.id) {
        return requestedWithInteractions;
      }
      return getTask(round.id, { includeEvents: true });
    }));
    if (requestRef.current !== requestId) {
      return;
    }
    const detailsById = Object.fromEntries(details.map((detail) => [detail.id, detail]));
    const nextTask = detailsById[latest?.id || requestedWithInteractions.id] || requestedWithInteractions;
    setRounds(mergedRounds);
    setRoundDetails(detailsById);
    setTask((current) => mergeTaskDetail(current, nextTask));
    setActiveRoundId((current) => current && detailsById[current] ? current : nextTask.id);
    setReadingRoundId((current) => current && detailsById[current] ? current : nextTask.id);
  }
  loadTaskRef.current = loadTask;

  async function refreshActiveTask(taskId: number) {
    const [detail, interactions] = await Promise.all([
      getTask(taskId, { includeEvents: true }),
      listTaskInteractions(taskId),
    ]);
    const nextDetail = { ...detail, interactions };
    setTask((current) => mergeTaskDetail(current, nextDetail));
    setRoundDetails((current) => ({ ...current, [taskId]: nextDetail }));
  }

  function handleBack() {
    const from = searchParams.get('from');
    const target = from === 'history' ? '/history' : from === 'ask' ? '/ask' : from === 'chat' ? '/chat' : '/';
    transitionNavigate(target, { direction: 'backward' });
  }

  async function handleCancel() {
    if (!task) {
      return;
    }
    await cancelTask(task.id);
    await loadTask(task.id);
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
      if (result.id !== task.id) {
        navigate(`/runs/${result.id}?${searchParams.toString()}`, { replace: true });
        return;
      }
      requestRef.current += 1;
      locatedConversationRef.current = false;
      scrollingToRoundRef.current = undefined;
      shouldFollowLatestRef.current = true;
      observedTaskStateRef.current = undefined;
      setTask(result);
      setRoundDetails((current) => ({ ...current, [result.id]: result }));
      setRounds((current) => mergeRoundList(current, result));
      setActiveRoundId(result.id);
      setReadingRoundId(result.id);
      setViewMode('conversation');
      const refreshedRounds = await listTaskRounds(result.id);
      setRounds(mergeRoundList(refreshedRounds, result));
    } finally {
      setRetrying(false);
    }
  }

  async function handleRerun(runtime: AgentRuntimeDescriptor) {
    if (!task || !runtime.available) {
      return;
    }
    const model = runtime.models.find((item) => item.id === task.modelProfileId && item.available)
      || runtime.models.find((item) => item.available);
    if (!model) {
      return;
    }
    setRerunning(true);
    try {
      const result = await rerunTask(task.id, runtime.code, model.id);
      navigate(`/runs/${result.id}?${searchParams.toString()}`, { replace: true });
    } finally {
      setRerunning(false);
    }
  }

  async function handleContinue(content: string, attachmentIds: string[]) {
    if (!task) {
      return;
    }
    const result = await continueTaskRound(task.id, { userInput: content, attachmentIds });
    navigate(`/runs/${result.id}?${searchParams.toString()}`, { replace: true });
  }

  function handleRoundSelect(taskId: number) {
    setActiveRoundId(taskId);
    if (viewMode === 'reading') {
      setReadingRoundId(taskId);
      return;
    }
    shouldFollowLatestRef.current = false;
    scrollToRound(taskId, 'smooth');
  }

  function handleViewModeChange(mode: RunViewMode) {
    setViewMode(mode);
    if (mode === 'reading') {
      setReadingRoundId(activeRoundId || task?.id);
      return;
    }
    if (active) {
      shouldFollowLatestRef.current = true;
      window.requestAnimationFrame(scrollConversationToBottom);
      return;
    }
    const section = conversationTasks.some((round) => hasTaskOutcome(round, buildTaskOutput(round).result)) ? 'result' : 'process';
    window.requestAnimationFrame(() => {
      scrollToConversationSection(section);
    });
  }

  function scrollToRound(taskId: number, behavior: ScrollBehavior = 'auto') {
    const container = conversationRef.current;
    const element = document.getElementById(roundElementId(taskId));
    if (!container || !element) {
      return;
    }
    scrollingToRoundRef.current = taskId;
    const distance = element.getBoundingClientRect().top - container.getBoundingClientRect().top;
    container.scrollTo({ top: container.scrollTop + distance - 18, behavior });
  }

  function scrollToConversationSection(section: 'process' | 'result') {
    const selected = conversationTasks.find((round) => round.id === activeRoundId);
    const available = (round: TaskItem) => section === 'process'
      ? hasTaskProcess(round)
      : hasTaskOutcome(round, buildTaskOutput(round).result);
    const target = selected && available(selected)
      ? selected
      : [...conversationTasks].reverse().find(available);
    const container = conversationRef.current;
    if (!container || !target) {
      return;
    }
    const element = document.getElementById(roundSectionElementId(target.id, section));
    if (!element) {
      return;
    }
    shouldFollowLatestRef.current = false;
    setActiveRoundId(target.id);
    scrollingToRoundRef.current = target.id;
    const distance = element.getBoundingClientRect().top - container.getBoundingClientRect().top;
    container.scrollTo({ top: container.scrollTop + distance - 18, behavior: 'smooth' });
  }

  function handleConversationScroll(event: UIEvent<HTMLDivElement>) {
    const distanceFromBottom = event.currentTarget.scrollHeight
      - event.currentTarget.scrollTop
      - event.currentTarget.clientHeight;
    shouldFollowLatestRef.current = distanceFromBottom < 120;
    const containerTop = event.currentTarget.getBoundingClientRect().top;
    let closestId = activeRoundId;
    let closestDistance = Number.MAX_SAFE_INTEGER;
    for (const round of visibleRounds) {
      const element = document.getElementById(roundElementId(round.id));
      if (!element) {
        continue;
      }
      const distance = Math.abs(element.getBoundingClientRect().top - containerTop - 20);
      if (distance < closestDistance) {
        closestDistance = distance;
        closestId = round.id;
      }
    }
    const scrollingToRoundId = scrollingToRoundRef.current;
    if (scrollingToRoundId && closestId !== scrollingToRoundId) {
      return;
    }
    if (scrollingToRoundId === closestId) {
      scrollingToRoundRef.current = undefined;
    }
    if (closestId && closestId !== activeRoundId) {
      setActiveRoundId(closestId);
    }
  }

  function scrollConversationToBottom() {
    const container = conversationRef.current;
    if (!container) {
      return;
    }
    container.scrollTop = container.scrollHeight;
  }

  if (!task) {
    return <TaskDetailLoading variant="run" runMode={viewMode} onBack={handleBack} />;
  }

  return (
    <div className={animateLaunch ? 'ask-run-page ask-run-page-launch' : 'ask-run-page'}>
      {viewMode === 'reading' && (
        <TaskUserFloat taskId={task.id} taskStatus={task.status} userInput={task.userInput} attachments={task.attachments} interactions={task.interactions} inputLabel="我" onAnswered={() => loadTask(task.id)} onContinue={handleContinue} />
      )}
      <section className="ask-run-agent">
        <div className="ask-run-head">
          <div className="ask-run-title-block">
            <span className="ask-run-icon"><DefinitionIcon src={taskDefinitionIconUrl(task)} label={displayTaskType(task.scenario, task.scenarioName)} size="css" /></span>
            <div className="ask-run-heading-content">
              <div className="ask-run-heading-row">
                <ExpandableTitle text={displayTaskQuestion(task)} className="ask-run-title-wrap" textClassName="ask-run-title" />
                <div className="ask-run-actions">
                  <Button size="small" icon={<CaretLeft size={14} />} onClick={handleBack}>返回</Button>
                  {hasPermission('TASK_ADMIN') && (
                    <Button size="small" icon={<ChartBar size={14} weight="fill" />} onClick={() => navigate(`/runs/${task.id}/analysis?returnTo=${encodeURIComponent(location.pathname + location.search)}`)}>分析报告</Button>
                  )}
                  <TaskShareButton size="small" task={task} disabled={active || !currentHasResult} label="分享" />
                  <TaskDownloadPdfButton size="small" task={task} disabled={active || !currentHasResult} />
                  {task.status === 'FAILED' && (
                    <Button size="small" loading={retrying} icon={<ArrowCounterClockwise size={14} weight="bold" />} onClick={() => handleRestart(false)}>重试</Button>
                  )}
                  {task.status === 'CANCELED' && (
                    <Button size="small" loading={retrying} icon={<ArrowCounterClockwise size={14} weight="bold" />} onClick={() => setResumeOpen(true)}>恢复</Button>
                  )}
                  {canChangeToDefaultRuntime && defaultRuntime && (
                    <Button
                      size="small"
                      disabled={!defaultRuntime.available}
                      loading={rerunning}
                      title={defaultRuntime.available ? defaultRuntime.description : defaultRuntime.unavailableReason}
                      onClick={() => handleRerun(defaultRuntime)}
                    >
                      改用{defaultRuntime.name}重新运行
                    </Button>
                  )}
                  <Button size="small" danger disabled={!active} icon={<Stop size={14} weight="fill" />} onClick={handleCancel}>取消</Button>
                </div>
              </div>
              <div className="ask-run-subline">
                <div className="ask-run-meta">
                  <span>{displayTaskType(task.scenario, task.scenarioName)}</span>
                  {premiseName && <AppTag tone="slate">{premiseName}</AppTag>}
                  <TaskStatusTag status={task.status} />
                  <RuntimeModeTag runtimeCode={task.runtimeCode} runtimeModes={runtimeModes} />
                  <TaskModelTag modelProviderName={task.modelProviderName} modelName={task.modelName} modelIdentifier={task.modelIdentifier} />
                  <span>{formMode ? '单次执行' : `${visibleRounds.length} 轮对话`}</span>
                  <span>{formatTime(task.createdAt)}</span>
                  {task.startedAt && <span>耗时：{formatDuration(task.startedAt, task.endedAt)}</span>}
                </div>
                {!formMode && <div className="run-view-controls">
                  <Segmented<RunViewMode>
                    className="app-view-switch"
                    size="small"
                    value={viewMode}
                    onChange={handleViewModeChange}
                    options={[
                      { value: 'conversation', label: <span><ChatsCircle size={13} weight="fill" />对话模式</span> },
                      { value: 'reading', label: <span><Article size={13} weight="fill" />阅读模式</span> },
                    ]}
                  />
                </div>}
              </div>
            </div>
          </div>
        </div>
        <div className="ask-run-body">
          {visibleRounds.length > 1 && (
            <RoundSwitcher rounds={visibleRounds} activeId={viewMode === 'reading' ? readingTask?.id : activeRoundId} onSelect={handleRoundSelect} />
          )}
          {!formMode && viewMode === 'conversation' ? (
            <div ref={conversationRef} className="run-conversation-scroll" onScroll={handleConversationScroll}>
              <div className="run-conversation">
                {conversationTasks.map((round, index) => (
                  <ConversationRound
                    key={round.id}
                    task={round}
                    isLatest={index === conversationTasks.length - 1}
                    scenarios={scenarios}
                  />
                ))}
              </div>
            </div>
          ) : readingTask ? (
            <ReadingView task={readingTask} scenarios={scenarios} />
          ) : null}
        </div>
        {!formMode && viewMode === 'conversation' && (
          <TaskUserFloat mode="composer" taskId={task.id} taskStatus={task.status} userInput={task.userInput} attachments={task.attachments} interactions={task.interactions} inputLabel="我" onAnswered={() => loadTask(task.id)} onContinue={handleContinue} />
        )}
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

function RoundSwitcher({ rounds, activeId, onSelect }: { rounds: TaskRoundSummary[]; activeId?: number; onSelect: (taskId: number) => void }) {
  return (
    <nav className="run-round-anchor" aria-label="轮次快速定位">
      {rounds.map((round) => {
        const title = round.userInput?.trim() || `第 ${round.roundNo || 1} 轮`;
        return (
          <button
            key={round.id}
            type="button"
            className={round.id === activeId ? 'run-round-anchor-item run-round-anchor-item-active' : 'run-round-anchor-item'}
            title={title}
            aria-current={round.id === activeId ? 'true' : undefined}
            onClick={() => onSelect(round.id)}
          >
            <span className="run-round-anchor-number">{round.roundNo || 1}</span>
            <span className="run-round-anchor-title">{title}</span>
          </button>
        );
      })}
    </nav>
  );
}

function ReadingView({ task, scenarios }: { task: TaskItem; scenarios: AgentScenario[] }) {
  const output = buildTaskOutput(task);
  const hasResult = hasTaskResult(task, output.result);
  const failureText = taskFailureText(task, output.result);
  const active = isActiveTaskStatus(task.status);

  if (active) {
    return (
      <div className="run-reading-view run-reading-live">
        <AgentOutput taskId={task.id} entries={task.eventEntries} events={task.events} liveMessages={task.liveAgentMessages} emptyText="等待输出..." fill autoScroll running />
      </div>
    );
  }

  return (
    <div className="run-reading-view">
      {hasTaskProcess(task) && <ProcessDisclosure task={task} />}
      <div className="run-reading-result agent-output-document">
        {failureText ? <TaskFailureNotice text={failureText} /> : hasResult ? (
          <>
            <StructuredResult
              data={task.resultData}
              fallback={output.result}
              title={displayTaskQuestion(task)}
              renderer={task.resultRenderer}
              reportName={displayTaskType(task.scenario, task.scenarioName)}
              embedded
            />
            <TaskRecommendedScenarios task={task} scenarios={scenarios} />
          </>
        ) : <div className="run-agent-empty">暂无结果</div>}
      </div>
    </div>
  );
}
