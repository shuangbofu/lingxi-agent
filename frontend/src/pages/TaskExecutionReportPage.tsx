import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Empty, Skeleton, Tabs, Tooltip } from 'antd';
import '../styles/execution-report.css';
import { ArrowClockwise, CaretLeft, ChartBar, Clock, CurrencyCny, Lightning, TerminalWindow } from '@phosphor-icons/react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { getTaskExecutionReport } from '../api/lingxi';
import { TaskStatusTag } from '../components/AppTag';
import { RuntimeModeTag } from '../components/RuntimeModeTag';
import { useRuntimeModes } from '../hooks/useRuntimeModes';
import type {
  TaskExecutionReport,
  TaskExecutionReportModelCall,
  TaskExecutionReportStep,
} from '../types/api';
import { formatModelCost, formatTime, formatTokenCount } from '../utils/format';
import { isActiveTaskStatus } from '../utils/taskStatus';

const diagnosisNames: Record<NonNullable<TaskExecutionReportModelCall['diagnosisType']>, string> = {
  ERROR: '调用失败',
  LARGE_INPUT: '输入偏大',
  SLOW_FIRST_RESPONSE: '首个响应偏慢',
  SLOW_GENERATION: '生成偏慢',
  HEAVY_REASONING: '推理量较大',
  NORMAL: '未见异常',
};

const tokenBreakdownItems = [
  { key: 'systemInstructionTokens', label: '系统基础指令', tone: 'system' },
  { key: 'taskInstructionTokens', label: '任务/场景指令', tone: 'task' },
  { key: 'mcpInstructionTokens', label: 'MCP 指令', tone: 'mcp' },
  { key: 'toolSchemaTokens', label: '工具 Schema', tone: 'schema' },
  { key: 'conversationTokens', label: '对话历史', tone: 'history' },
  { key: 'toolResultTokens', label: '工具结果', tone: 'result' },
  { key: 'imageTokens', label: '图片', tone: 'image' },
] as const;

interface TaskExecutionReportPageProps {
  /** 直接传入任务 ID（嵌入模式使用，不传时从路由参数读取） */
  taskId?: number;
  /** 嵌入模式：不渲染返回按钮，页面布局按内容区处理 */
  embedded?: boolean;
}

