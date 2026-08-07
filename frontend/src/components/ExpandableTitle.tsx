import { useEffect, useRef, useState } from 'react';

interface ExpandableTitleProps {
  text: string;
  className?: string;
  textClassName?: string;
}

/**
 * 标题超出两行时折叠并显示“展开/收起”，避免超长问题把头部撑得过高。
 *
 * @param text 标题文本
 * @param className 外层容器类名
 * @param textClassName 标题文本类名
 * @return 带折叠能力的标题节点
 */
export function ExpandableTitle({ text, className, textClassName }: ExpandableTitleProps) {
  const [expanded, setExpanded] = useState(false);
  const [overflow, setOverflow] = useState(false);
  const textRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setExpanded(false);
  }, [text]);

  useEffect(() => {
    const element = textRef.current;
    if (!element) {
      setOverflow(false);
      return;
    }
    const update = () => {
      if (!expanded) {
        setOverflow(element.scrollHeight > element.clientHeight + 1);
      }
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, [expanded, text]);

  return (
    <div className={['expandable-title', className].filter(Boolean).join(' ')}>
      <div
        ref={textRef}
        className={[textClassName, expanded ? '' : 'expandable-title-clamped'].filter(Boolean).join(' ')}
      >
        {text}
      </div>
      {overflow && (
        <button type="button" className="expandable-title-toggle" onClick={() => setExpanded((current) => !current)}>
          {expanded ? '收起' : '展开'}
        </button>
      )}
    </div>
  );
}
