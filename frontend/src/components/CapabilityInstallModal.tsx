import { Alert, Button, Descriptions, Modal, Spin, Upload, message } from 'antd';
import { FileZip, Package as PackageIcon, UploadSimple } from '@phosphor-icons/react';
import { useState } from 'react';
import { inspectCapabilityPackage, installCapabilityPackage } from '../api/lingxi';
import type { AgentCapability, CapabilityPackageInspection } from '../types/api';
import { formatFileSize } from '../utils/format';
import { AppTag } from './AppTag';
import { RuntimeActionIconView } from './RuntimeActionIconView';

interface CapabilityInstallModalProps {
  open: boolean;
  onClose: () => void;
  onInstalled: (capability: AgentCapability) => Promise<void>;
}

/**
 * 上传、预览并确认安装或更新可信能力包。
 *
 * @param open 是否显示安装弹窗
 * @param onClose 关闭弹窗回调
 * @param onInstalled 安装或更新成功后返回能力的回调
 * @return 能力安装弹窗
 */
export function CapabilityInstallModal({ open, onClose, onInstalled }: CapabilityInstallModalProps) {
  const [fileName, setFileName] = useState('');
  const [inspection, setInspection] = useState<CapabilityPackageInspection>();
  const [inspecting, setInspecting] = useState(false);
  const [installing, setInstalling] = useState(false);

  function reset() {
    setFileName('');
    setInspection(undefined);
    setInspecting(false);
    setInstalling(false);
  }

  function close() {
    if (installing) {
      return;
    }
    reset();
    onClose();
  }

  async function inspect(file: File) {
    setFileName(file.name);
    setInspection(undefined);
    setInspecting(true);
    try {
      setInspection(await inspectCapabilityPackage(file));
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
      const capability = await installCapabilityPackage(inspection.stagingToken);
      message.success(inspection.update ? `${capability.name}已更新` : `${capability.name}已安装，启用后即可使用`);
      reset();
      await onInstalled(capability);
    } catch {
      return;
    } finally {
      setInstalling(false);
    }
  }

  return (
    <Modal
      title={<span className="capability-install-title"><PackageIcon size={19} weight="fill" />{inspection?.update ? '更新能力' : '安装能力'}</span>}
      open={open}
      onCancel={close}
      width={760}
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
            <div className="capability-package-upload-title">选择能力安装包</div>
            <div className="capability-package-upload-description">支持不超过 20MB 的 ZIP 文件</div>
          </div>
        </Upload.Dragger>
      ) : null}

      {inspecting ? (
        <div className="capability-package-loading">
          <Spin />
          <span>正在校验 {fileName}</span>
        </div>
      ) : null}

      {inspection ? (
        <div className="capability-package-preview">
          <div className="capability-package-heading">
            <FileZip size={30} weight="duotone" />
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
            <Descriptions.Item label="运行脚本">{inspection.runtimeIncluded ? '包含' : '不包含'}</Descriptions.Item>
            <Descriptions.Item label="接入参数">{inspection.configParameterCount}</Descriptions.Item>
            <Descriptions.Item label="任务参数">{inspection.parameterCount}</Descriptions.Item>
            <Descriptions.Item label="使用说明">{inspection.guideCount}</Descriptions.Item>
            <Descriptions.Item label="Python 依赖">{inspection.requirements.length}</Descriptions.Item>
            <Descriptions.Item label="SHA-256" span={2}><code className="capability-package-hash">{inspection.packageHash}</code></Descriptions.Item>
          </Descriptions>

          <section className="capability-package-section">
            <div className="capability-package-section-title">命令</div>
            {inspection.commands.length ? inspection.commands.map((command) => (
              <div className="capability-package-command" key={`${command.code}-${command.command}`}>
                <div className="capability-package-command-name">
                  <RuntimeActionIconView icon={command.icon || 'WRENCH'} size={15} />
                  <span>{command.name || command.code}</span>
                </div>
                <code>{command.command}</code>
                {command.description ? <div>{command.description}</div> : null}
              </div>
            )) : <div className="capability-package-empty">不包含运行命令</div>}
          </section>

          {inspection.requirements.length ? (
            <section className="capability-package-section">
              <div className="capability-package-section-title">Python 依赖</div>
              <div className="capability-package-requirements">
                {inspection.requirements.map((requirement) => <code key={requirement}>{requirement}</code>)}
              </div>
            </section>
          ) : null}

          <Alert
            type="warning"
            showIcon
            message={inspection.update
              ? '更新将替换该外置能力的包文件，并保留启用状态、场景绑定和已有接入配置。请确认安装包来源可信。'
              : '能力包可能包含可执行脚本，请仅安装来源可信的文件。安装后默认关闭，完成接入配置后再启用。'}
          />
        </div>
      ) : null}
    </Modal>
  );
}
