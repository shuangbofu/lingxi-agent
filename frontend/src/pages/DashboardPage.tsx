import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, DatePicker, Empty, Segmented, Select, Skeleton, Table } from 'antd';
import '../styles/dashboard.css';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import * as echarts from 'echarts/core';
import type { EChartsOption } from 'echarts';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { getAgentRuntimes, getDashboardTokenUsage, listAllScenarios, listTaskOwners } from '../api/lingxi';
import type { AgentRuntimeDescriptor, AgentScenario, DashboardTimeGranularity, DashboardTokenDimension, DashboardTokenTrend, DashboardTokenUsage, TaskScenario, UserItem } from '../types/api';
import { displayTaskType, formatTokenCount } from '../utils/format';
import { definitionScenarioOptions } from '../utils/definitions';
import { scenarioPaletteForDimension, scenarioPaletteForTheme, scenarioPalettes, type ScenarioPalette } from '../utils/scenarioVisual';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { useThemeMode } from '../context/ThemeContext';

echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, CanvasRenderer]);

type TimePreset = 'today' | 'yesterday' | 'last24Hours' | 'last7Days' | 'last14Days' | 'last30Days' | 'thisMonth' | 'lastMonth';

const timeRangePresets = [
  { label: '今天', value: 'today' },
  { label: '昨天', value: 'yesterday' },
  { label: '近24小时', value: 'last24Hours' },
  { label: '近 7 天', value: 'last7Days' },
  { label: '近 14 天', value: 'last14Days' },
  { label: '近 30 天', value: 'last30Days' },
  { label: '本月', value: 'thisMonth' },
  { label: '上月', value: 'lastMonth' },
] satisfies Array<{ label: string; value: TimePreset }>;

const defaultTimePreset: TimePreset = 'last7Days';

type DashboardLoadParams = {
  createdRange: [Dayjs, Dayjs];
  ownerId?: number;
  scenario?: TaskScenario;
  modelProfileId?: string;
  runtimeCode?: string;
  granularity: DashboardTimeGranularity;
};

