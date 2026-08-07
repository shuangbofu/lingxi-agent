import { Checkbox, Modal } from 'antd';
import type { TaskRoundSummary } from '../types/api';
import '../styles/task-sharing.css';
import { formatTime } from '../utils/format';

interface TaskRoundSelectionModalProps {
  title: string;
  open: boolean;
  rounds: TaskRoundSummary[];
  selectedRoundIds: number[];
  loading?: boolean;
  okText: string;
  onChange: (roundIds: number[]) => void;
  onCancel: () => void;
  onOk: () => void;
}

export function TaskRoundSelectionModal({
  title,
  open,
  rounds,
  selectedRoundIds,
  loading,
  okText,
  onChange,
  onCancel,
  onOk,
}: TaskRoundSelectionModalProps) {
  return (
    <Modal
      title={title}
      open={open}
      onCancel={onCancel}
      onOk={onOk}
      okText={okText}
      cancelText="取消"
      confirmLoading={loading}
      okButtonProps={{ disabled: selectedRoundIds.length === 0 }}
      destroyOnClose
    >
      <div className="task-round-selection-toolbar">
        <Checkbox
          checked={rounds.length > 0 && selectedRoundIds.length === rounds.length}
          indeterminate={selectedRoundIds.length > 0 && selectedRoundIds.length < rounds.length}
          onChange={(event) => onChange(event.target.checked ? rounds.map((round) => round.id) : [])}
        >
          全选
        </Checkbox>
        <span>已选 {selectedRoundIds.length} 轮</span>
      </div>
      <Checkbox.Group
        className="task-round-selection-list"
        value={selectedRoundIds}
        onChange={(values) => onChange(values.map(Number))}
      >
        {rounds.map((round) => (
          <Checkbox className="task-round-selection-option" value={round.id} key={round.id}>
            <span className="task-round-selection-title">第 {round.roundNo || 1} 轮</span>
            <span className="task-round-selection-question">{round.userInput || '-'}</span>
            <span className="task-round-selection-time">{formatTime(round.updatedAt)}</span>
          </Checkbox>
        ))}
      </Checkbox.Group>
    </Modal>
  );
}
