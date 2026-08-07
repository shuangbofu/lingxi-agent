import { useEffect, useMemo, useState } from 'react';
import { Button, DatePicker, Input, Select, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowClockwise, ChartBar, Eye } from '@phosphor-icons/react';
import { useNavigate } from 'react-router-dom';
import type { Dayjs } from 'dayjs';
import { listAllScenarios, listTaskOwners, pageTasks } from '../api/lingxi';
import type { AgentScenario, TaskItem, TaskRoundSummary, TaskScenario, TaskStatus, UserItem } from '../types/api';
import { displayTaskTitle, displayTaskType, formatDuration, formatTime } from '../utils/format';
import { taskOwnerText } from '../utils/task';
import { definitionScenarioOptions } from '../utils/definitions';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { AppTag, TaskStatusTag } from '../components/AppTag';
import { RuntimeModeTag } from '../components/RuntimeModeTag';
import { TaskModelTag } from '../components/TaskModelTag';
import { useRuntimeModes } from '../hooks/useRuntimeModes';

const statusOptions: { label: string; value: TaskStatus }[] = [
  { label: '等待中', value: 'PENDING' },
  { label: '执行中', value: 'RUNNING' },
  { label: '待确认', value: 'WAITING_USER' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '已取消', value: 'CANCELED' },
];

