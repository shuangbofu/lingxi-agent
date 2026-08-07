import { useState } from 'react';

interface DefinitionIconProps {
  src?: string;
  label?: string;
  size?: number | 'css';
  className?: string;
}

const fallbackIcon = '/favicon.png';

export function DefinitionIcon({ src, label, size = 18, className }: DefinitionIconProps) {
  const [failedSrc, setFailedSrc] = useState<string>();
  const imageSrc = src && src !== failedSrc ? src : fallbackIcon;
  return (
    <img
      src={imageSrc}
      alt={label || ''}
      className={['definition-icon-image', className].filter(Boolean).join(' ')}
      style={size === 'css' ? undefined : { width: size, height: size }}
      onError={() => {
        if (imageSrc !== fallbackIcon) {
          setFailedSrc(imageSrc);
        }
      }}
    />
  );
}
