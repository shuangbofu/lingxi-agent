import dayjs from 'dayjs';
import type { TaskScenario, TaskStatus } from '../types/api';

export function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

export function formatRelativeTime(value?: string) {
  if (!value) {
    return '-';
  }
  const time = dayjs(value);
  if (!time.isValid()) {
    return '-';
  }
  const seconds = Math.max(0, dayjs().diff(time, 'second'));
  if (seconds < 60) {
    return '刚刚';
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}分钟前`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}小时前`;
  }
  const days = Math.floor(hours / 24);
  if (days < 30) {
    return `${days}天前`;
  }
  return time.format('MM-DD');
}

export function formatDuration(startedAt?: string, endedAt?: string) {
  if (!startedAt) {
    return '-';
  }
  const start = dayjs(startedAt);
  const end = endedAt ? dayjs(endedAt) : dayjs();
  if (!start.isValid() || !end.isValid() || end.isBefore(start)) {
    return '-';
  }
  let totalSeconds = Math.max(1, end.diff(start, 'second'));
  const days = Math.floor(totalSeconds / 86400);
  totalSeconds %= 86400;
  const hours = Math.floor(totalSeconds / 3600);
  totalSeconds %= 3600;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (days > 0) {
    return `${days}天${hours}小时${minutes}分${seconds}秒`;
  }
  if (hours > 0) {
    return `${hours}小时${minutes}分${seconds}秒`;
  }
  if (minutes > 0) {
    return `${minutes}分${seconds}秒`;
  }
  return `${seconds}秒`;
}

export function formatTokenCount(value?: number) {
  const numberValue = Math.max(0, Number(value || 0));
  const units = [
    { value: 1_000_000_000, suffix: 'B' },
    { value: 1_000_000, suffix: 'M' },
    { value: 1_000, suffix: 'K' },
  ];
  const unit = units.find((item) => numberValue >= item.value);
  if (!unit) {
    return numberValue.toLocaleString('zh-CN');
  }
  const scaled = numberValue / unit.value;
  const precision = scaled >= 100 ? 0 : scaled >= 10 ? 1 : 2;
  return `${trimFixed(scaled, precision)}${unit.suffix}`;
}

export function formatModelCost(value?: number, currency?: string) {
  if (value == null || !Number.isFinite(Number(value))) {
    return '-';
  }
  const amount = Math.max(0, Number(value));
  const precision = amount >= 1 ? 4 : amount >= 0.01 ? 6 : 8;
  const text = trimFixed(amount, precision);
  return currency === 'CNY' || !currency ? `¥${text}` : `${text} ${currency}`;
}

export function formatFileSize(value?: number) {
  const bytes = Math.max(0, Number(value || 0));
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${trimFixed(bytes / 1024, 1)} KB`;
  }
  return `${trimFixed(bytes / 1024 / 1024, 1)} MB`;
}

function trimFixed(value: number, precision: number) {
  return value.toFixed(precision).replace(/\.0+$|(\.\d*[1-9])0+$/, '$1');
}

export function displayTaskType(scenario: TaskScenario, scenarioName?: string) {
  return scenarioName || scenario;
}

export function displayTaskTitle(title: string, scenario: TaskScenario, scenarioName?: string) {
  const prefixes = [scenarioName, scenario].filter(Boolean) as string[];
  const matchedPrefix = prefixes.find((prefix) => title.startsWith(`${prefix}：`) || title.startsWith(`${prefix}:`));
  if (!matchedPrefix) {
    return title;
  }
  return title.slice(matchedPrefix.length + 1).trim();
}

export function statusText(value: TaskStatus) {
  const text: Record<TaskStatus, string> = {
    PENDING: '等待中',
    RUNNING: '执行中',
    WAITING_USER: '待确认',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELED: '已取消',
  };
  return text[value];
}
