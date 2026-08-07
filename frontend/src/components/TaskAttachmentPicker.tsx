import { useEffect, useRef, useState } from 'react';
import { FileText, Paperclip, SpinnerGap, VideoCamera, X } from '@phosphor-icons/react';
import { Modal, Tooltip, message } from 'antd';
import { getTaskAttachmentText, uploadTaskAttachment } from '../api/lingxi';
import type { TaskAttachment } from '../types/api';

const MAX_ATTACHMENT_COUNT = 5;
const MAX_ATTACHMENT_SIZE = 50 * 1024 * 1024;

interface TaskAttachmentPickerProps {
  attachments: TaskAttachment[];
  onChange: (attachments: TaskAttachment[]) => void;
  disabled?: boolean;
  floating?: boolean;
}

interface TaskAttachmentPreviewListProps {
  attachments: TaskAttachment[];
  onRemove?: (attachmentId: string) => void;
  compact?: boolean;
}

export function TaskAttachmentPicker({ attachments, onChange, disabled, floating }: TaskAttachmentPickerProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const regularAttachmentCount = attachments.filter((attachment) => attachment.inputKind !== 'user-input').length;

  async function handleFiles(files: FileList | null) {
    const selected = Array.from(files || []);
    if (selected.length === 0) {
      return;
    }
    if (regularAttachmentCount + selected.length > MAX_ATTACHMENT_COUNT) {
      message.warning(`每次最多上传 ${MAX_ATTACHMENT_COUNT} 个附件`);
      return;
    }
    const invalidType = selected.find((file) => !isSupportedFile(file));
    if (invalidType) {
      message.warning('仅支持常见图片或视频格式，不支持 SVG');
      return;
    }
    const oversized = selected.find((file) => file.size > MAX_ATTACHMENT_SIZE);
    if (oversized) {
      message.warning('单个附件不能超过 50MB');
      return;
    }
    setUploading(true);
    try {
      let uploaded = attachments;
      for (const file of selected) {
        uploaded = [...uploaded, await uploadTaskAttachment(file)];
        onChange(uploaded);
      }
    } finally {
      setUploading(false);
      if (inputRef.current) {
        inputRef.current.value = '';
      }
    }
  }

  return (
    <div className={floating ? 'task-attachment-picker task-attachment-picker-floating' : 'task-attachment-picker'}>
      <input
        ref={inputRef}
        className="task-attachment-file-input"
        type="file"
        hidden
        style={{ display: 'none' }}
        accept="image/png,image/jpeg,image/webp,image/gif,image/avif,image/bmp,video/mp4,video/webm,video/quicktime,video/mpeg,video/x-msvideo,video/x-matroska"
        multiple
        onChange={(event) => handleFiles(event.target.files)}
      />
      {attachments.length > 0 && !floating && (
        <TaskAttachmentPreviewList
          attachments={attachments}
          onRemove={(attachmentId) => onChange(attachments.filter((item) => item.id !== attachmentId))}
          compact={!floating}
        />
      )}
      <Tooltip title={uploading ? '正在上传附件' : '上传图片或视频'} placement="top">
        <button
          type="button"
          className={floating ? 'ask-tool-button task-attachment-upload-button' : 'task-attachment-compact-button'}
          disabled={disabled || uploading || regularAttachmentCount >= MAX_ATTACHMENT_COUNT}
          onClick={() => inputRef.current?.click()}
          aria-label={uploading ? '正在上传附件' : '上传图片或视频'}
        >
          {uploading ? <SpinnerGap className="task-attachment-spinner" size={20} /> : <Paperclip size={20} weight="bold" />}
        </button>
      </Tooltip>
    </div>
  );
}

export function TaskAttachmentPreviewList({ attachments, onRemove, compact }: TaskAttachmentPreviewListProps) {
  const [preview, setPreview] = useState<TaskAttachment>();
  const [previewText, setPreviewText] = useState<string>();

  useEffect(() => {
    if (!preview || !preview.contentType.startsWith('text/')) {
      setPreviewText(undefined);
      return;
    }
    let cancelled = false;
    setPreviewText('加载中…');
    getTaskAttachmentText(preview.url)
      .then((content) => {
        if (!cancelled) {
          setPreviewText(content);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setPreviewText('无法读取附件内容');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [preview]);

  if (attachments.length === 0) {
    return null;
  }
  return (
    <>
      <div className={compact ? 'task-attachment-list task-attachment-list-compact' : 'task-attachment-list'}>
        {attachments.map((attachment) => {
          const video = attachment.contentType.startsWith('video/');
          const text = attachment.contentType.startsWith('text/');
          return (
            <div
              className="task-attachment-item"
              key={attachment.id}
              onClick={() => setPreview(attachment)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  setPreview(attachment);
                }
              }}
              role="button"
              tabIndex={0}
              title={`预览 ${attachment.name}`}
            >
              <span className="task-attachment-thumb">
                {video ? (
                  <>
                    <video src={attachment.url} muted preload="metadata" />
                    <VideoCamera className="task-attachment-type-icon" size={18} weight="fill" />
                  </>
                ) : text ? (
                  <FileText className="task-attachment-type-icon" size={20} weight="fill" />
                ) : (
                  <img src={attachment.url} alt="" />
                )}
              </span>
              <span className="task-attachment-info">
                <strong>{attachment.name}</strong>
                <span>{video ? '视频' : text ? '文本' : '图片'} · {formatFileSize(attachment.size)}</span>
              </span>
              {onRemove && (
                <button
                  className="task-attachment-remove"
                  type="button"
                  aria-label={`移除 ${attachment.name}`}
                  title="移除附件"
                  onClick={(event) => {
                    event.stopPropagation();
                    onRemove(attachment.id);
                  }}
                >
                  <X size={13} weight="bold" />
                </button>
              )}
            </div>
          );
        })}
      </div>
      <Modal
        className="task-attachment-preview-modal"
        title={preview?.name}
        open={Boolean(preview)}
        footer={null}
        centered
        width={preview?.contentType.startsWith('video/') ? 900 : 760}
        destroyOnHidden
        onCancel={() => setPreview(undefined)}
      >
        {preview?.contentType.startsWith('video/') ? (
          <video className="task-attachment-preview-media" src={preview.url} controls autoPlay />
        ) : preview?.contentType.startsWith('text/') ? (
          <pre className="task-attachment-preview-text">{previewText || '加载中…'}</pre>
        ) : preview ? (
          <img className="task-attachment-preview-media" src={preview.url} alt={preview.name} />
        ) : null}
      </Modal>
    </>
  );
}

function isSupportedFile(file: File) {
  return file.type !== 'image/svg+xml' && (file.type.startsWith('image/') || file.type.startsWith('video/'));
}

function formatFileSize(size: number) {
  if (size < 1024 * 1024) {
    return `${Math.max(1, Math.round(size / 1024))} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}
