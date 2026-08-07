import { useEffect, useState } from 'react';
import { Button, Form, Input, Segmented, Tabs, message } from 'antd';
import '../styles/task-sharing.css';
import { LockKey, Rows, ShareNetwork, Tabs as TabsIcon } from '@phosphor-icons/react';
import { useParams } from 'react-router-dom';
import { getTaskShareMeta, previewTaskShare } from '../api/lingxi';
import type { TaskSharePreview, TaskSharePreviewRequest, TaskShareRoundPreview } from '../types/api';
import { StructuredResult } from '../components/StructuredResult';
import { displayTaskTitle, displayTaskType, formatTime } from '../utils/format';
import { AppLogo } from '../components/AppLogo';
import { BrandWatermark } from '../components/BrandWatermark';
import { AppBrandText } from '../components/AppBrandText';

type ShareViewMode = 'round' | 'continuous';

export function TaskSharePage() {
  const { shareCode } = useParams();
  const [form] = Form.useForm<TaskSharePreviewRequest>();
  const [loading, setLoading] = useState(false);
  const [meta, setMeta] = useState<TaskSharePreview>();
  const [preview, setPreview] = useState<TaskSharePreview>();
  const [viewMode, setViewMode] = useState<ShareViewMode>('continuous');
  const rounds = preview ? sharedRounds(preview) : [];

  useEffect(() => {
    if (!shareCode) {
      return;
    }
    getTaskShareMeta(shareCode)
      .then(setMeta)
      .catch(() => message.error('分享不存在或已失效'));
  }, [shareCode]);

  async function handlePreview(values: TaskSharePreviewRequest) {
    if (!shareCode) {
      return;
    }
    setLoading(true);
    try {
      setPreview(await previewTaskShare(shareCode, values));
    } catch {
      message.error('分享密码不正确或分享已失效');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="share-page bg-app">
      <main className={preview ? 'share-shell share-shell-result' : 'share-shell share-shell-access'}>
        {!preview ? (
          <section className="share-access-panel">
            <div className="share-access-brand-row">
              <AppLogo className="share-access-logo" />
              <AppBrandText className="share-access-brand-name" />
            </div>
            <div className="share-access-head">
              <div className="share-access-title">{meta ? displayTaskTitle(meta.userInput || meta.title, meta.scenario, meta.scenarioName) : '分享内容'}</div>
              <div className="share-access-source">{shareSourceText(meta)}</div>
              <div className="share-access-desc">输入密码后查看分享内容</div>
            </div>
            <Form form={form} layout="vertical" onFinish={handlePreview}>
              <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入分享密码' }]}>
                <Input.Password prefix={<LockKey size={16} />} placeholder="请输入分享密码" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={loading} block>查看</Button>
            </Form>
          </section>
        ) : (
          <section className="share-result-panel watermark-surface">
            <BrandWatermark />
            <div className="share-result-head">
              <div className="share-result-title-row">
                <div className="min-w-0">
                  <div className="share-result-brand">
                    <AppLogo className="share-result-logo" />
                    <AppBrandText className="share-result-brand-name" />
                  </div>
                  <h1>{displayTaskTitle(preview.userInput || preview.title, preview.scenario, preview.scenarioName)}</h1>
                </div>
                <div className="share-result-title-side">
                  <div className="share-result-source">
                    <ShareNetwork size={15} weight="fill" />
                    <span>{shareSourceText(preview)}</span>
                  </div>
                  {rounds.length > 1 && (
                    <Segmented<ShareViewMode>
                      className="share-result-view-switch"
                      value={viewMode}
                      onChange={setViewMode}
                      options={[
                        {
                          value: 'round',
                          label: <span className="share-result-view-option"><TabsIcon size={16} weight="fill" /><span>分轮阅读</span></span>,
                        },
                        {
                          value: 'continuous',
                          label: <span className="share-result-view-option"><Rows size={16} weight="fill" /><span>连续阅读</span></span>,
                        },
                      ]}
                    />
                  )}
                </div>
              </div>
              <div className="share-result-meta">
                <span>类型：{displayTaskType(preview.scenario, preview.scenarioName)}</span>
                {rounds.length > 0 && <span>轮次：{rounds.map((round) => `第 ${round.roundNo} 轮`).join('、')}</span>}
                {preview.endedAt && <span>完成：{formatTime(preview.endedAt)}</span>}
              </div>
            </div>
            <div className="share-result-content">
              {viewMode === 'round' && rounds.length > 1 ? (
                <Tabs
                  className="share-result-round-tabs"
                  defaultActiveKey={String(rounds[rounds.length - 1].taskId)}
                  destroyOnHidden
                  items={rounds.map((round) => ({
                    key: String(round.taskId),
                    label: `第 ${round.roundNo} 轮`,
                    children: <SharedRoundResult preview={preview} round={round} />,
                  }))}
                />
              ) : rounds.map((round) => <SharedRoundResult preview={preview} round={round} key={round.taskId} />)}
            </div>
          </section>
        )}
      </main>
    </div>
  );
}

function SharedRoundResult({ preview, round }: { preview: TaskSharePreview; round: TaskShareRoundPreview }) {
  return (
    <section className="share-result-round">
      <div className="share-result-round-head">
        <strong>第 {round.roundNo} 轮</strong>
        {round.endedAt && <span>{formatTime(round.endedAt)}</span>}
      </div>
      <div className="share-result-round-question">{round.userInput || '-'}</div>
      <StructuredResult
        data={round.resultData}
        fallback={round.resultText}
        title={round.userInput || `第 ${round.roundNo} 轮`}
        renderer={round.resultRenderer || preview.resultRenderer}
        reportName={displayTaskType(preview.scenario, preview.scenarioName)}
        showWatermark={false}
      />
    </section>
  );
}

function sharedRounds(preview: TaskSharePreview): TaskShareRoundPreview[] {
  if (preview.rounds?.length) {
    return preview.rounds;
  }
  return [{
    taskId: preview.taskId,
    roundNo: preview.sharedRoundNo || 1,
    userInput: preview.sharedRoundUserInput || preview.userInput,
    resultRenderer: preview.resultRenderer,
    resultText: preview.resultText,
    resultData: preview.resultData,
    startedAt: preview.startedAt,
    endedAt: preview.endedAt,
    createdAt: preview.createdAt,
  }];
}

function shareTypeText(preview: TaskSharePreview) {
  return `${displayTaskType(preview.scenario, preview.scenarioName)}内容`;
}

function shareSourceText(preview?: TaskSharePreview) {
  if (!preview) {
    return '分享内容';
  }
  const shareType = shareTypeText(preview);
  if (!preview.sharedByDisplayName && !preview.sharedByUsername) {
    return `${shareType}分享`;
  }
  if (!preview.sharedByDisplayName || preview.sharedByDisplayName === preview.sharedByUsername) {
    return `来自于${preview.sharedByUsername || preview.sharedByDisplayName}的${shareType}分享`;
  }
  return `来自于${preview.sharedByDisplayName}（${preview.sharedByUsername}）的${shareType}分享`;
}
