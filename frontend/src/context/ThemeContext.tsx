import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import type { ThemeConfig } from 'antd';
import zhCN from 'antd/locale/zh_CN';

export type ThemeMode = 'light' | 'dark';
export type DarkLevel = 'soft' | 'standard' | 'deep';

interface ThemeContextValue {
  mode: ThemeMode;
  darkLevel: DarkLevel;
  setMode: (mode: ThemeMode) => void;
  setDarkLevel: (level: DarkLevel) => void;
}

const THEME_STORAGE_KEY = 'workbench:theme-mode';
const DARK_LEVEL_STORAGE_KEY = 'workbench:theme-dark-level';
const DARK_THEME_PALETTES: Record<DarkLevel, DarkThemePalette> = {
  soft: {
    bg: '#222222',
    surface: '#2b2b2b',
    elevated: '#343434',
    muted: '#373737',
    strong: '#3e3e3e',
    border: '#484848',
    borderStrong: '#5a5a5a',
    split: '#414141',
    action: '#e8e8e8',
    actionHover: '#f5f5f5',
    actionActive: '#cfcfcf',
    selected: '#4a4a4a',
    disabledBg: '#272727',
    disabledBorder: '#3c3c3c',
    disabledText: '#707070',
    tableHeader: '#333333',
  },
  standard: {
    bg: '#101010',
    surface: '#181818',
    elevated: '#202020',
    muted: '#232323',
    strong: '#292929',
    border: '#343434',
    borderStrong: '#484848',
    split: '#303030',
    action: '#e5e5e5',
    actionHover: '#f5f5f5',
    actionActive: '#c7c7c7',
    selected: '#343434',
    disabledBg: '#141414',
    disabledBorder: '#292929',
    disabledText: '#606060',
    tableHeader: '#222222',
  },
  deep: {
    bg: '#050505',
    surface: '#0d0d0d',
    elevated: '#151515',
    muted: '#181818',
    strong: '#202020',
    border: '#292929',
    borderStrong: '#3a3a3a',
    split: '#242424',
    action: '#dedede',
    actionHover: '#ffffff',
    actionActive: '#bfbfbf',
    selected: '#292929',
    disabledBg: '#090909',
    disabledBorder: '#202020',
    disabledText: '#545454',
    tableHeader: '#171717',
  },
};
const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

interface DarkThemePalette {
  bg: string;
  surface: string;
  elevated: string;
  muted: string;
  strong: string;
  border: string;
  borderStrong: string;
  split: string;
  action: string;
  actionHover: string;
  actionActive: string;
  selected: string;
  disabledBg: string;
  disabledBorder: string;
  disabledText: string;
  tableHeader: string;
}

