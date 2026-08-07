import { useMemo, useState } from 'react';
import type { MouseEvent, ReactNode } from 'react';
import { Modal, Skeleton } from 'antd';
import { getTaskArtifact } from '../api/lingxi';
import { CodeBlock } from './CodeBlock';

interface ArtifactTarget {
  taskId: number;
  path: string;
  filename: string;
  language?: string;
}

export function TaskArtifactLink({ href, children }: { href: string; children: ReactNode }) {
  const target = useMemo(() => parseArtifactTarget(href), [href]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [content, setContent] = useState<string>();

  if (!target) {
    return <a href={href} target="_blank" rel="noreferrer">{children}</a>;
  }
  const artifact = target;

  async function handleOpen(event: MouseEvent<HTMLAnchorElement>) {
    event.preventDefault();
    setOpen(true);
    if (content !== undefined || loading) {
      return;
    }
    setLoading(true);
    try {
      const bytes = await getTaskArtifact(artifact.taskId, artifact.path);
      setContent(decodeText(bytes));
    } finally {
      setLoading(false);
    }
  }

  return (
    <>
      <a href={href} target="_blank" rel="noreferrer" onClick={handleOpen}>{children}</a>
      <Modal
        className="artifact-preview-modal"
        footer={null}
        open={open}
        title={artifact.filename}
        width="min(960px, calc(100vw - 24px))"
        destroyOnHidden
        onCancel={() => setOpen(false)}
      >
        {loading
          ? <Skeleton active paragraph={{ rows: 10 }} title={false} />
          : <CodeBlock code={content || ''} language={artifact.language} />}
      </Modal>
    </>
  );
}

function parseArtifactTarget(href: string): ArtifactTarget | undefined {
  try {
    const url = new URL(href, window.location.origin);
    const match = url.pathname.match(/^\/api\/agent\/tasks\/(\d+)\/artifacts$/);
    const path = url.searchParams.get('path');
    if (url.origin !== window.location.origin || !match || !path || !isTextFile(path)) {
      return undefined;
    }
    const filename = path.split('/').filter(Boolean).pop() || '文本附件';
    return {
      taskId: Number(match[1]),
      path,
      filename,
      language: languageFromFilename(filename),
    };
  } catch {
    return undefined;
  }
}

function isTextFile(path: string) {
  return /\.(?:c|cc|conf|cpp|cs|css|csv|env|go|gradle|groovy|h|hpp|html|ini|java|js|json|jsx|kt|kts|less|log|md|php|properties|py|rb|rs|scss|sh|sql|text|toml|ts|tsx|txt|vue|xml|yaml|yml|zsh)$/i.test(path);
}

function languageFromFilename(filename: string) {
  const extension = filename.split('.').pop()?.toLowerCase();
  const languages: Record<string, string> = {
    cc: 'cpp',
    h: 'c',
    hpp: 'cpp',
    js: 'javascript',
    jsx: 'javascript',
    kt: 'kotlin',
    kts: 'kotlin',
    py: 'python',
    sh: 'shell',
    text: 'text',
    ts: 'typescript',
    tsx: 'typescript',
    yml: 'yaml',
  };
  return extension ? languages[extension] || extension : undefined;
}

function decodeText(buffer: ArrayBuffer) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer);
  } catch {
    return new TextDecoder('gb18030').decode(buffer);
  }
}
