import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, InputNumber, Segmented, Select, message } from 'antd';
import '../styles/settings.css';
import { ArrowClockwise, ArrowSquareOut, Rows, Sidebar } from '@phosphor-icons/react';
import {
  getAgentRuntimes,
  getRuntimeConfig,
  getRuntimeInstallStatus,
  getRuntimeMaintenanceStatus,
  installRuntime,
  saveRuntimeConfig,
} from '../api/lingxi';
import type {
  AgentRuntimeDescriptor,
  CliInstallStatus,
  RuntimeConfig,
  RuntimeConfigRequest,
  RuntimeMaintenanceOperation,
  RuntimeMaintenanceStatus,
} from '../types/api';
import { PageHeaderTitle } from '../components/PageHeaderTitle';
import { formatTime } from '../utils/format';
import { invalidateRuntimeModes } from '../hooks/useRuntimeModes';
import {
  ADMIN_MENU_LAYOUT_EVENT,
  ADMIN_MENU_LAYOUT_KEY,
  type AdminMenuLayout,
} from '../utils/adminMenuLayout';

export function SystemSettingsPage() {
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimeConfig>();
  const [agentRuntimes, setAgentRuntimes] = useState<AgentRuntimeDescriptor[]>([]);
  const [maintenanceRuntimeCode, setMaintenanceRuntimeCode] = useState<string>();
  const [maintenanceStatus, setMaintenanceStatus] = useState<RuntimeMaintenanceStatus>();
  const [installStatus, setInstallStatus] = useState<RuntimeMaintenanceOperation>();
  const [showInstallResult, setShowInstallResult] = useState(false);
  const [saving, setSaving] = useState(false);
  const [adminMenuLayout, setAdminMenuLayout] = useState<AdminMenuLayout>(
    () => window.localStorage.getItem(ADMIN_MENU_LAYOUT_KEY) === 'top' ? 'top' : 'side',
  );
  const [form] = Form.useForm<RuntimeConfigRequest>();
  const maintenanceRuntimes = useMemo(
    () => agentRuntimes.filter((item) => item.maintenanceSupported),
    [agentRuntimes],
  );
  const selectedRuntime = maintenanceRuntimes.find((item) => item.code === maintenanceRuntimeCode);

  useEffect(() => {
    void loadAll();
  }, []);

  useEffect(() => {
    if (!selectedRuntime) {
      setMaintenanceStatus(undefined);
      setInstallStatus(undefined);
      return;
    }
    void loadMaintenance(selectedRuntime.code);
  }, [selectedRuntime?.code]);

  useEffect(() => {
    if (installStatus?.status !== 'RUNNING' || !selectedRuntime) return;
    const timer = window.setInterval(() => refreshInstallStatus(selectedRuntime.code), 2000);
    return () => window.clearInterval(timer);
  }, [installStatus?.status, selectedRuntime?.code]);

  async function loadAll() {
    const [runtimes, config] = await Promise.all([
      getAgentRuntimes(),
      getRuntimeConfig(),
    ]);
    setAgentRuntimes(runtimes);
    setRuntimeConfig(config);
    setMaintenanceRuntimeCode((current) => runtimes.some((item) => item.maintenanceSupported && item.code === current)
      ? current
      : runtimes.find((item) => item.maintenanceSupported)?.code);
    form.setFieldsValue({
      maxTaskConcurrency: config.maxTaskConcurrency || 2,
      taskExecutionTimeoutMinutes: config.taskExecutionTimeoutMinutes || 60,
      globalDailyTokenLimit: config.globalDailyTokenLimit,
      globalWeeklyTokenLimit: config.globalWeeklyTokenLimit,
      globalMonthlyTokenLimit: config.globalMonthlyTokenLimit,
      globalBoundaryPrompt: config.globalBoundaryPrompt,
    });
  }

  async function saveSettings() {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const result = await saveRuntimeConfig(values);
      setRuntimeConfig(result);
      invalidateRuntimeModes();
      setAgentRuntimes(await getAgentRuntimes());
      message.success('系统设置已保存');
    } finally {
      setSaving(false);
    }
  }

  function changeAdminMenuLayout(layout: AdminMenuLayout) {
    window.localStorage.setItem(ADMIN_MENU_LAYOUT_KEY, layout);
    setAdminMenuLayout(layout);
    window.dispatchEvent(new Event(ADMIN_MENU_LAYOUT_EVENT));
  }

  async function loadMaintenance(runtimeCode: string) {
    try {
      const [status, operation] = await Promise.all([
        getRuntimeMaintenanceStatus(runtimeCode),
        getRuntimeInstallStatus(runtimeCode),
      ]);
      setMaintenanceStatus(status);
      setInstallStatus(operation);
      setShowInstallResult(operation.status === 'RUNNING');
    } catch {
      setMaintenanceStatus(undefined);
      setInstallStatus(undefined);
      setShowInstallResult(false);
    }
  }

  async function handleInstall() {
    if (!selectedRuntime) return;
    const result = await installRuntime(selectedRuntime.code);
    setShowInstallResult(true);
    setInstallStatus(result);
    message.success(maintenanceStatus?.available ? '已开始升级' : '已开始安装');
    setMaintenanceStatus(await getRuntimeMaintenanceStatus(selectedRuntime.code));
  }

  async function refreshInstallStatus(runtimeCode: string) {
    const result = await getRuntimeInstallStatus(runtimeCode);
    setInstallStatus(result);
    setShowInstallResult(true);
    if (result.status !== 'RUNNING') {
      setMaintenanceStatus(await getRuntimeMaintenanceStatus(runtimeCode));
    }
  }

  const installLogText = [installStatus?.stderrText, installStatus?.stdoutText].filter(Boolean).join('\n').trim();
  const installRunning = installStatus?.status === 'RUNNING';
  const installActionText = installRunning
    ? (maintenanceStatus?.available ? '升级中' : '安装中')
    : !maintenanceStatus?.available
      ? selectedRuntime?.maintenancePresentation?.installActionLabel || '安装执行引擎'
      : maintenanceStatus.updateAvailable && maintenanceStatus.latestVersion
        ? `升级到 ${maintenanceStatus.latestVersion}`
        : '重新安装';

  return (
    <div className="settings-page">
      <section className="settings-section rounded-md bg-white p-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 text-base font-semibold">
            <PageHeaderTitle fallback="系统设置" path="/admin/settings" icon="settings" tone="slate" />
          </div>
          <div className="flex gap-2">
            <Button icon={<ArrowClockwise size={16} weight="bold" />} onClick={loadAll}>刷新</Button>
            <Button type="primary" loading={saving} onClick={saveSettings}>保存设置</Button>
          </div>
        </div>
        <div className="settings-overview-grid settings-overview-grid-compact">
          <InfoBox label="平台并行度" value={`${runtimeConfig?.maxTaskConcurrency || 2}`} />
          <InfoBox label="任务执行时限" value={`${runtimeConfig?.taskExecutionTimeoutMinutes || 60} 分钟`} />
          <InfoBox label="更新时间" value={formatTime(runtimeConfig?.updatedAt)} />
        </div>

        <Form className="settings-main-form" form={form} layout="vertical">
          <section className="settings-config-section settings-layout-section">
            <div className="settings-config-section-heading">界面布局</div>
            <div className="settings-config-section-caption">设置管理端路由菜单的展示位置。</div>
            <Segmented<AdminMenuLayout>
              className="app-emphasis-segmented settings-layout-segmented"
              block
              size="large"
              value={adminMenuLayout}
              onChange={changeAdminMenuLayout}
              options={[
                {
                  value: 'side',
                  label: <span className="settings-layout-option"><Sidebar size={18} /><span>左侧菜单</span></span>,
                },
                {
                  value: 'top',
                  label: <span className="settings-layout-option"><Rows size={18} /><span>顶部菜单</span></span>,
                },
              ]}
            />
          </section>
          <section className="settings-config-section">
            <div className="settings-config-section-heading">执行策略</div>
            <div className="settings-form-grid">
              <Form.Item label="平台并行度" name="maxTaskConcurrency" rules={[{ required: true, message: '请输入平台并行度' }]}>
                <InputNumber className="w-full" min={1} max={20} precision={0} />
              </Form.Item>
              <Form.Item label="任务最长执行时间" name="taskExecutionTimeoutMinutes" rules={[{ required: true, message: '请输入任务最长执行时间' }]}>
                <InputNumber className="w-full" min={5} max={1440} precision={0} addonAfter="分钟" />
              </Form.Item>
              <Form.Item className="settings-field-wide" label="全局边界" name="globalBoundaryPrompt">
                <Input.TextArea rows={7} placeholder="请输入平台通用边界" />
              </Form.Item>
            </div>
          </section>

          <section className="settings-config-section">
            <div className="settings-config-section-heading">全局用量限制</div>
            <div className="settings-config-section-caption">用户单独配置的额度优先于这里的全局默认值。</div>
            <div className="settings-quota-grid">
              <Form.Item label="每日 Token 配额" name="globalDailyTokenLimit"><InputNumber className="w-full" min={1} precision={0} controls={false} placeholder="不限额" /></Form.Item>
              <Form.Item label="每周 Token 配额" name="globalWeeklyTokenLimit"><InputNumber className="w-full" min={1} precision={0} controls={false} placeholder="不限额" /></Form.Item>
              <Form.Item label="每月 Token 配额" name="globalMonthlyTokenLimit"><InputNumber className="w-full" min={1} precision={0} controls={false} placeholder="不限额" /></Form.Item>
            </div>
          </section>
        </Form>

        {!!maintenanceRuntimes.length && (
          <section className="settings-config-section">
            <div className="settings-model-toolbar">
              <div>
                <div className="settings-config-section-heading">{selectedRuntime?.maintenancePresentation?.title || '执行引擎维护'}</div>
                <div className="settings-config-section-caption">{selectedRuntime?.maintenancePresentation?.description || '维护执行引擎的安装状态和版本。'}</div>
              </div>
              {maintenanceRuntimes.length > 1 && (
                <Select value={maintenanceRuntimeCode} options={maintenanceRuntimes.map((item) => ({ label: item.name, value: item.code }))} onChange={setMaintenanceRuntimeCode} />
              )}
            </div>
            <div className="settings-engine-pane">
              <div className="settings-engine-meta">
                <InfoBox label="当前版本" value={maintenanceStatus?.currentVersion || maintenanceStatus?.versionText || '-'} />
                <InfoBox label="最新稳定版" value={maintenanceStatus?.latestVersion || '未获取'} />
                <InfoBox label="安装状态" value={installStatusText(installStatus?.status, maintenanceStatus?.available)} />
              </div>
              {!maintenanceStatus?.available && maintenanceStatus?.errorText && <Alert type="warning" showIcon message={selectedRuntime?.maintenancePresentation?.unavailableTitle || '执行引擎不可用'} description={maintenanceStatus.errorText} />}
              {showInstallResult && installStatus?.status === 'FAILED' && <Alert type="error" showIcon message="维护失败" description={installLogText || '未返回错误输出'} />}
              {showInstallResult && installStatus?.status === 'RUNNING' && installLogText && <pre className="settings-log max-h-48 overflow-auto rounded-md p-3 text-xs leading-5">{installLogText}</pre>}
              <div className="flex flex-wrap items-center gap-2">
                <Button type={maintenanceStatus?.updateAvailable ? 'primary' : 'default'} loading={installRunning} onClick={handleInstall}>{installActionText}</Button>
                {maintenanceStatus?.releaseUrl && <a className="inline-flex items-center gap-1 text-sm text-neutral-500 hover:text-neutral-800" href={maintenanceStatus.releaseUrl} target="_blank" rel="noreferrer">查看更新 <ArrowSquareOut size={14} weight="bold" /></a>}
                {maintenanceStatus?.updateCheckError && <span className="text-xs text-neutral-400">{maintenanceStatus.updateCheckError}</span>}
              </div>
            </div>
          </section>
        )}
      </section>

    </div>
  );
}

function InfoBox({ label, value }: { label: string; value: string }) {
  return <div className="settings-info-box p-3"><div className="settings-info-label text-xs">{label}</div><div className="mt-1 truncate text-sm font-medium" title={value}>{value}</div></div>;
}

function installStatusText(status?: CliInstallStatus, available?: boolean) {
  if (status === 'RUNNING') return available ? '升级中' : '安装中';
  if (status === 'FAILED') return available ? '升级失败，当前版本可用' : '安装失败';
  return available ? '已安装' : status ? '未安装' : '-';
}
