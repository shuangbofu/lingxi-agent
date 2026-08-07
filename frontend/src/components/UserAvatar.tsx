import { Avatar } from 'antd';

interface UserAvatarProps {
  avatarUrl?: string;
  name?: string;
  className?: string;
  size?: number;
}

export function UserAvatar({ avatarUrl, name = '用户', className, size }: UserAvatarProps) {
  const fallbackStyle = avatarUrl ? undefined : avatarFallbackStyle(name);
  return (
    <Avatar className={className} src={avatarUrl || undefined} size={size} style={fallbackStyle}>
      {avatarText(name)}
    </Avatar>
  );
}

function avatarText(value: string) {
  const text = value.trim();
  return text ? text.slice(0, 1).toUpperCase() : 'U';
}

const avatarPalettes = [
  { backgroundColor: '#e0f2f1', color: '#0f766e' },
  { backgroundColor: '#dbeafe', color: '#2563eb' },
  { backgroundColor: '#ede9fe', color: '#7c3aed' },
  { backgroundColor: '#fee2e2', color: '#dc2626' },
  { backgroundColor: '#dcfce7', color: '#16a34a' },
  { backgroundColor: '#fef3c7', color: '#b45309' },
  { backgroundColor: '#e0f2fe', color: '#0284c7' },
  { backgroundColor: '#fce7f3', color: '#db2777' },
  { backgroundColor: '#e2e8f0', color: '#475569' },
];

function avatarFallbackStyle(value: string) {
  const palette = avatarPalettes[hashText(value.trim() || '用户') % avatarPalettes.length];
  return {
    backgroundColor: palette.backgroundColor,
    color: palette.color,
  };
}

function hashText(value: string) {
  let hash = 0;
  for (const char of value) {
    hash = (hash * 31 + char.charCodeAt(0)) >>> 0;
  }
  return hash;
}
