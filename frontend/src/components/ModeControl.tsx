import { Check, ChatsCircle, Sparkle } from '@phosphor-icons/react';
import { Dropdown } from 'antd';
import type { ItemType } from 'antd/es/menu/interface';
import { useNavigate } from 'react-router-dom';

export type ChatMode = 'chat' | 'ask';

interface ModeControlProps {
  placement: 'bottomRight' | 'topRight';
  /** 当前模式 */
  value: ChatMode;
}

/**
 * 对话 / 提问两种形态的切换入口（与主题切换同风格的图标下拉）。
 */
export function ModeControl({ placement, value }: ModeControlProps) {
  const navigate = useNavigate();
  const menuItems: ItemType[] = [
    {
      key: 'mode-chat',
      icon: <ChatsCircle size={16} weight={value === 'chat' ? 'fill' : 'regular'} />,
      label: <MenuOption label="会话模式" selected={value === 'chat'} />,
    },
    {
      key: 'mode-ask',
      icon: <Sparkle size={16} weight={value === 'ask' ? 'fill' : 'regular'} />,
      label: <MenuOption label="问答模式" selected={value === 'ask'} />,
    },
  ];

  function handleSelect(key: string) {
    if (key === 'mode-chat' && value !== 'chat') {
      navigate('/chat');
      return;
    }
    if (key === 'mode-ask' && value !== 'ask') {
      navigate('/ask');
    }
  }

  return (
    <Dropdown
      menu={{ items: menuItems, onClick: ({ key }) => handleSelect(String(key)) }}
      placement={placement}
      trigger={['click']}
    >
      <button className="mode-control-button" type="button" title="模式切换" aria-label="模式切换">
        {value === 'chat' ? <ChatsCircle size={19} weight="fill" /> : <Sparkle size={19} weight="fill" />}
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