export function DashboardPage() {
  const [data, setData] = useState<DashboardTokenUsage>();
  const [users, setUsers] = useState<UserItem[]>([]);
  const [scenarios, setScenarios] = useState<AgentScenario[]>([]);
  const [runtimes, setRuntimes] = useState<AgentRuntimeDescriptor[]>([]);
  const [timePreset, setTimePreset] = useState<TimePreset | undefined>(defaultTimePreset);
  const [createdRange, setCreatedRange] = useState<[Dayjs, Dayjs]>(() => presetRange(defaultTimePreset));
  const [ownerId, setOwnerId] = useState<number>();
  const [scenario, setScenario] = useState<TaskScenario>();
  const [modelProfileId, setModelProfileId] = useState<string>();
  const [runtimeCode, setRuntimeCode] = useState<string>();
  const [granularity, setGranularity] = useState<DashboardTimeGranularity>('day');
  const [dimension, setDimension] = useState<'type' | 'owner' | 'model'>('type');
  const [rankView, setRankView] = useState<'chart' | 'detail'>('chart');
  const [loading, setLoading] = useState(false);
  const [showInitialSkeleton, setShowInitialSkeleton] = useState(false);
  const loadSequenceRef = useRef(0);

  useEffect(() => {
    loadUsers();
    loadDefinitions();
    loadRuntimes();
  }, []);

  useEffect(() => {
    loadData({ createdRange, ownerId, scenario, modelProfileId, runtimeCode, granularity });
  }, [createdRange, ownerId, scenario, modelProfileId, runtimeCode, granularity]);

  const modelOptions = useMemo(() => Array.from(new Map(
    runtimes.flatMap((runtime) => runtime.models)
      .map((model) => [model.id, { label: model.name, value: model.id }] as const),
  ).values()), [runtimes]);

  const initialLoading = loading && !data;

  useEffect(() => {
    if (!initialLoading) {
      setShowInitialSkeleton(false);
      return undefined;
    }
    const timer = window.setTimeout(() => setShowInitialSkeleton(true), 200);
    return () => window.clearTimeout(timer);
  }, [initialLoading]);

  async function loadUsers() {
    setUsers(await listTaskOwners());
  }

  async function loadDefinitions() {
    setScenarios(await listAllScenarios());
  }

  async function loadRuntimes() {
    setRuntimes(await getAgentRuntimes());
  }

  async function loadData(params: DashboardLoadParams) {
    const requestId = loadSequenceRef.current + 1;
    loadSequenceRef.current = requestId;
    setLoading(true);
    try {
      const result = await getDashboardTokenUsage({
        createdStart: params.createdRange[0].format('YYYY-MM-DDTHH:mm:ss'),
        createdEnd: params.createdRange[1].format('YYYY-MM-DDTHH:mm:ss'),
        ownerId: params.ownerId,
        scenario: params.scenario,
        modelProfileId: params.modelProfileId,
        runtimeCode: params.runtimeCode,
        granularity: params.granularity,
      });
      if (loadSequenceRef.current === requestId) {
        setData(result);
      }
    } finally {
      if (loadSequenceRef.current === requestId) {
        setLoading(false);
      }
    }
  }

  const dimensionRows = dimension === 'type'
    ? data?.byQuestionTypes || []
    : dimension === 'owner' ? data?.byOwners || [] : data?.byModels || [];
  const dimensionName = dimension === 'type' ? typeName : dimension === 'owner' ? ownerName : modelName;
  const detailColumns: ColumnsType<DashboardTokenDimension> = [
    { title: dimension === 'type' ? '场景' : dimension === 'owner' ? '提问人' : '模型', dataIndex: 'dimensionName', ellipsis: true, render: (_, record) => dimensionName(record) },
    { title: '请求', dataIndex: 'requestCount', width: 80, align: 'right', render: formatTokenCount },
    { title: '任务', dataIndex: 'taskCount', width: 80, align: 'right', render: formatTokenCount },
    { title: 'Token', dataIndex: 'totalTokens', width: 110, align: 'right', render: formatTokenCount },
    { title: '平均', dataIndex: 'averageTokens', width: 100, align: 'right', render: formatTokenCount },
    { title: '输出', dataIndex: 'outputTokens', width: 100, align: 'right', render: formatTokenCount },
  ];

  function resetFilters() {
    setTimePreset(defaultTimePreset);
    setCreatedRange(presetRange(defaultTimePreset));
    setOwnerId(undefined);
    setScenario(undefined);
    setModelProfileId(undefined);
    setRuntimeCode(undefined);
    setGranularity('day');
  }

  function selectTimePreset(value?: TimePreset) {
    setTimePreset(value);
    if (value) {
      setCreatedRange(presetRange(value));
    }
  }

  return (
    <div className="dashboard-page flex h-full min-h-0 flex-col gap-3">
      <section className="dashboard-summary-section rounded-md border border-neutral-200 bg-white p-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback="仪表盘" path="/admin/dashboard" icon="dashboard" tone="blue" />
          </div>
          <div className="flex flex-wrap gap-2">
            <Select
              className="w-44"
              allowClear
              showSearch
              placeholder="全部用户"
              value={ownerId}
              optionFilterProp="label"
              options={users.map((item) => ({ label: `${item.displayName}（${item.username}）`, value: item.id }))}
              onChange={setOwnerId}
            />
            <Select className="w-44" allowClear placeholder="全部场景" value={scenario} options={definitionScenarioOptions(scenarios)} onChange={setScenario} />
            <Select
              className="w-36"
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="全部模型"
              value={modelProfileId}
              options={modelOptions}
              onChange={setModelProfileId}
            />
            <Select
              className="w-36"
              allowClear
              placeholder="全部运行方式"
              value={runtimeCode}
              options={runtimes.map((runtime) => ({ label: runtime.name, value: runtime.code }))}
              onChange={setRuntimeCode}
            />
            <div className="dashboard-date-filter">
              <Select
                className="dashboard-date-preset"
                allowClear
                placeholder="快捷时间"
                value={timePreset}
                options={timeRangePresets}
                onChange={selectTimePreset}
              />
              <DatePicker.RangePicker
                className="dashboard-date-range"
                allowClear={false}
                value={createdRange}
                onChange={(value) => {
                  if (!value?.[0] || !value?.[1]) {
                    return;
                  }
                  setTimePreset(undefined);
                  setCreatedRange([value[0], value[1]]);
                }}
              />
            </div>
            <Button onClick={resetFilters}>重置</Button>
          </div>
        </div>
        <div className="dashboard-metric-grid">
          <MetricCard
            tone="blue"
            label="数量"
            value={formatTokenCount(data?.summary?.requestCount)}
            suffix="次请求"
            details={[
              { label: '任务', value: formatTokenCount(data?.summary?.taskCount) },
              { label: '已完成', value: formatTokenCount(finishedTaskCount(data?.summary)) },
              { label: '等待中', value: formatTokenCount(data?.summary?.pendingCount) },
              { label: '进行中', value: formatTokenCount(activeTaskCount(data?.summary)) },
              { label: '待确认', value: formatTokenCount(data?.summary?.waitingUserCount) },
            ]}
            footer={`请求/任务：${requestPerTaskText(data?.summary)}`}
            loading={showInitialSkeleton}
            pending={initialLoading}
          />
          <MetricCard
            tone="cyan"
            label="总 Token"
            value={formatTokenCount(data?.summary?.totalTokens)}
            details={[
              { label: '输入', value: formatTokenCount(data?.summary?.inputTokens) },
              { label: '输出', value: formatTokenCount(data?.summary?.outputTokens) },
              { label: '缓存命中', value: formatTokenCount(data?.summary?.cachedInputTokens) },
              { label: '缓存创建', value: formatTokenCount(data?.summary?.cacheCreationInputTokens) },
            ]}
            footer={`缓存命中率：${cacheHitRatioText(data?.summary)}`}
            loading={showInitialSkeleton}
            pending={initialLoading}
          />
          <MetricCard
            tone="amber"
            label="平均"
            value={formatTokenCount(data?.summary?.averageTokens)}
            suffix="每任务"
            details={[
              { label: '每请求', value: formatTokenCount(averageByRequest(data?.summary)) },
              { label: '平均输入', value: formatTokenCount(averagePart(data?.summary?.inputTokens, data?.summary?.taskCount)) },
              { label: '平均输出', value: formatTokenCount(averagePart(data?.summary?.outputTokens, data?.summary?.taskCount)) },
              { label: '平均缓存', value: formatTokenCount(averagePart(data?.summary?.cachedInputTokens, data?.summary?.taskCount)) },
              { label: '平均推理', value: formatTokenCount(averagePart(data?.summary?.reasoningOutputTokens, data?.summary?.taskCount)) },
            ]}
            loading={showInitialSkeleton}
            pending={initialLoading}
          />
          <MetricCard
            tone="rose"
            label="执行结果"
            value={successRateText(data?.summary)}
            suffix="成功率"
            details={[
              { label: '成功', value: formatTokenCount(data?.summary?.successCount), tone: 'success' },
              { label: '失败', value: formatTokenCount(data?.summary?.failedCount), tone: 'danger' },
              { label: '取消', value: formatTokenCount(data?.summary?.canceledCount) },
              { label: '等待中', value: formatTokenCount(data?.summary?.pendingCount) },
              { label: '执行中', value: formatTokenCount(data?.summary?.runningCount) },
              { label: '待确认', value: formatTokenCount(data?.summary?.waitingUserCount) },
            ]}
            footer={`未完成：${formatTokenCount(activeTaskCount(data?.summary))}`}
            loading={showInitialSkeleton}
            pending={initialLoading}
          />
        </div>
        <div className="dashboard-observability-row">
          {initialLoading ? (
            [5, 7].map((count) => (
              <div className={`dashboard-observability-group${showInitialSkeleton ? ' is-loading' : ''}`} key={count}>
                {showInitialSkeleton && Array.from({ length: count }, (_, index) => (
                  <i className="dashboard-skeleton-block" key={index} />
                ))}
              </div>
            ))
          ) : (
            <>
              <div className="dashboard-observability-group">
                <span>执行体验</span>
                <strong>平均首反馈 {formatMilliseconds(data?.executionExperience?.averageFirstFeedbackMs)}</strong>
                <strong>平均命令耗时 {formatMilliseconds(data?.executionExperience?.averageCommandDurationMs)}</strong>
                <strong>平均结果整理 {formatMilliseconds(data?.executionExperience?.averageResultProcessingMs)}</strong>
                <strong>上下文压缩 {formatNumber(data?.executionExperience?.compactionCount)}</strong>
                <strong>重复能力调用 {formatNumber(data?.executionExperience?.duplicateCapabilityCallCount)}</strong>
              </div>
              <div className="dashboard-observability-group">
                <span>资源记忆</span>
                <strong>检索 {formatNumber(data?.resourceMemory?.searchCount)}</strong>
                <strong>命中 {formatNumber(data?.resourceMemory?.hitCount)}</strong>
                <strong>命中率 {formatRatio(data?.resourceMemory?.hitCount, data?.resourceMemory?.searchCount)}</strong>
                <strong>返回候选 {formatNumber(data?.resourceMemory?.candidateCount)}</strong>
                <strong>新增/刷新 {formatNumber(data?.resourceMemory?.createdCount)}/{formatNumber(data?.resourceMemory?.refreshedCount)}</strong>
                <strong>过期清理 {formatNumber(data?.resourceMemory?.expiredCount)}</strong>
                <strong>主动失效 {formatNumber(data?.resourceMemory?.invalidatedCount)}</strong>
              </div>
            </>
          )}
        </div>
      </section>

      <section className="dashboard-trend-section rounded-md border border-neutral-200 bg-white p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="text-sm font-semibold">用量趋势</div>
          <div className="flex flex-wrap items-center justify-end gap-3">
            <div className="dashboard-chart-legend">
              <span><i className="dashboard-legend-cache" />缓存命中</span>
              <span><i className="dashboard-legend-input" />非缓存输入</span>
              <span><i className="dashboard-legend-output" />输出</span>
              <span><i className="dashboard-legend-request" />请求数</span>
              <span><i className="dashboard-legend-hit-rate" />命中率</span>
            </div>
            <Segmented
              size="small"
              shape="round"
              value={granularity}
              options={[
                { label: '日', value: 'day' },
                { label: '周', value: 'week' },
                { label: '月', value: 'month' },
              ]}
              onChange={(value) => {
                const nextGranularity = value as DashboardTimeGranularity;
                setGranularity(nextGranularity);
              }}
            />
          </div>
        </div>
        <TrendChart data={data?.trends || []} loading={showInitialSkeleton} pending={initialLoading} />
      </section>

      <section className="dashboard-rank-section">
        <div className="dashboard-distribution rounded-md border border-neutral-200 bg-white p-4">
          <div className="dashboard-distribution-head flex flex-wrap items-center justify-between gap-2">
            <div className="text-sm font-semibold">用量排行</div>
            <div className="flex items-center gap-2">
              <Segmented
                size="small"
                shape="round"
                value={dimension}
                options={[
                  { label: '按场景', value: 'type' },
                  { label: '按人员', value: 'owner' },
                  { label: '按模型', value: 'model' },
                ]}
                onChange={(value) => setDimension(value as 'type' | 'owner' | 'model')}
              />
              <Segmented
                size="small"
                shape="round"
                value={rankView}
                options={[
                  { label: '图表', value: 'chart' },
                  { label: '明细', value: 'detail' },
                ]}
                onChange={(value) => setRankView(value as 'chart' | 'detail')}
              />
            </div>
          </div>
          {rankView === 'chart' ? (
            <DistributionContent
              data={dimensionRows}
              nameOf={dimensionName}
              loading={showInitialSkeleton}
              pending={initialLoading}
              scenarios={scenarios}
              dimension={dimension}
            />
          ) : initialLoading ? (
            showInitialSkeleton ? <DashboardDetailSkeleton /> : <div className="dashboard-detail-skeleton dashboard-loading-shell" />
          ) : (
            <Table
              rowKey="dimensionKey"
              columns={detailColumns}
              dataSource={dimensionRows.slice(0, 10)}
              size="small"
              scroll={{ x: 560 }}
              pagination={false}
            />
          )}
        </div>
      </section>
    </div>
  );
}

