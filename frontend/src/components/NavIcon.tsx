import {
  Brain,
  ChartPieSlice,
  ClockCounterClockwise,
  GearSix,
  MagnifyingGlass,
  ListChecks,
  PuzzlePiece,
  Shapes,
  TreeStructure,
  Users,
  Cpu,
} from '@phosphor-icons/react';

export type NavIconName = 'analysis' | 'dashboard' | 'record' | 'definition' | 'capabilityConfig' | 'settings' | 'task' | 'premise' | 'user' | 'model';
export type NavIconTone = 'sky' | 'indigo' | 'teal' | 'violet' | 'emerald' | 'rose' | 'slate' | 'orange' | 'cyan' | 'amber' | 'blue' | 'lime';

interface NavIconProps {
  name: NavIconName;
  tone?: NavIconTone;
}

const icons = {
  analysis: MagnifyingGlass,
  dashboard: ChartPieSlice,
  record: ClockCounterClockwise,
  definition: Shapes,
  capabilityConfig: PuzzlePiece,
  settings: GearSix,
  task: ListChecks,
  premise: TreeStructure,
  user: Users,
  model: Cpu,
} satisfies Record<NavIconName, typeof Brain>;

export function NavIcon({ name, tone = 'blue' }: NavIconProps) {
  const Icon = icons[name] || Brain;
  return (
    <span className={`nav-icon nav-icon--${tone}`} aria-hidden="true">
      <Icon weight="fill" />
    </span>
  );
}
