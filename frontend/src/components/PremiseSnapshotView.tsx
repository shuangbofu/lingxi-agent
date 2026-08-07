import type { TaskItem } from '../types/api';

export function PremiseSnapshotView({ task }: { task: TaskItem }) {
  const snapshotName = task.premiseSnapshotName || task.premiseName;
  const contextValues = task.premiseSnapshotContextValues || {};
  const contextEntries = Object.entries(contextValues).filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== '');
  const hasSnapshot = Boolean(task.premiseSnapshotName || task.premiseSnapshotDescription || contextEntries.length);
  if (!snapshotName) {
    return <span className="text-neutral-400">-</span>;
  }
  return (
    <div className="premise-snapshot-view">
      <div className="premise-snapshot-head">
        <span className="premise-snapshot-name">{snapshotName}</span>
        {!hasSnapshot && <span className="premise-snapshot-note">当前情境</span>}
      </div>
      {task.premiseSnapshotDescription && (
        <div className="premise-snapshot-description">{task.premiseSnapshotDescription}</div>
      )}
      {contextEntries.length > 0 && (
        <div className="premise-snapshot-context">
          {contextEntries.map(([key, value]) => (
            <span className="premise-snapshot-context-item" key={key}>
              <span className="premise-snapshot-context-key">{key}</span>
              <span className="premise-snapshot-context-value">{String(value)}</span>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
