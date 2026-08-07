import { useEffect, useMemo, useState } from 'react';
import { Button, Input, Modal, Tooltip, message } from 'antd';
import { ShareNetwork } from '@phosphor-icons/react';
import { createTaskShare, listTaskRounds } from '../api/lingxi';
import type { TaskItem, TaskRoundSummary, TaskShareCreateResult } from '../types/api';
import { displayTaskTitle, displayTaskType } from '../utils/format';
import { copyText } from '../utils/clipboard';
import { TaskRoundSelectionModal } from './TaskRoundSelectionModal';

interface TaskShareButtonProps {
  task: TaskItem;
  disabled?: boolean;
  label?: string;
  size?: 'small' | 'middle' | 'large';
  type?: 'default' | 'text';
}

export function TaskShareButton({ task, disabled, label, size = 'middle', type = 'default' }: TaskShareButtonProps) {
  const [loading, setLoading] = useState(false);
  const [share, setShare] = useState<TaskShareCreateResult>();
  const [rounds, setRounds] = useState<TaskRoundSummary[]>([]);
  const [roundModalOpen, setRoundModalOpen] = useState(false);
  const [selectedRoundIds, setSelectedRoundIds] = useState<number[]>([]);
  const selectedRounds = rounds.filter((round) => selectedRoundIds.includes(round.id));
  const shareText = useMemo(() => share ? buildShareText(task, share, selectedRounds, rounds[0]?.userInput) : '', [task, rounds, selectedRounds, share]);
  const canShare = !disabled && task.status === 'SUCCESS';

  useEffect(() => {
    if (!roundModalOpen) {
      return;
    }
    loadRoundsForShare();
  }, [roundModalOpen, task.id]);

  async function openRoundModal() {
    if (!canShare) {
      return;
    }
    setRoundModalOpen(true);
  }

  async function loadRoundsForShare() {
    setLoading(true);
    try {
      const result = await listTaskRounds(task.id);
      const nextRounds = mergeRoundList(result, task).filter((round) => round.status === 'SUCCESS');
      setRounds(nextRounds.length ? nextRounds : [taskSummary(task)]);
      const availableRounds = nextRounds.length ? nextRounds : [taskSummary(task)];
      setSelectedRoundIds((current) => {
        const availableIds = new Set(availableRounds.map((round) => round.id));
        const retained = current.filter((roundId) => availableIds.has(roundId));
        return retained.length ? retained : [latestRoundId(availableRounds)];
      });
    } finally {
      setLoading(false);
    }
  }

  async function handleCreateShare() {
    if (!canShare || selectedRoundIds.length === 0) {
      return;
    }
    setLoading(true);
    try {
      const result = await createTaskShare(task.id, { roundTaskIds: selectedRoundIds });
      setShare(result);
      setRoundModalOpen(false);
      message.success('分享信息已生成');
    } finally {
      setLoading(false);
    }
  }

  async function handleCopy() {
    const copied = await copyText(shareText);
    if (copied) {
      message.success('分享信息已复制');
      return;
    }
    message.warning('当前浏览器不支持自动复制，请手动复制');
  }

  return (
    <>
      <Tooltip title={canShare ? '分享' : '成功后可分享'}>
        <Button
          type={type}
          size={size}
          disabled={!canShare}
          loading={loading}
          icon={<ShareNetwork size={16} weight="fill" />}
          onClick={openRoundModal}
        >
          {label}
        </Button>
      </Tooltip>
      <TaskRoundSelectionModal
        title="选择分享轮次"
        open={roundModalOpen}
        rounds={rounds}
        selectedRoundIds={selectedRoundIds}
        loading={loading}
        onCancel={() => setRoundModalOpen(false)}
        onOk={handleCreateShare}
        okText="生成分享"
        onChange={setSelectedRoundIds}
      />
      <Modal
        title="分享"
        open={!!share}
        onCancel={() => setShare(undefined)}
        onOk={handleCopy}
        okText="复制分享信息"
        cancelText="关闭"
        destroyOnClose
      >
        <Input.TextArea value={shareText} readOnly autoSize={{ minRows: 5, maxRows: 8 }} />
      </Modal>
    </>
  );
}

function buildShareText(rootTask: TaskItem, share: TaskShareCreateResult, sharedRounds: TaskRoundSummary[], conversationQuestion?: string) {
  const shareName = shareTypeText(rootTask);
  const title = displayTaskTitle(conversationQuestion || rootTask.title, rootTask.scenario, rootTask.scenarioName);
  return [
    `我分享了一份${shareName}：${title}`,
    `轮次：${sharedRounds.map((round) => `第 ${round.roundNo || 1} 轮`).join('、')}`,
    `地址：${shareUrl(share.shareCode)}`,
    `密码：${share.password}`,
    '打开后输入密码即可预览问题和回答。',
  ].join('\n');
}

function shareTypeText(task: TaskItem) {
  return `${displayTaskType(task.scenario, task.scenarioName)}内容`;
}

function shareUrl(shareCode: string) {
  return `${window.location.origin}${window.location.pathname}${window.location.search}#/share/${shareCode}`;
}

function mergeRoundList(rounds: TaskRoundSummary[], currentTask: TaskItem) {
  const map = new Map<number, TaskRoundSummary>();
  rounds.forEach((round) => map.set(round.id, round));
  map.set(currentTask.id, taskSummary(currentTask));
  return Array.from(map.values()).sort((left, right) => (left.roundNo || 1) - (right.roundNo || 1) || left.id - right.id);
}

function latestRoundId(rounds: TaskRoundSummary[]) {
  return rounds[rounds.length - 1]?.id;
}

function taskSummary(task: TaskItem): TaskRoundSummary {
  return {
    id: task.id,
    roundNo: task.roundNo,
    userInput: task.userInput,
    status: task.status,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
  };
}
