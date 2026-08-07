import { Tabs } from 'antd';
import { useNavigate } from 'react-router-dom';
import { AnalysisRecordPage } from './AnalysisRecordPage';
import { TaskManagementPage } from './TaskManagementPage';

type TaskRecordTab = 'tasks' | 'records';

export function TaskRecordPage({ tab = 'records' }: { tab?: TaskRecordTab }) {
  const navigate = useNavigate();

  return (
    <div className="admin-page-surface flex h-full min-h-0 flex-col p-3">
      <Tabs
        className="management-tabs"
        activeKey={tab}
        onChange={(key) => navigate(key === 'tasks' ? '/admin/tasks' : '/admin/tasks/records')}
        items={[
          {
            key: 'records',
            label: '分析记录',
            children: <AnalysisRecordPage />,
          },
          {
            key: 'tasks',
            label: '执行记录',
            children: <TaskManagementPage />,
          },
        ]}
      />
    </div>
  );
}
