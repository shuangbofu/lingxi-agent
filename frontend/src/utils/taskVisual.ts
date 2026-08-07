import type { TaskItem } from '../types/api';

export function taskDefinitionIconUrl(task: TaskItem) {
  return task.scenarioIconUrl;
}
