import { Robot } from '@phosphor-icons/react';

interface ProviderTypeIconProps {
  icon?: string;
  darkIcon?: string;
  size?: number;
}

export function ProviderTypeIcon({ icon, darkIcon, size = 18 }: ProviderTypeIconProps) {
  if (!icon) {
    return <Robot size={size} weight="duotone" aria-hidden="true" />;
  }
  return (
    <span
      className={`provider-brand-logo${darkIcon ? ' provider-brand-logo-themed' : ''}`}
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <img className="provider-brand-logo-light" src={icon} alt="" />
      {darkIcon && <img className="provider-brand-logo-dark" src={darkIcon} alt="" />}
    </span>
  );
}
