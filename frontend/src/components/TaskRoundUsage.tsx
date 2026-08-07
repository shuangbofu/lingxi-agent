import type { TaskRoundSummary } from '../types/api';
import { formatDuration, formatTime, formatTokenCount } from '../utils/format';
import { TaskStatusTag } from './AppTag';

interface TaskRoundUsageProps {
  rounds: TaskRoundSummary[];
  activeId: number;
}

export function TaskRoundUsage({ rounds, activeId }: TaskRoundUsageProps) {
  if (rounds.length === 0) {
    return null;
  }
  return (
    <section className="task-round-usage-section">
      <div className="task-round-usage-heading">轮次用量</div>
      <div className="task-round-usage-list">
        {rounds.map((round) => (
          <article
            key={round.id}
            className={round.id === activeId ? 'task-round-usage-item task-round-usage-item-active' : 'task-round-usage-item'}
          >
            <div className="task-round-usage-main">
              <div className="task-round-usage-title">
                <strong>第 {round.roundNo || 1} 轮</strong>
                <span title={round.userInput}>{round.userInput || '-'}</span>
              </div>
              <div className="task-round-usage-meta">
                <TaskStatusTag status={round.status} />
                <span>{formatTime(round.createdAt)}</span>
                <span>耗时 {formatDuration(round.startedAt, round.endedAt)}</span>
              </div>
            </div>
            <div className="task-round-usage-metrics">
              <RoundMetric label="请求次数" value={formatRequestCount(round.requestCount)} />
              <RoundMetric label="总 Token" value={formatUsage(round.totalTokens)} emphasized />
              <RoundMetric label="输入" value={formatUsage(round.inputTokens)} />
              <RoundMetric label="输出" value={formatUsage(round.outputTokens)} />
              <RoundMetric label="缓存命中" value={formatUsage(round.cachedInputTokens)} />
              <RoundMetric label="缓存创建" value={formatUsage(round.cacheCreationInputTokens)} />
              <RoundMetric label="推理输出" value={formatUsage(round.reasoningOutputTokens)} />
            </div>
            <div className="task-round-observability">
              <div>
                <span>执行体验</span>
                <strong>首反馈 {formatMilliseconds(round.executionMetrics?.firstFeedbackMs)}</strong>
                <strong>命令 {formatMilliseconds(round.executionMetrics?.commandDurationMs)}</strong>
                <strong>整理 {formatMilliseconds(round.executionMetrics?.resultProcessingMs)}</strong>
                <strong>压缩 {formatCount(round.executionMetrics?.compactionCount)}</strong>
                <strong>重复调用 {formatCount(round.executionMetrics?.duplicateCapabilityCallCount)}</strong>
              </div>
              <div>
                <span>资源记忆</span>
                <strong>检索 {formatCount(round.resourceMemoryMetrics?.searchCount)}</strong>
                <strong>命中 {formatCount(round.resourceMemoryMetrics?.hitCount)}</strong>
                <strong>候选 {formatCount(round.resourceMemoryMetrics?.candidateCount)}</strong>
                <strong>写入 {formatCount(round.resourceMemoryMetrics?.createdCount)}</strong>
                <strong>刷新 {formatCount(round.resourceMemoryMetrics?.refreshedCount)}</strong>
                <strong>过期 {formatCount(round.resourceMemoryMetrics?.expiredCount)}</strong>
                <strong>失效 {formatCount(round.resourceMemoryMetrics?.invalidatedCount)}</strong>
              </div>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}

function RoundMetric({ label, value, emphasized = false }: { label: string; value: string; emphasized?: boolean }) {
  return (
    <div className={emphasized ? 'task-round-usage-metric task-round-usage-metric-emphasized' : 'task-round-usage-metric'}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function formatUsage(value?: number) {
  return value == null ? '-' : formatTokenCount(value);
}

function formatRequestCount(value?: number) {
  return value == null ? '-' : Math.max(0, value).toLocaleString('zh-CN');
}

function formatCount(value?: number) {
  return value == null ? '-' : Math.max(0, value).toLocaleString('zh-CN');
}

function formatMilliseconds(value?: number) {
  if (value == null) return '-';
  if (value < 1000) return `${Math.max(0, value)}ms`;
  if (value < 60_000) return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)}秒`;
  return `${Math.floor(value / 60_000)}分${Math.round((value % 60_000) / 1000)}秒`;
}
