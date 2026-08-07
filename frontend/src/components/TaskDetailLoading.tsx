import { Button, Skeleton } from 'antd';
import { CaretLeft } from '@phosphor-icons/react';

type TaskDetailLoadingProps = {
  variant: 'admin' | 'run';
  onBack: () => void;
  runMode?: 'conversation' | 'reading';
};

type TaskProcessLoadingProps = {
  failed?: boolean;
  onRetry?: () => void;
};

export function TaskDetailLoading({ variant, onBack, runMode = 'conversation' }: TaskDetailLoadingProps) {
  const admin = variant === 'admin';
  return (
    <div className={admin ? 'task-admin-detail-page' : 'ask-run-page'}>
      <section
        className={`${admin ? 'task-admin-detail-main' : 'ask-run-agent'} task-detail-loading-main`}
        aria-busy="true"
      >
        <div className={admin ? 'task-detail-head' : 'ask-run-head'}>
          {admin ? (
            <>
              <div className="task-detail-title-block">
                <Skeleton.Avatar active shape="square" size={34} />
                <div className="task-detail-loading-heading">
                  <Skeleton.Input active size="small" className="task-detail-loading-title" />
                  <Skeleton.Input active size="small" className="task-detail-loading-meta" />
                </div>
              </div>
              <div className="task-detail-actions">
                <Button size="small" icon={<CaretLeft size={14} />} onClick={onBack}>返回</Button>
                <Skeleton.Button active size="small" />
                <Skeleton.Button active size="small" />
              </div>
            </>
          ) : (
            <div className="ask-run-title-block">
              <Skeleton.Avatar active shape="square" size={52} />
              <div className="task-detail-loading-heading ask-run-heading-content">
                <div className="ask-run-heading-row">
                  <Skeleton.Input active size="small" className="task-detail-loading-title" />
                  <div className="ask-run-actions">
                    <Button size="small" icon={<CaretLeft size={14} />} onClick={onBack}>返回</Button>
                    <Skeleton.Button active size="small" />
                    <Skeleton.Button active size="small" />
                  </div>
                </div>
                <div className="ask-run-subline">
                  <Skeleton.Input active size="small" className="task-detail-loading-meta" />
                  <div className="task-run-loading-view-controls">
                    <i className="dashboard-skeleton-block" />
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
        <div className={admin ? 'task-detail-body' : 'ask-run-body'}>
          {admin ? (
            <div className="task-detail-tabs task-detail-loading-tabs">
              <div className="task-detail-loading-tab-list">
                {[0, 1, 2].map((item) => <i className="dashboard-skeleton-block" key={item} />)}
              </div>
              <div className="task-detail-tab-pane task-detail-loading-content">
                <Skeleton active title={false} paragraph={{ rows: 7, width: ['100%', '94%', '97%', '88%', '92%', '76%', '84%'] }} />
              </div>
            </div>
          ) : runMode === 'conversation' ? (
            <div className="run-conversation-scroll">
              <div className="run-conversation task-run-loading-conversation">
                <div className="run-user-row">
                  <div className="run-user-message task-run-loading-user-message">
                    <div className="task-run-loading-meta-row">
                      <i className="dashboard-skeleton-block" />
                      <i className="dashboard-skeleton-block" />
                    </div>
                    <div className="run-user-bubble task-run-loading-user-bubble">
                      <i className="dashboard-skeleton-block" />
                      <i className="dashboard-skeleton-block" />
                    </div>
                  </div>
                </div>
                <div className="run-agent-row">
                  <div className="run-agent-content task-run-loading-agent-content">
                    {[92, 78, 86, 64, 74].map((width) => (
                      <i className="dashboard-skeleton-block" key={width} style={{ width: `${width}%` }} />
                    ))}
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <div className="run-reading-view task-run-loading-reading">
              <div className="run-reading-result agent-output-document task-run-loading-reading-content">
                <i className="dashboard-skeleton-block task-run-loading-reading-title" />
                {[94, 82, 88, 72, 91, 66, 78].map((width) => (
                  <i className="dashboard-skeleton-block" key={width} style={{ width: `${width}%` }} />
                ))}
              </div>
            </div>
          )}
        </div>
        {!admin && runMode === 'conversation' && (
          <div className="task-user-composer task-run-loading-composer">
            <div className="task-user-composer-shell">
              <i className="dashboard-skeleton-block task-run-loading-composer-icon" />
              <i className="dashboard-skeleton-block task-run-loading-composer-input" />
              <i className="dashboard-skeleton-block task-run-loading-composer-send" />
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

export function TaskProcessLoading({ failed, onRetry }: TaskProcessLoadingProps) {
  if (failed) {
    return (
      <div className="task-process-load-failed">
        <span>思考过程加载失败</span>
        <Button size="small" onClick={onRetry}>重新加载</Button>
      </div>
    );
  }
  return (
    <div className="task-process-loading" aria-busy="true" aria-label="正在加载思考过程">
      {[72, 48, 64, 56, 78, 44].map((width, index) => (
        <div className="task-process-loading-row" key={`${width}-${index}`}>
          <i className="dashboard-skeleton-block" />
          <span className="dashboard-skeleton-block" style={{ width: `${width}%` }} />
        </div>
      ))}
    </div>
  );
}
