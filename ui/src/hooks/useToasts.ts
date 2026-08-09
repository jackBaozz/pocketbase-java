/**
 * useToasts — stacked toast notification system with dedup, auto-dismiss,
 * hover-pause, and manual close.
 *
 * Extracted from the root App component.
 */
import { useCallback, useMemo, useRef, useState } from "react";

export type ToastKind = "ok" | "error" | "warning" | "info";

export type ToastItem = {
  id: number;
  kind: ToastKind;
  message: string;
};

const MAX_VISIBLE = 4;
const AUTO_DISMISS_MS = 3200;

export function useToasts() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const toastIdRef = useRef(0);
  const toastTimersRef = useRef<Map<number, number>>(new Map());

  const dismissToast = useCallback((id: number) => {
    setToasts((current) => current.filter((item) => item.id !== id));
    const timer = toastTimersRef.current.get(id);
    if (timer) {
      window.clearTimeout(timer);
      toastTimersRef.current.delete(id);
    }
  }, []);

  const notify = useCallback(
    (message: string, kind: ToastKind = "ok") => {
      const id = ++toastIdRef.current;
      setToasts((current) => {
        // Dedupe by message: a duplicate simply refreshes the existing entry.
        if (current.some((item) => item.message === message)) return current;
        return [...current.slice(-(MAX_VISIBLE - 1)), { id, message, kind }];
      });
      const timer = window.setTimeout(() => dismissToast(id), AUTO_DISMISS_MS);
      toastTimersRef.current.set(id, timer);
    },
    [dismissToast]
  );

  /** Pause auto-dismiss for a toast (e.g. on mouse enter). */
  const pauseToast = useCallback((id: number) => {
    const timer = toastTimersRef.current.get(id);
    if (timer) window.clearTimeout(timer);
  }, []);

  /** Resume auto-dismiss for a toast (e.g. on mouse leave). */
  const resumeToast = useCallback(
    (id: number) => {
      const timer = window.setTimeout(() => dismissToast(id), AUTO_DISMISS_MS);
      toastTimersRef.current.set(id, timer);
    },
    [dismissToast]
  );

  return useMemo(
    () => ({
      toasts,
      notify,
      dismissToast,
      pauseToast,
      resumeToast,
    }),
    [toasts, notify, dismissToast, pauseToast, resumeToast]
  );
}

export type ToastSystem = ReturnType<typeof useToasts>;
