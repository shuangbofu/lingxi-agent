export const AI_DISCLAIMER_TEXT = '本回答由 AI 生成，内容仅供参考，请仔细甄别';

export function AiDisclaimer({ className }: { className?: string }) {
  return <div className={['ai-disclaimer', className || ''].filter(Boolean).join(' ')}>{AI_DISCLAIMER_TEXT}</div>;
}
