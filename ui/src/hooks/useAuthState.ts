/**
 * useAuthState — authentication session, MFA/OTP challenge, and login
 * attempt tracking extracted from the root App component.
 *
 * This hook owns the auth-related state machine but does NOT own the network
 * calls themselves (those stay in App because they depend on 10+ other state
 * setters like refreshCollections, refreshSettings, etc.).
 *
 * The hook provides:
 *   - Session state: token, authRecord, setupRequired, authenticated
 *   - MFA/OTP challenge state: mfaChallenge, otpCode
 *   - Login attempt tracking: failedCount, captchaCode, accountLocked
 *   - Setters for all of the above
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { RecordItem } from "../types/api";

export type MfaChallenge = {
  mfaId: string;
  otpId: string;
  email: string;
};

const CAPTCHA_AFTER_FAILURES = 3;
const MAX_AUTH_FAILURES = 10;
const LOCK_DURATION_MS = 10 * 60 * 1000;
const AUTH_ATTEMPTS_KEY = "pbj_auth_attempts";

function generateCaptchaCode(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  return Array.from({ length: 5 }, () => chars[Math.floor(Math.random() * chars.length)]).join("");
}

type AuthAttemptState = { count: number; lockedUntil: number };

function getAuthAttemptState(email: string): AuthAttemptState {
  try {
    const raw = localStorage.getItem(AUTH_ATTEMPTS_KEY);
    if (!raw) return { count: 0, lockedUntil: 0 };
    const data = JSON.parse(raw) as Record<string, AuthAttemptState>;
    const normalized = email.trim().toLowerCase();
    return data[normalized] ?? { count: 0, lockedUntil: 0 };
  } catch {
    return { count: 0, lockedUntil: 0 };
  }
}

function updateAuthAttempt(email: string, count: number, lock: boolean) {
  try {
    const raw = localStorage.getItem(AUTH_ATTEMPTS_KEY);
    const data = raw ? (JSON.parse(raw) as Record<string, AuthAttemptState>) : {};
    const normalized = email.trim().toLowerCase();
    const lockedUntil = lock ? Date.now() + LOCK_DURATION_MS : 0;
    data[normalized] = { count, lockedUntil };
    localStorage.setItem(AUTH_ATTEMPTS_KEY, JSON.stringify(data));
  } catch {
    // localStorage may be unavailable (private mode); degrade gracefully.
  }
}

function clearAuthAttempt(email: string) {
  try {
    const raw = localStorage.getItem(AUTH_ATTEMPTS_KEY);
    if (!raw) return;
    const data = JSON.parse(raw) as Record<string, AuthAttemptState>;
    delete data[email.trim().toLowerCase()];
    localStorage.setItem(AUTH_ATTEMPTS_KEY, JSON.stringify(data));
  } catch {
    // Ignore.
  }
}

export function isAuthIdentityLocked(email: string): boolean {
  return getAuthAttemptState(email).lockedUntil > Date.now();
}

export function failedAttemptsFromError(error: unknown): number | null {
  if (error && typeof error === "object" && "data" in error) {
    const data = (error as { data?: unknown }).data;
    if (data && typeof data === "object" && "attempts" in data) {
      const attempts = (data as { attempts?: unknown }).attempts;
      if (typeof attempts === "number") return attempts;
    }
  }
  return null;
}

export function useAuthState() {
  const [token, setAuthToken] = useState(() => localStorage.getItem("pbj_token") ?? "");
  const [authRecord, setAuthRecord] = useState<RecordItem | null>(null);
  const [setupRequired, setSetupRequired] = useState(true);
  const [mfaChallenge, setMfaChallenge] = useState<MfaChallenge | null>(null);
  const [otpCode, setOtpCode] = useState("");
  const [authEmail, setAuthEmail] = useState("");
  const [authPassword, setAuthPassword] = useState("");
  const [captchaInput, setCaptchaInput] = useState("");
  const [failedCount, setFailedCount] = useState(0);
  const [captchaCode, setCaptchaCode] = useState(generateCaptchaCode);
  const [authLockTick, setAuthLockTick] = useState(0);

  const authLockedUntil = getAuthAttemptState(authEmail).lockedUntil;
  const accountLocked = authLockedUntil > Date.now();

  const authenticated = Boolean(token) && !setupRequired;

  const refreshCaptcha = useCallback(() => {
    setCaptchaCode(generateCaptchaCode());
    setCaptchaInput("");
  }, []);

  // Update failed count when email changes (per-identity tracking).
  useEffect(() => {
    const state = getAuthAttemptState(authEmail);
    setFailedCount(state.count);
  }, [authEmail, authLockTick]);

  // Auto-unlock timer: re-render when lock expires.
  useEffect(() => {
    if (authLockedUntil <= Date.now()) return;
    const delay = Math.max(250, authLockedUntil - Date.now() + 50);
    const timer = window.setTimeout(() => setAuthLockTick((tick) => tick + 1), delay);
    return () => window.clearTimeout(timer);
  }, [authEmail, authLockedUntil]);

  const recordAuthSuccess = useCallback((email: string) => {
    clearAuthAttempt(email);
    setFailedCount(0);
  }, []);

  const recordAuthFailure = useCallback(
    (error: unknown, email: string): { locked: boolean } => {
      const prev = getAuthAttemptState(email);
      const serverCount = failedAttemptsFromError(error);
      const lockedByServer =
        error && typeof error === "object" && "status" in error && (error as { status: number }).status === 429;
      let count = prev.count + 1;
      if (serverCount != null && !lockedByServer) {
        count = Math.max(count, serverCount);
      }
      const lock = count >= MAX_AUTH_FAILURES;
      updateAuthAttempt(email, count, lock);
      setFailedCount(count);
      refreshCaptcha();
      return { locked: lock };
    },
    [refreshCaptcha]
  );

  const resetAuthState = useCallback(() => {
    setAuthToken("");
    setAuthRecord(null);
    setSetupRequired(true);
    setMfaChallenge(null);
    setOtpCode("");
    setAuthEmail("");
    setAuthPassword("");
    setCaptchaInput("");
    setFailedCount(0);
    setCaptchaCode(generateCaptchaCode());
    localStorage.removeItem("pbj_token");
  }, []);

  const logout = useCallback(() => {
    resetAuthState();
  }, [resetAuthState]);

  return useMemo(
    () => ({
      // Session
      token,
      setAuthToken,
      authRecord,
      setAuthRecord,
      setupRequired,
      setSetupRequired,
      authenticated,
      logout,
      // MFA/OTP
      mfaChallenge,
      setMfaChallenge,
      otpCode,
      setOtpCode,
      // Form fields
      authEmail,
      setAuthEmail,
      authPassword,
      setAuthPassword,
      captchaInput,
      setCaptchaInput,
      // Attempt tracking
      failedCount,
      captchaCode,
      accountLocked,
      authLockedUntil,
      refreshCaptcha,
      recordAuthSuccess,
      recordAuthFailure,
      // Constants
      captchaThreshold: CAPTCHA_AFTER_FAILURES,
      maxFailures: MAX_AUTH_FAILURES,
    }),
    [
      token,
      authRecord,
      setupRequired,
      authenticated,
      mfaChallenge,
      otpCode,
      authEmail,
      authPassword,
      captchaInput,
      failedCount,
      captchaCode,
      accountLocked,
      authLockedUntil,
      logout,
      refreshCaptcha,
      recordAuthSuccess,
      recordAuthFailure,
    ]
  );
}

export type AuthState = ReturnType<typeof useAuthState>;
