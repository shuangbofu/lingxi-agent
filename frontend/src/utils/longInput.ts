import { uploadTaskAttachment } from '../api/lingxi';
import type { TaskAttachment } from '../types/api';

export const LONG_INPUT_THRESHOLD = 500;
export const LONG_INPUT_EXCERPT_LENGTH = 120;

export interface PreparedUserInput {
  userInput: string;
  attachmentIds: string[];
  attachments: TaskAttachment[];
  textAttachment?: TaskAttachment;
}

/**
 * 超长用户输入转为文本附件，标题与 Prompt 只保留摘要；短文本保持原样。
 *
 * @param text 用户输入全文
 * @param attachments 已上传附件
 * @return 提交用摘要、附件列表、附件 ID 列表以及自动生成的文本附件
 */
export async function prepareLongInput(text: string, attachments: TaskAttachment[]): Promise<PreparedUserInput> {
  const trimmed = (text || '').trim();
  if (trimmed.length <= LONG_INPUT_THRESHOLD) {
    return { userInput: trimmed, attachments, attachmentIds: attachments.map((item) => item.id) };
  }
  const file = new File(
    [new Blob([trimmed], { type: 'text/plain;charset=utf-8' })],
    longInputFileName(trimmed),
    { type: 'text/plain;charset=utf-8' }
  );
  const textAttachment = await uploadTaskAttachment(file, 'user-input');
  const excerpt = `${trimmed.slice(0, LONG_INPUT_EXCERPT_LENGTH).trimEnd()}…`;
  const nextAttachments = [
    ...attachments.filter((attachment) => attachment.inputKind !== 'user-input'),
    textAttachment,
  ];
  return {
    userInput: excerpt,
    attachments: nextAttachments,
    attachmentIds: nextAttachments.map((attachment) => attachment.id),
    textAttachment,
  };
}

function longInputFileName(text: string) {
  const title = text
    .replace(/\s+/g, ' ')
    .slice(0, 32)
    .replace(/[\p{Cc}<>:"/\\|?*]/gu, '_')
    .trim();
  return `${title || '用户输入详情'}.txt`;
}

/**
 * 判断当前输入是否需要自动转换为全文附件。
 *
 * @param text 当前输入文本
 * @return 超过长文本阈值时返回 true
 */
export function isLongInput(text: string) {
  return (text || '').trim().length > LONG_INPUT_THRESHOLD;
}

/**
 * 按文本框当前选区计算粘贴完成后的完整内容。
 *
 * @param currentText 文本框当前内容
 * @param pastedText 剪贴板文本
 * @param selectionStart 选区起点
 * @param selectionEnd 选区终点
 * @return 替换选区后的文本
 */
export function mergePastedText(
  currentText: string,
  pastedText: string,
  selectionStart: number | null,
  selectionEnd: number | null,
) {
  const start = selectionStart ?? currentText.length;
  const end = selectionEnd ?? start;
  return `${currentText.slice(0, start)}${pastedText}${currentText.slice(end)}`;
}
