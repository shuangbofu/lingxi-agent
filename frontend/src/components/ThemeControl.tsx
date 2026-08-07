import { Check, Moon, Sun } from '@phosphor-icons/react';
import { Dropdown } from 'antd';
import type { ItemType } from 'antd/es/menu/interface';
import { useThemeMode, type DarkLevel } from '../context/ThemeContext';

interface ThemeControlProps {
  placement: 'bottomRight' | 'topRight';
}

const DARK_LEVEL_BY_KEY: Record<string, DarkLevel> = {
  'level-soft': 'soft',
  'level-standard': 'standard',
  'level-deep': 'deep',
};

/**
 * 显示主题模式和深色等级选择入口。
 *
 * @param placement 下拉菜单相对主题图标的展开方向
 * @return 主题选择下拉控件
 */
export function ThemeControl({ placement }: ThemeControlProps) {
  const { mode, darkLevel, setMode, setDarkLevel } = useThemeMode();
  const menuItems: ItemType[] = [
    {
      type: 'group',
      label: '显示模式',
      children: [
        {
          key: 'mode-light',
          icon: <Sun size={16} />,
          label: <MenuOption label="浅色" selected={mode === 'light'} />,
        },
        {
          key: 'mode-dark',
          icon: <Moon size={16} />,
          label: <MenuOption label="深色" selected={mode === 'dark'} />,
        },
      ],
    },
    { type: 'divider' },
    {
      type: 'group',
      label: '深色等级',
      children: [
        {
          key: 'level-soft',
          icon: <span className="theme-level-swatch theme-level-swatch--soft" />,
          label: <MenuOption label="柔和" selected={mode === 'dark' && darkLevel === 'soft'} />,
        },
        {
          key: 'level-standard',
          icon: <span className="theme-level-swatch theme-level-swatch--standard" />,
          label: <MenuOption label="标准" selected={mode === 'dark' && darkLevel === 'standard'} />,
        },
        {
          key: 'level-deep',
          icon: <span className="theme-level-swatch theme-level-swatch--deep" />,
          label: <MenuOption label="深黑" selected={mode === 'dark' && darkLevel === 'deep'} />,
        },
      ],
    },
  ];
  function handleSelect(key: string) {
    if (key === 'mode-light') {
      setMode('light');
      return;
    }
    if (key === 'mode-dark') {
      setMode('dark');
      return;
    }
    const level = DARK_LEVEL_BY_KEY[key];
    if (!level) {
      return;
    }
    setDarkLevel(level);
    setMode('dark');
  }

  return (
    <Dropdown
      menu={{ items: menuItems, onClick: ({ key }) => handleSelect(String(key)) }}
      placement={placement}
      trigger={['click']}
    >
      <button
        className={`theme-control-button ${mode === 'dark' ? 'theme-control-button--active' : ''}`}
        type="button"
        title="显示设置"
        aria-label="显示设置"
      >
        <Moon size={19} weight={mode === 'dark' ? 'fill' : 'regular'} />
      </button>
    </Dropdown>
  );
}

function MenuOption({ label, selected }: { label: string; selected: boolean }) {
  return (
    <span className="theme-menu-option">
      <span>{label}</span>
      {selected && <Check size={15} weight="bold" />}
    </span>
  );
}
