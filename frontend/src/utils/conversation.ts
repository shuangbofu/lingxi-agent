import type { TaskItem, TaskRoundSummary } from '../types/api';
import { mergeTaskEventState } from '../hooks/useTaskEventStream';

/**
 * 合并轮次摘要与当前任务详情，返回按轮次升序的完整轮次列表。
 *
 * @param rounds 会话轮次摘要列表
 * @param currentTask 当前任务详情（总是并入列表）
 * @return 升序轮次列表
 */
export function mergeRoundList(rounds: TaskRoundSummary[], currentTask: TaskItem) {
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

/**
 * 返回会话轮次列表中的最新一轮。
 *
 * @param rounds 升序轮次列表
 * @return 最后一轮
 */
export function latestRound(rounds: TaskRoundSummary[]) {
  return rounds[rounds.length - 1];
}

/**
 * 合并同一任务的两次详情快照，保留实时消息草稿。
 *
 * @param previous 页面当前任务详情
 * @param next 接口新返回的任务详情
 * @return 合并后的任务详情
 */
export function mergeTaskDetail(previous: TaskItem | undefined, next: TaskItem) {
  if (!previous || previous.id !== next.id) {
    return next;
  }
  return {
    ...next,
    ...mergeTaskEventState(previous.events, next.events, previous.liveAgentMessages),
    interactions: next.interactions || previous.interactions,
  };
}
