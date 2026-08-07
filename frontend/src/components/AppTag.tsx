import { Tag } from 'antd';
import type { ReactNode } from 'react';
import type { TaskStatus } from '../types/api';
import { statusText } from '../utils/format';

export type AppTagTone = 'emerald' | 'slate' | 'violet' | 'neutral' | 'blue' | 'amber' | 'rose';

interface AppTagProps {
  tone?: AppTagTone;
  status?: boolean;
  children: ReactNode;
  className?: string;
}

/**
 * 渲染平台统一标签样式。
 *
 * @param tone 标签颜色语义
 * @param status 是否使用状态标签字重
 * @param children 标签内容
 * @param className 附加样式类
 * @return 标签节点
 */
export function AppTag({ tone = 'neutral', status = false, children, className }: AppTagProps) {
  return (
    <Tag className={['app-tag', `app-tag-${tone}`, status ? 'app-tag-status' : '', className || ''].filter(Boolean).join(' ')}>
      {children}
    </Tag>
  );
}

export function EnabledTag({ enabled }: { enabled: boolean }) {
  return <AppTag tone={enabled ? 'emerald' : 'slate'}>{enabled ? '启用' : '停用'}</AppTag>;
}

export function TaskStatusTag({ status, className }: { status: TaskStatus; className?: string }) {
  return (
    <AppTag tone={taskStatusTone(status)} status className={[status === 'RUNNING' ? 'status-running-shine' : '', className || ''].filter(Boolean).join(' ')}>
      {statusText(status)}
    </AppTag>
  );
}

function taskStatusTone(status: TaskStatus): AppTagTone {
  const tones: Record<TaskStatus, AppTagTone> = {
    PENDING: 'neutral',
    RUNNING: 'blue',
    WAITING_USER: 'amber',
    SUCCESS: 'emerald',
    FAILED: 'rose',
    CANCELED: 'slate',
  };
  return tones[status];
}
