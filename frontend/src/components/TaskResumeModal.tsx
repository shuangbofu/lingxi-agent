import { useEffect, useState } from 'react';
import { Input, Modal, message } from 'antd';
import { getTaskAttachmentText } from '../api/lingxi';
import type { TaskItem } from '../types/api';

interface TaskResumeModalProps {
  open: boolean;
  task: TaskItem;
  loading: boolean;
  onCancel: () => void;
  onSubmit: (userInput?: string) => Promise<void>;
}

export function TaskResumeModal({ open, task, loading, onCancel, onSubmit }: TaskResumeModalProps) {
  const [value, setValue] = useState(task.userInput || '');
  const [originalValue, setOriginalValue] = useState(task.userInput || '');
  const [loadingInput, setLoadingInput] = useState(false);

  useEffect(() => {
    if (!open) {
      return;
    }
    let active = true;
    const initialValue = task.userInput || '';
    setValue(initialValue);
    setOriginalValue(initialValue);
    const inputAttachment = task.attachments?.find((attachment) => attachment.inputKind === 'user-input');
    if (!inputAttachment) {
      return () => {
        active = false;
      };
    }
    setLoadingInput(true);
    getTaskAttachmentText(inputAttachment.url)
      .then((content) => {
        if (active) {
          setValue(content);
          setOriginalValue(content);
        }
      })
      .catch(() => undefined)
      .finally(() => {
        if (active) {
          setLoadingInput(false);
        }
      });
    return () => {
      active = false;
    };
  }, [open, task.id]);

  async function submit() {
    const normalized = value.trim();
    const hasRegularAttachment = task.attachments?.some((attachment) => attachment.inputKind !== 'user-input');
    if (!normalized && !hasRegularAttachment) {
      message.warning('请输入本轮内容');
      return;
    }
    await onSubmit(normalized === originalValue ? undefined : normalized);
  }

  return (
    <Modal
      title="恢复当前轮"
      open={open}
      width={620}
      okText="恢复执行"
      cancelText="取消"
      confirmLoading={loading || loadingInput}
      onOk={submit}
      onCancel={onCancel}
      destroyOnClose
    >
      <Input.TextArea
        value={value}
        disabled={loadingInput}
        autoSize={{ minRows: 5, maxRows: 12 }}
        maxLength={50_000}
        showCount
        placeholder="请输入本轮内容"
        onChange={(event) => setValue(event.target.value)}
      />
    </Modal>
  );
}