function MetricCard({
  label,
  value,
  suffix,
  details,
  footer,
  tone,
  loading,
  pending,
}: {
  label: string;
  value: string;
  suffix?: string;
  details: { label: string; value: string; tone?: 'success' | 'danger' }[];
  footer?: string;
  tone: 'blue' | 'cyan' | 'amber' | 'rose';
  loading: boolean;
  pending: boolean;
}) {
  return (
    <div className={`dashboard-metric dashboard-metric-${tone} rounded-md p-3`}>
      {loading ? (
        <div className="dashboard-metric-skeleton">
          <Skeleton.Input active size="small" />
          <Skeleton.Input active />
          <Skeleton active title={false} paragraph={{ rows: 2, width: ['100%', '72%'] }} />
        </div>
      ) : pending ? null : (
        <>
          <div className="dashboard-metric-label text-xs">{label}</div>
          <div className="dashboard-metric-main">
            <span>{value}</span>
            {suffix && <em>{suffix}</em>}
          </div>
          <div className="dashboard-metric-detail">
            {details.map((item) => (
              <span className={item.tone ? `dashboard-metric-detail-${item.tone}` : undefined} key={item.label}>
                {item.label} {item.value}
              </span>
            ))}
          </div>
          {footer && <div className="dashboard-metric-footer">{footer}</div>}
        </>
      )}
    </div>
  );
}

