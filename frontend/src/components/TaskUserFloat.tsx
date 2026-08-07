import { useEffect, useMemo, useState } from 'react';
import type { ClipboardEvent, KeyboardEvent } from 'react';
import { CaretDown, ChatCircleDots, PaperPlaneRight } from '@phosphor-icons/react';
import { Button, Input, message } from 'antd';
import type { TaskAttachment, TaskInteractionItem, TaskStatus } from '../types/api';
import { TaskInteractionPanel } from './TaskInteractionPanel';
import { TaskAttachmentPicker, TaskAttachmentPreviewList } from './TaskAttachmentPicker';
import { isLongInput, mergePastedText, prepareLongInput } from '../utils/longInput';
import { isActiveTaskStatus } from '../utils/taskStatus';

interface TaskUserFloatProps {
  taskId: number;
  taskStatus?: TaskStatus;
  userInput?: string;
  attachments?: TaskAttachment[];
  interactions?: TaskInteractionItem[];
  inputLabel: string;
  mode?: 'floating' | 'composer';
  onAnswered?: () => void;
  onContinue?: (content: string, attachmentIds: string[]) => Promise<void>;
}

export function TaskUserFloat({ taskId, taskStatus, userInput, attachments, interactions, inputLabel, mode = 'floating', onAnswered, onContinue }: TaskUserFloatProps) {
  const canInteract = !taskStatus || isActiveTaskStatus(taskStatus);
  const canContinue = Boolean(onContinue) && taskStatus === 'SUCCESS';
  const pendingInteractions = useMemo(() => (canInteract ? (interactions || []).filter((item) => item.status === 'PENDING') : []), [canInteract, interactions]);
  const answeredInteractions = useMemo(() => (interactions || []).filter((item) => item.status !== 'PENDING'), [interactions]);
  const pendingKey = pendingInteractions.map((item) => item.id).join(',');
  const [expanded, setExpanded] = useState(false);
  const [continueText, setContinueText] = useState('');
  const [continueAttachments, setContinueAttachments] = useState<TaskAttachment[]>([]);
  const [submittingContinue, setSubmittingContinue] = useState(false);
  const [convertingLongInput, setConvertingLongInput] = useState(false);

  useEffect(() => {
    if (pendingInteractions.length > 0) {
      setExpanded(true);
    }
  }, [pendingKey]);

  useEffect(() => {
    if (!canInteract && !canContinue) {
      setExpanded(false);
    }
  }, [canInteract, canContinue]);

  const summary = pendingInteractions[0]?.question || userInput || '暂无输入内容';
  const title = pendingInteractions.length > 0 ? `${pendingInteractions.length > 1 ? `${pendingInteractions.length} 个` : ''}待确认` : inputLabel;

  if (mode === 'composer') {
    const composerDisabled = !canContinue || pendingInteractions.length > 0 || submittingContinue || convertingLongInput;
    const placeholder = pendingInteractions.length > 0
      ? '请先完成上方确认'
      : canContinue ? '继续提问...' : isActiveTaskStatus(taskStatus) ? '智能体正在处理...' : '当前对话无法继续提问';
    return (
      <aside className={pendingInteractions.length > 0 ? 'task-user-composer task-user-composer-alert' : 'task-user-composer'}>
        {pendingInteractions.length > 0 && (
          <div className="task-user-composer-interactions">
            <TaskInteractionPanel taskId={taskId} interactions={interactions} compact onAnswered={onAnswered} />
          </div>
        )}
        <div className="task-user-composer-shell">
          <TaskAttachmentPicker
            attachments={continueAttachments}
            onChange={handleContinueAttachmentsChange}
            disabled={composerDisabled}
          />
          <Input.TextArea
            className="task-user-composer-input"
            value={continueText}
            onChange={(event) => setContinueText(event.target.value)}
            onPaste={handleContinuePaste}
            onPressEnter={handleComposerEnter}
            placeholder={placeholder}
            disabled={composerDisabled}
            autoSize={{ minRows: 1, maxRows: 5 }}
            variant="borderless"
          />
          <Button
            className="task-user-composer-send"
            type="primary"
            shape="circle"
            loading={submittingContinue || convertingLongInput}
            disabled={composerDisabled || (!continueText.trim() && continueAttachments.length === 0)}
            icon={<PaperPlaneRight size={17} weight="fill" />}
            aria-label="发送"
            title="发送"
            onClick={handleContinue}
          />
        </div>
      </aside>
    );
  }

  return (
    <aside className={pendingInteractions.length > 0 ? 'task-user-float task-user-float-alert' : 'task-user-float'}>
      <button className="task-user-float-summary" type="button" onClick={() => setExpanded((value) => !value)}>
        <span className="task-user-float-icon"><ChatCircleDots size={18} weight="fill" /></span>
        <span className="task-user-float-text">
          <span className="task-user-float-title">{title}</span>
          <span className="task-user-float-preview">{summary}</span>
        </span>
        <CaretDown className={expanded ? 'task-user-float-caret task-user-float-caret-open' : 'task-user-float-caret'} size={16} weight="bold" />
      </button>
      {expanded && (
        <div className="task-user-float-body">
          {canInteract && <TaskInteractionPanel taskId={taskId} interactions={interactions} compact onAnswered={onAnswered} />}
          <div className="ask-user-message">
            <div className="ask-user-message-label">{inputLabel}</div>
            <div className="ask-user-message-content">{userInput || (attachments?.length ? '仅上传附件' : '-')}</div>
            <TaskAttachmentPreviewList attachments={attachments || []} compact />
          </div>
          {answeredInteractions.map((interaction) => (
            <div className="ask-user-message ask-user-message-secondary" key={interaction.id}>
              <div className="ask-user-message-label">{interaction.question}</div>
              <div className="ask-user-message-content">{interactionAnswerText(interaction)}</div>
            </div>
          ))}
          {canContinue && pendingInteractions.length === 0 && (
            <div className="task-continue-box">
              <div className="task-continue-label">继续追问</div>
              <Input.TextArea
                value={continueText}
                onChange={(event) => setContinueText(event.target.value)}
                onPaste={handleContinuePaste}
                placeholder="基于这次结果继续问..."
                disabled={submittingContinue || convertingLongInput}
                autoSize={{ minRows: 2, maxRows: 5 }}
              />
              <TaskAttachmentPicker
                attachments={continueAttachments}
                onChange={handleContinueAttachmentsChange}
                disabled={submittingContinue || convertingLongInput}
              />
              <div className="task-continue-actions">
                <Button
                  type="primary"
                  size="small"
                  loading={submittingContinue || convertingLongInput}
                  onClick={handleContinue}
                >
                  发送
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </aside>
  );

  async function handleContinue() {
    if (convertingLongInput) {
      return;
    }
    const text = continueText.trim();
    if (!text && continueAttachments.length === 0) {
      message.warning('请输入继续追问内容或上传附件');
      return;
    }
    if (!onContinue) {
      return;
    }
    setSubmittingContinue(true);
    try {
      const prepared = await prepareLongInput(text, continueAttachments);
      if (prepared.textAttachment) {
        setContinueAttachments(prepared.attachments);
      }
      await onContinue(prepared.userInput, prepared.attachmentIds);
      setContinueText('');
      setContinueAttachments([]);
      if (mode === 'floating') {
        setExpanded(false);
      }
    } finally {
      setSubmittingContinue(false);
    }
  }

  async function handleContinuePaste(event: ClipboardEvent<HTMLTextAreaElement>) {
    if (convertingLongInput) {
      event.preventDefault();
      return;
    }
    const pastedText = event.clipboardData.getData('text');
    const nextText = mergePastedText(
      event.currentTarget.value,
      pastedText,
      event.currentTarget.selectionStart,
      event.currentTarget.selectionEnd,
    );
    if (!isLongInput(nextText)) {
      return;
    }
    event.preventDefault();
    setConvertingLongInput(true);
    try {
      const prepared = await prepareLongInput(nextText, continueAttachments);
      setContinueText(prepared.userInput);
      setContinueAttachments(prepared.attachments);
    } catch {
      setContinueText(nextText);
    } finally {
      setConvertingLongInput(false);
    }
  }

  function handleContinueAttachmentsChange(nextAttachments: TaskAttachment[]) {
    const removedFullInput = continueAttachments.find((attachment) =>
      attachment.inputKind === 'user-input'
      && !nextAttachments.some((nextAttachment) => nextAttachment.id === attachment.id));
    if (removedFullInput) {
      setContinueText('');
    }
    setContinueAttachments(nextAttachments);
  }

  function handleComposerEnter(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.shiftKey) {
      return;
    }
    event.preventDefault();
    if (canContinue && pendingInteractions.length === 0 && !submittingContinue && !convertingLongInput
      && (continueText.trim() || continueAttachments.length > 0)) {
      void handleContinue();
    }
  }
}

function interactionAnswerText(interaction: TaskInteractionItem) {
  if (interaction.answerAction === 'SKIP') {
    return '已跳过';
  }
  if (interaction.answerAction === 'UNKNOWN') {
    return '不知道';
  }
  if (interaction.answerAction === 'CANCEL') {
    return '已取消';
  }
  if (interaction.answerActionKey) {
    const action = interaction.actions?.find((item) => item.key === interaction.answerActionKey);
    if (action) {
      return action.label;
    }
  }
  if (interaction.answerText) {
    return interaction.answerText;
  }
  if (interaction.answerValues?.length) {
    return interaction.answerValues.map((answer) => {
      const field = interaction.fields?.find((item) => item.key === answer.key);
      const label = field?.label || answer.key;
      const value = answer.value || answer.selectedValues?.map((item) => field?.options?.find((option) => option.value === item)?.label || item).join('、') || '-';
      return `${label}：${value}`;
    }).join('；');
  }
  const selected = interaction.selectedValues || [];
  if (selected.length === 0) {
    return '-';
  }
  const labels = selected.map((value) => interaction.options?.find((option) => option.value === value)?.label || value);
  return labels.join('、');
}
