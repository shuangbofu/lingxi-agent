import { useEffect, useMemo, useRef, useState } from 'react';
import { CaretRight, HeadCircuit } from '@phosphor-icons/react';
import type { ReactNode } from 'react';
import { useLayoutEffect } from 'react';
import { getTaskEventContent } from '../api/lingxi';
import { CodeBlock, detectCodeLanguage } from './CodeBlock';
import { JsonDataView } from './JsonDataView';
import { MarkdownText } from './MarkdownText';
import { RuntimeActionIconView } from './RuntimeActionIconView';
import { cleanGeneratedText } from '../utils/text';
import type { TaskEventEntry, TaskEventItem, TaskEventPayload, TaskLiveMessage } from '../types/api';

type DetailField = 'detail' | 'arguments' | 'output' | 'message' | 'command' | 'actionTarget' | 'toolName' | 'reasoning';

type OutputDetail = {
  label: string;
  text: string;
  eventId?: number;
  field?: DetailField;
  hasFile?: boolean;
};

type OutputEntry = {
  key?: string;
  type: 'status' | 'message';
  text: string;
  state?: 'running' | 'done' | 'failed' | 'warning' | 'thinking' | 'reasoning' | 'system';
  count?: number;
  details?: OutputDetail[];
  batchId?: string;
  batchSize?: number;
  operationLabel?: string;
  children?: OutputEntry[];
  startedAtMs?: number;
  endedAtMs?: number;
  durationMs?: number;
  timedAction?: boolean;
  actionIcon?: TaskEventPayload['actionIcon'];
  liveReasoning?: boolean;
};

type ActionEntry = OutputEntry & {
  actionKey?: string;
  runningText?: string;
  doneText?: string;
  failedText?: string;
};

interface AgentOutputProps {
  content?: string;
  emptyText: string;
  compact?: boolean;
  fill?: boolean;
  document?: boolean;
  autoScroll?: boolean;
  entries?: TaskEventEntry[];
  events?: TaskEventItem[];
  liveMessages?: Record<string, TaskLiveMessage>;
  running?: boolean;
  taskId?: number;
}

export function AgentOutput({ content, emptyText, compact, fill, document, autoScroll, entries, events, liveMessages, running, taskId }: AgentOutputProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const shouldFollowRef = useRef(true);
  const messages = useMemo(() => {
    const liveEntries: OutputEntry[] = Object.entries(liveMessages || {})
      .filter(([, message]) => Boolean(message.content))
      .map(([messageId, message]) => message.type === 'REASONING'
        ? {
            key: `reasoning:${messageId}`,
            type: 'status',
            text: '深度思考',
            state: 'reasoning',
            details: [{ label: '思考内容', text: message.content, field: 'reasoning' }],
            liveReasoning: true,
          }
        : { key: `live:${messageId}`, type: 'message', text: message.content });
    const eventEntries = events?.length
      ? entriesFromEvents(events, running, liveEntries.length > 0)
      : entries?.length ? normalizeEntries(entries) : [];
    const currentActivities = eventEntries.filter((entry) => (
      entry.key === 'current-activity' || (running && entry.state === 'running')
    ));
    const timelineEntries = eventEntries.filter((entry) => !currentActivities.includes(entry));
    return [...mergeEventEntries(timelineEntries, content, running), ...liveEntries, ...currentActivities];
  }, [entries, events, liveMessages, content, running]);

  useLayoutEffect(() => {
    shouldFollowRef.current = true;
  }, [autoScroll, taskId]);

  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!autoScroll || !container || !shouldFollowRef.current) {
      return;
    }

    container.scrollTop = container.scrollHeight;
    let trailingFrame = 0;
    const layoutFrame = requestAnimationFrame(() => {
      if (!shouldFollowRef.current) {
        return;
      }
      container.scrollTop = container.scrollHeight;
      trailingFrame = requestAnimationFrame(() => {
        if (shouldFollowRef.current) {
          container.scrollTop = container.scrollHeight;
        }
      });
    });

    return () => {
      cancelAnimationFrame(layoutFrame);
      cancelAnimationFrame(trailingFrame);
    };
  }, [autoScroll, messages.length, taskId]);

  useEffect(() => {
    const container = containerRef.current;
    if (!autoScroll || !container) {
      return;
    }
    let scrollFrame = 0;
    const scrollToBottom = () => {
      cancelAnimationFrame(scrollFrame);
      scrollFrame = requestAnimationFrame(() => {
        if (shouldFollowRef.current) {
          container.scrollTop = container.scrollHeight;
        }
      });
    };
    const observer = new ResizeObserver(scrollToBottom);
    observer.observe(container);
    for (const child of container.children) {
      if (child instanceof HTMLElement) {
        observer.observe(child);
      }
    }
    scrollToBottom();

    return () => {
      observer.disconnect();
      cancelAnimationFrame(scrollFrame);
    };
  }, [autoScroll, messages]);

  function handleScroll() {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    shouldFollowRef.current = container.scrollHeight - container.scrollTop - container.clientHeight < 120;
  }

  function handleStatusExpandedChange() {
    shouldFollowRef.current = false;
  }

  if (document) {
    const text = cleanGeneratedText(content);
    if (!text) {
      return <div className={fill ? 'agent-output-empty agent-output-fill' : 'agent-output-empty'}>{emptyText}</div>;
    }
    const className = ['agent-output-document', fill ? 'agent-output-fill' : ''].filter(Boolean).join(' ');
    return <div className={className}><MarkdownText content={text} /></div>;
  }
  if (messages.length === 0) {
    if (running) {
      const pendingClassName = ['agent-output', 'agent-output-pending', fill ? 'agent-output-fill' : ''].filter(Boolean).join(' ');
      return (
        <div className={pendingClassName}>
          <div className="agent-status agent-status-thinking">
            <span className="agent-status-text">正在思考</span>
          </div>
        </div>
      );
    }
    return <div className={fill ? 'agent-output-empty agent-output-fill' : 'agent-output-empty'}>{emptyText}</div>;
  }
  const className = ['agent-output', compact ? 'agent-output-compact' : '', fill ? 'agent-output-fill' : ''].filter(Boolean).join(' ');
  return (
    <div ref={containerRef} className={className} onScroll={handleScroll}>
      {messages.map((entry, index) => (
        entry.type === 'status' ? (
          <StatusEntry key={entry.key || index} entry={entry} taskId={taskId} onExpandedChange={handleStatusExpandedChange} />
        ) : (
          <div key={entry.key || index} className="agent-message">
            <MarkdownText content={entry.text} enhanced />
          </div>
        )
      ))}
    </div>
  );
}

