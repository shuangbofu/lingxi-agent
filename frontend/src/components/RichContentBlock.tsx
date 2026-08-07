import { useState } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import { Modal, Popover, Spin, Tooltip } from 'antd';
import { ArrowDown, ArrowSquareOut, ArrowsInSimple, ArrowsOutSimple, ArrowUp, CaretRight, Equals, Eye, LinkSimple } from '@phosphor-icons/react';
import { CodeBlock } from './CodeBlock';

interface RichContentBlockProps {
  source: string;
  renderContent: (content: string) => ReactNode;
}

type ViewRecord = Record<string, unknown>;

interface ParsedView {
  data: ViewRecord;
  complete: boolean;
}

interface ViewLink {
  title: string;
  url: string;
  description: string;
  source: string;
  target: string;
}

const STATUS_LABELS: Record<string, string> = {
  confirmed: '已确认',
  conflict: '存在冲突',
  error: '失败',
  info: '信息',
  partial: '部分确认',
  pending: '待处理',
  running: '进行中',
  skipped: '已跳过',
  success: '已完成',
  unknown: '待确认',
  warning: '需注意',
};

const VIEW_TYPES = new Set(['timeline', 'evidence', 'steps', 'metrics', 'links']);

export function isRichContentSource(source: string) {
  const value = source.trim();
  if (!value.startsWith('{')) {
    return false;
  }
  const parsed = parseView(value);
  return Boolean(parsed && VIEW_TYPES.has(text(parsed.data.type)));
}

export function RichContentBlock({ source, renderContent }: RichContentBlockProps) {
  const parsed = parseView(source);
  if (!parsed) {
    return <RichViewPending />;
  }
  const { data, complete } = parsed;
  if (data.type === 'timeline') {
    return renderTimeline(data, renderContent) || (complete ? <CodeBlock code={source} language="json" /> : <RichViewPending data={data} />);
  }
  if (data.type === 'evidence') {
    return renderEvidence(data, renderContent) || (complete ? <CodeBlock code={source} language="json" /> : <RichViewPending data={data} />);
  }
  if (data.type === 'steps') {
    return renderSteps(data, renderContent) || (complete ? <CodeBlock code={source} language="json" /> : <RichViewPending data={data} />);
  }
  if (data.type === 'metrics') {
    return renderMetrics(data) || (complete ? <CodeBlock code={source} language="json" /> : <RichViewPending data={data} />);
  }
  if (data.type === 'links') {
    return renderLinks(data, complete) || (complete ? <CodeBlock code={source} language="json" /> : <RichViewPending data={data} />);
  }
  return complete ? <CodeBlock code={source} language="json" /> : <RichViewPending data={data} />;
}

function RichViewPending({ data }: { data?: ViewRecord }) {
  const label = data?.type === 'links' ? '正在整理相关资料' : '正在整理内容';
  return (
    <div className="rich-view-streaming" role="status">
      <Spin size="small" />
      <span>{label}</span>
    </div>
  );
}

function renderLinks(data: ViewRecord, complete: boolean) {
  const items = viewLinks(data.items);
  if (!items.length) {
    return null;
  }
  return (
    <section className="rich-view rich-view-links">
      <div className="rich-view-links-header">
        {viewTitle(data, '相关链接')}
        {text(data.description) && <p className="rich-view-description">{text(data.description)}</p>}
      </div>
      <div className="rich-view-link-list">
        {items.map((item, index) => <RichContentLink item={item} key={`${item.title}-${index}`} />)}
        {!complete && (
          <div className="rich-view-streaming-row" role="status">
            <Spin size="small" />
            <span>正在整理下一条</span>
          </div>
        )}
      </div>
    </section>
  );
}