export function AnalysisRecordPage() {
  const navigate = useNavigate();
  const [records, setRecords] = useState<TaskItem[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [scenario, setScenario] = useState<TaskScenario>();
  const [status, setStatus] = useState<TaskStatus>();
  const [query, setQuery] = useState('');
  const [ownerId, setOwnerId] = useState<number>();
  const [runtimeCode, setRuntimeCode] = useState<string>();
  const [modelProfileId, setModelProfileId] = useState<string>();
  const [createdRange, setCreatedRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [users, setUsers] = useState<UserItem[]>([]);
  const [definitions, setDefinitions] = useState<AgentScenario[]>([]);
  const runtimeModes = useRuntimeModes();
  const pageSize = 15;

  useEffect(() => {
    loadUsers();
    loadDefinitions();
  }, []);

  useEffect(() => {
    loadRecords(1);
  }, [scenario, status, query, ownerId, createdRange, runtimeCode, modelProfileId]);

  const modelOptions = useMemo(() => Array.from(new Map(
    runtimeModes
      .filter((runtime) => !runtimeCode || runtime.code === runtimeCode)
      .flatMap((runtime) => runtime.models)
      .map((model) => [model.id, { label: model.name, value: model.id }] as const),
  ).values()), [runtimeCode, runtimeModes]);

  async function loadUsers() {
    const result = await listTaskOwners();
    setUsers(result);
  }

  async function loadDefinitions() {
    const scenarios = await listAllScenarios();
    setDefinitions(scenarios.filter((item) => item.userVisible !== false));
  }

  async function loadRecords(nextPage = page) {
    setLoading(true);
    try {
      const result = await pageTasks({
        page: nextPage,
        size: pageSize,
        scenario,
        status,
        recordType: 'analysis',
        query: query.trim() || undefined,
        ownerId,
        runtimeCode,
        modelProfileId,
        createdStart: createdRange?.[0]?.startOf('day').format('YYYY-MM-DDTHH:mm:ss'),
        createdEnd: createdRange?.[1]?.endOf('day').format('YYYY-MM-DDTHH:mm:ss'),
      });
      setRecords(result.records);
      setTotal(result.total);
      setPage(result.page);
    } finally {
      setLoading(false);
    }
  }

  function selectRuntime(nextRuntimeCode?: string) {
    setRuntimeCode(nextRuntimeCode);
    if (nextRuntimeCode && modelProfileId && !runtimeModes
      .find((runtime) => runtime.code === nextRuntimeCode)
      ?.models.some((model) => model.id === modelProfileId)) {
      setModelProfileId(undefined);
    }
  }

  const columns: ColumnsType<TaskItem> = [
    {
      title: '提问内容',
      dataIndex: 'userInput',
      ellipsis: true,
      render: (_, record) => (
        <Tooltip title={record.userInput || displayTaskTitle(record.title, record.scenario, record.scenarioName)}>
          <button className="task-question-link" type="button" onClick={() => navigate(`/admin/tasks/records/${record.id}`)}>
            {record.userInput || displayTaskTitle(record.title, record.scenario, record.scenarioName) || '-'}
          </button>
        </Tooltip>
      ),
    },
    { title: '提问人', dataIndex: 'ownerDisplayName', width: 150, render: (_, record) => taskOwnerText(record) },
    { title: '类型', dataIndex: 'scenario', width: 120, render: (_, record) => displayTaskType(record.scenario, record.scenarioName) },
    { title: '执行模式', dataIndex: 'runtimeCode', width: 110, render: (value) => <RuntimeModeTag runtimeCode={value} runtimeModes={runtimeModes} /> },
    {
      title: '模型',
      dataIndex: 'modelName',
      width: 240,
      ellipsis: true,
      render: (_, record) => <TaskModelTag modelProviderName={record.modelProviderName} modelName={record.modelName} modelIdentifier={record.modelIdentifier} />,
    },
    {
      title: '执行记录',
      width: 110,
      render: (_, record) => <AppTag tone="blue">{record.roundCount ?? record.roundSummaries?.length ?? 1} 条</AppTag>,
    },
    { title: '状态', dataIndex: 'status', width: 100, render: (value) => <TaskStatusTag status={value} /> },
    { title: '创建时间', width: 170, render: (_, record) => formatTime(record.createdAt) },
    { title: '更新时间', width: 170, render: (_, record) => formatTime(record.updatedAt) },
    {
      title: '操作',
      width: 170,
      render: (_, record) => (
        <div className="analysis-record-actions">
          <Button size="small" icon={<Eye size={14} weight="fill" />} onClick={() => navigate(`/admin/tasks/records/${record.id}`)}>
            查看
          </Button>
          <Button size="small" icon={<ChartBar size={14} weight="fill" />} onClick={() => navigate(`/admin/tasks/${latestRoundId(record)}/report?returnTo=${encodeURIComponent('/admin/tasks/records')}`)}>
            分析报告
          </Button>
        </div>
      ),
    },
  ];

  const executionColumns: ColumnsType<TaskRoundSummary> = [
    { title: '执行轮次', dataIndex: 'roundNo', width: 100, render: (value) => `第 ${value || 1} 轮` },
    {
      title: '提问内容',
      dataIndex: 'userInput',
      ellipsis: true,
      render: (value) => <Tooltip title={value || '-'}>{value || '-'}</Tooltip>,
    },
    { title: '执行模式', dataIndex: 'runtimeCode', width: 110, render: (value) => <RuntimeModeTag runtimeCode={value} runtimeModes={runtimeModes} /> },
    {
      title: '模型',
      dataIndex: 'modelName',
      width: 240,
      ellipsis: true,
      render: (_, record) => <TaskModelTag modelProviderName={record.modelProviderName} modelName={record.modelName} modelIdentifier={record.modelIdentifier} />,
    },
    { title: '状态', dataIndex: 'status', width: 100, render: (value) => <TaskStatusTag status={value} /> },
    { title: '耗时', width: 130, render: (_, record) => formatDuration(record.startedAt, record.endedAt) },
    { title: '创建时间', dataIndex: 'createdAt', width: 170, render: formatTime },
    {
      title: '操作',
      width: 210,
      render: (_, record) => (
        <div className="analysis-record-actions">
          <Button size="small" icon={<Eye size={14} weight="fill" />} onClick={() => navigate(`/admin/tasks/${record.id}`)}>
            查看执行
          </Button>
          <Button size="small" icon={<ChartBar size={14} weight="fill" />} onClick={() => navigate(`/admin/tasks/${record.id}/report?returnTo=${encodeURIComponent('/admin/tasks/records')}`)}>
            分析报告
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <section className="flex min-h-0 flex-1 flex-col bg-white">
        <div className="mb-3 flex shrink-0 flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback="分析记录" path="/admin/tasks/records" icon="record" tone="blue" />
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
            <Select className="w-40" allowClear placeholder="分析类型" value={scenario} options={definitionScenarioOptions(definitions)} onChange={setScenario} />
            <Select
              className="w-40"
              allowClear
              placeholder="全部运行方式"
              value={runtimeCode}
              options={runtimeModes.map((runtime) => ({ label: runtime.name, value: runtime.code }))}
              onChange={selectRuntime}
            />
            <Select
              className="w-40"
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部模型"
              value={modelProfileId}
              options={modelOptions}
              onChange={setModelProfileId}
            />
            <Select className="w-32" allowClear placeholder="状态" value={status} options={statusOptions} onChange={setStatus} />
            <Button icon={<ArrowClockwise size={16} weight="fill" />} onClick={() => loadRecords(page)}>刷新</Button>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <Table
            className="analysis-record-table"
            rowKey="id"
            columns={columns}
            dataSource={records}
            loading={loading}
            size="middle"
            scroll={{ x: 1620 }}
            expandable={{
              expandedRowRender: (record) => (
                <div className="analysis-execution-records">
                  <div className="analysis-execution-records-title">
                    执行记录 <span>共 {record.roundCount ?? record.roundSummaries?.length ?? 1} 条</span>
                  </div>
                  <Table<TaskRoundSummary>
                    rowKey="id"
                    columns={executionColumns}
                    dataSource={record.roundSummaries || []}
                    size="small"
                    scroll={{ x: 1330 }}
                    pagination={false}
                  />
                </div>
              ),
              rowExpandable: (record) => (record.roundSummaries?.length || 0) > 0,
            }}
            pagination={{
              current: page,
              pageSize,
              total,
              showSizeChanger: false,
              showTotal: (value) => `共 ${value} 条`,
              onChange: (nextPage) => loadRecords(nextPage),
            }}
          />
        </div>
      </section>
    </div>
  );
}

function latestRoundId(task: TaskItem) {
  const rounds = task.roundSummaries || [];
  return rounds.length ? rounds[rounds.length - 1].id : task.id;
}