function TrendChart({ data, loading, pending }: { data: DashboardTokenTrend[]; loading: boolean; pending: boolean }) {
  const { mode } = useThemeMode();
  const rows = useMemo(() => data.slice(-30), [data]);
  const chartNeutral = chartNeutralColors(mode);
  const option = useMemo<EChartsOption>(() => ({
    color: ['#67d5c4', '#60a5fa', '#f59e72', '#b7a6ff', '#ef8f57', '#22a6a1'],
    grid: { left: 48, right: 72, top: 28, bottom: 36 },
    tooltip: {
      trigger: 'axis',
      formatter: (params) => trendTooltip(params, rows),
      ...chartNeutral.tooltip,
    },
    xAxis: {
      type: 'category',
      data: rows.map((item) => shortDate(item.date)),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: chartNeutral.line } },
      axisLabel: { color: chartNeutral.text },
    },
    yAxis: [
      {
        type: 'value',
        name: 'Token',
        nameTextStyle: { color: chartNeutral.text },
        axisLabel: { color: chartNeutral.text, formatter: (value: number) => formatTokenCount(value) },
        splitLine: { lineStyle: { color: chartNeutral.line } },
      },
      {
        type: 'value',
        name: '请求',
        nameTextStyle: { color: chartNeutral.text },
        axisLabel: { color: chartNeutral.text, formatter: (value: number) => `${formatTokenCount(value)}次` },
        splitLine: { show: false },
      },
      {
        type: 'value',
        name: '命中率',
        min: 0,
        max: 100,
        offset: 42,
        nameTextStyle: { color: chartNeutral.text },
        axisLabel: { color: chartNeutral.text, formatter: (value: number) => `${value}%` },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '缓存命中',
        type: 'bar',
        stack: 'token',
        data: rows.map((item) => item.cachedInputTokens),
        barMaxWidth: 28,
        itemStyle: { color: '#67d5c4' },
      },
      {
        name: '非缓存输入',
        type: 'bar',
        stack: 'token',
        data: rows.map((item) => Math.max(0, item.inputTokens - item.cachedInputTokens)),
        barMaxWidth: 28,
        itemStyle: { color: '#60a5fa' },
      },
      {
        name: '输出',
        type: 'bar',
        stack: 'token',
        data: rows.map((item) => item.outputTokens),
        barMaxWidth: 28,
        itemStyle: { color: '#f59e72' },
      },
      {
        name: '推理输出',
        type: 'bar',
        stack: 'token',
        data: rows.map((item) => item.reasoningOutputTokens),
        barMaxWidth: 28,
        itemStyle: { color: '#b7a6ff' },
      },
      {
        name: '请求数',
        type: 'line',
        yAxisIndex: 1,
        data: rows.map((item) => item.requestCount),
        smooth: true,
        symbolSize: 7,
        itemStyle: { color: '#ef8f57' },
        lineStyle: { width: 3, color: '#ef8f57' },
      },
      {
        name: '缓存命中率',
        type: 'line',
        yAxisIndex: 2,
        data: rows.map((item) => cacheHitRatio(item)),
        smooth: true,
        symbolSize: 7,
        itemStyle: { color: '#22a6a1' },
        lineStyle: { width: 3, color: '#22a6a1' },
      },
    ],
  }), [chartNeutral, rows]);

  if (loading) {
    return <DashboardChartSkeleton className="dashboard-trend-chart" />;
  }
  if (pending) {
    return <div className="dashboard-trend-chart dashboard-loading-shell" />;
  }
  if (rows.length === 0) {
    return <Empty className="dashboard-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无用量数据" />;
  }

  return <EChart className="dashboard-trend-chart" option={option} />;
}