function RichContentLink({ item }: { item: ViewLink }) {
  const [previewOpen, setPreviewOpen] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [loading, setLoading] = useState(true);
  const previewable = isPreviewableUrl(item.url);
  const samePage = item.target === 'self';
  const content = (
    <>
      <span className="rich-view-link-icon"><LinkSimple size={15} weight="bold" /></span>
      <span className="rich-view-link-body">
        <strong>{item.title}</strong>
        {(item.description || item.source) && (
          <span className="rich-view-link-meta">
            {item.description && <small>{item.description}</small>}
            {item.source && <span>{item.source}</span>}
          </span>
        )}
      </span>
      {previewable ? <Eye size={16} weight="bold" /> : samePage ? <CaretRight size={16} weight="bold" /> : <ArrowSquareOut size={16} weight="bold" />}
    </>
  );

  if (!previewable) {
    return (
      <a
        className="rich-view-link-item"
        href={item.url}
        rel={samePage ? undefined : 'noreferrer'}
        target={samePage ? '_self' : '_blank'}
      >
        {content}
      </a>
    );
  }

  function openPreview(event?: MouseEvent<HTMLElement>) {
    event?.preventDefault();
    event?.stopPropagation();
    setLoading(true);
    setPreviewOpen(true);
  }

  function closePreview() {
    setPreviewOpen(false);
    setFullscreen(false);
  }

  const hoverActions = (
    <div className="rich-view-link-hover-actions">
      <button type="button" onClick={openPreview}>
        <Eye size={15} weight="bold" />
        在线查看
      </button>
      <a href={item.url} target="_blank" rel="noreferrer">
        <ArrowSquareOut size={15} weight="bold" />
        打开新页面
      </a>
    </div>
  );

  return (
    <>
      <Popover
        arrow={false}
        content={hoverActions}
        mouseEnterDelay={0.35}
        mouseLeaveDelay={0.15}
        placement="topRight"
        trigger="hover"
      >
        <button className="rich-view-link-item" type="button" onClick={openPreview}>
          {content}
        </button>
      </Popover>
      <Modal
        afterClose={() => setLoading(true)}
        centered={!fullscreen}
        className={`rich-link-preview-modal${fullscreen ? ' rich-link-preview-modal-fullscreen' : ''}`}
        destroyOnHidden
        footer={null}
        open={previewOpen}
        title={(
          <div className="rich-link-preview-title">
            <span>{item.title}</span>
            <div className="rich-link-preview-actions">
              <Tooltip title={fullscreen ? '恢复小窗' : '放大预览'}>
                <button type="button" aria-label={fullscreen ? '恢复小窗' : '放大预览'} onClick={() => setFullscreen((value) => !value)}>
                  {fullscreen ? <ArrowsInSimple size={17} weight="bold" /> : <ArrowsOutSimple size={17} weight="bold" />}
                </button>
              </Tooltip>
              <Tooltip title="打开原页面">
                <a aria-label="打开原页面" href={item.url} target="_blank" rel="noreferrer">
                  <ArrowSquareOut size={17} weight="bold" />
                </a>
              </Tooltip>
            </div>
          </div>
        )}
        width={fullscreen ? 'calc(100vw - 24px)' : 'min(900px, calc(100vw - 24px))'}
        onCancel={closePreview}
      >
        <div className="rich-link-preview-frame">
          {loading && <div className="rich-link-preview-loading"><Spin /></div>}
          <iframe
            allowFullScreen
            loading="eager"
            referrerPolicy="no-referrer"
            src={item.url}
            title={item.title}
            onLoad={() => setLoading(false)}
          />
        </div>
      </Modal>
    </>
  );
}