function StatusEntry({ entry, taskId, onExpandedChange }: { entry: OutputEntry; taskId?: number; onExpandedChange: () => void }) {
  const [expanded, setExpanded] = useState(false);
  const [fullTextByKey, setFullTextByKey] = useState<Record<string, string>>({});
  const [expandedDetailKeys, setExpandedDetailKeys] = useState<Set<string>>(() => new Set());
  const [loadingKey, setLoadingKey] = useState<string>();
  const duration = useEntryDuration(entry);
  const visibleDetails = entry.details?.filter((detail) => (
    detail.text !== '输入内容已隐藏' && detail.text !== '输出内容已隐藏'
  )) || [];
  const toolDetail = visibleDetails.find((detail) => detail.field === 'toolName');
  const toolTargetDetail = toolDetail
    ? visibleDetails.find((detail) => detail.field === 'actionTarget' && isCompactToolTarget(detail))
    : undefined;
  const hasLeadingIcon = Boolean(entry.actionIcon || entry.state === 'reasoning');
  const foldableDetails = visibleDetails.filter((detail) => {
    if (detail === toolDetail || detail === toolTargetDetail) {
      return false;
    }
    return detail.field !== 'arguments'
      || parameterEntries(detail.text, toolTargetDetail?.text)?.length !== 0;
  });
  const content = (
    <>
      {entry.actionIcon
        ? <RuntimeActionIconView className="agent-status-leading-icon" icon={entry.actionIcon} />
        : entry.state === 'reasoning' && <HeadCircuit className="agent-status-leading-icon" size={15} weight="regular" aria-hidden />}
      <span className="agent-status-text">{entry.text}</span>
      {entry.count && entry.count > 1 && <span className="agent-status-count">x{entry.count}</span>}
    </>
  );
  if (entry.children?.length) {
    return (
      <div className={`agent-status-foldable agent-tool-batch agent-status-${entry.state || 'system'}${expanded ? ' agent-status-foldable-open' : ''}`}>
        <button
          type="button"
          className={hasLeadingIcon ? 'agent-status-summary agent-status-with-icon' : 'agent-status-summary'}
          aria-expanded={expanded}
          onClick={() => {
            onExpandedChange();
            setExpanded((current) => !current);
          }}
        >
          {content}
          {duration && <span className="agent-status-duration">{duration}</span>}
          <CaretRight className="agent-status-expand-icon" size={14} weight="bold" aria-hidden />
        </button>
        <div className="agent-status-detail-motion" aria-hidden={!expanded}>
          <div className="agent-status-detail agent-tool-batch-items">
            {entry.children.map((child, index) => (
              <StatusEntry
                key={child.key || `${entry.key}:tool:${index}`}
                entry={child}
                taskId={taskId}
                onExpandedChange={onExpandedChange}
              />
            ))}
          </div>
        </div>
      </div>
    );
  }
  if (!toolDetail && !foldableDetails.length) {
    return <div className={`agent-status agent-status-${entry.state || 'system'}${hasLeadingIcon ? ' agent-status-with-icon' : ''}`}>{content}</div>;
  }
  return (
    <div className={`agent-status-foldable agent-status-${entry.state || 'system'}${entry.liveReasoning ? ' agent-status-reasoning-live' : ''}${expanded ? ' agent-status-foldable-open' : ''}`}>
      <button
        type="button"
        className={hasLeadingIcon ? 'agent-status-summary agent-status-with-icon' : 'agent-status-summary'}
        aria-expanded={expanded}
        onClick={() => {
          onExpandedChange();
          setExpanded((current) => !current);
        }}
      >
        {content}
        {duration && <span className="agent-status-duration">{duration}</span>}
        <CaretRight className="agent-status-expand-icon" size={14} weight="bold" aria-hidden />
      </button>
      <div className="agent-status-detail-motion" aria-hidden={!expanded}>
        <div className="agent-status-detail">
          {toolDetail && (
            <div className="agent-status-tool-meta">
              <span className="agent-status-tool-label">工具</span>
              <span className="agent-status-tool-chip">{toolDetail.text}</span>
              {toolTargetDetail && <span className="agent-status-tool-target">{toolTargetDetail.text}</span>}
            </div>
          )}
          {foldableDetails.map((detail, index) => {
            const key = detailKey(detail, index);
            const fullText = fullTextByKey[key];
            const displayText = fullText || detail.text;
            const canLoad = taskId !== undefined && detail.eventId !== undefined && detail.field && detail.hasFile;
            const contentOmitted = Boolean(canLoad && !fullText);
            const isCommandDetail = detail.field === 'command' || detail.field === 'actionTarget';
            const isArgumentDetail = detail.field === 'arguments';
            const isReasoningDetail = detail.field === 'reasoning';
            const isOutputDetail = detail.field === 'output' || detail.field === 'message' || detail.field === 'detail';
            const canCollapse = !canLoad && !isCommandDetail && detail.field !== 'reasoning' && isVerboseDetail(displayText);
            const detailExpanded = expandedDetailKeys.has(key);
            const codeLanguage = isOutputDetail && !contentOmitted ? detectCodeLanguage(displayText) : undefined;
            const integratedCodeHeader = codeLanguage === 'json' || codeLanguage === 'sql' || codeLanguage === 'yaml';
            const inlineOutput = isOutputDetail
              && !contentOmitted
              && !integratedCodeHeader
              && !hasMarkdownFormatting(displayText)
              && displayText.length <= 160
              && !/[\r\n]/.test(displayText);
            const boxedTextOutput = isOutputDetail && !contentOmitted && !integratedCodeHeader && !inlineOutput;
            const integratedResultHeader = integratedCodeHeader || boxedTextOutput;
            const detailAction: ReactNode = canLoad ? (
              <button
                type="button"
                className="agent-status-detail-action"
                disabled={loadingKey === key}
                onClick={() => handleToggleFullContent(key, taskId, detail, fullText)}
              >
                {loadingKey === key ? '读取中...' : fullText ? '收起完整内容' : '查看完整内容'}
              </button>
            ) : canCollapse ? (
              <button
                type="button"
                className="agent-status-detail-action"
                onClick={() => setExpandedDetailKeys((previous) => toggleSetValue(previous, key))}
              >
                {detailExpanded ? '收起' : '展开'}
              </button>
            ) : undefined;
            return (
              <div className={[
                'agent-status-detail-item',
                isCommandDetail ? 'agent-status-command-detail' : '',
                isArgumentDetail ? 'agent-status-argument-detail' : '',
                isReasoningDetail ? 'agent-status-reasoning-detail' : '',
                isOutputDetail ? 'agent-status-output-detail' : '',
                integratedCodeHeader ? 'agent-status-output-code' : '',
                inlineOutput ? 'agent-status-output-inline' : '',
              ].filter(Boolean).join(' ')} key={key}>
                {!isCommandDetail && !isArgumentDetail && !isReasoningDetail && !integratedResultHeader && (
                  <div className="agent-status-detail-head">
                    <span>{detail.label}</span>
                    {detailAction}
                  </div>
                )}
                {contentOmitted ? (
                  <div className="agent-status-detail-omitted">
                    <span>内容较长，已省略</span>
                    {(isArgumentDetail || isCommandDetail) && detailAction}
                  </div>
                ) : isCommandDetail ? (
                  <div className="agent-status-command-text">{displayCommandText(displayText)}</div>
                ) : (
                  <div className={canCollapse && !detailExpanded ? 'agent-status-detail-content agent-status-detail-content-collapsed' : 'agent-status-detail-content'}>
                    <DetailText
                      text={displayText}
                      field={detail.field}
                      codeTitle={integratedResultHeader ? detail.label : undefined}
                      codeAction={integratedResultHeader ? detailAction : undefined}
                      boxedResult={boxedTextOutput}
                      excludedParameterValue={toolTargetDetail?.text}
                    />
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );

  async function handleToggleFullContent(key: string, currentTaskId: number, detail: OutputDetail, fullText?: string) {
    if (fullText) {
      setFullTextByKey((previous) => {
        const next = { ...previous };
        delete next[key];
        return next;
      });
      return;
    }
    if (!detail.eventId || !detail.field) {
      return;
    }
    setLoadingKey(key);
    try {
      const sourceField = detail.field === 'reasoning' ? 'detail' : detail.field;
      const result = await getTaskEventContent(currentTaskId, detail.eventId, sourceField);
      setFullTextByKey((previous) => ({ ...previous, [key]: result.content }));
    } finally {
      setLoadingKey(undefined);
    }
  }
}

function DetailText({ text, field, codeTitle, codeAction, boxedResult, excludedParameterValue }: {
  text: string;
  field?: DetailField;
  codeTitle?: string;
  codeAction?: ReactNode;
  boxedResult?: boolean;
  excludedParameterValue?: string;
}) {
  if (text === '输出内容已隐藏' || text === '输入内容已隐藏') {
    return <div className="agent-status-detail-muted">{text}</div>;
  }
  if (field === 'arguments') {
    const parameters = parameterEntries(text, excludedParameterValue);
    if (parameters) {
      return (
        <dl className="agent-status-parameter-list">
          {parameters.map(([name, value]) => (
            <div className={`agent-status-parameter-row agent-status-parameter-${parameterPresentation(value)}`} key={name}>
              <dt>{name}</dt>
              <dd>{parameterValue(value)}</dd>
            </div>
          ))}
        </dl>
      );
    }
  }
  if (field === 'reasoning') {
    return <div className="agent-status-reasoning-content"><MarkdownText content={text} /></div>;
  }
  const language = detectCodeLanguage(text);
  if (language === 'json' && (field === 'output' || field === 'message' || field === 'detail')) {
    return <JsonDataView source={text} title={codeTitle} action={codeAction} />;
  }
  if (language === 'sql' || language === 'yaml') {
    return <CodeBlock code={text} language={language} title={codeTitle} extra={codeAction} />;
  }
  if (field === 'output' || field === 'message' || field === 'detail') {
    if (boxedResult) {
      return (
        <div className="markdown-code agent-status-markdown-result">
          <div className="syntax-code-head">
            <span className="syntax-code-title">{codeTitle || '执行结果'}</span>
            {codeAction && <div className="syntax-code-actions">{codeAction}</div>}
          </div>
          <div className="agent-status-markdown-result-body">
            <MarkdownText content={text} />
          </div>
        </div>
      );
    }
    return <div className="agent-status-detail-markdown"><MarkdownText content={text} /></div>;
  }
  return <pre>{text}</pre>;
}

function parseParameterObject(text: string): [string, unknown][] | undefined {
  try {
    const value: unknown = JSON.parse(text);
    if (!value || Array.isArray(value) || typeof value !== 'object') {
      return undefined;
    }
    return Object.entries(value);
  } catch {
    return undefined;
  }
}

function parameterEntries(text: string, excludedValue?: string) {
  const entries = parseParameterObject(text);
  if (!entries || !excludedValue) {
    return entries;
  }
  return entries.filter(([, value]) => value !== excludedValue);
}

function parameterValue(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return <span className="agent-status-parameter-empty">未设置</span>;
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (typeof value === 'string' || typeof value === 'number') {
    return String(value);
  }
  if (Array.isArray(value) && value.every((item) => item === null || ['string', 'number', 'boolean'].includes(typeof item))) {
    return value.length ? value.map((item) => String(item ?? '未设置')).join('、') : <span className="agent-status-parameter-empty">无</span>;
  }
  return <code className="agent-status-parameter-json">{JSON.stringify(value)}</code>;
}

function parameterPresentation(value: unknown) {
  if (value === null || value === undefined || typeof value === 'boolean' || typeof value === 'number') {
    return 'compact';
  }
  if (typeof value === 'string') {
    return value.length <= 36 && !/[\r\n]/.test(value) ? 'compact' : 'wide';
  }
  if (Array.isArray(value) && value.every((item) => item === null || ['string', 'number', 'boolean'].includes(typeof item))) {
    return JSON.stringify(value).length <= 60 ? 'compact' : 'wide';
  }
  return 'structured';
}

function isVerboseDetail(text: string) {
  return text.length > 600 || text.split(/\r?\n/).length > 8;
}

function isCompactToolTarget(detail: OutputDetail) {
  return !detail.hasFile && detail.text.length <= 120 && !/[\r\n]/.test(detail.text);
}

function hasMarkdownFormatting(text: string) {
  return /(^|\n)\s{0,3}(#{1,6}\s|[-*+]\s|\d+\.\s|>\s|```)|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\)|\|[^\n]+\|/.test(text);
}

function toggleSetValue(values: Set<string>, value: string) {
  const next = new Set(values);
  if (next.has(value)) {
    next.delete(value);
  } else {
    next.add(value);
  }
  return next;
}

function displayCommandText(command: string) {
  return collapseHeredocBodies(unwrapShellCommand(command))
    .split(/\r?\n/)
    .map((line) => line.replace(/[^\S\r\n]+/g, ' ').trim())
    .join('\n')
    .replace(/[\t ]*&&[\t ]*/g, '\n')
    .replace(/[\t ]*;[\t ]*/g, '\n')
    .trim();
}

function unwrapShellCommand(command: string) {
  const value = command.trim();
  const match = value.match(/^(?:\/bin\/)?(?:bash|zsh|sh)\s+-lc\s+(['"])([\s\S]*)\1$/i);
  return match?.[2]?.trim() || value;
}

function collapseHeredocBodies(command: string) {
  const lines = command.split(/\r?\n/);
  const visibleLines: string[] = [];
  let terminator: string | undefined;
  for (const line of lines) {
    if (terminator) {
      if (line.trim() === terminator) {
        terminator = undefined;
      }
      continue;
    }
    visibleLines.push(line);
    const match = line.match(/<<-?\s*(['"]?)([A-Za-z_][A-Za-z0-9_]*)\1/);
    if (match) {
      visibleLines.push('...');
      terminator = match[2];
    }
  }
  return visibleLines.join('\n');
}

function entriesFromEvents(events: TaskEventItem[], running?: boolean, hasLiveMessage?: boolean): OutputEntry[] {
  const entries = summarizeActivity(visibleProcessEvents(events, running, hasLiveMessage).map((event) => ({
    key: isModelReasoningEvent(event) && event.payload?.itemId
      ? `reasoning:${event.payload.itemId}`
      : event.payload?.transientEvent
      ? 'current-activity'
      : `event:${event.id}`,
    type: event.type === 'AGENT_MESSAGE' ? 'message' : 'status',
    text: eventDisplayText(event),
    state: eventStatusState(event),
    count: 1,
    details: eventDetailLines(event),
    batchId: event.payload?.actionGroupId,
    batchSize: event.payload?.actionGroupSize,
    operationLabel: eventOperationLabel(event),
    actionKey: event.payload?.actionInstanceId || event.payload?.actionKey,
    runningText: event.status === 'RUNNING' ? eventTitle(event) : undefined,
    doneText: event.status === 'SUCCESS' ? eventTitle(event) : undefined,
    failedText: event.status === 'FAILED' ? eventTitle(event) : undefined,
    startedAtMs: event.status === 'RUNNING' ? eventTimestamp(event.createdAt) : undefined,
    endedAtMs: event.status === 'SUCCESS' || event.status === 'FAILED' ? eventTimestamp(event.createdAt) : undefined,
    timedAction: event.type === 'COMMAND' && Boolean(event.payload?.actionInstanceId || event.payload?.actionKey),
    actionIcon: event.payload?.actionIcon,
  })), running);
  return groupToolBatchEntries(entries);
}

function groupToolBatchEntries(entries: OutputEntry[]) {
  const result: OutputEntry[] = [];
  for (let index = 0; index < entries.length; index += 1) {
    const entry = entries[index];
    if (!entry.batchId || entry.type !== 'status') {
      result.push(entry);
      continue;
    }
    const children = [entry];
    while (index + 1 < entries.length) {
      const next = entries[index + 1];
      if (next.type !== 'status' || next.batchId !== entry.batchId) {
        break;
      }
      children.push(next);
      index += 1;
    }
    const size = children.reduce((current, child) => Math.max(current, child.batchSize || 0), children.length);
    if (size <= 1) {
      result.push(entry);
      continue;
    }
    const failedCount = children.filter((child) => child.state === 'failed').length;
    const groupRunning = children.length < size
      || children.some((child) => child.state === 'running');
    const operationLabels = Array.from(new Set(children
      .map((child) => child.operationLabel?.trim())
      .filter((label): label is string => Boolean(label))));
    const operationSummary = operationLabels.length
      ? `${operationLabels.slice(0, 2).join('、')}等 ${size} 项`
      : `${size} 项操作`;
    result.push({
      key: `tool-batch:${entry.batchId}`,
      type: 'status',
      text: groupRunning
        ? `正在${operationSummary}`
        : failedCount > 0
          ? `${operationSummary}完成，${failedCount} 项失败`
          : `${operationSummary}已完成`,
      state: groupRunning ? 'running' : failedCount > 0 ? 'failed' : 'done',
      children,
      startedAtMs: minimumTimestamp(children.map((child) => child.startedAtMs)),
      endedAtMs: groupRunning ? undefined : maximumTimestamp(children.map((child) => child.endedAtMs)),
      durationMs: groupRunning ? undefined : groupedDuration(children),
      timedAction: true,
      actionIcon: mergedActionIcon(children, groupRunning),
    });
  }
  return result;
}

function visibleProcessEvents(events: TaskEventItem[], running?: boolean, hasLiveMessage?: boolean) {
  const processEvents = events.filter((event) => !isCompletionEvent(event) && event.payload?.visibility !== 'INTERNAL');
  let lastThinkingIndex = -1;
  processEvents.forEach((event, index) => {
    if (isThinkingEvent(event)) {
      lastThinkingIndex = index;
    }
  });
  const keepThinking = Boolean(running) && !hasLiveMessage && lastThinkingIndex === processEvents.length - 1;
  return processEvents
    .filter((event, index) => !isThinkingEvent(event) || (keepThinking && index === lastThinkingIndex))
    .filter((event) => event.type !== 'THINKING' || event.status === 'RUNNING' || isModelReasoningEvent(event))
    .filter((event, index, list) => running || index !== list.length - 1 || event.type !== 'AGENT_MESSAGE');
}

function isThinkingEvent(event: TaskEventItem) {
  return event.type === 'THINKING' && event.status === 'RUNNING';
}

function isCompletionEvent(event: TaskEventItem) {
  return event.type === 'SYSTEM'
    && event.status === 'SUCCESS'
    && event.payload?.transientEvent === true;
}

function eventDisplayText(event: TaskEventItem) {
  if (event.type === 'AGENT_MESSAGE') {
    return event.detail || event.title;
  }
  return eventTitle(event);
}

function mergeEventEntries(eventEntries: OutputEntry[], content?: string, running?: boolean): OutputEntry[] {
  if (eventEntries.length === 0) {
    return splitMessages(content, running);
  }
  const fallbackMessages = splitMessages(content, running).filter((entry) => entry.type === 'message');
  const hasMessage = eventEntries.some((entry) => entry.type === 'message');
  if (hasMessage || fallbackMessages.length === 0) {
    return eventEntries;
  }
  return [...eventEntries, ...fallbackMessages];
}

function eventStatusState(event: TaskEventItem): OutputEntry['state'] {
  if (event.type === 'THINKING' && event.status === 'RUNNING') {
    return 'thinking';
  }
  if (isModelReasoningEvent(event)) {
    return 'reasoning';
  }
  if (event.status === 'RUNNING') {
    return 'running';
  }
  if (isOptionalResourceFileMissingEvent(event)) {
    return 'warning';
  }
  if (isSearchNoMatchEvent(event)) {
    return 'system';
  }
  if (event.status === 'SUCCESS') {
    return 'done';
  }
  if (event.status === 'FAILED') {
    return 'failed';
  }
  return 'system';
}

function eventTitle(event: TaskEventItem) {
  if (event.type === 'THINKING' && event.status === 'RUNNING') {
    return '正在思考';
  }
  if (isModelReasoningEvent(event)) {
    return '深度思考';
  }
  if (event.type === 'AGENT_MESSAGE') {
    return event.title;
  }
  if (isOptionalResourceFileMissingEvent(event)) {
    return '暂无可用的资源候选';
  }
  const actionKey = event.payload?.actionKey;
  const target = event.payload?.actionTarget;
  if (actionKey) {
    return formatActionTitle(actionKey, event.status, target, event.payload);
  }
  return formatSystemTitle(event);
}

function eventOperationLabel(event: TaskEventItem) {
  const actionKey = event.payload?.actionKey;
  if (!actionKey) {
    return undefined;
  }
  const providedLabel = event.payload?.actionLabel?.trim();
  return providedLabel && providedLabel !== actionKey
    ? providedLabel
    : actionLabel(actionKey, event.payload?.actionTarget, event.payload);
}

function eventDetailLines(event: TaskEventItem) {
  const details = eventDetails(event);
  return details.length ? details : undefined;
}

function eventDetails(event: TaskEventItem) {
  const payload = event.payload;
  const details: OutputDetail[] = [];
  if (isModelReasoningEvent(event)) {
    appendDetail(details, '思考内容', event.detail, event, 'reasoning', event.detailFile || payload?.detailFile);
    return details;
  }
  const shellAction = Boolean(payload?.actionKey
    && (isCommandAction(payload.actionKey) || isCapabilityAction(payload.actionKey)));
  if (shellAction) {
    appendDetail(details, '命令', payload?.actionTarget || payload?.command, event, 'actionTarget', payload?.actionTargetFile);
    if (payload?.actionKey && isCapabilityAction(payload.actionKey)) {
      appendDetail(details, '工具', payload.actionLabel, event, 'toolName');
    }
  } else {
    appendDetail(details, '命令', payload?.command, event, 'command', payload?.commandFile);
    if (!payload?.command) {
      appendDetail(details, '命令', payload?.actionTarget, event, 'actionTarget', payload?.actionTargetFile);
    }
    appendDetail(details, '工具', payload?.actionLabel || payload?.toolName, event, 'toolName');
  }
  appendDetail(details, '调用参数', payload?.arguments, event, 'arguments', payload?.argumentsFile);
  appendDetail(details, '执行结果', payload?.output, event, 'output', payload?.outputFile);
  if (!payload?.output) {
    appendDetail(details, '执行结果', payload?.message, event, 'message', payload?.messageFile);
  }
  if (!payload?.command && !payload?.output && !payload?.message) {
    appendDetail(details, '执行结果', event.detail, event, 'detail', event.detailFile || payload?.detailFile);
  }
  return details;
}

function isModelReasoningEvent(event: TaskEventItem) {
  return event.type === 'THINKING'
    && event.status === 'SUCCESS';
}

function appendDetail(details: OutputDetail[], label: string, value?: string, event?: TaskEventItem, field?: DetailField, hasFile?: boolean) {
  const text = value?.trim();
  if (!text) {
    return;
  }
  details.push({
    label,
    text,
    eventId: event?.id,
    field,
    hasFile,
  });
}

function normalizeEntries(entries: TaskEventEntry[]): OutputEntry[] {
  return entries.map((entry) => ({
    ...entry,
    details: entry.details?.map((text) => ({ label: '详情', text })),
  }));
}

function detailKey(detail: OutputDetail, index: number) {
  return `${detail.eventId || 'local'}-${detail.field || detail.label}-${index}`;
}

function splitMessages(content?: string, running?: boolean): OutputEntry[] {
  const text = cleanGeneratedText(content);
  if (!text) {
    return [];
  }
  const entries = parseOutputEntries(text);
  return summarizeActivity(entries, running);
}

function parseOutputEntries(text: string) {
  const entries: OutputEntry[] = [];
  const messageLines: string[] = [];
  for (const rawLine of text.split(/\n/)) {
    const line = rawLine.trim();
    if (!line) {
      if (messageLines.length > 0) {
        messageLines.push('');
      }
      continue;
    }
    const entry = toOutputEntry(line);
    if (entry.type === 'status') {
      flushMessageLines(entries, messageLines);
      entries.push(entry);
      continue;
    }
    messageLines.push(rawLine);
  }
  flushMessageLines(entries, messageLines);
  return entries;
}

function flushMessageLines(entries: OutputEntry[], messageLines: string[]) {
  const text = messageLines.join('\n').trim();
  messageLines.length = 0;
  if (text) {
    entries.push({ type: 'message', text });
  }
}

function toOutputEntry(text: string): ActionEntry {
  if (text === '正在思考' || text === '思考中...') {
    return { type: 'status', text, state: 'thinking', count: 1 };
  }
  if (text.startsWith('正在')) {
    return { type: 'status', text, state: 'running', count: 1 };
  }
  if (text.startsWith('已完成：')) {
    return { type: 'status', text, state: 'done', count: 1 };
  }
  if (text.startsWith('未完成：') || text.startsWith('执行引擎错误') || text.startsWith('执行引擎执行失败')) {
    return { type: 'status', text, state: 'failed', count: 1 };
  }
  if (text.startsWith('执行引擎')) {
    return { type: 'status', text, state: 'system', count: 1 };
  }
  return { type: 'message', text };
}

function formatActionTitle(actionKey: string, status: TaskEventItem['status'], target?: string, payload?: TaskEventPayload) {
  const providedLabel = payload?.actionLabel?.trim();
  if (isCapabilityAction(actionKey) && providedLabel && providedLabel !== actionKey) {
    return status === 'RUNNING'
      ? `正在${providedLabel}`
      : status === 'FAILED'
        ? `${providedLabel}失败`
        : status === 'SUCCESS'
          ? `${providedLabel}完成`
          : providedLabel;
  }
  if (!isCommandAction(actionKey) && providedLabel && providedLabel !== actionKey) {
    return status === 'RUNNING'
      ? `正在${providedLabel}`
      : status === 'FAILED'
        ? `${providedLabel}失败`
        : status === 'SUCCESS'
          ? `已${providedLabel}`
          : providedLabel;
  }
  const label = actionLabel(actionKey, target, payload);
  if (status === 'RUNNING') {
    return `正在${label}`;
  }
  if (status === 'FAILED') {
    if (isSearchNoMatchPayload(payload)) {
      return `${label}无结果`;
    }
    return `${label}失败`;
  }
  if (status === 'SUCCESS') {
    return `${label}完成`;
  }
  return label;
}

function actionLabel(actionKey: string, target?: string, payload?: TaskEventPayload) {
  if (isCommandAction(actionKey)) {
    return commandActionLabel(target || payload?.command || actionKey);
  }
  if (isCapabilityAction(actionKey)) {
    return payload?.actionLabel?.trim() || '调用能力';
  }
  if (actionKey.startsWith('tool:')) {
    const providedLabel = payload?.actionLabel?.trim();
    const toolName = payload?.toolName?.trim();
    return providedLabel && providedLabel !== actionKey && providedLabel !== toolName
      ? providedLabel
      : toolName ? `调用工具 ${toolName}` : '调用工具';
  }
  const labels: Record<string, string> = { command: '执行命令', tool: '调用工具' };
  return labels[actionKey] || actionKey;
}

function isCommandAction(actionKey: string) {
  return actionKey === 'command' || actionKey.startsWith('command:');
}

function isCapabilityAction(actionKey: string) {
  return actionKey === 'capability' || actionKey.startsWith('capability:');
}

function commandActionLabel(command: string) {
  const value = displayCommandText(command);
  const rules: Array<[RegExp, string]> = [
    [/^cat\b.*\s>>?\s*\S+/, '写入文件'],
    [/^(sed|cat|head|tail|nl)\b/, '查看文件'],
    [/^(rg|grep|find)\b/, '搜索代码'],
    [/^(mvn|gradle|\.\/gradlew)\b.*\btest\b|^(yarn|npm|pnpm)\b.*\btest\b/, '运行测试'],
    [/^(mvn|gradle|\.\/gradlew)\b.*\b(package|install|build|compile)\b|^(yarn|npm|pnpm)\b.*\b(build|compile)\b/, '构建项目'],
    [/^(yarn|npm|pnpm)\s+install\b/, '安装依赖'],
    [/^(python|python3|node|java)\b/, '运行脚本'],
    [/^(ls|pwd|tree|du|wc)\b/, '查看项目文件'],
  ];
  return rules.find(([pattern]) => pattern.test(value))?.[1] || '执行命令';
}

function formatSystemTitle(event: TaskEventItem) {
  const providedLabel = event.payload?.actionLabel?.trim();
  if (providedLabel) {
    return providedLabel;
  }
  const label = event.type === 'ERROR'
    ? '执行引擎错误'
    : event.status === 'RUNNING'
      ? '执行引擎处理中'
      : event.status === 'SUCCESS'
        ? '执行引擎处理完成'
        : '执行引擎状态更新';
  if (event.status === 'FAILED' && !label.includes('失败') && !label.includes('错误')) {
    return `${label}失败`;
  }
  return label;
}

function isSearchNoMatchEvent(event: TaskEventItem) {
  return event.status === 'FAILED' && isSearchNoMatchPayload(event.payload);
}

function isOptionalResourceFileMissingEvent(event: TaskEventItem) {
  if (event.status !== 'FAILED' || event.payload?.toolName !== 'read_workspace_file') {
    return false;
  }
  try {
    const argumentsValue = JSON.parse(event.payload.arguments || '{}') as { relativePath?: string };
    const output = event.payload.output || event.payload.message || event.detail || '';
    return argumentsValue.relativePath === '.agent-task/resources.json' && output.includes('文件不存在');
  } catch {
    return false;
  }
}

function isSearchNoMatchPayload(payload?: TaskEventPayload) {
  if (payload?.exitCode !== 1) {
    return false;
  }
  const command = normalizeShellCommand(payload.actionTarget || payload.command || '');
  return /^(rg|grep)\b/.test(command) && !payload.output?.trim() && !payload.message?.trim();
}

function normalizeShellCommand(command: string) {
  return command
    .replace(/\s+/g, ' ')
    .replace(/^(?:\/bin\/)?(?:bash|zsh|sh)\s+-lc\s+(['"])(.*)\1$/i, '$2')
    .trim();
}

function summarizeActivity(entries: ActionEntry[], running?: boolean) {
  const timeline: ActionEntry[] = [];
  const activeActionIndexes = new Map<string, number>();

  // 工具行以开始事件为锚点，完成事件只原位更新，避免并行工具按完成顺序重排。
  for (const entry of entries) {
    const actionKey = entry.actionKey;
    if (entry.type === 'message' && activeActionIndexes.size > 0) {
      const activeEntries = Array.from(activeActionIndexes.entries())
        .sort((left, right) => left[1] - right[1])
        .map(([key, index]) => ({ key, entry: timeline[index] }));
      const activeIndexes = new Set(activeEntries.map(({ key }) => activeActionIndexes.get(key)));
      const settledTimeline = timeline.filter((_, index) => !activeIndexes.has(index));
      settledTimeline.push(entry);
      activeActionIndexes.clear();
      activeEntries.forEach(({ key, entry: activeEntry }) => {
        activeActionIndexes.set(key, settledTimeline.length);
        settledTimeline.push(activeEntry);
      });
      timeline.length = 0;
      timeline.push(...settledTimeline);
      continue;
    }
    if (actionKey && entry.runningText) {
      const activeIndex = activeActionIndexes.get(actionKey);
      const runningEntry = { ...entry, text: entry.runningText, state: 'running' as const };
      if (activeIndex === undefined) {
        activeActionIndexes.set(actionKey, timeline.length);
        timeline.push(runningEntry);
      } else {
        timeline[activeIndex] = {
          ...runningEntry,
          startedAtMs: timeline[activeIndex].startedAtMs ?? runningEntry.startedAtMs,
        };
      }
      continue;
    }

    const finishedText = entry.doneText || entry.failedText;
    if (actionKey && finishedText) {
      const activeIndex = activeActionIndexes.get(actionKey);
      const finishedEntry = { ...entry, text: finishedText };
      if (activeIndex === undefined) {
        timeline.push(finishedEntry);
      } else {
        const startedAtMs = timeline[activeIndex].startedAtMs;
        const endedAtMs = finishedEntry.endedAtMs;
        timeline[activeIndex] = {
          ...finishedEntry,
          key: timeline[activeIndex].key || finishedEntry.key,
          startedAtMs,
          durationMs: startedAtMs !== undefined && endedAtMs !== undefined
            ? Math.max(0, endedAtMs - startedAtMs)
            : undefined,
        };
        activeActionIndexes.delete(actionKey);
      }
      continue;
    }

    timeline.push(entry.state === 'thinking' ? { ...entry, text: '正在思考' } : { ...entry });
  }

  const unresolvedIndexes = new Set(activeActionIndexes.values());
  const result: OutputEntry[] = [];
  timeline.forEach((entry, index) => {
    if (!running && unresolvedIndexes.has(index)) {
      return;
    }
    const previous = result[result.length - 1];
    if (entry.type === 'status'
      && previous?.type === 'status'
      && previous.text === entry.text
      && previous.state === entry.state
      && !previous.details?.length
      && !entry.details?.length) {
      previous.count = (previous.count || 1) + (entry.count || 1);
      previous.actionIcon = entry.actionIcon || previous.actionIcon;
      return;
    }
    result.push({ ...entry });
  });
  return result;
}

function useEntryDuration(entry: OutputEntry) {
  const [now, setNow] = useState(() => Date.now());
  const live = entry.timedAction && entry.state === 'running' && entry.startedAtMs !== undefined;

  useEffect(() => {
    if (!live) {
      return;
    }
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 100);
    return () => window.clearInterval(timer);
  }, [live, entry.startedAtMs]);

  if (!entry.timedAction) {
    return undefined;
  }
  const elapsed = entry.durationMs ?? (live ? Math.max(0, now - entry.startedAtMs!) : undefined);
  return elapsed === undefined ? undefined : formatDuration(elapsed);
}

function formatDuration(durationMs: number) {
  if (durationMs < 1000) {
    return `${Math.round(durationMs)} ms`;
  }
  const totalSeconds = Math.round(durationMs / 1000);
  if (totalSeconds < 60) {
    return `${totalSeconds} s`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  return `${minutes} m ${String(totalSeconds % 60).padStart(2, '0')} s`;
}

function eventTimestamp(value?: string) {
  if (!value) {
    return undefined;
  }
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : undefined;
}

function minimumTimestamp(values: Array<number | undefined>) {
  const timestamps = values.filter((value): value is number => value !== undefined);
  return timestamps.length ? Math.min(...timestamps) : undefined;
}

function maximumTimestamp(values: Array<number | undefined>) {
  const timestamps = values.filter((value): value is number => value !== undefined);
  return timestamps.length ? Math.max(...timestamps) : undefined;
}

function groupedDuration(entries: OutputEntry[]) {
  const startedAtMs = minimumTimestamp(entries.map((entry) => entry.startedAtMs));
  const endedAtMs = maximumTimestamp(entries.map((entry) => entry.endedAtMs));
  return startedAtMs !== undefined && endedAtMs !== undefined
    ? Math.max(0, endedAtMs - startedAtMs)
    : undefined;
}

function mergedActionIcon(entries: OutputEntry[], running: boolean): OutputEntry['actionIcon'] {
  let fallback: OutputEntry['actionIcon'];
  let selected: OutputEntry['actionIcon'];
  let selectedEndedAt = Number.NEGATIVE_INFINITY;
  for (const entry of entries) {
    if (!entry.actionIcon) continue;
    fallback = entry.actionIcon;
    if (running && entry.state === 'running') {
      selected = entry.actionIcon;
    } else if (!running && (entry.endedAtMs ?? Number.NEGATIVE_INFINITY) >= selectedEndedAt) {
      selected = entry.actionIcon;
      selectedEndedAt = entry.endedAtMs ?? Number.NEGATIVE_INFINITY;
    }
  }
  return selected || fallback;
}