export function TaskExecutionReportPage({ taskId: taskIdProp, embedded = false }: TaskExecutionReportPageProps = {}) {
  const { id } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const runtimeModes = useRuntimeModes();
  const [report, setReport] = useState<TaskExecutionReport>();
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [selectedModelCallId, setSelectedModelCallId] = useState<string>();
  const [selectedModelCallGroupKey, setSelectedModelCallGroupKey] = useState<string | undefined>('all');
  const taskId = Number(taskIdProp ?? id);
  const runLayout = !embedded && location.pathname.startsWith('/runs/');
  const pageClassName = runLayout ? 'execution-report-page ask-run-page' : 'execution-report-page';

  const loadReport = useCallback(async () => {
    if (!Number.isFinite(taskId)) {
      setFailed(true);
      setLoading(false);
      return;
    }
    setFailed(false);
    try {
      setReport(await getTaskExecutionReport(taskId));
    } catch {
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, [taskId]);

  useEffect(() => {
    setLoading(true);
    void loadReport();
  }, [loadReport]);

  useEffect(() => {
    if (!report || !isActiveTaskStatus(report.status)) {
      return;
    }
    const timer = window.setInterval(() => void loadReport(), 3000);
    return () => window.clearInterval(timer);
  }, [report?.status, loadReport]);

  const operationalSteps = useMemo(() => [...(report?.steps || [])]
    .filter((step) => step.durationMs > 0 && ['COMMAND', 'ORCHESTRATION', 'RESULT_PROCESSING'].includes(step.type))
    .sort((left, right) => right.durationMs - left.durationMs)
    .slice(0, 8), [report?.steps]);
  const timelineEntries = useMemo(() => [
    ...(report?.modelCalls || []).map((call) => ({ kind: 'model' as const, startedAt: call.startedAt, call })),
    ...(report?.steps || [])
      .filter((step) => ['COMMAND', 'ORCHESTRATION', 'RESULT_PROCESSING'].includes(step.type) && step.durationMs >= 100)
      .map((step) => ({ kind: 'step' as const, startedAt: step.startedAt, step })),
  ].sort((left, right) => new Date(left.startedAt).getTime() - new Date(right.startedAt).getTime()), [report]);
  const selectedModelCall = useMemo(() => {
    const calls = report?.modelCalls || [];
    return calls.find((call) => call.id === selectedModelCallId)
      || calls.reduce<TaskExecutionReportModelCall | undefined>((slowest, call) => (
        !slowest || call.totalDurationMs > slowest.totalDurationMs ? call : slowest
      ), undefined);
  }, [report?.modelCalls, selectedModelCallId]);
  const modelCallGroups = useMemo(() => {
    const calls = report?.modelCalls || [];
    return calls.length > 0 ? [{ key: 'all', label: '全部调用', calls }] : [];
  }, [report?.modelCalls]);
  const selectedModelCallGroup = modelCallGroups.find((group) => group.key === selectedModelCallGroupKey);

  function handleBack() {
    const returnTo = searchParams.get('returnTo');
    navigate(returnTo && returnTo.startsWith('/') && !returnTo.startsWith('//')
      ? returnTo
      : runLayout ? `/runs/${taskId}` : `/admin/tasks/${taskId}`);
  }

  if (loading && !report) {
    return <ExecutionReportSkeleton className={pageClassName} onBack={embedded ? undefined : handleBack} />;
  }

  if (!report) {
    return (
      <div className={pageClassName}>
        <div className="execution-report-empty">
          <Empty description={failed ? '分析加载失败' : '暂无分析数据'} />
          <div className="execution-report-empty-actions">
            {!embedded && <Button icon={<CaretLeft size={14} />} onClick={handleBack}>返回</Button>}
            <Button icon={<ArrowClockwise size={14} weight="fill" />} onClick={() => void loadReport()}>重试</Button>
          </div>
        </div>
      </div>
    );
  }

  const cacheRate = ratio(report.cachedInputTokens, report.inputTokens);
  const measuredModelRequests = report.modelCalls.length > 0;
  const visibleRequestCount = Math.max(report.requestCount, report.modelCalls.length);
  const modelWallDuration = report.modelCalls.reduce((total, call) => total + call.totalDurationMs, 0);
  const commandCount = report.steps.filter((step) => step.type === 'COMMAND').length;

  return (
    <div className={pageClassName}>
      <section className="execution-report-surface">
        <header className="execution-report-head">
          <div className="execution-report-heading">
            <span className="execution-report-heading-icon"><ChartBar size={22} weight="fill" /></span>
            <div>
              <h1>分析报告</h1>
              <p>{report.title}</p>
            </div>
          </div>
          <div className="execution-report-actions">
            {!embedded && <Button size="small" icon={<CaretLeft size={14} />} onClick={handleBack}>返回</Button>}
            <Button size="small" loading={loading} icon={<ArrowClockwise size={14} weight="fill" />} onClick={() => void loadReport()}>刷新</Button>
          </div>
          <div className="execution-report-meta">
            <TaskStatusTag status={report.status} />
            <RuntimeModeTag runtimeCode={report.runtimeCode} runtimeModes={runtimeModes} />
            <span>{report.modelName || report.modelIdentifier || '模型未记录'}</span>
            <span>{formatTime(report.startedAt)} 至 {formatTime(report.endedAt)}</span>
          </div>
        </header>

        <main className="execution-report-body">
          <section className="execution-report-metrics">
            <Metric icon={<Clock size={18} weight="fill" />} label="总耗时" value={formatDurationMs(report.totalDurationMs)}
              detail={report.firstFeedbackMs == null ? '首次反馈未记录' : `首次反馈 ${formatDurationMs(report.firstFeedbackMs)}`} />
            <Metric
              icon={<Lightning size={18} weight="fill" />}
              label="模型调用"
              value={measuredModelRequests ? `${report.modelCalls.length} 次` : `${report.requestCount} 次`}
              detail={measuredModelRequests
                ? `${report.modelRoundCount || report.modelCalls.length} 轮 · 请求工具 ${report.toolCallCount || 0} 次\n累计 ${formatDurationMs(modelWallDuration)} · 平均 ${formatDurationMs(modelWallDuration / report.modelCalls.length)}`
                : '历史任务未采集逐次请求耗时'}
              tone="wait"
            />
            <Metric
              icon={<TerminalWindow size={18} weight="fill" />}
              label="工具执行"
              value={`${commandCount} 次`}
              detail={commandCount > 0
                ? `累计 ${formatDurationMs(report.commandExecutionDurationMs)} · 平均 ${formatDurationMs(report.commandExecutionDurationMs / commandCount)}`
                : '本任务未执行工具'}
              tone="command"
            />
            <Metric icon={<ChartBar size={18} weight="fill" />} label="Token 用量" value={formatTokenCount(report.totalTokens)}
              detail={`输入 ${formatTokenCount(report.inputTokens)} · 输出 ${formatTokenCount(report.outputTokens)}\n缓存命中 ${formatTokenCount(report.cachedInputTokens)} · 命中率 ${formatPercent(cacheRate)}`} tone="usage" />
            <Metric
              icon={<CurrencyCny size={18} weight="fill" />}
              label="费用估算"
              value={report.costAmount == null ? undefined : formatModelCost(report.costAmount, report.costCurrency)}
              emptyText="未计价"
              detail={report.costAmount == null
                ? '本任务没有价格快照'
                : `平均每次 ${formatModelCost(report.costAmount / Math.max(1, report.modelCalls.length || report.requestCount), report.costCurrency)}`}
              meta={report.costAmount == null ? undefined : priceTierText(report.priceTier)}
              tone="cost"
            />
          </section>

          <section className="execution-report-section execution-report-detail-tabs">
            <Tabs
              defaultActiveKey="model-calls"
              items={[
                {
                  key: 'model-calls',
                  label: <span>模型调用 <em>{report.modelCalls.length || report.requestCount}</em></span>,
                  children: measuredModelRequests ? (
                    <div className="execution-model-call-workspace">
                      <div className="execution-model-call-list">
                        {modelCallGroups.map((group) => (
                          <section className="execution-model-call-group" key={group.key}>
                            <button
                              type="button"
                              className={`execution-model-call-group-title${selectedModelCallGroup?.key === group.key ? ' is-selected' : ''}`}
                              onClick={() => {
                                setSelectedModelCallGroupKey(group.key);
                                setSelectedModelCallId(undefined);
                              }}
                            >
                              <span>{group.label}</span>
                              <em>{group.calls.length} 次</em>
                            </button>
                            <div className="execution-model-call-group-items" role="list">
                              {group.calls.map((call) => (
                                <ModelCallListRow
                                  key={call.id}
                                  call={call}
                                  selected={!selectedModelCallGroup && call.id === selectedModelCall?.id}
                                  onSelect={() => {
                                    setSelectedModelCallId(call.id);
                                    setSelectedModelCallGroupKey(undefined);
                                  }}
                                />
                              ))}
                            </div>
                          </section>
                        ))}
                      </div>
                      {selectedModelCallGroup
                        ? <ModelCallGroupDetail label={selectedModelCallGroup.label} calls={selectedModelCallGroup.calls} />
                        : selectedModelCall && <ModelCallDetail call={selectedModelCall} />}
                    </div>
                  ) : (
                    <div className="execution-report-limitation">
                      该任务执行时尚未采集逐次模型调用的输入 Token、首个响应和生成速度，只能根据事件间隔推算，不能据此判断网络或模型性能。
                    </div>
                  ),
                },
                {
                  key: 'operations',
                  label: <span>工具与平台 <em>{operationalSteps.length}</em></span>,
                  children: (
                    <div className="execution-report-tab-columns">
                      <div>
                        <SectionTitle title="工具与平台耗时" description="排除模型调用后的慢步骤" />
                        <div className="execution-slow-list">
                          {operationalSteps.length ? operationalSteps.map((step, index) => (
                            <div className="execution-slow-row" key={step.id}>
                              <span className="execution-slow-rank">{index + 1}</span>
                              <span className={`execution-step-dot execution-step-${step.type.toLowerCase()}`} />
                              <div className="execution-slow-content">
                                <strong>{step.title}</strong>
                                <span>{step.detail || '任务调度与执行'}</span>
                              </div>
                              <b>{formatDurationMs(step.durationMs)}</b>
                            </div>
                          )) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无工具或平台耗时步骤" />}
                        </div>
                      </div>
                    </div>
                  ),
                },
                {
                  key: 'timeline',
                  label: <span>时间线 <em>{timelineEntries.length}</em></span>,
                  children: (
                    <div className="execution-timeline-tab">
                      <div className="execution-timeline-axis">
                        <span>开始</span><span>50%</span><span>结束</span>
                      </div>
                      <div className="execution-timeline-list">
                        {timelineEntries.map((entry) => entry.kind === 'model'
                          ? <ModelCallTimelineRow key={`model-${entry.call.id}`} call={entry.call} report={report} />
                          : <TimelineRow key={entry.step.id} step={entry.step} report={report} />)}
                      </div>
                    </div>
                  ),
                },
              ]}
            />
          </section>

          {(report.compactionCount > 0 || report.duplicateCapabilityCallCount > 0) && (
            <div className="execution-report-notes">
              {report.compactionCount > 0 && <span>上下文压缩 {report.compactionCount} 次</span>}
              {report.duplicateCapabilityCallCount > 0 && <span>重复能力调用 {report.duplicateCapabilityCallCount} 次</span>}
            </div>
          )}
          <div className="execution-report-attribution-note">
            {measuredModelRequests
              ? report.modelTimingNote || '模型耗时由当前执行引擎采集；首个响应可能包含网络传输、上游排队和模型推理。'
              : '该任务执行时尚未采集模型请求边界，模型相关耗时仅能根据前后事件推算。'}
            <span> 本任务共 {visibleRequestCount} 次模型请求，消耗 {formatTokenCount(report.totalTokens)} Token，缓存命中 {formatPercent(cacheRate)}{report.costAmount == null
              ? '。' : `，估算费用 ${formatModelCost(report.costAmount, report.costCurrency)}。`}</span>
          </div>
        </main>
      </section>
    </div>
  );
}

function Metric({ icon, label, value, detail, meta, emptyText = '未记录', tone = 'default' }: {
  icon: React.ReactNode;
  label: string;
  value?: string;
  detail: string;
  meta?: string;
  emptyText?: string;
  tone?: 'default' | 'wait' | 'command' | 'usage' | 'cost';
}) {
  return (
    <div className={`execution-metric execution-metric-${tone}`}>
      <div className="execution-metric-label"><span>{icon}</span>{label}</div>
      {value ? <strong>{value}</strong> : <strong className="execution-metric-empty">{emptyText}</strong>}
      <p><span>{detail}</span>{meta && <em>{meta}</em>}</p>
    </div>
  );
}

function SectionTitle({ title, description }: { title: string; description: string }) {
  return <div className="execution-section-title"><h2>{title}</h2><span>{description}</span></div>;
}

function ModelCallListRow({ call, selected, onSelect }: {
  call: TaskExecutionReportModelCall;
  selected: boolean;
  onSelect: () => void;
}) {
  const diagnosisType = call.diagnosisType || 'NORMAL';
  const purpose = formatModelCallPurpose(call.purpose);
  const breakdown = tokenBreakdownData(call);
  return (
    <button
      type="button"
      role="listitem"
      className={`execution-model-call-option execution-diagnosis-${diagnosisType.toLowerCase()}${selected ? ' is-selected' : ''}`}
      onClick={onSelect}
    >
      <span className="execution-model-call-sequence">{call.sequence}</span>
      <span className="execution-model-call-option-main">
        <strong>{purpose.primary}</strong>
        <span>{purpose.secondary ? `${purpose.secondary} · ` : ''}{call.model || '模型未记录'} · {formatDurationMs(call.totalDurationMs)}</span>
        {breakdown.total > 0 && (
          <span className="execution-model-call-token-bar" aria-label="本次输入 Token 构成占比">
            {breakdown.items.filter((item) => item.value > 0).map((item) => (
              <Tooltip key={item.key} title={`${item.label} ${formatPercent(ratio(item.value, breakdown.total))}`}>
                <i className={`execution-token-part execution-token-part-${item.tone}`}
                  style={{ width: `${ratio(item.value, breakdown.total) * 100}%` }} />
              </Tooltip>
            ))}
          </span>
        )}
      </span>
      <span className="execution-model-call-option-diagnosis">{diagnosisNames[diagnosisType]}</span>
    </button>
  );
}

function ModelCallDetail({ call }: { call: TaskExecutionReportModelCall }) {
  const diagnosisType = call.diagnosisType || 'NORMAL';
  const observedTiming = call.modelTimingMode === 'OBSERVED';
  const cacheRate = call.cachedInputTokens == null || call.inputTokens == null
    ? undefined : ratio(call.cachedInputTokens, call.inputTokens);
  const responseKind = call.responseKind === 'TOOL_CALL' ? (call.toolRequestCount == null ? '请求工具' : `请求 ${call.toolRequestCount} 个工具`)
    : call.responseKind === 'ANSWER' ? '生成回答' : '响应类型未记录';
  const purpose = formatModelCallPurpose(call.purpose);
  return (
    <article className={`execution-model-call-detail execution-diagnosis-${diagnosisType.toLowerCase()}`}>
      <header className="execution-model-call-detail-head">
        <div>
          <span>第 {call.sequence} 次模型调用</span>
          <h3>{purpose.primary}</h3>
          <p>{purpose.secondary ? `${purpose.secondary} · ` : ''}{call.model || '模型未记录'} · {responseKind}</p>
        </div>
        <span className="execution-model-call-diagnosis">{diagnosisNames[diagnosisType]}</span>
      </header>
      <div className="execution-model-call-measures">
        <CallMeasure label="本次总耗时" value={formatDurationMs(call.totalDurationMs)} />
        <CallMeasure label={observedTiming ? '首个可观测响应' : '等待首个响应'}
          value={call.firstResponseMs == null ? undefined : formatDurationMs(call.firstResponseMs)} />
        <CallMeasure label={observedTiming ? '后续响应耗时' : '响应持续生成'}
          value={call.firstResponseAt ? formatDurationMs(call.generationMs) : undefined} />
        <CallMeasure label="输出速度"
          value={!observedTiming && call.outputTokensPerSecond != null ? `${formatDecimal(call.outputTokensPerSecond)} Token/秒` : undefined}
          emptyText={observedTiming ? '协议未提供' : undefined} />
      </div>
      <TokenBreakdown breakdown={call} actualInputTokens={call.inputTokens} />
      <div className="execution-model-call-usage">
        <CallMeasure label="输入 Token" value={call.inputTokens == null ? undefined : formatTokenCount(call.inputTokens)} />
        <CallMeasure label="缓存命中" value={call.cachedInputTokens == null || cacheRate == null
          ? undefined : `${formatTokenCount(call.cachedInputTokens)} · ${formatPercent(cacheRate)}`} />
        <CallMeasure label="输出 Token" value={call.outputTokens == null ? undefined : formatTokenCount(call.outputTokens)} />
        <CallMeasure label="推理 Token" value={call.reasoningOutputTokens == null ? undefined : formatTokenCount(call.reasoningOutputTokens)} />
        {call.costAmount != null && <CallMeasure label="本次费用" value={formatModelCost(call.costAmount, call.costCurrency)} meta={priceTierText(call.priceTier)} />}
        {call.costAmount != null && (
          <div className="execution-call-measure execution-cost-breakdown">
            <span>费用构成</span>
            <div className="execution-cost-parts">
              <div><em>命中</em><strong>{formatModelCost(call.cacheHitInputCost, call.costCurrency)}</strong></div>
              <div><em>未命中</em><strong>{formatModelCost(call.cacheMissInputCost, call.costCurrency)}</strong></div>
              <div><em>输出</em><strong>{formatModelCost(call.outputCost, call.costCurrency)}</strong></div>
            </div>
          </div>
        )}
        <CallMeasure label="上下文消息" value={call.messageCount == null ? undefined : `${call.messageCount} 条`} />
        <CallMeasure label="可用工具" value={call.toolDefinitionCount == null ? undefined : `${call.toolDefinitionCount} 个`} />
      </div>
      <div className="execution-model-call-explanation">
        <strong>{call.diagnosis || diagnosisNames[diagnosisType]}</strong>
        <p>{call.diagnosisDetail || '当前指标不足，暂时无法进一步归因。'}</p>
      </div>
      {call.errorMessage && <div className="execution-model-call-error">{call.errorMessage}</div>}
    </article>
  );
}

function ModelCallGroupDetail({ label, calls }: { label: string; calls: TaskExecutionReportModelCall[] }) {
  const requestCount = calls.length;
  const totalDurationMs = calls.reduce((total, call) => total + call.totalDurationMs, 0);
  const inputTokens = calls.reduce((total, call) => total + Number(call.inputTokens || 0), 0);
  const cachedInputTokens = calls.reduce((total, call) => total + Number(call.cachedInputTokens || 0), 0);
  const outputTokens = calls.reduce((total, call) => total + Number(call.outputTokens || 0), 0);
  const reasoningTokens = calls.reduce((total, call) => total + Number(call.reasoningOutputTokens || 0), 0);
  const totalTokens = calls.reduce((total, call) => total + Number(call.totalTokens ?? ((call.inputTokens || 0) + (call.outputTokens || 0))), 0);
  const firstResponseCalls = calls.filter((call) => call.firstResponseMs != null);
  const firstResponseMs = firstResponseCalls.reduce((total, call) => total + call.firstResponseMs, 0);
  const pricedCalls = calls.filter((call) => call.costAmount != null);
  const costAmount = pricedCalls.length
    ? pricedCalls.reduce((total, call) => total + Number(call.costAmount || 0), 0)
    : undefined;
  const cacheHitInputCost = pricedCalls.reduce((total, call) => total + Number(call.cacheHitInputCost || 0), 0);
  const cacheMissInputCost = pricedCalls.reduce((total, call) => total + Number(call.cacheMissInputCost || 0), 0);
  const outputCost = pricedCalls.reduce((total, call) => total + Number(call.outputCost || 0), 0);
  const costCurrency = pricedCalls.find((call) => call.costCurrency)?.costCurrency;
  const priceTiers = new Set(pricedCalls.map((call) => call.priceTier).filter(Boolean));
  const priceTier = priceTiers.size > 1 ? 'MIXED' : pricedCalls.find((call) => call.priceTier)?.priceTier;
  const models = Array.from(new Set(calls.map((call) => call.model).filter(Boolean))).join('、');

  return (
    <article className="execution-model-call-detail execution-model-call-group-detail">
      <header className="execution-model-call-detail-head">
        <div>
          <span>调用汇总</span>
          <h3>{label}</h3>
          <p>{models || '模型未记录'} · {formatTime(calls[0].startedAt)} 至 {formatTime(calls[calls.length - 1].endedAt)}</p>
        </div>
        <span className="execution-model-call-diagnosis">{requestCount} 次调用</span>
      </header>
      <div className="execution-model-call-measures">
        <CallMeasure label="请求" value={`${requestCount} 次`} />
        <CallMeasure label="Token 总量" value={formatTokenCount(totalTokens)}
          meta={`平均 ${formatTokenCount(totalTokens / requestCount)}`} />
        <CallMeasure label="调用总耗时" value={formatDurationMs(totalDurationMs)}
          meta={`平均 ${formatDurationMs(totalDurationMs / requestCount)}`} />
        <CallMeasure label="费用总额"
          value={costAmount == null ? undefined : formatModelCost(costAmount, costCurrency)}
          emptyText="未计价"
          meta={costAmount == null ? undefined : `平均 ${formatModelCost(costAmount / requestCount, costCurrency)} · ${priceTierText(priceTier)}`} />
      </div>
      <TokenBreakdown breakdown={sumTokenBreakdown(calls)} actualInputTokens={inputTokens} aggregate />
      <div className="execution-model-call-usage">
        <CallMeasure label="输入 Token" value={formatTokenCount(inputTokens)} meta={`平均 ${formatTokenCount(inputTokens / requestCount)}`} />
        <CallMeasure label="缓存命中" value={`${formatTokenCount(cachedInputTokens)} · ${formatPercent(ratio(cachedInputTokens, inputTokens))}`} />
        <CallMeasure label="输出 Token" value={formatTokenCount(outputTokens)} meta={`平均 ${formatTokenCount(outputTokens / requestCount)}`} />
        <CallMeasure label="推理 Token" value={formatTokenCount(reasoningTokens)} meta={`平均 ${formatTokenCount(reasoningTokens / requestCount)}`} />
        <CallMeasure label="首个响应" value={firstResponseCalls.length ? formatDurationMs(firstResponseMs) : undefined}
          meta={firstResponseCalls.length ? `平均 ${formatDurationMs(firstResponseMs / firstResponseCalls.length)}` : undefined} />
        <CallMeasure label="已计价请求" value={pricedCalls.length ? `${pricedCalls.length} 次` : undefined} emptyText="未计价" />
        {costAmount != null && (
          <div className="execution-call-measure execution-cost-breakdown">
            <span>费用构成</span>
            <div className="execution-cost-parts">
              <div><em>命中输入</em><strong>{formatModelCost(cacheHitInputCost, costCurrency)}</strong></div>
              <div><em>未命中输入</em><strong>{formatModelCost(cacheMissInputCost, costCurrency)}</strong></div>
              <div><em>输出</em><strong>{formatModelCost(outputCost, costCurrency)}</strong></div>
            </div>
          </div>
        )}
      </div>
    </article>
  );
}

type TokenBreakdownSource = Pick<TaskExecutionReportModelCall,
  | 'estimatedInputTokens'
  | 'systemInstructionTokens'
  | 'taskInstructionTokens'
  | 'mcpInstructionTokens'
  | 'toolSchemaTokens'
  | 'conversationTokens'
  | 'toolResultTokens'
  | 'imageTokens'>;

function TokenBreakdown({ breakdown, actualInputTokens, aggregate = false }: {
  breakdown: TokenBreakdownSource;
  actualInputTokens?: number;
  aggregate?: boolean;
}) {
  const { items, total: estimatedTotal } = tokenBreakdownData(breakdown);
  if (estimatedTotal <= 0) {
    return null;
  }
  return (
    <section className="execution-token-breakdown">
      <header>
        <div>
          <strong>{aggregate ? '输入 Token 构成汇总' : '本次输入 Token 构成'}</strong>
          <span>分类为本地估算，实际输入以供应商用量为准</span>
        </div>
        <div className="execution-token-breakdown-total">
          <span>估算 {formatTokenCount(estimatedTotal)}</span>
          {actualInputTokens != null && <strong>实际 {formatTokenCount(actualInputTokens)}</strong>}
        </div>
      </header>
      <div className="execution-token-breakdown-bar" aria-label="输入 Token 构成占比">
        {items.filter((item) => item.value > 0).map((item) => (
          <Tooltip key={item.key} title={`${item.label} ${formatTokenCount(item.value)} · ${formatPercent(ratio(item.value, estimatedTotal))}`}>
            <span className={`execution-token-part execution-token-part-${item.tone}`}
              style={{ width: `${ratio(item.value, estimatedTotal) * 100}%` }} />
          </Tooltip>
        ))}
      </div>
      <div className="execution-token-breakdown-legend">
        {items.map((item) => (
          <div key={item.key}>
            <i className={`execution-token-swatch execution-token-part-${item.tone}`} />
            <span>{item.label}</span>
            <strong>{formatTokenCount(item.value)}</strong>
            <em>{formatPercent(ratio(item.value, estimatedTotal))}</em>
          </div>
        ))}
      </div>
    </section>
  );
}

function tokenBreakdownData(breakdown: TokenBreakdownSource) {
  const items = tokenBreakdownItems.map((item) => ({
    ...item,
    value: Number(breakdown[item.key] || 0),
  }));
  return {
    items,
    total: Number(breakdown.estimatedInputTokens || items.reduce((total, item) => total + item.value, 0)),
  };
}

function formatModelCallPurpose(value: string) {
  const toolResult = value.match(/^处理[“"](.+?)[”"]结果(?:并(.+))?$/);
  if (toolResult) {
    return {
      primary: toolResult[1],
      secondary: `处理结果${toolResult[2] ? ` · ${toolResult[2].replace(/^生成/, '')}` : ''}`,
    };
  }
  const continued = value.match(/^根据[“"](.+?)[”"]继续分析$/);
  if (continued) {
    return { primary: continued[1], secondary: '继续分析' };
  }
  if (value === '分析问题并准备首次工具调用') {
    return { primary: '分析问题', secondary: '准备首次工具调用' };
  }
  return { primary: value, secondary: '' };
}

function sumTokenBreakdown(calls: TaskExecutionReportModelCall[]): TokenBreakdownSource {
  const sum = (key: keyof TokenBreakdownSource) => calls.reduce((total, call) => total + Number(call[key] || 0), 0);
  return {
    estimatedInputTokens: sum('estimatedInputTokens'),
    systemInstructionTokens: sum('systemInstructionTokens'),
    taskInstructionTokens: sum('taskInstructionTokens'),
    mcpInstructionTokens: sum('mcpInstructionTokens'),
    toolSchemaTokens: sum('toolSchemaTokens'),
    conversationTokens: sum('conversationTokens'),
    toolResultTokens: sum('toolResultTokens'),
    imageTokens: sum('imageTokens'),
  };
}

function CallMeasure({ label, value, meta, emptyText = '未采集' }: {
  label: string;
  value?: string;
  meta?: string;
  emptyText?: string;
}) {
  return (
    <div className="execution-call-measure">
      <span>{label}</span>
      {value ? (
        <div className="execution-call-measure-value">
          <strong>{value}</strong>
          {meta && <em>{meta}</em>}
        </div>
      ) : <em className="execution-call-measure-empty">{emptyText}</em>}
    </div>
  );
}

function priceTierText(tier?: string) {
  if (tier === 'MIXED') {
    return '混合时段';
  }
  return tier && tier !== 'STANDARD' ? tier : '常规价';
}

function TimelineRow({ step, report }: { step: TaskExecutionReportStep; report: TaskExecutionReport }) {
  const reportStart = new Date(report.startedAt).getTime();
  const total = Math.max(1, report.totalDurationMs);
  const left = Math.max(0, Math.min(100, ((new Date(step.startedAt).getTime() - reportStart) / total) * 100));
  const width = Math.max(0.5, Math.min(100 - left, (step.durationMs / total) * 100));
  return (
    <div className="execution-timeline-row">
      <div className="execution-timeline-label">
        <span className={`execution-step-dot execution-step-${step.type.toLowerCase()}`} />
        <div><strong>{step.title}</strong><span>任务执行</span></div>
      </div>
      <div className="execution-timeline-track">
        <Tooltip title={`${formatTime(step.startedAt)} - ${formatTime(step.endedAt)}${step.detail ? `\n${step.detail}` : ''}`}>
          <span className={`execution-timeline-bar execution-step-${step.type.toLowerCase()}`} style={{ left: `${left}%`, width: `${width}%` }} />
        </Tooltip>
      </div>
      <b>{formatDurationMs(step.durationMs)}</b>
    </div>
  );
}

function ModelCallTimelineRow({ call, report }: { call: TaskExecutionReportModelCall; report: TaskExecutionReport }) {
  const reportStart = new Date(report.startedAt).getTime();
  const total = Math.max(1, report.totalDurationMs);
  const left = Math.max(0, Math.min(100, ((new Date(call.startedAt).getTime() - reportStart) / total) * 100));
  const width = Math.max(0.5, Math.min(100 - left, (call.totalDurationMs / total) * 100));
  const diagnosisType = call.diagnosisType || 'NORMAL';
  const purpose = formatModelCallPurpose(call.purpose);
  return (
    <div className="execution-timeline-row">
      <div className="execution-timeline-label">
        <span className={`execution-step-dot execution-diagnosis-${diagnosisType.toLowerCase()}`} />
        <div><strong>第 {call.sequence} 次 · {purpose.primary}</strong><span>{purpose.secondary ? `${purpose.secondary} · ` : ''}{call.model || '模型未记录'}</span></div>
      </div>
      <div className="execution-timeline-track">
        <Tooltip title={`${formatTime(call.startedAt)} - ${formatTime(call.endedAt)}\n${call.diagnosis || diagnosisNames[diagnosisType]}`}>
          <span className={`execution-timeline-bar execution-diagnosis-${diagnosisType.toLowerCase()}`} style={{ left: `${left}%`, width: `${width}%` }} />
        </Tooltip>
      </div>
      <b>{formatDurationMs(call.totalDurationMs)}</b>
    </div>
  );
}

function ExecutionReportSkeleton({ className, onBack }: { className: string; onBack?: () => void }) {
  return (
    <div className={className}>
      <section className="execution-report-surface execution-report-skeleton">
        <header className="execution-report-head">
          <div className="execution-report-heading"><Skeleton.Avatar active shape="square" /><div><Skeleton.Input active size="small" /><Skeleton.Input active size="small" /></div></div>
          {onBack && <Button size="small" icon={<CaretLeft size={14} />} onClick={onBack}>返回</Button>}
        </header>
        <main className="execution-report-body">
          <section className="execution-report-metrics">{[1, 2, 3, 4, 5].map((item) => <div className="execution-metric" key={item}><Skeleton active paragraph={{ rows: 2 }} title={{ width: '45%' }} /></div>)}</section>
          <section className="execution-report-section"><Skeleton active paragraph={{ rows: 5 }} /></section>
          <div className="execution-report-columns"><section className="execution-report-section"><Skeleton active paragraph={{ rows: 6 }} /></section><section className="execution-report-section"><Skeleton active paragraph={{ rows: 6 }} /></section></div>
        </main>
      </section>
    </div>
  );
}

function ratio(value?: number, total?: number) {
  return total && total > 0 ? Math.max(0, Math.min(1, Number(value || 0) / total)) : 0;
}

function formatPercent(value: number) {
  return `${(value * 100).toFixed(value >= 0.1 ? 1 : 2).replace(/\.0+$/, '')}%`;
}

function formatDurationMs(value?: number) {
  const milliseconds = Math.round(Math.max(0, Number(value || 0)));
  if (milliseconds < 1000) {
    return `${milliseconds} 毫秒`;
  }
  const seconds = milliseconds / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(seconds >= 10 ? 1 : 2).replace(/\.0+$/, '')} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainSeconds = Math.round(seconds % 60);
  if (minutes < 60) {
    return `${minutes} 分 ${remainSeconds} 秒`;
  }
  const hours = Math.floor(minutes / 60);
  return `${hours} 小时 ${minutes % 60} 分`;
}

function formatDecimal(value: number) {
  return value.toFixed(value >= 10 ? 1 : 2).replace(/\.0+$/, '');
}
