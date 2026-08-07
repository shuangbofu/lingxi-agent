import { Tooltip } from 'antd';
import type { AgentRuntimeDescriptor } from '../types/api';
import { AppTag } from './AppTag';

export function RuntimeModeTag({ runtimeCode, runtimeModes }: {
  runtimeCode?: string;
  runtimeModes: AgentRuntimeDescriptor[];
}) {
  const normalizedCode = runtimeCode?.trim();
  const runtime = runtimeModes.find((item) => item.code.trim() === normalizedCode);
  const label = runtime?.name?.trim() || (!normalizedCode ? '未记录' : runtimeModes.length ? '未知模式' : '加载中');
  const description = (runtime?.available ? runtime.description : runtime?.unavailableReason || runtime?.description)?.trim();
  const tag = <span className="runtime-mode-tag-wrap"><AppTag tone="slate" className="runtime-mode-tag">{label}</AppTag></span>;
  return description ? <Tooltip title={description}>{tag}</Tooltip> : tag;
}
