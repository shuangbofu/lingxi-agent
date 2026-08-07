import { useEffect, useMemo, useState } from 'react';
import { Button, Empty, Input, Select, Spin } from 'antd';
import '../styles/history.css';
import { ArrowClockwise, CaretLeft, ClockCounterClockwise } from '@phosphor-icons/react';
import dayjs from 'dayjs';
import { pageTasks } from '../api/lingxi';
import { DefinitionIcon } from '../components/DefinitionIcon';
import type { TaskItem, TaskRoundSummary, TaskStatus } from '../types/api';
import { displayTaskTitle, displayTaskType, formatDuration, formatTime, statusText } from '../utils/format';
import { taskDefinitionIconUrl } from '../utils/taskVisual';
import { TaskStatusTag } from '../components/AppTag';
import { RuntimeModeTag } from '../components/RuntimeModeTag';
import { TaskModelTag } from '../components/TaskModelTag';
import { useRuntimeModes } from '../hooks/useRuntimeModes';
import { usePageTransitionNavigate } from '../hooks/usePageTransitionNavigate';

const HISTORY_SIZE = 80;
type TaskSortBy = 'updatedAt' | 'createdAt';

export function TaskHistoryPage() {
  const transitionNavigate = usePageTransitionNavigate();
  const [tasks, setTasks] = useState<TaskItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<TaskStatus>();
  const [sortBy, setSortBy] = useState<TaskSortBy>('updatedAt');
  const [runtimeCode, setRuntimeCode] = useState<string>();
  const [modelProfileId, setModelProfileId] = useState<string>();
  const [expandedTaskIds, setExpandedTaskIds] = useState<Set<number>>(() => new Set());
  const runtimeModes = useRuntimeModes();

  useEffect(() => {
    loadHistory(query);
  }, [status, sortBy, runtimeCode, modelProfileId]);

  async function loadHistory(nextQuery = query) {
    setLoading(true);
    try {
      const result = await pageTasks({
        page: 1,
        size: HISTORY_SIZE,
        scope: 'mine',
        query: nextQuery,
        status,
        sortBy,
        runtimeCode,
        modelProfileId,
      });
      setTasks(result.records || []);
    } finally {
      setLoading(false);
    }
  }

  const groups = useMemo(() => groupByDate(tasks, sortBy), [sortBy, tasks]);
  const modelOptions = useMemo(() => Array.from(new Map(
    runtimeModes
      .filter((runtime) => !runtimeCode || runtime.code === runtimeCode)
      .flatMap((runtime) => runtime.models)
      .map((model) => [model.id, { label: model.name, value: model.id }] as const),
  ).values()), [runtimeCode, runtimeModes]);

  function selectRuntime(nextRuntimeCode?: string) {
    setRuntimeCode(nextRuntimeCode);
    if (nextRuntimeCode && modelProfileId && !runtimeModes
      .find((runtime) => runtime.code === nextRuntimeCode)
      ?.models.some((model) => model.id === modelProfileId)) {
      setModelProfileId(undefined);
    }
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
    <div className="history-page">
      <section className="history-content">
        <div className="history-headline">
          <ClockCounterClockwise size={20} weight="fill" />
          <div className="min-w-0">
            <h1>历史记录</h1>
          </div>
          <div className="history-actions">
            <Button icon={<CaretLeft size={16} />} onClick={() => transitionNavigate('/', { direction: 'backward' })}>返回</Button>
            <Button icon={<ArrowClockwise size={16} weight="fill" />} onClick={() => loadHistory()}>刷新</Button>
          </div>
        </div>
        <div className="history-filter-bar">
          <Input.Search
            allowClear
            className="history-search"
            placeholder="搜索提问内容"
            size="middle"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onSearch={(value) => loadHistory(value)}
          />
          <Select
            className="history-sort-filter"
            size="middle"
            value={sortBy}
            options={[
              { label: '按更新时间', value: 'updatedAt' },
              { label: '按创建时间', value: 'createdAt' },
            ]}
            onChange={setSortBy}
          />
          <Select
            allowClear
            className="history-status-filter"
            placeholder="全部状态"
            size="middle"
            value={status}
            options={taskStatusOptions()}
            onChange={setStatus}
          />
          <Select
            allowClear
            className="history-runtime-filter"
            placeholder="全部运行方式"
            size="middle"
            value={runtimeCode}
            options={runtimeModes.map((runtime) => ({ label: runtime.name, value: runtime.code }))}
            onChange={selectRuntime}
          />
          <Select
            allowClear
            showSearch
            optionFilterProp="label"
            className="history-model-filter"
            placeholder="全部模型"
            size="middle"
            value={modelProfileId}
            options={modelOptions}
            onChange={setModelProfileId}
          />
        </div>
        <div className="history-scroll-area">
          <Spin spinning={loading} wrapperClassName="history-list-spin">
            {groups.length === 0 && !loading ? (
              <Empty className="history-empty" description="暂无历史记录" />
            ) : (
              <div className="history-groups">
                {groups.map((group) => (
                  <section className="history-group" key={group.date}>
                    <div className="history-date">{group.dateLabel}</div>
                    <div className="history-cards">
                      {group.items.map((task) => (
                        <div
                          className="history-card"
                          role="button"
                          tabIndex={0}
                          key={task.id}
                          onClick={() => transitionNavigate(`/runs/${latestRoundId(task)}?from=history`, { direction: 'forward' })}
                          onKeyDown={(event) => {
                            if (event.key === 'Enter' || event.key === ' ') {
                              event.preventDefault();
                              transitionNavigate(`/runs/${latestRoundId(task)}?from=history`, { direction: 'forward' });
                            }
                          }}
                        >
                          <span className="history-card-icon"><DefinitionIcon src={taskDefinitionIconUrl(task)} label={displayTaskType(task.scenario, task.scenarioName)} size="css" /></span>
                          <div className="history-card-main">
                            <div className="history-card-title">{displayTaskTitle(task.title, task.scenario, task.scenarioName)}</div>
                            <div className="history-card-meta">
                              <span>{displayTaskType(task.scenario, task.scenarioName)}</span>
                              <RuntimeModeTag runtimeCode={task.runtimeCode} runtimeModes={runtimeModes} />
                              <TaskModelTag modelProviderName={task.modelProviderName} modelName={task.modelName} modelIdentifier={task.modelIdentifier} />
                              {task.roundCount && task.roundCount > 1 && <span>共 {task.roundCount} 轮</span>}
                              <span>{sortBy === 'updatedAt' ? `更新：${formatTime(task.updatedAt)}` : `创建：${formatTime(task.createdAt)}`}</span>
                              {task.startedAt && <span>耗时：{formatDuration(task.startedAt, task.endedAt)}</span>}
                            </div>
                            <RoundPreview task={task} expanded={expandedTaskIds.has(task.id)} onToggle={() => toggleRounds(task.id)} />
                          </div>
                          <TaskStatusTag status={task.status} className="history-status" />
                        </div>
                      ))}
                    </div>
                  </section>
                ))}
              </div>
            )}
          </Spin>
        </div>
      </section>
    </div>
  );
}

