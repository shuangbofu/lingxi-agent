import { MarkdownInline, MarkdownText } from './MarkdownText';
import { TestCaseReport } from './TestCaseReport';
import { AiDisclaimer } from './AiDisclaimer';
import type { StructuredTaskResult } from '../types/api';

export function StructuredResult({
  data,
  fallback,
  title,
  renderer,
  reportName,
  showWatermark,
  embedded,
  showDisclaimer = true,
}: {
  data?: StructuredTaskResult;
  fallback?: string;
  title?: string;
  renderer?: string;
  reportName?: string;
  showWatermark?: boolean;
  embedded?: boolean;
  showDisclaimer?: boolean;
}) {
  if ((renderer || data?.renderer) === 'document') {
    return <TestCaseReport data={data} fallback={fallback} title={title} reportName={reportName} showWatermark={showWatermark ?? false} embedded={embedded} showDisclaimer={showDisclaimer} />;
  }
  if ((renderer || data?.renderer) === 'workflow' && data) {
    return <WorkflowResult data={data} fallback={fallback} showDisclaimer={showDisclaimer} />;
  }
  if (!data) {
    return (
      <>
        <MarkdownText content={fallback || ''} enhanced />
        {showDisclaimer && <AiDisclaimer />}
      </>
    );
  }
  const sections = data.sections || [];
  if (!sections.length) {
    return (
      <>
        <MarkdownText content={fallback || data.markdown || ''} enhanced />
        {showDisclaimer && <AiDisclaimer />}
      </>
    );
  }
  return (
    <div className="structured-result">
      {sections.map((section, index) => (
        <section key={`${section.kind}-${section.title}-${index}`} className={`structured-result-section structured-result-${section.kind.toLowerCase()}`}>
          <div className="structured-result-title"><MarkdownInline content={section.title} /></div>
          <MarkdownText content={section.content} enhanced />
        </section>
      ))}
      {showDisclaimer && <AiDisclaimer />}
    </div>
  );
}

function WorkflowResult({ data, fallback, showDisclaimer }: { data: StructuredTaskResult; fallback?: string; showDisclaimer: boolean }) {
  const sections = (data.sections || []).filter((section) => section.title || section.content);
  if (!sections.length) {
    return (
      <div className="workflow-result">
        <MarkdownText content={fallback || data.markdown || ''} enhanced />
        {showDisclaimer && <AiDisclaimer />}
      </div>
    );
  }
  return (
    <div className="workflow-result">
      {sections.map((section, index) => (
        <section className={index === 0 ? 'workflow-result-section workflow-result-summary' : 'workflow-result-section'} key={`${section.title}-${index}`}>
          <div className="workflow-result-title"><MarkdownInline content={section.title} /></div>
          {section.content && <MarkdownText content={section.content} enhanced />}
        </section>
      ))}
      {showDisclaimer && <AiDisclaimer />}
    </div>
  );
}
