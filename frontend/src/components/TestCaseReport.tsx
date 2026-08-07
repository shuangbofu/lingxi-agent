import { MarkdownText } from './MarkdownText';
import { AppLogo } from './AppLogo';
import { BrandWatermark } from './BrandWatermark';
import { AppBrandText } from './AppBrandText';
import { AiDisclaimer } from './AiDisclaimer';
import type { StructuredTaskResult } from '../types/api';

export function TestCaseReport({
  data,
  fallback,
  title,
  reportName = '报告',
  showBrand = false,
  showWatermark = false,
  embedded = false,
  showDisclaimer = true,
}: {
  data?: StructuredTaskResult;
  fallback?: string;
  title?: string;
  reportName?: string;
  showBrand?: boolean;
  showWatermark?: boolean;
  embedded?: boolean;
  showDisclaimer?: boolean;
}) {
  const markdown = data?.markdown || fallback || '';
  const className = [
    'test-report',
    showWatermark ? 'test-report-watermarked' : '',
    embedded ? 'test-report-embedded' : '',
  ].filter(Boolean).join(' ');

  return (
    <article className={className} data-test-report>
      {showWatermark && <BrandWatermark />}
      {showBrand && (
        <header className="test-report-brand">
          <AppLogo className="test-report-brand-logo" />
          <div>
            <AppBrandText className="test-report-brand-name" />
            <div className="test-report-brand-subtitle">{reportName}</div>
          </div>
        </header>
      )}
      {title && <h1 className="test-report-document-title">{title}</h1>}
      <div className="test-report-paper">
        <MarkdownText content={markdown} enhanced />
      </div>
      {showDisclaimer && <AiDisclaimer />}
    </article>
  );
}
