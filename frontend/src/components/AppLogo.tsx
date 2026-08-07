interface AppLogoProps {
  className?: string;
  size?: number;
}

export function AppLogo({ className, size }: AppLogoProps) {
  const style = size ? { width: size, height: size } : undefined;
  return (
    <span className={className ? `app-logo-mark ${className}` : 'app-logo-mark'} style={style}>
      <img src="/app-logo.png" alt="" />
    </span>
  );
}
