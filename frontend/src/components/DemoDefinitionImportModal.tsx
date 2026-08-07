import { Alert, Button, Modal, Progress } from 'antd';
import { CheckCircle, CircleNotch, Package as PackageIcon, WarningCircle } from '@phosphor-icons/react';
import { useEffect, useMemo, useState } from 'react';
import {
  createCapabilityConfig,
  inspectCapabilityPackage,
  inspectScenarioPackage,
  installCapabilityPackage,
  installScenarioPackage,
  pageCapabilityConfigs,
  updateCapability,
  updateCapabilityConfig,
} from '../api/lingxi';
import type { AgentCapability, CapabilityConfigSaveRequest } from '../types/api';
import { AppTag } from './AppTag';

export interface DemoImportCapability {
  code: string;
  name: string;
  packageUrl: string;
}

export interface DemoImportScenario {
  code: string;
  name: string;
  version?: string;
  color?: string;
  packageUrl: string;
}

export interface DemoImportConfiguration {
  capabilityCode: string;
  name: string;
  description?: string;
  config: Record<string, string | number | boolean | null>;
}

export interface DemoDefinitionImportOffer {
  type: 'LINGXI_DEFINITION_IMPORT_OFFER';
  sourceName: string;
  capabilities: DemoImportCapability[];
  configurations: DemoImportConfiguration[];
  scenarios: DemoImportScenario[];
}

interface DemoDefinitionImportModalProps {
  offer?: DemoDefinitionImportOffer;
  sourceOrigin?: string;
  onClose: () => void;
  onInstalled: () => Promise<void>;
}

type ImportStatus = 'waiting' | 'installing' | 'success' | 'failed';

interface ImportItemStatus {
  key: string;
  name: string;
  kind: 'capability' | 'scenario';
  status: ImportStatus;
  detail?: string;
}

/**
 * 在 Lingxi 当前管理员登录态中确认并执行 Demo 选择的能力、配置和场景安装。
 *
 * @param offer Demo 通过 postMessage 提交的安装清单
 * @param sourceOrigin 发起消息的 Demo 来源，用于限制安装包下载域名
 * @param onClose 关闭确认弹窗
 * @param onInstalled 全部安装任务结束后的列表刷新回调
 * @return Demo 批量安装确认弹窗
 */
