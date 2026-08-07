import { useState } from 'react';
import { Button, Dropdown, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import { CaretDown, DownloadSimple, FilePdf, MarkdownLogo } from '@phosphor-icons/react';
import html2canvas from 'html2canvas';
import { jsPDF } from 'jspdf';
import { createRoot } from 'react-dom/client';
import type { TaskItem, TaskRoundSummary } from '../types/api';
import { TaskExportDocument } from './TaskExportDocument';
import { APP_ENGLISH_NAME, APP_NAME } from '../constants/app';
import { displayTaskTitle, displayTaskType } from '../utils/format';
import { cleanGeneratedText } from '../utils/text';
import { useAuth } from '../context/AuthContext';
import { AI_DISCLAIMER_TEXT } from './AiDisclaimer';
import { getTask, listTaskRounds } from '../api/lingxi';
import { TaskRoundSelectionModal } from './TaskRoundSelectionModal';
import { useRuntimeModes } from '../hooks/useRuntimeModes';

interface TaskDownloadPdfButtonProps {
  task: TaskItem;
  disabled?: boolean;
  label?: string;
  size?: 'small' | 'middle' | 'large';
}

export function TaskDownloadPdfButton({ task, disabled, label = '下载', size = 'middle' }: TaskDownloadPdfButtonProps) {
  const { user } = useAuth();
  const runtimeModes = useRuntimeModes();
  const [downloading, setDownloading] = useState(false);
  const [exportFormat, setExportFormat] = useState<'md' | 'pdf'>('pdf');
  const [roundModalOpen, setRoundModalOpen] = useState(false);
  const [rounds, setRounds] = useState<TaskRoundSummary[]>([]);
  const [selectedRoundIds, setSelectedRoundIds] = useState<number[]>([]);
  const canDownload = !disabled && task.status === 'SUCCESS' && hasDownloadContent(task);
  const canDownloadMarkdown = user?.role === 'ADMIN';
  const items: MenuProps['items'] = [
    ...(canDownloadMarkdown ? [{
      key: 'md',
      icon: <MarkdownLogo size={16} />,
      label: 'Markdown',
    }] : []),
    { key: 'pdf', icon: <FilePdf size={16} />, label: 'PDF' },
  ];

  async function handleDownload({ key }: { key: string }) {
    if (!canDownload || downloading) {
      return;
    }
    const format = key === 'md' ? 'md' : 'pdf';
    setExportFormat(format);
    setRoundModalOpen(true);
    setDownloading(true);
    try {
      const result = await listTaskRounds(task.id);
      const availableRounds = mergeRoundList(result, task).filter((round) => round.status === 'SUCCESS');
      setRounds(availableRounds);
      setSelectedRoundIds((current) => {
        const availableIds = new Set(availableRounds.map((round) => round.id));
        const retained = current.filter((roundId) => availableIds.has(roundId));
        return retained.length ? retained : [availableRounds[availableRounds.length - 1]?.id].filter((id): id is number => id != null);
      });
    } finally {
      setDownloading(false);
    }
  }

  async function handleExport() {
    if (!selectedRoundIds.length || (exportFormat === 'md' && !canDownloadMarkdown)) {
      return;
    }
    setDownloading(true);
    try {
      const selectedSummaries = rounds.filter((round) => selectedRoundIds.includes(round.id));
      const selectedTasks = await Promise.all(selectedSummaries.map((round) => round.id === task.id ? task : getTask(round.id)));
      const title = displayTaskTitle(rounds[0]?.userInput || task.title, task.scenario, task.scenarioName);
      const documentName = taskDisplayName(task);
      const runtimeNames = Object.fromEntries(runtimeModes.map((runtime) => [runtime.code, runtime.name]));
      if (exportFormat === 'md') {
        downloadMarkdown(selectedTasks, title, documentName, runtimeNames);
      } else {
        await downloadPdf(selectedTasks, title, documentName, runtimeNames);
      }
      setRoundModalOpen(false);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <>
      <Tooltip title={canDownload ? `下载${taskDisplayName(task)}` : `${taskDisplayName(task)}生成成功后可下载`}>
        <Dropdown
          disabled={!canDownload || downloading}
          menu={{ items, onClick: handleDownload }}
          trigger={['click']}
        >
          <Button size={size} disabled={!canDownload} loading={downloading} icon={!downloading ? <DownloadSimple size={16} /> : undefined}>
            <span className="inline-flex items-center gap-1">
              {label}
              <CaretDown size={13} />
            </span>
          </Button>
        </Dropdown>
      </Tooltip>
      <TaskRoundSelectionModal
        title="选择导出轮次"
        open={roundModalOpen}
        rounds={rounds}
        selectedRoundIds={selectedRoundIds}
        loading={downloading}
        okText={`导出 ${exportFormat === 'pdf' ? 'PDF' : 'Markdown'}`}
        onChange={setSelectedRoundIds}
        onCancel={() => setRoundModalOpen(false)}
        onOk={handleExport}
      />
    </>
  );
}

function downloadMarkdown(rounds: TaskItem[], title: string, documentName: string, runtimeNames: Record<string, string>) {
  const content = [
    `# ${APP_NAME}`,
    APP_ENGLISH_NAME,
    '',
    `## ${title}`,
    '',
    `- 文档类型：${documentName}`,
    '',
    `- 导出轮次：${rounds.map((round) => `第 ${round.roundNo || 1} 轮`).join('、')}`,
    '',
    ...rounds.flatMap((round) => [
      `## 第 ${round.roundNo || 1} 轮`,
      '',
      `- Runtime：${runtimeNames[round.runtimeCode] || round.runtimeCode || '未记录'}`,
      `- 模型：${round.modelName || round.modelIdentifier || '未记录'}`,
      '',
      `### 问题`,
      '',
      round.userInput || '-',
      '',
      `### 回答`,
      '',
      taskContent(round),
      '',
    ]),
    AI_DISCLAIMER_TEXT,
  ].join('\n');
  downloadBlob(new Blob([content], { type: 'text/markdown;charset=utf-8' }), `${safeFileName(title || documentName)}.md`);
}

async function downloadPdf(rounds: TaskItem[], title: string, reportName: string, runtimeNames: Record<string, string>) {
  const { report, cleanup } = await renderExportReport(rounds, title, reportName, runtimeNames);
  try {
    const watermarkTile = await createWatermarkTile();
    const canvas = await html2canvas(report, {
      backgroundColor: '#ffffff',
      scale: Math.min(2, window.devicePixelRatio || 1),
      useCORS: true,
    });
    const pdf = new jsPDF('p', 'mm', 'a4');
    addCanvasPagesToPdf(pdf, canvas, watermarkTile);

    pdf.save(`${safeFileName(title || reportName)}.pdf`);
  } finally {
    cleanup();
  }
}

async function renderExportReport(rounds: TaskItem[], title: string, reportName: string, runtimeNames: Record<string, string>) {
  const container = document.createElement('div');
  container.style.position = 'absolute';
  container.style.left = '-10000px';
  container.style.top = '0';
  container.style.width = '1000px';
  container.style.background = '#ffffff';
  container.style.padding = '32px';
  container.className = 'export-report-surface';
  document.body.appendChild(container);
  const root = createRoot(container);
  root.render(<TaskExportDocument rounds={rounds} title={title} documentName={reportName} runtimeNames={runtimeNames} />);
  await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  await waitForExportContent(container);
  const report = container.querySelector<HTMLElement>('[data-task-export]') || container;
  return {
    report,
    cleanup: () => {
      root.unmount();
      document.body.removeChild(container);
    },
  };
}

function addCanvasPagesToPdf(
  pdf: jsPDF,
  canvas: HTMLCanvasElement,
  watermarkTile?: { dataUrl: string; width: number; height: number },
) {
  const pageWidth = pdf.internal.pageSize.getWidth();
  const pageHeight = pdf.internal.pageSize.getHeight();
  const margin = 12;
  const imageWidth = pageWidth - margin * 2;
  const pageContentHeight = pageHeight - margin * 2;
  const maxSliceHeight = Math.floor(canvas.width * pageContentHeight / imageWidth);
  const sourceContext = canvas.getContext('2d', { willReadFrequently: true });
  let sourceY = 0;
  let pageIndex = 0;

  while (sourceY < canvas.height) {
    const targetY = Math.min(canvas.height, sourceY + maxSliceHeight);
    const endY = targetY < canvas.height && sourceContext
      ? findCanvasPageBreak(sourceContext, canvas.width, sourceY, targetY)
      : targetY;
    const sliceHeight = Math.max(1, endY - sourceY);
    const pageCanvas = document.createElement('canvas');
    pageCanvas.width = canvas.width;
    pageCanvas.height = sliceHeight;
    const pageContext = pageCanvas.getContext('2d');
    if (!pageContext) {
      throw new Error('无法创建 PDF 分页画布');
    }
    pageContext.fillStyle = '#ffffff';
    pageContext.fillRect(0, 0, pageCanvas.width, pageCanvas.height);
    pageContext.drawImage(canvas, 0, sourceY, canvas.width, sliceHeight, 0, 0, canvas.width, sliceHeight);

    if (pageIndex > 0) {
      pdf.addPage();
    }
    const imageHeight = sliceHeight * imageWidth / canvas.width;
    pdf.addImage(pageCanvas.toDataURL('image/jpeg', 0.9), 'JPEG', margin, margin, imageWidth, imageHeight);
    drawPdfWatermark(pdf, pageWidth, pageHeight, watermarkTile);
    sourceY = endY;
    pageIndex += 1;
  }
}

function findCanvasPageBreak(
  context: CanvasRenderingContext2D,
  canvasWidth: number,
  sourceY: number,
  targetY: number,
) {
  const searchDepth = Math.min(targetY - sourceY, Math.max(100, Math.round(canvasWidth * 0.16)));
  const searchStart = Math.max(sourceY + Math.round((targetY - sourceY) * 0.68), targetY - searchDepth);
  const image = context.getImageData(0, searchStart, canvasWidth, targetY - searchStart);
  const sampleStep = 4;
  const maximumInkSamples = Math.max(2, Math.floor((canvasWidth / sampleStep) * 0.002));
  const requiredClearRows = Math.max(4, Math.round(canvasWidth / 250));
  let clearRows = 0;

  for (let y = image.height - 1; y >= 0; y -= 1) {
    let inkSamples = 0;
    for (let x = 0; x < canvasWidth; x += sampleStep) {
      const offset = (y * canvasWidth + x) * 4;
      if (image.data[offset + 3] > 0
        && (image.data[offset] < 225 || image.data[offset + 1] < 225 || image.data[offset + 2] < 225)) {
        inkSamples += 1;
        if (inkSamples > maximumInkSamples) {
          break;
        }
      }
    }
    clearRows = inkSamples <= maximumInkSamples ? clearRows + 1 : 0;
    if (clearRows >= requiredClearRows) {
      return searchStart + y + Math.floor(requiredClearRows / 2);
    }
  }
  return targetY;
}

async function waitForExportContent(container: HTMLElement) {
  const timeoutAt = Date.now() + 10000;
  while (container.querySelector('[data-export-pending="true"]') && Date.now() < timeoutAt) {
    await new Promise((resolve) => window.setTimeout(resolve, 50));
  }
}

async function createWatermarkTile() {
  const canvas = document.createElement('canvas');
  const scale = 2;
  const width = 260;
  const height = 140;
  canvas.width = width * scale;
  canvas.height = height * scale;
  const context = canvas.getContext('2d');
  if (!context) {
    return undefined;
  }
  context.scale(scale, scale);
  context.translate(width / 2, height / 2);
  context.rotate(-24 * Math.PI / 180);
  context.globalAlpha = 0.22;
  const logo = await loadImage('/app-logo.png').catch(() => undefined);
  if (logo) {
    context.drawImage(logo, -78, -12, 20, 20);
  }
  context.fillStyle = '#737373';
  context.font = '700 15px "PingFang SC", "Microsoft YaHei", "Noto Sans CJK SC", sans-serif';
  context.textBaseline = 'middle';
  context.fillText(APP_NAME, -50, -6);
  context.globalAlpha = 0.16;
  context.font = '500 9px Arial, sans-serif';
  context.fillText(APP_ENGLISH_NAME, -50, 10);
  return {
    dataUrl: canvas.toDataURL('image/png'),
    width: 72,
    height: 39,
  };
}

function drawPdfWatermark(pdf: jsPDF, pageWidth: number, pageHeight: number, tile?: { dataUrl: string; width: number; height: number }) {
  if (!tile) {
    return;
  }
  pdf.saveGraphicsState();
  const stepX = 78;
  const stepY = 48;
  let row = 0;
  for (let y = -stepY; y < pageHeight + stepY * 2; y += stepY) {
    const rowOffset = row % 2 === 0 ? -28 : 12;
    let column = 0;
    for (let x = -stepX * 2 + rowOffset; x < pageWidth + stepX * 2; x += stepX) {
      const jitterX = (column % 3 - 1) * 4;
      const jitterY = ((row + column) % 5 - 2) * 1.8;
      pdf.addImage(tile.dataUrl, 'PNG', x + jitterX, y + jitterY, tile.width, tile.height);
      column += 1;
    }
    row += 1;
  }
  pdf.restoreGraphicsState();
}

function loadImage(src: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = reject;
    image.src = src;
  });
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function safeFileName(name: string) {
  return name.replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim().slice(0, 80) || '报告';
}

function taskDisplayName(task: TaskItem) {
  return displayTaskType(task.scenario, task.scenarioName);
}

function hasDownloadContent(task: TaskItem) {
  return Boolean(taskContent(task).trim());
}

function taskContent(task: TaskItem) {
  if (task.resultData?.markdown || task.resultText) {
    return cleanGeneratedText(task.resultData?.markdown || task.resultText);
  }
  return cleanGeneratedText((task.resultData?.sections || [])
    .filter((section) => section.title || section.content)
    .map((section) => [`## ${section.title || '结果'}`, section.content].filter(Boolean).join('\n\n'))
    .join('\n\n'));
}

function mergeRoundList(rounds: TaskRoundSummary[], currentTask: TaskItem) {
  const map = new Map<number, TaskRoundSummary>();
  rounds.forEach((round) => map.set(round.id, round));
  map.set(currentTask.id, {
    id: currentTask.id,
    roundNo: currentTask.roundNo,
    userInput: currentTask.userInput,
    status: currentTask.status,
    startedAt: currentTask.startedAt,
    endedAt: currentTask.endedAt,
    createdAt: currentTask.createdAt,
    updatedAt: currentTask.updatedAt,
  });
  return Array.from(map.values()).sort((left, right) => (left.roundNo || 1) - (right.roundNo || 1) || left.id - right.id);
}
