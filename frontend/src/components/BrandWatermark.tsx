import { CSSProperties, useLayoutEffect, useRef, useState } from 'react';
import { AppLogo } from './AppLogo';
import { AppBrandText } from './AppBrandText';

const CELL_WIDTH = 218;
const CELL_HEIGHT = 108;

export function BrandWatermark() {
  const watermarkRef = useRef<HTMLDivElement>(null);
  const [layout, setLayout] = useState({ columns: 8, count: 120 });

  useLayoutEffect(() => {
    const watermark = watermarkRef.current;
    const target = watermark?.parentElement;
    if (!target) {
      return undefined;
    }
    const targetElement = target;

    function updateLayout() {
      const width = Math.max(targetElement.clientWidth, targetElement.scrollWidth);
      const height = Math.max(targetElement.clientHeight, targetElement.scrollHeight);
      const columns = Math.max(1, Math.ceil((width + CELL_WIDTH * 2) / CELL_WIDTH) + 1);
      const rows = Math.max(1, Math.ceil((height + CELL_HEIGHT * 2) / CELL_HEIGHT) + 2);
      setLayout((previous) => {
        const next = { columns, count: columns * rows };
        return previous.columns === next.columns && previous.count === next.count ? previous : next;
      });
    }

    const resizeObserver = new ResizeObserver(updateLayout);
    resizeObserver.observe(targetElement);
    updateLayout();
    window.addEventListener('resize', updateLayout);
    return () => {
      resizeObserver.disconnect();
      window.removeEventListener('resize', updateLayout);
    };
  }, []);

  const style = {
    '--brand-watermark-columns': String(layout.columns),
    '--brand-watermark-cell-width': `${CELL_WIDTH}px`,
    '--brand-watermark-cell-height': `${CELL_HEIGHT}px`,
  } as CSSProperties;

  return (
    <div className="brand-watermark" aria-hidden="true" ref={watermarkRef} style={style}>
      {Array.from({ length: layout.count }).map((_, index) => (
        <div className="brand-watermark-item" key={index} style={watermarkItemStyle(index, layout.columns)}>
          <AppLogo className="brand-watermark-logo" size={18} />
          <AppBrandText className="brand-watermark-text" />
        </div>
      ))}
    </div>
  );
}

function watermarkItemStyle(index: number, columns: number) {
  const row = Math.floor(index / columns);
  const column = index % columns;
  const rowOffset = row % 2 === 0 ? -36 : 42;
  const columnOffset = column % 3 === 0 ? -10 : column % 3 === 1 ? 14 : -2;
  const verticalOffset = (index % 5 - 2) * 3;
  const angle = -24 + (index % 7 - 3) * 1.4;
  const opacity = 0.045 + (index % 4) * 0.006;
  return {
    '--brand-watermark-x': `${rowOffset + columnOffset}px`,
    '--brand-watermark-y': `${verticalOffset}px`,
    '--brand-watermark-angle': `${angle}deg`,
    '--brand-watermark-opacity': String(opacity),
  } as CSSProperties;
}
