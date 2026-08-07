import { Cpu } from '@phosphor-icons/react';

export function RuntimeIcon({ iconUrl, size }: { iconUrl?: string; size: number }) {
  if (iconUrl) {
    return (
      <span className="provider-brand-logo" style={{ width: size, height: size }} aria-hidden="true">
        <img src={iconUrl} alt="" />
      </span>
    );
  }
  return <Cpu size={size} weight="duotone" aria-hidden="true" />;
}
