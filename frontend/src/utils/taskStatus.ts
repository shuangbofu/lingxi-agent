import type { TaskStatus } from '../types/api';

export function isActiveTaskStatus(status?: TaskStatus) {
  return status === 'PENDING' || status === 'RUNNING' || status === 'WAITING_USER';
}
