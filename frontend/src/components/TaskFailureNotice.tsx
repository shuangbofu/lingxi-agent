import { WarningCircle } from '@phosphor-icons/react';

export function TaskFailureNotice({ text }: { text: string }) {
  return (
    <div className="task-failure-notice">
      <WarningCircle size={20} weight="fill" />
      <div className="min-w-0">
        <div className="task-failure-title">执行失败</div>
        <div className="task-failure-text">{text}</div>
      </div>
    </div>
  );
}
