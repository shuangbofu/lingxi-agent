import { useEffect, useRef, useState } from 'react';
import { ArrowClockwise, ArrowsOutSimple, Check, CopySimple, DownloadSimple, Minus, Plus } from '@phosphor-icons/react';
import { Tooltip } from 'antd';
import type { CSSProperties } from 'react';
import { CodeBlock } from './CodeBlock';
import { ContentFullscreenModal } from './ContentFullscreenModal';
import { useThemeMode } from '../context/ThemeContext';
import { copyText } from '../utils/clipboard';

let diagramSequence = 0;
const MIN_ZOOM = 0.5;
const MAX_ZOOM = 2.5;
const ZOOM_STEP = 0.25;
const DEFAULT_ZOOM = 0.75;

export function MermaidBlock({ source }: { source: string }) {
  const { mode } = useThemeMode();
  const surfaceRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const zoomRef = useRef(DEFAULT_ZOOM);
  const diagramId = useRef(`lingxi-mermaid-${++diagramSequence}`).current;
  const [svg, setSvg] = useState('');
  const [failed, setFailed] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [zoom, setZoom] = useState(DEFAULT_ZOOM);
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle');

  useEffect(() => {
    let active = true;
    setSvg('');
    setFailed(false);
    import('mermaid')
      .then(async ({ default: mermaid }) => {
        const styles = getComputedStyle(surfaceRef.current || document.documentElement);
        const themeColor = (name: string) => styles.getPropertyValue(name).trim();
        const darkMode = styles.colorScheme === 'dark';
        mermaid.initialize({
          startOnLoad: false,
          securityLevel: 'strict',
          suppressErrorRendering: true,
          theme: 'base',
          themeVariables: {
            background: themeColor('--wb-surface'),
            primaryColor: themeColor('--wb-surface-muted'),
            primaryTextColor: themeColor('--wb-text'),
            primaryBorderColor: themeColor('--wb-border-strong'),
            secondaryColor: themeColor('--wb-surface-subtle'),
            tertiaryColor: themeColor('--wb-surface'),
            lineColor: themeColor('--wb-text-muted'),
            textColor: themeColor('--wb-text'),
            clusterBkg: themeColor('--wb-surface-subtle'),
            clusterBorder: themeColor('--wb-border'),
            darkMode,
          },
        });
        const result = await mermaid.render(diagramId, source);
        if (active) {
          setSvg(result.svg);
        }
      })
      .catch(() => {
        if (active) {
          setFailed(true);
        }
      });
    return () => {
      active = false;
    };
  }, [diagramId, mode, source]);

  useEffect(() => {
    if (copyState === 'idle') {
      return;
    }
    const timer = window.setTimeout(() => setCopyState('idle'), 1600);
    return () => window.clearTimeout(timer);
  }, [copyState]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !svg) {
      return;
    }

    const handleWheel = (event: WheelEvent) => {
      if (event.deltaY === 0) {
        return;
      }

      const deltaPixels = event.deltaMode === 1
        ? event.deltaY * 16
        : event.deltaMode === 2 ? event.deltaY * canvas.clientHeight : event.deltaY;
      const currentZoom = zoomRef.current;
      const nextZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(currentZoom * Math.exp(-deltaPixels * 0.0015) * 1000) / 1000));
      if (nextZoom === currentZoom) {
        return;
      }

      event.preventDefault();
      const content = canvas.querySelector<HTMLElement>('.mermaid-diagram-content');
      const contentRect = content?.getBoundingClientRect();
      const horizontalRatio = contentRect?.width
        ? (event.clientX - contentRect.left) / contentRect.width
        : 0.5;
      const verticalRatio = contentRect?.height
        ? (event.clientY - contentRect.top) / contentRect.height
        : 0.5;

      zoomRef.current = nextZoom;
      setZoom(nextZoom);
      requestAnimationFrame(() => {
        const nextContent = canvas.querySelector<HTMLElement>('.mermaid-diagram-content');
        if (!nextContent || !canvas.isConnected) {
          return;
        }
        const nextRect = nextContent.getBoundingClientRect();
        canvas.scrollLeft += nextRect.left + nextRect.width * horizontalRatio - event.clientX;
        canvas.scrollTop += nextRect.top + nextRect.height * verticalRatio - event.clientY;
      });
    };

    canvas.addEventListener('wheel', handleWheel, { passive: false });
    return () => canvas.removeEventListener('wheel', handleWheel);
  }, [fullscreen, svg]);

  async function handleCopy() {
    setCopyState(await copyText(source) ? 'copied' : 'failed');
  }

  function handleDownload() {
    const blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'lingxi-mermaid.svg';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function changeZoom(nextZoom: number) {
    const clampedZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, nextZoom));
    zoomRef.current = clampedZoom;
    setZoom(clampedZoom);
  }

  function renderDiagramSurface(expanded: boolean) {
    const diagramStyle = { '--mermaid-zoom-width': `${zoom * 100}%` } as CSSProperties;
    return (
      <div className="mermaid-diagram" ref={surfaceRef} data-export-pending={!svg && !failed ? 'true' : undefined}>
        <div className="syntax-code-head">
          <span>MERMAID</span>
          <div className="syntax-code-actions">
            <Tooltip title={copyState === 'copied' ? '已复制' : copyState === 'failed' ? '复制失败' : '复制源码'}>
              <button className="syntax-code-tool" type="button" aria-label="复制流程图源码" onClick={handleCopy}>
                {copyState === 'copied' ? <Check size={15} weight="bold" /> : <CopySimple size={15} />}
              </button>
            </Tooltip>
            <Tooltip title="下载 SVG">
              <button className="syntax-code-tool" type="button" aria-label="下载流程图 SVG" onClick={handleDownload}>
                <DownloadSimple size={15} weight="bold" />
              </button>
            </Tooltip>
            <Tooltip title="缩小">
              <button className="syntax-code-tool" type="button" aria-label="缩小流程图" disabled={zoom <= MIN_ZOOM} onClick={() => changeZoom(zoom - ZOOM_STEP)}>
                <Minus size={15} weight="bold" />
              </button>
            </Tooltip>
            <Tooltip title={`${Math.round(zoom * 100)}%`}>
              <button className="syntax-code-tool" type="button" aria-label="重置流程图缩放" disabled={zoom === DEFAULT_ZOOM} onClick={() => changeZoom(DEFAULT_ZOOM)}>
                <ArrowClockwise size={15} weight="bold" />
              </button>
            </Tooltip>
            <Tooltip title="放大">
              <button className="syntax-code-tool" type="button" aria-label="放大流程图" disabled={zoom >= MAX_ZOOM} onClick={() => changeZoom(zoom + ZOOM_STEP)}>
                <Plus size={15} weight="bold" />
              </button>
            </Tooltip>
            {!expanded && (
              <Tooltip title="全屏查看">
                <button className="syntax-code-tool" type="button" aria-label="全屏查看流程图" onClick={() => setFullscreen(true)}>
                  <ArrowsOutSimple size={15} weight="bold" />
                </button>
              </Tooltip>
            )}
          </div>
        </div>
        <div className="mermaid-diagram-canvas" ref={canvasRef}>
          <div className="mermaid-diagram-content" style={diagramStyle} dangerouslySetInnerHTML={{ __html: svg }} />
        </div>
      </div>
    );
  }

  if (failed) {
    return <CodeBlock code={source} language="mermaid" />;
  }
  if (!svg) {
    return <div className="mermaid-diagram mermaid-diagram-loading" aria-label="流程图加载中" />;
  }
  return (
    <>
      {!fullscreen && renderDiagramSurface(false)}
      <ContentFullscreenModal open={fullscreen} title="流程图" onClose={() => setFullscreen(false)}>
        {fullscreen && renderDiagramSurface(true)}
      </ContentFullscreenModal>
    </>
  );
}
