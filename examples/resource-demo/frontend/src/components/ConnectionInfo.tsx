import { CopyOutlined } from '@ant-design/icons';
import { App, Button, Input, Modal, Skeleton } from 'antd';
import { useEffect, useState } from 'react';
import { demoApi } from '../api/demo';
import type { DemoInfo } from '../types';

interface ConnectionInfoProps {
  open: boolean;
  onClose: () => void;
}

export function ConnectionInfo({ open, onClose }: ConnectionInfoProps) {
  const { message } = App.useApp();
  const [info, setInfo] = useState<DemoInfo>();

  useEffect(() => {
    if (open && !info) {
      void demoApi.info().then(setInfo);
    }
  }, [open, info]);

  const copy = async (value: string) => {
    await navigator.clipboard.writeText(value);
    message.success('已复制');
  };

  const rows = info
    ? [
        ['项目服务地址', info.projectHubBaseUrl],
        ['文档服务地址', info.wikiBaseUrl],
        ['HTTP 日志地址', info.httpLogBaseUrl],
        ['访问令牌', info.token],
      ]
    : [];

  return (
    <Modal title="Lingxi 接入信息" open={open} onCancel={onClose} footer={null} width={680}>
      {!info ? (
        <Skeleton active paragraph={{ rows: 4 }} />
      ) : (
        <div className="pt-2">
          {rows.map(([label, value]) => (
            <div className="connection-grid" key={label}>
              <span className="text-sm text-slate-600">{label}</span>
              <Input value={value} readOnly />
              <Button icon={<CopyOutlined />} aria-label={`复制${label}`} onClick={() => void copy(value)} />
            </div>
          ))}
        </div>
      )}
    </Modal>
  );
}
