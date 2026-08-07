import { useCallback, useEffect, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { getTask } from '../api/lingxi';
import type { TaskItem } from '../types/api';
import { mergeTaskEventState } from './useTaskEventStream';

type TaskSetter = Dispatch<SetStateAction<TaskItem | undefined>>;

/**
 * 按任务懒加载完整思考过程，并隔离轮次切换时的并发请求。
 *
 * @param task 当前展示的任务
 * @param activeTab 当前详情页签
 * @param setTask 任务详情状态更新函数
 * @returns 过程是否可展示、失败状态、重载与重置操作
 */
export function useTaskProcessLoader(task: TaskItem | undefined, activeTab: string | undefined, setTask: TaskSetter) {
  const requestSequenceRef = useRef(0);
  const loadedTaskIdRef = useRef<number>();
  const observedTaskIdRef = useRef(task?.id);
  const loadingRequestsRef = useRef(new Map<number, number>());
  const currentTaskIdRef = useRef(task?.id);
  const [failedTaskId, setFailedTaskId] = useState<number>();
  currentTaskIdRef.current = task?.id;
  if (observedTaskIdRef.current !== task?.id) {
    observedTaskIdRef.current = task?.id;
    loadedTaskIdRef.current = undefined;
  }

  useEffect(() => {
    setFailedTaskId(undefined);
  }, [task?.id]);

  const load = useCallback(async (taskId: number) => {
    if (loadingRequestsRef.current.has(taskId)) {
      return;
    }
    setFailedTaskId(undefined);
    const requestId = requestSequenceRef.current + 1;
    requestSequenceRef.current = requestId;
    loadingRequestsRef.current.set(taskId, requestId);
    try {
      const detail = await getTask(taskId, { includeEvents: true });
      if (loadingRequestsRef.current.get(taskId) !== requestId || currentTaskIdRef.current !== taskId) {
        return;
      }
      loadedTaskIdRef.current = taskId;
      setTask((current) => {
        if (current?.id !== taskId) {
          return current;
        }
        return {
          ...current,
          ...mergeTaskEventState(current.events, detail.events, current.liveAgentMessages),
          eventEntries: undefined,
          interactions: current.interactions?.length ? current.interactions : detail.interactions,
        };
      });
    } catch {
      if (loadingRequestsRef.current.get(taskId) === requestId && currentTaskIdRef.current === taskId) {
        setFailedTaskId(taskId);
      }
    } finally {
      if (loadingRequestsRef.current.get(taskId) === requestId) {
        loadingRequestsRef.current.delete(taskId);
      }
    }
  }, [setTask]);

  useEffect(() => {
    if (!task || activeTab !== 'process'
      || loadedTaskIdRef.current === task.id
      || loadingRequestsRef.current.has(task.id)
      || failedTaskId === task.id) {
      return;
    }
    load(task.id);
  }, [task?.id, task?.status, activeTab, failedTaskId, load]);

  const reset = useCallback((taskId: number) => {
    if (loadedTaskIdRef.current === taskId) {
      loadedTaskIdRef.current = undefined;
    }
    loadingRequestsRef.current.delete(taskId);
    setFailedTaskId(undefined);
  }, []);

  return {
    ready: Boolean(task && loadedTaskIdRef.current === task.id),
    failed: Boolean(task && failedTaskId === task.id),
    reload: () => task && load(task.id),
    reset,
  };
}
