import React from 'react';
import { CodeBlock } from './CodeBlock';
import { MermaidBlock } from './MermaidBlock';
import { TaskArtifactLink } from './TaskArtifactLink';
import { isRichContentSource, RichContentBlock } from './RichContentBlock';
import { isJsonDataSource, JsonDataView } from './JsonDataView';
import { cleanGeneratedText } from '../utils/text';

interface MarkdownTextProps {
  content?: string;
  enhanced?: boolean;
}

export function MarkdownText({ content, enhanced = false }: MarkdownTextProps) {
  return <div className="markdown-text">{renderBlocks(content || '', enhanced)}</div>;
}

export function MarkdownInline({ content }: { content?: string }) {
  return <>{renderInline(content || '')}</>;
}

function renderBlocks(content: string, enhanced: boolean) {
  const cleaned = cleanGeneratedText(content);
  if (enhanced && isRichContentSource(cleaned)) {
    return [(
      <RichContentBlock
        key="rich-content"
        source={cleaned}
        renderContent={(value) => <MarkdownText content={value} />}
      />
    )];
  }
  if (isJsonDataSource(cleaned)) {
    return [<JsonDataView key="json-data" source={cleaned} />];
  }
  const lines = cleaned.split(/\n/);
  const blocks: JSX.Element[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) {
      index++;
      continue;
    }
    if (line.trim().startsWith('```')) {
      const code: string[] = [];
      const language = line.trim().slice(3).trim().split(/\s+/)[0];
      index++;
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        code.push(lines[index]);
        index++;
      }
      if (index < lines.length) {
        index++;
      }
      const source = code.join('\n');
      const normalizedLanguage = language.toLowerCase();
      blocks.push(normalizedLanguage === 'mermaid'
        ? <MermaidBlock key={blocks.length} source={source} />
        : normalizedLanguage === 'lingxi-view' && enhanced
          ? <RichContentBlock
              key={blocks.length}
              source={source}
              renderContent={(value) => <MarkdownText content={value} />}
            />
          : normalizedLanguage === 'json' && isJsonDataSource(source)
            ? <JsonDataView key={blocks.length} source={source} />
          : <CodeBlock key={blocks.length} code={source} language={language} />);
      continue;
    }
    if (/^#{1,4}\s+/.test(line)) {
      const level = line.match(/^#+/)?.[0].length || 1;
      const text = line.replace(/^#{1,4}\s+/, '');
      const Tag = (`h${Math.min(level, 4)}` as keyof JSX.IntrinsicElements);
      blocks.push(<Tag key={blocks.length}>{renderInline(text)}</Tag>);
      index++;
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\s*[-*]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*[-*]\s+/, ''));
        index++;
      }
      blocks.push(<ul key={blocks.length}>{items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}</ul>);
      continue;
    }
    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\s*\d+\.\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\s*\d+\.\s+/, ''));
        index++;
      }
      blocks.push(<ol key={blocks.length}>{items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}</ol>);
      continue;
    }
    if (isTableStart(lines, index)) {
      const tableLines: string[] = [];
      while (index < lines.length && lines[index].includes('|')) {
        tableLines.push(lines[index]);
        index++;
      }
      blocks.push(renderTable(tableLines, blocks.length));
      continue;
    }
    const paragraph: string[] = [];
    while (index < lines.length && lines[index].trim() && !isBlockStart(lines[index])) {
      paragraph.push(lines[index]);
      index++;
    }
    blocks.push(<p key={blocks.length}>{renderInline(paragraph.join('\n'))}</p>);
  }
  return blocks;
}

function isBlockStart(line: string) {
  return line.trim().startsWith('```')
    || /^#{1,4}\s+/.test(line)
    || /^\s*[-*]\s+/.test(line)
    || /^\s*\d+\.\s+/.test(line)
    || isTableStart([line, '---'], 0);
}

function isTableStart(lines: string[], index: number) {
  const header = splitTableCells(lines[index] || '');
  const separator = splitTableCells(lines[index + 1] || '');
  return header.length > 0
    && separator.length === header.length
    && separator.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function renderTable(lines: string[], key: number) {
  const rows = lines
    .filter((_, index) => index !== 1)
    .map(splitTableCells);
  const [headers, ...body] = rows;
  return (
    <div key={key} className="markdown-table-wrap">
      <table>
        <thead>
          <tr>{headers.map((header, index) => <th key={index}>{renderInline(header)}</th>)}</tr>
        </thead>
        <tbody>
          {body.map((row, rowIndex) => (
            <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}>{renderInline(cell)}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function splitTableCells(line: string) {
  const trimmed = line.trim();
  if (!trimmed.includes('|')) {
    return [];
  }
  return trimmed.replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim());
}

function renderInline(text: string) {
  const nodes: React.ReactNode[] = [];
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\([^)]+\))/g;
  let lastIndex = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }
    const token = match[0];
    if (token.startsWith('`')) {
      nodes.push(<code key={nodes.length}>{token.slice(1, -1)}</code>);
    } else if (token.startsWith('**')) {
      nodes.push(<strong key={nodes.length}>{token.slice(2, -2)}</strong>);
    } else {
      const link = token.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
      nodes.push(link
        ? <TaskArtifactLink href={link[2]} key={nodes.length}>{link[1]}</TaskArtifactLink>
        : token);
    }
    lastIndex = match.index + token.length;
  }
  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }
  return nodes;
}
