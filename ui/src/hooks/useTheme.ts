/**
 * useTheme — theme mode (light/dark/auto) with system preference detection,
 * localStorage persistence, cross-tab BroadcastChannel sync, and resolved
 * theme tracking.
 *
 * Extracted from the root App component. Aligned with App's full behavior
 * including dataset.themeMode and cross-tab sync.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

export type ThemeMode = "light" | "dark" | "auto";
export type ResolvedTheme = "light" | "dark";

const THEME_KEY = "pbj_theme";
const SYNC_CHANNEL = "pocketbase-java-admin-sync";

export function readThemeMode(): ThemeMode {
  const value = localStorage.getItem(THEME_KEY);
  return value === "light" || value === "dark" || value === "auto" ? value : "auto";
}

export function resolveThemeMode(mode: ThemeMode): ResolvedTheme {
  if (mode === "auto") {
    return window.matchMedia?.("(prefers-color-scheme: dark)")?.matches ? "dark" : "light";
  }
  return mode;
}

export function useTheme() {
  const [themeMode, setThemeModeState] = useState<ThemeMode>(readThemeMode);
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>(() => resolveThemeMode(readThemeMode()));
  const syncChannelRef = useRef<BroadcastChannel | null>(null);

  // Apply theme to document + listen for system preference changes.
  useEffect(() => {
    const media = window.matchMedia?.("(prefers-color-scheme: dark)");
    const applyTheme = () => {
      const nextResolved = resolveThemeMode(themeMode);
      setResolvedTheme(nextResolved);
      document.documentElement.dataset.theme = nextResolved;
      document.documentElement.dataset.themeMode = themeMode;
    };

    applyTheme();
    if (themeMode === "auto" && media) {
      media.addEventListener("change", applyTheme);
      return () => media.removeEventListener("change", applyTheme);
    }
    return undefined;
  }, [themeMode]);

  // Cross-tab sync via BroadcastChannel.
  useEffect(() => {
    if (typeof BroadcastChannel === "undefined") return;
    const channel = new BroadcastChannel(SYNC_CHANNEL);
    syncChannelRef.current = channel;
    channel.onmessage = (event: MessageEvent) => {
      const data = event.data;
      if (data && typeof data === "object" && data.type === "theme" && data.theme) {
        setThemeModeState(data.theme as ThemeMode);
        localStorage.setItem(THEME_KEY, data.theme);
      }
    };
    return () => {
      channel.close();
      syncChannelRef.current = null;
    };
  }, []);

  const setThemeMode = useCallback((mode: ThemeMode) => {
    setThemeModeState(mode);
    localStorage.setItem(THEME_KEY, mode);
    // Broadcast to other tabs.
    syncChannelRef.current?.postMessage({ source: "pocketbase-java", type: "theme", theme: mode });
  }, []);

  return useMemo(
    () => ({
      themeMode,
      resolvedTheme,
      setThemeMode,
    }),
    [themeMode, resolvedTheme, setThemeMode]
  );
}

export type ThemeState = ReturnType<typeof useTheme>;