function RoundPreview({ task, expanded, onToggle }: { task: TaskItem; expanded: boolean; onToggle: () => void }) {
  const rounds = visibleRounds(task.roundSummaries);
  if (rounds.length === 0) {
    return null;
  }
  const previewRound = rounds[rounds.length - 1];
  return (
    <div className="history-round-preview" onClick={(event) => event.stopPropagation()}>
      {!expanded && <RoundFoldToggle expanded={false} roundNo={previewRound.roundNo || 1} text={previewRound.userInput || '-'} onToggle={onToggle} />}
      {expanded && rounds.map((round) => (
        <div className="history-round-line" key={round.id}>
          <span>第 {round.roundNo || 1} 轮 · {formatTime(round.createdAt)}</span>
          <span>{round.userInput || '-'}</span>
        </div>
      ))}
      {expanded && <RoundFoldClose onToggle={onToggle} />}
    </div>
  );
}

function RoundFoldToggle({ expanded, roundNo, text, onToggle }: { expanded: boolean; roundNo: number; text: string; onToggle: () => void }) {
  return (
    <button className="round-fold-toggle history-round-toggle" type="button" onClick={onToggle}>
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

function groupByDate(tasks: TaskItem[], sortBy: TaskSortBy) {
  const sorted = [...tasks].sort((left, right) => taskSortTime(right, sortBy) - taskSortTime(left, sortBy));
  const map = new Map<string, TaskItem[]>();
  sorted.forEach((task) => {
    const date = dayjs(taskSortValue(task, sortBy)).format('YYYY-MM-DD');
    map.set(date, [...(map.get(date) || []), task]);
  });
  return Array.from(map.entries()).map(([date, items]) => ({
    date,
    dateLabel: dateLabel(date),
    items,
  }));
}

function taskSortValue(task: TaskItem, sortBy: TaskSortBy) {
  return sortBy === 'updatedAt' ? task.updatedAt || task.createdAt : task.createdAt;
}

function taskSortTime(task: TaskItem, sortBy: TaskSortBy) {
  return dayjs(taskSortValue(task, sortBy)).valueOf();
}

function dateLabel(date: string) {
  const today = dayjs().format('YYYY-MM-DD');
  const yesterday = dayjs().subtract(1, 'day').format('YYYY-MM-DD');
  if (date === today) {
    return '今天';
  }
  if (date === yesterday) {
    return '昨天';
  }
  return dayjs(date).format('YYYY年MM月DD日');
}

function taskStatusOptions() {
  const statuses: TaskStatus[] = ['PENDING', 'RUNNING', 'WAITING_USER', 'SUCCESS', 'FAILED', 'CANCELED'];
  return statuses.map((value) => ({ label: statusText(value), value }));
}
