import { APP_ENGLISH_NAME, APP_NAME } from '../constants/app';

interface AppBrandTextProps {
  className?: string;
  name?: string;
  englishName?: string;
}

export function AppBrandText({ className, name = APP_NAME, englishName = APP_ENGLISH_NAME }: AppBrandTextProps) {
  const classes = className ? `app-brand-text ${className}` : 'app-brand-text';
  return (
    <span className={classes}>
      <span className="app-brand-text-cn">{name}</span>
      <span className="app-brand-text-en">{englishName}</span>
    </span>
  );
}
