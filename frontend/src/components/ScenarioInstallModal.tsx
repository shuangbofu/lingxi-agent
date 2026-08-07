import { Alert, Button, Descriptions, Modal, Spin, Upload, message } from 'antd';
import { FileZip, Package as PackageIcon, UploadSimple } from '@phosphor-icons/react';
import { useState } from 'react';
import { inspectScenarioPackage, installScenarioPackage } from '../api/lingxi';
import type { AgentScenario, ScenarioPackageInspection } from '../types/api';
import { formatFileSize } from '../utils/format';
import { AppTag } from './AppTag';

interface ScenarioInstallModalProps {
  open: boolean;
  onClose: () => void;
  onInstalled: (scenario: AgentScenario) => Promise<void>;
}

/**
 * 上传、预览并确认安装或更新单个场景包。
 *
 * @param open 是否显示安装弹窗
 * @param onClose 关闭弹窗回调
 * @param onInstalled 安装或更新完成回调
 * @return 场景安装弹窗
 */
export function ScenarioInstallModal({ open, onClose, onInstalled }: ScenarioInstallModalProps) {
  const [fileName, setFileName] = useState('');
  const [inspection, setInspection] = useState<ScenarioPackageInspection>();
  const [inspecting, setInspecting] = useState(false);
  const [installing, setInstalling] = useState(false);

  function reset() {
    setFileName('');
    setInspection(undefined);
    setInspecting(false);
    setInstalling(false);
  }

  function close() {
    if (!installing) {
      reset();
      onClose();
    }
  }

  async function inspect(file: File) {
    setFileName(file.name);
    setInspection(undefined);
    setInspecting(true);
    try {
      setInspection(await inspectScenarioPackage(file));
    } catch {
      return Upload.LIST_IGNORE;
    } finally {
      setInspecting(false);
    }
    return Upload.LIST_IGNORE;
  }

  async function install() {
    if (!inspection) {
      return;
    }
    setInstalling(true);
    try {
      const scenario = await installScenarioPackage(inspection.stagingToken);
      message.success(inspection.update ? `${scenario.name}已更新` : `${scenario.name}已安装`);
      reset();
      await onInstalled(scenario);
    } finally {
      setInstalling(false);
    }
  }

  return (
    <Modal
      title={<span className="capability-install-title"><PackageIcon size={19} weight="fill" />{inspection?.update ? '更新场景' : '安装场景'}</span>}
      open={open}
      onCancel={close}
      width={700}
      destroyOnClose
      footer={[
        <Button key="cancel" onClick={close} disabled={installing}>取消</Button>,
        <Button key="install" type="primary" loading={installing} disabled={!inspection || inspecting} onClick={install}>
          {inspection?.update ? '确认更新' : '确认安装'}
        </Button>,
      ]}
    >
      {!inspection && !inspecting ? (
        <Upload.Dragger
          accept=".zip,application/zip"
          maxCount={1}
          showUploadList={false}
          beforeUpload={inspect}
          className="capability-package-upload"
        >
          <div className="capability-package-upload-content">
            <UploadSimple size={28} weight="duotone" />
            <div className="capability-package-upload-title">选择场景安装包</div>
            <div className="capability-package-upload-description">支持不超过 10MB 的 ZIP 文件</div>
          </div>
        </Upload.Dragger>
      ) : null}

      {inspecting ? (
        <div className="capability-package-loading"><Spin /><span>正在校验 {fileName}</span></div>
      ) : null}

      {inspection ? (
        <div className="capability-package-preview">
          <div className="capability-package-heading">
            <FileZip size={30} weight="duotone" color={inspection.color} />
            <div className="min-w-0 flex-1">
              <div className="capability-package-name">{inspection.name}</div>
              <div className="capability-package-code">{inspection.code}</div>
            </div>
            <Button size="small" type="text" onClick={reset}>重新选择</Button>
            <AppTag tone="blue">
              {inspection.update && inspection.currentVersion
                ? `v${inspection.currentVersion} -> v${inspection.version}`
                : `v${inspection.version}`}
            </AppTag>
          </div>
          {inspection.description ? <p className="capability-package-description">{inspection.description}</p> : null}
          <Descriptions size="small" column={2} className="capability-package-meta">
            <Descriptions.Item label="安装包">{formatFileSize(inspection.packageSize)}</Descriptions.Item>
            <Descriptions.Item label="场景参数">{inspection.parameterCount}</Descriptions.Item>
            <Descriptions.Item label="关联能力" span={2}>
              <div className="flex flex-wrap gap-1">
                {inspection.capabilities.length
                  ? inspection.capabilities.map((code) => <AppTag key={code}>{code}</AppTag>)
                  : <span>无</span>}
              </div>
            </Descriptions.Item>
            <Descriptions.Item label="SHA-256" span={2}><code className="capability-package-hash">{inspection.packageHash}</code></Descriptions.Item>
          </Descriptions>
          <Alert
            type="warning"
            showIcon
            message={inspection.update
              ? '更新会替换场景定义和图标，并保留平台内的启用状态及排序。'
              : '场景安装后会按包内定义显示；未安装的关联能力不会自动执行。'}
          />
        </div>
      ) : null}
    </Modal>
  );
}
