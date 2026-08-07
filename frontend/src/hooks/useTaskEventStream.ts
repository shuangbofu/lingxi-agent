import { useEffect, useRef } from 'react';
import type { TaskEventItem, TaskInteractionItem, TaskItem, TaskLiveMessage, TaskMessageDelta, TaskUsageSnapshot } from '../types/api';
import { isActiveTaskStatus } from '../utils/taskStatus';

type TaskUpdater = (updater: (task?: TaskItem) => TaskItem | undefined) => void;

export function useTaskEventStream(task?: TaskItem, setTask?: TaskUpdater, onComplete?: (taskId: number) => void, includeTaskEvents = true) {
  const onCompleteRef = useRef(onComplete);
  const connectionIdRef = useRef(createConnectionId());

  useEffect(() => {
    onCompleteRef.current = onComplete;
  }, [onComplete]);

  useEffect(() => {
    if (!task || !setTask || !isActiveTaskStatus(task.status)) {
      return;
    }
    let lastEventId = task.events?.reduce((max, event) => Math.max(max, event.id), 0) || 0;
    let source: EventSource | undefined;
    let reconnectTimer: number | undefined;
    let reconnectAttempt = 0;
    let stopped = false;
    let completed = false;
    const complete = () => {
      if (completed) {
        return;
      }
      completed = true;
      stopped = true;
      source?.close();
      if (reconnectTimer !== undefined) {
        window.clearTimeout(reconnectTimer);
      }
      onCompleteRef.current?.(task.id);
    };
    const scheduleReconnect = (currentSource: EventSource) => {
      if (stopped || source !== currentSource || reconnectTimer !== undefined) {
        return;
      }
      currentSource.close();
      source = undefined;
      const delay = Math.min(1500 * (2 ** reconnectAttempt), 8000);
      reconnectAttempt += 1;
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = undefined;
        connect();
      }, delay);
    };
    const connect = () => {
      if (stopped) {
        return;
      }
      const query = new URLSearchParams({
        afterId: String(lastEventId),
        includeTaskEvents: String(includeTaskEvents),
        connectionId: connectionIdRef.current,
      });
      const currentSource = new EventSource(`/api/agent/tasks/${task.id}/events/stream?${query}`);
      source = currentSource;
      currentSource.onopen = () => {
        reconnectAttempt = 0;
      };
      currentSource.addEventListener('task-event', (message) => {
        const event = JSON.parse((message as MessageEvent<string>).data) as TaskEventItem;
        if (event.id > 0) {
          lastEventId = Math.max(lastEventId, event.id);
        }
        appendEvent(setTask, task.id, event);
      });
      currentSource.addEventListener('task-usage', (message) => {
        const usage = JSON.parse((message as MessageEvent<string>).data) as TaskUsageSnapshot;
        applyUsage(setTask, task.id, usage);
      });
      currentSource.addEventListener('task-message-delta', (message) => {
        const delta = JSON.parse((message as MessageEvent<string>).data) as TaskMessageDelta;
        applyMessageDelta(setTask, task.id, delta);
      });
      currentSource.addEventListener('task-complete', complete);
      currentSource.addEventListener('stream-rejected', () => scheduleReconnect(currentSource));
      currentSource.onerror = () => scheduleReconnect(currentSource);
    };
    connect();
    return () => {
      stopped = true;
      source?.close();
      if (reconnectTimer !== undefined) {
        window.clearTimeout(reconnectTimer);
      }
    };
  }, [includeTaskEvents, task?.id, task?.status]);
}