function renderTimeline(data: ViewRecord, renderContent: RichContentBlockProps['renderContent']) {
  const items = records(data.items).filter((item) => text(item.time) && text(item.title));
  if (!items.length) {
    return null;
  }
  return (
    <section className="rich-view rich-view-timeline">
      {viewTitle(data, '关键时间线')}
      <div className="rich-view-timeline-list">
        {items.map((item, index) => (
          <div className="rich-view-timeline-item" key={`${text(item.time)}-${text(item.title)}-${index}`}>
            <div className="rich-view-timeline-axis">
              <span className={`rich-view-status-dot ${statusClass(item.status)}`} />
            </div>
            <time>{text(item.time)}</time>
            <div className="rich-view-item-body">
              <strong>{text(item.title)}</strong>
              {text(item.content) && renderContent(text(item.content))}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function renderEvidence(data: ViewRecord, renderContent: RichContentBlockProps['renderContent']) {
  const items = records(data.items).filter((item) => text(item.title) && text(item.content));
  if (!items.length) {
    return null;
  }
  return (
    <section className="rich-view rich-view-evidence">
      {viewTitle(data, '结论与证据')}
      {text(data.conclusion) && <div className="rich-view-conclusion">{renderContent(text(data.conclusion))}</div>}
      <div className="rich-view-evidence-list">
        {items.map((item, index) => (
          <div className="rich-view-evidence-item" key={`${text(item.title)}-${index}`}>
            <div className="rich-view-evidence-head">
              <div>
                {text(item.type) && <span className="rich-view-kicker">{text(item.type)}</span>}
                <strong>{text(item.title)}</strong>
              </div>
              {statusLabel(item.status) && (
                <span className={`rich-view-status ${statusClass(item.status)}`}>{statusLabel(item.status)}</span>
              )}
            </div>
            <div className="rich-view-item-content">{renderContent(text(item.content))}</div>
            {text(item.source) && <div className="rich-view-source">来源：{text(item.source)}</div>}
          </div>
        ))}
      </div>
    </section>
  );
}

function renderSteps(data: ViewRecord, renderContent: RichContentBlockProps['renderContent']) {
  const items = records(data.items).filter((item) => text(item.title));
  if (!items.length) {
    return null;
  }
  return (
    <section className="rich-view rich-view-steps">
      {viewTitle(data, '执行步骤')}
      <ol className="rich-view-step-list">
        {items.map((item, index) => (
          <li key={`${text(item.title)}-${index}`}>
            <span className={`rich-view-step-index ${statusClass(item.status)}`}>{index + 1}</span>
            <div className="rich-view-item-body">
              <div className="rich-view-step-head">
                <strong>{text(item.title)}</strong>
                {statusLabel(item.status) && (
                  <span className={`rich-view-status ${statusClass(item.status)}`}>{statusLabel(item.status)}</span>
                )}
              </div>
              {text(item.content) && renderContent(text(item.content))}
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

function renderMetrics(data: ViewRecord) {
  const items = records(data.items).filter((item) => text(item.label) && text(item.value));
  if (!items.length) {
    return null;
  }
  return (
    <section className="rich-view rich-view-metrics">
      {viewTitle(data, '关键指标')}
      <div className="rich-view-metric-grid">
        {items.map((item, index) => (
          <div className="rich-view-metric" key={`${text(item.label)}-${index}`}>
            <span>{text(item.label)}</span>
            <strong>{text(item.value)}</strong>
            <div>
              {trend(item.trend)}
              {text(item.detail) && <small>{text(item.detail)}</small>}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

function viewTitle(data: ViewRecord, fallback: string) {
  return <h3 className="rich-view-title">{text(data.title) || fallback}</h3>;
}

function trend(value: unknown) {
  if (value === 'up') {
    return <span className="rich-view-trend rich-view-trend-up"><ArrowUp size={13} />上升</span>;
  }
  if (value === 'down') {
    return <span className="rich-view-trend rich-view-trend-down"><ArrowDown size={13} />下降</span>;
  }
  if (value === 'flat') {
    return <span className="rich-view-trend"><Equals size={13} />持平</span>;
  }
  return null;
}

function parseView(source: string): ParsedView | null {
  try {
    const value: unknown = JSON.parse(source);
    return record(value) && typeof value.type === 'string' ? { data: value, complete: true } : null;
  } catch {
    const type = partialStringProperty(source, 'type');
    if (!type) {
      return null;
    }
    const data: ViewRecord = { type };
    ['title', 'description', 'conclusion'].forEach((property) => {
      const value = partialStringProperty(source, property);
      if (value) {
        data[property] = value;
      }
    });
    data.items = partialObjectArrayProperty(source, 'items');
    return { data, complete: false };
  }
}

function partialStringProperty(source: string, property: string) {
  const start = propertyValueStart(source, property);
  if (start < 0 || source[start] !== '"') {
    return '';
  }
  const end = jsonStringEnd(source, start);
  if (end < 0) {
    return '';
  }
  try {
    const value: unknown = JSON.parse(source.slice(start, end + 1));
    return typeof value === 'string' ? value.trim() : '';
  } catch {
    return '';
  }
}

function partialObjectArrayProperty(source: string, property: string): ViewRecord[] {
  const start = propertyValueStart(source, property);
  if (start < 0 || source[start] !== '[') {
    return [];
  }
  const items: ViewRecord[] = [];
  let objectStart = -1;
  let objectDepth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start + 1; index < source.length; index++) {
    const character = source[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }
    if (character === '"') {
      inString = true;
      continue;
    }
    if (character === '{') {
      if (objectDepth === 0) {
        objectStart = index;
      }
      objectDepth++;
      continue;
    }
    if (character !== '}' || objectDepth === 0) {
      continue;
    }
    objectDepth--;
    if (objectDepth !== 0 || objectStart < 0) {
      continue;
    }
    try {
      const value: unknown = JSON.parse(source.slice(objectStart, index + 1));
      if (record(value)) {
        items.push(value);
      }
    } catch {
      // 只忽略尚未形成有效 JSON 的条目，后续流式片段到达后会重新解析。
    }
    objectStart = -1;
  }
  return items;
}

function propertyValueStart(source: string, property: string) {
  const containers: string[] = [];
  for (let index = 0; index < source.length; index++) {
    const character = source[index];
    if (character === '"') {
      const end = jsonStringEnd(source, index);
      if (end < 0) {
        return -1;
      }
      if (containers.length === 1 && containers[0] === '{') {
        try {
          const key: unknown = JSON.parse(source.slice(index, end + 1));
          let valueStart = end + 1;
          while (/\s/.test(source[valueStart] || '')) {
            valueStart++;
          }
          if (key === property && source[valueStart] === ':') {
            valueStart++;
            while (/\s/.test(source[valueStart] || '')) {
              valueStart++;
            }
            return valueStart;
          }
        } catch {
          return -1;
        }
      }
      index = end;
      continue;
    }
    if (character === '{' || character === '[') {
      containers.push(character);
    } else if (character === '}' || character === ']') {
      containers.pop();
    }
  }
  return -1;
}

function jsonStringEnd(source: string, start: number) {
  let escaped = false;
  for (let index = start + 1; index < source.length; index++) {
    const character = source[index];
    if (escaped) {
      escaped = false;
    } else if (character === '\\') {
      escaped = true;
    } else if (character === '"') {
      return index;
    }
  }
  return -1;
}

function records(value: unknown): ViewRecord[] {
  return Array.isArray(value) ? value.filter(record) : [];
}

function viewLinks(value: unknown): ViewLink[] {
  return records(value).map((item) => ({
    title: text(item.title),
    url: safeUrl(text(item.url) || text(item.href)),
    description: text(item.description),
    source: text(item.source),
    target: text(item.target),
  })).filter((item) => item.title && item.url);
}

function record(value: unknown): value is ViewRecord {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function safeUrl(value: string) {
  if (!value) {
    return '';
  }
  try {
    const url = new URL(value, window.location.origin);
    if (!['http:', 'https:', 'mailto:'].includes(url.protocol)) {
      return '';
    }
    return url.origin === window.location.origin && value.startsWith('/')
      ? `${url.pathname}${url.search}${url.hash}`
      : url.toString();
  } catch {
    return '';
  }
}

function isPreviewableUrl(value: string) {
  try {
    return ['http:', 'https:'].includes(new URL(value, window.location.origin).protocol);
  } catch {
    return false;
  }
}

function statusLabel(value: unknown) {
  return typeof value === 'string' ? STATUS_LABELS[value] || '' : '';
}

function statusClass(value: unknown) {
  return typeof value === 'string' && STATUS_LABELS[value] ? `rich-view-status-${value}` : 'rich-view-status-default';
}
