import { AppLogo } from './AppLogo';
import { AppBrandText } from './AppBrandText';
import { AiDisclaimer } from './AiDisclaimer';
import { formatTime } from '../utils/format';
import type { TaskItem } from '../types/api';
import { StructuredResult } from './StructuredResult';

export function TaskExportDocument({
  rounds,
  title,
  documentName,
  runtimeNames,
}: {
  rounds: TaskItem[];
  title: string;
  documentName: string;
  runtimeNames: Record<string, string>;
}) {
  return (
    <article className="task-export-document" data-task-export>
      <header className="test-report-brand">
        <AppLogo className="test-report-brand-logo" />
        <div>
          <AppBrandText className="test-report-brand-name" />
          <div className="test-report-brand-subtitle">{documentName}</div>
        </div>
      </header>
      <h1 className="test-report-document-title">{title}</h1>
      <div className="task-export-meta">
        <span>文档类型：{documentName}</span>
        <span>导出轮次：{rounds.map((round) => `第 ${round.roundNo || 1} 轮`).join('、')}</span>
      </div>
      {rounds.map((round) => (
        <section className="task-export-round" key={round.id}>
          <div className="task-export-round-head">
            <strong>第 {round.roundNo || 1} 轮</strong>
            <span>记录编号：{round.id}</span>
            <span>Runtime：{runtimeNames[round.runtimeCode] || round.runtimeCode || '未记录'}</span>
            <span>模型：{round.modelName || round.modelIdentifier || '未记录'}</span>
            {round.endedAt && <span>完成：{formatTime(round.endedAt)}</span>}
          </div>
          <div className="task-export-round-question">{round.userInput || '-'}</div>
          <div className="test-report-paper">
            <StructuredResult
              data={round.resultData}
              fallback={round.resultText}
              renderer={round.resultRenderer}
              reportName={documentName}
              showDisclaimer={false}
            />
          </div>
        </section>
      ))}
      <AiDisclaimer />
    </article>
  );
}
