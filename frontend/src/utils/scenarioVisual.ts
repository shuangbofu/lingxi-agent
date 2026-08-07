import type { AgentScenario, DashboardTokenDimension, TaskItem } from '../types/api';

export interface ScenarioPalette {
  bg: string;
  surface: string;
  soft: string;
  border: string;
  accent: string;
}

export const scenarioPalettes: ScenarioPalette[] = [
  { bg: '#f4f9f8', surface: '#fbfefd', soft: '#e0f2f0', border: '#b8ddd8', accent: '#0f766e' },
  { bg: '#f7fbfd', surface: '#fcfeff', soft: '#e0f2f7', border: '#b6dce8', accent: '#0891b2' },
  { bg: '#f5f8ff', surface: '#fbfcff', soft: '#e6efff', border: '#bfd0f4', accent: '#2563eb' },
  { bg: '#fbf5f5', surface: '#fffafa', soft: '#fee8e8', border: '#f4b9b9', accent: '#dc2626' },
  { bg: '#f4faf6', surface: '#fbfefc', soft: '#e3f4e8', border: '#bde3c9', accent: '#16a34a' },
  { bg: '#f8f5ff', surface: '#fdfbff', soft: '#ede7fb', border: '#d3c3f3', accent: '#7c3aed' },
  { bg: '#fbf7f3', surface: '#fefcf9', soft: '#f4e9dd', border: '#e3d2bd', accent: '#a18462' },
  { bg: '#f5f7fa', surface: '#fbfcfe', soft: '#e7ebf2', border: '#ccd4df', accent: '#728197' },
];

const neutralPalette: ScenarioPalette = {
  bg: '#ffffff',
  surface: '#ffffff',
  soft: '#fafafa',
  border: '#d4d4d4',
  accent: '#737373',
};

export function scenarioPaletteForScenario(scenario: AgentScenario, scenarios: AgentScenario[] = []): ScenarioPalette {
  return paletteFromColor(scenario.color) || scenarioPaletteForKey(scenarioVisualKey(scenario), scenarios);
}

export function scenarioPaletteForTask(task: TaskItem) {
  const configured = paletteFromColor(task.scenarioColor);
  if (configured) {
    return configured;
  }
  return scenarioPaletteForKey([task.scenario, task.scenarioName].filter(Boolean).join(' '));
}

export function scenarioPaletteForDimension(record: DashboardTokenDimension, scenarios: AgentScenario[] = []) {
  const configured = paletteFromColor(record.scenarioColor);
  if (configured) {
    return configured;
  }
  const matched = record.scenarioCode ? scenarios.find((item) => item.code === record.scenarioCode) : undefined;
  if (matched) {
    return scenarioPaletteForScenario(matched, scenarios);
  }
  return scenarioPaletteForKey([record.scenario, record.scenarioName, record.dimensionName].filter(Boolean).join(' '), scenarios);
}

export function scenarioCssVariables(palette: ScenarioPalette) {
  const darkPalette = scenarioPaletteForTheme(palette, 'dark');
  return {
    '--ask-scenario-bg': palette.bg,
    '--ask-scenario-surface': palette.surface,
    '--ask-scenario-soft': palette.soft,
    '--ask-scenario-border': palette.border,
    '--ask-scenario-accent': palette.accent,
    '--ask-scenario-dark-bg': darkPalette.bg,
    '--ask-scenario-dark-surface': darkPalette.surface,
    '--ask-scenario-dark-soft': darkPalette.soft,
    '--ask-scenario-dark-border': darkPalette.border,
  };
}

export function neutralScenarioCssVariables() {
  return scenarioCssVariables(neutralPalette);
}

export function scenarioPaletteForTheme(palette: ScenarioPalette, mode: 'light' | 'dark'): ScenarioPalette {
  if (mode === 'light') {
    return palette;
  }
  return {
    ...palette,
    bg: mixHex(palette.accent, '#101010', 0.95),
    surface: mixHex(palette.accent, '#181818', 0.96),
    soft: mixHex(palette.accent, '#181818', 0.90),
    border: mixHex(palette.accent, '#343434', 0.72),
  };
}

function scenarioPaletteForKey(value: string, scenarios: AgentScenario[] = []): ScenarioPalette {
  const key = value.toLowerCase();
  const indexedScenario = scenarios.find((item) => scenarioVisualKey(item).toLowerCase() === key);
  if (indexedScenario) {
    return paletteFromColor(indexedScenario.color) || scenarioPalettes[scenarios.indexOf(indexedScenario) % scenarioPalettes.length];
  }
  return scenarioPalettes[hashText(key) % scenarioPalettes.length] || neutralPalette;
}

function scenarioVisualKey(scenario: AgentScenario) {
  return scenario?.code || scenario?.scenario || scenario?.name || '';
}

function paletteFromColor(color?: string): ScenarioPalette | undefined {
  const normalized = color?.trim();
  if (!normalized || !/^#[0-9a-f]{6}$/i.test(normalized)) {
    return undefined;
  }
  return {
    bg: mixHex(normalized, '#ffffff', 0.95),
    surface: mixHex(normalized, '#ffffff', 0.985),
    soft: mixHex(normalized, '#ffffff', 0.86),
    border: mixHex(normalized, '#ffffff', 0.68),
    accent: normalized,
  };
}

function mixHex(color: string, background: string, backgroundRatio: number) {
  const foregroundRatio = 1 - backgroundRatio;
  const channels = [1, 3, 5].map((index) => {
    const foreground = Number.parseInt(color.slice(index, index + 2), 16);
    const base = Number.parseInt(background.slice(index, index + 2), 16);
    return Math.round(foreground * foregroundRatio + base * backgroundRatio).toString(16).padStart(2, '0');
  });
  return `#${channels.join('')}`;
}

function hashText(value: string) {
  return Array.from(value || 'default').reduce((hash, char) => hash + char.charCodeAt(0), 0);
}
