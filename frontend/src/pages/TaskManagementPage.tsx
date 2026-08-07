import { useEffect, useState } from 'react';
import { Button, DatePicker, Input, Select, Table, Tooltip, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowClockwise, Eye, Stop } from '@phosphor-icons/react';
import { useNavigate } from 'react-router-dom';
import type { Dayjs } from 'dayjs';
import { cancelTask, listAllScenarios, listTaskOwners, pageTasks } from '../api/lingxi';
import type { AgentScenario, TaskItem, TaskScenario, TaskStatus, UserItem } from '../types/api';
import { displayTaskTitle, displayTaskType, formatTime } from '../utils/format';
import { isActiveTaskStatus } from '../utils/taskStatus';
import { taskOwnerText } from '../utils/task';
import { definitionScenarioOptions } from '../utils/definitions';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { TaskStatusTag } from '../components/AppTag';
import { RuntimeModeTag } from '../components/RuntimeModeTag';
import { useRuntimeModes } from '../hooks/useRuntimeModes';

const statusOptions: { label: string; value: TaskStatus }[] = [
  { label: '等待中', value: 'PENDING' },
  { label: '执行中', value: 'RUNNING' },
  { label: '待确认', value: 'WAITING_USER' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '已取消', value: 'CANCELED' },
];

export function TaskManagementPage() {
  const navigate = useNavigate();
  const [records, setRecords] = useState<TaskItem[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [scenario, setScenario] = useState<TaskScenario>();
  const [status, setStatus] = useState<TaskStatus>();
  const [query, setQuery] = useState('');
  const [ownerId, setOwnerId] = useState<number>();
  const [createdRange, setCreatedRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [users, setUsers] = useState<UserItem[]>([]);
  const [definitions, setDefinitions] = useState<AgentScenario[]>([]);
  const runtimeModes = useRuntimeModes();
  const pageSize = 15;
  const hasRunningTask = records.some((item) => isActiveTaskStatus(item.status));

  useEffect(() => {
    loadUsers();
    loadDefinitions();
  }, []);

  useEffect(() => {
    loadTasks(1);
  }, [scenario, status, query, ownerId, createdRange]);

  useEffect(() => {
    if (!hasRunningTask) {
      return;
    }
    const timer = window.setInterval(() => loadTasks(page), 3000);
    return () => window.clearInterval(timer);
  }, [hasRunningTask, page, scenario, status, query, ownerId, createdRange]);

  async function loadUsers() {
    const result = await listTaskOwners();
    setUsers(result);
  }

  async function loadDefinitions() {
    setDefinitions(await listAllScenarios());
  }

  async function loadTasks(nextPage = page) {
    setLoading(true);
    try {
      const result = await pageTasks({
        page: nextPage,
        size: pageSize,
        scenario,
        status,
        query: query.trim() || undefined,
        ownerId,
        createdStart: createdRange?.[0]?.startOf('day').format('YYYY-MM-DDTHH:mm:ss'),
        createdEnd: createdRange?.[1]?.endOf('day').format('YYYY-MM-DDTHH:mm:ss'),
        aggregateConversation: false,
      });
      setRecords(result.records);
      setTotal(result.total);
      setPage(result.page);
    } finally {
      setLoading(false);
    }
  }

  async function stopTask(id: number) {
    await cancelTask(id);
    message.success('任务已取消');
    await loadTasks(page);
  }

  const columns: ColumnsType<TaskItem> = [
    {
      title: '提问内容',
      dataIndex: 'userInput',
      ellipsis: true,
      render: (_, record) => (
        <Tooltip title={record.userInput || displayTaskTitle(record.title, record.scenario, record.scenarioName)}>
          <button className="task-question-link" type="button" onClick={() => navigate(`/admin/tasks/${record.id}`)}>
            {record.userInput || displayTaskTitle(record.title, record.scenario, record.scenarioName) || '-'}
          </button>
        </Tooltip>
      ),
    },
    { title: '提问人', dataIndex: 'ownerDisplayName', width: 150, render: (_, record) => taskOwnerText(record) },
    { title: '类型', dataIndex: 'scenario', width: 120, render: (_, record) => displayTaskType(record.scenario, record.scenarioName) },
    { title: '执行模式', dataIndex: 'runtimeCode', width: 110, render: (value) => <RuntimeModeTag runtimeCode={value} runtimeModes={runtimeModes} /> },
    { title: '状态', dataIndex: 'status', width: 100, render: (value) => <TaskStatusTag status={value} /> },
    { title: '创建时间', width: 170, render: (_, record) => formatTime(record.createdAt) },
    { title: '更新时间', width: 170, render: (_, record) => formatTime(record.updatedAt) },
    {
      title: '操作',
      width: 150,
      render: (_, record) => (
        <div className="flex gap-2">
          <Button size="small" icon={<Eye size={14} weight="fill" />} onClick={() => navigate(`/admin/tasks/${record.id}`)}>查看</Button>
          {isActiveTaskStatus(record.status) && (
            <Button size="small" danger icon={<Stop size={14} weight="fill" />} onClick={() => stopTask(record.id)}>取消</Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <section className="flex min-h-0 flex-1 flex-col bg-white">
        <div className="mb-3 flex shrink-0 flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback="执行记录" path="/admin/tasks" icon="record" tone="blue" />
          </div>
          <div className="flex flex-wrap gap-2">
            <Input.Search className="w-56" allowClear placeholder="搜索提问内容" value={query} onChange={(event) => setQuery(event.target.value)} />
            <Select
              className="w-40"
              allowClear
              showSearch
              placeholder="提问人"
              value={ownerId}
              optionFilterProp="label"
              options={users.map((item) => ({ label: `${item.displayName}（${item.username}）`, value: item.id }))}
              onChange={setOwnerId}
            />
            <DatePicker.RangePicker className="w-64" value={createdRange} onChange={(value) => setCreatedRange(value as [Dayjs, Dayjs] | null)} />
            <Select className="w-40" allowClear placeholder="任务类型" value={scenario} options={definitionScenarioOptions(definitions)} onChange={setScenario} />
            <Select className="w-32" allowClear placeholder="状态" value={status} options={statusOptions} onChange={setStatus} />
            <Button icon={<ArrowClockwise size={16} weight="fill" />} onClick={() => loadTasks(page)}>刷新</Button>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <Table
            rowKey="id"
            columns={columns}
            dataSource={records}
            loading={loading}
            size="middle"
            scroll={{ x: 1290 }}
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: false,
              showTotal: (value) => `共 ${value} 条`,
              onChange: (nextPage) => loadTasks(nextPage),
            }}
          />
        </div>
      </section>
    </div>
  );
}