function DistributionContent({
  data,
  nameOf,
  loading,
  pending,
  scenarios,
  dimension,
}: {
  data: DashboardTokenDimension[];
  nameOf: (record: DashboardTokenDimension) => string;
  loading: boolean;
  pending: boolean;
  scenarios: AgentScenario[];
  dimension: 'type' | 'owner' | 'model';
}) {
  const { mode } = useThemeMode();
  const rows = data.slice(0, 10);
  const maxToken = Math.max(...rows.map((item) => item.totalTokens), 1);
  const paletteOf = (record: DashboardTokenDimension, index: number) => scenarioPaletteForTheme(dimension === 'type'
    ? scenarioPaletteForDimension(record, scenarios)
    : scenarioPalettes[index % scenarioPalettes.length], mode);
  if (loading) {
    return <DashboardDistributionSkeleton />;
  }
  if (pending) {
    return <div className="dashboard-distribution-grid dashboard-loading-shell" />;
  }
  return (
    <>
      {rows.length === 0 && !loading ? (
        <Empty className="dashboard-empty" image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无分布数据" />
      ) : (
        <div className="dashboard-distribution-grid">
          <DistributionPie data={rows} nameOf={nameOf} paletteOf={paletteOf} />
          <div className="dashboard-bar-list">
            {rows.map((item, index) => {
              const percent = Math.max(3, Math.round((item.totalTokens / maxToken) * 100));
              const palette = paletteOf(item, index);
              return (
                <div className="dashboard-bar-row" key={item.dimensionKey}>
                  <div className="dashboard-bar-row-main">
                    <span className="dashboard-rank" style={{ background: palette.soft, color: palette.accent }}>{index + 1}</span>
                    <span className="dashboard-bar-name" title={nameOf(item)}>{nameOf(item)}</span>
                    <span className="dashboard-bar-value">{formatTokenCount(item.totalTokens)}</span>
                  </div>
                  <div className="dashboard-bar-track">
                    <div
                      className="dashboard-bar-fill"
                      style={{
                        width: `${percent}%`,
                        background: `linear-gradient(90deg, ${palette.border}, ${palette.accent})`,
                      }}
                    />
                  </div>
                  <div className="dashboard-bar-meta">
                    <span>总量 {formatTokenCount(item.totalTokens)}</span>
                    <span>{formatTokenCount(item.requestCount)} 次请求</span>
                    <span>{item.taskCount} 个任务</span>
                    <span>平均 {formatTokenCount(item.averageTokens)}</span>
                    <span>输出 {formatTokenCount(item.outputTokens)}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </>
  );
}

function DashboardChartSkeleton({ className }: { className: string }) {
  return (
    <div className={`dashboard-chart-skeleton ${className}`}>
      <div className="dashboard-chart-skeleton-plot">
        <div className="dashboard-chart-skeleton-grid">
          {[0, 1, 2, 3, 4].map((item) => <i key={item} />)}
        </div>
        <div className="dashboard-chart-skeleton-bars">
          {[48, 72, 42, 84, 60, 76, 52, 68].map((height, index) => (
            <span className="dashboard-skeleton-block" key={index} style={{ height: `${height}%` }} />
          ))}
        </div>
      </div>
    </div>
  );
}

function DashboardDistributionSkeleton() {
  return (
    <div className="dashboard-distribution-grid dashboard-distribution-skeleton">
      <div className="dashboard-pie-chart dashboard-pie-skeleton"><div className="dashboard-pie-skeleton-ring" /></div>
      <div className="dashboard-bar-list dashboard-bar-skeleton-list">
        {Array.from({ length: 10 }, (_, item) => (
          <div className="dashboard-bar-row dashboard-bar-row-skeleton" key={item}>
            <div className="dashboard-bar-row-main">
              <i className="dashboard-skeleton-block" />
              <span className="dashboard-skeleton-block" />
              <em className="dashboard-skeleton-block" />
            </div>
            <div className="dashboard-skeleton-block dashboard-bar-row-skeleton-track" />
            <div className="dashboard-bar-meta">
              <span className="dashboard-skeleton-block" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function DashboardDetailSkeleton() {
  return (
    <div className="dashboard-detail-skeleton">
      <div className="dashboard-detail-skeleton-row dashboard-detail-skeleton-head">
        {[28, 12, 12, 16, 14, 14].map((width, index) => <Skeleton.Input active size="small" style={{ width: `${width}%` }} key={index} />)}
      </div>
      {Array.from({ length: 6 }, (_, row) => (
        <div className="dashboard-detail-skeleton-row" key={row}>
          {[28, 12, 12, 16, 14, 14].map((width, index) => <Skeleton.Input active size="small" style={{ width: `${width}%` }} key={index} />)}
        </div>
      ))}
    </div>
  );
}

function DistributionPie({
  data,
  nameOf,
  paletteOf,
}: {
  data: DashboardTokenDimension[];
  nameOf: (record: DashboardTokenDimension) => string;
  paletteOf: (record: DashboardTokenDimension, index: number) => ScenarioPalette;
}) {
  const { mode } = useThemeMode();
  const chartNeutral = chartNeutralColors(mode);
  const option = useMemo<EChartsOption>(() => ({
    color: data.map((item, index) => paletteOf(item, index).accent),
    tooltip: {
      trigger: 'item',
      ...chartNeutral.tooltip,
      formatter: (params) => {
        const item = params as { name: string; value: number; percent: number };
        return `${item.name}<br/>消耗量：${formatTokenCount(item.value)}<br/>占比：${item.percent}%`;
      },
    },
    series: [
      {
        name: '用量分布',
        type: 'pie',
        radius: ['52%', '74%'],
        center: ['44%', '50%'],
        avoidLabelOverlap: true,
        label: { show: false },
        labelLine: { show: false },
        data: data.map((item, index) => ({
          name: nameOf(item),
          value: item.totalTokens,
          itemStyle: {
            color: paletteOf(item, index).accent,
            borderColor: chartNeutral.surface,
            borderWidth: 2,
          },
        })),
      },
    ],
  }), [chartNeutral, data, nameOf, paletteOf]);

  return <EChart className="dashboard-pie-chart" option={option} />;
}

function EChart({ option, className }: { option: EChartsOption; className: string }) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<ReturnType<typeof echarts.init>>();

  useEffect(() => {
    if (!containerRef.current) {
      return undefined;
    }
    const chart = echarts.init(containerRef.current);
    chartRef.current = chart;
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(containerRef.current);
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', resize);
      chart.dispose();
      chartRef.current = undefined;
    };
  }, []);

  useEffect(() => {
    chartRef.current?.setOption(option, { notMerge: true, lazyUpdate: true });
  }, [option]);

  return <div ref={containerRef} className={className} />;
}

function chartNeutralColors(mode: 'light' | 'dark') {
  const dark = mode === 'dark';
  return {
    line: dark ? '#343434' : '#e5e7eb',
    surface: dark ? '#181818' : '#ffffff',
    text: dark ? '#9d9d9d' : '#737373',
    tooltip: {
      backgroundColor: dark ? '#202020' : 'rgba(255, 255, 255, 0.96)',
      borderColor: dark ? '#3a3a3a' : '#e5e7eb',
      textStyle: { color: dark ? '#ededed' : '#171717' },
    },
  };
}

function shortDate(value: string) {
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return value.slice(5);
  }
  return value;
}

function ownerName(record: DashboardTokenDimension) {
  if (!record.ownerUsername) {
    return record.dimensionName || '未知用户';
  }
  return `${record.ownerDisplayName || record.ownerUsername}（${record.ownerUsername}）`;
}

function typeName(record: DashboardTokenDimension) {
  if (record.scenarioName) {
    return record.scenarioName;
  }
  return record.scenario ? displayTaskType(record.scenario, record.dimensionName) : record.dimensionName;
}

function modelName(record: DashboardTokenDimension) {
  const name = record.modelName || record.dimensionName || '未记录模型';
  if (!record.modelIdentifier || record.modelIdentifier === 'UNKNOWN' || record.modelIdentifier === name) {
    return name;
  }
  return `${name}（${record.modelIdentifier}）`;
}

function activeTaskCount(summary?: DashboardTokenDimension) {
  return (summary?.pendingCount || 0) + (summary?.runningCount || 0) + (summary?.waitingUserCount || 0);
}

function finishedTaskCount(summary?: DashboardTokenDimension) {
  return (summary?.successCount || 0) + (summary?.failedCount || 0) + (summary?.canceledCount || 0);
}

function requestPerTaskText(summary?: DashboardTokenDimension) {
  const tasks = summary?.taskCount || 0;
  if (tasks === 0) {
    return '0';
  }
  return trimPercent((summary?.requestCount || 0) / tasks);
}

function averageByRequest(summary?: DashboardTokenDimension) {
  const requests = summary?.requestCount || 0;
  return requests === 0 ? 0 : Math.round((summary?.totalTokens || 0) / requests);
}

function averagePart(value?: number, taskCount?: number) {
  const count = taskCount || 0;
  return count === 0 ? 0 : Math.round((value || 0) / count);
}

function cacheHitRatio(row: Pick<DashboardTokenTrend, 'cachedInputTokens' | 'inputTokens'>) {
  return row.inputTokens === 0 ? 0 : Number(((row.cachedInputTokens / row.inputTokens) * 100).toFixed(1));
}

function trendTooltip(params: unknown, rows: DashboardTokenTrend[]) {
  const items = Array.isArray(params) ? params as { dataIndex: number }[] : [];
  const row = rows[items[0]?.dataIndex ?? 0];
  if (!row) {
    return '';
  }
  const directInput = Math.max(0, row.inputTokens - row.cachedInputTokens);
  return [
    `<strong>${shortDate(row.date)}</strong>`,
    `总 Token：${formatTokenCount(row.totalTokens)}`,
    `缓存命中：${formatTokenCount(row.cachedInputTokens)}（${trimPercent(cacheHitRatio(row))}%）`,
    `非缓存输入：${formatTokenCount(directInput)}`,
    `输出：${formatTokenCount(row.outputTokens)}`,
    `推理输出：${formatTokenCount(row.reasoningOutputTokens)}`,
    `缓存创建：${formatTokenCount(row.cacheCreationInputTokens)}`,
    `请求数：${formatTokenCount(row.requestCount)} · 任务数：${formatTokenCount(row.taskCount)}`,
  ].join('<br/>');
}

function cacheHitRatioText(summary?: DashboardTokenDimension) {
  const cached = summary?.cachedInputTokens || 0;
  const input = summary?.inputTokens || 0;
  const ratio = input === 0 ? 0 : (cached / input) * 100;
  return `${formatTokenCount(cached)}/${formatTokenCount(input)} ${trimPercent(ratio)}%`;
}

function successRateText(summary?: DashboardTokenDimension) {
  const success = summary?.successCount || 0;
  const finished = success + (summary?.failedCount || 0) + (summary?.canceledCount || 0);
  return `${trimPercent(finished === 0 ? 0 : (success / finished) * 100)}%`;
}

function trimPercent(value: number) {
  return value.toFixed(value >= 10 ? 1 : 2).replace(/\.0+$|(\.\d*[1-9])0+$/, '$1');
}

function formatNumber(value?: number) {
  return Math.max(0, value || 0).toLocaleString('zh-CN');
}

function formatRatio(value?: number, total?: number) {
  const denominator = total || 0;
  return `${trimPercent(denominator === 0 ? 0 : ((value || 0) / denominator) * 100)}%`;
}

function formatMilliseconds(value?: number) {
  if (value == null) return '-';
  if (value < 1000) return `${Math.max(0, value)}ms`;
  if (value < 60_000) return `${(value / 1000).toFixed(value < 10_000 ? 1 : 0)}秒`;
  return `${Math.floor(value / 60_000)}分${Math.round((value % 60_000) / 1000)}秒`;
}

function presetRange(value: TimePreset): [Dayjs, Dayjs] {
  const now = dayjs();
  if (value === 'today') {
    return [now.startOf('day'), now.endOf('day')];
  }
  if (value === 'yesterday') {
    const yesterday = now.subtract(1, 'day');
    return [yesterday.startOf('day'), yesterday.endOf('day')];
  }
  if (value === 'last24Hours') {
    return [now.subtract(24, 'hour'), now];
  }
  if (value === 'last14Days') {
    return [now.subtract(13, 'day').startOf('day'), now.endOf('day')];
  }
  if (value === 'last30Days') {
    return [now.subtract(29, 'day').startOf('day'), now.endOf('day')];
  }
  if (value === 'thisMonth') {
    return [now.startOf('month'), now.endOf('day')];
  }
  if (value === 'lastMonth') {
    const lastMonth = now.subtract(1, 'month');
    return [lastMonth.startOf('month'), lastMonth.endOf('month')];
  }
  return [now.subtract(6, 'day').startOf('day'), now.endOf('day')];
}
