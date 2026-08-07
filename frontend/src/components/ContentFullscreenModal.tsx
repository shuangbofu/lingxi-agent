import type { ReactNode } from 'react';
import { Modal } from 'antd';

export function ContentFullscreenModal({
  open,
  title,
  children,
  onClose,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  return (
    <Modal
      className="content-fullscreen-modal"
      destroyOnHidden
      footer={null}
      open={open}
      title={title}
      width="calc(100vw - 24px)"
      onCancel={onClose}
    >
      {children}
    </Modal>
  );
}