export function DemoDefinitionImportModal({ offer, sourceOrigin, onClose, onInstalled }: DemoDefinitionImportModalProps) {
  const [installing, setInstalling] = useState(false);
  const [completed, setCompleted] = useState(false);
  const [items, setItems] = useState<ImportItemStatus[]>([]);

  useEffect(() => {
    setInstalling(false);
    setCompleted(false);
    setItems([
      ...(offer?.capabilities || []).map((item) => ({ key: `capability:${item.code}`, name: item.name, kind: 'capability' as const, status: 'waiting' as const })),
      ...(offer?.scenarios || []).map((item) => ({ key: `scenario:${item.code}`, name: item.name, kind: 'scenario' as const, status: 'waiting' as const })),
    ]);
  }, [offer]);

  const successCount = items.filter((item) => item.status === 'success').length;
  const failedCount = items.filter((item) => item.status === 'failed').length;
  const progress = items.length ? Math.round(((successCount + failedCount) / items.length) * 100) : 0;
  const configurationCodes = useMemo(() => new Set((offer?.configurations || []).map((item) => item.capabilityCode)), [offer]);

  function updateStatus(key: string, status: ImportStatus, detail?: string) {
    setItems((current) => current.map((item) => item.key === key ? { ...item, status, detail } : item));
  }

  async function installAll() {
    if (!offer || !sourceOrigin) {
      return;
    }
    setInstalling(true);
    setCompleted(false);

    // 能力必须先安装并配置，场景落库后才能立即调用这些能力。
    for (const item of offer.capabilities) {
      const key = `capability:${item.code}`;
      updateStatus(key, 'installing', '正在校验能力包');
      try {
        const file = await downloadPackage(item.packageUrl, sourceOrigin, `${item.code}.zip`);
        const inspection = await inspectCapabilityPackage(file);
        if (inspection.code !== item.code) {
          throw new Error(`能力编码不一致：${inspection.code}`);
        }
        const installed = await installCapabilityPackage(inspection.stagingToken);
        const enabled = installed.enabled ? installed : await enableCapability(installed);
        const configurations = offer.configurations.filter((configuration) => configuration.capabilityCode === item.code);
        for (const configuration of configurations) {
          await saveConfiguration(enabled, configuration);
        }
        updateStatus(key, 'success', configurations.length ? `已安装并写入 ${configurations.length} 个接入配置` : '已安装并启用');
      } catch (error) {
        updateStatus(key, 'failed', errorText(error));
      }
    }

    for (const item of offer.scenarios) {
      const key = `scenario:${item.code}`;
      updateStatus(key, 'installing', '正在校验场景包');
      try {
        const file = await downloadPackage(item.packageUrl, sourceOrigin, `${item.code}.zip`);
        const inspection = await inspectScenarioPackage(file);
        if (inspection.code !== item.code) {
          throw new Error(`场景编码不一致：${inspection.code}`);
        }
        await installScenarioPackage(inspection.stagingToken);
        updateStatus(key, 'success', inspection.update ? `已更新至 v${inspection.version}` : `已安装 v${inspection.version}`);
      } catch (error) {
        updateStatus(key, 'failed', errorText(error));
      }
    }
    setInstalling(false);
    setCompleted(true);
    await onInstalled();
  }

  return (
    <Modal
      title={<span className="capability-install-title"><PackageIcon size={19} weight="fill" />导入 Demo 演示内容</span>}
      open={!!offer}
      width={720}
      destroyOnClose
      closable={!installing}
      maskClosable={!installing}
      onCancel={onClose}
      footer={[
        <Button key="cancel" disabled={installing} onClick={onClose}>{completed ? '关闭' : '取消'}</Button>,
        <Button key="install" type="primary" loading={installing} disabled={!items.length || completed} onClick={installAll}>确认导入</Button>,
      ]}
    >
      <Alert
        className="mb-3"
        type="warning"
        showIcon
        message={`来源：${offer?.sourceName || 'Demo'}（${sourceOrigin || '-'}）`}
        description="所选能力包包含可执行脚本。仅确认该 Demo 地址可信时继续；Lingxi 不会把登录信息发送给 Demo。"
      />
      <div className="mb-2 flex flex-wrap items-center gap-2 text-sm text-neutral-600">
        <span>能力 {offer?.capabilities.length || 0} 个</span>
        <span>场景 {offer?.scenarios.length || 0} 个</span>
        {configurationCodes.size ? <span>接入配置 {offer?.configurations.length || 0} 个</span> : null}
      </div>
      {installing || completed ? <Progress className="mb-2" percent={progress} status={failedCount ? 'exception' : undefined} /> : null}
      <div className="max-h-[390px] overflow-y-auto border-t border-neutral-200">
        {items.map((item) => (
          <div className="flex min-h-12 items-center gap-3 border-b border-neutral-100 px-1 py-2" key={item.key}>
            <ImportStatusIcon status={item.status} />
            <div className="min-w-0 flex-1">
              <div className="font-medium text-neutral-800">{item.name}</div>
              {item.detail ? <div className={item.status === 'failed' ? 'text-xs text-red-600' : 'text-xs text-neutral-500'}>{item.detail}</div> : null}
            </div>
            <AppTag tone={item.kind === 'capability' ? 'blue' : 'violet'}>{item.kind === 'capability' ? '能力' : '场景'}</AppTag>
          </div>
        ))}
      </div>
    </Modal>
  );
}

function ImportStatusIcon({ status }: { status: ImportStatus }) {
  if (status === 'installing') {
    return <CircleNotch className="animate-spin text-blue-600" size={19} />;
  }
  if (status === 'success') {
    return <CheckCircle className="text-emerald-600" size={19} weight="fill" />;
  }
  if (status === 'failed') {
    return <WarningCircle className="text-red-600" size={19} weight="fill" />;
  }
  return <span className="h-[19px] w-[19px] rounded-full border border-neutral-300" />;
}

async function downloadPackage(packageUrl: string, sourceOrigin: string, fileName: string) {
  const url = new URL(packageUrl);
  if (!['http:', 'https:'].includes(url.protocol) || url.origin !== sourceOrigin) {
    throw new Error('安装包地址与 Demo 来源不一致');
  }
  const response = await fetch(url.toString(), { credentials: 'omit' });
  if (!response.ok) {
    throw new Error(`安装包下载失败（${response.status}）`);
  }
  return new File([await response.blob()], fileName, { type: 'application/zip' });
}

async function enableCapability(capability: AgentCapability) {
  return updateCapability(capability.code, {
    enabled: true,
  });
}

async function saveConfiguration(capability: AgentCapability, configuration: DemoImportConfiguration) {
  const payload: CapabilityConfigSaveRequest = {
    name: configuration.name,
    capabilityCode: capability.code,
    description: configuration.description,
    config: configuration.config,
    enabled: true,
  };
  const existing = (await pageCapabilityConfigs(1, 100, capability.code)).records.find((item) => item.name === configuration.name);
  if (existing) {
    await updateCapabilityConfig(existing.id, payload);
  } else {
    await createCapabilityConfig(payload);
  }
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : '导入失败';
}