/**
 * 为应用提供持久化的亮暗主题和 Ant Design 主题配置。
 *
 * @param children 应用内容
 * @return 带主题上下文和组件主题的应用节点
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<ThemeMode>(readInitialMode);
  const [darkLevel, setDarkLevel] = useState<DarkLevel>(readInitialDarkLevel);
  const value = useMemo<ThemeContextValue>(() => ({
    mode,
    darkLevel,
    setMode,
    setDarkLevel,
  }), [mode, darkLevel]);
  const dark = mode === 'dark';
  const darkPalette = DARK_THEME_PALETTES[darkLevel];

  useEffect(() => {
    document.documentElement.dataset.theme = mode;
    document.documentElement.dataset.darkLevel = darkLevel;
    document.documentElement.style.colorScheme = mode;
    window.localStorage.setItem(THEME_STORAGE_KEY, mode);
    window.localStorage.setItem(DARK_LEVEL_STORAGE_KEY, darkLevel);
  }, [mode, darkLevel]);

  const themeConfig = useMemo<ThemeConfig>(() => ({
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
            borderRadius: 22,
            borderRadiusLG: 22,
            borderRadiusSM: 22,
            controlHeight: 34,
            controlHeightSM: 28,
            controlOutline: dark ? 'rgba(255, 255, 255, 0.16)' : 'rgba(64, 64, 64, 0.08)',
            fontSize: 13,
            colorPrimary: dark ? darkPalette.action : '#111111',
            colorPrimaryHover: dark ? darkPalette.actionHover : '#404040',
            colorPrimaryActive: dark ? darkPalette.actionActive : '#111111',
            colorPrimaryBg: dark ? darkPalette.muted : '#f5f5f5',
            colorPrimaryBgHover: dark ? darkPalette.strong : '#eeeeee',
            colorInfo: dark ? darkPalette.action : '#111111',
            colorSuccess: dark ? '#4ade80' : '#166534',
            colorWarning: dark ? '#e5b86b' : '#b88c4a',
            colorTextBase: dark ? '#ededed' : '#171717',
            colorTextPlaceholder: dark ? '#929292' : '#8a8a8a',
            colorTextDisabled: dark ? darkPalette.disabledText : '#a3a3a3',
            colorTextLightSolid: dark ? '#171717' : '#ffffff',
            colorBgBase: dark ? darkPalette.bg : '#ffffff',
            colorBgContainer: dark ? darkPalette.surface : '#ffffff',
            colorBgElevated: dark ? darkPalette.elevated : '#ffffff',
            colorBgLayout: dark ? darkPalette.bg : '#f7f7f7',
            colorBgContainerDisabled: dark ? darkPalette.disabledBg : '#f5f5f5',
            colorBgTextHover: dark ? darkPalette.strong : '#f5f5f5',
            colorBgTextActive: dark ? darkPalette.selected : '#e5e5e5',
            colorBorder: dark ? darkPalette.border : '#d4d4d4',
            colorSplit: dark ? darkPalette.split : '#eeeeee',
            controlItemBgHover: dark ? darkPalette.strong : '#f5f5f5',
            controlItemBgActive: dark ? darkPalette.selected : '#e5e5e5',
            controlItemBgActiveHover: dark ? darkPalette.selected : '#d4d4d4',
            controlItemBgActiveDisabled: dark ? darkPalette.disabledBg : '#f5f5f5',
            fontFamily: '"PingFang SC", "Microsoft YaHei", sans-serif',
    },
    components: {
            Button: {
              borderRadius: 22,
              controlHeight: 34,
              defaultShadow: 'none',
              primaryShadow: 'none',
              defaultBg: dark ? darkPalette.elevated : '#ffffff',
              defaultBorderColor: dark ? darkPalette.border : '#d4d4d4',
              defaultColor: dark ? '#d4d4d4' : '#404040',
              defaultHoverBg: dark ? darkPalette.strong : '#f5f5f5',
              defaultHoverBorderColor: dark ? darkPalette.borderStrong : '#a3a3a3',
              defaultHoverColor: dark ? '#ffffff' : '#171717',
              defaultActiveBg: dark ? darkPalette.selected : '#e5e5e5',
              defaultActiveBorderColor: dark ? darkPalette.borderStrong : '#737373',
              defaultActiveColor: dark ? '#ffffff' : '#111111',
              borderColorDisabled: dark ? darkPalette.disabledBorder : '#e5e5e5',
              colorPrimary: dark ? darkPalette.action : '#111111',
              colorPrimaryHover: dark ? darkPalette.actionHover : '#404040',
              colorPrimaryActive: dark ? darkPalette.actionActive : '#111111',
              primaryColor: dark ? '#171717' : '#ffffff',
            },
            DatePicker: focusTokens(dark, darkPalette),
            Input: focusTokens(dark, darkPalette),
            InputNumber: focusTokens(dark, darkPalette),
            Collapse: {
              borderlessContentBg: 'transparent',
              borderlessContentPadding: '2px 0 0',
              contentBg: 'transparent',
              headerBg: 'transparent',
              headerPadding: '7px 0',
            },
            Form: {
              itemMarginBottom: 13,
              labelColor: dark ? '#d4d4d4' : '#404040',
              labelFontSize: 13,
              labelHeight: 18,
              verticalLabelPadding: '0 0 5px',
            },
            Layout: {
              bodyBg: 'transparent',
              siderBg: dark ? darkPalette.surface : '#ffffff',
            },
            Menu: {
              itemBg: 'transparent',
              itemHoverBg: dark ? darkPalette.muted : '#f1f1f1',
              itemSelectedBg: dark ? darkPalette.selected : '#e9e9e9',
              itemSelectedColor: dark ? '#ffffff' : '#171717',
              itemDisabledColor: dark ? darkPalette.disabledText : '#a3a3a3',
            },
            Modal: {
              contentBg: dark ? darkPalette.elevated : '#ffffff',
              headerBg: dark ? darkPalette.elevated : '#ffffff',
              titleColor: dark ? '#ededed' : '#171717',
            },
            Select: {
              activeBorderColor: dark ? darkPalette.actionHover : '#a3a3a3',
              activeOutlineColor: dark ? 'rgba(255, 255, 255, 0.16)' : 'rgba(64, 64, 64, 0.08)',
              hoverBorderColor: dark ? darkPalette.actionHover : '#a3a3a3',
              optionActiveBg: dark ? darkPalette.muted : '#f5f5f5',
              optionSelectedBg: dark ? darkPalette.selected : '#eeeeee',
              optionSelectedColor: dark ? '#ffffff' : '#171717',
              optionSelectedFontWeight: 500,
            },
            Segmented: {
              itemActiveBg: dark ? darkPalette.selected : '#d4d4d4',
              itemHoverBg: dark ? darkPalette.selected : '#e5e5e5',
              itemSelectedBg: dark ? darkPalette.surface : '#ffffff',
              itemSelectedColor: dark ? '#f0f0f0' : '#171717',
              trackBg: dark ? darkPalette.strong : '#f1f1f1',
              trackPadding: 3,
            },
            Switch: {
              colorPrimary: dark ? darkPalette.action : '#111111',
              colorPrimaryHover: dark ? darkPalette.actionHover : '#404040',
              handleBg: dark ? '#171717' : '#ffffff',
              handleShadow: dark ? '0 1px 3px rgba(0, 0, 0, 0.55)' : '0 1px 3px rgba(0, 0, 0, 0.20)',
            },
            Table: {
              headerBg: dark ? darkPalette.tableHeader : '#f4f4f5',
              headerColor: dark ? '#cfcfcf' : '#525252',
              rowHoverBg: dark ? darkPalette.muted : '#f5f5f5',
            },
            Tabs: {
              inkBarColor: dark ? darkPalette.actionHover : '#111111',
              itemActiveColor: dark ? '#ffffff' : '#111111',
              itemHoverColor: dark ? '#d4d4d4' : '#404040',
              itemSelectedColor: dark ? '#f0f0f0' : '#111111',
            },
            Tag: {
              borderRadiusSM: 22,
              defaultBg: dark ? darkPalette.strong : '#f4f4f5',
              defaultColor: dark ? '#d4d4d4' : '#404040',
            },
    },
  }), [dark, darkPalette]);

  useLayoutEffect(() => {
    ConfigProvider.config({
      holderRender: (content) => (
        <ConfigProvider locale={zhCN} theme={themeConfig}>
          {content}
        </ConfigProvider>
      ),
    });
  }, [themeConfig]);

  return (
    <ThemeContext.Provider value={value}>
      <ConfigProvider locale={zhCN} theme={themeConfig}>
        {children}
      </ConfigProvider>
    </ThemeContext.Provider>
  );
}

export function useThemeMode() {
  const context = useContext(ThemeContext);
  if (!context) {
    throw new Error('useThemeMode must be used within ThemeProvider');
  }
  return context;
}

function readInitialMode(): ThemeMode {
  const mode = window.localStorage.getItem(THEME_STORAGE_KEY) === 'dark' ? 'dark' : 'light';
  document.documentElement.dataset.theme = mode;
  document.documentElement.style.colorScheme = mode;
  return mode;
}

function readInitialDarkLevel(): DarkLevel {
  const storedLevel = window.localStorage.getItem(DARK_LEVEL_STORAGE_KEY);
  const level: DarkLevel = storedLevel === 'soft' || storedLevel === 'deep' ? storedLevel : 'standard';
  document.documentElement.dataset.darkLevel = level;
  return level;
}

function focusTokens(dark: boolean, palette: DarkThemePalette) {
  return {
    activeBorderColor: dark ? palette.actionHover : '#a3a3a3',
    activeShadow: dark ? '0 0 0 2px rgba(255, 255, 255, 0.16)' : '0 0 0 2px rgba(64, 64, 64, 0.08)',
    hoverBorderColor: dark ? palette.actionHover : '#a3a3a3',
  };
}
