import { Tooltip } from 'antd';
import { AppTag } from './AppTag';

interface TaskModelTagProps {
  modelProviderName?: string;
  modelName?: string;
  modelIdentifier?: string;
}

export function TaskModelTag({ modelProviderName, modelName, modelIdentifier }: TaskModelTagProps) {
  const model = modelName || modelIdentifier;
  const label = [modelProviderName, model].filter(Boolean).join(' ') || '未记录';
  return (
    <Tooltip title={modelIdentifier || label}>
      <span className="task-model-tag-wrap">
        <AppTag tone="slate" className="task-model-tag">
          {modelProviderName && <span className="task-model-tag-segment">{modelProviderName}</span>}
          {model && <span className="task-model-tag-segment">{model}</span>}
          {!modelProviderName && !model && <span className="task-model-tag-segment">未记录</span>}
        </AppTag>
      </span>
    </Tooltip>
  );
}
