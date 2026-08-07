import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { ArrowsOutSimple, Check, CopySimple } from '@phosphor-icons/react';
import { Tooltip } from 'antd';
import { copyText } from '../utils/clipboard';
import { ContentFullscreenModal } from './ContentFullscreenModal';

interface CodeBlockProps {
  code: string;
  language?: string;
  title?: string;
  extra?: ReactNode;
}

export type CodeLanguage = 'json' | 'sql' | 'yaml';

let highlighterPromise: ReturnType<typeof importHighlighter> | undefined;

export function CodeBlock({ code, language, title, extra }: CodeBlockProps) {
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');
  const [fullscreen, setFullscreen] = useState(false);
  const declaredLanguage = normalizeLanguage(language);
  const [highlighted, setHighlighted] = useState<{ html: string; language?: string }>({ html: '', language: declaredLanguage });
  const label = (declaredLanguage || highlighted.language || 'text').toUpperCase();

  useEffect(() => {
    let active = true;
    setHighlighted({ html: '', language: declaredLanguage });
    loadHighlighter().then((highlighter) => {
      if (active) {
        setHighlighted(highlightSource(code, declaredLanguage, highlighter));
      }
    }).catch(() => {
      if (active) {
        setHighlighted({ html: '', language: declaredLanguage });
      }
    });
    return () => {
      active = false;
    };
  }, [code, declaredLanguage]);

  useEffect(() => {
    if (copyState === 'idle') {
      return;
    }
    const timer = window.setTimeout(() => setCopyState('idle'), 1600);
    return () => window.clearTimeout(timer);
  }, [copyState]);

  async function handleCopy() {
    setCopyState(await copyText(code) ? 'copied' : 'failed');
  }

  function renderCodeSurface(expanded: boolean) {
    return (
      <div className={`markdown-code syntax-code${declaredLanguage ? ` language-${declaredLanguage}` : ''}`}>
        <div className="syntax-code-head">
          <span className="syntax-code-title">
            <span>{title || label}</span>
            {title && <span className="syntax-code-language">{label}</span>}
          </span>
          <div className="syntax-code-actions">
            {!expanded && extra}
            <Tooltip title={copyState === 'copied' ? '已复制' : copyState === 'failed' ? '复制失败' : '复制'}>
              <button className="syntax-code-tool" type="button" aria-label="复制代码" onClick={handleCopy}>
                {copyState === 'copied' ? <Check size={15} weight="bold" /> : <CopySimple size={15} />}
              </button>
            </Tooltip>
            {!expanded && (
              <Tooltip title="全屏查看">
                <button className="syntax-code-tool" type="button" aria-label="全屏查看代码" onClick={() => setFullscreen(true)}>
                  <ArrowsOutSimple size={15} weight="bold" />
                </button>
              </Tooltip>
            )}
          </div>
        </div>
        <pre className="syntax-code-scroll">
          {highlighted.html
            ? <code className="hljs" dangerouslySetInnerHTML={{ __html: highlighted.html }} />
            : <code>{code}</code>}
        </pre>
      </div>
    );
  }

  return (
    <>
      {!fullscreen && renderCodeSurface(false)}
      <ContentFullscreenModal open={fullscreen} title={title || `${label} 代码`} onClose={() => setFullscreen(false)}>
        {fullscreen && renderCodeSurface(true)}
      </ContentFullscreenModal>
    </>
  );
}

function normalizeLanguage(language?: string) {
  const value = language?.trim().toLowerCase();
  if (!value) {
    return undefined;
  }
  const aliases: Record<string, string> = {
    html: 'xml',
    js: 'javascript',
    jsx: 'javascript',
    kt: 'kotlin',
    kts: 'kotlin',
    py: 'python',
    sh: 'shell',
    ts: 'typescript',
    tsx: 'typescript',
    yml: 'yaml',
  };
  const normalized = aliases[value] || value.replace(/[^a-z0-9_+#.-]/g, '');
  return normalized;
}

function importHighlighter() {
  return import('highlight.js/lib/common').then(({ default: highlighter }) => highlighter);
}

function loadHighlighter() {
  highlighterPromise ||= importHighlighter().catch((error) => {
    highlighterPromise = undefined;
    throw error;
  });
  return highlighterPromise;
}

function highlightSource(code: string, language: string | undefined, highlighter: Awaited<ReturnType<typeof importHighlighter>>) {
  try {
    if (language && highlighter.getLanguage(language)) {
      const result = highlighter.highlight(code, { language, ignoreIllegals: true });
      return { html: result.value, language };
    }
    const result = highlighter.highlightAuto(code);
    return { html: result.value, language: result.language };
  } catch {
    return { html: '', language };
  }
}

export function detectCodeLanguage(code: string): CodeLanguage | undefined {
  const value = code.trim();
  if (!value) {
    return undefined;
  }
  if ((value.startsWith('{') && value.endsWith('}')) || (value.startsWith('[') && value.endsWith(']'))) {
    try {
      JSON.parse(value);
      return 'json';
    } catch {
      return undefined;
    }
  }
  if (/^\s*(select|with|insert|update|delete|create|alter|drop)\b/i.test(value)) {
    return 'sql';
  }
  if (/^\s*[\w.-]+\s*:\s*.+/m.test(value)) {
    return 'yaml';
  }
  return undefined;
}
