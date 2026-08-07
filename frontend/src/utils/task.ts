import type { TaskItem } from '../types/api';

export function taskOwnerText(task: Pick<TaskItem, 'ownerDisplayName' | 'ownerUsername'>) {
  if (!task.ownerDisplayName && !task.ownerUsername) {
    return '-';
  }
  if (!task.ownerDisplayName || task.ownerDisplayName === task.ownerUsername) {
    return task.ownerUsername || task.ownerDisplayName;
  }
  return `${task.ownerDisplayName}（${task.ownerUsername}）`;
}
