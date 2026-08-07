import { useCallback } from 'react';
import { flushSync } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import type { NavigateOptions, To } from 'react-router-dom';

type PageTransitionDirection = 'forward' | 'backward';

type PageTransitionNavigateOptions = NavigateOptions & {
  direction: PageTransitionDirection;
};

type ViewTransitionDocument = Document & {
  startViewTransition?: (update: () => void) => { finished: Promise<void> };
};

let transitionRunning = false;

/**
 * 在普通用户页面之间执行成对的前进或返回转场。
 *
 * @return 接收目标地址和转场方向的导航函数
 */
export function usePageTransitionNavigate() {
  const navigate = useNavigate();
  const location = useLocation();

  return useCallback((to: To, options: PageTransitionNavigateOptions) => {
    const { direction, ...navigateOptions } = options;
    if (isCurrentLocation(to, location.pathname, location.search) || prefersReducedMotion()) {
      navigate(to, navigateOptions);
      return;
    }
    if (transitionRunning) {
      return;
    }

    const root = document.documentElement;
    const transitionDocument = document as ViewTransitionDocument;
    root.dataset.pageTransitionDirection = direction;
    transitionRunning = true;

    if (transitionDocument.startViewTransition) {
      try {
        const transition = transitionDocument.startViewTransition(() => {
          flushSync(() => navigate(to, navigateOptions));
        });
        void transition.finished.then(clearTransitionState, clearTransitionState);
        return;
      } catch {
        clearTransitionState();
        root.dataset.pageTransitionDirection = direction;
        transitionRunning = true;
      }
    }

    void fallbackTransition(direction, () => {
      flushSync(() => navigate(to, navigateOptions));
    }).then(clearTransitionState, clearTransitionState);
  }, [location.pathname, location.search, navigate]);
}

function isCurrentLocation(to: To, pathname: string, search: string) {
  if (typeof to !== 'string') {
    return to.pathname === pathname && (to.search || '') === search;
  }
  const [targetPathname, targetSearch = ''] = to.split('?');
  return targetPathname === pathname && (targetSearch ? `?${targetSearch}` : '') === search;
}

function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

async function fallbackTransition(direction: PageTransitionDirection, navigate: () => void) {
  const currentPage = document.querySelector<HTMLElement>('.app-page');
  if (!currentPage) {
    navigate();
    return;
  }
  await animatePage(currentPage, direction, 'exit');
  navigate();
  await nextFrame();
  const nextPage = document.querySelector<HTMLElement>('.app-page');
  if (nextPage) {
    await animatePage(nextPage, direction, 'enter');
  }
}

function animatePage(element: HTMLElement, direction: PageTransitionDirection, phase: 'enter' | 'exit') {
  const frames = transitionFrames(direction, phase);
  const animation = element.animate(frames, {
    duration: 220,
    easing: 'cubic-bezier(0.16, 1, 0.3, 1)',
    fill: 'both',
  });
  return animation.finished.catch(() => undefined);
}

function transitionFrames(direction: PageTransitionDirection, phase: 'enter' | 'exit'): Keyframe[] {
  if (direction === 'forward' && phase === 'exit') {
    return [
      { opacity: 1 },
      { opacity: 0 },
    ];
  }
  if (direction === 'forward') {
    return [
      { opacity: 0, transform: 'scale(1.04)' },
      { opacity: 1, transform: 'scale(1)' },
    ];
  }
  if (phase === 'exit') {
    return [
      { opacity: 1, transform: 'scale(1)' },
      { opacity: 0, transform: 'scale(1.04)' },
    ];
  }
  return [
    { opacity: 0 },
    { opacity: 1 },
  ];
}

function nextFrame() {
  return new Promise<void>((resolve) => window.requestAnimationFrame(() => resolve()));
}

function clearTransitionState() {
  delete document.documentElement.dataset.pageTransitionDirection;
  transitionRunning = false;
}
