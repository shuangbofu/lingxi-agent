import { useState } from 'react';
import type { ReactNode } from 'react';
import { CodeBlock } from './CodeBlock';

const JSON_TABLE_ROW_LIMIT = 200;
const JSON_TABLE_COLUMN_LIMIT = 20;

type JsonTableData = {
  kind: 'object';
  rows: [string, unknown][];
  totalRows: number;
} | {
  kind: 'array';
  columns: string[];
  rows: Record<string, unknown>[];
  totalRows: number;
};

export function JsonDataView({ source, title, action }: { source: string; title?: string; action?: ReactNode }) {
  const [view, setView] = useState<'table' | 'json'>('table');
  const table = parseJsonTable(source);
  const viewControl = table ? (
    <div className="json-data-view-control" role="group" aria-label="JSON 展示方式">
      <button type="button" aria-pressed={view === 'table'} onClick={() => setView('table')}>表格</button>
      <button type="button" aria-pressed={view === 'json'} onClick={() => setView('json')}>JSON</button>
    </div>
  ) : undefined;

  if (!table || view === 'json') {
    return (
      <CodeBlock
        code={formatJson(source)}
        language="json"
        title={title}
        extra={<>{action}{viewControl}</>}
      />
    );
  }

  return (
    <div className="markdown-code json-data-result">
      <div className="syntax-code-head">
        <span className="syntax-code-title">
          <span>{title || '执行结果'}</span>
          <span className="syntax-code-language">JSON</span>
        </span>
        <div className="syntax-code-actions">{action}{viewControl}</div>
      </div>
      <div className="json-data-table-scroll">
        {table.kind === 'array' ? (
          <table className="json-data-table" aria-label="JSON 数据表格">
            <thead>
              <tr>
                <th className="json-data-row-number" scope="col">#</th>
                {table.columns.map((column) => <th scope="col" key={column}>{column}</th>)}
              </tr>
            </thead>
            <tbody>
              {table.rows.map((row, rowIndex) => (
                <tr key={rowIndex}>
                  <td className="json-data-row-number">{rowIndex + 1}</td>
                  {table.columns.map((column) => <td key={column}>{jsonTableValue(row[column])}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <table className="json-data-table json-data-object-table" aria-label="JSON 对象表格">
            <thead><tr><th scope="col">字段</th><th scope="col">值</th></tr></thead>
            <tbody>
              {table.rows.map(([name, value]) => (
                <tr key={name}><th scope="row">{name}</th><td>{jsonTableValue(value)}</td></tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {table.totalRows > table.rows.length && (
        <div className="json-data-table-note">
          表格展示前 {table.rows.length} 行，共 {table.totalRows} 行；切换到 JSON 可查看完整内容
        </div>
      )}
    </div>
  );
}

export function isJsonDataSource(source: string) {
  const value = source.trim();
  if (!((value.startsWith('{') && value.endsWith('}')) || (value.startsWith('[') && value.endsWith(']')))) {
    return false;
  }
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}

function parseJsonTable(source: string): JsonTableData | undefined {
  try {
    const value: unknown = JSON.parse(source);
    if (Array.isArray(value) && value.length && value.every(isJsonRecord)) {
      const columns = Object.keys(value[0]);
      if (!columns.length || columns.length > JSON_TABLE_COLUMN_LIMIT) {
        return undefined;
      }
      const columnSet = new Set(columns);
      if (!value.every((row) => {
        const keys = Object.keys(row);
        return keys.length === columns.length && keys.every((key) => columnSet.has(key));
      })) {
        return undefined;
      }
      return {
        kind: 'array',
        columns,
        rows: value.slice(0, JSON_TABLE_ROW_LIMIT),
        totalRows: value.length,
      };
    }
    if (isJsonRecord(value)) {
      const rows = Object.entries(value);
      return rows.length ? { kind: 'object', rows: rows.slice(0, JSON_TABLE_ROW_LIMIT), totalRows: rows.length } : undefined;
    }
    return undefined;
  } catch {
    return undefined;
  }
}

function isJsonRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && !Array.isArray(value) && typeof value === 'object';
}

function jsonTableValue(value: unknown) {
  if (value === null) {
    return <span className="json-data-null">null</span>;
  }
  if (typeof value === 'boolean') {
    return <span className="json-data-boolean">{String(value)}</span>;
  }
  if (typeof value === 'number') {
    return <span className="json-data-number">{String(value)}</span>;
  }
  if (typeof value === 'string') {
    return value || <span className="json-data-empty">空字符串</span>;
  }
  return <code className="json-data-nested">{JSON.stringify(value)}</code>;
}

function formatJson(source: string) {
  try {
    return JSON.stringify(JSON.parse(source), null, 2);
  } catch {
    return source;
  }
}
