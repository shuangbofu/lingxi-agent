import { useEffect, useState } from 'react';
import { CaretRight } from '@phosphor-icons/react';
import type { AgentScenario, TaskItem } from '../../types/api';
import { displayTaskTitle, displayTaskType, formatDuration, formatTime } from '../../utils/format';
import { isActiveTaskStatus } from '../../utils/taskStatus';
import { cleanGeneratedText } from '../../utils/text';
import { AgentOutput } from '../AgentOutput';
import { StructuredResult } from '../StructuredResult';
import { TaskAttachmentPreviewList } from '../TaskAttachmentPicker';
import { TaskRecommendedScenarios } from '../TaskRecommendedScenarios';
import { TaskFailureNotice } from '../TaskFailureNotice';

/**
 * 会话中的单轮对话：用户气泡 + 智能体过程与结果。
 */
export function ConversationRound({ task, isLatest, scenarios }: { task: TaskItem; isLatest: boolean; scenarios: AgentScenario[] }) {
  const output = buildTaskOutput(task);
  const hasResult = hasTaskResult(task, output.result);
  const failureText = taskFailureText(task, output.result);
  const active = isActiveTaskStatus(task.status);
  const hasProcess = hasTaskProcess(task);
  return (
    <section id={roundElementId(task.id)} className="run-conversation-round">
      <div className="run-user-row">
        <div className="run-user-message">
          <div className="run-message-meta">
            <span>{(task.roundNo || 1) > 1 ? `我 · 第 ${task.roundNo} 轮` : '我'}</span>
            <span>{formatTime(task.createdAt)}</span>
          </div>
          <div className="run-user-bubble">
            <div className="run-user-text">{task.userInput || (task.attachments?.length ? '请处理这些附件' : '-')}</div>
            <TaskAttachmentPreviewList attachments={task.attachments || []} compact />
          </div>
        </div>
      </div>
      <div className="run-agent-row">
        <div className="run-agent-content">
          {hasProcess && active && (
            <div id={roundSectionElementId(task.id, 'process')} className="run-agent-process">
              <AgentOutput
                taskId={task.id}
                entries={task.eventEntries}
                events={task.events}
                liveMessages={task.liveAgentMessages}
                emptyText={active ? '正在处理...' : '暂无执行过程'}
                running={active}
              />
            </div>
          )}
          {hasProcess && !active && <ProcessDisclosure task={task} id={roundSectionElementId(task.id, 'process')} />}
          {hasResult && (
            <div id={roundSectionElementId(task.id, 'result')} className={hasProcess && active ? 'run-agent-result run-agent-result-separated' : 'run-agent-result'}>
              <StructuredResult
                data={task.resultData}
                fallback={output.result}
                title={displayTaskQuestion(task)}
                renderer={task.resultRenderer}
                reportName={displayTaskType(task.scenario, task.scenarioName)}
                embedded
              />
              {isLatest && <TaskRecommendedScenarios task={task} scenarios={scenarios} />}
            </div>
          )}
          {failureText && (
            <div id={roundSectionElementId(task.id, 'result')} className="run-agent-result">
              <TaskFailureNotice text={failureText} />
            </div>
          )}
          {!hasProcess && !hasResult && !failureText && <div className="run-agent-empty">暂无输出</div>}
        </div>
      </div>
    </section>
  );
}

/**
 * 已完成任务的执行过程折叠面板。
 */
export function ProcessDisclosure({ task, id }: { task: TaskItem; id?: string }) {
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    setExpanded(false);
  }, [task.id, task.status]);

  const duration = task.startedAt ? formatDuration(task.startedAt, task.endedAt) : undefined;
  return (
    <section id={id} className={expanded ? 'run-process-disclosure run-process-disclosure-open' : 'run-process-disclosure'}>
      <button type="button" className="run-process-disclosure-toggle" aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>
        <span>已处理{duration ? ` ${duration}` : ''}</span>
        <CaretRight size={14} weight="bold" aria-hidden />
      </button>
      <div className="run-process-disclosure-motion" aria-hidden={!expanded}>
        <div className="run-process-disclosure-body run-agent-process">
          <AgentOutput taskId={task.id} entries={task.eventEntries} events={task.events} liveMessages={task.liveAgentMessages} emptyText="暂无思考过程" />
        </div>
      </div>
    </section>
  );
}

export function displayTaskQuestion(task: TaskItem) {
  return displayTaskTitle(task.userInput || task.title, task.scenario, task.scenarioName);
}

export function roundElementId(taskId: number) {
  return `run-round-${taskId}`;
}

export function roundSectionElementId(taskId: number, section: 'process' | 'result') {
  return `${roundElementId(taskId)}-${section}`;
}

export function buildTaskOutput(task: TaskItem) {
  const process = cleanGeneratedText(task.stdoutText);
  const result = cleanGeneratedText(task.resultText);
  return result ? { process, result } : { process, result: '' };
}

export function hasTaskResult(task: TaskItem, result: string) {
  return Boolean(task.resultData || (task.status !== 'FAILED' && result));
}

export function hasTaskOutcome(task: TaskItem, result: string) {
  return task.status === 'FAILED' || hasTaskResult(task, result);
}

export function taskFailureText(task: TaskItem, result: string) {
  if (task.status !== 'FAILED') {
    return '';
  }
  return cleanGeneratedText(task.stderrText || result)
    .replace(/^执行失败[:：]\s*/, '')
    || '处理失败，未返回错误详情';
}

export function hasTaskProcess(task: TaskItem) {
  return isActiveTaskStatus(task.status)
    || task.status === 'FAILED'
    || Boolean(task.events?.length || task.eventEntries?.length || Object.keys(task.liveAgentMessages || {}).length);
}
