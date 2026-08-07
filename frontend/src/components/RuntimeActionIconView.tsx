import type { ComponentProps } from 'react';
import {
  ArrowCounterClockwise,
  BookOpenText,
  BracketsCurly,
  Browser,
  CalendarBlank,
  CheckCircle,
  Clock,
  ClockCounterClockwise,
  Code,
  Database,
  Eye,
  FileMagnifyingGlass,
  FilePlus,
  FileText,
  Folder,
  GitBranch,
  GitCommit,
  GitDiff,
  Graph,
  HeadCircuit,
  ImageSquare,
  ListBullets,
  MagnifyingGlass,
  PlayCircle,
  PlugsConnected,
  Question,
  ShieldCheck,
  Terminal,
  TreeStructure,
  VideoCamera,
  WarningCircle,
  Wrench,
  XCircle,
} from '@phosphor-icons/react';
import type { RuntimeActionIcon } from '../types/api';

interface RuntimeActionIconViewProps {
  icon: RuntimeActionIcon;
  className?: string;
  size?: number;
  weight?: ComponentProps<typeof Wrench>['weight'];
}

export function RuntimeActionIconView({
  icon,
  className,
  size = 15,
  weight = 'regular',
}: RuntimeActionIconViewProps) {
  const props = { className, size, weight, 'aria-hidden': true as const };
  switch (icon) {
    case 'TERMINAL': return <Terminal {...props} />;
    case 'BOOK_OPEN': return <BookOpenText {...props} />;
    case 'FILE_TEXT': return <FileText {...props} />;
    case 'FILE_PLUS': return <FilePlus {...props} />;
    case 'FILE_MAGNIFYING_GLASS': return <FileMagnifyingGlass {...props} />;
    case 'MAGNIFYING_GLASS': return <MagnifyingGlass {...props} />;
    case 'BRACKETS_CURLY': return <BracketsCurly {...props} />;
    case 'CODE': return <Code {...props} />;
    case 'GRAPH': return <Graph {...props} />;
    case 'GIT_DIFF': return <GitDiff {...props} />;
    case 'TREE_STRUCTURE': return <TreeStructure {...props} />;
    case 'PLUGS_CONNECTED': return <PlugsConnected {...props} />;
    case 'QUESTION': return <Question {...props} />;
    case 'LIST_BULLETS': return <ListBullets {...props} />;
    case 'CHECK_CIRCLE': return <CheckCircle {...props} />;
    case 'CALENDAR': return <CalendarBlank {...props} />;
    case 'CLOCK': return <Clock {...props} />;
    case 'DATABASE': return <Database {...props} />;
    case 'FOLDER': return <Folder {...props} />;
    case 'GIT_BRANCH': return <GitBranch {...props} />;
    case 'GIT_COMMIT': return <GitCommit {...props} />;
    case 'SHIELD_CHECK': return <ShieldCheck {...props} />;
    case 'ARROW_COUNTER_CLOCKWISE': return <ArrowCounterClockwise {...props} />;
    case 'PLAY_CIRCLE': return <PlayCircle {...props} />;
    case 'VIDEO_CAMERA': return <VideoCamera {...props} />;
    case 'IMAGE_SQUARE': return <ImageSquare {...props} />;
    case 'EYE': return <Eye {...props} />;
    case 'HISTORY': return <ClockCounterClockwise {...props} />;
    case 'BROWSER': return <Browser {...props} />;
    case 'HEAD_CIRCUIT': return <HeadCircuit {...props} />;
    case 'WARNING_CIRCLE': return <WarningCircle {...props} />;
    case 'X_CIRCLE': return <XCircle {...props} />;
    default: return <Wrench {...props} />;
  }
}
