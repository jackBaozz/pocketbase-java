import { useCallback, useEffect, useRef, useState } from "react";
import type { AnimationEvent as ReactAnimationEvent } from "react";

/**
 * Canonical exit animation name for every right-edge drawer.
 * Keyframes live in `styles.css` (`drawer-slide-out` / `drawer-fade-out`).
 * Component CSS must reuse these names so exit completion is detectable.
 */
export const DRAWER_SLIDE_OUT = "drawer-slide-out";

/** Fallback if `animationend` is missed (reduced-motion, interrupted layout, etc.). */
const EXIT_FALLBACK_MS = 280;

/**
 * Shared open/close lifecycle for right-edge drawers.
 *
 * - Call `requestClose()` from X / backdrop / Escape (via useModalInteraction).
 * - Put `is-exiting` on backdrop + panel while `exiting` is true.
 * - Wire `onPanelAnimationEnd` to the sliding panel element.
 * - Parent keeps the drawer mounted until `onClose` runs after the exit animation.
 */
export function useDrawerTransition(onClose: () => void) {
  const [exiting, setExiting] = useState(false);
  const onCloseRef = useRef(onClose);
  const finishedRef = useRef(false);
  onCloseRef.current = onClose;

  const finish = useCallback(() => {
    if (finishedRef.current) return;
    finishedRef.current = true;
    onCloseRef.current();
  }, []);

  const requestClose = useCallback(() => {
    setExiting((current) => (current ? current : true));
  }, []);

  useEffect(() => {
    if (!exiting) return;
    const timer = window.setTimeout(finish, EXIT_FALLBACK_MS);
    return () => window.clearTimeout(timer);
  }, [exiting, finish]);

  const onPanelAnimationEnd = useCallback(
    (event: ReactAnimationEvent<HTMLElement>) => {
      if (!exiting) return;
      if (event.target !== event.currentTarget) return;
      if (event.animationName !== DRAWER_SLIDE_OUT) return;
      finish();
    },
    [exiting, finish]
  );

  return { exiting, requestClose, onPanelAnimationEnd };
}