function createConnectionId() {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function applyMessageDelta(setTask: TaskUpdater, taskId: number, messageDelta: TaskMessageDelta) {
  if (!messageDelta.messageId || (!messageDelta.delta && messageDelta.content === undefined)) {
    return;
  }
  setTask((current) => {
    if (!current || current.id !== taskId) {
      return current;
    }
    const type = messageDelta.type || 'MESSAGE';
    if (hasCommittedMessage(current.events, messageDelta.messageId, type)) {
      return current;
    }
    const liveAgentMessages = { ...(current.liveAgentMessages || {}) };
    const previous = liveAgentMessages[messageDelta.messageId];
    liveAgentMessages[messageDelta.messageId] = {
      type,
      content: messageDelta.content ?? `${previous?.content || ''}${messageDelta.delta || ''}`,
    };
    return { ...current, liveAgentMessages };
  });
}

function applyUsage(setTask: TaskUpdater, taskId: number, usage: TaskUsageSnapshot) {
  setTask((current) => {
    if (!current || current.id !== taskId) {
      return current;
    }
    return { ...current, ...usage };
  });
}

function appendEvent(setTask: TaskUpdater, taskId: number, event: TaskEventItem) {
  setTask((current) => {
    if (!current || current.id !== taskId) {
      return current;
    }
    const eventState = mergeTaskEventState(current.events, [event], current.liveAgentMessages);
    return {
      ...current,
      ...eventState,
      interactions: mergeInteractionFromEvent(current.interactions || [], event),
      eventEntries: undefined,
    };
  });
}

/**
 * 合并持久化事件，并只在对应完整消息已经到达后移除实时草稿。
 *
 * @param currentEvents 页面当前事件
 * @param incomingEvents 接口刷新或 SSE 新收到的事件
 * @param liveAgentMessages 尚未提交的实时消息
 * @returns 合并后的事件与实时消息状态
 */
export function mergeTaskEventState(
  currentEvents: TaskEventItem[] | undefined,
  incomingEvents: TaskEventItem[] | undefined,
  liveAgentMessages: Record<string, TaskLiveMessage> | undefined,
) {
  const events = mergeEvents(currentEvents || [], incomingEvents || []);
  const liveMessages = { ...(liveAgentMessages || {}) };
  for (const event of events) {
    if ((event.type === 'AGENT_MESSAGE' || event.type === 'THINKING') && event.payload?.itemId) {
      delete liveMessages[event.payload.itemId];
    }
  }
  return {
    events,
    liveAgentMessages: Object.keys(liveMessages).length ? liveMessages : undefined,
  };
}

function hasCommittedMessage(events: TaskEventItem[] | undefined, messageId: string, type: TaskLiveMessage['type']) {
  const committedEventType = type === 'REASONING' ? 'THINKING' : 'AGENT_MESSAGE';
  return events?.some((event) => event.type === committedEventType && event.payload?.itemId === messageId) === true;
}

export function mergeEvents(current: TaskEventItem[], incoming: TaskEventItem[]) {
  const byId = new Map<number, TaskEventItem>();
  for (const event of current) {
    byId.set(event.id, event);
  }
  for (const event of incoming) {
    if (event.payload?.transientEvent) {
      removeTransientEvents(byId);
      byId.set(event.id, event);
      continue;
    }
    removeStaleTransientEvents(byId, event);
    byId.set(event.id, event);
  }
  return Array.from(byId.values()).sort((a, b) => eventSortValue(a) - eventSortValue(b));
}

function removeTransientEvents(events: Map<number, TaskEventItem>) {
  for (const [id, event] of events.entries()) {
    if (event.payload?.transientEvent) {
      events.delete(id);
    }
  }
}

function removeStaleTransientEvents(events: Map<number, TaskEventItem>, incoming: TaskEventItem) {
  const incomingTime = eventCreatedTime(incoming);
  for (const [id, event] of events.entries()) {
    if (event.payload?.transientEvent && eventCreatedTime(event) <= incomingTime) {
      events.delete(id);
    }
  }
}

function eventSortValue(event: TaskEventItem) {
  return event.payload?.transientEvent ? Number.MAX_SAFE_INTEGER : event.id;
}

function eventCreatedTime(event: TaskEventItem) {
  const value = Date.parse(event.createdAt || '');
  return Number.isFinite(value) ? value : 0;
}

function mergeInteractionFromEvent(current: TaskInteractionItem[], event: TaskEventItem) {
  const payload = event.payload;
  if (event.type !== 'INTERACTION' || !payload?.interactionId || !payload.question || !payload.interactionInputType || !payload.interactionStatus) {
    return current;
  }
  const next: TaskInteractionItem = {
    id: payload.interactionId,
    taskId: 0,
    question: payload.question,
    content: payload.interactionContent,
    inputType: payload.interactionInputType,
    options: payload.options || [],
    actions: payload.actions || [],
    fields: payload.fields || [],
    required: payload.required,
    placeholder: payload.placeholder,
    answerHint: payload.answerHint,
    defaultValue: payload.defaultValue,
    contextKey: payload.contextKey,
    status: payload.interactionStatus,
    answerText: payload.answerText,
    selectedValues: payload.selectedValues || [],
    answerValues: payload.answerValues || [],
    answerAction: payload.interactionAnswerAction,
    answerActionKey: payload.interactionAnswerActionKey,
    createdAt: event.createdAt,
  };
  const byId = new Map<number, TaskInteractionItem>();
  for (const item of current) {
    byId.set(item.id, item);
  }
  byId.set(next.id, { ...(byId.get(next.id) || {}), ...next });
  return Array.from(byId.values()).sort((a, b) => a.id - b.id);
}
