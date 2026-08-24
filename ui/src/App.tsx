import {
  Activity,
  ArrowRight,
  Archive,
  CheckSquare2,
  ChevronDown,
  ChevronRight,
  ChevronUp,
  Clock3,
  Code2,
  Columns3,
  Copy,
  Database,
  Download,
  Edit3,
  GripVertical,
  FileArchive,
  FileUp,
  GitBranch,
  HardDrive,
  KeyRound,
  ListFilter,
  Lock,
  LogOut,
  Mail,
  Minus,
  Moon,
  Network,
  Pin,
  PinOff,
  Plus,
  Play,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
  Server,
  Settings,
  Shield,
  Square,
  Sun,
  Trash2,
  Unlock,
  Upload,
  Users,
  Info,
  X
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type {
  AnimationEvent as ReactAnimationEvent,
  FormEvent,
  KeyboardEvent as ReactKeyboardEvent,
  ReactNode,
  RefObject
} from "react";
import type { TFunction } from "i18next";
import { AuthActionPages } from "./AuthActionPages";
import { DropdownSelect } from "./components/DropdownSelect";
import { FieldEditor } from "./components/FieldEditor";
import { useToasts } from "./hooks/useToasts";
import { useTheme } from "./hooks/useTheme";
import { useRecordSelection } from "./hooks/useRecordSelection";
import { useColumnPreferences } from "./hooks/useColumnPreferences";
import { useCollections } from "./hooks/useCollections";
import type { FieldSchema as SharedFieldSchema } from "./types/api";
import { fieldMultiplicity } from "./domain/fields";
import { formatDate as sharedFormatDate, formatValue as sharedFormatValue } from "./utils/date";

import { useTranslation } from "react-i18next";
import { LanguageSelector } from "./components/LanguageSelector";
import { AccentColorPicker } from "./components/AccentColorPicker";
import { ApiPreview } from "./components/ApiPreview";
import { CodeEditor, buildRuleCompletions } from "./components/CodeEditor";
import { ConfirmDialog } from "./components/ConfirmDialog";
import { CopyButton } from "./components/CopyButton";
import { buildIndex, IndexManager, parseIndex } from "./components/IndexManager";
import type { ConfirmRequest } from "./components/ConfirmDialog";
import { RecordFieldControl } from "./components/RecordFieldControl";
import { RefreshButton } from "./components/RefreshButton";
import { FileFieldControl } from "./components/FileFieldControl";
import { PasswordInput } from "./components/PasswordInput";
import { Switch } from "./components/Switch";
import { AuthRecordActions } from "./components/AuthRecordActions";
import type { AuthRecordLink, ImpersonationResult } from "./components/AuthRecordActions";
import { CollectionsOverview } from "./components/CollectionsOverview";
import { LogDetailsDrawer } from "./components/LogDetailsDrawer";
import { ResizableSidebar, clampSidebarWidth } from "./components/ResizableSidebar";
import {
  AppleClientSecretAssistant,
  OidcDiscoveryAssistant
} from "./components/OAuthProviderAssistants";
import type { AppleClientSecretInput } from "./components/OAuthProviderAssistants";
import {
  recordListRelationExpandPaths,
  recordSummary,
  relationSearchFilter,
  relationTarget
} from "./components/RelationPicker";
import type { RelationCollection, RelationFetcher, RelationRecord } from "./components/RelationPicker";
import { useModalInteraction } from "./components/useModalInteraction";


type HealthResponse = {
  data: {
    canBackup?: boolean;
    realIP?: string;
    possibleProxyHeader?: string;
  };
};

type BootstrapStatus = {
  required: boolean;
};

type ListResponse<T> = {
  page: number;
  perPage: number;
  totalItems: number;
  totalPages: number;
  items: T[];
};

type ApiError = {
  message?: string;
  data?: unknown;
};

type MfaChallenge = {
  mfaId: string;
  otpId: string;
  email: string;
};

type PendingConfirm = ConfirmRequest & { resolve: (confirmed: boolean) => void };

// Re-export the shared FieldSchema type so all 11K lines of App.tsx keep working
// without changing every import. This is the single source of truth.
type FieldSchema = SharedFieldSchema;

type PasswordAuthConfig = {
  enabled?: boolean;
  identityFields?: string[];
};

type OtpConfig = {
  enabled?: boolean;
  duration?: number;
  length?: number;
  emailTemplate?: EmailTemplate;
};

type MfaConfig = {
  enabled?: boolean;
  duration?: number;
  rule?: string | null;
};

type EmailTemplate = {
  subject?: string;
  body?: string;
};

type TokenConfig = {
  duration?: number;
  secret?: string;
};

type AuthAlertConfig = {
  enabled?: boolean;
  emailTemplate?: EmailTemplate;
};

type OAuth2ProviderConfig = {
  name: string;
  clientId?: string;
  clientSecret?: string;
  authURL?: string;
  tokenURL?: string;
  userInfoURL?: string;
  displayName?: string;
  scopes?: string[];
  pkce?: boolean;
  extra?: Record<string, unknown>;
};

type OAuth2Config = {
  enabled?: boolean;
  providers?: OAuth2ProviderConfig[];
  mappedFields?: OAuth2MappedFields;
};

type OAuth2MappedFields = {
  id?: string;
  name?: string;
  username?: string;
  avatarURL?: string;
};

type CollectionSchema = {
  id: string;
  name: string;
  type: "base" | "auth" | "view" | string;
  system?: boolean;
  fields?: FieldSchema[];
  listRule?: string | null;
  viewRule?: string | null;
  createRule?: string | null;
  updateRule?: string | null;
  deleteRule?: string | null;
  passwordAuth?: PasswordAuthConfig;
  otp?: OtpConfig;
  mfa?: MfaConfig;
  oauth2?: OAuth2Config;
  authAlert?: AuthAlertConfig;
  authToken?: TokenConfig;
  passwordResetToken?: TokenConfig;
  verificationToken?: TokenConfig;
  emailChangeToken?: TokenConfig;
  fileToken?: TokenConfig;
  verificationTemplate?: EmailTemplate;
  resetPasswordTemplate?: EmailTemplate;
  confirmEmailChangeTemplate?: EmailTemplate;
  authRule?: string | null;
  manageRule?: string | null;
  viewQuery?: string | null;
  indexes?: string[];
  created?: string;
  updated?: string;
};

type OAuthProviderMetadata = {
  name: string;
  displayName: string;
  logo: string;
};

type AuthMethodProvider = OAuthProviderMetadata & {
  state?: string;
  authURL?: string;
  authUrl?: string;
  codeVerifier?: string;
  codeChallenge?: string;
  codeChallengeMethod?: string;
};

type AuthMethodsResponse = {
  password: {
    enabled: boolean;
    identityFields: string[];
  };
  oauth2: {
    enabled: boolean;
    providers: AuthMethodProvider[];
  };
  mfa: {
    enabled: boolean;
    duration: number;
  };
  otp: {
    enabled: boolean;
    duration: number;
  };
  authProviders?: AuthMethodProvider[];
  usernamePassword?: boolean;
  emailPassword?: boolean;
};

type RecordItem = Record<string, unknown> & {
  id: string;
  collectionId?: string;
  collectionName?: string;
  created?: string;
  updated?: string;
};

type AuthResponse = {
  token: string;
  record: RecordItem;
  meta?: Record<string, unknown>;
};

type BackupInfo = {
  key: string;
  size: number;
  modified: string;
};

type AppSettings = Record<string, unknown>;

type LogItem = {
  id: string;
  created: string;
  updated?: string;
  level: number;
  message: string;
  data: Record<string, unknown>;
};

type LogStat = {
  date: string;
  total: number;
};

type LogTimeRange = {
  start: string;
  end: string;
};

type LogPageCache = {
  scope: string;
  pages: Map<number, ListResponse<LogItem>>;
  stats: LogStat[] | null;
};

type CronJob = {
  id: string;
  expression: string;
};

type SqlColumn = {
  name: string;
  type?: string;
  nullable?: boolean;
};

type SqlResult = {
  columns?: SqlColumn[];
  rows?: unknown[][];
  affectedRows?: number;
};

type ViewQueryPreview = {
  fields: FieldSchema[];
  sample: Record<string, unknown>[];
};

type QueryState = {
  filter: string;
  sort: string;
  perPage: number;
};

type SortDirection = "asc" | "desc";
type ThemeMode = "light" | "dark" | "auto";
type ResolvedTheme = "light" | "dark";

type ViewName =
  | "records"
  | "schema"
  | "settings"
  | "mail"
  | "storage"
  | "backups"
  | "crons"
  | "export"
  | "import"
  | "sql"
  | "logs";

type AdminRoute = {
  view: ViewName;
  collectionName?: string;
  params: Record<string, string>;
};

function adminRouteFromHash(hash: string): AdminRoute | null {
  if (!hash || !hash.startsWith("#/")) return null;
  const [rawPath, rawQuery = ""] = hash.slice(1).split("?", 2);
  const params = Object.fromEntries(new URLSearchParams(rawQuery).entries());
  const segments = rawPath
    .slice(1)
    .split("/")
    .filter(Boolean)
    .map((segment) => {
      try {
        return decodeURIComponent(segment);
      } catch {
        return segment;
      }
  });
  if (segments[0] === "logs") {
    return { view: "logs", params };
  }
  if (segments[0] === "settings") {
    const settingsRoutes: Record<string, ViewName> = {
      "": "settings",
      general: "settings",
      mail: "mail",
      storage: "storage",
      backups: "backups",
      crons: "crons",
      "export-collections": "export",
      export: "export",
      "import-collections": "import",
      import: "import",
      sql: "sql"
    };
    return { view: settingsRoutes[segments[1] ?? ""] ?? "settings", params };
  }
  if (segments[0] === "collections") {
    // Accept the existing path route and the official query form (#/collections?collection=...).
    const collectionName = params.collection || segments[1];
    const routeView = params.view || params.tab || segments[2];
    // The standalone schema view was removed; legacy schema/fields routes fall back to records.
    const view = "records";
    return collectionName ? { view, collectionName, params } : { view: "records", params };
  }
  return null;
}

function adminHashFor(view: ViewName, collectionName?: string, params: Record<string, string | number | undefined> = {}) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "" && value !== 0) search.set(key, String(value));
  }
  const withParams = (base: string) => (search.size ? `${base}?${search.toString()}` : base);
  const settingsRoutes: Partial<Record<ViewName, string>> = {
    settings: "#/settings",
    mail: "#/settings/mail",
    storage: "#/settings/storage",
    backups: "#/settings/backups",
    crons: "#/settings/crons",
    export: "#/settings/export-collections",
    import: "#/settings/import-collections",
    sql: "#/settings/sql"
  };
  if (view === "logs") return withParams("#/logs");
  if (settingsRoutes[view]) return withParams(settingsRoutes[view]!);
  const base = collectionName ? `#/collections/${encodeURIComponent(collectionName)}` : "#/collections";
  return withParams(`${base}/records`);
}

function routeNumber(value: string | undefined, fallback: number, minimum: number, maximum: number) {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) ? Math.max(minimum, Math.min(maximum, parsed)) : fallback;
}

function queryFromRoute(params: Record<string, string>): QueryState {
  return {
    filter: params.filter ?? "",
    sort: params.sort || "-created",
    perPage: routeNumber(params.perPage, 50, 1, 500)
  };
}

function recordRouteParams(query: QueryState, page = 1, recordId = "") {
  return {
    filter: query.filter || undefined,
    sort: query.sort === "-created" ? undefined : query.sort,
    perPage: query.perPage === 50 ? undefined : query.perPage,
    page: page > 1 ? page : undefined,
    record: recordId || undefined
  };
}

function logRouteParams(
  filter: string,
  page = 1,
  includeSuperuserRequests = false,
  logId = "",
  timeRange: LogTimeRange | null = null
) {
  return {
    filter: filter || undefined,
    page: page > 1 ? page : undefined,
    superuserRequests: includeSuperuserRequests ? "1" : undefined,
    log: logId || undefined,
    logStart: timeRange?.start || undefined,
    logEnd: timeRange?.end || undefined
  };
}

function logFilterWithVisibility(filter: string, includeSuperuserRequests: boolean) {
  const trimmed = filter.trim();
  if (includeSuperuserRequests) return trimmed;
  const superuserFilter = 'data.auth != "_superusers"';
  return trimmed ? `(${trimmed}) && (${superuserFilter})` : superuserFilter;
}

function logTimeRangeFromRoute(params: Record<string, string>): LogTimeRange | null {
  const start = params.logStart ?? "";
  const end = params.logEnd ?? "";
  const startTime = parseLogDate(start)?.getTime();
  const endTime = parseLogDate(end)?.getTime();
  return startTime !== undefined && endTime !== undefined && endTime > startTime ? { start, end } : null;
}

function translateErrorMessage(err: unknown, t: (key: string, defaultVal: string) => string): string {
  const text = errorMessage(err);
  if (!text) return t("notifications.failed_to_authenticate", "Failed to authenticate. Incorrect email or password.");
  if (text.includes("Failed to authenticate")) {
    return t("notifications.failed_to_authenticate", "Failed to authenticate. Incorrect email or password.");
  }
  if (text.includes("Too many failed login attempts")) {
    return t("notifications.too_many_login_attempts", "Too many failed login attempts. Please try again after 10 minutes.");
  }
  if (text.includes("Too many requests")) {
    return t("notifications.too_many_requests", "Too many requests. Please try again later.");
  }
  return text;
}

function parseLogDate(value: string) {
  const parsed = new Date(value.includes("T") ? value : value.replace(" ", "T"));
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function formatLogHour(value: Date) {
  return value.toISOString().replace("T", " ");
}

function nextLogHour(value: string) {
  const date = parseLogDate(value);
  return date ? formatLogHour(new Date(date.getTime() + 60 * 60 * 1000)) : "";
}

/** Nice ceiling for chart Y scale (e.g. 819 → 1000). */
function niceChartCeil(value: number): number {
  if (value <= 0) return 1;
  const exp = Math.floor(Math.log10(value));
  const base = Math.pow(10, exp);
  const scaled = value / base;
  const nice = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10;
  return nice * base;
}

function chartYTicks(maxValue: number): number[] {
  const top = niceChartCeil(maxValue);
  if (top <= 1) return [0, top];
  if (top <= 2) return [0, top];
  return [0, top / 2, top];
}

/** Official chart axis always uses compact English am/pm (7pm, 12am). */
function formatLogChartHourAmPm(date: Date): string {
  const h = date.getHours();
  if (h === 0) return "12am";
  if (h === 12) return "12pm";
  return h < 12 ? `${h}am` : `${h - 12}pm`;
}

function formatLogChartMonthDay(date: Date): string {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "2-digit" }).format(date);
}

function formatLogChartAxisLabel(value: string, showDate: boolean): { time: string; date?: string } {
  const date = parseLogDate(value);
  if (!date) return { time: value };
  return showDate
    ? { time: formatLogChartHourAmPm(date), date: formatLogChartMonthDay(date) }
    : { time: formatLogChartHourAmPm(date) };
}

/** Tooltip range like official: "Aug 01 11pm-12am". */
function formatLogChartHourRange(value: string): string {
  const start = parseLogDate(value);
  if (!start) return value;
  const end = new Date(start.getTime() + 60 * 60 * 1000);
  return `${formatLogChartMonthDay(start)} ${formatLogChartHourAmPm(start)}-${formatLogChartHourAmPm(end)}`;
}

function formatChartYLabel(value: number): string {
  return new Intl.NumberFormat("en-US").format(value);
}

/**
 * Official logs chart is a continuous step-area (not gapped bars).
 * Path is built in a fixed viewBox so it scales with the container.
 */
function buildLogChartStepPaths(values: number[], yMax: number, vbW = 1000, vbH = 100) {
  const n = values.length;
  if (n === 0 || yMax <= 0) {
    return { area: "", line: "" };
  }
  const barW = vbW / n;
  const yAt = (v: number) => vbH - (Math.max(0, v) / yMax) * vbH;
  let line = `M 0 ${yAt(values[0])}`;
  let area = `M 0 ${vbH} L 0 ${yAt(values[0])}`;
  for (let i = 0; i < n; i++) {
    const x0 = i * barW;
    const x1 = (i + 1) * barW;
    const y = yAt(values[i]);
    if (i === 0) {
      line = `M ${x0} ${y}`;
      area = `M ${x0} ${vbH} L ${x0} ${y}`;
    } else {
      line += ` L ${x0} ${y}`;
      area += ` L ${x0} ${y}`;
    }
    line += ` L ${x1} ${y}`;
    area += ` L ${x1} ${y}`;
  }
  area += ` L ${vbW} ${vbH} Z`;
  return { area, line };
}

function combineLogFilters(...filters: string[]) {
  return filters
    .map((filter) => filter.trim())
    .filter(Boolean)
    .map((filter) => `(${filter})`)
    .join(" && ");
}

function logTimeRangeFilter(range: LogTimeRange) {
  // The stats endpoint uses PocketBase's human-readable bucket format with a
  // space between date and hour, while record timestamps retain ISO `T`.
  // Convert only at the request boundary so the chart labels and deep links
  // stay readable but RuleEvaluator compares compatible values.
  const timestamp = (value: string) => parseLogDate(value)?.toISOString() ?? value;
  return `created >= ${JSON.stringify(timestamp(range.start))} && created < ${JSON.stringify(timestamp(range.end))}`;
}

/**
 * The stats API only returns occupied buckets. Insert a bounded set of empty
 * hourly buckets so the chart's scale and drag selection remain truthful.
 */
function fillLogStatGaps(stats: LogStat[]) {
  const normalized = stats
    .map((item) => ({ ...item, date: item.date.trim(), timestamp: parseLogDate(item.date)?.getTime() }))
    .filter((item): item is LogStat & { timestamp: number } => item.timestamp !== undefined)
    .sort((left, right) => left.timestamp - right.timestamp);
  if (normalized.length === 0) return stats.slice(-28);

  const totals = new Map(normalized.map((item) => [formatLogHour(new Date(item.timestamp)), Number(item.total || 0)]));
  const first = normalized[0].timestamp;
  const last = normalized[normalized.length - 1].timestamp;
  const hours = Math.round((last - first) / (60 * 60 * 1000));
  // A large retention range may span years; keep its sparse buckets rather than
  // allocating tens of thousands of synthetic DOM nodes.
  if (hours > 24 * 31) return normalized.map(({ date, total }) => ({ date, total }));

  const result: LogStat[] = [];
  for (let timestamp = first; timestamp <= last; timestamp += 60 * 60 * 1000) {
    const date = formatLogHour(new Date(timestamp));
    result.push({ date, total: totals.get(date) ?? 0 });
  }
  return result;
}

type CollectionEditorState = {
  mode: "create" | "edit";
  collection?: CollectionSchema;
};

type RecordEditorState = {
  record?: RecordItem;
  mode?: "duplicate";
  /** Nested relation forms need a stable, parent-scoped draft namespace. */
  draftKey?: string;
};

/** A record editor opened from a relation field. It deliberately stays off the
 * primary hash route so the parent record draft remains intact underneath it. */
type RelationRecordEditorState = RecordEditorState & {
  editorId: string;
  collection: CollectionSchema;
  onSaved?: (record: RelationRecord) => void;
};

type ToastState = {
  id: number;
  kind: "ok" | "error";
  message: string;
};

type S3TestState = {
  status: "idle" | "testing" | "success" | "error";
  message: string;
};

type BackupOperation = {
  kind: "create" | "restore";
  key?: string;
};

type OAuthResultState = {
  provider: AuthMethodProvider;
  response: AuthResponse;
};

type ApiOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
};

const TOKEN_KEY = "pbj_token";
const PINNED_COLLECTIONS_KEY = "pbj_pinned_collections";
const HIDDEN_COLUMNS_KEY = "pbj_hidden_columns";
// A visibility snapshot records every hidden column, including schema defaults.
// Older preference arrays only tracked ad-hoc hidden columns and have no marker.
const HIDDEN_COLUMNS_SNAPSHOT_MARKER = "__pbj_hidden_columns_v2__";
const THEME_KEY = "pbj_theme";
const ACTIVE_COLLECTION_KEY = "pbj_active_collection";
const SIDEBAR_WIDTH_KEY = "pbj_sidebar_width";
/**
 * Per-identity failed login state in sessionStorage:
 * { [emailLower]: { count, lockedUntil } }. Survives refresh; server remains authoritative.
 */
const AUTH_ATTEMPTS_KEY = "pbj_auth_attempts_v1";
/** Legacy single counter — migrated once into AUTH_ATTEMPTS_KEY. */
const AUTH_FAILED_COUNT_KEY_LEGACY = "pbj_auth_failed_count";
/** Show captcha after this many failed login attempts in the current cycle. */
const CAPTCHA_AFTER_FAILURES = 3;
/** Match server AuthFailedAttemptTracker.MAX_FAILED_ATTEMPTS / LOCK_DURATION. */
const MAX_AUTH_FAILURES = 10;
const AUTH_LOCK_DURATION_MS = 10 * 60 * 1000;
const SEARCH_HISTORY_LIMIT = 15;
// Keep bulk deletion bounded even when selection spans many loaded pages. This
// matches PocketBase's batch-oriented UX without flooding a Java server with an
// unbounded Promise.all request fan-out.
const RECORD_DELETE_BATCH_SIZE = 100;
const DEFAULT_FIELDS = [{ name: "title", type: "text", required: true }];
const SYSTEM_RECORD_KEYS = new Set(["id", "collectionId", "collectionName", "created", "updated", "expand"]);

function App() {
  const { t } = useTranslation();
  const [hash, setHash] = useState(window.location.hash);
  useEffect(() => {
    const handler = () => setHash(window.location.hash);
    window.addEventListener("hashchange", handler);
    return () => window.removeEventListener("hashchange", handler);
  }, []);

  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) || "");
  const [authRecord, setAuthRecord] = useState<RecordItem | null>(null);
  const [health, setHealth] = useState<HealthResponse["data"] | null>(null);
  const [setupRequired, setSetupRequired] = useState(true);
  const [confirmState, setConfirmState] = useState<PendingConfirm | null>(null);
  const [apiPreviewOpen, setApiPreviewOpen] = useState(false);
  const [collectionsOverviewOpen, setCollectionsOverviewOpen] = useState(false);
  const [mfaChallenge, setMfaChallenge] = useState<MfaChallenge | null>(null);
  const [otpCode, setOtpCode] = useState("");
  const {
    collections, setCollections,
    selectedName, setSelectedName,
    selected: selectedCollection,
    collectionSearch, setCollectionSearch,
    visibleCollections,
    pinnedCollectionNames,
    togglePinned: togglePinnedCollection,
  } = useCollections();
  const [records, setRecords] = useState<RecordItem[]>([]);
  const [recordPage, setRecordPage] = useState<ListResponse<RecordItem> | null>(null);
  const [recordRoutePage, setRecordRoutePage] = useState(1);
  const [recordRouteId, setRecordRouteId] = useState("");
  const [query, setQuery] = useState<QueryState>({ filter: "", sort: "-created", perPage: 50 });
  const [view, setView] = useState<ViewName>("records");
  const [loading, setLoading] = useState(false);
  const { toasts, notify, dismissToast, pauseToast, resumeToast } = useToasts();

  const [authEmail, setAuthEmail] = useState(() => new URLSearchParams(window.location.search).get("demoEmail") ?? "");
  // Passwords in URLs leak through browser history, referers and access logs. Keep
  // the local demo convenience out of production bundles entirely.
  const [authPassword, setAuthPassword] = useState(() =>
    import.meta.env.DEV ? new URLSearchParams(window.location.search).get("demoPassword") ?? "" : ""
  );
  const [failedCount, setFailedCount] = useState(0);
  const [authLockedUntil, setAuthLockedUntil] = useState(0);
  const [captchaCode, setCaptchaCode] = useState(generateCaptchaCode);
  const [captchaInput, setCaptchaInput] = useState("");
  // Re-render when a lock timer expires so the submit button reappears.
  const [authLockTick, setAuthLockTick] = useState(0);

  const accountLocked = authLockedUntil > Date.now();

  useLayoutEffect(() => {
    if (!import.meta.env.DEV) return;
    const url = new URL(window.location.href);
    if (!url.searchParams.has("demoPassword")) return;
    url.searchParams.delete("demoPassword");
    window.history.replaceState(window.history.state, "", `${url.pathname}${url.search}${url.hash}`);
  }, []);

  // Load per-email attempt state whenever the identity field changes.
  useEffect(() => {
    const state = getAuthAttemptState(authEmail);
    setFailedCount(state.count);
    setAuthLockedUntil(state.lockedUntil);
  }, [authEmail, authLockTick]);

  useEffect(() => {
    if (authLockedUntil <= Date.now()) return;
    const delay = Math.max(250, authLockedUntil - Date.now() + 50);
    const timer = window.setTimeout(() => {
      clearAuthAttemptState(authEmail);
      setAuthLockTick((n) => n + 1);
    }, delay);
    return () => window.clearTimeout(timer);
  }, [authEmail, authLockedUntil]);

  const updateAuthAttempt = useCallback((email: string, count: number, lock: boolean) => {
    const nextCount = Math.max(0, count);
    const shouldLock = lock || nextCount >= MAX_AUTH_FAILURES;
    const prev = getAuthAttemptState(email);
    const now = Date.now();
    let lockedUntil = 0;
    if (shouldLock) {
      lockedUntil =
        prev.lockedUntil > now ? prev.lockedUntil : now + AUTH_LOCK_DURATION_MS;
    }
    writeAuthAttemptState(email, nextCount, lockedUntil);
    if (normalizeAuthIdentity(email) === normalizeAuthIdentity(authEmail)) {
      setFailedCount(nextCount);
      setAuthLockedUntil(lockedUntil);
    }
  }, [authEmail]);

  const refreshCaptcha = useCallback(() => {
    setCaptchaCode(generateCaptchaCode());
    setCaptchaInput("");
  }, []);
  const [collectionEditor, setCollectionEditor] = useState<CollectionEditorState | null>(null);
  const [recordEditor, setRecordEditor] = useState<RecordEditorState | null>(null);
  const [relationRecordEditors, setRelationRecordEditors] = useState<RelationRecordEditorState[]>([]);
  const [backups, setBackups] = useState<BackupInfo[]>([]);
  const [backupOperation, setBackupOperation] = useState<BackupOperation | null>(null);
  const [backupName, setBackupName] = useState("");
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [settingsDraft, setSettingsDraft] = useState("");
  const [accentPreview, setAccentPreview] = useState<string | null>(null);
  const [logsSettingsOpen, setLogsSettingsOpen] = useState(false);
  const [logs, setLogs] = useState<LogItem[]>([]);
  const [logPage, setLogPage] = useState<ListResponse<LogItem> | null>(null);
  const [logRoutePage, setLogRoutePage] = useState(1);
  const [logRouteId, setLogRouteId] = useState("");
  // Keep the editable value separate from the hash-backed query so typing does
  // not start a request before the user chooses Apply.
  const [logFilter, setLogFilter] = useState("");
  const [logFilterDraft, setLogFilterDraft] = useState("");
  const [logTimeRange, setLogTimeRange] = useState<LogTimeRange | null>(null);
  const [includeSuperuserRequests, setIncludeSuperuserRequests] = useState(false);
  const [selectedLog, setSelectedLog] = useState<LogItem | null>(null);
  const [logStats, setLogStats] = useState<LogStat[]>([]);
  const [isLogListLoading, setIsLogListLoading] = useState(false);
  const [isLogStatsLoading, setIsLogStatsLoading] = useState(false);
  const [isLogFirstLoadReady, setIsLogFirstLoadReady] = useState(false);
  const [logRefreshVersion, setLogRefreshVersion] = useState(0);
  const [crons, setCrons] = useState<CronJob[]>([]);
  const [oauthProviders, setOauthProviders] = useState<OAuthProviderMetadata[]>([]);
  const [authMethods, setAuthMethods] = useState<AuthMethodsResponse | null>(null);
  const [oauthResult, setOauthResult] = useState<OAuthResultState | null>(null);
  const [oauthTestingProvider, setOauthTestingProvider] = useState<string>("");
  const { themeMode, resolvedTheme, setThemeMode } = useTheme();
  const [sidebarWidth, setSidebarWidth] = useState(readSidebarWidth);
  const {
    selectedIds: selectedRecordIds,
    setSelectedIds: setSelectedRecordIds,
    toggleSelected: toggleRecordSelection,
    toggleAll: toggleCurrentPageSelection,
    clearSelection: clearRecordSelection,
  } = useRecordSelection();
  const [recordsNeedRefresh, setRecordsNeedRefresh] = useState(false);
  const [sqlQuery, setSqlQuery] = useState("select 1");
  const [sqlResult, setSqlResult] = useState<SqlResult | null>(null);
  const [sqlError, setSqlError] = useState("");
  const [sqlElapsedMs, setSqlElapsedMs] = useState<number | null>(null);
  const [sqlHistory, setSqlHistory] = useState<string[]>(() => {
    try {
      return JSON.parse(sessionStorage.getItem("pbj_sql_history") || "[]");
    } catch {
      return [];
    }
  });
  const [exportDraft, setExportDraft] = useState("");
  const [importDraft, setImportDraft] = useState("");
  const [deleteMissingCollections, setDeleteMissingCollections] = useState(true);
  const [testEmail, setTestEmail] = useState(() => localStorage.getItem("pbj_test_email_recipient") ?? "");
  const [testEmailTemplate, setTestEmailTemplate] = useState("verification");
  const [testEmailCollection, setTestEmailCollection] = useState("_superusers");
  const [testS3Target, setTestS3Target] = useState("storage");
  const [s3TestState, setS3TestState] = useState<S3TestState>({ status: "idle", message: "" });
  const backupUploadRef = useRef<HTMLInputElement>(null);
  const recordPageCacheRef = useRef<{ scope: string; pages: Map<number, ListResponse<RecordItem>> }>({
    scope: "",
    pages: new Map()
  });
  const logPageCacheRef = useRef<LogPageCache>({ scope: "", pages: new Map(), stats: null });
  const recordsLoadGenerationRef = useRef(0);
  const logsLoadGenerationRef = useRef(0);
  const logLoadScopeRef = useRef<string | null>(null);
  const recordDetailGenerationRef = useRef(0);
  const relationEditorSequenceRef = useRef(0);
  const s3TestRequestIdRef = useRef(0);
  const sqlHideControlsConfirmationRef = useRef(false);
  const syncChannelRef = useRef<BroadcastChannel | null>(null);
  const syncSourceRef = useRef(`pbj-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  const settingsRef = useRef<AppSettings | null>(null);
  const settingsDraftRef = useRef("");

  const authenticated = Boolean(token) && !setupRequired;
  const collectionView = view === "records" || view === "schema";
  const settingsView = isSettingsView(view);
  const selected = selectedCollection;

  // Column preferences — now in a dedicated hook, aligned with App's snapshot marker logic.
  // Cast to satisfy the stricter CollectionSchema type in types/api.ts.
  const { hiddenColumns, toggleColumn, resetColumns, setHiddenColumnsByCollection } = useColumnPreferences(
    selected as unknown as import("./types/api").CollectionSchema | null
  );

  const hideControls = settingsHideControls(settings);
  const accentColor = accentPreview ?? settingsAccentColor(settings);

  const replaceHash = useCallback((nextHash: string) => {
    if (window.location.hash === nextHash) return;
    window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}${nextHash}`);
    setHash(nextHash);
  }, []);

  const replaceRecordRoute = useCallback(
    (nextQuery = query, page = recordRoutePage, recordId = recordRouteId) => {
      if (!selectedName) return;
      replaceHash(adminHashFor("records", selectedName, recordRouteParams(nextQuery, page, recordId)));
    },
    [query, recordRouteId, recordRoutePage, replaceHash, selectedName]
  );

  const replaceLogRoute = useCallback(
    (
      filter = logFilter,
      page = logRoutePage,
      includeSuperusers = includeSuperuserRequests,
      logId = logRouteId,
      timeRange = logTimeRange
    ) => {
      replaceHash(adminHashFor("logs", undefined, logRouteParams(filter, page, includeSuperusers, logId, timeRange)));
    },
    [includeSuperuserRequests, logFilter, logRouteId, logRoutePage, logTimeRange, replaceHash]
  );

  const navigateTo = useCallback(
    (
      nextView: ViewName,
      collectionName = selectedName,
      options: { resetRecordQuery?: boolean } = {}
    ) => {
      // Filters and sort fields are collection-specific. A cross-collection
      // shortcut must not carry an invalid field reference into its target.
      const resetRecordQuery = nextView === "records" && options.resetRecordQuery;
      const nextRecordQuery = resetRecordQuery
        ? { ...query, filter: "", sort: "-created" }
        : query;
      if (resetRecordQuery) {
        setQuery((current) =>
          current.filter === nextRecordQuery.filter &&
          current.sort === nextRecordQuery.sort &&
          current.perPage === nextRecordQuery.perPage
            ? current
            : nextRecordQuery
        );
        setRecordRoutePage(1);
        setRecordRouteId("");
      }
      if ((nextView === "records" || nextView === "schema") && collectionName) {
        setSelectedName(collectionName);
      }
      setView(nextView);
      const nextHash = adminHashFor(
        nextView,
        collectionName,
        nextView === "records"
          ? recordRouteParams(nextRecordQuery, 1)
          : nextView === "logs"
            ? logRouteParams(logFilter, 1, includeSuperuserRequests, "", logTimeRange)
            : {}
      );
      if (window.location.hash !== nextHash) {
        window.location.hash = nextHash;
      }
    },
    [includeSuperuserRequests, logFilter, logTimeRange, query, selectedName]
  );

  useEffect(() => {
    if (!authenticated) return;
    const route = adminRouteFromHash(hash);
    if (!route) return;
    if ((route.view === "records" || route.view === "schema") && route.collectionName) {
      if (collections.some((collection) => collection.name === route.collectionName)) {
        setSelectedName(route.collectionName);
      }
    }
    if (route.view === "records") {
      const nextQuery = queryFromRoute(route.params);
      const nextRecordId = route.params.record ?? "";
      const collectionChanged = Boolean(route.collectionName && route.collectionName !== selectedName);
      setQuery((current) =>
        current.filter === nextQuery.filter && current.sort === nextQuery.sort && current.perPage === nextQuery.perPage
          ? current
          : nextQuery
      );
      setRecordRoutePage(routeNumber(route.params.page, 1, 1, 20));
      setRecordRouteId(nextRecordId);
      setRecordEditor((current) => {
        if (collectionChanged) return null;
        if (nextRecordId && current?.record?.id !== nextRecordId) return null;
        if (!nextRecordId && current?.record && current.mode !== "duplicate") return null;
        return current;
      });
    } else {
      setRecordRouteId("");
      setRecordEditor(null);
    }
    if (route.view === "logs") {
      const nextLogId = route.params.log ?? "";
      const nextLogFilter = route.params.filter ?? "";
      setLogFilter(nextLogFilter);
      setLogFilterDraft(nextLogFilter);
      setLogRoutePage(routeNumber(route.params.page, 1, 1, 20));
      setIncludeSuperuserRequests(route.params.superuserRequests === "1");
      setLogRouteId(nextLogId);
      setLogTimeRange(logTimeRangeFromRoute(route.params));
      setSelectedLog((current) => (current?.id === nextLogId ? current : null));
    } else {
      setLogRouteId("");
      setSelectedLog(null);
    }
    setView(route.view);
  }, [authenticated, collections, hash, selectedName]);

  const api = useCallback(
    async <T,>(path: string, options: ApiOptions = {}): Promise<T> => {
      return apiRequest<T>(path, token, options);
    },
    [token]
  );

  const getFileToken = useCallback(async () => {
    const response = await api<{ token: string }>("/api/files/token", { method: "POST" });
    return response.token;
  }, [api]);

  const generateAppleClientSecret = useCallback(
    (input: AppleClientSecretInput) =>
      api<{ secret: string }>("/api/settings/apple/generate-client-secret", { method: "POST", body: input }),
    [api]
  );

  const dryRunView = useCallback(
    (query: string) => api<ViewQueryPreview>("/api/collections/meta/dry-run-view", { method: "POST", body: { query } }),
    [api]
  );

  const refreshHealth = useCallback(async (authToken = token) => {
    const data = await apiRequest<HealthResponse>("/api/health", authToken);
    setHealth(data.data);
    return data.data;
  }, [token]);

  const refreshBootstrapStatus = useCallback(async () => {
    const status = await apiRequest<BootstrapStatus>("/api/bootstrap/superuser", "");
    setSetupRequired(status.required);
    return status;
  }, []);


  const refreshCollections = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await apiRequest<ListResponse<CollectionSchema>>("/api/collections?perPage=500&sort=name", token);
      setCollections(data.items);
      setSelectedName((current) => {
        const route = adminRouteFromHash(window.location.hash);
        if (route?.collectionName && data.items.some((collection) => collection.name === route.collectionName)) {
          return route.collectionName;
        }
        if (current && data.items.some((collection) => collection.name === current)) return current;
        const remembered = localStorage.getItem(ACTIVE_COLLECTION_KEY) || "";
        if (remembered && data.items.some((collection) => collection.name === remembered)) return remembered;
        return data.items.find((collection) => collection.name !== "_superusers")?.name ?? data.items[0]?.name ?? "";
      });
    } finally {
      setLoading(false);
    }
  }, [token]);

  const fetchRecordsPage = useCallback(
    async (
      collectionName: string,
      nextQuery: QueryState,
      page: number,
      options: { force?: boolean; signal?: AbortSignal } = {}
    ) => {
      if (!token || !collectionName) return null;
      const collection = collections.find((item) => item.name === collectionName);
      // Relation summaries can themselves contain presentable relations. Request
      // those paths up front so a list row never falls back to raw ids while its
      // nested summary is available from the existing records API.
      const relationFields = recordListRelationExpandPaths(collection, collections);
      const filter = relationSearchFilter(nextQuery.filter, collection, collections);
      const scope = JSON.stringify({
        collectionName,
        perPage: nextQuery.perPage,
        sort: nextQuery.sort,
        filter,
        expand: relationFields
      });
      let cache = recordPageCacheRef.current;
      if (cache.scope !== scope || options.force) {
        cache = { scope, pages: new Map() };
        recordPageCacheRef.current = cache;
      }
      if (!options.force) {
        const cached = cache.pages.get(page);
        if (cached) return cached;
      }
      // First page fetch requests the total count; subsequent pages (Load more,
      // deep-link pagination) skip it to stay lightweight, matching the official UI.
      const qs = buildQuery({
        page,
        perPage: nextQuery.perPage,
        sort: nextQuery.sort,
        filter,
        ...(page > 1 ? { skipTotal: 1 } : {}),
        ...(relationFields.length ? { expand: relationFields.join(",") } : {})
      });
      const data = await apiRequest<ListResponse<RecordItem>>(
        `/api/collections/${encodeURIComponent(collectionName)}/records?${qs}`,
        token,
        { signal: options.signal }
      );
      if (options.signal?.aborted) return null;
      if (recordPageCacheRef.current === cache) cache.pages.set(page, data);
      return data;
    },
    [collections, token]
  );

  const refreshRecords = useCallback(
    async (
      collectionName = selectedName,
      nextQuery = query,
      page = 1,
      options: { force?: boolean; signal?: AbortSignal } = {}
    ) => {
      if (!collectionName) return null;
      const generation = ++recordsLoadGenerationRef.current;
      setLoading(true);
      try {
        const data = await fetchRecordsPage(collectionName, nextQuery, page, {
          ...options,
          force: options.force ?? true
        });
        if (!data || options.signal?.aborted || generation !== recordsLoadGenerationRef.current) return data;
        // Subsequent pages use skipTotal so their totals are -1. Preserve the
        // first page's authoritative totals without capturing stale state.
        setRecordPage((current) =>
          data.totalItems < 0 && page > 1 && current && current.totalItems >= 0
            ? { ...data, totalItems: current.totalItems, totalPages: current.totalPages }
            : data
        );
        setRecords((prev) => (page > 1 ? mergeRecordItems(prev, data.items) : data.items));
        setRecordsNeedRefresh(false);
        return data;
      } finally {
        if (generation === recordsLoadGenerationRef.current) setLoading(false);
      }
    },
    [fetchRecordsPage, query, selectedName]
  );

  /**
   * An update response does not carry relation expansion data, while the row in
   * the loaded list often does. Merge the authoritative fields into every
   * loaded copy instead of reloading page 1 and losing the user's scroll and
   * cross-page selection.
   */
  function mergeSavedRecordIntoLoadedState(saved: RecordItem) {
    const hasExpand = Object.prototype.hasOwnProperty.call(saved, "expand");
    const merge = (current: RecordItem) =>
      current.id === saved.id
        ? { ...current, ...saved, ...(hasExpand ? {} : { expand: current.expand }) }
        : current;

    setRecords((current) => current.map(merge));
    setRecordPage((current) => (current ? { ...current, items: current.items.map(merge) } : current));

    const cache = recordPageCacheRef.current;
    for (const [page, data] of cache.pages) {
      if (!data.items.some((record) => record.id === saved.id)) continue;
      cache.pages.set(page, { ...data, items: data.items.map(merge) });
    }
  }

  /** Remove successfully deleted records from the currently loaded pages and cache. */
  function removeRecordsFromLoadedState(ids: Iterable<string>) {
    const deletedIds = new Set(ids);
    if (deletedIds.size === 0) return;
    const remove = (items: RecordItem[]) => items.filter((record) => !deletedIds.has(record.id));
    const pageCount = (totalItems: number, perPage: number) =>
      totalItems === 0 ? 0 : Math.ceil(totalItems / Math.max(1, perPage));

    setRecords(remove);
    setRecordPage((current) => {
      if (!current) return current;
      const totalItems = Math.max(0, current.totalItems - deletedIds.size);
      return {
        ...current,
        items: remove(current.items),
        totalItems,
        totalPages: pageCount(totalItems, current.perPage)
      };
    });
    setSelectedRecordIds((current) => current.filter((id) => !deletedIds.has(id)));

    const cache = recordPageCacheRef.current;
    for (const [page, data] of cache.pages) {
      const totalItems = Math.max(0, data.totalItems - deletedIds.size);
      cache.pages.set(page, {
        ...data,
        items: remove(data.items),
        totalItems,
        totalPages: pageCount(totalItems, data.perPage)
      });
    }
    // Removing a row locally is immediate and safe, but a partially loaded
    // page may now be missing records that moved up from a later page.
    if (recordPage && records.length < recordPage.totalItems) setRecordsNeedRefresh(true);
  }

  const confirm = useCallback(
    (request: ConfirmRequest) =>
      new Promise<boolean>((resolve) => {
        setConfirmState({ ...request, resolve });
      }),
    []
  );

  const fetchRelationRecords = useCallback<RelationFetcher>(
    async (collectionName, params) => {
      const collection = collections.find((item) => item.name === collectionName);
      const qs = buildQuery({
        page: params.page,
        perPage: params.perPage,
        sort: "-created",
        filter: relationSearchFilter(params.filter, collection, collections),
        expand: params.expand
      });
      return apiRequest(`/api/collections/${encodeURIComponent(collectionName)}/records?${qs}`, token);
    },
    [collections, token]
  );

  const loadMoreRecords = useCallback(() => {
    if (!selectedName || !recordPage) return;
    const nextPage = recordPage.page + 1;
    if (nextPage > recordPage.totalPages) return;
    setRecordRoutePage(nextPage);
    replaceRecordRoute(query, nextPage, recordRouteId);
  }, [query, recordPage, recordRouteId, replaceRecordRoute, selectedName]);

  const refreshBackups = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await apiRequest<BackupInfo[]>("/api/backups", token);
      setBackups(data);
    } finally {
      setLoading(false);
    }
  }, [token]);

  // While the backups page is open, poll health so an in-progress backup/restore
  // disables the controls and the list refreshes once the operation clears.
  useEffect(() => {
    if (view !== "backups" || !token) return;
    let cancelled = false;
    let wasBusy = false;
    const timer = setInterval(async () => {
      try {
        const data = await refreshHealth();
        if (cancelled) return;
        const busy = data?.canBackup === false;
        if (wasBusy && !busy) void refreshBackups();
        wasBusy = busy;
      } catch {
        // A transient health failure shouldn't kill the poll.
      }
    }, 3500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [refreshBackups, refreshHealth, token, view]);

  const refreshSettings = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await apiRequest<AppSettings>("/api/settings", token);
      setSettings(data);
      setSettingsDraft(JSON.stringify(data, null, 2));
    } finally {
      setLoading(false);
    }
  }, [token]);

  const broadcastSync = useCallback((type: "collections" | "settings" | "theme", theme?: ThemeMode) => {
    syncChannelRef.current?.postMessage({ source: syncSourceRef.current, type, theme });
  }, []);

  useEffect(() => {
    settingsRef.current = settings;
    settingsDraftRef.current = settingsDraft;
  }, [settings, settingsDraft]);

  useEffect(() => {
    if (!authenticated || typeof BroadcastChannel === "undefined") return;
    const channel = new BroadcastChannel("pocketbase-java-admin-sync");
    syncChannelRef.current = channel;
    channel.onmessage = (event: MessageEvent<{ source?: string; type?: string; theme?: ThemeMode }>) => {
      const message = event.data;
      if (!message || message.source === syncSourceRef.current) return;

      // Theme sync is handled by useTheme's own BroadcastChannel.

      if (message.type === "collections") {
        void refreshCollections().catch((error) => notify(errorMessage(error), "error"));
        return;
      }

      if (message.type === "settings") {
        const currentSettings = settingsRef.current;
        const hasLocalEdits = Boolean(
          currentSettings && settingsDraftRef.current !== JSON.stringify(currentSettings, null, 2)
        );
        if (hasLocalEdits) {
          notify(
            t(
              "settings.remote_update_pending",
              "Settings changed in another tab. Refresh after resolving your local edits."
            ),
            "error"
          );
          return;
        }
        void refreshSettings().catch((error) => notify(errorMessage(error), "error"));
      }
    };
    return () => {
      if (syncChannelRef.current === channel) syncChannelRef.current = null;
      channel.close();
    };
  }, [authenticated, notify, refreshCollections, refreshSettings, t]);

  const getLogPageCache = useCallback((scope: string): LogPageCache => {
    let cache = logPageCacheRef.current;
    if (cache.scope !== scope) {
      cache = { scope, pages: new Map(), stats: null };
      logPageCacheRef.current = cache;
    }
    return cache;
  }, []);

  const fetchLogsPage = useCallback(
    async (page: number, filter: string, scope: string, signal?: AbortSignal) => {
      if (!token) return null;
      const cache = getLogPageCache(scope);
      const cached = cache.pages.get(page);
      if (cached) return cached;

      const qs = buildQuery({ page, perPage: 50, sort: "-created", filter });
      const data = await apiRequest<ListResponse<LogItem>>(`/api/logs?${qs}`, token, { signal });
      if (signal?.aborted) return null;
      if (logPageCacheRef.current === cache) cache.pages.set(page, data);
      return data;
    },
    [getLogPageCache, token]
  );

  const fetchLogStats = useCallback(
    async (filter: string, scope: string, signal?: AbortSignal) => {
      if (!token) return null;
      const cache = getLogPageCache(scope);
      if (cache.stats !== null) return cache.stats;

      const statsQs = buildQuery({ filter });
      const data = await apiRequest<LogStat[]>(
        `/api/logs/stats${statsQs ? `?${statsQs}` : ""}`,
        token,
        { signal }
      );
      if (signal?.aborted) return null;
      if (logPageCacheRef.current === cache) cache.stats = data;
      return data;
    },
    [getLogPageCache, token]
  );

  const requestLogRefresh = useCallback(() => {
    setLogRefreshVersion((version) => version + 1);
  }, []);

  const loadMoreLogs = useCallback(() => {
    if (!logPage || logPage.page >= logPage.totalPages) return;
    const nextPage = logPage.page + 1;
    setLogRoutePage(nextPage);
    replaceLogRoute(logFilter, nextPage, includeSuperuserRequests, logRouteId, logTimeRange);
  }, [includeSuperuserRequests, logFilter, logPage, logRouteId, logTimeRange, replaceLogRoute]);

  const refreshCrons = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await apiRequest<CronJob[]>("/api/crons", token);
      setCrons(data);
    } finally {
      setLoading(false);
    }
  }, [token]);

  const refreshOauthProviders = useCallback(async () => {
    if (!token) return;
    const data = await apiRequest<OAuthProviderMetadata[]>("/api/collections/meta/oauth2-providers", token);
    setOauthProviders(data);
  }, [token]);

  const refreshAuthMethods = useCallback(async (collectionName = selectedName) => {
    if (!collectionName) {
      setAuthMethods(null);
      return;
    }
    const collection = collections.find((item) => item.name === collectionName);
    if (!collection || collection.type !== "auth") {
      setAuthMethods(null);
      return;
    }
    const data = await apiRequest<AuthMethodsResponse>(
      `/api/collections/${encodeURIComponent(collectionName)}/auth-methods`,
      token
    );
    setAuthMethods(data);
  }, [collections, selectedName, token]);

  const refreshAuthRecord = useCallback(async (authToken = token) => {
    if (!authToken) {
      setAuthRecord(null);
      return null;
    }
    try {
      // Display-only identity load. Do not rotate the stored token here — auth-refresh
      // can mint a new JWT on every call and would retrigger the bootstrap effect loop.
      const auth = await apiRequest<AuthResponse>("/api/collections/_superusers/auth-refresh", authToken, {
        method: "POST"
      });
      setAuthRecord(auth.record ?? null);
      return auth;
    } catch {
      // Keep the existing session until a real request is rejected as unauthorized.
      return null;
    }
  }, [token]);

  const refreshAll = useCallback(async () => {
    try {
      const [, bootstrap] = await Promise.all([refreshHealth(), refreshBootstrapStatus()]);
      if (token && !bootstrap.required) {
        await Promise.all([refreshCollections(), refreshOauthProviders()]);
      }
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }, [notify, refreshBootstrapStatus, refreshCollections, refreshHealth, refreshOauthProviders, token]);

  useEffect(() => {
    refreshAll();
  }, [refreshAll]);

  // Restore the signed-in superuser identity after a page reload (token is in localStorage).
  useEffect(() => {
    if (!token || authRecord) return;
    void refreshAuthRecord();
  }, [authRecord, refreshAuthRecord, token]);

  useEffect(() => {
    const handleUnauthorized = () => {
      notify(t("errors.session_expired", "Your session has expired. Please log in again."), "error");
      // Close any open editor so stale state doesn't linger behind the login screen.
      setCollectionEditor(null);
      setRecordEditor(null);
      setRelationRecordEditors([]);
      setConfirmState(null);
      logout();
    };
    window.addEventListener("pbj_unauthorized", handleUnauthorized);
    return () => window.removeEventListener("pbj_unauthorized", handleUnauthorized);
  }, []);



  // Older versions keyed both the collection and fields by their mutable names,
  // and stored only columns manually hidden by the user. Migrate each still-known
  // preference to a full visibility snapshot so schema-hidden fields stay hidden
  // until the user explicitly opts in.
  useEffect(() => {
    if (collections.length === 0) return;
    setHiddenColumnsByCollection((current) => {
      let changed = false;
      const next = { ...current };
      for (const collection of collections) {
        const storeKey = collectionPreferenceStoreKey(collection);
        const legacyKey = collection.name;
        const source = hiddenColumnPreferencesFor(collection, current);
        const hasStoredPreference =
          Object.prototype.hasOwnProperty.call(current, storeKey) ||
          Object.prototype.hasOwnProperty.call(current, legacyKey);
        if (!hasStoredPreference) continue;
        const normalized = hiddenColumnPreferenceSnapshot(collection, source);
        const stored = current[storeKey] ?? [];
        const hasLegacy = storeKey !== legacyKey && Object.prototype.hasOwnProperty.call(current, legacyKey);
        if (sameStringValues(stored, normalized) && !hasLegacy) continue;
        changed = true;
        next[storeKey] = normalized;
        if (storeKey !== legacyKey) delete next[legacyKey];
      }
      return changed ? next : current;
    });
  }, [collections]);

  useEffect(() => {
    localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidth));
  }, [sidebarWidth]);

  useEffect(() => {
    if (selectedName) localStorage.setItem(ACTIVE_COLLECTION_KEY, selectedName);
  }, [selectedName]);

  useEffect(() => {
    const root = document.documentElement;
    let themeColor = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
    if (!themeColor) {
      themeColor = document.createElement("meta");
      themeColor.name = "theme-color";
      themeColor.dataset.pbjAccentColor = "true";
      document.head.appendChild(themeColor);
    }

    if (accentColor) {
      root.style.setProperty("--accentColor", accentColor);
      themeColor.content = accentColor;
    } else {
      root.style.removeProperty("--accentColor");
      themeColor.removeAttribute("content");
    }
  }, [accentColor]);

  useEffect(() => {
    return () => {
      document.documentElement.style.removeProperty("--accentColor");
      document.querySelector<HTMLMetaElement>('meta[name="theme-color"][data-pbj-accent-color="true"]')?.remove();
    };
  }, []);

  useEffect(() => {
    if (!hideControls) sqlHideControlsConfirmationRef.current = false;
  }, [hideControls]);

  useEffect(() => {
    clearRecordSelection();
    setRecordsNeedRefresh(false);
  }, [query.filter, query.perPage, query.sort, selectedName, clearRecordSelection]);

  useEffect(() => {
    const generation = ++recordsLoadGenerationRef.current;
    if (!authenticated || !selectedName || view !== "records") {
      setLoading(false);
      return undefined;
    }
    const controller = new AbortController();
    const loadRoutePages = async () => {
      setLoading(true);
      try {
        // Deep links may request several pages. They are independent, so fetch them
        // concurrently and commit once in page order. The per-query cache prevents
        // a Load more route update from re-fetching already loaded pages.
        const requestedPages = Array.from({ length: recordRoutePage }, (_, index) => index + 1);
        const result = await Promise.all(
          requestedPages.map((page) =>
            fetchRecordsPage(selectedName, query, page, { signal: controller.signal })
          )
        );
        if (controller.signal.aborted || generation !== recordsLoadGenerationRef.current) return;
        const pages = result.filter((page): page is ListResponse<RecordItem> => page !== null);
        if (pages.length !== requestedPages.length) return;
        const firstPage = pages[0];
        const lastPage = pages.at(-1) ?? null;
        setRecordPage(
          lastPage && firstPage && lastPage.totalItems < 0
            ? { ...lastPage, totalItems: firstPage.totalItems, totalPages: firstPage.totalPages }
            : lastPage
        );
        setRecords(mergeRecordItems([], pages.flatMap((page) => page.items)));
        setRecordsNeedRefresh(false);
      } catch (error) {
        if (!controller.signal.aborted && generation === recordsLoadGenerationRef.current) {
          notify(errorMessage(error), "error");
        }
      } finally {
        if (generation === recordsLoadGenerationRef.current) setLoading(false);
      }
    };
    void loadRoutePages();
    return () => controller.abort();
  }, [authenticated, fetchRecordsPage, notify, query, recordRoutePage, selectedName, view]);

  useEffect(() => {
    if (authenticated && view === "backups") {
      refreshBackups().catch((error) => notify(errorMessage(error), "error"));
    }
  }, [authenticated, notify, refreshBackups, view]);

  useEffect(() => {
    if (authenticated && (view === "settings" || view === "mail" || view === "storage" || view === "backups")) {
      refreshSettings().catch((error) => notify(errorMessage(error), "error"));
    }
  }, [authenticated, notify, refreshSettings, view]);

  useEffect(() => {
    if (!authenticated || view !== "settings") return;
    const timer = window.setInterval(() => {
      void refreshHealth().catch(() => {
        // The diagnostic is supplementary; keep editing available on transient failures.
      });
    }, 5000);
    return () => window.clearInterval(timer);
  }, [authenticated, refreshHealth, view]);

  useEffect(() => {
    if (authenticated && view === "export") {
      setExportDraft(JSON.stringify(collections, null, 2));
    }
  }, [authenticated, collections, view]);

  useEffect(() => {
    const generation = ++logsLoadGenerationRef.current;
    const route = adminRouteFromHash(hash);
    if (!authenticated || view !== "logs" || route?.view !== "logs") {
      setIsLogListLoading(false);
      setIsLogStatsLoading(false);
      return undefined;
    }

    // Derive the request from the hash rather than the editable input state. This
    // makes the route effect the single load owner even while a new filter is
    // being typed, and prevents an old route from briefly loading after navigation.
    const routeFilter = route.params.filter ?? "";
    const routeTimeRange = logTimeRangeFromRoute(route.params);
    const routePage = routeNumber(route.params.page, 1, 1, 20);
    const routeIncludesSuperuserRequests = route.params.superuserRequests === "1";
    const filter = logFilterWithVisibility(
      combineLogFilters(
        normalizeSearchTerm(routeFilter, LOG_SEARCH_FIELDS),
        routeTimeRange ? logTimeRangeFilter(routeTimeRange) : ""
      ),
      routeIncludesSuperuserRequests
    );
    const scope = JSON.stringify({ filter, refreshVersion: logRefreshVersion });
    const scopeChanged = logLoadScopeRef.current !== scope;
    logLoadScopeRef.current = scope;
    if (scopeChanged) {
      // `page` and `logId` hash changes reuse the same data scope, so they must
      // not hide an already stable chart while opening a log or loading more.
      setIsLogFirstLoadReady(false);
      setLogStats([]);
    }
    const cache = getLogPageCache(scope);
    const requestedPages = Array.from({ length: routePage }, (_, index) => index + 1);
    const missingPages = requestedPages.filter((page) => !cache.pages.has(page));
    const controller = new AbortController();
    let firstLoadReadyTimer: number | undefined;

    const isCurrentLoad = () =>
      !controller.signal.aborted
      && generation === logsLoadGenerationRef.current
      && logPageCacheRef.current === cache;

    const applyLoadedPages = () => {
      if (!isCurrentLoad()) return;
      const pages = requestedPages.map((page) => cache.pages.get(page));
      if (pages.some((page) => !page)) return;
      const loadedPages = pages as ListResponse<LogItem>[];
      setLogPage(loadedPages.at(-1) ?? null);
      setLogs(mergeLogItems([], loadedPages.flatMap((page) => page.items)));
      setIsLogListLoading(false);
      // Let React commit the list first. This is intentionally independent from
      // the stats request so a slow chart never blocks the log table.
      firstLoadReadyTimer = window.setTimeout(() => {
        if (isCurrentLoad()) setIsLogFirstLoadReady(true);
      }, 0);
    };

    const loadRoutePages = async () => {
      setLoading(true);
      const needsStats = cache.stats === null;
      setIsLogListLoading(missingPages.length > 0);
      setIsLogStatsLoading(needsStats);

      const listTask = Promise.all(
        missingPages.map((page) => fetchLogsPage(page, filter, scope, controller.signal))
      )
        .then(applyLoadedPages)
        .catch((error) => {
          if (!isCurrentLoad()) return;
          setIsLogListLoading(false);
          notify(errorMessage(error), "error");
        });
      const statsTask = (needsStats
        ? fetchLogStats(filter, scope, controller.signal)
        : Promise.resolve(cache.stats))
        .then((stats) => {
          if (!isCurrentLoad() || stats === null) return;
          // Stats are loaded only for a new query scope. Loading another page
          // uses the same cached series instead of repeating /api/logs/stats.
          setLogStats(stats);
          setIsLogStatsLoading(false);
        })
        .catch((error) => {
          if (!isCurrentLoad()) return;
          setIsLogStatsLoading(false);
          notify(errorMessage(error), "error");
        });

      await Promise.all([listTask, statsTask]);
      if (isCurrentLoad()) setLoading(false);
    };

    void loadRoutePages();
    return () => {
      controller.abort();
      if (firstLoadReadyTimer !== undefined) window.clearTimeout(firstLoadReadyTimer);
    };
  }, [authenticated, fetchLogStats, fetchLogsPage, getLogPageCache, hash, logRefreshVersion, notify, view]);

  useEffect(() => {
    if (authenticated && view === "crons") {
      refreshCrons().catch((error) => notify(errorMessage(error), "error"));
    }
  }, [authenticated, notify, refreshCrons, view]);

  useEffect(() => {
    if (authenticated && selectedName && view === "schema") {
      refreshAuthMethods(selectedName).catch((error) => notify(errorMessage(error), "error"));
      return;
    }
    setAuthMethods(null);
  }, [authenticated, notify, refreshAuthMethods, selectedName, view]);

  useEffect(() => {
    if (!authenticated || view !== "records" || !selectedName || !recordRouteId) return;
    const generation = ++recordDetailGenerationRef.current;
    const controller = new AbortController();
    api<RecordItem>(
      `/api/collections/${encodeURIComponent(selectedName)}/records/${encodeURIComponent(recordRouteId)}`,
      { signal: controller.signal }
    )
      .then((record) => {
        if (!controller.signal.aborted && generation === recordDetailGenerationRef.current) {
          setRecordEditor({ record });
        }
      })
      .catch((error) => {
        if (controller.signal.aborted || generation !== recordDetailGenerationRef.current) return;
        notify(errorMessage(error), "error");
        setRecordRouteId("");
        replaceRecordRoute(query, recordRoutePage, "");
      });
    return () => {
      controller.abort();
    };
  }, [api, authenticated, notify, query, recordRouteId, recordRoutePage, replaceRecordRoute, selectedName, view]);

  useEffect(() => {
    if (!authenticated || view !== "logs" || !logRouteId) return;
    if (selectedLog?.id === logRouteId) return;
    let cancelled = false;
    api<LogItem>(`/api/logs/${encodeURIComponent(logRouteId)}`)
      .then((log) => {
        if (!cancelled) setSelectedLog(log);
      })
      .catch((error) => {
        if (cancelled) return;
        notify(errorMessage(error), "error");
        setLogRouteId("");
        replaceLogRoute(logFilter, logRoutePage, includeSuperuserRequests, "");
      });
    return () => {
      cancelled = true;
    };
  }, [api, authenticated, includeSuperuserRequests, logFilter, logRouteId, logRoutePage, notify, replaceLogRoute, selectedLog?.id, view]);

  async function completeAuth(auth: AuthResponse) {
    const signedInEmail =
      typeof auth.record?.email === "string" && auth.record.email
        ? String(auth.record.email)
        : authEmail;
    clearAuthAttemptState(signedInEmail);
    clearAuthAttemptState(authEmail);
    setAuthToken(auth.token);
    setAuthRecord(auth.record ?? null);
    setAuthEmail("");
    setAuthPassword("");
    setFailedCount(0);
    setAuthLockedUntil(0);
    setCaptchaInput("");
    setMfaChallenge(null);
    setOtpCode("");
    const [, bootstrap] = await Promise.all([refreshHealth(auth.token), refreshBootstrapStatus()]);
    if (!bootstrap.required) {
      await refreshCollectionsWithToken(auth.token);
    }
  }

  async function requestOtp(email: string, mfaId: string) {
    const otp = await apiRequest<{ otpId: string }>("/api/collections/_superusers/request-otp", "", {
      method: "POST",
      body: { email }
    });
    setMfaChallenge({ mfaId, otpId: otp.otpId, email });
    notify(t("notifications.otp_sent", "A one-time code has been sent to your email"));
  }

  function applyAuthFailure(error: unknown, email: string) {
    const prev = getAuthAttemptState(email);
    const serverCount = failedAttemptsFromError(error);
    const lockedByServer = error instanceof ApiRequestError && error.status === 429;
    // Count failures per email for UI lock/captcha. Do not use server max(IP, identity)
    // as the UI counter — an IP-wide lock would otherwise freeze every new account too.
    let count = prev.count + 1;
    if (serverCount != null && !lockedByServer) {
      count = Math.max(count, serverCount);
    }
    // Lock this identity only after *its* own 10 failures (or the 10th attempt that 429s).
    const lock = count >= MAX_AUTH_FAILURES;
    updateAuthAttempt(email, count, lock);
    refreshCaptcha();
  }

  async function handleAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const email = authEmail.trim();
    if (!email) return;

    // Per-account UI lock: this identity cannot submit until the lock expires.
    // Changing the email field loads another identity's state and may re-enable submit.
    if (!setupRequired && isAuthIdentityLocked(email)) {
      notify(
        t(
          "notifications.too_many_login_attempts",
          "Too many failed login attempts. Please try again after 10 minutes."
        ),
        "error"
      );
      return;
    }

    // Captcha is client UX, but every failed attempt (including wrong captcha) must hit the
    // server so identity+IP lockout still advances. Wrong captcha uses a dummy password so a
    // correct password is never accepted when captcha fails.
    if (!setupRequired && failedCount >= CAPTCHA_AFTER_FAILURES && !isAuthIdentityLocked(email)) {
      if (captchaInput.trim().toUpperCase() !== captchaCode.toUpperCase()) {
        setLoading(true);
        try {
          await apiRequest<AuthResponse>("/api/collections/_superusers/auth-with-password", "", {
            method: "POST",
            body: {
              email,
              identity: email,
              // Deliberately invalid — records a server-side failure for lockout without
              // revealing whether the real password was correct.
              password: `\0captcha-reject-${Date.now()}`
            }
          });
        } catch (error) {
          applyAuthFailure(error, email);
          if (error instanceof ApiRequestError && error.status === 429) {
            notify(translateErrorMessage(error, t), "error");
            return;
          }
        } finally {
          setLoading(false);
        }
        notify(t("notifications.captcha_incorrect", "Incorrect verification code. Please try again."), "error");
        return;
      }
    }

    const body = { email, identity: email, password: authPassword };
    setLoading(true);
    try {
      if (setupRequired) {
        await apiRequest("/api/bootstrap/superuser", "", { method: "POST", body });
        notify(t("notifications.superuser_created", "Superuser created"));
      }
      const auth = await apiRequest<AuthResponse>("/api/collections/_superusers/auth-with-password", "", {
        method: "POST",
        body
      });
      await completeAuth(auth);
    } catch (error) {
      // Password was accepted; MFA is a next step, not a failed attempt.
      if (error instanceof ApiRequestError && error.mfaId) {
        try {
          await requestOtp(email, error.mfaId);
        } catch (otpError) {
          notify(translateErrorMessage(otpError, t), "error");
        }
        return;
      }
      applyAuthFailure(error, email);
      notify(translateErrorMessage(error, t), "error");
    } finally {
      setLoading(false);
    }
  }

  async function handleOtpSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!mfaChallenge) return;
    setLoading(true);
    try {
      const auth = await apiRequest<AuthResponse>("/api/collections/_superusers/auth-with-otp", "", {
        method: "POST",
        body: { otpId: mfaChallenge.otpId, password: otpCode.trim(), mfaId: mfaChallenge.mfaId }
      });
      await completeAuth(auth);
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setLoading(false);
    }
  }

  async function resendOtp() {
    if (!mfaChallenge) return;
    setLoading(true);
    try {
      setOtpCode("");
      await requestOtp(mfaChallenge.email, mfaChallenge.mfaId);
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setLoading(false);
    }
  }

  function cancelMfa() {
    setMfaChallenge(null);
    setOtpCode("");
    setAuthPassword("");
  }

  async function refreshCollectionsWithToken(nextToken: string) {
    const data = await apiRequest<ListResponse<CollectionSchema>>("/api/collections?perPage=500&sort=name", nextToken);
    setCollections(data.items);
    setSelectedName(data.items.find((collection) => collection.name !== "_superusers")?.name ?? data.items[0]?.name ?? "");
    const providers = await apiRequest<OAuthProviderMetadata[]>("/api/collections/meta/oauth2-providers", nextToken);
    setOauthProviders(providers);
  }

  function setAuthToken(nextToken: string) {
    recordPageCacheRef.current = { scope: "", pages: new Map() };
    logPageCacheRef.current = { scope: "", pages: new Map(), stats: null };
    recordsLoadGenerationRef.current += 1;
    logsLoadGenerationRef.current += 1;
    logLoadScopeRef.current = null;
    setIsLogListLoading(false);
    setIsLogStatsLoading(false);
    setIsLogFirstLoadReady(false);
    setToken(nextToken);
    if (nextToken) {
      localStorage.setItem(TOKEN_KEY, nextToken);
    } else {
      localStorage.removeItem(TOKEN_KEY);
    }
  }

  function logout() {
    setAuthToken("");
    setAuthRecord(null);
    setLoading(false);
    setCollections([]);
    setRecords([]);
    setRecordPage(null);
    setBackups([]);
    setSettings(null);
    setSettingsDraft("");
    setLogs([]);
    setLogPage(null);
    setLogStats([]);
    setAuthMethods(null);
    setOauthResult(null);
    setOauthTestingProvider("");
    setSelectedRecordIds([]);
    setSqlResult(null);
    setSqlError("");
    setSelectedName("");
    setView("records");
    if (window.location.hash.startsWith("#/")) {
      window.location.hash = "";
    }
  }

  async function saveCollection(payload: CollectionPayload): Promise<boolean> {
    try {
      if (collectionEditor?.mode === "edit" && collectionEditor.collection) {
        // Use the collection id for the PATCH path so a rename is safe: the URL
        // target stays stable regardless of the new name (official parity).
        const target = collectionEditor.collection.id || collectionEditor.collection.name;
        await api(`/api/collections/${encodeURIComponent(target)}`, {
          method: "PATCH",
          body: payload
        });
        notify(t("notifications.collection_saved", "Collection saved"));
      } else {
        await api("/api/collections", { method: "POST", body: payload });
        notify(t("notifications.collection_created", "Collection created"));
      }
      // Leave the editor open so CollectionModal can play the drawer exit animation.
      await refreshCollections();
      broadcastSync("collections");
      return true;
    } catch (error) {
      notify(errorMessage(error), "error");
      return false;
    }
  }

  async function deleteCollection(collection: CollectionSchema) {
    const confirmed = await confirm({
      title: t("confirm.delete_collection_title", "Delete collection"),
      message: t("confirm.delete_collection_body", {
        name: collection.name,
        defaultValue:
          'All records of "{{name}}" and their uploaded files will be permanently deleted. This cannot be undone.'
      }),
      confirmLabel: t("actions.delete", "Delete"),
      danger: true,
      requireText: collection.name
    });
    if (!confirmed) return;
    try {
      await api(`/api/collections/${encodeURIComponent(collection.name)}`, { method: "DELETE" });
      notify(t("notifications.collection_deleted", "Collection deleted"));
      setSelectedName("");
      await refreshCollections();
      broadcastSync("collections");
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function truncateCollection(collection: CollectionSchema) {
    if (collection.system) return;
    const confirmed = await confirm({
      title: t("parity.collection.truncate_title", "Truncate collection"),
      message: t("parity.collection.truncate_body", {
        name: collection.name,
        defaultValue: "Permanently delete every record and uploaded file in {{name}}. The collection schema will be kept."
      }),
      confirmLabel: t("parity.collection.truncate_action", "Truncate collection"),
      danger: true,
      requireText: collection.name
    });
    if (!confirmed) return;
    try {
      await api(`/api/collections/${encodeURIComponent(collection.name)}/truncate`, { method: "DELETE" });
      notify(t("parity.notifications.collection_truncated", "Collection truncated"));
      await refreshRecords(collection.name);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function duplicateCollection(collection: CollectionSchema) {
    if (collection.system) return;
    const name = nextDuplicateCollectionName(collection.name, collections);
    try {
      const created = await api<CollectionSchema>("/api/collections", {
        method: "POST",
        body: duplicateCollectionPayload(collection, name)
      });
      notify(t("parity.notifications.collection_duplicated", { name: created.name, defaultValue: "Collection {{name}} created" }));
      await refreshCollections();
      broadcastSync("collections");
      navigateTo("records", created.name);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function startOAuthTest(provider: AuthMethodProvider) {
    if (!selected) return;
    if (!provider.authURL || !provider.state) {
      notify(t("errors.provider_missing_auth_url", { name: provider.displayName || provider.name, defaultValue: "Provider {{name}} is missing an auth URL" }), "error");
      return;
    }
    const redirectURL = `${window.location.origin}/api/oauth2-redirect`;
    const popup = window.open(
      "about:blank",
      `pbj-oauth-${provider.name}`,
      "popup,width=720,height=820"
    );
    if (!popup) {
      notify(t("errors.oauth_popup_blocked", "OAuth popup was blocked"), "error");
      return;
    }

    setOauthTestingProvider(provider.name);
    let realtime: EventSource | null = null;
    try {
      realtime = new EventSource("/api/realtime");
      const clientId = await waitForRealtimeClient(realtime, t("errors.oauth_popup_timed_out", "OAuth2 popup timed out."));
      await api("/api/realtime", {
        method: "POST",
        body: {
          clientId,
          subscriptions: ["@oauth2"]
        }
      });
      const authURL = new URL(provider.authURL);
      authURL.searchParams.set("state", clientId);
      authURL.searchParams.set("redirect_uri", redirectURL);
      popup.location.replace(authURL.toString());

      const payload = await waitForOAuthResult(clientId, realtime, popup, {
        closed: t("errors.oauth_popup_closed", "OAuth2 popup was closed before authentication completed."),
        timeout: t("errors.oauth_popup_timed_out", "OAuth2 popup timed out.")
      });
      if (payload.error) throw new Error(payload.error);
      if (!payload.code) throw new Error(t("errors.oauth_missing_code", "OAuth2 redirect did not provide an authorization code."));
      const response = await apiRequest<AuthResponse>(
        `/api/collections/${encodeURIComponent(selected.name)}/auth-with-oauth2`,
        "",
        {
          method: "POST",
          body: {
            provider: provider.name,
            code: payload.code,
            codeVerifier: provider.codeVerifier ?? "",
            redirectURL
          }
        }
      );
      setOauthResult({ provider, response });
      notify(t("notifications.oauth_completed", { name: provider.displayName || provider.name, defaultValue: "OAuth2 auth completed for {{name}}" }));
      await refreshRecords(selected.name);
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      realtime?.close();
      if (!popup.closed) popup.close();
      setOauthTestingProvider("");
    }
  }

  async function saveRecord(payload: Record<string, unknown>, files: Record<string, File[]>, options: { close?: boolean } = {}) {
    if (!selected) return;
    try {
      const body = recordRequestBody(payload, files);
      // Duplicating begins with a source record for form defaults, but it must
      // always create a new row rather than PATCHing that source ID.
      const id = recordEditor?.mode === "duplicate" ? undefined : recordEditor?.record?.id;
      const path = id
        ? `/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(id)}`
        : `/api/collections/${encodeURIComponent(selected.name)}/records`;
      const saved = await api<RecordItem>(path, { method: id ? "PATCH" : "POST", body });
      notify(id ? t("notifications.record_saved", "Record saved") : t("notifications.record_created", "Record created"));
      if (options.close !== false) {
        closeRecordEditor();
      } else if (id) {
        setRecordEditor({ record: saved });
      }
      if (id) {
        mergeSavedRecordIntoLoadedState(saved);
        if (recordListMayNeedRefresh(query)) setRecordsNeedRefresh(true);
      } else {
        // New rows may or may not belong to the active filter/sort. Reload only
        // that case instead of making every edit jump the list back to page 1.
        await refreshRecords(selected.name);
      }
    } catch (error) {
      notify(errorMessage(error), "error");
      throw error;
    }
  }

  async function openRelationRecordEditor(
    target: RelationCollection,
    recordId: string | undefined,
    onSaved: (record: RelationRecord) => void,
    parentEditorId = "root"
  ) {
    const collection = collections.find((item) => item.id === target.id || item.name === target.name);
    if (!collection || collection.type === "view") return;
    try {
      const record = recordId
        ? await api<RecordItem>(
            `/api/collections/${encodeURIComponent(collection.name)}/records/${encodeURIComponent(recordId)}`
          )
        : undefined;
      const editorId = `relation-${++relationEditorSequenceRef.current}`;
      setRelationRecordEditors((current) => [
        ...current,
        {
          editorId,
          // A child form uses its parent's editor id as a namespace. This lets a
          // multi-level relation workflow preserve each draft independently.
          draftKey: `pbj_relation_draft_${collection.id || collection.name}_${record?.id || "new"}_${parentEditorId}`,
          collection,
          record,
          onSaved
        }
      ]);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function saveRelationRecord(
    payload: Record<string, unknown>,
    files: Record<string, File[]>,
    options: { close?: boolean } = {}
  ) {
    const editor = relationRecordEditors[relationRecordEditors.length - 1];
    if (!editor) return;
    try {
      const body = recordRequestBody(payload, files);
      const id = editor.mode === "duplicate" ? undefined : editor.record?.id;
      const path = id
        ? `/api/collections/${encodeURIComponent(editor.collection.name)}/records/${encodeURIComponent(id)}`
        : `/api/collections/${encodeURIComponent(editor.collection.name)}/records`;
      const saved = await api<RecordItem>(path, { method: id ? "PATCH" : "POST", body });
      editor.onSaved?.(saved);
      notify(id ? t("notifications.record_saved", "Record saved") : t("notifications.record_created", "Record created"));

      // A self-relation can change the table currently shown behind the form.
      // Keep that projection correct without rerouting or discarding the parent draft.
      if (editor.collection.name === selectedName) {
        if (id) {
          mergeSavedRecordIntoLoadedState(saved);
          if (recordListMayNeedRefresh(query)) setRecordsNeedRefresh(true);
        }
        else await refreshRecords(editor.collection.name);
      }

      if (options.close !== false) {
        setRelationRecordEditors((current) => {
          const top = current[current.length - 1];
          return top?.editorId === editor.editorId ? current.slice(0, -1) : current;
        });
      } else if (id) {
        setRelationRecordEditors((current) =>
          current.map((candidate, index) =>
            index === current.length - 1 && candidate.editorId === editor.editorId
              ? { ...candidate, record: saved }
              : candidate
          )
        );
      }
    } catch (error) {
      notify(errorMessage(error), "error");
      throw error;
    }
  }

  function duplicateRelationRecord() {
    setRelationRecordEditors((current) =>
      current.map((editor, index) =>
        index === current.length - 1 && editor.record ? { ...editor, mode: "duplicate" } : editor
      )
    );
  }

  function closeRelationRecordEditor(editorId: string) {
    setRelationRecordEditors((current) => {
      const top = current[current.length - 1];
      return top?.editorId === editorId ? current.slice(0, -1) : current;
    });
  }

  function openRecordEditor(record?: RecordItem) {
    const recordId = record?.id ?? "";
    // The table row is a list projection and can be stale or omit fields. Route
    // record edits through the detail effect so both clicks and deep links use
    // the same GET /records/{id} freshness boundary.
    setRecordEditor(record ? null : {});
    setRecordRouteId(recordId);
    replaceRecordRoute(query, recordRoutePage, recordId);
  }

  function openRecordDuplicate(record: RecordItem) {
    setRecordEditor({ record, mode: "duplicate" });
    setRecordRouteId("");
    replaceRecordRoute(query, recordRoutePage, "");
  }

  function closeRecordEditor() {
    setRecordEditor(null);
    setRecordRouteId("");
    replaceRecordRoute(query, recordRoutePage, "");
  }

  function openLogDetails(log: LogItem) {
    setSelectedLog(log);
    setLogRouteId(log.id);
    replaceLogRoute(logFilter, logRoutePage, includeSuperuserRequests, log.id);
  }

  function closeLogDetails() {
    setSelectedLog(null);
    setLogRouteId("");
    replaceLogRoute(logFilter, logRoutePage, includeSuperuserRequests, "");
  }

  async function requestRecordVerification(collection: CollectionSchema, record: RecordItem) {
    const email = typeof record.email === "string" ? record.email.trim() : "";
    if (!email) throw new Error(t("parity.errors.auth_record_missing_email", "This auth record has no email address."));
    await api(`/api/collections/${encodeURIComponent(collection.name)}/request-verification`, {
      method: "POST",
      body: { email }
    });
    notify(t("parity.notifications.verification_sent", "Verification email requested"));
  }

  async function requestRecordPasswordReset(collection: CollectionSchema, record: RecordItem) {
    const email = typeof record.email === "string" ? record.email.trim() : "";
    if (!email) throw new Error(t("parity.errors.auth_record_missing_email", "This auth record has no email address."));
    await api(`/api/collections/${encodeURIComponent(collection.name)}/request-password-reset`, {
      method: "POST",
      body: { email }
    });
    notify(t("parity.notifications.password_reset_sent", "Password reset email requested"));
  }

  async function impersonateAuthRecord(
    collection: CollectionSchema,
    record: RecordItem,
    duration: number
  ): Promise<ImpersonationResult> {
    return api<ImpersonationResult>(
      `/api/collections/${encodeURIComponent(collection.name)}/impersonate/${encodeURIComponent(record.id)}`,
      { method: "POST", body: { duration } }
    );
  }

  async function loadExternalAuthLinks(collection: CollectionSchema, record: RecordItem): Promise<AuthRecordLink[]> {
    const filter = `collectionRef=${JSON.stringify(collection.id)} && recordRef=${JSON.stringify(record.id)}`;
    const qs = buildQuery({ page: 1, perPage: 500, sort: "-created", filter });
    const response = await api<ListResponse<AuthRecordLink>>(`/api/collections/_externalAuths/records?${qs}`);
    return response.items;
  }

  async function unlinkExternalAuth(link: AuthRecordLink) {
    await api(`/api/collections/_externalAuths/records/${encodeURIComponent(link.id)}`, { method: "DELETE" });
  }

  async function deleteRecord(record: RecordItem) {
    if (!selected) return;
    const confirmed = await confirm({
      title: t("confirm.delete_record_title", "Delete record"),
      message: t("confirm.delete_record", { id: record.id, defaultValue: "Delete record {{id}}?" }),
      confirmLabel: t("actions.delete", "Delete"),
      danger: true
    });
    if (!confirmed) return;
    try {
      await api(`/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(record.id)}`, {
        method: "DELETE"
      });
      notify(t("notifications.record_deleted", "Record deleted"));
      removeRecordsFromLoadedState([record.id]);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function deleteSelectedRecords() {
    if (!selected || selectedRecordIds.length === 0) return;
    const confirmed = await confirm({
      title: t("confirm.delete_records_title", "Delete records"),
      message: t("confirm.delete_selected_records", {
        count: selectedRecordIds.length,
        defaultValue: "Delete {{count}} selected records?"
      }),
      confirmLabel: t("actions.delete", "Delete"),
      danger: true
    });
    if (!confirmed) return;
    try {
      const ids = [...selectedRecordIds];
      const deletedIds: string[] = [];
      let failure: unknown;
      let failed = false;
      for (let offset = 0; offset < ids.length; offset += RECORD_DELETE_BATCH_SIZE) {
        const batch = ids.slice(offset, offset + RECORD_DELETE_BATCH_SIZE);
        const results = await Promise.allSettled(
          batch.map((id) =>
            api(`/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(id)}`, {
              method: "DELETE"
            })
          )
        );
        results.forEach((result, index) => {
          if (result.status === "fulfilled") deletedIds.push(batch[index]);
          else if (!failed) {
            failure = result.reason;
            failed = true;
          }
        });
        // Do not start another hundred deletes once one batch has a failure;
        // completed requests are still reflected locally below.
        if (failed) break;
      }
      removeRecordsFromLoadedState(deletedIds);
      if (failed) throw failure;
      notify(t("notifications.records_deleted", "Records deleted"));
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  // togglePinnedCollection is now provided by useCollections.

  // toggleRecordSelection, clearRecordSelection, and toggleCurrentPageSelection
  // are now provided by useRecordSelection. Wrap them to bind `records` so
  // call sites keep the same signature as before.
  const toggleRecordSelectionFn = useCallback(
    (id: string, extendRange = false) => toggleRecordSelection(id, records, extendRange),
    [toggleRecordSelection, records]
  );
  const toggleCurrentPageSelectionFn = useCallback(
    (checked: boolean, anchorId?: string) => toggleCurrentPageSelection(records, checked, anchorId),
    [toggleCurrentPageSelection, records]
  );

  // toggleColumn and resetColumns are now provided by useColumnPreferences.

  async function openFile(record: RecordItem, filename: string) {
    if (!selected) return;
    try {
      const parameters = new URLSearchParams();
      try {
        const fileToken = await api<{ token: string }>("/api/files/token", { method: "POST" });
        if (fileToken.token) parameters.set("token", fileToken.token);
      } catch {
        // Public file rules don't require a token.
      }
      // A browser navigation gives an uploaded document the admin origin. Keep the
      // convenience preview for inert raster images only; all other record files are
      // explicitly downloaded and receive the server-side sandbox headers as well.
      if (!safeImageFilename(filename)) parameters.set("download", "1");
      const query = parameters.toString();
      const url = `/api/files/${encodeURIComponent(selected.name)}/${encodeURIComponent(record.id)}/${encodeURIComponent(filename)}${query ? `?${query}` : ""}`;
      window.open(url, "_blank", "noopener,noreferrer");
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function createBackup() {
    const requestedName = backupName.trim();
    setBackupOperation({ kind: "create", key: requestedName || undefined });
    try {
      await api("/api/backups", { method: "POST", body: requestedName ? { name: requestedName } : {} });
      setBackupName("");
      notify(t("notifications.backup_created", "Backup created"));
      await refreshBackups();
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setBackupOperation((current) => (current?.kind === "create" ? null : current));
    }
  }

  async function uploadBackup(file: File) {
    try {
      const form = new FormData();
      form.append("file", file);
      await api("/api/backups/upload", { method: "POST", body: form });
      notify(t("notifications.backup_uploaded", "Backup uploaded"));
      await refreshBackups();
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      if (backupUploadRef.current) backupUploadRef.current.value = "";
    }
  }

  async function downloadBackup(backup: BackupInfo) {
    try {
      const response = await fetch(`/api/backups/${encodeURIComponent(backup.key)}`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `${response.status} ${response.statusText}`);
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = backup.key;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function restoreBackup(backup: BackupInfo) {
    const confirmed = await confirm({
      title: t("confirm.restore_backup_title", "Restore backup"),
      message: t("confirm.restore_backup_body", {
        key: backup.key,
        defaultValue:
          'The current application data will be REPLACED with the one from "{{key}}". All records, files and settings created since that backup will be lost. This cannot be undone.'
      }),
      confirmLabel: t("actions.restore", "Restore"),
      danger: true,
      requireText: backup.key
    });
    if (!confirmed) return;
    setBackupOperation({ kind: "restore", key: backup.key });
    try {
      await api(`/api/backups/${encodeURIComponent(backup.key)}/restore`, { method: "POST" });
      notify(t("notifications.backup_restored", "Backup restored"));
      // A restore replaces the entire application state (records, files, settings).
      // Reload the page so no stale state lingers, matching the official behavior.
      window.location.reload();
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setBackupOperation((current) => (current?.kind === "restore" && current.key === backup.key ? null : current));
    }
  }

  async function deleteBackup(backup: BackupInfo) {
    const confirmed = await confirm({
      title: t("confirm.delete_backup_title", "Delete backup"),
      message: t("confirm.delete_backup", { key: backup.key, defaultValue: "Delete backup {{key}}?" }),
      confirmLabel: t("actions.delete", "Delete"),
      danger: true
    });
    if (!confirmed) return;
    try {
      await api(`/api/backups/${encodeURIComponent(backup.key)}`, { method: "DELETE" });
      notify(t("notifications.backup_deleted", "Backup deleted"));
      await refreshBackups();
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function clearAllLogs() {
    const confirmed = await confirm({
      title: t("settings.delete_all_logs_title", "Delete all logs"),
      message: t(
        "settings.delete_all_logs_confirm",
        "Do you really want to delete all logs?"
      ),
      confirmLabel: t("actions.delete", "Delete"),
      danger: true
    });
    if (!confirmed) return;
    try {
      await api("/api/logs", { method: "DELETE" });
      notify(t("notifications.logs_cleared", "Successfully deleted all logs."));
      setLogsSettingsOpen(false);
      setSelectedLog(null);
      setLogRouteId("");
      replaceLogRoute(logFilter, 1, includeSuperuserRequests, "");
      logPageCacheRef.current = { scope: "", pages: new Map(), stats: null };
      setLogRefreshVersion((v) => v + 1);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function saveSettings(draft?: string): Promise<boolean> {
    // onClick={saveSettings} would pass a MouseEvent as the first arg — only accept real draft strings.
    const source = typeof draft === "string" ? draft : settingsDraft;
    try {
      const parsed = JSON.parse(source || "{}") as AppSettings;
      // Rules are resolved in order, so normalise priority on save like the official UI.
      const limits = isPlainObject(parsed.rateLimits) ? parsed.rateLimits : null;
      if (limits && Array.isArray(limits.rules)) {
        limits.rules = sortRateLimitRules(limits.rules as RateLimitRule[]);
      }
      const superuserIPs = Array.isArray(parsed.superuserIPs)
        ? parsed.superuserIPs.map((value) => String(value).trim()).filter(Boolean)
        : [];
      const currentIp = health?.realIP?.trim() ?? "";
      if (currentIp && superuserIPs.length > 0 && !superuserIPs.some((rule) => ipRuleAllows(currentIp, rule))) {
        const confirmed = await confirm({
          title: t("settings.superuser_ip_warning_title", "Current IP is not allowlisted"),
          message: t("settings.superuser_ip_warning_body", {
            ip: currentIp,
            defaultValue:
              "The new Superuser IP rules do not include {{ip}}. Saving can lock this browser out of the dashboard. Continue anyway?"
          }),
          confirmLabel: t("actions.save", "Save"),
          danger: true
        });
        if (!confirmed) return false;
      }
      await api<AppSettings>("/api/settings", { method: "PATCH", body: parsed });
      // The HTTP layer starts enforcing new IP rules on the following request.
      // Refresh now, so a potential lockout is surfaced while this tab is still open.
      if (superuserIPs.length > 0) {
        try {
          const refreshed = await api<AuthResponse>("/api/collections/_superusers/auth-refresh", { method: "POST" });
          if (refreshed.token) setAuthToken(refreshed.token);
          if (refreshed.record) setAuthRecord(refreshed.record);
        } catch (error) {
          notify(
            `${t("settings.superuser_ip_refresh_failed", "The IP restriction was saved, but this browser is no longer authorized.")} ${errorMessage(error)}`,
            "error"
          );
          return false;
        }
      }
      // Re-read instead of trusting the PATCH echo: the server normalises settings
      // (dedupes rate limit rules, redacts secrets) and the response can differ.
      await refreshSettings();
      broadcastSync("settings");
      notify(t("notifications.settings_saved", "Settings saved"));
      return true;
    } catch (error) {
      notify(errorMessage(error), "error");
      return false;
    }
  }

  async function testEmailSettings() {
    const email = testEmail.trim();
    if (!email) return;
    // Remember the recipient for convenience across sessions.
    localStorage.setItem("pbj_test_email_recipient", email);
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 15000);
    try {
      await api("/api/settings/test/email", {
        method: "POST",
        body: {
          email,
          template: testEmailTemplate || "verification",
          collection: testEmailCollection
        },
        signal: controller.signal
      });
      notify(t("notifications.test_email_queued", "Test email queued"));
    } catch (error) {
      if (controller.signal.aborted) {
        notify(t("errors.test_email_timeout", "Test email timed out after 15s. Check your SMTP settings."), "error");
      } else {
        notify(errorMessage(error), "error");
      }
    } finally {
      window.clearTimeout(timeout);
    }
  }

  async function testS3Settings(automatic = false) {
    const requestId = ++s3TestRequestIdRef.current;
    const draft = parseSettingsDraft(settingsDraft, settings);
    const body =
      testS3Target === "backups"
        ? {
            filesystem: "backups",
            backups: { s3: settingsObject(settingsObject(draft, "backups"), "s3") }
          }
        : { filesystem: "storage", s3: settingsObject(draft, "s3") };
    setS3TestState({ status: "testing", message: "" });
    try {
      await api("/api/settings/test/s3", {
        method: "POST",
        body
      });
      if (requestId !== s3TestRequestIdRef.current) return;
      const message = t("settings.s3_test_success", "S3 connection verified");
      setS3TestState({ status: "success", message });
      if (!automatic) notify(t("notifications.s3_check_completed", "S3 connection check completed"));
    } catch (error) {
      if (requestId !== s3TestRequestIdRef.current) return;
      const message = errorMessage(error);
      setS3TestState({ status: "error", message });
      if (!automatic) notify(message, "error");
    }
  }

  async function importCollections(): Promise<boolean> {
    try {
      const parsed = JSON.parse(importDraft || "{}");
      const collectionsPayload = Array.isArray(parsed)
        ? parsed
        : isPlainObject(parsed) && Array.isArray(parsed.collections)
          ? parsed.collections
          : null;
      if (!collectionsPayload) throw new Error(t("errors.import_json_shape", "Import JSON must be an array or an object with collections."));
      await api("/api/collections/import", {
        method: "PUT",
        body: {
          deleteMissing: deleteMissingCollections,
          collections: collectionsPayload
        }
      });
      notify(t("notifications.collections_imported", "Collections imported"));
      await refreshCollections();
      broadcastSync("collections");
      setExportDraft(JSON.stringify(collectionsPayload, null, 2));
      return true;
    } catch (error) {
      notify(errorMessage(error), "error");
      return false;
    }
  }

  function pushSqlHistory(query: string) {
    const trimmed = query.trim();
    if (!trimmed) return;
    setSqlHistory((prev) => {
      const next = [trimmed, ...prev.filter((item) => item !== trimmed)].slice(0, 10);
      sessionStorage.setItem("pbj_sql_history", JSON.stringify(next));
      return next;
    });
  }

  function removeSqlHistory(query: string) {
    setSqlHistory((prev) => {
      const next = prev.filter((item) => item !== query);
      sessionStorage.setItem("pbj_sql_history", JSON.stringify(next));
      return next;
    });
  }

  async function runSql() {
    // Match statements anywhere in a batch/CTE, as the official console does.
    // Checking only the first token misses `select ...; drop ...` and
    // `with ... delete ...`.
    const normalizedQuery = `${sqlQuery.replace(/[\\s;]/g, " ").toUpperCase()} `;
    const statement = DANGEROUS_SQL.find((keyword) => normalizedQuery.includes(`${keyword.toUpperCase()} `));
    const confirmForHideControls = hideControls && !sqlHideControlsConfirmationRef.current;
    if (confirmForHideControls || statement) {
      // PocketBase asks once even for a read-only query while hideControls is
      // active. It is a deliberate acknowledgement, not an authorization gate.
      if (confirmForHideControls) sqlHideControlsConfirmationRef.current = true;
      const confirmed = await confirm({
        title: statement
          ? t("confirm.run_sql_title", "Run write statement")
          : t("confirm.run_sql_hide_controls_title", "Confirm SQL query"),
        message: statement
          ? t("confirm.run_sql_body", {
              statement: statement.toUpperCase(),
              defaultValue:
                "This query contains {{statement}} and can modify or destroy data. Are you sure you want to execute it?"
            })
          : t(
              "confirm.run_sql_hide_controls_body",
              "Hide/Lock collection and record controls is enabled. Continue only if you understand that this SQL query may affect your application."
            ),
        confirmLabel: t("actions.execute", "Execute"),
        danger: true
      });
      if (!confirmed) return;
    }
    setSqlError("");
    setLoading(true);
    const started = performance.now();
    try {
      const result = await api<SqlResult>("/api/sql", { method: "POST", body: { query: sqlQuery } });
      setSqlResult(result);
      pushSqlHistory(sqlQuery);
      notify(t("notifications.sql_executed", "SQL executed"));
      await refreshCollections();
    } catch (error) {
      const message = errorMessage(error);
      setSqlError(message);
      notify(message, "error");
    } finally {
      setSqlElapsedMs(Math.round(performance.now() - started));
      setLoading(false);
    }
  }

  async function runCron(job: CronJob) {
    setLoading(true);
    try {
      await api(`/api/crons/${encodeURIComponent(job.id)}`, { method: "POST" });
      notify(t("notifications.cron_triggered", { id: job.id, defaultValue: "Triggered {{id}}" }));
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setLoading(false);
    }
  }

  // Table and column names are what you actually type in the SQL console.
  const sqlCompletions = useMemo(() => {
    const names: string[] = [];
    for (const collection of collections) {
      names.push(collection.name);
      for (const field of collection.fields ?? []) {
        names.push(field.name, `${collection.name}.${field.name}`);
      }
    }
    return [...new Set(names)];
  }, [collections]);

  const allColumns = useMemo(() => recordColumns(selected), [selected]);
  const columns = useMemo(
    () => allColumns.filter((column) => !hiddenColumns.includes(column)),
    [allColumns, hiddenColumns]
  );
  const pageMeta = viewMeta(view, selected, t);
  const applicationName = settingsApplicationName(settings) || "pocketbase-java";
  const documentTitle = authenticated && pageMeta.title !== applicationName ? `${pageMeta.title} · ${applicationName}` : applicationName;
  const showWorkspaceTopbar = authenticated && (!collectionView && !settingsView && view !== "logs");

  useEffect(() => {
    document.title = documentTitle;
  }, [documentTitle]);

  if (
    hash.startsWith("#/pbinstall/") ||
    hash.startsWith("#/request-password-reset") ||
    hash.startsWith("#/auth/confirm-") ||
    hash.startsWith("#/auth/oauth2-redirect-")
  ) {
    return <AuthActionPages />;
  }

  const shellClassName = [
    "app-shell",
    hideControls ? "hide-controls" : "",
    !authenticated ? "auth-shell" : ""
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={shellClassName}>
      <header className="app-header">
        <button
          className="logo"
          onClick={() => {
            if (selectedName) navigateTo("records");
          }}
          aria-label={t("nav.open_collections", "Open collections")}
        >
          <img
            className="brand-mark"
            src={`${import.meta.env.BASE_URL}favicon.svg`}
            alt=""
            aria-hidden="true"
            width={30}
            height={30}
            draggable={false}
          />
        </button>
        <nav className="app-main-nav" aria-label={t("nav.primary", "Primary")}>
          <button
            className={collectionView ? "header-link active" : "header-link"}
            onClick={() => navigateTo("records")}
            disabled={!authenticated || !selectedName}
          >
            <Database size={15} />
            {t("nav.collections")}
          </button>
          <button
            className={view === "logs" ? "header-link active" : "header-link"}
            onClick={() => navigateTo("logs")}
            disabled={!authenticated}
          >
            <Activity size={15} />
            {t("nav.logs")}
          </button>
          <button
            className={settingsView ? "header-link active" : "header-link"}
            onClick={() => navigateTo("settings")}
            disabled={!authenticated}
          >
            <Settings size={15} />
            {t("nav.settings")}
          </button>
        </nav>
        <div className="header-tools">
          <LanguageSelector />
          <ThemeSelector mode={themeMode} resolvedTheme={resolvedTheme} onChange={setThemeMode} />
          {authenticated && (
            <AccountMenu
              email={typeof authRecord?.email === "string" ? authRecord.email : ""}
              onManageSuperusers={() => navigateTo("records", "_superusers", { resetRecordQuery: true })}
              onLogout={logout}
            />
          )}
        </div>
      </header>

      <div className={view === "logs" ? "app-body app-body-wide" : "app-body"}>
        {authenticated && !setupRequired && collectionView && (
          <ResizableSidebar
            width={sidebarWidth}
            onWidthChange={setSidebarWidth}
            label={t("nav.resize_sidebar", "Resize sidebar")}
          >
            <CollectionSidebar
              collections={visibleCollections}
              currentName={selectedName}
              pinnedNames={pinnedCollectionNames}
              search={collectionSearch}
              hideControls={hideControls}
              onSearch={setCollectionSearch}
              onCreate={() => setCollectionEditor({ mode: "create" })}
              onOverview={() => setCollectionsOverviewOpen(true)}
              onSelect={(collection) => {
                navigateTo("records", collection.name);
              }}
              onTogglePinned={togglePinnedCollection}
            />
          </ResizableSidebar>
        )}

        {authenticated && !setupRequired && settingsView && (
          <ResizableSidebar
            width={sidebarWidth}
            onWidthChange={setSidebarWidth}
            label={t("nav.resize_sidebar", "Resize sidebar")}
          >
            <SettingsSidebar current={view} onSelect={navigateTo} hideControls={hideControls} />
          </ResizableSidebar>
        )}

        <main className={showWorkspaceTopbar ? "workspace" : "workspace workspace-flush"}>
          {showWorkspaceTopbar && (
            <header className="topbar">
              <div>
                <p className="eyebrow">{pageMeta.eyebrow}</p>
                <h1>{pageMeta.title}</h1>
              </div>
            </header>
          )}

          {!authenticated ? (
            <AuthPanel
              setupRequired={setupRequired}
              email={authEmail}
              password={authPassword}
              loading={loading}
              accountLocked={accountLocked}
              mfaChallenge={mfaChallenge}
              otpCode={otpCode}
              failedCount={failedCount}
              captchaCode={captchaCode}
              captchaInput={captchaInput}
              onEmail={setAuthEmail}
              onPassword={setAuthPassword}
              onOtpCode={setOtpCode}
              onCaptchaInput={setCaptchaInput}
              onRefreshCaptcha={refreshCaptcha}
              onSubmit={handleAuth}
              onOtpSubmit={handleOtpSubmit}
              onResendOtp={resendOtp}
              onCancelMfa={cancelMfa}
            />
          ) : (
            <>
              {view === "backups" ? (
                <BackupView
                  backups={backups}
                  settings={settings}
                  draft={settingsDraft}
                  backupName={backupName}
                  canBackup={Boolean(health?.canBackup)}
                  operation={backupOperation}
                  loading={loading}
                  uploadRef={backupUploadRef}
                  onConfirm={confirm}
                  onBackupName={setBackupName}
                  onDraft={setSettingsDraft}
                  onSave={saveSettings}
                  onCreate={createBackup}
                  onRefresh={refreshBackups}
                  onUpload={uploadBackup}
                  onDownload={downloadBackup}
                  onRestore={restoreBackup}
                  onDelete={deleteBackup}
                />
              ) : view === "settings" ? (
                <SettingsView
                  settings={settings}
                  draft={settingsDraft}
                  health={health}
                  loading={loading}
                  collections={collections}
                  onDraft={setSettingsDraft}
                  onRefresh={refreshSettings}
                  onSave={saveSettings}
                  onAccentPreview={setAccentPreview}
                />
              ) : view === "mail" ? (
                <MailSettingsView
                  settings={settings}
                  draft={settingsDraft}
                  email={testEmail}
                  template={testEmailTemplate}
                  collection={testEmailCollection}
                  collections={collections}
                  loading={loading}
                  onDraft={setSettingsDraft}
                  onSave={saveSettings}
                  onEmail={setTestEmail}
                  onTemplate={setTestEmailTemplate}
                  onCollection={setTestEmailCollection}
                  onTest={testEmailSettings}
                />
              ) : view === "storage" ? (
                <StorageSettingsView
                  settings={settings}
                  draft={settingsDraft}
                  target={testS3Target}
                  testState={s3TestState}
                  loading={loading}
                  onDraft={setSettingsDraft}
                  onSave={saveSettings}
                  onTarget={(value) => {
                    s3TestRequestIdRef.current += 1;
                    setTestS3Target(value);
                    setS3TestState({ status: "idle", message: "" });
                  }}
                  onTest={testS3Settings}
                />
              ) : view === "export" ? (
                <CollectionTransferView
                  mode="export"
                  collections={collections}
                  draft={exportDraft}
                  deleteMissing={deleteMissingCollections}
                  loading={loading}
                  onDraft={setExportDraft}
                  onDeleteMissing={setDeleteMissingCollections}
                  onExport={() => setExportDraft(JSON.stringify(collections, null, 2))}
                  onImport={importCollections}
                  onCopy={(value) => {
                    navigator.clipboard.writeText(value).then(
                      () => notify(t("notifications.copied", "Copied")),
                      (error) => notify(errorMessage(error), "error")
                    );
                  }}
                />
              ) : view === "import" ? (
                <CollectionTransferView
                  mode="import"
                  collections={collections}
                  draft={importDraft}
                  deleteMissing={deleteMissingCollections}
                  loading={loading}
                  onDraft={setImportDraft}
                  onDeleteMissing={setDeleteMissingCollections}
                  onExport={() => setExportDraft(JSON.stringify(collections, null, 2))}
                  onImport={importCollections}
                  onCopy={(value) => {
                    navigator.clipboard.writeText(value).then(
                      () => notify(t("notifications.copied", "Copied")),
                      (error) => notify(errorMessage(error), "error")
                    );
                  }}
                />
              ) : view === "sql" ? (
                <SqlView
                  query={sqlQuery}
                  result={sqlResult}
                  error={sqlError}
                  elapsedMs={sqlElapsedMs}
                  loading={loading}
                  sqlCompletions={sqlCompletions}
                  history={sqlHistory}
                  onQuery={setSqlQuery}
                  onRun={runSql}
                  onRemoveHistory={removeSqlHistory}
                />
              ) : view === "logs" ? (
                <LogsView
                  logs={logs}
                  logPage={logPage}
                  filter={logFilterDraft}
                  stats={logStats}
                  timeRange={logTimeRange}
                  includeSuperuserRequests={includeSuperuserRequests}
                  loading={loading}
                  isLogListLoading={isLogListLoading}
                  isLogStatsLoading={isLogStatsLoading}
                  isLogFirstLoadReady={isLogFirstLoadReady}
                  onFilter={setLogFilterDraft}
                  onApply={() => {
                    setLogFilter(logFilterDraft);
                    setLogRoutePage(1);
                    setLogRouteId("");
                    replaceLogRoute(logFilterDraft, 1, includeSuperuserRequests, "", logTimeRange);
                    requestLogRefresh();
                  }}
                  onIncludeSuperuserRequests={(value) => {
                    setLogFilter(logFilterDraft);
                    setIncludeSuperuserRequests(value);
                    setLogRoutePage(1);
                    setLogRouteId("");
                    replaceLogRoute(logFilterDraft, 1, value, "", logTimeRange);
                  }}
                  onTimeRange={(range) => {
                    setLogTimeRange(range);
                    setLogRoutePage(1);
                    setLogRouteId("");
                    replaceLogRoute(logFilter, 1, includeSuperuserRequests, "", range);
                  }}
                  onClearTimeRange={() => {
                    setLogTimeRange(null);
                    setLogRoutePage(1);
                    setLogRouteId("");
                    replaceLogRoute(logFilter, 1, includeSuperuserRequests, "", null);
                  }}
                  onRefresh={requestLogRefresh}
                  onLoadMore={loadMoreLogs}
                  onOpenLog={openLogDetails}
                  onNotify={notify}
                  onOpenSettings={() => setLogsSettingsOpen(true)}
                />
              ) : view === "crons" ? (
                <CronsView crons={crons} loading={loading} onRefresh={refreshCrons} onRun={runCron} />
              ) : selected ? (
                  <RecordsView
                    collection={selected}
                    collections={collections}
                    records={records}
                    columns={columns}
                    allColumns={allColumns}
                    hiddenColumns={hiddenColumns}
                    selectedIds={selectedRecordIds}
                    query={query}
                    recordPage={recordPage}
                    loading={loading}
                    refreshSuggested={recordsNeedRefresh}
                    hideControls={hideControls}
                    fileAccessToken={token}
                    onApply={(nextQuery) => {
                      setQuery((current) =>
                        current.filter === nextQuery.filter &&
                        current.sort === nextQuery.sort &&
                          current.perPage === nextQuery.perPage
                          ? current
                          : nextQuery
                      );
                      setRecordRoutePage(1);
                      setRecordRouteId("");
                      replaceRecordRoute(nextQuery, 1, "");
                    }}
                    onRefresh={async () => {
                      await refreshRecords(selected.name, query);
                    }}
                    onLoadMore={loadMoreRecords}
                    onEditCollection={() => setCollectionEditor({ mode: "edit", collection: selected })}
                    onApiPreview={() => setApiPreviewOpen(true)}
                    onNew={() => openRecordEditor()}
                    onEdit={openRecordEditor}
                    onDelete={deleteRecord}
                    onDeleteSelected={deleteSelectedRecords}
                    onToggleColumn={toggleColumn}
                    onResetColumns={resetColumns}
                    onToggleSelected={toggleRecordSelectionFn}
                    onToggleAll={toggleCurrentPageSelectionFn}
                    onClearSelection={clearRecordSelection}
                    onOpenFile={openFile}
                  />
              ) : (
                <EmptyState icon={Database} title={t("collections.no_collection_selected", "No collection selected")} />
              )}
            </>
          )}
        </main>
      </div>

      {collectionEditor && (
        <CollectionModal
          state={collectionEditor}
          oauthProviders={oauthProviders}
          allCollections={collections}
          onClose={() => setCollectionEditor(null)}
          onConfirm={confirm}
          onDryRunView={dryRunView}
          onGenerateAppleClientSecret={generateAppleClientSecret}
          onSubmit={(payload) => saveCollection(payload)}
          onNotify={notify}
          onDuplicate={() => selected && duplicateCollection(selected)}
          onTruncate={() => selected && truncateCollection(selected)}
          onDelete={() => selected && deleteCollection(selected)}
        />
      )}

      {recordEditor && selected && (
        <RecordModal
          key={`${recordEditor.mode ?? "edit"}:${recordEditor.record?.id ?? "new"}`}
          collection={selected}
          collections={collections}
          state={recordEditor}
          hideControls={hideControls}
          onClose={closeRecordEditor}
          onConfirm={confirm}
          fetchRecords={fetchRelationRecords}
          getFileToken={getFileToken}
          onRequestVerification={() => requestRecordVerification(selected, recordEditor.record!)}
          onRequestPasswordReset={() => requestRecordPasswordReset(selected, recordEditor.record!)}
          onImpersonate={(duration) => impersonateAuthRecord(selected, recordEditor.record!, duration)}
          onLoadExternalAuths={() => loadExternalAuthLinks(selected, recordEditor.record!)}
          onUnlinkExternalAuth={unlinkExternalAuth}
          onDuplicate={() => openRecordDuplicate(recordEditor.record!)}
          onCreateRelationRecord={(target, onSaved) => {
            void openRelationRecordEditor(target, undefined, onSaved);
          }}
          onEditRelationRecord={(target, id, onSaved) => {
            void openRelationRecordEditor(target, id, onSaved);
          }}
          onNotify={notify}
          onSubmit={saveRecord}
        />
      )}

      {relationRecordEditors.map((editor) => (
        <RecordModal
          key={`relation:${editor.editorId}:${editor.collection.id}:${editor.mode ?? "edit"}:${editor.record?.id ?? "new"}`}
          collection={editor.collection}
          collections={collections}
          state={editor}
          hideControls={hideControls}
          onClose={() => closeRelationRecordEditor(editor.editorId)}
          onConfirm={confirm}
          fetchRecords={fetchRelationRecords}
          getFileToken={getFileToken}
          onRequestVerification={() => requestRecordVerification(editor.collection, editor.record!)}
          onRequestPasswordReset={() => requestRecordPasswordReset(editor.collection, editor.record!)}
          onImpersonate={(duration) => impersonateAuthRecord(editor.collection, editor.record!, duration)}
          onLoadExternalAuths={() => loadExternalAuthLinks(editor.collection, editor.record!)}
          onUnlinkExternalAuth={unlinkExternalAuth}
          onDuplicate={duplicateRelationRecord}
          onCreateRelationRecord={(target, onSaved) => {
            void openRelationRecordEditor(target, undefined, onSaved, editor.editorId);
          }}
          onEditRelationRecord={(target, id, onSaved) => {
            void openRelationRecordEditor(target, id, onSaved, editor.editorId);
          }}
          onNotify={notify}
          onSubmit={saveRelationRecord}
        />
      ))}

      {oauthResult && (
        <OAuthResultModal result={oauthResult} onClose={() => setOauthResult(null)} />
      )}

      {apiPreviewOpen && selected && (
        <ApiPreview collection={selected} baseUrl={window.location.origin} onClose={() => setApiPreviewOpen(false)} />
      )}

      {collectionsOverviewOpen && (
        <CollectionsOverview
          collections={collections}
          onClose={() => setCollectionsOverviewOpen(false)}
          onSelect={(name) => {
            setCollectionsOverviewOpen(false);
            navigateTo("records", name);
          }}
        />
      )}

      {view === "logs" && selectedLog && (
        <LogDetailsDrawer log={selectedLog} onClose={closeLogDetails} onNotify={notify} />
      )}

      {logsSettingsOpen && (
        <LogSettingsModal
          settings={settings}
          draft={settingsDraft}
          loading={loading}
          onDraft={setSettingsDraft}
          onSave={saveSettings}
          onClearLogs={clearAllLogs}
          onClose={() => setLogsSettingsOpen(false)}
        />
      )}

      {confirmState && (
        <ConfirmDialog
          {...confirmState}
          onResolve={(confirmed) => {
            confirmState.resolve(confirmed);
            setConfirmState(null);
          }}
        />
      )}

      {toasts.length > 0 && (
        <div className="toast-stack" role="region" aria-label="Notifications">
          {toasts.map((item) => (
            <div
              key={item.id}
              className={`toast ${item.kind}`}
              onMouseEnter={() => pauseToast(item.id)}
              onMouseLeave={() => resumeToast(item.id)}
            >
              <span>{item.message}</span>
              <button
                type="button"
                className="toast-close"
                onClick={() => dismissToast(item.id)}
                aria-label={t("actions.close", "Close")}
              >
                <X size={14} />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

type AuthPanelProps = {
  setupRequired: boolean;
  email: string;
  password: string;
  loading: boolean;
  /** Current email identity is locked after 10 failed attempts (UI + session). */
  accountLocked: boolean;
  mfaChallenge: MfaChallenge | null;
  otpCode: string;
  failedCount: number;
  captchaCode: string;
  captchaInput: string;
  onEmail: (value: string) => void;
  onPassword: (value: string) => void;
  onOtpCode: (value: string) => void;
  onCaptchaInput: (value: string) => void;
  onRefreshCaptcha: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onOtpSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onResendOtp: () => void;
  onCancelMfa: () => void;
};

type StoredAuthAttempt = {
  count: number;
  lockedUntil: number;
};

function normalizeAuthIdentity(email: string): string {
  return email.trim().toLowerCase();
}

function readAllAuthAttempts(): Record<string, StoredAuthAttempt> {
  try {
    const raw = sessionStorage.getItem(AUTH_ATTEMPTS_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!isPlainObject(parsed)) return {};
    const result: Record<string, StoredAuthAttempt> = {};
    const now = Date.now();
    for (const [key, value] of Object.entries(parsed)) {
      if (!isPlainObject(value)) continue;
      const count = typeof value.count === "number" && Number.isFinite(value.count) ? Math.max(0, Math.floor(value.count)) : 0;
      const lockedUntil =
        typeof value.lockedUntil === "number" && Number.isFinite(value.lockedUntil)
          ? Math.max(0, Math.floor(value.lockedUntil))
          : 0;
      // Drop expired locks with zero remaining interest.
      if (lockedUntil > 0 && lockedUntil <= now) continue;
      if (count <= 0 && lockedUntil <= now) continue;
      result[key] = { count, lockedUntil: lockedUntil > now ? lockedUntil : 0 };
    }
    return result;
  } catch {
    return {};
  }
}

function writeAllAuthAttempts(map: Record<string, StoredAuthAttempt>) {
  try {
    if (Object.keys(map).length === 0) {
      sessionStorage.removeItem(AUTH_ATTEMPTS_KEY);
    } else {
      sessionStorage.setItem(AUTH_ATTEMPTS_KEY, JSON.stringify(map));
    }
    // Drop legacy single counter if present.
    sessionStorage.removeItem(AUTH_FAILED_COUNT_KEY_LEGACY);
  } catch {
    // Private mode / storage full — in-memory state still works for this page session.
  }
}

function getAuthAttemptState(email: string): StoredAuthAttempt {
  const key = normalizeAuthIdentity(email);
  if (!key) return { count: 0, lockedUntil: 0 };
  const state = readAllAuthAttempts()[key];
  if (!state) return { count: 0, lockedUntil: 0 };
  const now = Date.now();
  if (state.lockedUntil > 0 && state.lockedUntil <= now) {
    return { count: 0, lockedUntil: 0 };
  }
  return {
    count: state.count,
    lockedUntil: state.lockedUntil > now ? state.lockedUntil : 0
  };
}

function writeAuthAttemptState(email: string, count: number, lockedUntil: number) {
  const key = normalizeAuthIdentity(email);
  if (!key) return;
  const all = readAllAuthAttempts();
  const now = Date.now();
  if (count <= 0 && lockedUntil <= now) {
    delete all[key];
  } else {
    all[key] = {
      count: Math.max(0, count),
      lockedUntil: lockedUntil > now ? lockedUntil : 0
    };
  }
  writeAllAuthAttempts(all);
}

function clearAuthAttemptState(email: string) {
  writeAuthAttemptState(email, 0, 0);
}

function isAuthIdentityLocked(email: string): boolean {
  return getAuthAttemptState(email).lockedUntil > Date.now();
}

/** Reads server-reported failure count from auth error payload data.failedAttempts. */
function failedAttemptsFromError(error: unknown): number | null {
  if (!(error instanceof ApiRequestError) || !isPlainObject(error.data)) return null;
  const value = error.data.failedAttempts;
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return Math.floor(value);
  }
  return null;
}

function generateCaptchaCode(): string {
  const chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let result = "";
  for (let i = 0; i < 4; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

/**
 * Footer icons use filled 16×16 glyphs with a padded viewBox.
 * Lucide stroke icons (and edge-touching fills) get clipped by the SVG viewport
 * at small sizes, so icons look incomplete on the page footer.
 */
function DocsBookIcon() {
  return (
    <svg
      className="footer-icon"
      viewBox="0 0 16 16"
      width={16}
      height={16}
      aria-hidden="true"
      focusable="false"
    >
      {/*
        Bootstrap Icons "book" path, slightly inset so stroke-less fills are not
        clipped by the SVG canvas edge at 16px.
      */}
      <g transform="translate(0.5 0.5) scale(0.9375)">
        <path
          fill="currentColor"
          d="M1 2.828c.885-.37 2.154-.769 3.388-.893 1.23-.124 2.503.063 3.112.752v9.746c-.935-.53-2.12-.603-3.213-.493-1.18.12-2.37.461-3.287.811zm7.5-.141c.654-.689 1.923-.876 3.112-.752 1.234.124 2.503.523 3.388.893v9.923c-.918-.35-2.107-.692-3.287-.81-1.094-.111-2.278-.039-3.213.492zM8 1.783C7.015.936 5.587.81 4.287.94c-1.514.153-3.042.672-3.994 1.105A.5.5 0 0 0 0 2.5v11a.5.5 0 0 0 .707.455c.882-.4 2.303-.881 3.68-1.02 1.409-.142 2.59.087 3.223.877a.5.5 0 0 0 .78 0c.633-.79 1.814-1.019 3.222-.877 1.378.139 2.8.62 3.681 1.02A.5.5 0 0 0 16 13.5v-11a.5.5 0 0 0-.293-.455c-.952-.433-2.48-.952-3.994-1.105C10.413.809 8.985.936 8 1.783"
        />
      </g>
    </svg>
  );
}

function GithubMarkIcon() {
  return (
    <svg
      className="footer-icon"
      viewBox="0 0 16 16"
      width={16}
      height={16}
      aria-hidden="true"
      focusable="false"
    >
      {/*
        Official Octicons mark, scaled slightly inward so the circle/ears are not
        clipped by the SVG canvas anti-alias edge (looks "cut off" at 16px).
      */}
      <g transform="translate(0.6 0.6) scale(0.925)">
        <path
          fill="currentColor"
          d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"
        />
      </g>
    </svg>
  );
}

function CaptchaView(props: { code: string; onRefresh: () => void }) {
  const { t } = useTranslation();
  const chars = props.code.split("");
  const colors = ["#ef4444", "#3b82f6", "#10b981", "#f59e0b", "#8b5cf6", "#ec4899"];
  const lines = [
    { x1: 10, y1: 15, x2: 110, y2: 25, stroke: "#64748b" },
    { x1: 5, y1: 30, x2: 115, y2: 10, stroke: "#94a3b8" }
  ];
  const refreshLabel = t("auth.captcha_refresh", "Refresh");
  const refreshTitle = t("auth.captcha_refresh_title", "Click to refresh captcha");
  return (
    <div className="captcha-container" style={{ display: "flex", alignItems: "center", gap: "0.75rem", marginTop: "0.5rem" }}>
      <svg
        width="120"
        height="38"
        viewBox="0 0 120 38"
        onClick={props.onRefresh}
        role="img"
        aria-label={refreshTitle}
        style={{
          background: "var(--surface-muted, #1e293b)",
          borderRadius: "6px",
          border: "1px solid var(--border, #334155)",
          cursor: "pointer",
          userSelect: "none"
        }}
      >
        <title>{refreshTitle}</title>
        {lines.map((l, i) => (
          <line key={i} x1={l.x1} y1={l.y1} x2={l.x2} y2={l.y2} stroke={l.stroke} strokeWidth="1.5" opacity="0.6" />
        ))}
        {chars.map((char, index) => {
          const x = 18 + index * 24;
          const y = 26;
          const rotate = (index % 2 === 0 ? 1 : -1) * (10 + (index * 7) % 15);
          const color = colors[index % colors.length];
          return (
            <text
              key={index}
              x={x}
              y={y}
              fill={color}
              fontSize="22"
              fontWeight="bold"
              fontFamily="monospace"
              transform={`rotate(${rotate}, ${x}, ${y})`}
            >
              {char}
            </text>
          );
        })}
      </svg>
      <button
        type="button"
        className="subtle compact"
        onClick={props.onRefresh}
        title={refreshTitle}
        aria-label={refreshLabel}
        style={{ fontSize: "0.75rem", padding: "0.25rem 0.5rem" }}
      >
        {refreshLabel}
      </button>
    </div>
  );
}

function AuthPanel(props: AuthPanelProps) {
  const { t } = useTranslation();

  if (props.mfaChallenge) {
    return (
      <section className="auth-layout auth-login-panel">
        <div className="auth-copy auth-login-copy">
          <img
            className="auth-brand-mark"
            src={`${import.meta.env.BASE_URL}favicon.svg`}
            alt=""
            aria-hidden="true"
            width={46}
            height={46}
            draggable={false}
          />
          <p className="eyebrow">{t("auth.mfa_step", "Step 2 of 2")}</p>
          <h2>{t("auth.enter_otp", "Enter the one-time code")}</h2>
          <p className="auth-hint">
            {t("auth.otp_sent_to", {
              email: props.mfaChallenge.email,
              defaultValue: "We sent a one-time code to {{email}}."
            })}
          </p>
        </div>
        <form className="auth-form" onSubmit={props.onOtpSubmit}>
          <label>
            <span className="auth-field-label">{t("auth.otp_code", "One-time code")}</span>
            <input
              id="superuser-otp"
              name="otp"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              autoFocus
              required
              value={props.otpCode}
              onChange={(event) => props.onOtpCode(event.target.value)}
            />
          </label>
          <button className="primary submit" type="submit" disabled={props.loading}>
            <KeyRound size={16} />
            {t("auth.verify_and_sign_in", "Verify and sign in")}
          </button>
          <div className="auth-secondary-actions">
            <button type="button" className="subtle" onClick={props.onResendOtp} disabled={props.loading}>
              {t("auth.request_another_otp", "Request another code")}
            </button>
            <button type="button" className="subtle" onClick={props.onCancelMfa} disabled={props.loading}>
              {t("actions.cancel", "Cancel")}
            </button>
          </div>
        </form>
      </section>
    );
  }

  return (
    <section className="auth-layout auth-login-panel">
      <div className="auth-copy auth-login-copy">
        <img
          className="auth-brand-mark"
          src={`${import.meta.env.BASE_URL}favicon.svg`}
          alt=""
          aria-hidden="true"
          width={46}
          height={46}
          draggable={false}
        />
        {props.setupRequired && <p className="eyebrow">{t("auth.bootstrap", "Bootstrap")}</p>}
        <h2>
          {props.setupRequired
            ? t("auth.create_first_superuser", "Create the first superuser")
            : t("auth.superuser_login", "Superuser login")}
        </h2>
      </div>
      <form className="auth-form" onSubmit={props.onSubmit}>
        <label>
          <span className="auth-field-label">
            {t("auth.email", "Email")}
            <span className="auth-required" aria-hidden="true">*</span>
          </span>
          <input
            id="superuser-email"
            name="email"
            type="email"
            autoComplete="username"
            required
            value={props.email}
            onChange={(event) => props.onEmail(event.target.value)}
          />
        </label>
        <label>
          <span className="auth-field-label">
            {t("auth.password", "Password")}
            <span className="auth-required" aria-hidden="true">*</span>
          </span>
          <PasswordInput
            id="superuser-password"
            name="password"
            autoComplete={props.setupRequired ? "new-password" : "current-password"}
            required
            minLength={8}
            value={props.password}
            onChange={(event) => props.onPassword(event.target.value)}
            disabled={props.accountLocked}
          />
        </label>
        {!props.setupRequired && !props.accountLocked && props.failedCount >= CAPTCHA_AFTER_FAILURES && (
          <label style={{ marginTop: "0.75rem" }}>
            <span className="auth-field-label">
              {t("auth.captcha_label", "Verification Code")}
              <span className="auth-required" aria-hidden="true">*</span>
            </span>
            <input
              id="superuser-captcha"
              name="captcha"
              type="text"
              maxLength={4}
              required
              autoComplete="off"
              value={props.captchaInput}
              onChange={(event) => props.onCaptchaInput(event.target.value)}
              placeholder={t("auth.captcha_placeholder", "4-digit code")}
            />
            <CaptchaView code={props.captchaCode} onRefresh={props.onRefreshCaptcha} />
          </label>
        )}
        {!props.setupRequired && (
          <button
            type="button"
            className="subtle compact auth-forgot-password"
            onClick={() => {
              window.location.hash = "#/request-password-reset?collection=_superusers";
            }}
          >
            {t("auth.forgot_password", "Forgot password?")}
          </button>
        )}
        {props.accountLocked ? (
          <p className="auth-account-locked" role="alert">
            {t(
              "auth.account_locked",
              "This account is temporarily locked after too many failed attempts. Try again in 10 minutes, or sign in with a different account."
            )}
          </p>
        ) : (
          <button className="primary submit" type="submit" disabled={props.loading}>
            {props.setupRequired ? (
              <>
                {t("auth.create_and_sign_in", "Create and sign in")}
                <KeyRound size={16} />
              </>
            ) : (
              <>
                {t("auth.sign_in", "Sign in")}
                <ArrowRight size={18} />
              </>
            )}
          </button>
        )}
      </form>
    </section>
  );
}

type CollectionSidebarProps = {
  collections: CollectionSchema[];
  currentName: string;
  pinnedNames: string[];
  search: string;
  hideControls: boolean;
  onSearch: (value: string) => void;
  onCreate: () => void;
  onOverview: () => void;
  onSelect: (collection: CollectionSchema) => void;
  onTogglePinned: (collection: CollectionSchema) => void;
};

function CollectionSidebar(props: CollectionSidebarProps) {
  const { t } = useTranslation();
  const pinned = props.pinnedNames
    .map((name) => props.collections.find((collection) => collection.name === name))
    .filter(Boolean) as CollectionSchema[];
  const pinnedSet = new Set(pinned.map((collection) => collection.name));
  const regular = props.collections.filter((collection) => !pinnedSet.has(collection.name) && !isSystemCollection(collection));
  const systemOrder = new Map([
    ["_superusers", 0],
    ["_authOrigins", 1],
    ["_externalAuths", 2],
    ["_mfas", 3],
    ["_otps", 4]
  ]);
  const system = props.collections
    .filter((collection) => !pinnedSet.has(collection.name) && isSystemCollection(collection))
    .sort(
      (left, right) =>
        (systemOrder.get(left.name) ?? Number.MAX_SAFE_INTEGER) -
          (systemOrder.get(right.name) ?? Number.MAX_SAFE_INTEGER) ||
        left.name.localeCompare(right.name)
    );
  const noMatches = props.search.trim().length > 0 && props.collections.length === 0;

  return (
    <aside className="sidebar collections-sidebar">
      <div className="search-box sidebar-search">
        <input
          id="collection-search"
          name="collectionSearch"
          autoComplete="off"
          value={props.search}
          onChange={(event) => props.onSearch(event.target.value)}
          placeholder={t("collections.search_placeholder", "Search collections...")}
          aria-label={t("collections.search_placeholder", "Search collections...")}
        />
        {/* Official: Clear only when input has content; overview always visible. */}
        <div className="sidebar-search-addons">
          {props.search.trim().length > 0 && (
            <button
              type="button"
              className="icon-button page-circle sidebar-search-addon-btn"
              onClick={() => props.onSearch("")}
              title={t("actions.clear", "Clear")}
              aria-label={t("actions.clear", "Clear")}
            >
              <X size={16} />
            </button>
          )}
          <button
            type="button"
            className="icon-button page-circle sidebar-search-addon-btn sidebar-search-overview-btn"
            onClick={props.onOverview}
            title={t("parity.collection.overview", "Collections overview")}
            aria-label={t("parity.collection.overview", "Collections overview")}
          >
            <Network size={16} />
          </button>
        </div>
      </div>

      {noMatches ? (
        <div className="sidebar-no-results">
          <p>{t("collections.no_collections")}</p>
          <button className="subtle" onClick={() => props.onSearch("")}>
            {t("actions.clear_search", "Clear search")}
          </button>
        </div>
      ) : (
        <nav className={(pinned.length + regular.length > 12 ? "collection-nav compact" : "collection-nav")} aria-label={t("nav.collections", "Collections")}>
          {pinned.length > 0 && (
            <CollectionGroup
              title={t("collections.pinned", "Pinned")}
              collections={pinned}
              currentName={props.currentName}
              pinnedNames={props.pinnedNames}
              onSelect={props.onSelect}
              onTogglePinned={props.onTogglePinned}
            />
          )}
          {regular.length > 0 && (
            <CollectionGroup
              title={pinned.length > 0 ? t("collections.others", "Others") : t("nav.collections", "Collections")}
              collections={regular}
              currentName={props.currentName}
              pinnedNames={props.pinnedNames}
              onSelect={props.onSelect}
              onTogglePinned={props.onTogglePinned}
            />
          )}
          {system.length > 0 && (
            <CollectionGroup
              title={t("collections.system", "System")}
              collections={system}
              currentName={props.currentName}
              pinnedNames={props.pinnedNames}
              onSelect={props.onSelect}
              onTogglePinned={props.onTogglePinned}
              collapsible
            />
          )}
        </nav>
      )}

      <div className="sidebar-actions">
        <button className="subtle outline-button" onClick={props.onOverview}>
          <GitBranch size={16} />
          {t("parity.collection.overview", "Collections overview")}
        </button>
        {!props.hideControls && (
          <button className="subtle outline-button" onClick={props.onCreate}>
            <Plus size={16} />
            {t("actions.new_collection", "New collection")}
          </button>
        )}
      </div>
    </aside>
  );
}

type CollectionGroupProps = {
  title: string;
  collections: CollectionSchema[];
  currentName: string;
  pinnedNames: string[];
  onSelect: (collection: CollectionSchema) => void;
  onTogglePinned: (collection: CollectionSchema) => void;
  collapsible?: boolean;
};

function CollectionGroup(props: CollectionGroupProps) {
  const { t } = useTranslation();
  // `collapsible` is the semantic System-group flag. Do not infer behavior from
  // a translated title, which would leave the group expanded in non-English UI.
  const [collapsed, setCollapsed] = useState(Boolean(props.collapsible));
  const expanded = !props.collapsible || !collapsed;
  // Keep the active collection visible as the selection changes (keyboard nav,
  // deep links) without scrolling the whole page.
  const activeRowRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    activeRowRef.current?.scrollIntoView({ block: "nearest" });
  }, [props.currentName]);
  // Auto-expand when the active collection lives in this group.
  useEffect(() => {
    if (props.currentName && props.collections.some((collection) => collection.name === props.currentName)) {
      setCollapsed(false);
    }
  }, [props.currentName, props.collections]);
  return (
    <section className={`sidebar-group${expanded ? "" : " collapsed"}`}>
      {props.collapsible ? (
        <button
          className="sidebar-section-title sidebar-group-toggle"
          onClick={() => setCollapsed((value) => !value)}
          aria-expanded={expanded}
          aria-label={
            expanded
              ? t("collections.collapse_group", { name: props.title, defaultValue: "Collapse {{name}}" })
              : t("collections.expand_group", { name: props.title, defaultValue: "Expand {{name}}" })
          }
        >
          <span>{props.title}</span>
          <ChevronUp size={14} className="sidebar-group-chevron" />
        </button>
      ) : (
        <div className="sidebar-section-title">{props.title}</div>
      )}
      <div className="sidebar-group-body">
        <div className="sidebar-group-items">
          {props.collections.map((collection) => {
            const pinned = props.pinnedNames.includes(collection.name);
            const isActive = props.currentName === collection.name;
            return (
              <div
                ref={isActive ? activeRowRef : undefined}
                className={isActive ? "collection-nav-row active" : "collection-nav-row"}
                key={collection.id || collection.name}
              >
                <button
                  className="collection-nav-main"
                  onClick={() => {
                    if (props.collapsible) setCollapsed(false);
                    props.onSelect(collection);
                  }}
                  title={collection.name}
                >
                  <span className="nav-icon">
                    {/* Official PB uses ri-group-line for auth collections (users/group icon). */}
                    {collection.type === "auth" ? <Users size={16} /> : <Database size={16} />}
                  </span>
                  <span className="nav-text">
                    <strong>{collection.name}</strong>
                  </span>
                </button>
                <button
                  className="icon-button pin-button"
                  onClick={() => props.onTogglePinned(collection)}
              title={pinned ? t("actions.unpin_collection", "Unpin collection") : t("actions.pin_collection", "Pin collection")}
              aria-label={pinned ? t("actions.unpin_collection", "Unpin collection") : t("actions.pin_collection", "Pin collection")}
            >
              {pinned ? <PinOff size={14} /> : <Pin size={14} />}
            </button>
          </div>
        );
      })}
        </div>
      </div>
    </section>
  );
}

/** Official PocketBase settings sidebar: System → Sync → Debug (always expanded). */
const getSettingsNavGroups = (t: any): Array<{
  title: string;
  items: Array<{ view: ViewName; label: string; icon: LucideIcon }>;
}> => [
  {
    title: t("settings.nav.system", "System"),
    items: [
      { view: "settings", label: t("settings.nav.application", "Application"), icon: Settings },
      { view: "mail", label: t("settings.nav.mail", "Mail settings"), icon: Mail },
      { view: "storage", label: t("settings.nav.storage", "Files storage"), icon: HardDrive },
      { view: "backups", label: t("settings.nav.backups", "Backups"), icon: FileArchive },
      { view: "crons", label: t("settings.nav.crons", "Crons"), icon: Clock3 }
    ]
  },
  {
    title: t("settings.nav.sync", "Sync"),
    items: [
      { view: "export", label: t("settings.nav.export", "Export collections"), icon: Download },
      { view: "import", label: t("settings.nav.import", "Import collections"), icon: Upload }
    ]
  },
  {
    title: t("settings.nav.debug", "Debug"),
    items: [{ view: "sql", label: t("settings.nav.sql", "SQL console"), icon: Code2 }]
  }
];

function SettingsSidebar({
  current,
  onSelect,
  hideControls
}: {
  current: ViewName;
  onSelect: (view: ViewName) => void;
  hideControls: boolean;
}) {
  const { t } = useTranslation();
  const groups = useMemo(() => getSettingsNavGroups(t), [t]);

  return (
    <aside className="sidebar settings-sidebar">
      {groups.map((group) => {
        // Official "Sync" group is hidden when collection/record controls are locked.
        const items = hideControls
          ? group.items.filter((item) => item.view !== "export" && item.view !== "import")
          : group.items;
        if (items.length === 0) return null;
        return (
          <section className="sidebar-group" key={group.title}>
            <div className="sidebar-section-title">{group.title}</div>
            <nav className="settings-nav" aria-label={group.title}>
              {items.map((item) => {
                const Icon = item.icon;
                return (
                  <button
                    key={item.view}
                    type="button"
                    className={current === item.view ? "active" : ""}
                    onClick={() => onSelect(item.view)}
                  >
                    <span className="nav-icon">
                      <Icon size={16} />
                    </span>
                    <span className="nav-text">
                      <strong>{item.label}</strong>
                    </span>
                  </button>
                );
              })}
            </nav>
          </section>
        );
      })}
    </aside>
  );
}

type RecordsViewProps = {
  collection: CollectionSchema;
  collections: CollectionSchema[];
  records: RecordItem[];
  columns: string[];
  allColumns: string[];
  hiddenColumns: string[];
  selectedIds: string[];
  query: QueryState;
  recordPage: ListResponse<RecordItem> | null;
  loading: boolean;
  refreshSuggested: boolean;
  hideControls: boolean;
  fileAccessToken: string;
  onApply: (query: QueryState) => void;
  onRefresh: () => void | Promise<void>;
  onLoadMore: () => void | Promise<void>;
  onEditCollection: () => void;
  onApiPreview: () => void;
  onNew: () => void;
  onEdit: (record: RecordItem) => void;
  onDelete: (record: RecordItem) => void | Promise<void>;
  onDeleteSelected: () => void;
  onToggleColumn: (column: string) => void;
  onResetColumns: () => void;
  onToggleSelected: (id: string, extendRange?: boolean) => void;
  onToggleAll: (checked: boolean, anchorId?: string) => void;
  onClearSelection: () => void;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function RecordsView(props: RecordsViewProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(props.query);
  const [columnsOpen, setColumnsOpen] = useState(false);
  const [activeRecordId, setActiveRecordId] = useState(() => props.records[0]?.id ?? "");
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());
  const deleteInFlight = useRef(false);
  const searchHistoryKey = useMemo(
    () => `pbj_record_search_history:${props.collection.id || props.collection.name}`,
    [props.collection.id, props.collection.name]
  );
  const [searchHistory, setSearchHistory] = useState<string[]>(() => readSearchHistory(searchHistoryKey));
  const selectedSet = useMemo(() => new Set(props.selectedIds), [props.selectedIds]);
  const focusedRecordId = props.records.some((record) => record.id === activeRecordId)
    ? activeRecordId
    : props.records[0]?.id ?? "";
  const allVisibleSelected =
    props.records.length > 0 && props.records.every((record) => selectedSet.has(record.id));
  const canCreateRecord = props.collection.type !== "view";
  const canDeleteRecords = props.collection.type !== "view";
  const hasMoreRecords = Boolean(
    props.recordPage && props.records.length > 0 && props.records.length < props.recordPage.totalItems
  );
  const sortState = parseSortValue(draft.sort);
  const sortableColumns = useMemo(() => {
    const source = props.columns.length ? props.columns : props.allColumns;
    const columns = source.filter((column) => column !== "expand");
    return sortState.field && !columns.includes(sortState.field) ? [sortState.field, ...columns] : columns;
  }, [props.allColumns, props.columns, sortState.field]);
  const sortColumnOptions = useMemo(() => sortableColumns.map((column) => ({ value: column, label: column })), [sortableColumns]);
  const sortDirectionOptions = useMemo(
    () => [
      { value: "asc" as SortDirection, label: t("collections.sort_asc", "Ascending") },
      { value: "desc" as SortDirection, label: t("collections.sort_desc", "Descending") }
    ],
    [t]
  );
  const perPageOptions = useMemo(() => [25, 50, 100, 200].map((value) => ({ value, label: String(value) })), []);
  const sortFieldWidth = useMemo(() => compactSelectWidth(sortableColumns), [sortableColumns]);
  const sortDirectionWidth = compactSelectWidth(sortDirectionOptions.map((option) => String(option.label)));
  const perPageWidth = compactSelectWidth(perPageOptions.map((option) => String(option.label)));

  useEffect(() => setDraft(props.query), [props.query]);
  useEffect(() => setSearchHistory(readSearchHistory(searchHistoryKey)), [searchHistoryKey]);
  useEffect(() => {
    setActiveRecordId((current) =>
      props.records.some((record) => record.id === current) ? current : props.records[0]?.id ?? ""
    );
  }, [props.records]);

  function rememberSearch(value: string) {
    const next = writeSearchHistory(searchHistoryKey, value);
    setSearchHistory(next);
  }

  function apply() {
    rememberSearch(draft.filter);
    props.onApply(draft);
  }

  function clearFilter() {
    const next = { ...draft, filter: "" };
    setDraft(next);
    props.onApply(next);
  }

  function updateSort(field: string, direction = sortState.direction) {
    setDraft({ ...draft, sort: formatSortValue(field, direction) });
  }

  function updateSortDirection(direction: SortDirection) {
    setDraft({ ...draft, sort: formatSortValue(sortState.field || sortableColumns[0] || "created", direction) });
  }

  function toggleColumnSort(column: string) {
    const direction: SortDirection = sortState.field === column && sortState.direction === "asc" ? "desc" : "asc";
    const next = { ...draft, sort: formatSortValue(column, direction) };
    setDraft(next);
    props.onApply(next);
  }

  function focusRecord(recordId: string) {
    setActiveRecordId(recordId);
    window.requestAnimationFrame(() => rowRefs.current.get(recordId)?.focus());
  }

  function moveRecordFocus(index: number, extendSelection = false) {
    const target = props.records[Math.max(0, Math.min(index, props.records.length - 1))];
    if (!target) return;
    focusRecord(target.id);
    if (extendSelection) props.onToggleSelected(target.id, true);
  }

  function visibleRowStep(recordId: string) {
    const row = rowRefs.current.get(recordId);
    const viewport = row?.closest<HTMLElement>(".page-table-scroll, .page-table-wrapper");
    const rowHeight = Math.max(1, row?.getBoundingClientRect().height || 44);
    return Math.max(1, Math.floor((viewport?.clientHeight || window.innerHeight) / rowHeight) - 1);
  }

  function handleRecordRowKeyDown(event: ReactKeyboardEvent<HTMLTableRowElement>, record: RecordItem, index: number) {
    // Buttons and field controls inside a row retain their native keyboard
    // behavior. The roving row target is the only element that owns these keys.
    if (event.target !== event.currentTarget) return;

    const extendSelection = event.shiftKey;
    const move = (nextIndex: number) => {
      event.preventDefault();
      const targetIndex = Math.max(0, Math.min(nextIndex, props.records.length - 1));
      if (targetIndex !== index) moveRecordFocus(targetIndex, extendSelection);
    };

    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "a") {
      event.preventDefault();
      props.onToggleAll(true, record.id);
      return;
    }

    switch (event.key) {
      case "ArrowUp":
        move(index - 1);
        return;
      case "ArrowDown":
        move(index + 1);
        return;
      case "Home":
        move(0);
        return;
      case "End":
        move(props.records.length - 1);
        return;
      case "PageUp":
        move(index - visibleRowStep(record.id));
        return;
      case "PageDown":
        move(index + visibleRowStep(record.id));
        return;
      case "Enter":
        event.preventDefault();
        props.onEdit(record);
        return;
      case " ":
      case "Spacebar":
        event.preventDefault();
        props.onToggleSelected(record.id, extendSelection);
        return;
      case "Escape":
        event.preventDefault();
        props.onClearSelection();
        return;
      case "Delete":
      case "Backspace":
        if (!canDeleteRecords || deleteInFlight.current) return;
        event.preventDefault();
        deleteInFlight.current = true;
        void Promise.resolve(props.onDelete(record)).finally(() => {
          deleteInFlight.current = false;
        });
        return;
      default:
        return;
    }
  }

  return (
    <section className="records-page">
      <header className="page-header records-page-header">
        {/* Official layout: breadcrumb + settings/refresh on the left; API preview + New record on the right. */}
        <div className="page-header-left">
          <nav className="breadcrumbs" aria-label={t("common.breadcrumb", "Breadcrumb")}>
            <span>{t("nav.collections")}</span>
            <span title={props.collection.name}>{props.collection.name}</span>
          </nav>
          <div className="page-header-inline-btns">
            {!props.hideControls && (
              <button
                className="icon-button page-circle"
                onClick={props.onEditCollection}
                title={t("collections.collection_settings", "Collection settings")}
                aria-label={t("collections.collection_settings", "Collection settings")}
              >
                <Settings size={17} />
              </button>
            )}
            <RefreshButton
              className="icon-button page-circle"
              refreshSuggested={props.refreshSuggested}
              onClick={props.onRefresh}
              title={t("actions.refresh_records", "Refresh records")}
            />
          </div>
        </div>
        <div className="page-header-right">
          <button
            type="button"
            className="subtle outline-button page-header-api-btn"
            onClick={props.onApiPreview}
            title={t("collections.api_preview", "API preview")}
          >
            <Code2 size={16} />
            <span>{t("collections.api_preview", "API preview")}</span>
          </button>
          {canCreateRecord && (
            <button type="button" className="primary new-record-btn" onClick={props.onNew}>
              <Plus size={16} />
              <span>{t("actions.new_record", "New record")}</span>
            </button>
          )}
        </div>
      </header>

      <div className="records-searchbar-row">
        <div className="searchbar records-searchbar">
          <Search size={17} />
          <input
            id="records-filter"
            name="filter"
            autoComplete="off"
            list="records-search-history"
            aria-label={t("logs.search_aria", "Search term or filter")}
            value={draft.filter}
            onChange={(event) => setDraft({ ...draft, filter: event.target.value })}
            onKeyDown={(event) => {
              if (event.key === "Enter") apply();
            }}
            placeholder={t("records.search_placeholder", "Search term or filter...")}
          />
          {draft.filter && (
            <button
              type="button"
              className="search-clear-button"
              onClick={clearFilter}
              title={t("actions.clear_search", "Clear search")}
              aria-label={t("actions.clear_search", "Clear search")}
            >
              <X size={14} />
            </button>
          )}
          <datalist id="records-search-history">
            {searchHistory.map((value) => <option key={value} value={value} />)}
          </datalist>
        </div>
        <label className="compact-field sort-field">
          {t("collections.sort", "Sort")}
          <span className="sort-controls">
            <DropdownSelect
              id="records-sort"
              name="sort"
              value={sortState.field}
              options={sortColumnOptions}
              onChange={updateSort}
              ariaLabel={t("collections.sort_field", "Sort field")}
              className="compact-dropdown"
              style={{ width: sortFieldWidth }}
            />
            <DropdownSelect<SortDirection>
              id="records-sort-direction"
              name="sortDirection"
              value={sortState.direction}
              options={sortDirectionOptions}
              onChange={updateSortDirection}
              ariaLabel={t("collections.sort_direction", "Sort direction")}
              className="compact-dropdown"
              style={{ width: sortDirectionWidth }}
            />
          </span>
        </label>
        <label className="compact-field per-page-field">
          {t("collections.per_page", "Per page")}
          <DropdownSelect<number>
            id="records-per-page"
            name="perPage"
            value={draft.perPage}
            options={perPageOptions}
            onChange={(value) => setDraft({ ...draft, perPage: value })}
            ariaLabel={t("collections.per_page", "Per page")}
            className="compact-dropdown"
            style={{ width: perPageWidth }}
          />
        </label>
        <button className="subtle apply-button" onClick={apply} disabled={props.loading}>
          <ListFilter size={16} />
          {t("actions.apply", "Apply")}
        </button>
        <div className="column-picker">
          <button className="subtle" onClick={() => setColumnsOpen((open) => !open)}>
            <Columns3 size={16} />
            {t("collections.columns", "Columns")}
          </button>
          {columnsOpen && (
            <div className="columns-popover">
              <div className="columns-popover-header">
                <strong>{t("collections.visible_columns")}</strong>
                <button className="icon-button tiny" onClick={props.onResetColumns} title={t("collections.reset_columns", "Reset columns")} aria-label={t("collections.reset_columns", "Reset columns")}>
                  <RotateCcw size={14} />
                </button>
              </div>
              <div className="stacked-checks">
                {props.allColumns.map((column) => (
                  <label className="check-row" key={column}>
                    <input
                      type="checkbox"
                      checked={!props.hiddenColumns.includes(column)}
                      onChange={() => props.onToggleColumn(column)}
                    />
                    {column}
                  </label>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="page-table-wrapper">
        <table className="records-table responsive-table">
          <thead>
            <tr>
              <th className="select-col">
                <button
                  className={`checkbox-button${allVisibleSelected ? " is-checked" : ""}`}
                  onClick={() => props.onToggleAll(!allVisibleSelected)}
                  title={allVisibleSelected ? t("actions.clear_selection", "Clear selection") : t("actions.select_page", "Select page")}
                  aria-label={allVisibleSelected ? t("actions.clear_selection", "Clear selection") : t("actions.select_page", "Select page")}
                >
                  {allVisibleSelected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                </button>
              </th>
              {props.columns.map((column) => {
                const sorted = sortState.field === column;
                return (
                  <th key={column} aria-sort={sorted ? (sortState.direction === "asc" ? "ascending" : "descending") : "none"}>
                    <button
                      type="button"
                      className={sorted ? "records-sort-button is-sorted" : "records-sort-button"}
                      onClick={() => toggleColumnSort(column)}
                      title={`${t("collections.sort", "Sort")}: ${column}`}
                      aria-label={`${t("collections.sort", "Sort")}: ${column}`}
                    >
                      <span>{column}</span>
                      {sorted && <span className="records-sort-indicator" aria-hidden="true">{sortState.direction === "asc" ? "↑" : "↓"}</span>}
                    </button>
                  </th>
                );
              })}
              <th className="row-actions"></th>
            </tr>
          </thead>
          <tbody>
            {props.records.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={props.columns.length + 2}>
                  <div className="empty-table-message">
                    <strong>{t("collections.no_records")}</strong>
                    {!draft.filter && canCreateRecord && (
                      <button className="subtle" onClick={props.onNew}>
                        <Plus size={16} />
                        {t("actions.new_record", "New record")}
                      </button>
                    )}
                    {draft.filter && (
                      <button
                        className="subtle"
                        onClick={() => {
                          clearFilter();
                        }}
                      >
                        {t("actions.clear_search", "Clear search")}
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ) : (
              props.records.map((record, index) => {
                const selected = selectedSet.has(record.id);
                return (
                  <tr
                    ref={(node) => {
                      if (node) rowRefs.current.set(record.id, node);
                      else rowRefs.current.delete(record.id);
                    }}
                    className={`record-row${selected ? " selected" : ""}`}
                    key={record.id}
                    tabIndex={focusedRecordId === record.id ? 0 : -1}
                    aria-selected={selected}
                    onFocus={() => setActiveRecordId(record.id)}
                    onKeyDown={(event) => handleRecordRowKeyDown(event, record, index)}
                    onClick={() => props.onEdit(record)}
                  >
                    <td className="select-col">
                      <button
                        className={`checkbox-button${selected ? " is-checked" : ""}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          props.onToggleSelected(record.id, event.shiftKey);
                        }}
                        title={selected ? t("actions.unselect_record", "Unselect record") : t("actions.select_record", "Select record")}
                        aria-label={selected ? t("actions.unselect_record", "Unselect record") : t("actions.select_record", "Select record")}
                      >
                        {selected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                      </button>
                    </td>
                    {props.columns.map((column) => {
                      const fieldDef = (props.collection.fields ?? []).find((f) => f.name === column);
                      const isHiddenField = Boolean(fieldDef?.hidden);
                      return (
                        <td key={column} className={isHiddenField ? "hidden-field-col" : ""}>
                          <CellValue
                            collection={props.collection}
                            collections={props.collections}
                            column={column}
                            record={record}
                            fileAccessToken={props.fileAccessToken}
                            onOpenFile={props.onOpenFile}
                          />
                        </td>
                      );
                    })}
                    <td className="row-actions">
                      <span className="row-arrow" aria-hidden="true">
                        <ChevronRight size={16} />
                      </span>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
      {hasMoreRecords && (
        <div className="load-more-row">
          <button className="subtle" onClick={() => props.onLoadMore()} disabled={props.loading}>
            {props.loading
              ? t("common.loading", "Loading...")
              : t("records.load_more", {
                  count: (props.recordPage?.totalItems ?? 0) - props.records.length,
                  defaultValue: "Load more ({{count}} remaining)"
                })}
          </button>
        </div>
      )}
      {props.selectedIds.length > 0 && (
        <div className="selection-tray" role="status" aria-live="polite">
          <div className="selection-tray-panel">
            <div className="selection-tray-group">
              <span className="selection-tray-count">
                {t("records.selected_count", {
                  count: props.selectedIds.length,
                  defaultValue: "Selected {{count}} record"
                })}
              </span>
              <button type="button" className="selection-tray-btn" onClick={props.onClearSelection}>
                {t("actions.reset", "Reset")}
              </button>
            </div>
            <div className="selection-tray-group selection-tray-actions">
              {canDeleteRecords && (
                <button
                  type="button"
                  className="selection-tray-btn selection-tray-delete"
                  onClick={props.onDeleteSelected}
                >
                  <Trash2 size={14} />
                  {t("actions.delete", "Delete")}
                </button>
              )}
              <button
                type="button"
                className="selection-tray-btn selection-tray-json"
                onClick={() => {
                  const selectedRecords = props.records.filter((record) => selectedSet.has(record.id));
                  downloadJsonFile(
                    selectedRecords,
                    `pocketbase-${props.collection.name}-selected.json`
                  );
                }}
              >
                <Download size={14} />
                JSON
              </button>
            </div>
          </div>
        </div>
      )}

      <footer className="page-footer">
        <div className="page-footer-left">
          <span>
            {t("common.loaded_of_total", {
              loaded: props.records.length,
              count: props.recordPage?.totalItems ?? props.records.length,
              defaultValue: "Showing {{loaded}} of {{count}}"
            })}
          </span>
          <span>{t("collections.fields_count", { count: props.collection.fields?.length ?? 0, defaultValue: "{{count}} fields" })}</span>
          <span>{t("collections.columns_count", { shown: props.columns.length, total: props.allColumns.length, defaultValue: "{{shown}}/{{total}} columns" })}</span>
        </div>
        <div className="page-footer-right">
          <a
            href="javascript:void(0)"
            className="footer-link"
            onClick={(e) => e.preventDefault()}
            title={t("footer.docs", "Docs")}
          >
            <DocsBookIcon />
            <span>Docs</span>
          </a>
          <span className="footer-link-separator">|</span>
          <a
            href="https://github.com/jackBaozz/pocketbase-java"
            target="_blank"
            rel="noopener noreferrer"
            className="footer-link"
            title="PocketBase Java GitHub"
          >
            <GithubMarkIcon />
            <span>PocketBase v0.4.0</span>
          </a>
        </div>
      </footer>
    </section>
  );
}

type CellValueProps = {
  collection: CollectionSchema;
  column: string;
  record: RecordItem;
  collections?: CollectionSchema[];
  fileAccessToken: string;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function CellValue({ collection, collections, column, record, fileAccessToken, onOpenFile }: CellValueProps) {
  const field = collection.fields?.find((item) => item.name === column);
  const value = record[column];

  // Date-like fields (schema date/autodate and the system created/updated) show a
  // localized timestamp with the raw UTC value available on hover, like the official UI.
  if ((field?.type === "date" || field?.type === "autodate" || column === "created" || column === "updated") && value) {
    const iso = String(value);
    const local = formatDate(iso);
    return <code title={iso}>{local}</code>;
  }

  if (field?.type === "relation" && value) {
    const ids = (Array.isArray(value) ? value : [value]).map(String).filter(Boolean);
    const target = collections ? relationTarget(field, collections) : undefined;
    const rawExpand = record.expand ?? record["@expand"];
    const expand = isPlainObject(rawExpand) ? (rawExpand as Record<string, unknown>) : undefined;
    const expanded = expand?.[column];
    const expandedList = expanded === undefined ? [] : Array.isArray(expanded) ? expanded : [expanded];
    const summaries = new Map<string, string>();
    for (const item of expandedList) {
      if (isPlainObject(item) && typeof item.id === "string") {
        summaries.set(item.id, recordSummary(item as RelationRecord, target, collections ?? []));
      }
    }
    const shown = ids.slice(0, 3);
    return (
      <div className="relation-cell">
        {shown.map((id) => (
          <span key={id} className="relation-cell-item" title={id}>
            {summaries.get(id) ?? id}
          </span>
        ))}
        {ids.length > shown.length && <span className="relation-cell-more">({ids.length - shown.length} more)</span>}
      </div>
    );
  }

  if (field?.type === "file" && value) {
    const files = Array.isArray(value) ? value : [value];
    const collectionId = collection.id || collection.name;
    return (
      <div className="file-list">
        {files.filter(Boolean).map((filename) => {
          const name = String(filename);
          const isImage = safeImageFilename(name);
          const baseUrl = `/api/files/${encodeURIComponent(collectionId)}/${encodeURIComponent(record.id)}/${encodeURIComponent(name)}`;
          const thumbnail = fileThumbnailSpec(field);
          const fileUrl = thumbnail ? `${baseUrl}?thumb=${encodeURIComponent(thumbnail)}` : baseUrl;
          return (
            <button
              className={isImage ? "file-thumb" : "file-pill"}
              key={name}
              onClick={(event) => {
                event.stopPropagation();
                onOpenFile(record, name);
              }}
              title={name}
            >
              {isImage ? (
                <FileThumbnail url={fileUrl} filename={name} accessToken={fileAccessToken} />
              ) : (
                <>
                  <Download size={13} />
                  {name}
                </>
              )}
            </button>
          );
        })}
      </div>
    );
  }

  if (typeof value === "boolean") {
    return <span className={value ? "bool yes" : "bool no"}>{value ? "true" : "false"}</span>;
  }

  return <code>{formatValue(value)}</code>;
}

type FileThumbnailProps = {
  url: string;
  filename: string;
  accessToken: string;
};

/**
 * Browser image elements cannot attach the dashboard bearer token. Fetch the
 * file with authorization and render an object URL instead, keeping protected
 * images private without putting a file token into the DOM.
 */
function FileThumbnail({ url, filename, accessToken }: FileThumbnailProps) {
  const rootRef = useRef<HTMLSpanElement>(null);
  const [shouldLoad, setShouldLoad] = useState(false);
  const [source, setSource] = useState("");
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const node = rootRef.current;
    if (!node || !("IntersectionObserver" in window)) {
      setShouldLoad(true);
      return undefined;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        setShouldLoad(true);
        observer.disconnect();
      },
      { rootMargin: "160px" }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!shouldLoad) return undefined;
    const controller = new AbortController();
    let objectUrl = "";
    setSource("");
    setFailed(false);
    void fetch(url, {
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
      signal: controller.signal
    })
      .then(async (response) => {
        if (!response.ok) throw new Error(`Failed to load file (${response.status})`);
        const blob = await response.blob();
        if (!blob.type.startsWith("image/")) throw new Error("The file is not an image.");
        return blob;
      })
      .then((blob) => {
        if (controller.signal.aborted) return;
        objectUrl = URL.createObjectURL(blob);
        setSource(objectUrl);
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [accessToken, shouldLoad, url]);

  return (
    <span ref={rootRef} className="file-thumb-content">
      {failed ? (
        <span className="file-thumb-fallback">
          <Download size={13} />
          <span>{filename}</span>
        </span>
      ) : source ? (
        <img className="file-thumb-img" src={source} alt={filename} />
      ) : (
        <span className="file-thumb-loading" aria-hidden="true" />
      )}
    </span>
  );
}


type BackupViewProps = {
  backups: BackupInfo[];
  settings: AppSettings | null;
  draft: string;
  backupName: string;
  canBackup: boolean;
  operation: BackupOperation | null;
  loading: boolean;
  uploadRef: RefObject<HTMLInputElement | null>;
  onConfirm: (request: ConfirmRequest) => Promise<boolean>;
  onBackupName: (value: string) => void;
  onDraft: (value: string) => void;
  onSave: () => void;
  onCreate: () => void;
  onRefresh: () => void;
  onUpload: (file: File) => void;
  onDownload: (backup: BackupInfo) => void;
  onRestore: (backup: BackupInfo) => void;
  onDelete: (backup: BackupInfo) => void;
};

function SettingsPageHeader({ section, actions }: { section: string; actions?: ReactNode }) {
  const { t } = useTranslation();
  return (
    <header className="page-header settings-page-header">
      <nav className="breadcrumbs" aria-label={t("common.breadcrumb", "Breadcrumb")}>
        <span>{t("nav.settings")}</span>
        <span>{section}</span>
      </nav>
      {actions && <div className="page-header-primary-btns">{actions}</div>}
    </header>
  );
}

function BackupView(props: BackupViewProps) {
  const { t } = useTranslation();
  const [createOpen, setCreateOpen] = useState(false);
  const [optionsOpen, setOptionsOpen] = useState(false);
  const draftSettings = useMemo(() => parseSettingsDraft(props.draft, props.settings), [props.draft, props.settings]);
  const backupsSettings = settingsObject(draftSettings, "backups");
  const backupS3 = isPlainObject(backupsSettings.s3) ? (backupsSettings.s3 as Record<string, unknown>) : {};
  const sortedBackups = useMemo(
    () => [...props.backups].sort((left, right) => String(right.modified ?? "").localeCompare(String(left.modified ?? ""))),
    [props.backups]
  );
  const totalSize = sortedBackups.reduce((sum, backup) => sum + Number(backup.size || 0), 0);
  const latestBackup = sortedBackups[0];
  const autoBackupsEnabled = Boolean(backupsSettings.cron);
  const backupS3Enabled = Boolean(backupS3.enabled);
  const hasBackupS3Secret = Object.prototype.hasOwnProperty.call(backupS3, "secret");
  const operationBusy = Boolean(props.operation) || !props.canBackup;
  const cronPresets = [
    { cron: "0 0 * * *", label: t("settings.cron_every_day", "Every day at 00:00h") },
    { cron: "0 0 * * 0", label: t("settings.cron_every_sunday", "Every Sunday at 00:00h") },
    { cron: "0 0 * * 1,3", label: t("settings.cron_every_mon_wed", "Every Mon and Wed at 00:00h") },
    { cron: "0 0 1 * *", label: t("settings.cron_every_month_first", "Every first day of the month") }
  ];

  function updateSetting(path: string[], value: unknown) {
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, path, value);
    props.onDraft(JSON.stringify(next, null, 2));
  }

  function updateNumber(path: string[], value: string) {
    updateSetting(path, value === "" ? 0 : Number(value));
  }

  function toggleAutoBackups(enabled: boolean) {
    updateSetting(["backups", "cron"], enabled ? String(backupsSettings.cron || cronPresets[0].cron) : "");
  }

  async function confirmUpload(file?: File) {
    if (!file) return;
    const confirmed = await props.onConfirm({
      title: t("confirm.upload_backup_title", "Upload backup"),
      message: t("confirm.upload_backup", {
        name: file.name,
        defaultValue:
          'Uploaded backup files are not validated before restore. Proceed only if you trust the source. Upload "{{name}}"?'
      }),
      confirmLabel: t("actions.upload", "Upload"),
      danger: true
    });
    if (confirmed) {
      props.onUpload(file);
    } else if (props.uploadRef.current) {
      props.uploadRef.current.value = "";
    }
  }

  function closeCreateModal() {
    props.onBackupName("");
    setCreateOpen(false);
  }

  function startBackup() {
    props.onCreate();
    setCreateOpen(false);
  }

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.backups", "Backups")}
        actions={
          <>
            <RefreshButton className="icon-button page-circle" onClick={props.onRefresh} title={t("actions.refresh_backups", "Refresh backups")} />
            <button className="icon-button page-circle" onClick={() => props.uploadRef.current?.click()} disabled={operationBusy || props.loading} title={t("actions.upload_backup", "Upload backup")} aria-label={t("actions.upload_backup", "Upload backup")}>
              <Upload size={17} />
            </button>
            <input
              ref={props.uploadRef}
              className="hidden-input"
              name="backupFile"
              type="file"
              accept=".zip,application/zip"
              onChange={(event) => confirmUpload(event.target.files?.[0])}
            />
          </>
        }
      />

      {props.operation && (
        <aside className="settings-alert info backup-operation-notice" aria-live="polite">
          <RefreshCw className="backup-operation-spinner" size={17} aria-hidden="true" />
          <div>
            <strong>
              {props.operation.kind === "create"
                ? t("settings.backup_creating_in_background", "Backup creation is in progress")
                : t("settings.backup_restoring_in_background", "Backup restore is in progress")}
            </strong>
            <span>
              {t(
                "settings.backup_background_help",
                "You can continue navigating in the admin UI. Backup controls will become available again when the Java server finishes the operation."
              )}
            </span>
          </div>
        </aside>
      )}

      <section className="surface backups-surface">
        <div className="backup-list-header">
          <div>
            <p className="settings-intro">{t("settings.backups_intro")}</p>
            <div className="backup-metrics">
              <span>{t("settings.backups_count", { count: sortedBackups.length, defaultValue: "{{count}} backups" })}</span>
              <span>{t("settings.backups_total", { size: formatBytes(totalSize), defaultValue: "{{size}} total" })}</span>
              <span>{t("settings.backups_latest", { value: latestBackup ? formatDate(latestBackup.modified) : t("common.none", "none"), defaultValue: "Latest {{value}}" })}</span>
            </div>
          </div>
          <button className="primary" onClick={() => setCreateOpen(true)} disabled={operationBusy || props.loading}>
            <Archive size={16} />
            {t("actions.initialize_backup", "Initialize new backup")}
          </button>
        </div>

        <div className="backups-list" aria-live="polite">
          {sortedBackups.length === 0 ? (
            <article className="backup-list-item empty">
              <FileArchive size={20} />
              <div>
                <strong>{t("settings.no_backups")}</strong>
                <span>{t("settings.no_backups_desc")}</span>
              </div>
            </article>
          ) : (
            sortedBackups.map((backup) => (
              <article className="backup-list-item" key={backup.key}>
                <FileArchive size={21} />
                <div className="backup-item-content">
                  <strong title={backup.key}>{backup.key}</strong>
                  <span>{formatBytes(backup.size)} · {formatDate(backup.modified)}</span>
                </div>
                <nav className="backup-row-actions" aria-label={t("common.item_actions", { name: backup.key, defaultValue: "{{name}} actions" })}>
                  <button className="icon-button" onClick={() => props.onDownload(backup)} title={t("actions.download", "Download")} aria-label={t("actions.download", "Download")}>
                    <Download size={16} />
                  </button>
                  <button className="icon-button" onClick={() => props.onRestore(backup)} disabled={operationBusy || props.loading} title={t("actions.restore", "Restore")} aria-label={t("actions.restore", "Restore")}>
                    <FileUp size={16} />
                  </button>
                  <button className="icon-button danger" onClick={() => props.onDelete(backup)} disabled={operationBusy || props.loading} title={t("actions.delete", "Delete")} aria-label={t("actions.delete", "Delete")}>
                    <Trash2 size={16} />
                  </button>
                </nav>
              </article>
            ))
          )}
        </div>
      </section>

      <section className="surface backup-options-surface">
        <button className="backup-options-toggle" type="button" onClick={() => setOptionsOpen((value) => !value)}>
          <span>{t("settings.backup_options")}</span>
          <ChevronRight className={optionsOpen ? "expanded" : ""} size={18} />
        </button>
        {optionsOpen && (
          <div className="settings-config-form backup-options-form">
            <Switch
              id="enable-auto-backups"
              name="enableAutoBackups"
              checked={autoBackupsEnabled}
              onChange={(checked) => toggleAutoBackups(checked)}
              label={t("settings.enable_auto_backups", "Enable auto backups")}
            />
            {autoBackupsEnabled && (
              <div className="settings-accordion-card settings-form-block">
                <header>
                  <div>
                    <strong>{t("settings.schedule")}</strong>
                    <span>{t("settings.schedule_utc")}</span>
                  </div>
                  <Clock3 size={18} />
                </header>
                <div className="settings-form-row two">
                  <label>
                    {t("settings.cron_expression", "Cron expression")}
                    <input
                      id="backups-cron"
                      name="backups.cron"
                      className="code-input"
                      type="text"
                      required
                      autoComplete="off"
                      value={String(backupsSettings.cron ?? "")}
                      placeholder="e.g. 0 0 * * *"
                      onChange={(event) => updateSetting(["backups", "cron"], event.target.value)}
                    />
                  </label>
                  <label>
                    {t("settings.max_auto_backups", "Max @auto backups to keep")}
                    <input
                      id="backups-cron-max-keep"
                      name="backups.cronMaxKeep"
                      type="number"
                      min="1"
                      required
                      value={String(backupsSettings.cronMaxKeep ?? 3)}
                      onChange={(event) => updateNumber(["backups", "cronMaxKeep"], event.target.value)}
                    />
                  </label>
                </div>
                <div className="cron-preset-row">
                  {cronPresets.map((preset) => (
                    <button
                      type="button"
                      className={String(backupsSettings.cron ?? "") === preset.cron ? "active" : ""}
                      key={preset.cron}
                      onClick={() => updateSetting(["backups", "cron"], preset.cron)}
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
              </div>
            )}

            <section className="settings-accordion-card settings-form-block">
              <header>
                <div>
                  <strong>{t("settings.backup_s3_storage", "Backup S3 storage")}</strong>
                  <span>{backupS3Enabled ? String(backupS3.bucket ?? t("settings.no_bucket", "no bucket")) : t("settings.local_backup_filesystem", "local backups filesystem")}</span>
                </div>
                <HardDrive size={18} />
              </header>
              <Switch
                id="backups-s3-enabled"
                name="backups.s3.enabled"
                checked={backupS3Enabled}
                onChange={(checked) => updateSetting(["backups", "s3", "enabled"], checked)}
                label={t("settings.store_backups_s3", "Store backups in S3 storage")}
              />
              {backupS3Enabled && (
                <>
                  <div className="settings-form-row three">
                    <label>
                      {t("settings.endpoint", "Endpoint")}
                      <input
                        id="backups-s3-endpoint"
                        name="backups.s3.endpoint"
                        type="text"
                        required
                        autoComplete="off"
                        value={String(backupS3.endpoint ?? "")}
                        onChange={(event) => updateSetting(["backups", "s3", "endpoint"], event.target.value)}
                      />
                    </label>
                    <label>
                      {t("settings.bucket", "Bucket")}
                      <input
                        id="backups-s3-bucket"
                        name="backups.s3.bucket"
                        type="text"
                        required
                        autoComplete="off"
                        value={String(backupS3.bucket ?? "")}
                        onChange={(event) => updateSetting(["backups", "s3", "bucket"], event.target.value)}
                      />
                    </label>
                    <label>
                      {t("settings.region", "Region")}
                      <input
                        id="backups-s3-region"
                        name="backups.s3.region"
                        type="text"
                        required
                        autoComplete="off"
                        value={String(backupS3.region ?? "")}
                        onChange={(event) => updateSetting(["backups", "s3", "region"], event.target.value)}
                      />
                    </label>
                  </div>
                  <div className="settings-form-row two">
                    <label>
                      {t("settings.access_key", "Access key")}
                      <input
                        id="backups-s3-access-key"
                        name="backups.s3.accessKey"
                        type="text"
                        required
                        autoComplete="off"
                        value={String(backupS3.accessKey ?? "")}
                        onChange={(event) => updateSetting(["backups", "s3", "accessKey"], event.target.value)}
                      />
                    </label>
                    <label>
                      {t("settings.secret", "Secret")}
                      <PasswordInput
                        id="backups-s3-secret"
                        name="backups.s3.secret"
                        autoComplete="new-password"
                        value={String(backupS3.secret ?? "")}
                        placeholder={hasBackupS3Secret ? "" : "* * * * * *"}
                        onChange={(event) => updateSetting(["backups", "s3", "secret"], event.target.value)}
                      />
                    </label>
                  </div>
                  <Switch
                    id="backups-s3-force-path-style"
                    name="backups.s3.forcePathStyle"
                    checked={Boolean(backupS3.forcePathStyle)}
                    onChange={(checked) => updateSetting(["backups", "s3", "forcePathStyle"], checked)}
                    label={t("settings.force_path_style", "Force path-style addressing")}
                  />
                </>
              )}
            </section>

            <div className="backup-options-actions">
              <button className="primary" type="button" onClick={() => props.onSave()} disabled={operationBusy || props.loading}>
                <Save size={16} />
                {t("actions.save_changes", "Save changes")}
              </button>
            </div>
          </div>
        )}
      </section>

      {createOpen && (
        <Modal title={t("actions.initialize_backup", "Initialize new backup")} onClose={closeCreateModal}>
          <div className="modal-grid backup-create-modal">
            <div className="settings-alert">
              {t("settings.backup_create_warning", "During backup generation the database can be temporarily locked and concurrent write requests may fail. Files stored in S3 are not included in the generated local ZIP backup.")}
            </div>
            <label>
              {t("settings.backup_name", "Backup name")}
              <input
                id="backup-name"
                name="backupName"
                autoComplete="off"
                pattern="^[a-z0-9_-]+\\.zip$"
                value={props.backupName}
                onChange={(event) => props.onBackupName(event.target.value)}
                placeholder={t("settings.backup_name_placeholder", "Leave empty to autogenerate")}
              />
            </label>
            <p className="settings-help-text">{t("settings.backup_name_help", "Must be in the format [a-z0-9_-].zip")}</p>
            <div className="modal-actions">
              <button type="button" className="subtle" onClick={closeCreateModal} disabled={props.loading}>
                <X size={16} />
                {t("actions.cancel", "Cancel")}
              </button>
              <button type="button" className="primary" onClick={startBackup} disabled={operationBusy || props.loading}>
                <Archive size={16} />
                {t("actions.start_backup", "Start backup")}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}

type CronsViewProps = {
  crons: CronJob[];
  loading: boolean;
  onRefresh: () => void;
  onRun: (job: CronJob) => Promise<void> | void;
};

function CronsView(props: CronsViewProps) {
  const { t } = useTranslation();
  const [runningCronId, setRunningCronId] = useState("");
  // Keep the API's original order rather than re-sorting by id (official parity).
  const sortedCrons = props.crons;

  async function runCron(job: CronJob) {
    if (runningCronId === job.id) return;
    setRunningCronId(job.id);
    try {
      await props.onRun(job);
    } finally {
      setRunningCronId("");
    }
  }

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.crons", "Crons")}
        actions={
          <RefreshButton className="icon-button page-circle" onClick={props.onRefresh} title={t("actions.refresh_crons", "Refresh crons")} />
        }
      />
      <section className="surface crons-surface">
        <div className="cron-list-header">
          <div>
            <p className="settings-intro">{t("settings.crons_intro", "Registered app cron jobs")}</p>
            <span>{t("settings.crons_count", { count: sortedCrons.length, defaultValue: "{{count}} jobs" })}</span>
          </div>
        </div>

        <div className="crons-list" aria-live="polite">
          {sortedCrons.length === 0 ? (
            <article className="cron-list-item empty">
              <Clock3 size={20} />
              <div>
                <strong>{t("settings.no_crons", "No registered crons found.")}</strong>
                <span>{t("settings.no_crons_desc", "App cron jobs are registered by the runtime or enabled settings.")}</span>
              </div>
            </article>
          ) : (
            sortedCrons.map((cron) => (
              <article className="cron-list-item" key={cron.id}>
                <Clock3 size={21} />
                <div className="cron-item-content">
                  <strong title={cron.id}>{cron.id}</strong>
                  <code>{cron.expression}</code>
                </div>
                <nav className="cron-row-actions" aria-label={t("common.item_actions", { name: cron.id, defaultValue: "{{name}} actions" })}>
                  <button
                    className={runningCronId === cron.id ? "icon-button busy" : "icon-button"}
                    onClick={() => runCron(cron)}
                    title={t("actions.run", "Run")}
                    aria-label={t("actions.run", "Run")}
                    disabled={props.loading || runningCronId === cron.id}
                  >
                    <Play size={16} />
                  </button>
                </nav>
              </article>
            ))
          )}
        </div>
      </section>
      <p className="settings-footnote">{t("settings.crons_footnote", "App cron jobs can be registered programmatically; enabled backup schedules are listed here too.")}</p>
    </section>
  );
}

type SettingsViewProps = {
  settings: AppSettings | null;
  draft: string;
  health: HealthResponse["data"] | null;
  loading: boolean;
  collections: CollectionSchema[];
  onDraft: (value: string) => void;
  onRefresh: () => void;
  onSave: () => void;
  onAccentPreview: (color: string | null) => void;
};

type RateLimitRule = {
  label: string;
  maxRequests: number;
  duration: number;
  audience: string;
};

const BASE_RATE_LIMIT_TAGS = [
  "*:list",
  "*:view",
  "*:create",
  "*:update",
  "*:delete",
  "*:file",
  "*:listAuthMethods",
  "*:authRefresh",
  "*:auth",
  "*:authWithPassword",
  "*:authWithOAuth2",
  "*:authWithOTP",
  "*:requestOTP",
  "*:requestPasswordReset",
  "*:confirmPasswordReset",
  "*:requestVerification",
  "*:confirmVerification",
  "*:requestEmailChange",
  "*:confirmEmailChange"
];

/** Per-collection tags, mirroring the official rate limit accordion's suggestions. */
function rateLimitTags(collections: CollectionSchema[]) {
  const tags = [...BASE_RATE_LIMIT_TAGS];
  for (const collection of collections) {
    if (collection.system) continue;
    tags.push(`${collection.name}:list`, `${collection.name}:view`);
    if (collection.type !== "view") {
      tags.push(`${collection.name}:create`, `${collection.name}:update`, `${collection.name}:delete`);
    }
    if (collection.type === "auth") {
      for (const action of [
        "listAuthMethods",
        "authRefresh",
        "auth",
        "authWithPassword",
        "authWithOAuth2",
        "authWithOTP",
        "requestOTP",
        "requestPasswordReset",
        "confirmPasswordReset",
        "requestVerification",
        "confirmVerification",
        "requestEmailChange",
        "confirmEmailChange"
      ]) {
        tags.push(`${collection.name}:${action}`);
      }
    }
  }
  return tags;
}

/**
 * Orders rules the way the server resolves them: tags beat paths, exact beats
 * wildcard, and longer prefixes beat shorter ones. Ported from the official sortRules.
 */
function sortRateLimitRules(rules: RateLimitRule[]) {
  const score = (label: string) => {
    const isTag = label.includes(":") || !label.includes("/");
    if (isTag) return 1000 + (label.startsWith("*") ? 5 : 10);
    let value = 0;
    if (label.includes(" /")) value += 10;
    if (!label.endsWith("/")) value += 5;
    return value;
  };
  return [...rules].sort((a, b) => {
    const diff = score(b.label) - score(a.label);
    if (diff !== 0) return diff;
    // Among same-shaped prefix rules the more specific (longer) one wins.
    if (a.label.endsWith("/") && b.label.endsWith("/")) return b.label.length - a.label.length;
    return 0;
  });
}

function SettingsView(props: SettingsViewProps) {
  const { t } = useTranslation();
  const draftSettings = useMemo(() => parseSettingsDraft(props.draft, props.settings), [props.draft, props.settings]);
  const meta = settingsObject(draftSettings, "meta");
  const batch = settingsObject(draftSettings, "batch");
  const trustedProxy = settingsObject(draftSettings, "trustedProxy");
  const rateLimits = settingsObject(draftSettings, "rateLimits");
  const superuserIPs = Array.isArray(draftSettings.superuserIPs)
    ? draftSettings.superuserIPs.map((item) => String(item)).join(", ")
    : "";
  const rawTrustedHeaders = trustedProxy.headers;
  const trustedHeaders = Array.isArray(rawTrustedHeaders) ? rawTrustedHeaders.map((item) => String(item)).join(", ") : "";
  const rateLimitRules: RateLimitRule[] = Array.isArray(rateLimits.rules)
    ? (rateLimits.rules as unknown[]).map((rule) => {
        const item = isPlainObject(rule) ? rule : {};
        return {
          label: String(item.label ?? ""),
          maxRequests: Number(item.maxRequests ?? 0),
          duration: Number(item.duration ?? 0),
          audience: String(item.audience ?? "")
        };
      })
    : [];
  const excludedIPs = Array.isArray(rateLimits.excludedIPs)
    ? rateLimits.excludedIPs.map((item) => String(item)).join(", ")
    : "";
  const rateLimitTagOptions = useMemo(() => rateLimitTags(props.collections), [props.collections]);
  const currentIp = props.health?.realIP?.trim() ?? "";
  const detectedProxyHeader = props.health?.possibleProxyHeader?.trim() ?? "";
  const draftAccentColor = normalizeAccentColor(meta.accentColor) || "#1055c9";
  const [accentColorError, setAccentColorError] = useState("");
  const [batchOpen, setBatchOpen] = useState(false);
  const [trustedProxyOpen, setTrustedProxyOpen] = useState(false);
  const [rateLimitsOpen, setRateLimitsOpen] = useState(false);
  const [superusersOpen, setSuperusersOpen] = useState(false);

  useEffect(() => {
    props.onAccentPreview(draftAccentColor);
    return () => props.onAccentPreview(null);
  }, [draftAccentColor, props.onAccentPreview]);

  function writeRateLimitRules(rules: RateLimitRule[], enabled?: boolean) {
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, ["rateLimits", "rules"], sortRateLimitRules(rules));
    if (enabled !== undefined) setNestedSetting(next, ["rateLimits", "enabled"], enabled);
    props.onDraft(JSON.stringify(next, null, 2));
  }

  function addRateLimitRule() {
    const rules = [...rateLimitRules, { label: "", maxRequests: 300, duration: 10, audience: "" }];
    // Adding the first rule turns limiting on, matching the official accordion.
    writeRateLimitRules(rules, rateLimitRules.length === 0 ? true : undefined);
  }

  function updateRateLimitRule(index: number, patch: Partial<RateLimitRule>) {
    // Don't re-sort while typing — it would yank the focused row out from under the cursor.
    const rules = rateLimitRules.map((rule, i) => (i === index ? { ...rule, ...patch } : rule));
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, ["rateLimits", "rules"], rules);
    props.onDraft(JSON.stringify(next, null, 2));
  }

  function removeRateLimitRule(index: number) {
    const rules = rateLimitRules.filter((_, i) => i !== index);
    writeRateLimitRules(rules, rules.length === 0 ? false : undefined);
  }

  function updateSetting(path: string[], value: unknown) {
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, path, value);
    props.onDraft(JSON.stringify(next, null, 2));
  }

  function updateNumber(path: string[], value: string) {
    updateSetting(path, value === "" ? 0 : Number(value));
  }

  function updateAccentColor(value: string) {
    const normalized = normalizeAccentColor(value);
    if (!isDarkEnoughForWhiteText(normalized)) {
      setAccentColorError(
        t(
          "settings.accent_color_too_light",
          "Choose a darker accent color so white text remains readable."
        )
      );
      return;
    }
    setAccentColorError("");
    updateSetting(["meta", "accentColor"], normalized);
  }

  const dirty = Boolean(props.draft) && props.draft !== JSON.stringify(props.settings ?? {}, null, 2);

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.application", "Application")}
      />

      <section className="surface application-settings-form">
        <div className="settings-form-row primary-fields">
          <label>
            {t("settings.application_name", "Application name")}
            <input
              id="meta-app-name"
              name="meta.appName"
              autoComplete="off"
              value={String(meta.appName ?? "")}
              onChange={(event) => updateSetting(["meta", "appName"], event.target.value)}
            />
          </label>
          <label>
            {t("settings.application_url", "Application URL")}
            <input
              id="meta-app-url"
              name="meta.appURL"
              autoComplete="off"
              value={String(meta.appURL ?? "")}
              onChange={(event) => updateSetting(["meta", "appURL"], event.target.value)}
            />
          </label>
          <AccentColorPicker
            value={draftAccentColor}
            onChange={updateAccentColor}
            error={accentColorError}
          />
        </div>

        <section className="settings-accordion-grid">
          {/* 1. 批量 Web API */}
          <article className={`settings-accordion-card ${batchOpen ? "is-open" : "is-collapsed"}`}>
            <header
              className="settings-accordion-header"
              onClick={() => setBatchOpen((prev) => !prev)}
              role="button"
              tabIndex={0}
              aria-expanded={batchOpen}
            >
              <div className="accordion-header-left">
                <Archive size={18} />
                <strong>{t("settings.batch_requests", "Batch requests")}</strong>
              </div>
              <div className="accordion-header-right">
                <span className={`bool ${batch.enabled ? "yes" : "no"}`}>
                  {batch.enabled ? t("common.enabled_status", "Enabled") : t("common.disabled_status", "Disabled")}
                </span>
                {batchOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
              </div>
            </header>
            {batchOpen && (
              <div className="settings-accordion-content">
                <Switch
                  id="batch-enabled"
                  name="batch.enabled"
                  checked={Boolean(batch.enabled)}
                  onChange={(checked) => updateSetting(["batch", "enabled"], checked)}
                  label={t("settings.enable_batch_api", "Enable batch API")}
                />
                <div className="settings-form-row three">
                  <label>
                    {t("settings.max_requests", "Max requests")}
                    <input
                      id="batch-max-requests"
                      name="batch.maxRequests"
                      type="number"
                      value={String(batch.maxRequests ?? 50)}
                      onChange={(event) => updateNumber(["batch", "maxRequests"], event.target.value)}
                      disabled={!Boolean(batch.enabled)}
                    />
                  </label>
                  <label>
                    {t("settings.timeout", "Timeout")}
                    <input
                      id="batch-timeout"
                      name="batch.timeout"
                      type="number"
                      value={String(batch.timeout ?? 3)}
                      onChange={(event) => updateNumber(["batch", "timeout"], event.target.value)}
                      disabled={!Boolean(batch.enabled)}
                    />
                  </label>
                  <label>
                    {t("settings.max_body_size", "Max body size")}
                    <input
                      id="batch-max-body-size"
                      name="batch.maxBodySize"
                      type="number"
                      value={String(batch.maxBodySize ?? 33554432)}
                      onChange={(event) => updateNumber(["batch", "maxBodySize"], event.target.value)}
                      disabled={!Boolean(batch.enabled)}
                    />
                  </label>
                </div>
              </div>
            )}
          </article>

          {/* 2. IP 代理头信息 */}
          <article className={`settings-accordion-card ${trustedProxyOpen ? "is-open" : "is-collapsed"}`}>
            <header
              className="settings-accordion-header"
              onClick={() => setTrustedProxyOpen((prev) => !prev)}
              role="button"
              tabIndex={0}
              aria-expanded={trustedProxyOpen}
            >
              <div className="accordion-header-left">
                <Server size={18} />
                <strong>{t("settings.trusted_proxy", "Trusted proxy")}</strong>
              </div>
              <div className="accordion-header-right">
                <span className={`bool ${splitCsv(trustedHeaders).length > 0 ? "yes" : "no"}`}>
                  {splitCsv(trustedHeaders).length > 0 ? t("common.enabled_status", "Enabled") : t("common.disabled_status", "Disabled")}
                </span>
                {trustedProxyOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
              </div>
            </header>
            {trustedProxyOpen && (
              <div className="settings-accordion-content">
                <label>
                  {t("settings.trusted_headers", "Trusted headers")}
                  <input
                    id="trusted-proxy-headers"
                    name="trustedProxy.headers"
                    autoComplete="off"
                    placeholder="X-Forwarded-For, X-Real-IP"
                    value={trustedHeaders}
                    onChange={(event) => updateSetting(["trustedProxy", "headers"], splitCsv(event.target.value))}
                  />
                </label>
                <Switch
                  id="trusted-proxy-leftmost"
                  name="trustedProxy.useLeftmostIP"
                  checked={Boolean(trustedProxy.useLeftmostIP)}
                  onChange={(checked) => updateSetting(["trustedProxy", "useLeftmostIP"], checked)}
                  label={t("settings.use_leftmost_ip", "Use leftmost IP")}
                />
                <div className="settings-diagnostic">
                  <span>{t("settings.resolved_ip", "Resolved client IP")}</span>
                  <code>{currentIp || t("settings.unavailable", "Unavailable")}</code>
                  {detectedProxyHeader ? (
                    <>
                      <span>{t("settings.detected_proxy_header", "Detected proxy header")}</span>
                      <code>{detectedProxyHeader}</code>
                      {!splitCsv(trustedHeaders).some((header) => header.toLowerCase() === detectedProxyHeader.toLowerCase()) && (
                        <button
                          type="button"
                          className="subtle compact"
                          onClick={() => updateSetting(["trustedProxy", "headers"], [...splitCsv(trustedHeaders), detectedProxyHeader])}
                        >
                          {t("settings.use_detected_header", "Use detected header")}
                        </button>
                      )}
                    </>
                  ) : (
                    <em>{t("settings.trusted_proxy_no_header", "No forwarded IP header detected for this request.")}</em>
                  )}
                </div>
              </div>
            )}
          </article>

          {/* 3. 速率限制 */}
          <article className={`settings-accordion-card rate-limit-card ${rateLimitsOpen ? "is-open" : "is-collapsed"}`}>
            <header
              className="settings-accordion-header"
              onClick={() => setRateLimitsOpen((prev) => !prev)}
              role="button"
              tabIndex={0}
              aria-expanded={rateLimitsOpen}
            >
              <div className="accordion-header-left">
                <Activity size={18} />
                <strong>{t("settings.rate_limiting", "Rate limiting")}</strong>
              </div>
              <div className="accordion-header-right">
                <span className={`bool ${rateLimits.enabled ? "yes" : "no"}`}>
                  {rateLimits.enabled ? t("common.enabled_status", "Enabled") : t("common.disabled_status", "Disabled")}
                </span>
                {rateLimitsOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
              </div>
            </header>
            {rateLimitsOpen && (
              <div className="settings-accordion-content">
                <Switch
                  id="rate-limits-enabled"
                  name="rateLimits.enabled"
                  checked={Boolean(rateLimits.enabled)}
                  onChange={(checked) => updateSetting(["rateLimits", "enabled"], checked)}
                  label={t("settings.enable_rate_limiting", "Enable rate limiting")}
                />

                <datalist id="rate-limit-tags">
                  {rateLimitTagOptions.map((tag) => (
                    <option key={tag} value={tag} />
                  ))}
                </datalist>

                {rateLimitRules.length === 0 ? (
                  <p className="rate-limit-empty">{t("settings.no_rate_limit_rules", "No rules defined yet.")}</p>
                ) : (
                  <div className="rate-limit-table">
                    <div className="rate-limit-head">
                      <span>{t("settings.rule_label", "Label")}</span>
                      <span>{t("settings.max_requests", "Max requests")}</span>
                      <span>{t("settings.interval_seconds", "Interval (s)")}</span>
                      <span>{t("settings.audience", "Audience")}</span>
                      <span />
                    </div>
                    {rateLimitRules.map((rule, index) => (
                      <div className="rate-limit-row" key={index}>
                        <input
                          type="text"
                          list="rate-limit-tags"
                          autoComplete="off"
                          spellCheck={false}
                          placeholder="*:list or /api/path"
                          value={rule.label}
                          onChange={(event) => updateRateLimitRule(index, { label: event.target.value })}
                        />
                        <input
                          type="number"
                          min="0"
                          value={String(rule.maxRequests ?? 0)}
                          onChange={(event) => updateRateLimitRule(index, { maxRequests: Number(event.target.value || 0) })}
                        />
                        <input
                          type="number"
                          min="0"
                          value={String(rule.duration ?? 0)}
                          onChange={(event) => updateRateLimitRule(index, { duration: Number(event.target.value || 0) })}
                        />
                        <select
                          value={rule.audience ?? ""}
                          onChange={(event) => updateRateLimitRule(index, { audience: event.target.value })}
                        >
                          <option value="">{t("settings.audience_all", "All")}</option>
                          <option value="@guest">{t("settings.audience_guest", "Guest only")}</option>
                          <option value="@auth">{t("settings.audience_auth", "Auth only")}</option>
                        </select>
                        <button
                          type="button"
                          className="icon-button danger"
                          onClick={() => removeRateLimitRule(index)}
                          title={t("settings.remove_rule", "Remove rule")}
                          aria-label={t("settings.remove_rule", "Remove rule")}
                        >
                          <Trash2 size={15} />
                        </button>
                      </div>
                    ))}
                  </div>
                )}

                <button type="button" className="subtle rate-limit-add" onClick={addRateLimitRule}>
                  <Plus size={14} />
                  {t("settings.add_rule", "Add rule")}
                </button>

                <label>
                  {t("settings.excluded_ips_label", "Excluded IPs")}
                  <input
                    id="rate-limit-excluded-ips"
                    name="rateLimits.excludedIPs"
                    autoComplete="off"
                    placeholder="127.0.0.1, 10.0.0.0/8"
                    value={excludedIPs}
                    onChange={(event) => updateSetting(["rateLimits", "excludedIPs"], splitCsv(event.target.value))}
                  />
                </label>
              </div>
            )}
          </article>

          {/* 4. 超级用户 IP 地址 */}
          <article className={`settings-accordion-card ${superusersOpen ? "is-open" : "is-collapsed"}`}>
            <header
              className="settings-accordion-header"
              onClick={() => setSuperusersOpen((prev) => !prev)}
              role="button"
              tabIndex={0}
              aria-expanded={superusersOpen}
            >
              <div className="accordion-header-left">
                <Shield size={18} />
                <strong>{t("settings.superusers", "Superusers")}</strong>
              </div>
              <div className="accordion-header-right">
                <span className={`bool ${splitCsv(superuserIPs).length > 0 ? "yes" : "no"}`}>
                  {splitCsv(superuserIPs).length > 0 ? t("common.enabled_status", "Enabled") : t("common.disabled_status", "Disabled")}
                </span>
                {superusersOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
              </div>
            </header>
            {superusersOpen && (
              <div className="settings-accordion-content">
                <label>
                  {t("settings.allowed_ips", "Allowed IPs")}
                  <input
                    id="superuser-ips"
                    name="superuserIPs"
                    autoComplete="off"
                    placeholder="127.0.0.1, 10.0.0.0/8"
                    value={superuserIPs}
                    onChange={(event) => updateSetting(["superuserIPs"], splitCsv(event.target.value))}
                  />
                </label>
                {currentIp && (
                  <div className="settings-current-ip">
                    <span>{t("settings.current_ip", { ip: currentIp, defaultValue: "Your current IP: {{ip}}" })}</span>
                    <button
                      type="button"
                      className="subtle compact"
                      onClick={() => {
                        const next = splitCsv(superuserIPs);
                        if (!next.some((value) => value === currentIp)) next.push(currentIp);
                        updateSetting(["superuserIPs"], next);
                      }}
                    >
                      {t("settings.add_current_ip", "Add current IP")}
                    </button>
                  </div>
                )}
              </div>
            )}
          </article>
        </section>

        <div className="settings-switch-row settings-hide-controls-row">
          <Switch
            id="meta-hide-controls"
            name="meta.hideControls"
            checked={Boolean(meta.hideControls)}
            onChange={(checked) => updateSetting(["meta", "hideControls"], checked)}
            label={
              <span className="hide-controls-label-wrap">
                {t("settings.hide_controls", "Hide/Lock collection and record controls")}
                <span
                  className="hide-controls-info-icon"
                  data-tooltip={t(
                    "settings.hide_controls_help",
                    "To prevent accidental changes when in production environment, collections create and update buttons will be hidden.\nRecords update will also require an extra unlock step before save."
                  )}
                  aria-label={t(
                    "settings.hide_controls_help",
                    "To prevent accidental changes when in production environment, collections create and update buttons will be hidden.\nRecords update will also require an extra unlock step before save."
                  )}
                >
                  <Info size={15} />
                </span>
              </span>
            }
          />
        </div>

        <footer className="application-settings-footer">
          <button className="primary" onClick={() => props.onSave()} disabled={props.loading || !dirty}>
            <Save size={16} />
            {t("actions.save_settings", "Save settings")}
          </button>
        </footer>
      </section>
    </section>
  );
}

type LogSettingsModalProps = {
  settings: AppSettings | null;
  draft: string;
  loading: boolean;
  onDraft: (value: string) => void;
  onSave: (draft?: string) => Promise<boolean>;
  onClearLogs: () => Promise<void>;
  onClose: () => void;
};

/**
 * PocketBase keeps log retention and request metadata settings beside the log
 * stream, not in the broader application settings page. Keep an isolated draft
 * here so cancelling the dialog cannot overwrite edits the user already made
 * in another settings section.
 *
 * Layout matches official "Logs settings": stacked filled fields, helper text,
 * pill toggles, Close left / Save changes right.
 */
function LogSettingsModal(props: LogSettingsModalProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(props.draft);
  const [saving, setSaving] = useState(false);
  const draftSettings = useMemo(() => parseSettingsDraft(draft, props.settings), [draft, props.settings]);
  const logs = settingsObject(draftSettings, "logs");
  const disabled = props.loading || saving;
  const dirty = draft !== props.draft;
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction(() => {
    if (!saving) props.onClose();
  });

  useEffect(() => {
    if (!saving) setDraft(props.draft);
  }, [props.draft, saving]);

  function closeUnlessSaving() {
    if (!saving) props.onClose();
  }

  function updateSetting(key: string, value: unknown) {
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, ["logs", key], value);
    setDraft(JSON.stringify(next, null, 2));
  }

  async function save() {
    if (disabled || !dirty) return;
    setSaving(true);
    props.onDraft(draft);
    const saved = await props.onSave(draft);
    setSaving(false);
    if (saved) props.onClose();
  }

  const levelHints = [
    { value: "-4", label: "DEBUG" },
    { value: "0", label: "INFO" },
    { value: "4", label: "WARN" },
    { value: "8", label: "ERROR" }
  ] as const;

  return (
    <div
      className="modal-backdrop logs-settings-backdrop"
      role="presentation"
      onMouseDown={onBackdropMouseDown}
      onMouseUp={onBackdropMouseUp}
    >
      <section
        ref={dialogRef}
        className="logs-settings-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t("settings.logs_settings_title", "Logs settings")}
        tabIndex={-1}
      >
        <header className="logs-settings-head">
          <h2>{t("settings.logs_settings_title", "Logs settings")}</h2>
        </header>

        <div className="logs-settings-body">
          <label className="logs-settings-field">
            <span className="logs-settings-label">
              {t("settings.max_days_retention", "Max days retention")}
              <em className="logs-settings-required" aria-hidden="true">
                *
              </em>
            </span>
            <input
              id="logs-max-days"
              name="logs.maxDays"
              type="number"
              min={0}
              value={String(logs.maxDays ?? 5)}
              onChange={(event) =>
                updateSetting("maxDays", Math.max(0, Number(event.target.value || 0)))
              }
              disabled={disabled}
            />
            <span className="logs-settings-help">
              {t("settings.max_days_retention_help", "Set to 0 to disable logs persistence.")}
            </span>
          </label>

          <label className="logs-settings-field">
            <span className="logs-settings-label">
              {t("settings.max_data_size", "Max log data size (bytes)")}
              <em className="logs-settings-required" aria-hidden="true">
                *
              </em>
            </span>
            <input
              id="logs-max-data-size"
              name="logs.maxDataSize"
              type="number"
              min={0}
              value={String(logs.maxDataSize ?? 0)}
              onChange={(event) =>
                updateSetting("maxDataSize", Math.max(0, Number(event.target.value || 0)))
              }
              disabled={disabled}
            />
            <span className="logs-settings-help">
              {t("settings.max_data_size_help", "Set to 0 to use the default 16KB (16384 bytes) per log entry data limit.")}
            </span>
          </label>

          <label className="logs-settings-field">
            <span className="logs-settings-label">
              {t("settings.min_log_level", "Min log level")}
              <em className="logs-settings-required" aria-hidden="true">
                *
              </em>
            </span>
            <input
              id="logs-min-level"
              name="logs.minLevel"
              type="number"
              min={-100}
              max={100}
              value={String(logs.minLevel ?? 0)}
              onChange={(event) =>
                updateSetting("minLevel", Math.max(-100, Math.min(100, Number(event.target.value || 0))))
              }
              disabled={disabled}
            />
            <span className="logs-settings-help">
              {t(
                "settings.min_log_level_help",
                "Logs with level below the minimum will be ignored."
              )}{" "}
              {t("settings.default_log_levels", "Default log levels:")}{" "}
              {levelHints.map((hint) => (
                <span key={hint.value} className={`logs-level-chip logs-level-chip-${hint.label.toLowerCase()}`}>
                  {hint.value}:{hint.label}
                </span>
              ))}
            </span>
          </label>

          <label className={`logs-settings-toggle${disabled ? " is-disabled" : ""}`}>
            <input
              id="logs-log-ip"
              name="logs.logIP"
              type="checkbox"
              checked={Boolean(logs.logIP)}
              onChange={(event) => updateSetting("logIP", event.target.checked)}
              disabled={disabled}
            />
            <span className="logs-settings-toggle-track" aria-hidden="true" />
            <span>{t("settings.enable_ip_logging", "Enable IP logging")}</span>
          </label>

          <label className={`logs-settings-toggle${disabled ? " is-disabled" : ""}`}>
            <input
              id="logs-log-auth-id"
              name="logs.logAuthId"
              type="checkbox"
              checked={Boolean(logs.logAuthId)}
              onChange={(event) => updateSetting("logAuthId", event.target.checked)}
              disabled={disabled}
            />
            <span className="logs-settings-toggle-track" aria-hidden="true" />
            <span>{t("settings.enable_auth_id_logging", "Enable Auth Id logging")}</span>
          </label>
        </div>

        <footer className="logs-settings-foot">
          <button
            type="button"
            className="logs-settings-btn-clear"
            onClick={props.onClearLogs}
            disabled={disabled}
          >
            {t("settings.delete_all_logs", "Delete all logs")}
          </button>
          <div className="logs-settings-foot-actions">
            <button type="button" className="logs-settings-btn-close" onClick={closeUnlessSaving} disabled={saving}>
              {t("actions.close", "Close")}
            </button>
            <button
              type="button"
              className="logs-settings-btn-save"
              onClick={() => void save()}
              disabled={disabled || !dirty}
            >
              {saving ? t("common.submitting", "Submitting...") : t("actions.save_changes", "Save changes")}
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}

type SettingValueCardProps = {
  title: string;
  value: string;
  detail: string;
};

function SettingValueCard(props: SettingValueCardProps) {
  const { t } = useTranslation();
  return (
    <article className="setting-card">
      <span>{props.title}</span>
      <strong title={props.value}>{props.value || t("common.not_set", "not set")}</strong>
      <code title={props.detail}>{props.detail || t("common.not_set", "not set")}</code>
    </article>
  );
}

type MailSettingsViewProps = {
  settings: AppSettings | null;
  draft: string;
  email: string;
  template: string;
  collection: string;
  collections: CollectionSchema[];
  loading: boolean;
  onDraft: (value: string) => void;
  onSave: () => void;
  onEmail: (value: string) => void;
  onTemplate: (value: string) => void;
  onCollection: (value: string) => void;
  onTest: () => void;
};

function MailSettingsView(props: MailSettingsViewProps) {
  const { t } = useTranslation();
  const [showMoreOptions, setShowMoreOptions] = useState(false);
  const draftSettings = useMemo(() => parseSettingsDraft(props.draft, props.settings), [props.draft, props.settings]);
  const meta = settingsObject(draftSettings, "meta");
  const smtp = settingsObject(draftSettings, "smtp");
  const hasSmtpPassword = Object.prototype.hasOwnProperty.call(smtp, "password");

  function updateSetting(path: string[], value: unknown) {
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, path, value);
    props.onDraft(JSON.stringify(next, null, 2));
  }

  function updateOptionalNumber(path: string[], value: string) {
    updateSetting(path, value === "" ? undefined : Number(value));
  }

  const dirty = Boolean(props.draft) && props.draft !== JSON.stringify(props.settings ?? {}, null, 2);

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.mail", "Mail settings")}
        actions={
          <button className="primary" onClick={() => props.onSave()} disabled={props.loading || !dirty}>
            <Save size={16} />
            {t("actions.save_settings", "Save settings")}
          </button>
        }
      />
      <section className="surface settings-config-form mail-settings-form">
        <p className="settings-intro">{t("settings.mail_intro", "Configure common settings for sending emails.")}</p>
        <div className="settings-form-row two">
          <label>
            {t("settings.sender_name", "Sender name")}
            <input
              id="meta-sender-name"
              name="meta.senderName"
              type="text"
              required
              autoComplete="off"
              value={String(meta.senderName ?? "")}
              onChange={(event) => updateSetting(["meta", "senderName"], event.target.value)}
            />
          </label>
          <label>
            {t("settings.sender_address", "Sender address")}
            <input
              id="meta-sender-address"
              name="meta.senderAddress"
              type="email"
              required
              autoComplete="off"
              value={String(meta.senderAddress ?? "")}
              onChange={(event) => updateSetting(["meta", "senderAddress"], event.target.value)}
            />
          </label>
        </div>

        <div className="settings-switch-row">
          <Switch
            id="smtp-enabled"
            name="smtp.enabled"
            checked={Boolean(smtp.enabled)}
            onChange={(checked) => updateSetting(["smtp", "enabled"], checked)}
            label={t("settings.use_smtp", "Use SMTP mail server")}
          />
          <p className="settings-help-text">
            {t("settings.smtp_help", "By default PocketBase uses the server sendmail command. SMTP is recommended for better deliverability.")}
          </p>
        </div>

        {Boolean(smtp.enabled) && (
          <section className="settings-accordion-card settings-form-block">
            <header>
              <div>
                <strong>SMTP</strong>
                <span>{`${String(smtp.host ?? t("settings.no_host", "no host"))}:${String(smtp.port ?? "")}`}</span>
              </div>
              <Server size={18} />
            </header>
            <div className="settings-form-row three">
              <label>
                {t("settings.smtp_host", "SMTP server host")}
                <input
                  id="smtp-host"
                  name="smtp.host"
                  type="text"
                  required
                  autoComplete="off"
                  value={String(smtp.host ?? "")}
                  onChange={(event) => updateSetting(["smtp", "host"], event.target.value)}
                />
              </label>
              <label>
                {t("settings.port", "Port")}
                <input
                  id="smtp-port"
                  name="smtp.port"
                  type="number"
                  min="0"
                  step="1"
                  required
                  value={String(smtp.port ?? "")}
                  onChange={(event) => updateOptionalNumber(["smtp", "port"], event.target.value)}
                />
              </label>
              <label>
                {t("settings.username", "Username")}
                <input
                  id="smtp-username"
                  name="smtp.username"
                  type="text"
                  autoComplete="off"
                  value={String(smtp.username ?? "")}
                  onChange={(event) => updateSetting(["smtp", "username"], event.target.value)}
                />
              </label>
            </div>
            <div className="settings-form-row two">
              <label>
                {t("auth.password", "Password")}
                <PasswordInput
                  id="smtp-password"
                  name="smtp.password"
                  autoComplete="new-password"
                  value={String(smtp.password ?? "")}
                  placeholder={hasSmtpPassword ? "" : "* * * * * *"}
                  onChange={(event) => updateSetting(["smtp", "password"], event.target.value)}
                />
              </label>
              <div className="settings-inline-actions">
                <button type="button" className="subtle" onClick={() => setShowMoreOptions((value) => !value)}>
                  {showMoreOptions ? t("actions.hide_more_options", "Hide more options") : t("actions.show_more_options", "Show more options")}
                </button>
              </div>
            </div>
            {showMoreOptions && (
              <div className="settings-form-row three">
                <label>
                  {t("settings.tls_encryption", "TLS encryption")}
                  <select
                    id="smtp-tls"
                    name="smtp.tls"
                    value={String(Boolean(smtp.tls))}
                    onChange={(event) => updateSetting(["smtp", "tls"], event.target.value === "true")}
                  >
                    <option value="false">{t("settings.auto_starttls", "Auto (StartTLS)")}</option>
                    <option value="true">{t("settings.always", "Always")}</option>
                  </select>
                </label>
                <label>
                  {t("settings.auth_method", "AUTH method")}
                  <select
                    id="smtp-auth-method"
                    name="smtp.authMethod"
                    value={String(smtp.authMethod ?? "PLAIN")}
                    onChange={(event) => updateSetting(["smtp", "authMethod"], event.target.value)}
                  >
                    <option value="PLAIN">PLAIN (default)</option>
                    <option value="LOGIN">LOGIN</option>
                  </select>
                </label>
                <label>
                  {t("settings.ehlo_domain", "EHLO/HELO domain")}
                  <input
                    id="smtp-local-name"
                    name="smtp.localName"
                    type="text"
                    autoComplete="off"
                    placeholder={t("settings.default_localhost", "Default to localhost")}
                    value={String(smtp.localName ?? "")}
                    onChange={(event) => updateSetting(["smtp", "localName"], event.target.value)}
                  />
                </label>
              </div>
            )}
          </section>
        )}
      </section>

      <section className="surface settings-section">
        <div className="section-heading">
          <div>
            <h2>{t("settings.test_email", "Test email")}</h2>
            <p>{t("settings.test_email_desc", "Send a test auth email with the current mail configuration.")}</p>
          </div>
          <Mail size={18} />
        </div>
        <div className="settings-form-grid">
          <label>
            {t("settings.recipient", "Recipient")}
            <input
              id="test-email-recipient"
              name="testEmailRecipient"
              type="email"
              autoComplete="off"
              value={props.email}
              onChange={(event) => props.onEmail(event.target.value)}
              placeholder="admin@example.com"
            />
          </label>
          <label>
            {t("settings.template", "Template")}
            <select
              id="test-email-template"
              name="testEmailTemplate"
              value={props.template}
              onChange={(event) => props.onTemplate(event.target.value)}
            >
              <option value="verification">verification</option>
              <option value="password-reset">password-reset</option>
              <option value="email-change">email-change</option>
              <option value="otp">otp</option>
              <option value="login-alert">login-alert</option>
            </select>
          </label>
          <label>
            {t("settings.collection", "Auth collection")}
            <select
              value={props.collection}
              onChange={(event) => props.onCollection(event.target.value)}
            >
              <option value="_superusers">_superusers</option>
              {props.collections
                .filter((collection) => collection.type === "auth" && collection.name !== "_superusers")
                .map((collection) => (
                  <option key={collection.id || collection.name} value={collection.name}>
                    {collection.name}
                  </option>
                ))}
            </select>
          </label>
          <button className="primary apply-button" onClick={props.onTest} disabled={props.loading || !props.email.trim() || dirty}>
            <Play size={16} />
            {t("actions.send_test", "Send test")}
          </button>
        </div>
      </section>
    </section>
  );
}

type StorageSettingsViewProps = {
  settings: AppSettings | null;
  draft: string;
  target: string;
  testState: S3TestState;
  loading: boolean;
  onDraft: (value: string) => void;
  onSave: () => void;
  onTarget: (value: string) => void;
  onTest: (automatic?: boolean) => void;
};

function StorageSettingsView(props: StorageSettingsViewProps) {
  const { t } = useTranslation();
  const draftSettings = useMemo(() => parseSettingsDraft(props.draft, props.settings), [props.draft, props.settings]);
  const storage = settingsObject(draftSettings, "s3");
  const originalStorage = settingsObject(props.settings, "s3");
  const storageEnabled = Boolean(storage.enabled);
  const backupStorage = settingsObject(settingsObject(draftSettings, "backups"), "s3");
  const targetStorage = props.target === "backups" ? backupStorage : storage;
  const canAutomaticallyTest = Boolean(
    targetStorage.enabled &&
      String(targetStorage.endpoint ?? "").trim() &&
      String(targetStorage.bucket ?? "").trim() &&
      String(targetStorage.region ?? "").trim() &&
      String(targetStorage.accessKey ?? "").trim()
  );
  const hasS3Secret = Object.prototype.hasOwnProperty.call(storage, "secret");
  const changedStorageMode = Boolean(originalStorage.enabled) !== storageEnabled;
  const onTestRef = useRef(props.onTest);
  const lastAutomaticTestFingerprint = useRef<string | null>(null);
  const automaticTestFingerprint = JSON.stringify({
    target: props.target,
    enabled: Boolean(targetStorage.enabled),
    endpoint: String(targetStorage.endpoint ?? "").trim(),
    bucket: String(targetStorage.bucket ?? "").trim(),
    region: String(targetStorage.region ?? "").trim(),
    accessKey: String(targetStorage.accessKey ?? "").trim(),
    secret: String(targetStorage.secret ?? ""),
    forcePathStyle: Boolean(targetStorage.forcePathStyle)
  });

  useEffect(() => {
    onTestRef.current = props.onTest;
  }, [props.onTest]);

  function updateSetting(path: string[], value: unknown) {
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, path, value);
    props.onDraft(JSON.stringify(next, null, 2));
  }

  useEffect(() => {
    if (lastAutomaticTestFingerprint.current === null) {
      lastAutomaticTestFingerprint.current = automaticTestFingerprint;
      return;
    }
    if (lastAutomaticTestFingerprint.current === automaticTestFingerprint) return;
    lastAutomaticTestFingerprint.current = automaticTestFingerprint;
    if (!canAutomaticallyTest) return;
    const timer = window.setTimeout(() => onTestRef.current(true), 650);
    return () => window.clearTimeout(timer);
  }, [automaticTestFingerprint, canAutomaticallyTest]);

  const dirty = Boolean(props.draft) && props.draft !== JSON.stringify(props.settings ?? {}, null, 2);

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.storage", "File storage")}
        actions={
          <button className="primary" onClick={() => props.onSave()} disabled={props.loading || !dirty}>
            <Save size={16} />
            {t("actions.save_settings", "Save settings")}
          </button>
        }
      />
      <section className="surface settings-config-form storage-settings-form">
        <div className="settings-intro">
          <p>{t("settings.storage_intro_local", "By default PocketBase uses and recommends the local file system to store uploaded files.")}</p>
          <p>{t("settings.storage_intro_s3", "Alternatively, you can use an S3 compatible external storage when disk space is limited.")}</p>
        </div>
        <Switch
          id="s3-enabled"
          name="s3.enabled"
          checked={storageEnabled}
          onChange={(checked) => updateSetting(["s3", "enabled"], checked)}
          label={t("settings.use_s3_storage", "Use S3 storage")}
        />
        {changedStorageMode && (
          <div className="settings-alert">
            {t("settings.storage_migration_warning", "Existing uploaded files need to be migrated manually between the local file system and S3 storage.")}
          </div>
        )}
        {storageEnabled && (
          <section className="settings-accordion-card settings-form-block">
            <header>
              <div>
                <strong>{t("settings.s3_configuration", "S3 configuration")}</strong>
                <span>{String(storage.bucket ?? t("settings.no_bucket", "no bucket"))}</span>
              </div>
              <HardDrive size={18} />
            </header>
            <div className="settings-form-row three">
              <label>
                {t("settings.endpoint", "Endpoint")}
                <input
                  id="s3-endpoint"
                  name="s3.endpoint"
                  type="text"
                  required
                  autoComplete="off"
                  value={String(storage.endpoint ?? "")}
                  onChange={(event) => updateSetting(["s3", "endpoint"], event.target.value)}
                />
              </label>
              <label>
                {t("settings.bucket", "Bucket")}
                <input
                  id="s3-bucket"
                  name="s3.bucket"
                  type="text"
                  required
                  autoComplete="off"
                  value={String(storage.bucket ?? "")}
                  onChange={(event) => updateSetting(["s3", "bucket"], event.target.value)}
                />
              </label>
              <label>
                {t("settings.region", "Region")}
                <input
                  id="s3-region"
                  name="s3.region"
                  type="text"
                  required
                  autoComplete="off"
                  value={String(storage.region ?? "")}
                  onChange={(event) => updateSetting(["s3", "region"], event.target.value)}
                />
              </label>
            </div>
            <div className="settings-form-row two">
              <label>
                {t("settings.access_key", "Access key")}
                <input
                  id="s3-access-key"
                  name="s3.accessKey"
                  type="text"
                  required
                  autoComplete="off"
                  value={String(storage.accessKey ?? "")}
                  onChange={(event) => updateSetting(["s3", "accessKey"], event.target.value)}
                />
              </label>
              <label>
                {t("settings.secret", "Secret")}
                <PasswordInput
                  id="s3-secret"
                  name="s3.secret"
                  autoComplete="new-password"
                  value={String(storage.secret ?? "")}
                  placeholder={hasS3Secret ? "" : "* * * * * *"}
                  onChange={(event) => updateSetting(["s3", "secret"], event.target.value)}
                />
              </label>
            </div>
            <Switch
              id="s3-force-path-style"
              name="s3.forcePathStyle"
              checked={Boolean(storage.forcePathStyle)}
              onChange={(checked) => updateSetting(["s3", "forcePathStyle"], checked)}
              label={t("settings.force_path_style", "Force path-style addressing")}
            />
          </section>
        )}
      </section>
      <section className="surface settings-section">
        <div className="section-heading">
          <div>
            <h2>{t("settings.s3_connection", "S3 connection")}</h2>
            <p>{t("settings.s3_connection_desc", "Check the configured storage or backups filesystem target.")}</p>
          </div>
          <Server size={18} />
        </div>
        <div className="settings-form-grid compact">
          <label>
            {t("settings.target", "Target")}
            <select id="s3-test-target" name="s3TestTarget" value={props.target} onChange={(event) => props.onTarget(event.target.value)}>
              <option value="storage">storage</option>
              <option value="backups">backups</option>
            </select>
          </label>
          <button className="primary apply-button" onClick={() => props.onTest()} disabled={props.loading}>
            <Play size={16} />
            {t("actions.test_s3", "Test S3")}
          </button>
        </div>
        <div className={`s3-test-state ${props.testState.status}`} role="status" aria-live="polite">
          {props.testState.status === "testing"
            ? t("settings.s3_test_pending", "Testing S3 connection…")
            : props.testState.status === "success"
              ? props.testState.message
              : props.testState.status === "error"
                ? `${t("settings.s3_test_failed", "S3 connection failed")}: ${props.testState.message}`
                : canAutomaticallyTest
                  ? t("settings.s3_test_waiting", "Connection test will run after changes settle.")
                  : t("settings.s3_test_incomplete", "Enter the S3 endpoint, bucket, region and access key to test automatically.")}
        </div>
      </section>
    </section>
  );
}

type CollectionTransferViewProps = {
  mode: "export" | "import";
  collections: CollectionSchema[];
  draft: string;
  deleteMissing: boolean;
  loading: boolean;
  onDraft: (value: string) => void;
  onDeleteMissing: (value: boolean) => void;
  onExport: () => void;
  onImport: () => Promise<boolean> | boolean;
  onCopy: (value: string) => void;
};

function CollectionTransferView(props: CollectionTransferViewProps) {
  const { t } = useTranslation();
  const importing = props.mode === "import";
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [selectedExportIds, setSelectedExportIds] = useState<string[]>([]);
  const [reviewOpen, setReviewOpen] = useState(false);
  const exportCollections = useMemo(() => sortedCollectionsForTransfer(props.collections), [props.collections]);
  const selectedExportCollections = useMemo(
    () => exportCollections.filter((collection) => selectedExportIds.includes(collection.id)),
    [exportCollections, selectedExportIds]
  );
  const importedCollections = useMemo(() => parseCollectionsPayload(props.draft), [props.draft]);
  const collectionIdReplacements = useMemo(
    () => collectionIdReplacementSuggestions(props.collections, importedCollections),
    [props.collections, importedCollections]
  );
  const importChanges = useMemo(
    () => collectionImportChanges(props.collections, importedCollections, props.deleteMissing),
    [props.collections, importedCollections, props.deleteMissing]
  );
  const importInvalid = importing && props.draft.trim() !== "" && importedCollections === null;
  const importHasChanges = importChanges.added.length > 0 || importChanges.changed.length > 0 || importChanges.deleted.length > 0;
  const canReview = Boolean(importedCollections?.length) && importHasChanges && !importInvalid;
  const selectedExportJson = JSON.stringify(selectedExportCollections, null, 2);

  useEffect(() => {
    if (importing) return;
    const ids = exportCollections.map((collection) => collection.id);
    setSelectedExportIds(ids);
    props.onDraft(JSON.stringify(exportCollections, null, 2));
  }, [importing, exportCollections]);

  useEffect(() => {
    if (importing) return;
    props.onDraft(selectedExportJson);
  }, [importing, selectedExportJson]);

  function toggleSelectAll() {
    if (selectedExportIds.length === exportCollections.length) {
      setSelectedExportIds([]);
    } else {
      setSelectedExportIds(exportCollections.map((collection) => collection.id));
    }
  }

  function toggleCollection(id: string) {
    setSelectedExportIds((current) => (current.includes(id) ? current.filter((value) => value !== id) : [...current, id]));
  }

  function downloadExport() {
    downloadJsonFile(selectedExportCollections, "pb_schema.json");
  }

  function loadImportFile(file?: File) {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => props.onDraft(String(reader.result ?? ""));
    reader.readAsText(file);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function clearImport() {
    props.onDraft("");
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function applyCollectionIdReplacements() {
    const nextDraft = replaceCollectionIdsInImportPayload(props.draft, collectionIdReplacements);
    if (!nextDraft) return;
    setReviewOpen(false);
    props.onDraft(nextDraft);
  }

  async function confirmImport() {
    if (await props.onImport()) setReviewOpen(false);
  }

  if (!importing) {
    const allSelected = selectedExportIds.length > 0 && selectedExportIds.length === exportCollections.length;
    return (
      <section className="settings-page">
        <SettingsPageHeader section={t("settings.nav.export", "Export collections")} />
        <p className="settings-intro sync-page-intro">
          {t("transfer.export_intro", "Below you'll find your current collections configuration that you could import in another PocketBase environment.")}
        </p>
        <section className="surface transfer-surface export-transfer-surface">
          <aside className="export-collection-list">
            <label className="check-row export-select-all">
              <input type="checkbox" checked={allSelected} onChange={toggleSelectAll} />
              {t("actions.select_all", "Select all")}
            </label>
            <div className="export-collection-items">
              {exportCollections.map((collection) => (
                <label className="export-collection-row" key={collection.id}>
                  <input type="checkbox" checked={selectedExportIds.includes(collection.id)} onChange={() => toggleCollection(collection.id)} />
                  <span title={collection.name}>{collection.name}</span>
                  <code>{collection.type}</code>
                </label>
              ))}
            </div>
          </aside>
          <section className="export-preview-panel">
            <div className="surface-toolbar">
              <div className="table-meta transfer-meta">
                <span>{t("transfer.selected_count", { count: selectedExportCollections.length, defaultValue: "{{count}} selected" })}</span>
              </div>
              <div className="top-actions">
                <RefreshButton className="subtle" iconSize={16} onClick={props.onExport} title={t("actions.refresh", "Refresh")}>
                  {t("actions.refresh", "Refresh")}
                </RefreshButton>
                <button className="subtle" onClick={() => props.onCopy(selectedExportJson)} disabled={selectedExportCollections.length === 0}>
                  <Copy size={16} />
                  {t("actions.copy_json", "Copy JSON")}
                </button>
                <button className="primary" onClick={downloadExport} disabled={selectedExportCollections.length === 0}>
                  <Download size={16} />
                  {t("actions.download_json", "Download as JSON")}
                </button>
              </div>
            </div>
            <pre className="export-json-preview">{selectedExportJson}</pre>
          </section>
        </section>
      </section>
    );
  }

  return (
    <section className="settings-page">
      <SettingsPageHeader section={t("settings.nav.import", "Import collections")} />
      <section className="surface transfer-surface import-transfer-surface">
        <div className="settings-section">
          <div className="section-heading">
            <div>
              <h2>{t("transfer.collections", "Collections")}</h2>
              <p>{t("transfer.import_intro", "Paste below the collections configuration you want to import or load it from a JSON file.")}</p>
            </div>
            <Upload size={18} />
          </div>
          <div className="top-actions import-file-actions">
            <button type="button" className="subtle" onClick={() => fileInputRef.current?.click()}>
              <Upload size={16} />
              {t("actions.load_json_file", "Load from JSON file")}
            </button>
            <button type="button" className="subtle" onClick={clearImport} disabled={!props.draft.trim()}>
              <X size={16} />
              {t("actions.clear", "Clear")}
            </button>
            <input
              ref={fileInputRef}
              className="hidden-input"
              type="file"
              accept=".json,application/json"
              onChange={(event) => loadImportFile(event.target.files?.[0])}
            />
          </div>
          <label className="import-json-field">
            {t("transfer.collections_json", "Collections JSON")}
            <textarea
              id="import-collections-json"
              name="collectionsJson"
              value={props.draft}
              onChange={(event) => props.onDraft(event.target.value)}
              spellCheck={false}
            />
          </label>
          {importInvalid && <div className="form-error">{t("transfer.invalid_config", "Invalid collections configuration.")}</div>}
          {Boolean(importedCollections?.length) && !importInvalid && (
            <fieldset className="import-mode-options">
              <legend>{t("transfer.import_mode", "Import mode")}</legend>
              <label className="check-row">
                <input
                  type="radio"
                  name="collectionImportMode"
                  checked={!props.deleteMissing}
                  onChange={() => props.onDeleteMissing(false)}
                />
                <span>
                  <strong>{t("transfer.merge_mode", "Merge with current collections")}</strong>
                  <small>{t("transfer.merge_mode_help", "Keep collections that are not included in the import.")}</small>
                </span>
              </label>
              <label className="check-row">
                <input
                  type="radio"
                  name="collectionImportMode"
                  checked={props.deleteMissing}
                  onChange={() => props.onDeleteMissing(true)}
                />
                <span>
                  <strong>{t("transfer.replace_mode", "Replace current collections")}</strong>
                  <small>{t("transfer.replace_mode_help", "Delete non-system collections that are not included in the import.")}</small>
                </span>
              </label>
            </fieldset>
          )}
          {collectionIdReplacements.length > 0 && (
            <aside className="settings-alert warning import-id-replacement-suggestions">
              <strong>{t("transfer.collection_id_conflicts", "Matching collection names use different IDs")}</strong>
              <p>
                {t(
                  "transfer.collection_id_conflicts_help",
                  "This Java storage implementation keeps collection IDs as stable relation keys. Replace the imported IDs with the matching local IDs before importing to update the existing collections and preserve relation targets."
                )}
              </p>
              <ul>
                {collectionIdReplacements.map((replacement) => (
                  <li key={`${replacement.fromId}:${replacement.toId}`}>
                    <code>{replacement.name}</code>
                    <span>{replacement.fromId}</span>
                    <ChevronRight size={14} aria-hidden="true" />
                    <span>{replacement.toId}</span>
                  </li>
                ))}
              </ul>
              <button className="subtle" type="button" onClick={applyCollectionIdReplacements}>
                <GitBranch size={16} />
                {t("actions.replace_collection_ids", "Use matching local IDs")}
              </button>
            </aside>
          )}
        </div>

        <div className="settings-section import-review-panel">
          <div className="section-heading">
            <div>
              <h2>{t("transfer.detected_changes", "Detected changes")}</h2>
              <p>{importedCollections?.length ? t("transfer.parsed_count", { count: importedCollections.length, defaultValue: "{{count}} imported collections parsed" }) : t("transfer.parsed_none", "No imported collections parsed yet.")}</p>
            </div>
            <ListFilter size={18} />
          </div>
          {importedCollections?.length && !importHasChanges && !importInvalid ? (
            <div className="settings-alert info">{t("transfer.up_to_date", "Your collections configuration is already up-to-date.")}</div>
          ) : (
            <div className="import-change-list">
              {importChanges.deleted.map((collection) => (
                <TransferChangeRow key={`delete-${collection.id}`} label="Deleted" tone="danger" collection={collection} />
              ))}
              {importChanges.changed.map((pair) => (
                <TransferChangeRow key={`change-${pair.next.id}`} label="Changed" tone="warning" collection={pair.next} previousName={pair.previous.name} />
              ))}
              {importChanges.added.map((collection) => (
                <TransferChangeRow key={`add-${collection.id}`} label="Added" tone="success" collection={collection} />
              ))}
              {!importChanges.deleted.length && !importChanges.changed.length && !importChanges.added.length && (
                <article className="import-change-row empty">
                  <span>{t("transfer.no_changes", "No changes detected")}</span>
                </article>
              )}
            </div>
          )}
          <div className="transfer-actions">
            <button className="primary" type="button" onClick={() => setReviewOpen(true)} disabled={props.loading || !canReview}>
              <CheckSquare2 size={16} />
              {t("actions.review", "Review")}
            </button>
          </div>
        </div>
      </section>
      {reviewOpen && (
        <Modal title={t("transfer.review_title", "Review collections import")} onClose={() => setReviewOpen(false)} wide>
          <div className="modal-grid import-review-modal">
            <div className="import-review-summary">
              <span>{t("transfer.added_count", { count: importChanges.added.length, defaultValue: "{{count}} added" })}</span>
              <span>{t("transfer.changed_count", { count: importChanges.changed.length, defaultValue: "{{count}} changed" })}</span>
              <span>{t("transfer.deleted_count", { count: importChanges.deleted.length, defaultValue: "{{count}} deleted" })}</span>
            </div>
            <div className="import-change-list compact">
              {importChanges.deleted.map((collection) => (
                <TransferChangeRow key={`modal-delete-${collection.id}`} label="Deleted" tone="danger" collection={collection} />
              ))}
              {importChanges.changed.map((pair) => (
                <TransferChangeRow key={`modal-change-${pair.next.id}`} label="Changed" tone="warning" collection={pair.next} previousName={pair.previous.name} />
              ))}
              {importChanges.added.map((collection) => (
                <TransferChangeRow key={`modal-add-${collection.id}`} label="Added" tone="success" collection={collection} />
              ))}
            </div>
            {importChanges.changed.map((pair) => (
              <CollectionImportDiff key={`diff-${pair.next.id}`} previous={pair.previous} next={pair.next} />
            ))}
            <div className="settings-alert">
              {t("transfer.import_warning", "Importing will apply schema changes to the current database. Review destructive changes before continuing.")}
            </div>
            <div className="modal-actions">
              <button type="button" className="subtle" onClick={() => setReviewOpen(false)}>
                <X size={16} />
                {t("actions.cancel", "Cancel")}
              </button>
              <button type="button" className="primary" onClick={confirmImport} disabled={props.loading}>
                <Upload size={16} />
                {t("actions.import_collections", "Import collections")}
              </button>
            </div>
          </div>
        </Modal>
      )}
    </section>
  );
}

type TransferChangeRowProps = {
  label: "Added" | "Changed" | "Deleted";
  tone: "success" | "warning" | "danger";
  collection: CollectionSchema;
  previousName?: string;
};

function TransferChangeRow({ label, tone, collection, previousName }: TransferChangeRowProps) {
  const { t } = useTranslation();
  const translatedLabel =
    label === "Added"
      ? t("transfer.change_added", "Added")
      : label === "Changed"
        ? t("transfer.change_changed", "Changed")
        : t("transfer.change_deleted", "Deleted");
  return (
    <article className="import-change-row">
      <span className={`sync-change-label ${tone}`}>{translatedLabel}</span>
      <div>
        {previousName && previousName !== collection.name && (
          <>
            <span className="previous-name">{previousName}</span>
            <ChevronRight size={14} />
          </>
        )}
        <strong>{collection.name}</strong>
        <code>{collection.id}</code>
      </div>
    </article>
  );
}

type CollectionFieldChange = {
  kind: "Added" | "Changed" | "Deleted";
  previous?: FieldSchema;
  next?: FieldSchema;
  changedKeys: string[];
};

function CollectionImportDiff({ previous, next }: { previous: CollectionSchema; next: CollectionSchema }) {
  const { t } = useTranslation();
  const fieldChanges = useMemo(() => collectionFieldChanges(previous, next), [previous, next]);
  return (
    <article className="collection-import-diff">
      <header className="collection-import-diff-header">
        <div>
          <strong>{next.name}</strong>
          {previous.name !== next.name && <span className="previous-name">{previous.name}</span>}
        </div>
        <code>{next.id}</code>
      </header>
      <div className="collection-import-diff-columns">
        <section>
          <h3>{t("transfer.current", "Current")}</h3>
          <pre>{JSON.stringify(previous, null, 2)}</pre>
        </section>
        <section>
          <h3>{t("transfer.imported", "Imported")}</h3>
          <pre>{JSON.stringify(next, null, 2)}</pre>
        </section>
      </div>
      <section className="collection-field-diff">
        <h3>{t("transfer.field_changes", "Field changes")}</h3>
        {fieldChanges.length === 0 ? (
          <p>{t("transfer.no_field_changes", "No field changes")}</p>
        ) : (
          <div className="collection-field-diff-list">
            {fieldChanges.map((change) => {
              const field = change.next ?? change.previous!;
              const label =
                change.kind === "Added"
                  ? t("transfer.change_added", "Added")
                  : change.kind === "Deleted"
                    ? t("transfer.change_deleted", "Deleted")
                    : t("transfer.change_changed", "Changed");
              const tone = change.kind === "Added" ? "success" : change.kind === "Deleted" ? "danger" : "warning";
              return (
                <div className="collection-field-diff-row" key={`${change.kind}:${field.id ?? field.name}`}>
                  <span className={`sync-change-label ${tone}`}>{label}</span>
                  <div>
                    {change.previous && change.next && change.previous.name !== change.next.name ? (
                      <>
                        <span className="previous-name">{change.previous.name}</span>
                        <ChevronRight size={14} />
                        <strong>{change.next.name}</strong>
                      </>
                    ) : (
                      <strong>{field.name}</strong>
                    )}
                    <code>{field.type}</code>
                    {change.changedKeys.length > 0 && <code>{change.changedKeys.join(", ")}</code>}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </article>
  );
}

type SqlViewProps = {
  query: string;
  result: SqlResult | null;
  error: string;
  elapsedMs: number | null;
  loading: boolean;
  sqlCompletions: string[];
  history?: string[];
  onQuery: (value: string) => void;
  onRun: () => void;
  onRemoveHistory?: (query: string) => void;
};

function SqlView(props: SqlViewProps) {
  const { t } = useTranslation();
  const columns = props.result?.columns ?? [];  const rows = props.result?.rows ?? [];
  const [visibleRows, setVisibleRows] = useState(250);
  const [sort, setSort] = useState<{ index: number; direction: SortDirection } | null>(null);

  useEffect(() => {
    setVisibleRows(250);
    setSort(null);
  }, [props.result]);

  const sortedRows = useMemo(() => {
    const indexed: Array<{ row: unknown[]; index: number }> = rows.map((row, index) => ({ row, index }));
    if (!sort) return indexed;
    return [...indexed].sort((left, right) => {
      const result = compareSqlValues(left.row[sort.index], right.row[sort.index]);
      return result === 0 ? left.index - right.index : sort.direction === "asc" ? result : -result;
    });
  }, [rows, sort]);
  const shownRows = sortedRows.slice(0, visibleRows);

  function toggleSort(index: number) {
    setSort((current) => {
      if (!current || current.index !== index) return { index, direction: "asc" };
      return { index, direction: current.direction === "asc" ? "desc" : "asc" };
    });
  }

  function exportCsv() {
    downloadCsvFile(
      columns.map((column) => column.name),
      rows,
      `pocketbase-sql-${new Date().toISOString().replace(/[:.]/g, "-")}.csv`
    );
  }

  return (
    <section className="settings-page">
      <SettingsPageHeader section={t("settings.nav.sql", "SQL console")} />
      <div className="sql-layout">
        <section className="surface sql-editor">
          <div className="surface-toolbar">
            <div className="table-meta transfer-meta">
              <span>{t("sql.superuser_console", "Superuser SQL console")}</span>
            </div>
            <button className="primary" onClick={props.onRun} disabled={props.loading || !props.query.trim()}>
              <Play size={16} />
              {t("actions.run_query", "Run query")}
            </button>
          </div>
          <label className="sql-textarea">
            {t("sql.query", "Query")}
            <CodeEditor
              value={props.query}
              onChange={props.onQuery}
              language="sql"
              completions={props.sqlCompletions}
              id="sql-query"
              name="sqlQuery"
              ariaLabel={t("sql.query", "Query")}
              minHeight={140}
            />
          </label>
          {props.history && props.history.length > 0 && (
            <div className="sql-history">
              <span className="sql-history-label">{t("sql.history", "Recent")}</span>
              <div className="sql-history-list">
                {props.history.map((item) => (
                  <div className="sql-history-item" key={item}>
                    <button
                      type="button"
                      className="sql-history-query"
                      onClick={() => props.onQuery(item)}
                      title={item}
                    >
                      {item.length > 60 ? `${item.slice(0, 60)}…` : item}
                    </button>
                    {props.onRemoveHistory && (
                      <button
                        type="button"
                        className="icon-button sql-history-remove"
                        onClick={() => props.onRemoveHistory?.(item)}
                        title={t("actions.remove", "Remove")}
                        aria-label={t("actions.remove", "Remove")}
                      >
                        <X size={13} />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>

        <section className="surface sql-result">
          <div className="table-meta">
            <span>{t("sql.affected_rows", { count: Number(props.result?.affectedRows ?? 0), defaultValue: "{{count}} affected rows" })}</span>
            <span>{t("sql.result_rows", { count: rows.length, defaultValue: "{{count}} result rows" })}</span>
            {props.elapsedMs !== null && <span>{t("sql.elapsed_ms", { count: props.elapsedMs, defaultValue: "{{count}} ms" })}</span>}
            {props.error && <span className="danger">{props.error}</span>}
            {columns.length > 0 && rows.length > 0 && (
              <button type="button" className="subtle compact" onClick={exportCsv}>
                <Download size={14} />
                {t("sql.export_csv", "Export CSV")}
              </button>
            )}
          </div>
          <div className="table-wrap">
            <table className="sql-table">
              <thead>
                <tr>
                  {columns.length === 0 ? (
                    <th>{t("sql.result", "Result")}</th>
                  ) : (
                    columns.map((column, index) => (
                      <th key={column.name} aria-sort={sort?.index === index ? (sort.direction === "asc" ? "ascending" : "descending") : "none"}>
                        <button type="button" className="table-sort-button" onClick={() => toggleSort(index)}>
                          {column.name}
                          {sort?.index === index && <span aria-hidden="true">{sort.direction === "asc" ? " ↑" : " ↓"}</span>}
                        </button>
                      </th>
                    ))
                  )}
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 ? (
                  <tr>
                    <td className="empty-row" colSpan={Math.max(1, columns.length)}>
                      {t("sql.no_rows", "No rows")}
                    </td>
                  </tr>
                ) : (
                  shownRows.map(({ row, index: rowIndex }) => (
                    <tr key={rowIndex}>
                      {columns.map((column, columnIndex) => (
                        <td key={column.name}>
                          {row[columnIndex] === null ? (
                            <em className="sql-null">{t("sql.null", "NULL")}</em>
                          ) : (
                            <code>{formatValue(row[columnIndex])}</code>
                          )}
                        </td>
                      ))}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
          {shownRows.length < rows.length && (
            <div className="load-more-row sql-load-more">
              <span>{t("sql.showing_rows", { shown: shownRows.length, count: rows.length, defaultValue: "Showing {{shown}} of {{count}} rows" })}</span>
              <button type="button" className="subtle" onClick={() => setVisibleRows((count) => Math.min(rows.length, count + 250))}>
                {t("sql.load_more", { count: rows.length - shownRows.length, defaultValue: "Load 250 more ({{count}} remaining)" })}
              </button>
            </div>
          )}
        </section>
      </div>
    </section>
  );
}

type LogsViewProps = {
  logs: LogItem[];
  logPage: ListResponse<LogItem> | null;
  filter: string;
  stats: LogStat[];
  timeRange: LogTimeRange | null;
  includeSuperuserRequests: boolean;
  loading: boolean;
  isLogListLoading: boolean;
  isLogStatsLoading: boolean;
  isLogFirstLoadReady: boolean;
  onFilter: (value: string) => void;
  onApply: () => void;
  onIncludeSuperuserRequests: (value: boolean) => void;
  onTimeRange: (range: LogTimeRange) => void;
  onClearTimeRange: () => void;
  onRefresh: () => void;
  onLoadMore: () => void | Promise<void>;
  onOpenLog: (log: LogItem) => void;
  onNotify: (message: string, kind?: "ok" | "error") => void;
  onOpenSettings: () => void;
};

function LogsView(props: LogsViewProps) {
  const { t } = useTranslation();
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [searchHistory, setSearchHistory] = useState<string[]>(() => readSearchHistory("pbj_log_search_history"));
  const lastSelectedId = useRef<string | null>(null);
  const chartSelectionStart = useRef<number | null>(null);
  const [chartSelection, setChartSelection] = useState<{ start: number; end: number } | null>(null);
  const [hoveredChartIndex, setHoveredChartIndex] = useState<number | null>(null);
  const chartStats = useMemo(() => fillLogStatGaps(props.stats), [props.stats]);
  const [chartWindowStart, setChartWindowStart] = useState(0);
  const [chartPlaceholder, setChartPlaceholder] = useState(false);
  const total = props.logPage?.totalItems ?? props.logs.length;
  const hasMoreLogs = Boolean(props.logPage && props.logs.length > 0 && props.logs.length < total);
  const statsTotal = props.stats.reduce((sum, item) => sum + Number(item.total || 0), 0);
  // Official shows a denser hourly strip; 24 keeps one day visible and readable.
  const chartWindowSize = 24;
  const chartMaxStart = Math.max(0, chartStats.length - chartWindowSize);
  const visibleChartStats = chartStats.slice(chartWindowStart, chartWindowStart + chartWindowSize);
  const maxStat = Math.max(1, ...visibleChartStats.map((item) => Number(item.total || 0)));
  const yMax = niceChartCeil(maxStat);
  const yTicks = chartYTicks(maxStat);
  const chartValues = useMemo(
    () => visibleChartStats.map((item) => Number(item.total || 0)),
    [visibleChartStats]
  );
  const chartPaths = useMemo(() => buildLogChartStepPaths(chartValues, yMax), [chartValues, yMax]);
  const selectedSet = useMemo(() => new Set(selectedIds), [selectedIds]);
  const allVisibleSelected = props.logs.length > 0 && props.logs.every((log) => selectedSet.has(log.id));
  const selectedLogs = useMemo(() => props.logs.filter((log) => selectedSet.has(log.id)), [props.logs, selectedSet]);

  useEffect(() => {
    const ids = new Set(props.logs.map((log) => log.id));
    setSelectedIds((current) => current.filter((id) => ids.has(id)));
  }, [props.logs]);

  useEffect(() => {
    setChartWindowStart(chartMaxStart);
    setChartSelection(null);
    chartSelectionStart.current = null;
  }, [chartMaxStart, props.stats]);

  // Official v0.39.10: delay the spinner briefly to avoid flicker on quick
  // stats reads, while still revealing a clear loading state for slow reads.
  useEffect(() => {
    if (!props.isLogStatsLoading) {
      setChartPlaceholder(false);
      return undefined;
    }
    const timer = window.setTimeout(() => setChartPlaceholder(true), 250);
    return () => {
      window.clearTimeout(timer);
    };
  }, [props.isLogStatsLoading]);

  function toggleSelected(id: string, extendRange = false) {
    setSelectedIds((current) => {
      const isSelected = current.includes(id);
      if (extendRange && lastSelectedId.current) {
        const from = props.logs.findIndex((log) => log.id === lastSelectedId.current);
        const to = props.logs.findIndex((log) => log.id === id);
        if (from >= 0 && to >= 0) {
          const ids = props.logs.slice(Math.min(from, to), Math.max(from, to) + 1).map((log) => log.id);
          const next = new Set(current);
          for (const itemId of ids) isSelected ? next.delete(itemId) : next.add(itemId);
          return Array.from(next);
        }
      }
      lastSelectedId.current = id;
      return isSelected ? current.filter((itemId) => itemId !== id) : [...current, id];
    });
  }

  function exportSelected() {
    downloadJsonFile(
      selectedLogs,
      `pocketbase-logs-${new Date().toISOString().replace(/[:.]/g, "-")}.json`
    );
    props.onNotify(t("logs.exported_selected", "Selected logs downloaded"));
  }

  function apply() {
    setSearchHistory(writeSearchHistory("pbj_log_search_history", props.filter));
    props.onApply();
  }

  function chartIndex(event: { currentTarget: HTMLDivElement; clientX: number }) {
    if (visibleChartStats.length === 0) return -1;
    const bounds = event.currentTarget.getBoundingClientRect();
    if (bounds.width <= 0) return -1;
    const ratio = Math.max(0, Math.min(0.999999, (event.clientX - bounds.left) / bounds.width));
    return Math.floor(ratio * visibleChartStats.length);
  }

  function selectChartRange(startIndex: number, endIndex: number) {
    const first = visibleChartStats[Math.min(startIndex, endIndex)];
    const last = visibleChartStats[Math.max(startIndex, endIndex)];
    const end = last ? nextLogHour(last.date) : "";
    if (first && end) props.onTimeRange({ start: first.date, end });
  }

  function resetChart() {
    setChartWindowStart(chartMaxStart);
    if (props.timeRange) props.onClearTimeRange();
  }

  // Official v0.39.10 chart visibility states. A chart never exposes stale
  // points while the current list or stats request is still settling.
  const statsReady = chartStats.length > 0;
  const hasLogItems = props.logs.length > 0;
  const chartAwaitingList = !props.isLogFirstLoadReady;
  const chartLoading = chartAwaitingList || props.isLogStatsLoading;
  // Before the current list is ready, and for an unzoomed empty list, the
  // official UI collapses the chart instead of leaving a blank blue strip.
  const chartCollapsed = chartAwaitingList || (!hasLogItems && !props.timeRange);
  const chartInteractive = !chartLoading && statsReady;

  useEffect(() => {
    if (chartInteractive) return;
    chartSelectionStart.current = null;
    setChartSelection(null);
    setHoveredChartIndex(null);
  }, [chartInteractive]);

  return (
    <section className="logs-page" aria-busy={props.isLogListLoading || props.isLogStatsLoading || undefined}>
      {!chartCollapsed && (
      <div className={`logs-chart-strip${chartLoading ? " pending" : ""}${hasLogItems ? " nonempty-list" : " empty-list"}`}>
        <button
          type="button"
          className="logs-chart-pan logs-chart-pan-left"
          disabled={chartWindowStart <= 0 || !chartInteractive}
          onClick={() => setChartWindowStart((start) => Math.max(0, start - 12))}
          title={t("actions.back", "Back")}
          aria-label={t("actions.back", "Back")}
        >
          <ChevronRight size={16} />
        </button>
        <div className="logs-chart-main">
          <div className="logs-chart-plot">
            {visibleChartStats.length > 0 && (
              <div className="logs-chart-y" aria-hidden="true">
                {[...yTicks].reverse().map((tick) => (
                  <span key={tick} className="logs-chart-y-tick">
                    {formatChartYLabel(tick)}
                  </span>
                ))}
              </div>
            )}
            <div
              className={`logs-chart-canvas${!chartInteractive ? " is-pending" : ""}`}
              aria-label={t("logs.activity", "Log activity")}
              aria-busy={chartLoading || undefined}
              {...(!chartInteractive ? { inert: true } : {})}
              onPointerDown={(event) => {
                if (!chartInteractive || event.button !== 0) return;
                const index = chartIndex(event);
                if (index < 0) return;
                chartSelectionStart.current = index;
                setChartSelection({ start: index, end: index });
                event.currentTarget.setPointerCapture(event.pointerId);
              }}
              onPointerMove={(event) => {
                if (!chartInteractive) return;
                const index = chartIndex(event);
                if (index >= 0) setHoveredChartIndex(index);
                if (index >= 0 && chartSelectionStart.current !== null) {
                  setChartSelection({ start: chartSelectionStart.current, end: index });
                }
              }}
              onPointerUp={(event) => {
                if (!chartInteractive) return;
                const start = chartSelectionStart.current;
                const end = chartIndex(event);
                chartSelectionStart.current = null;
                setChartSelection(null);
                if (event.currentTarget.hasPointerCapture(event.pointerId)) {
                  event.currentTarget.releasePointerCapture(event.pointerId);
                }
                if (start !== null && end >= 0) selectChartRange(start, end);
              }}
              onPointerCancel={() => {
                chartSelectionStart.current = null;
                setChartSelection(null);
              }}
              onPointerLeave={() => {
                if (!chartInteractive) return;
                if (chartSelectionStart.current === null) setHoveredChartIndex(null);
              }}
              onDoubleClick={() => {
                if (chartInteractive) resetChart();
              }}
            >
              {chartLoading && !statsReady ? (
                <span className="logs-chart-pending">
                  {chartPlaceholder ? (
                    <>
                      <span className="logs-chart-loader" aria-hidden="true" />
                      {t("common.loading", "Loading...")}
                    </>
                  ) : (
                    "\u00A0"
                  )}
                </span>
              ) : visibleChartStats.length === 0 ? (
                <span className="logs-chart-empty">{t("logs.no_activity", "No log activity")}</span>
              ) : (
                <>
                  {/* Continuous step-area fill (official style). */}
                  <svg
                    className="logs-chart-svg"
                    viewBox="0 0 1000 100"
                    preserveAspectRatio="none"
                    aria-hidden="true"
                  >
                    <path className="logs-chart-area" d={chartPaths.area} />
                    <path className="logs-chart-line" d={chartPaths.line} />
                    {chartSelection &&
                      (() => {
                        const a = Math.min(chartSelection.start, chartSelection.end);
                        const b = Math.max(chartSelection.start, chartSelection.end) + 1;
                        const n = visibleChartStats.length || 1;
                        const x = (a / n) * 1000;
                        const w = ((b - a) / n) * 1000;
                        return <rect className="logs-chart-selection" x={x} y={0} width={w} height={100} />;
                      })()}
                  </svg>
                  {/* Per-hour hit targets for hover + keyboard. */}
                  <div className="logs-chart-hits">
                    {visibleChartStats.map((item, index) => (
                      <button
                        key={item.date}
                        type="button"
                        className={`logs-chart-hit${hoveredChartIndex === index ? " is-hover" : ""}`}
                        disabled={!chartInteractive}
                        tabIndex={chartInteractive ? 0 : -1}
                        aria-label={`${formatLogChartHourRange(item.date)}: ${item.total}`}
                        onFocus={() => setHoveredChartIndex(index)}
                        onBlur={() => setHoveredChartIndex(null)}
                        onKeyDown={(event) => {
                          if (chartInteractive && (event.key === "Enter" || event.key === " ")) {
                            event.preventDefault();
                            selectChartRange(index, index);
                          }
                        }}
                      />
                    ))}
                  </div>
                </>
              )}
              {chartLoading && statsReady && (
                <span className="logs-chart-pending logs-chart-pending-overlay">
                  {chartPlaceholder ? (
                    <>
                      <span className="logs-chart-loader" aria-hidden="true" />
                      {t("common.loading", "Loading...")}
                    </>
                  ) : (
                    "\u00A0"
                  )}
                </span>
              )}
              {hoveredChartIndex !== null && visibleChartStats[hoveredChartIndex] && (
                <span
                  className="logs-chart-tooltip"
                  style={{ left: `${((hoveredChartIndex + 0.5) / visibleChartStats.length) * 100}%` }}
                >
                  <strong>
                    {t("logs.requests_count", {
                      count: Number(visibleChartStats[hoveredChartIndex].total || 0),
                      defaultValue: "{{count}} requests"
                    })}
                  </strong>
                  <span>{formatLogChartHourRange(visibleChartStats[hoveredChartIndex].date)}</span>
                </span>
              )}
            </div>
          </div>
          {visibleChartStats.length > 0 && (
            <div className="logs-chart-x" aria-hidden="true">
              {visibleChartStats.map((item, index) => {
                const prev = index > 0 ? parseLogDate(visibleChartStats[index - 1].date) : null;
                const cur = parseLogDate(item.date);
                const showDate =
                  Boolean(cur) &&
                  (!prev ||
                    prev.getFullYear() !== cur!.getFullYear() ||
                    prev.getMonth() !== cur!.getMonth() ||
                    prev.getDate() !== cur!.getDate());
                // Official labels every hour; date only when the day rolls over.
                const label = formatLogChartAxisLabel(item.date, showDate);
                return (
                  <span key={item.date} className="logs-chart-x-tick">
                    <em>{label.time}</em>
                    {label.date && <small>{label.date}</small>}
                  </span>
                );
              })}
            </div>
          )}
        </div>
        <button
          type="button"
          className="logs-chart-pan"
          disabled={chartWindowStart >= chartMaxStart || !chartInteractive}
          onClick={() => setChartWindowStart((start) => Math.min(chartMaxStart, start + 12))}
          title={t("actions.next", "Next")}
          aria-label={t("actions.next", "Next")}
        >
          <ChevronRight size={16} />
        </button>
      </div>
      )}

      {/* Official: white panel with top rounded corners sitting over the blue chart. */}
      <div className="logs-content">
      <header className="page-header logs-page-header">
        <nav className="breadcrumbs" aria-label={t("common.breadcrumb", "Breadcrumb")}>
          <span>{t("nav.logs", "Logs")}</span>
        </nav>
        <button className="icon-button page-circle" onClick={props.onOpenSettings} title={t("settings.logs_title", "Logs")} aria-label={t("settings.logs_title", "Logs")}>
          <Settings size={17} />
        </button>
        <RefreshButton className="icon-button page-circle" onClick={props.onRefresh} title={t("actions.refresh_logs", "Refresh logs")} />
        <div className="searchbar logs-searchbar">
          <Search size={17} />
          <input
            id="logs-filter"
            name="logsFilter"
            autoComplete="off"
            list="logs-search-history"
            aria-label={t("logs.search_aria", "Search term or filter")}
            value={props.filter}
            onChange={(event) => props.onFilter(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") apply();
            }}
            placeholder={t("logs.search_placeholder", "Search term or filter like `level > 0`")}
          />
          <datalist id="logs-search-history">
            {searchHistory.map((value) => <option key={value} value={value} />)}
          </datalist>
        </div>
        <button className="subtle apply-button" onClick={apply} disabled={props.loading}>
          <ListFilter size={16} />
          {t("actions.apply", "Apply")}
        </button>
        <label className="check-row logs-superuser-toggle">
          <input
            type="checkbox"
            checked={props.includeSuperuserRequests}
            onChange={(event) => props.onIncludeSuperuserRequests(event.target.checked)}
          />
          {t("logs.include_superuser_requests", "Include requests by superusers")}
        </label>
        <div className="logs-header-meta">
          <span>{t("logs.hourly_events", { count: statsTotal, defaultValue: "{{count}} hourly events" })}</span>
        </div>
      </header>

      {selectedIds.length > 0 && (
        <div className="bulkbar">
          <span>{t("transfer.selected_count", { count: selectedIds.length, defaultValue: "{{count}} selected" })}</span>
          <button type="button" className="subtle" onClick={() => setSelectedIds([])}>
            <X size={16} />
            {t("actions.clear", "Clear")}
          </button>
          <button type="button" className="subtle" onClick={exportSelected}>
            <Download size={16} />
            {t("logs.export_selected", "Download JSON")}
          </button>
        </div>
      )}

      <div className="page-table-wrapper">
        <table className="logs-table">
          <thead>
            <tr>
              <th className="select-col">
                <button
                  type="button"
                  className={`checkbox-button${allVisibleSelected ? " is-checked" : ""}`}
                  onClick={() => setSelectedIds(allVisibleSelected ? [] : props.logs.map((log) => log.id))}
                  title={allVisibleSelected ? t("actions.clear_selection", "Clear selection") : t("actions.select_page", "Select page")}
                  aria-label={allVisibleSelected ? t("actions.clear_selection", "Clear selection") : t("actions.select_page", "Select page")}
                >
                  {allVisibleSelected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                </button>
              </th>
              <th className="log-level-col">{t("logs.level", "Level")}</th>
              <th>{t("logs.message", "Message")}</th>
              <th>{t("logs.time", "Created")}</th>
              <th className="actions-col">{t("collections.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {props.logs.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={5}>
                  <div className="empty-table-message">
                    <strong>{t("logs.no_logs", "No logs")}</strong>
                    <div className="empty-row-actions">
                      {props.filter.trim() && (
                        <button className="subtle" onClick={() => props.onFilter("")}>
                          {t("actions.clear_search", "Clear search")}
                        </button>
                      )}
                      {(props.timeRange || chartWindowStart > 0) && (
                        <button
                          className="subtle"
                          onClick={() => {
                            props.onClearTimeRange();
                            setChartWindowStart(0);
                          }}
                        >
                          {t("actions.reset_zoom", "Reset zoom")}
                        </button>
                      )}
                    </div>
                  </div>
                </td>
              </tr>
            ) : (
              props.logs.map((log) => {
                const level = logLevel(log.level);
                const selected = selectedSet.has(log.id);
                return (
                  <tr key={log.id} onClick={() => props.onOpenLog(log)} className={selected ? "log-row selected" : "log-row"}>
                    <td className="select-col">
                      <button
                        type="button"
                        className={`checkbox-button${selected ? " is-checked" : ""}`}
                        onClick={(event) => {
                          event.stopPropagation();
                          toggleSelected(log.id, event.shiftKey);
                        }}
                        title={selected ? t("actions.unselect_record", "Unselect record") : t("actions.select_record", "Select record")}
                        aria-label={selected ? t("actions.unselect_record", "Unselect record") : t("actions.select_record", "Select record")}
                      >
                        {selected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                      </button>
                    </td>
                    <td className="log-level-col">
                      <span className={`log-level ${level.kind}`}>{level.label}</span>
                    </td>
                    <td>
                      <div className="log-message">
                        <span className="log-message-text" title={log.message}>
                          {log.message || <em>{t("logs.no_message", "(no message)")}</em>}
                        </span>
                        <span className="log-data-chips">
                          {logDataChips(log).map((chip) => (
                            <span key={chip.key} className={`log-chip ${chip.kind ?? ""}`} title={`${chip.key}: ${chip.value}`}>
                              <em>{chip.key}</em>
                              {chip.value}
                            </span>
                          ))}
                        </span>
                      </div>
                    </td>
                    <td>{formatDate(log.created)}</td>
                    <td className="row-actions">
                      <button
                        className="icon-button"
                        onClick={(event) => {
                          event.stopPropagation();
                          props.onOpenLog(log);
                        }}
                        title={t("logs.inspect", "Inspect log")}
                        aria-label={t("logs.inspect", "Inspect log")}
                      >
                        <ChevronRight size={16} />
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
        {hasMoreLogs && (
          <div className="load-more-row">
            <button className="subtle" onClick={() => props.onLoadMore()} disabled={props.loading}>
              {props.loading
                ? t("common.loading", "Loading...")
                : t("logs.load_older", { count: total - props.logs.length, defaultValue: "Load older ({{count}} remaining)" })}
            </button>
          </div>
        )}
      </div>

      <footer className="page-footer">
        <div className="page-footer-left">
          <span>{t("common.total_count", { count: total, defaultValue: "Total: {{count}}" })}</span>
          <span>{t("logs.visible_count", { count: props.logs.length, defaultValue: "{{count}} visible" })}</span>
        </div>
        <div className="page-footer-right">
          <a
            href="javascript:void(0)"
            className="footer-link"
            onClick={(e) => e.preventDefault()}
            title={t("footer.docs", "Docs")}
          >
            <DocsBookIcon />
            <span>Docs</span>
          </a>
          <span className="footer-link-separator">|</span>
          <a
            href="https://github.com/jackBaozz/pocketbase-java"
            target="_blank"
            rel="noopener noreferrer"
            className="footer-link"
            title="PocketBase Java GitHub"
          >
            <GithubMarkIcon />
            <span>PocketBase v0.4.0</span>
          </a>
        </div>
      </footer>
      </div>
    </section>
  );
}

type CollectionPayload = {
  name: string;
  type: string;
  fields: FieldSchema[];
  listRule: string | null;
  viewRule: string | null;
  createRule: string | null;
  updateRule: string | null;
  deleteRule: string | null;
  passwordAuth?: PasswordAuthConfig;
  otp?: OtpConfig;
  mfa?: MfaConfig;
  oauth2?: OAuth2Config;
  authAlert?: AuthAlertConfig;
  authToken?: TokenConfig;
  passwordResetToken?: TokenConfig;
  verificationToken?: TokenConfig;
  emailChangeToken?: TokenConfig;
  fileToken?: TokenConfig;
  verificationTemplate?: EmailTemplate;
  resetPasswordTemplate?: EmailTemplate;
  confirmEmailChangeTemplate?: EmailTemplate;
  authRule?: string | null;
  manageRule?: string | null;
  viewQuery?: string | null;
  indexes?: string[];
};

type PendingDeletedField = {
  field: FieldSchema;
  /** Original position is retained so restore does not unexpectedly reshuffle schema fields. */
  index: number;
};

type RuleKey = "listRule" | "viewRule" | "createRule" | "updateRule" | "deleteRule" | "authRule" | "manageRule";

type CollectionModalProps = {
  state: CollectionEditorState;
  oauthProviders: OAuthProviderMetadata[];
  allCollections: CollectionSchema[];
  onClose: () => void;
  onConfirm: (request: ConfirmRequest) => Promise<boolean>;
  onDryRunView: (query: string) => Promise<ViewQueryPreview>;
  onGenerateAppleClientSecret: (input: AppleClientSecretInput) => Promise<{ secret: string }>;
  onSubmit: (payload: CollectionPayload) => Promise<boolean>;
  onNotify: (message: string, kind?: "ok" | "error") => void;
  /** Collection-level actions, shown only when editing an existing collection. */
  onDuplicate?: () => void;
  onTruncate?: () => void;
  onDelete?: () => void;
};

function CollectionModal({
  state,
  oauthProviders,
  allCollections,
  onClose,
  onConfirm,
  onDryRunView,
  onGenerateAppleClientSecret,
  onSubmit,
  onNotify,
  onDuplicate,
  onTruncate,
  onDelete
}: CollectionModalProps) {
  const { t } = useTranslation();
  const collection = state.collection;
  const [name, setName] = useState(collection?.name ?? "");
  const [type, setType] = useState(collection?.type ?? "base");
  const [fields, setFields] = useState(JSON.stringify(collection?.fields ?? DEFAULT_FIELDS, null, 2));
  const [pendingDeletedFields, setPendingDeletedFields] = useState<PendingDeletedField[]>([]);
  const [viewQuery, setViewQuery] = useState(collection?.viewQuery ?? "");
  const [indexes, setIndexes] = useState<string[]>(collection?.indexes ?? []);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);
  const [dragArmed, setDragArmed] = useState(false);
  const [viewPreview, setViewPreview] = useState<ViewQueryPreview | null>(null);
  const [viewPreviewError, setViewPreviewError] = useState("");
  const [viewPreviewLoading, setViewPreviewLoading] = useState(false);
  const ruleCompletions = useMemo(
    () => buildRuleCompletions(collection ?? null, allCollections),
    [allCollections, collection]
  );
  const viewQueryCompletions = useMemo(() => {
    const names: string[] = [];
    for (const item of allCollections) {
      names.push(item.name);
      for (const field of item.fields ?? []) names.push(field.name, `${item.name}.${field.name}`);
    }
    return [...new Set(names)];
  }, [allCollections]);
  const [passwordEnabled, setPasswordEnabled] = useState(collection?.passwordAuth?.enabled ?? true);
  const [identityFields, setIdentityFields] = useState<string[]>(collection?.passwordAuth?.identityFields ?? ["email"]);
  const [otpEnabled, setOtpEnabled] = useState(collection?.otp?.enabled ?? false);
  const [otpDuration, setOtpDuration] = useState(String(collection?.otp?.duration ?? 180));
  const [otpLength, setOtpLength] = useState(String(collection?.otp?.length ?? 8));
  const [mfaEnabled, setMfaEnabled] = useState(collection?.mfa?.enabled ?? false);
  const [mfaDuration, setMfaDuration] = useState(String(collection?.mfa?.duration ?? 600));
  const [mfaRule, setMfaRule] = useState(collection?.mfa?.rule ?? "");
  const [authAlertEnabled, setAuthAlertEnabled] = useState(collection?.authAlert?.enabled ?? true);
  const [templates, setTemplates] = useState<Record<"verification" | "passwordReset" | "emailChange" | "otp" | "authAlert", EmailTemplate>>({
    verification: collection?.verificationTemplate ?? {},
    passwordReset: collection?.resetPasswordTemplate ?? {},
    emailChange: collection?.confirmEmailChangeTemplate ?? {},
    otp: collection?.otp?.emailTemplate ?? {},
    authAlert: collection?.authAlert?.emailTemplate ?? {}
  });
  const [tokenDrafts, setTokenDrafts] = useState<Record<"authToken" | "passwordResetToken" | "verificationToken" | "emailChangeToken" | "fileToken", { duration: string; rotate: boolean }>>({
    authToken: { duration: String(collection?.authToken?.duration ?? 432000), rotate: false },
    passwordResetToken: { duration: String(collection?.passwordResetToken?.duration ?? 1800), rotate: false },
    verificationToken: { duration: String(collection?.verificationToken?.duration ?? 86400), rotate: false },
    emailChangeToken: { duration: String(collection?.emailChangeToken?.duration ?? 1800), rotate: false },
    fileToken: { duration: String(collection?.fileToken?.duration ?? 180), rotate: false }
  });
  const [oauthEnabled, setOauthEnabled] = useState(collection?.oauth2?.enabled ?? false);
  const [oauthProviderNames, setOauthProviderNames] = useState<string[]>(
    collection?.oauth2?.providers?.map((provider) => provider.name) ?? []
  );
  const [oauthProviderConfigs, setOauthProviderConfigs] = useState<Record<string, OAuth2ProviderConfig>>(() => {
    const entries = collection?.oauth2?.providers ?? [];
    return Object.fromEntries(entries.map((provider) => [provider.name, provider]));
  });
  const [oauthMappedFields, setOauthMappedFields] = useState<OAuth2MappedFields>(
    collection?.oauth2?.mappedFields ?? { id: "", name: "", username: "", avatarURL: "" }
  );
  // null = superusers only (locked); "" = public access. The distinction is part of
  // the PocketBase API contract, so it must survive a load/save round-trip untouched.
  const [rules, setRules] = useState<Record<RuleKey, string | null>>({
    listRule: collection?.listRule ?? null,
    viewRule: collection?.viewRule ?? null,
    createRule: collection?.createRule ?? null,
    updateRule: collection?.updateRule ?? null,
    deleteRule: collection?.deleteRule ?? null,
    // New auth collections authenticate publicly by default. Existing `null` must
    // stay locked (superusers only), so do not collapse it with the public `""`.
    authRule: collection && collection.authRule !== undefined ? collection.authRule : "",
    manageRule: collection?.manageRule ?? null
  });
  const [ruleMemory, setRuleMemory] = useState<Partial<Record<RuleKey, string>>>({});
  const [error, setError] = useState("");
  const [activeTab, setActiveTab] = useState("fields");
  const [exiting, setExiting] = useState(false);
  const [saving, setSaving] = useState(false);
  const tabs = useMemo(() => collectionModalTabs(type, t), [type, t]);

  const snapshot = JSON.stringify({
    name,
    type,
    fields,
    viewQuery,
    indexes,
    rules,
    passwordEnabled,
    identityFields,
    otpEnabled,
    otpDuration,
    otpLength,
    mfaEnabled,
    mfaDuration,
    mfaRule,
    authAlertEnabled,
    templates,
    tokenDrafts,
    oauthEnabled,
    oauthProviderNames,
    oauthProviderConfigs,
    oauthMappedFields
  });
  const initialSnapshot = useRef(snapshot);
  const hasChanges = snapshot !== initialSnapshot.current;

  async function requestClose() {
    if (exiting) return;
    if (hasChanges) {
      const discard = await onConfirm({
        title: t("confirm.discard_changes_title", "Discard changes"),
        message: t(
          "confirm.discard_changes_body",
          "You have unsaved changes. Do you really want to discard them?"
        ),
        confirmLabel: t("actions.discard", "Discard"),
        danger: true
      });
      if (!discard) return;
    }
    setExiting(true);
  }

  useEffect(() => {
    if (!tabs.some((tab) => tab.id === activeTab)) {
      setActiveTab(tabs[0]?.id ?? "fields");
    }
  }, [activeTab, tabs]);

  useEffect(() => {
    if (type !== "view" || activeTab !== "query" || !viewQuery.trim()) {
      setViewPreview(null);
      setViewPreviewError("");
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setViewPreviewLoading(true);
      setViewPreviewError("");
      onDryRunView(viewQuery)
        .then((preview) => {
          if (!cancelled) setViewPreview(preview);
        })
        .catch((error) => {
          if (!cancelled) {
            setViewPreview(null);
            setViewPreviewError(errorMessage(error));
          }
        })
        .finally(() => {
          if (!cancelled) setViewPreviewLoading(false);
        });
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [activeTab, onDryRunView, type, viewQuery]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving) return;
    setSaving(true);
    try {
      const parsedFields = JSON.parse(fields || "[]") as FieldSchema[];
      if (!Array.isArray(parsedFields)) throw new Error(t("errors.fields_must_be_array", "Fields must be an array."));

      // When editing an existing collection, sync index SQL so renamed fields
      // keep referencing the correct columns (official parity).
      let syncedIndexes = indexes;
      if (collection && type !== "view") {
        const renames = new Map<string, string>();
        for (const originalField of collection.fields ?? []) {
          const match = parsedFields.find((item) => item.id && originalField.id && item.id === originalField.id);
          if (match && match.name !== originalField.name) {
            renames.set(originalField.name, match.name);
          }
        }
        if (renames.size > 0) {
          syncedIndexes = indexes.map((sql) => renameIndexColumns(sql, renames));
        }
      }

      // Editing an existing collection can drop columns or break clients — show what
      // is about to change before it is applied, like the official confirmation modal.
      if (collection) {
        const changes = collectionChanges(collection, { name: name.trim(), fields: parsedFields, rules }, t);
        if (changes.lines.length > 0) {
          const proceed = await onConfirm({
            title: t("confirm.apply_changes_title", "Apply collection changes"),
            message: changes.lines.join("\n"),
            confirmLabel: t("actions.apply_changes", "Apply changes"),
            danger: changes.destructive
          });
          if (!proceed) return;
        }
      }

      const saved = await onSubmit({
        name: name.trim(),
        type,
        fields: type === "view" ? (collection?.fields ?? []) : parsedFields,
        ...(type === "view" ? {} : { indexes: syncedIndexes }),
        listRule: normalizeRule(rules.listRule),
        viewRule: normalizeRule(rules.viewRule),
        createRule: normalizeRule(rules.createRule),
        updateRule: normalizeRule(rules.updateRule),
        deleteRule: normalizeRule(rules.deleteRule),
        authRule: type === "auth" ? normalizeRule(rules.authRule) : null,
        manageRule: normalizeRule(rules.manageRule),
        ...(type === "view" ? { viewQuery: viewQuery.trim() } : {}),
        ...(type === "auth"
          ? {
              passwordAuth: {
                enabled: passwordEnabled,
                identityFields
              },
              otp: {
                enabled: otpEnabled,
                duration: Number(otpDuration || 180),
                length: Number(otpLength || 8),
                emailTemplate: templates.otp
              },
              mfa: {
                enabled: mfaEnabled,
                duration: Number(mfaDuration || 600),
                rule: normalizeRule(mfaRule)
              },
              authAlert: {
                enabled: authAlertEnabled,
                emailTemplate: templates.authAlert
              },
              authToken: tokenPayload("authToken"),
              passwordResetToken: tokenPayload("passwordResetToken"),
              verificationToken: tokenPayload("verificationToken"),
              emailChangeToken: tokenPayload("emailChangeToken"),
              fileToken: tokenPayload("fileToken"),
              verificationTemplate: templates.verification,
              resetPasswordTemplate: templates.passwordReset,
              confirmEmailChangeTemplate: templates.emailChange,
              oauth2: {
                enabled: oauthEnabled,
                mappedFields: oauthMappedFields,
                providers: oauthProviderNames.map((provider) => ({
                  name: provider,
                  clientId: oauthProviderConfigs[provider]?.clientId?.trim() ?? "",
                  clientSecret: oauthProviderConfigs[provider]?.clientSecret?.trim() ?? "",
                  authURL: oauthProviderConfigs[provider]?.authURL?.trim() ?? "",
                  tokenURL: oauthProviderConfigs[provider]?.tokenURL?.trim() ?? "",
                  userInfoURL: oauthProviderConfigs[provider]?.userInfoURL?.trim() ?? "",
                  displayName: oauthProviderConfigs[provider]?.displayName?.trim() ?? "",
                  scopes: splitScopes(oauthProviderConfigs[provider]?.scopes),
                  pkce: oauthProviderConfigs[provider]?.pkce,
                  extra: oauthProviderConfigs[provider]?.extra ?? {}
                }))
              }
            }
          : {})
      });
      if (saved) setExiting(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  function renderRule(key: RuleKey) {
    const value = rules[key];
    const locked = value === null;
    const isReadOnly = Boolean(collection?.system);
    return (
      <div key={key} className={`rule-field${locked ? " locked" : ""}`}>
        <div className="rule-field-header">
          <span className="rule-field-label">
            {collectionRuleLabel(key, t)}
            {locked && (
              <em className="rule-field-hint">
                {t("collections.superusers_only", "(Superusers only)")}
              </em>
            )}
          </span>
          {!isReadOnly &&
            (locked ? (
              <button
                type="button"
                className="subtle rule-field-toggle"
                onClick={() => setRules({ ...rules, [key]: ruleMemory[key] ?? "" })}
              >
                <Unlock size={14} />
                {t("collections.unlock_rule", "Unlock and set custom rule")}
              </button>
            ) : (
              <button
                type="button"
                className="subtle rule-field-toggle"
                onClick={() => {
                  setRuleMemory({ ...ruleMemory, [key]: value ?? "" });
                  setRules({ ...rules, [key]: null });
                }}
              >
                <Lock size={14} />
                {t("collections.set_superusers_only", "Set superusers only")}
              </button>
            ))}
        </div>
        {locked ? (
          <div className="rule-field-locked" aria-hidden="true">
            <Lock size={15} />
            <span>{t("collections.rule_locked", "Superusers only")}</span>
          </div>
        ) : (
          <CodeEditor
            value={value}
            onChange={(next) => setRules({ ...rules, [key]: next })}
            language="pbrule"
            completions={ruleCompletions}
            placeholder={t(
              "collections.rule_placeholder",
              'Leave empty to grant everyone access, eg. @request.auth.id != ""'
            )}
            disabled={isReadOnly}
            name={key}
            ariaLabel={collectionRuleLabel(key, t)}
          />
        )}
      </div>
    );
  }

  function toggleIdentityField(field: string) {
    setIdentityFields((current) => {
      if (current.includes(field)) {
        return current.filter((item) => item !== field);
      }
      return [...current, field];
    });
  }

  function toggleOauthProvider(name: string) {
    setOauthProviderNames((current) => {
      let next: string[];
      if (current.includes(name)) {
        next = current.filter((item) => item !== name);
      } else {
        next = [...current, name];
      }
      // Official parity: adding the first provider enables OAuth2; removing the
      // last one disables it so the toggle never points at an empty list.
      if (next.length > 0 && !oauthEnabled) setOauthEnabled(true);
      else if (next.length === 0 && oauthEnabled) setOauthEnabled(false);
      return next;
    });
    setOauthProviderConfigs((current) => ({
      ...current,
      [name]: current[name] ?? {
        name,
        clientId: "",
        clientSecret: "",
        authURL: "",
        tokenURL: "",
        userInfoURL: "",
        scopes: []
      }
    }));
  }

  function updateOauthProviderConfig(name: string, patch: Partial<OAuth2ProviderConfig>) {
    setOauthProviderConfigs((current) => ({
      ...current,
      [name]: {
        clientId: "",
        clientSecret: "",
        authURL: "",
        tokenURL: "",
        userInfoURL: "",
        scopes: [],
        ...current[name],
        ...patch
      }
    }));
  }

  function updateTemplate(
    key: "verification" | "passwordReset" | "emailChange" | "otp" | "authAlert",
    patch: Partial<EmailTemplate>
  ) {
    setTemplates((current) => ({ ...current, [key]: { ...current[key], ...patch } }));
  }

  function updateTokenDraft(
    key: "authToken" | "passwordResetToken" | "verificationToken" | "emailChangeToken" | "fileToken",
    patch: Partial<{ duration: string; rotate: boolean }>
  ) {
    setTokenDrafts((current) => ({ ...current, [key]: { ...current[key], ...patch } }));
  }

  function tokenPayload(key: "authToken" | "passwordResetToken" | "verificationToken" | "emailChangeToken" | "fileToken"): TokenConfig {
    const draft = tokenDrafts[key];
    return {
      duration: Math.max(1, Number(draft.duration) || 1),
      ...(draft.rotate ? { secret: randomTokenSecret() } : {})
    };
  }

  async function requestTokenRotation(key: "authToken" | "passwordResetToken" | "verificationToken" | "emailChangeToken" | "fileToken") {
    const confirmed = await onConfirm({
      title: t("parity.collection.rotate_token_title", "Invalidate previously issued tokens"),
      message: t(
        "parity.collection.rotate_token_body",
        "Saving this collection will rotate the signing secret for this token type. Existing tokens of this type will stop working."
      ),
      confirmLabel: t("parity.collection.rotate_token_action", "Invalidate tokens"),
      danger: true
    });
    if (confirmed) updateTokenDraft(key, { rotate: true });
  }

  const fieldsPreview = useMemo(() => parseFieldsPreview(fields, t), [fields, t]);
  // Official identity candidates: email plus any field backed by a single-column
  // unique index. Stays in sync as fields and indexes change.
  const identityFieldCandidates = useMemo(() => {
    const candidates = new Set<string>(["email"]);
    for (const raw of indexes) {
      const parsed = parseIndex(raw);
      if (parsed.unique && parsed.columns.length === 1) {
        candidates.add(parsed.columns[0]);
      }
    }
    return [...candidates].filter((name) => fieldsPreview.fields.some((field) => field.name === name));
  }, [indexes, fieldsPreview.fields]);

  function updateFields(nextFields: FieldSchema[]) {
    setFields(JSON.stringify(nextFields, null, 2));
  }

  function addField(fieldType: string) {
    if (fieldsPreview.error) {
      setError(fieldsPreview.error);
      return;
    }
    const current = fieldsPreview.fields;
    const fieldName = uniqueFieldName(current, fieldType);
    const newField = {
      name: fieldName,
      type: fieldType,
      required: false,
      unique: false,
      hidden: false,
      system: false
    };
    // Insert before trailing autodate fields (created/updated), like the official editor.
    let insertAt = current.length;
    while (insertAt > 0 && current[insertAt - 1].type === "autodate") insertAt--;
    const next = [...current];
    next.splice(insertAt, 0, newField);
    updateFields(next);
  }

  function removeField(index: number) {
    if (fieldsPreview.error) {
      setError(fieldsPreview.error);
      return;
    }
    const removed = fieldsPreview.fields[index];
    if (!removed) return;
    // An existing field's deletion is destructive only once the collection is
    // saved. Keep it recoverable in this modal, matching the official staged
    // delete flow. A brand-new field has no persisted data and can disappear
    // immediately.
    if (removed.id) {
      setPendingDeletedFields((current) =>
        current.some((item) => item.field.id === removed.id)
          ? current
          : [...current, { field: removed, index }]
      );
    }
    updateFields(fieldsPreview.fields.filter((_, currentIndex) => currentIndex !== index));
  }

  function restoreDeletedField(deleted: PendingDeletedField) {
    if (fieldsPreview.error || fieldsPreview.fields.some((field) => field.name === deleted.field.name)) return;
    const nextFields = [...fieldsPreview.fields];
    nextFields.splice(Math.min(deleted.index, nextFields.length), 0, deleted.field);
    updateFields(nextFields);
    setPendingDeletedFields((current) => current.filter((item) => item.field.id !== deleted.field.id));
  }

  function duplicateField(index: number) {
    if (fieldsPreview.error) {
      setError(fieldsPreview.error);
      return;
    }
    const source = fieldsPreview.fields[index];
    if (!source) return;
    const base = `${source.name}_copy`.replace(/[^A-Za-z0-9_]/g, "_").slice(0, 63) || "field_copy";
    const existing = new Set(fieldsPreview.fields.map((field) => field.name));
    let copyName = base;
    if (existing.has(copyName)) {
      let n = 2;
      while (existing.has(`${base}_${n}`)) n++;
      copyName = `${base}_${n}`;
    }
    // A duplicate is a fresh field: no persisted id, never a system field.
    const copy: FieldSchema = { ...source, id: undefined, system: false, name: copyName };
    const next = [...fieldsPreview.fields];
    next.splice(index + 1, 0, copy);
    updateFields(next);
  }

  function updateFieldAt(index: number, updatedField: FieldSchema) {
    if (fieldsPreview.error) {
      setError(fieldsPreview.error);
      return;
    }
    const nextFields = [...fieldsPreview.fields];
    nextFields[index] = updatedField;
    updateFields(nextFields);
  }

  function moveField(from: number, to: number) {
    if (fieldsPreview.error) {
      setError(fieldsPreview.error);
      return;
    }
    const nextFields = [...fieldsPreview.fields];
    const [moved] = nextFields.splice(from, 1);
    if (!moved) return;
    nextFields.splice(to, 0, moved);
    updateFields(nextFields);
  }

  return (
    <Modal
      title={
        state.mode === "edit"
          ? t("collections.edit_collection_title", { name: collection?.name, defaultValue: "Edit {{name}}" })
          : t("actions.new_collection", "New collection")
      }
      variant="drawer"
      wide
      exiting={exiting}
      onClose={requestClose}
      onExitComplete={onClose}
    >
      <form className="modal-grid collection-upsert-form" onSubmit={submit}>
        <section className="collection-modal-head">
          <div className="collection-name-field">
            <label>
              {t("common.name", "Name")}{collection?.system ? ` (${t("collections.system", "system")})` : ""}
              <input
                value={name}
                onChange={(event) => setName(event.target.value.replace(/\s+/g, "_").replace(/[^A-Za-z0-9_]/g, ""))}
                required
                pattern="[A-Za-z_][A-Za-z0-9_]{0,62}"
                placeholder="posts"
                disabled={Boolean(collection?.system)}
              />
            </label>
          </div>
          <div className="collection-type-switch" aria-label={t("collections.collection_type", "Collection type")}>
            {[
              { id: "base", label: t("collections.type_base", "Base"), icon: Database },
              { id: "view", label: t("collections.type_view", "View"), icon: Code2 },
              { id: "auth", label: t("collections.type_auth", "Auth"), icon: Users }
            ].map((option) => {
              const Icon = option.icon;
              return (
                <button
                  type="button"
                  key={option.id}
                  className={type === option.id ? "active" : ""}
                  disabled={Boolean(collection)}
                  onClick={() => setType(option.id)}
                >
                  <Icon size={15} />
                  {option.label}
                </button>
              );
            })}
          </div>
        </section>

        <nav className="collection-modal-tabs" aria-label={t("collections.editor_tabs", "Collection editor tabs")}>
          {tabs.map((tab) => (
            <button
              key={tab.id}
              type="button"
              className={activeTab === tab.id ? "active" : ""}
              onClick={() => setActiveTab(tab.id)}
            >
              {tab.label}
              {tab.id === "fields" && fieldsPreview.error && <span className="tab-error-dot" aria-hidden="true" />}
            </button>
          ))}
        </nav>

        {activeTab === "fields" && (
          <section className="field-builder-panel collection-tab-panel">
          <header>
            <div>
              <strong>{t("collections.fields", "Fields")}</strong>
              <span>
                {fieldsPreview.error
                  ? t("collections.invalid_fields_json", "Invalid fields JSON")
                  : t("collections.configured_fields", { count: fieldsPreview.fields.length, defaultValue: "{{count}} configured fields" })}
              </span>
            </div>
            <div className="field-builder-actions">
              {["text", "number", "bool", "email", "file", "json", "relation"].map((fieldType) => (
                <button className="subtle" type="button" key={fieldType} onClick={() => addField(fieldType)}>
                  <Plus size={14} />
                  {fieldType}
                </button>
              ))}
            </div>
          </header>
          {fieldsPreview.error ? (
            <p className="form-error">{fieldsPreview.error}</p>
          ) : (
            <div className="field-builder-list">
              {fieldsPreview.fields.length === 0 ? (
                <p className="sidebar-empty">{t("collections.no_fields_configured", "No fields configured")}</p>
              ) : (
                fieldsPreview.fields.map((field, index) => (
                  <div
                    key={`${field.name}-${index}`}
                    className={`field-drag-row${dragIndex === index ? " dragging" : ""}${dragOverIndex === index && dragIndex !== index ? " drop-target" : ""}`}
                    draggable={dragArmed}
                    onDragStart={() => setDragIndex(index)}
                    onDragOver={(event) => {
                      event.preventDefault();
                      setDragOverIndex(index);
                    }}
                    onDrop={(event) => {
                      event.preventDefault();
                      if (dragIndex !== null && dragIndex !== index) moveField(dragIndex, index);
                      setDragIndex(null);
                      setDragOverIndex(null);
                      setDragArmed(false);
                    }}
                    onDragEnd={() => {
                      setDragIndex(null);
                      setDragOverIndex(null);
                      setDragArmed(false);
                    }}
                  >
                    <span
                      className="field-drag-handle"
                      // Arm dragging only from the handle so text selection inside the
                      // expanded editor keeps working.
                      onMouseDown={() => setDragArmed(true)}
                      onMouseUp={() => setDragArmed(false)}
                      title={t("collections.drag_to_reorder", "Drag to reorder")}
                      aria-hidden="true"
                    >
                      <GripVertical size={14} />
                    </span>
                    <FieldEditor
                      field={field}
                      index={index}
                      collections={allCollections}
                      onUpdate={updateFieldAt}
                      onRemove={removeField}
                      onDuplicate={duplicateField}
                    />
                  </div>
                ))
              )}
            </div>
          )}
          {pendingDeletedFields.length > 0 && !fieldsPreview.error && (
            <aside className="field-deletion-queue" aria-label={t("actions.remove", "Remove")}>
              {pendingDeletedFields.map((deleted) => {
                const nameConflict = fieldsPreview.fields.some((field) => field.name === deleted.field.name);
                return (
                  <div className="field-deletion-queue-item" key={deleted.field.id || deleted.field.name}>
                    <span>
                      <strong>{deleted.field.name}</strong>
                      <em>{deleted.field.type}</em>
                    </span>
                    <button
                      type="button"
                      className="subtle compact"
                      disabled={nameConflict}
                      onClick={() => restoreDeletedField(deleted)}
                      title={t("actions.restore", "Restore")}
                    >
                      <RotateCcw size={14} />
                      {t("actions.restore", "Restore")}
                    </button>
                  </div>
                );
              })}
            </aside>
          )}
          <label>
            {t("collections.fields_json", "Fields JSON")}
            <textarea value={fields} onChange={(event) => setFields(event.target.value)} spellCheck={false} />
          </label>
          <IndexManager
            indexes={indexes}
            collectionName={name || collection?.name || ""}
            fieldNames={fieldsPreview.fields.map((field) => field.name)}
            disabled={Boolean(collection?.system)}
            onChange={setIndexes}
          />
          </section>
        )}

        {activeTab === "query" && (
          <section className="collection-query-panel collection-tab-panel">
            <label>
              {t("collections.view_query", "View query")}
              <CodeEditor
                value={viewQuery}
                onChange={setViewQuery}
                language="sql"
                completions={viewQueryCompletions}
                placeholder="select id, created, updated from posts"
                name="viewQuery"
                ariaLabel={t("collections.view_query", "View query")}
                minHeight={120}
              />
            </label>
            <div className="table-meta">
              <span>{t("parity.collection.view_preview", "Live query preview")}</span>
              {viewPreviewLoading && <span>{t("common.loading", "Loading...")}</span>}
              {viewPreview && <span>{t("parity.collection.view_preview_rows", { count: viewPreview.sample.length, defaultValue: "{{count}} sample row(s)" })}</span>}
            </div>
            {viewPreviewError && <p className="form-error">{viewPreviewError}</p>}
            {viewPreview && (
              <div className="table-wrap">
                <table className="sql-table">
                  <thead>
                    <tr>
                      {viewPreview.fields.map((field) => <th key={field.name}>{field.name}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {viewPreview.sample.length === 0 ? (
                      <tr>
                        <td className="empty-row" colSpan={Math.max(1, viewPreview.fields.length)}>
                          {t("parity.collection.view_preview_empty", "The query is valid but returned no sample rows.")}
                        </td>
                      </tr>
                    ) : (
                      viewPreview.sample.map((row, index) => (
                        <tr key={String(row.id ?? index)}>
                          {viewPreview.fields.map((field) => <td key={field.name}><code>{formatValue(row[field.name])}</code></td>)}
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        )}

        {activeTab === "auth" && type === "auth" && (
          <section className="auth-config-grid collection-tab-panel">
            <article className="auth-config-card">
              <header>
                <strong>{t("collections.password_auth", "Password auth")}</strong>
              </header>
              <Switch
                checked={passwordEnabled}
                onChange={(checked) => setPasswordEnabled(checked)}
                label={t("common.enabled", "Enabled")}
              />
              <div className="stacked-checks">
                {identityFieldCandidates.map((fieldName) => (
                  <label className="check-row" key={fieldName}>
                    <input
                      type="checkbox"
                      checked={identityFields.includes(fieldName)}
                      onChange={() => toggleIdentityField(fieldName)}
                    />
                    {t("collections.field_identity", { name: fieldName, defaultValue: "{{name}} identity" })}
                  </label>
                ))}
                {identityFieldCandidates.length === 0 && (
                  <span className="sidebar-empty">{t("collections.no_identity_fields", "No eligible fields (add a unique index to a field)")}</span>
                )}
              </div>
            </article>

            <article className="auth-config-card">
              <header>
                <strong>{t("auth.otp")}</strong>
              </header>
              <Switch
                checked={otpEnabled}
                onChange={(checked) => setOtpEnabled(checked)}
                label={t("common.enabled", "Enabled")}
              />
              <div className="two-col compact">
                <label>
                  {t("collections.duration_seconds", "Duration (s)")}
                  <input
                    type="number"
                    min={1}
                    value={otpDuration}
                    onChange={(event) => setOtpDuration(event.target.value)}
                  />
                </label>
                <label>
                  {t("collections.length", "Length")}
                  <input
                    type="number"
                    min={4}
                    max={12}
                    value={otpLength}
                    onChange={(event) => setOtpLength(event.target.value)}
                  />
                </label>
              </div>
            </article>

            <article className="auth-config-card">
              <header>
                <strong>{t("auth.mfa")}</strong>
              </header>
              <Switch
                checked={mfaEnabled}
                onChange={(checked) => setMfaEnabled(checked)}
                label={t("common.enabled", "Enabled")}
              />
              <label>
                {t("collections.duration_seconds", "Duration (s)")}
                <input
                  type="number"
                  min={1}
                  value={mfaDuration}
                  onChange={(event) => setMfaDuration(event.target.value)}
                />
              </label>
              <label>
                {t("parity.collection.mfa_rule", "MFA rule")}
                <CodeEditor
                  value={mfaRule}
                  onChange={setMfaRule}
                  language="pbrule"
                  completions={ruleCompletions}
                  placeholder={t("parity.collection.mfa_rule_placeholder", "Leave empty to require MFA from every account")}
                  name="mfaRule"
                  ariaLabel={t("parity.collection.mfa_rule", "MFA rule")}
                  minHeight={72}
                />
              </label>
            </article>

            <article className="auth-config-card">
              <header>
                <strong>{t("parity.collection.auth_alert", "Login alert")}</strong>
              </header>
              <Switch
                checked={authAlertEnabled}
                onChange={(checked) => setAuthAlertEnabled(checked)}
                label={t("parity.collection.auth_alert_enabled", "Send an email when a new login origin is detected")}
              />
              <p className="field-option-help">{t("parity.collection.auth_alert_help", "Edit the login alert email in the Templates tab.")}</p>
            </article>

            <article className="auth-config-card auth-config-card-wide">
              <header>
                <strong>{t("settings.oauth2")}</strong>
              </header>
              <Switch
                checked={oauthEnabled}
                onChange={(checked) => setOauthEnabled(checked)}
                label={t("common.enabled", "Enabled")}
              />
              {oauthEnabled && oauthProviderNames.length === 0 && (
                <p className="field-option-help" style={{ color: "var(--dangerColor)" }}>
                  {t("collections.oauth_no_providers_warning", "OAuth2 is enabled but no providers are configured. Select at least one provider below.")}
                </p>
              )}
              <div className="two-col oauth-provider-fields">
                {([
                  ["id", t("collections.oauth_provider_id_field", "Provider ID field")],
                  ["name", t("collections.oauth_name_field", "Name field")],
                  ["username", t("collections.oauth_username_field", "Username field")],
                  ["avatarURL", t("collections.oauth_avatar_field", "Avatar field")]
                ] as const).map(([mapping, label]) => (
                  <label key={mapping}>
                    {label}
                    <select
                      value={oauthMappedFields[mapping] ?? ""}
                      onChange={(event) => setOauthMappedFields((current) => ({ ...current, [mapping]: event.target.value }))}
                    >
                      <option value="">{t("common.none", "None")}</option>
                      {fieldsPreview.fields.map((field) => (
                        <option value={field.name} key={`${mapping}-${field.name}`}>{field.name}</option>
                      ))}
                    </select>
                  </label>
                ))}
              </div>
              <div className="provider-option-grid">
                {oauthProviders.map((provider) => (
                  <label className="check-row" key={provider.name}>
                    <input
                      type="checkbox"
                      checked={oauthProviderNames.includes(provider.name)}
                      onChange={() => toggleOauthProvider(provider.name)}
                    />
                    {provider.displayName}
                  </label>
                ))}
              </div>
              {oauthProviderNames.length > 0 && (
                <div className="oauth-provider-config-list">
                  {oauthProviderNames.map((providerName) => {
                    const config = oauthProviderConfigs[providerName] ?? {
                      name: providerName,
                      clientId: "",
                      clientSecret: "",
                      authURL: "",
                      tokenURL: "",
                      userInfoURL: "",
                      scopes: []
                    };
                    return (
                      <article className="oauth-provider-config-card" key={providerName}>
                        <header>
                          <strong>{oauthProviders.find((provider) => provider.name === providerName)?.displayName ?? providerName}</strong>
                        </header>
                        <div className="two-col oauth-provider-fields">
                          <label>
                            {t("settings.client_id", "Client ID")}
                            <input
                              value={config.clientId ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { clientId: event.target.value })}
                            />
                          </label>
                          <label>
                            {t("settings.client_secret", "Client Secret")}
                            <input
                              value={config.clientSecret ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { clientSecret: event.target.value })}
                            />
                          </label>
                        </div>
                        <div className="two-col oauth-provider-fields">
                          <label>
                            {t("settings.auth_url", "Auth URL")}
                            <input
                              value={config.authURL ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { authURL: event.target.value })}
                            />
                          </label>
                          <label>
                            {t("settings.token_url", "Token URL")}
                            <input
                              value={config.tokenURL ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { tokenURL: event.target.value })}
                            />
                          </label>
                        </div>
                        <label>
                          {t("settings.user_info_url", "User Info URL")}
                          <input
                            value={config.userInfoURL ?? ""}
                            onChange={(event) => updateOauthProviderConfig(providerName, { userInfoURL: event.target.value })}
                          />
                        </label>
                        <div className="two-col oauth-provider-fields">
                          <label>
                            {t("settings.scopes", "Scopes")}
                            <input
                              value={Array.isArray(config.scopes) ? config.scopes.join(", ") : ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { scopes: splitScopes(event.target.value) })}
                              placeholder="openid, email, profile"
                            />
                          </label>
                          <label className="check-row oauth-pkce-toggle">
                            <input
                              type="checkbox"
                              checked={config.pkce ?? defaultProviderPkce(providerName)}
                              onChange={(event) => updateOauthProviderConfig(providerName, { pkce: event.target.checked })}
                            />
                            PKCE
                          </label>
                        </div>
                        {providerName === "apple" && (
                          <AppleClientSecretAssistant
                            clientId={config.clientId ?? ""}
                            onGenerate={onGenerateAppleClientSecret}
                            onApplySecret={(clientSecret) => updateOauthProviderConfig(providerName, { clientSecret })}
                          />
                        )}
                        {providerName === "oidc" && (
                          <OidcDiscoveryAssistant
                            config={config}
                            onApply={(patch) => updateOauthProviderConfig(providerName, patch)}
                          />
                        )}
                      </article>
                    );
                  })}
                </div>
              )}
            </article>
          </section>
        )}

        {activeTab === "templates" && type === "auth" && (
          <section className="auth-config-grid collection-tab-panel">
            {([
              ["verification", t("parity.collection.template_verification", "Verification email")],
              ["passwordReset", t("parity.collection.template_password_reset", "Password reset email")],
              ["emailChange", t("parity.collection.template_email_change", "Confirm email change")],
              ["otp", t("parity.collection.template_otp", "One-time password")],
              ["authAlert", t("parity.collection.template_auth_alert", "New login alert")]
            ] as const).map(([key, label]) => {
              const template = templates[key];
              return (
                <article className="auth-config-card auth-config-card-wide" key={key}>
                  <header>
                    <strong>{label}</strong>
                  </header>
                  <label>
                    {t("parity.collection.template_subject", "Subject")}
                    <input
                      value={template.subject ?? ""}
                      onChange={(event) => updateTemplate(key, { subject: event.target.value })}
                      placeholder={t("parity.collection.template_subject_placeholder", "Use {APP_NAME} for the application name")}
                    />
                  </label>
                  <label>
                    {t("parity.collection.template_body", "HTML body")}
                    <textarea
                      value={template.body ?? ""}
                      onChange={(event) => updateTemplate(key, { body: event.target.value })}
                      rows={8}
                      spellCheck={false}
                    />
                  </label>
                  <p className="field-option-help">{t("parity.collection.template_help", "Available placeholders include {APP_NAME}, {APP_URL}, {TOKEN}, and action-specific values.")}</p>
                </article>
              );
            })}
          </section>
        )}

        {activeTab === "tokens" && type === "auth" && (
          <section className="auth-config-grid collection-tab-panel">
            {([
              ["authToken", t("parity.collection.auth_token", "Auth token"), t("parity.collection.auth_token_help", "Used for signed-in auth records.")],
              ["passwordResetToken", t("parity.collection.password_reset_token", "Password reset token"), t("parity.collection.password_reset_token_help", "Used by password reset links.")],
              ["verificationToken", t("parity.collection.verification_token", "Verification token"), t("parity.collection.verification_token_help", "Used by email verification links.")],
              ["emailChangeToken", t("parity.collection.email_change_token", "Email change token"), t("parity.collection.email_change_token_help", "Used by email change confirmation links.")],
              ["fileToken", t("parity.collection.file_token", "File token"), t("parity.collection.file_token_help", "Used for time-limited protected file URLs.")]
            ] as const).map(([key, label, help]) => {
              const draft = tokenDrafts[key];
              return (
                <article className="auth-config-card" key={key}>
                  <header>
                    <div>
                      <strong>{label}</strong>
                      <span>{help}</span>
                    </div>
                  </header>
                  <label>
                    {t("parity.collection.token_duration", "Duration (seconds)")}
                    <input
                      type="number"
                      min={1}
                      value={draft.duration}
                      onChange={(event) => updateTokenDraft(key, { duration: event.target.value })}
                    />
                  </label>
                  <button
                    type="button"
                    className={draft.rotate ? "subtle danger" : "subtle"}
                    onClick={() => {
                      if (draft.rotate) updateTokenDraft(key, { rotate: false });
                      else void requestTokenRotation(key);
                    }}
                  >
                    <RotateCcw size={15} />
                    {draft.rotate
                      ? t("parity.collection.token_rotation_pending", "Will invalidate on save")
                      : t("parity.collection.rotate_token_action", "Invalidate tokens")}
                  </button>
                </article>
              );
            })}
          </section>
        )}

        {activeTab === "rules" && (
          <section className="collection-rules-panel collection-tab-panel">            <div className="rules-helper">
              <div>
                <strong>{t("collections.available_fields", "Available fields")}</strong>
                <div className="chips">
                  {fieldsPreview.fields.length === 0 ? (
                    <span>id</span>
                  ) : (
                    fieldsPreview.fields.map((field) => <span key={field.name}>{field.name}</span>)
                  )}
                  <span>created</span>
                  <span>updated</span>
                </div>
              </div>
              <div>
                <strong>{t("collections.request_fields", "Request fields")}</strong>
                <div className="chips">
                  {["@request.auth.*", "@request.body.*", "@request.query.*", "@collection.*"].map((item) => (
                    <span key={item}>{item}</span>
                  ))}
                </div>
              </div>
            </div>
            <div className="rules-grid official">
              {collectionRuleKeys(type)
                .filter((key) => key !== "authRule" && key !== "manageRule")
                .map((key) => renderRule(key))}
            </div>
            {type === "auth" && (
              <details className="auth-rules-collapse">
                <summary>{t("collections.auth_manage_rules", "Auth & manage rules")}</summary>
                <div className="rules-grid official">
                  {(["authRule", "manageRule"] as RuleKey[]).map((key) => renderRule(key))}
                </div>
              </details>
            )}
          </section>
        )}
        {error && <p className="form-error">{error}</p>}
        <div className="modal-actions">
          {collection && (
            <>
              <CopyButton
                value={collection ? JSON.stringify(collection, null, 2) : ""}
                label={t("actions.copy_json", "Copy JSON")}
                onError={(error) => onNotify(errorMessage(error), "error")}
              />
              <button type="button" className="subtle" onClick={onDuplicate} disabled={exiting}>
                <Copy size={16} />
                {t("parity.collection.duplicate", "Duplicate")}
              </button>
              <button type="button" className="danger subtle" onClick={onTruncate} disabled={exiting || Boolean(collection.system)}>
                <Archive size={16} />
                {t("parity.collection.truncate", "Truncate")}
              </button>
              <button type="button" className="danger subtle" onClick={onDelete} disabled={exiting || Boolean(collection.system)}>
                <Trash2 size={16} />
                {t("actions.delete", "Delete")}
              </button>
              <span className="modal-actions-spacer" />
            </>
          )}
          <button type="button" className="subtle" onClick={requestClose} disabled={exiting}>
            <X size={16} />
            {t("actions.cancel", "Cancel")}
          </button>
          <button className="primary" type="submit" disabled={saving || exiting}>
            <Save size={16} />
            {saving ? t("common.submitting", "Submitting...") : t("actions.save", "Save")}
          </button>
        </div>
      </form>
    </Modal>
  );
}

type RecordModalProps = {
  collection: CollectionSchema;
  collections: CollectionSchema[];
  state: RecordEditorState;
  hideControls: boolean;
  onClose: () => void;
  onConfirm: (request: ConfirmRequest) => Promise<boolean>;
  fetchRecords: RelationFetcher;
  getFileToken: () => Promise<string>;
  onRequestVerification: () => Promise<void>;
  onRequestPasswordReset: () => Promise<void>;
  onImpersonate: (duration: number) => Promise<ImpersonationResult>;
  onLoadExternalAuths: () => Promise<AuthRecordLink[]>;
  onUnlinkExternalAuth: (link: AuthRecordLink) => Promise<void>;
  onDuplicate: () => void;
  onCreateRelationRecord?: (target: RelationCollection, onSaved: (record: RelationRecord) => void) => void;
  onEditRelationRecord?: (target: RelationCollection, id: string, onSaved: (record: RelationRecord) => void) => void;
  onNotify: (message: string, kind?: "ok" | "error") => void;
  onSubmit: (
    payload: Record<string, unknown>,
    files: Record<string, File[]>,
    options?: { close?: boolean }
  ) => Promise<void> | void;
};

function RecordModal({
  collection,
  collections,
  state,
  hideControls,
  onClose,
  onConfirm,
  fetchRecords,
  getFileToken,
  onRequestVerification,
  onRequestPasswordReset,
  onImpersonate,
  onLoadExternalAuths,
  onUnlinkExternalAuth,
  onDuplicate,
  onCreateRelationRecord,
  onEditRelationRecord,
  onNotify,
  onSubmit
}: RecordModalProps) {
  const { t } = useTranslation();
  const fileFields = (collection.fields ?? []).filter((field) => field.type === "file" && !field.hidden);
  const ordinaryEditableFields = (collection.fields ?? []).filter(
    (field) => field.type !== "file" && !field.hidden && !field.system
  );
  const authVisibleFields =
    collection.type === "auth"
      ? (collection.fields ?? []).filter((field) => ["email", "emailVisibility", "verified"].includes(field.name))
      : [];
  const passwordField =
    collection.type === "auth" ? (collection.fields ?? []).find((field) => field.name === "password") : undefined;
  const duplicating = state.mode === "duplicate";
  const editing = Boolean(state.record) && !duplicating;
  // Creating a new record lets the user supply a custom id (leave empty for autogenerate).
  const idFieldSchema: FieldSchema = { id: "id", name: "id", type: "text", system: true };
  const editableFields = editing
    ? [...authVisibleFields, ...ordinaryEditableFields]
    : [idFieldSchema, ...authVisibleFields, ...ordinaryEditableFields];
  const readOnly = collection.type === "view";
  const initialPayload = useMemo(
    () => (duplicating ? duplicateRecordPayload(collection, state.record) : recordEditorPayload(collection, state.record)),
    [collection, duplicating, state.record]
  );
  const draftKey = state.draftKey
    ? `${state.draftKey}${duplicating ? "_duplicate" : ""}`
    : `pbj_record_draft_${collection.id || collection.name}_${duplicating ? `duplicate_${state.record?.id || "new"}` : state.record?.id || "new"}`;
  const [basePayload, setBasePayload] = useState<Record<string, unknown>>(() => initialPayload);
  const [payload, setPayload] = useState<Record<string, unknown>>(() => initialPayload);
  const [json, setJson] = useState(JSON.stringify(initialPayload, null, 2));
  const [initialDraft, setInitialDraft] = useState<Record<string, unknown> | null>(() => readRecordDraft(draftKey));
  const [activeTab, setActiveTab] = useState<"main" | "providers" | "actions">("main");
  const [files, setFiles] = useState<Record<string, File[]>>({});
  const [fileRemovals, setFileRemovals] = useState<Record<string, string[]>>({});
  const [fileToken, setFileToken] = useState("");
  // A duplicated auth record is a new account, so it must collect a fresh
  // password instead of inheriting the source record's edit-only state.
  const [changePassword, setChangePassword] = useState(() => Boolean(passwordField) && (!state.record || duplicating));
  const [passwordValue, setPasswordValue] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [invalidJsonFields, setInvalidJsonFields] = useState<Record<string, true>>({});
  const [invalidRecordJson, setInvalidRecordJson] = useState(false);
  const [jsonFieldResetVersion, setJsonFieldResetVersion] = useState(0);
  const [saving, setSaving] = useState(false);
  // Match PocketBase's safety mode: a new record remains creatable, while an
  // existing record can be inspected/edited but requires an explicit unlock
  // before any change can be submitted.
  const [locked, setLocked] = useState(() => editing && !readOnly && hideControls);
  const [exiting, setExiting] = useState(false);
  const showTabs = !duplicating && Boolean(state.record?.id) && collection.type === "auth" && collection.name !== "_superusers";
  const invalidJsonFieldNames = Object.keys(invalidJsonFields);
  const hasInvalidJsonFields = invalidJsonFieldNames.length > 0;
  const hasInvalidJson = hasInvalidJsonFields || invalidRecordJson;
  const changed =
    JSON.stringify(payload) !== JSON.stringify(basePayload) ||
    Object.values(files).some((items) => items.length > 0) ||
    Object.values(fileRemovals).some((items) => items.length > 0) ||
    hasInvalidJson;
  const canSubmit = !readOnly && !locked && !saving && !hasInvalidJson && (!editing || changed);
  const exportRecord = useMemo(
    () => sanitizeRecordForExport(readOnly && state.record ? state.record : payload),
    [payload, readOnly, state.record]
  );
  const exportJson = useMemo(() => JSON.stringify(exportRecord, null, 2), [exportRecord]);

  useEffect(() => {
    if (!changed) return;
    localStorage.setItem(draftKey, JSON.stringify(sanitizeRecordForExport(payload)));
  }, [changed, draftKey, payload]);

  useEffect(() => {
    if (!showTabs && activeTab !== "main") setActiveTab("main");
  }, [activeTab, showTabs]);

  useEffect(() => {
    if (readOnly || duplicating || !state.record?.id || fileFields.length === 0) return;
    let cancelled = false;
    getFileToken()
      .then((nextToken) => {
        if (!cancelled) setFileToken(nextToken);
      })
      .catch(() => {
        // A regular file URL still works for public file fields. The explicit
        // token is only needed by the browser's media elements for protected ones.
      });
    return () => {
      cancelled = true;
    };
  }, [duplicating, fileFields.length, getFileToken, readOnly, state.record?.id]);

  async function requestClose() {
    if (exiting) return;
    if (changed) {
      const discard = await onConfirm({
        title: t("confirm.discard_changes_title", "Discard changes"),
        message: t(
          "confirm.discard_changes_body",
          "You have unsaved changes. Do you really want to discard them?"
        ),
        confirmLabel: t("actions.discard", "Discard"),
        danger: true
      });
      if (!discard) return;
      localStorage.removeItem(draftKey);
    }
    setExiting(true);
  }

  function updatePayload(field: FieldSchema, value: unknown) {
    setPayload((current) => {
      const next = { ...current, [field.name]: value };
      setJson(JSON.stringify(next, null, 2));
      return next;
    });
    setInvalidRecordJson(false);
    setError("");
    // Editing a field clears its own error, so stale markers don't linger.
    setFieldErrors((current) => {
      if (!current[field.name]) return current;
      const next = { ...current };
      delete next[field.name];
      return next;
    });
  }

  const updateJsonFieldValidity = useCallback((fieldName: string, valid: boolean) => {
    setInvalidJsonFields((current) => {
      const alreadyInvalid = Boolean(current[fieldName]);
      if (valid) {
        if (!alreadyInvalid) return current;
        const next = { ...current };
        delete next[fieldName];
        return next;
      }
      if (alreadyInvalid) return current;
      return { ...current, [fieldName]: true };
    });
  }, []);

  async function updateVerified(field: FieldSchema, value: boolean) {
    if (value && state.record && !Boolean(payload[field.name])) {
      const proceed = await onConfirm({
        title: t("parity.confirm.verify_auth_record_title", "Verify email address"),
        message: t(
          "parity.confirm.verify_auth_record_body",
          "Mark this account as verified without the owner completing the email verification link?"
        ),
        confirmLabel: t("actions.verify", "Verify")
      });
      if (!proceed) return;
    }
    updatePayload(field, value);
  }

  function updatePassword(value: string) {
    setPasswordValue(value);
    if (passwordField) updatePayload(passwordField, value);
  }

  function togglePasswordChange(enabled: boolean) {
    setChangePassword(enabled);
    setPasswordValue("");
    setPasswordConfirmation("");
    if (!enabled && passwordField) {
      setPayload((current) => {
        const next = { ...current };
        delete next[passwordField.name];
        setJson(JSON.stringify(next, null, 2));
        return next;
      });
      setInvalidRecordJson(false);
    }
  }

  function generatePassword() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@$%*";
    const bytes = new Uint32Array(18);
    crypto.getRandomValues(bytes);
    const next = Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join("");
    setPasswordConfirmation(next);
    updatePassword(next);
  }

  function updateJson(value: string) {
    setJson(value);
    try {
      const parsed = JSON.parse(value || "{}") as Record<string, unknown>;
      if (!isPlainObject(parsed)) {
        setInvalidRecordJson(true);
        return;
      }
      setPayload(parsed);
      setInvalidRecordJson(false);
      setError("");
    } catch {
      // Keep the raw JSON text visible, but prevent a submit from silently
      // falling back to the last valid object.
      setInvalidRecordJson(true);
    }
  }

  function restoreDraft() {
    if (!initialDraft) return;
    setPayload(initialDraft);
    setJson(JSON.stringify(initialDraft, null, 2));
    setInitialDraft(null);
    setError("");
    setInvalidJsonFields({});
    setInvalidRecordJson(false);
    setJsonFieldResetVersion((current) => current + 1);
  }

  function discardDraft() {
    localStorage.removeItem(draftKey);
    setInitialDraft(null);
  }

  function resetForm() {
    setPayload(basePayload);
    setJson(JSON.stringify(basePayload, null, 2));
    setFiles({});
    setFileRemovals({});
    localStorage.removeItem(draftKey);
    setInitialDraft(null);
    setError("");
    setInvalidJsonFields({});
    setInvalidRecordJson(false);
    setJsonFieldResetVersion((current) => current + 1);
  }

  async function submit(event: FormEvent<HTMLFormElement> | null, close = true) {
    event?.preventDefault();
    if (saving || readOnly || locked) return;
    if (hasInvalidJson) {
      const message = t("errors.invalid_json", "Enter valid JSON before saving.");
      setError(message);
      if (hasInvalidJsonFields) {
        setFieldErrors((current) => ({
          ...current,
          ...Object.fromEntries(invalidJsonFieldNames.map((fieldName) => [fieldName, message]))
        }));
      }
      return;
    }
    setSaving(true);
    try {
      const parsedPayload = JSON.parse(json || "{}") as Record<string, unknown>;
      if (!isPlainObject(parsedPayload)) throw new Error(t("errors.record_payload_object", "Record payload must be an object."));
      const requestPayload = { ...parsedPayload };
      // An empty custom id means "let the server autogenerate" — don't send it.
      if (requestPayload.id === "") delete requestPayload.id;
      if (passwordField && changePassword && passwordValue !== passwordConfirmation) {
        throw new Error(t("parity.errors.password_confirmation_mismatch", "Password confirmation does not match."));
      }
      if (passwordField && changePassword) {
        requestPayload.passwordConfirm = passwordConfirmation;
      }
      if (passwordField && editing && !changePassword) {
        delete requestPayload[passwordField.name];
        delete requestPayload.passwordConfirm;
      }
      for (const [fieldName, names] of Object.entries(fileRemovals)) {
        if (names.length > 0) requestPayload[`${fieldName}-`] = names;
      }
      await onSubmit(requestPayload, files, { close });
      if (!close && !editing) {
        const nextPayload = recordEditorPayload(collection);
        setBasePayload(nextPayload);
        setPayload(nextPayload);
        setJson(JSON.stringify(nextPayload, null, 2));
        setFiles({});
        setFileRemovals({});
        localStorage.removeItem(draftKey);
        setInitialDraft(null);
        setInvalidJsonFields({});
        setInvalidRecordJson(false);
        setJsonFieldResetVersion((current) => current + 1);
        return;
      }
      setBasePayload(parsedPayload);
      setPayload(parsedPayload);
      setFiles({});
      setFileRemovals({});
      localStorage.removeItem(draftKey);
      setInitialDraft(null);
      setInvalidJsonFields({});
      setInvalidRecordJson(false);
      setJsonFieldResetVersion((current) => current + 1);
    } catch (err) {
      setError(errorMessage(err));
      setFieldErrors(fieldErrorsOf(err));
    } finally {
      setSaving(false);
    }
  }

  function downloadRecordJson() {
    const id = state.record?.id || "new";
    downloadJsonFile(exportRecord, `pocketbase-${collection.name}-${id}.json`);
  }

  function handleRecordFormKeyDown(event: ReactKeyboardEvent<HTMLFormElement>) {
    if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "s") return;
    event.preventDefault();
    if (canSubmit) void submit(null, true);
  }

  if (readOnly) {
    return (
      <Modal
        title={t("records.view_record_title", { id: state.record?.id ?? "", defaultValue: "View {{id}}" })}
        onClose={requestClose}
        variant="drawer"
        wide
        exiting={exiting}
        onExitComplete={onClose}
      >
        <section className="record-preview">
          <p>{t("parity.records.view_read_only", "View collection records are read-only.")}</p>
          <pre>{exportJson}</pre>
          <div className="modal-actions record-footer-actions">
            <button type="button" className="subtle" onClick={requestClose} disabled={exiting}>
              <X size={16} />
              {t("actions.close", "Close")}
            </button>
            <span className="modal-actions-spacer" />
            <CopyButton
              value={exportJson}
              label={t("actions.copy_json", "Copy JSON")}
              onError={(error) => onNotify(errorMessage(error), "error")}
            />
            <button type="button" className="primary" onClick={downloadRecordJson}>
              <Download size={16} />
              {t("actions.download_json", "Download as JSON")}
            </button>
          </div>
        </section>
      </Modal>
    );
  }

  return (
    <Modal
      title={
        duplicating
          ? t("parity.records.duplicate_record_title", { id: state.record?.id ?? "", defaultValue: "Duplicate {{id}}" })
          : state.record
          ? t("records.edit_record_title", { id: state.record.id, defaultValue: "Edit {{id}}" })
          : t("records.new_record_title", { name: collection.name, defaultValue: "New {{name}}" })
      }
      onClose={requestClose}
      variant="drawer"
      wide
      exiting={exiting}
      onExitComplete={onClose}
    >
      <form
        className="modal-grid record-upsert-form"
        onSubmit={(event) => submit(event, true)}
        onKeyDown={handleRecordFormKeyDown}
      >
        {initialDraft && (
          <div className="draft-alert">
            <div>
              <strong>{t("records.unsaved_draft", "Unsaved draft")}</strong>
              <span>{t("records.unsaved_draft_desc", "This record has locally saved changes.")}</span>
            </div>
            <button type="button" className="subtle" onClick={restoreDraft}>
              <RotateCcw size={15} />
              {t("actions.restore_draft", "Restore draft")}
            </button>
            <button type="button" className="icon-button" onClick={discardDraft} title={t("actions.discard_draft", "Discard draft")} aria-label={t("actions.discard_draft", "Discard draft")}>
              <X size={15} />
            </button>
          </div>
        )}

        {showTabs && (
          <nav className="record-modal-tabs" aria-label={t("records.editor_tabs", "Record editor tabs")}>
            <button type="button" className={activeTab === "main" ? "active" : ""} onClick={() => setActiveTab("main")}>
              {t("records.account", "Account")}
              {changed && activeTab !== "main" && <span className="tab-dot" />}
            </button>
            <button
              type="button"
              className={activeTab === "providers" ? "active" : ""}
              onClick={() => setActiveTab("providers")}
            >
              {t("records.auth_providers", "Auth providers")}
            </button>
            <button
              type="button"
              className={activeTab === "actions" ? "active" : ""}
              onClick={() => setActiveTab("actions")}
            >
              {t("parity.records.account_actions", "Account actions")}
            </button>
          </nav>
        )}

        {activeTab === "actions" && state.record ? (
          <AuthRecordActions
            record={state.record}
            onConfirm={onConfirm}
            onRequestVerification={onRequestVerification}
            onRequestPasswordReset={onRequestPasswordReset}
            onImpersonate={onImpersonate}
            onLoadLinks={onLoadExternalAuths}
            onUnlink={onUnlinkExternalAuth}
            onNotify={onNotify}
          />
        ) : activeTab === "providers" ? (
          <AuthProvidersPanel collection={collection} record={state.record} />
        ) : (
          <div className="record-editor-layout">
            <section className="record-form-panel">
              <div className="section-heading compact">
                <div>
                  <h2>{collection.type === "auth" ? t("records.account", "Account") : t("collections.fields", "Fields")}</h2>
                  <p>{t("records.editable_fields_count", { count: editableFields.length, defaultValue: "{{count}} editable fields" })}</p>
                </div>
              </div>
              <div className="record-field-grid">
                {editableFields.length === 0 ? (
                  <p className="sidebar-empty">{t("records.no_editable_fields", "No editable fields")}</p>
                ) : (
                  editableFields.map((field) => (
                    <div
                      key={field.name}
                      className={`record-field-slot${fieldErrors[field.name] || invalidJsonFields[field.name] ? " has-error" : ""}`}
                    >
                      <RecordFieldControl
                        field={field}
                        value={payload[field.name]}
                        collections={collections}
                        fetchRecords={fetchRecords}
                        onCreateRelationRecord={onCreateRelationRecord}
                        onEditRelationRecord={onEditRelationRecord}
                        resolveFileUrl={({ collection: fileCollection, recordId, filename, thumb }) => {
                          const query = new URLSearchParams();
                          if (thumb) query.set("thumb", thumb);
                          const suffix = query.toString();
                          return `/api/files/${encodeURIComponent(fileCollection.id || fileCollection.name)}/${encodeURIComponent(recordId)}/${encodeURIComponent(filename)}${suffix ? `?${suffix}` : ""}`;
                        }}
                        onValidityChange={updateJsonFieldValidity}
                        resetVersion={jsonFieldResetVersion}
                        onChange={(value) => {
                          if (field.name === "verified") {
                            void updateVerified(field, Boolean(value));
                          } else {
                            updatePayload(field, value);
                          }
                        }}
                      />
                      {fieldErrors[field.name] && (
                        <p className="record-field-error">{fieldErrors[field.name]}</p>
                      )}
                    </div>
                  ))
                )}
                {passwordField && (
                  <div className="record-field-slot auth-password-slot">
                    {editing && (
                      <label className="check-row">
                        <input
                          type="checkbox"
                          checked={changePassword}
                          onChange={(event) => togglePasswordChange(event.target.checked)}
                        />
                        {t("parity.records.change_password", "Change password")}
                      </label>
                    )}
                    {changePassword && (
                      <div className="record-field-card wide auth-password-fields">
                        <span>
                          <strong>{t("parity.records.new_password", "New password")}</strong>
                          <span className="record-field-meta">{t("parity.records.password_help", "Use a strong password; changing it invalidates existing sessions.")}</span>
                        </span>
                        <div className="auth-password-input-row">
                          <PasswordInput
                            name="password"
                            autoComplete="new-password"
                            value={passwordValue}
                            onChange={(event) => updatePassword(event.target.value)}
                            required={changePassword}
                          />
                          <button type="button" className="subtle compact" onClick={generatePassword}>
                            {t("parity.actions.generate_password", "Generate")}
                          </button>
                        </div>
                        <label>
                          {t("parity.records.confirm_password", "Confirm password")}
                          <PasswordInput
                            name="passwordConfirm"
                            autoComplete="new-password"
                            value={passwordConfirmation}
                            onChange={(event) => setPasswordConfirmation(event.target.value)}
                            required={changePassword}
                          />
                        </label>
                      </div>
                    )}
                  </div>
                )}
              </div>

              {fileFields.length > 0 && (
                <div className="record-file-grid">
                  {fileFields.map((field) => (
                    <FileFieldControl
                      key={field.name}
                      field={field}
                      value={payload[field.name]}
                      files={files[field.name] ?? []}
                      removed={fileRemovals[field.name] ?? []}
                      fileUrl={(filename, thumb) => {
                        const query = new URLSearchParams();
                        if (fileToken) query.set("token", fileToken);
                        if (thumb) query.set("thumb", thumb);
                        const suffix = query.toString();
                        return `/api/files/${encodeURIComponent(collection.id || collection.name)}/${encodeURIComponent(state.record?.id ?? "new")}/${encodeURIComponent(filename)}${suffix ? `?${suffix}` : ""}`;
                      }}
                      onValueChange={(value) => updatePayload(field, value)}
                      onFilesChange={(nextFiles) => setFiles((current) => ({ ...current, [field.name]: nextFiles }))}
                      onRemovedChange={(names) => setFileRemovals((current) => ({ ...current, [field.name]: names }))}
                    />
                  ))}
                </div>
              )}
            </section>

            <section className="record-json-panel">
              <div className="record-json-heading">
                <strong>JSON</strong>
                <div>
                  <CopyButton
                    value={json}
                    variant="compact"
                    label={t("actions.copy_json", "Copy JSON")}
                    onError={(error) => onNotify(errorMessage(error), "error")}
                  />
                  <button type="button" className="subtle compact" onClick={downloadRecordJson}>
                    <Download size={14} />
                    {t("actions.download_json", "Download as JSON")}
                  </button>
                </div>
              </div>
              <CodeEditor
                name={`${collection.name}RecordJson`}
                ariaLabel={t("records.record_json", "Record JSON")}
                value={json}
                onChange={updateJson}
                language="json"
                minHeight={520}
              />
              {invalidRecordJson && (
                <p className="record-json-field-error" role="alert">
                  {t("errors.invalid_json", "Enter valid JSON before saving.")}
                </p>
              )}
            </section>
          </div>
        )}
        {error && <p className="form-error">{error}</p>}
        <div className="modal-actions record-footer-actions">
          <button type="button" className="subtle" onClick={requestClose} disabled={exiting}>
            <X size={16} />
            {t("actions.close", "Close")}
          </button>
          <button type="button" className="subtle" onClick={resetForm} disabled={!changed || saving || exiting}>
            <RotateCcw size={16} />
            {t("actions.reset_form", "Reset form")}
          </button>
          {editing && (
            <button type="button" className="subtle" onClick={onDuplicate} disabled={saving || exiting}>
              <Copy size={16} />
              {t("parity.records.duplicate", "Duplicate")}
            </button>
          )}
          <span className="modal-actions-spacer" />
          {locked ? (
            <button
              type="button"
              className="subtle outline-button"
              onClick={() => setLocked(false)}
              disabled={saving || !changed || exiting}
            >
              <Unlock size={16} />
              {t("parity.records.unlock_to_save", "Unlock to save")}
            </button>
          ) : (
            <>
              <button className="primary" type="submit" disabled={!canSubmit || exiting}>
                <Save size={16} />
                {editing ? t("actions.save_changes", "Save changes") : t("actions.create", "Create")}
              </button>
              {!editing && (
                <button className="subtle" type="button" onClick={() => submit(null, false)} disabled={!canSubmit || exiting}>
                  {t("actions.save_and_continue", "Save and continue")}
                </button>
              )}
            </>
          )}
        </div>
      </form>
    </Modal>
  );
}

function AuthProvidersPanel({ collection, record }: { collection: CollectionSchema; record?: RecordItem }) {
  const { t } = useTranslation();
  const providers = collection.oauth2?.providers ?? [];
  return (
    <section className="auth-providers-panel">
      {providers.length === 0 ? (
        <EmptyState icon={Shield} title={t("records.no_auth_providers", "No auth providers configured")} />
      ) : (
        providers.map((provider) => (
          <article className="auth-provider-row" key={provider.name}>
            <div className="nav-icon">
              <Shield size={16} />
            </div>
            <div>
              <strong>{provider.name}</strong>
              <span>{provider.clientId ? t("common.configured", "configured") : t("settings.missing_credentials", "missing credentials")}</span>
            </div>
            <code>{String(record?.[`${provider.name}Id`] ?? t("records.not_linked", "not linked"))}</code>
          </article>
        ))
      )}
    </section>
  );
}


type ModalProps = {
  title: string;
  onClose: () => void;
  wide?: boolean;
  /** Right-edge drawer with slide in/out animations (collection editor). */
  variant?: "dialog" | "drawer";
  /** When true, plays the exit animation then calls onExitComplete. */
  exiting?: boolean;
  onExitComplete?: () => void;
  children: ReactNode;
};

function Modal({ title, onClose, wide, variant = "dialog", exiting = false, onExitComplete, children }: ModalProps) {
  const { t } = useTranslation();
  const isDrawer = variant === "drawer";
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction(onClose, {
    active: !exiting
  });

  function handlePanelAnimationEnd(event: ReactAnimationEvent<HTMLElement>) {
    if (!isDrawer || !exiting) return;
    if (event.target !== event.currentTarget) return;
    // Only finish after the panel finishes sliding out (not the enter animation).
    // Must match `DRAWER_SLIDE_OUT` / styles.css `drawer-slide-out`.
    if (event.animationName !== "drawer-slide-out") return;
    onExitComplete?.();
  }

  const backdropClass = [
    isDrawer ? "drawer-backdrop" : "modal-backdrop",
    isDrawer && exiting ? "is-exiting" : ""
  ]
    .filter(Boolean)
    .join(" ");

  const panelClass = [
    isDrawer ? "drawer-panel" : "modal",
    wide ? "wide" : "",
    isDrawer && exiting ? "is-exiting" : ""
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <div
      className={backdropClass}
      role="presentation"
      onMouseDown={exiting ? undefined : onBackdropMouseDown}
      onMouseUp={exiting ? undefined : onBackdropMouseUp}
    >
      <section
        ref={dialogRef}
        className={panelClass}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onAnimationEnd={handlePanelAnimationEnd}
      >
        <header>
          <h2>{title}</h2>
          <button
            className="icon-button"
            onClick={onClose}
            disabled={exiting}
            title={t("actions.close", "Close")}
            aria-label={t("actions.close", "Close")}
          >
            <X size={18} />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}

type OAuthResultModalProps = {
  result: OAuthResultState;
  onClose: () => void;
};

function OAuthResultModal({ result, onClose }: OAuthResultModalProps) {
  const { t } = useTranslation();
  return (
    <Modal title={t("auth.oauth_result_title", { name: result.provider.displayName || result.provider.name, defaultValue: "OAuth2 Result: {{name}}" })} onClose={onClose} wide>
      <div className="modal-grid">
        <div className="summary-row compact">
          <span>{t("common.token", "Token")}</span>
          <code>{result.response.token}</code>
        </div>
        <label>
          {t("records.record", "Record")}
          <textarea value={JSON.stringify(result.response.record, null, 2)} readOnly spellCheck={false} />
        </label>
        <label>
          {t("records.meta", "Meta")}
          <textarea value={JSON.stringify(result.response.meta ?? {}, null, 2)} readOnly spellCheck={false} />
        </label>
        <div className="modal-actions">
          <button type="button" className="subtle" onClick={onClose}>
            <X size={16} />
            {t("actions.close", "Close")}
          </button>
        </div>
      </div>
    </Modal>
  );
}


function AccountMenu({
  email,
  onManageSuperusers,
  onLogout
}: {
  email: string;
  onManageSuperusers: () => void;
  onLogout: () => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const label = email.trim() || t("auth.superuser", "Superuser");

  useEffect(() => {
    if (!open) return;

    function handlePointerDown(event: MouseEvent) {
      if (!dropdownRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setOpen(false);
    }

    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open]);

  return (
    <div className="account-menu" ref={dropdownRef}>
      <button
        type="button"
        className={open ? "header-link account-menu-trigger active" : "header-link account-menu-trigger"}
        title={label}
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="account-menu-email">{label}</span>
        <ChevronDown size={14} aria-hidden="true" />
      </button>

      {open && (
        <div className="account-menu-dropdown" role="menu">
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onManageSuperusers();
            }}
          >
            <Users size={15} aria-hidden="true" />
            {t("nav.manage_superusers", "Manage superusers")}
          </button>
          <button
            type="button"
            role="menuitem"
            className="danger"
            onClick={() => {
              setOpen(false);
              onLogout();
            }}
          >
            <LogOut size={15} aria-hidden="true" />
            {t("actions.logout", "Logout")}
          </button>
        </div>
      )}
    </div>
  );
}

function ThemeSelector({ mode, resolvedTheme, onChange }: { mode: ThemeMode; resolvedTheme: ResolvedTheme; onChange: (mode: ThemeMode) => void }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const TriggerIcon = mode === "auto" ? (resolvedTheme === "dark" ? Moon : Sun) : mode === "dark" ? Moon : Sun;
  const options: Array<{ value: ThemeMode; label: string; icon: LucideIcon }> = [
    { value: "light", label: t("theme.light", "Light"), icon: Sun },
    { value: "dark", label: t("theme.dark", "Dark"), icon: Moon },
    { value: "auto", label: t("theme.auto", "Auto"), icon: Minus }
  ];

  useEffect(() => {
    if (!open) return;

    function handlePointerDown(event: MouseEvent) {
      if (!dropdownRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    document.addEventListener("mousedown", handlePointerDown);
    return () => document.removeEventListener("mousedown", handlePointerDown);
  }, [open]);

  return (
    <div className="theme-selector" ref={dropdownRef}>
      <button
        type="button"
        className={open ? "icon-button header-icon active" : "icon-button header-icon"}
        title={t("theme.change", "Change theme")}
        aria-label={t("theme.change", "Change theme")}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <TriggerIcon size={17} />
      </button>

      {open && (
        <div className="theme-menu" role="menu">
          {options.map((option) => {
            const Icon = option.icon;
            return (
              <button
                key={option.value}
                type="button"
                role="menuitemradio"
                aria-checked={mode === option.value}
                className={mode === option.value ? "active" : ""}
                onClick={() => {
                  onChange(option.value);
                  setOpen(false);
                }}
              >
                <Icon size={15} />
                {option.label}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

function EmptyState({ icon: Icon, title }: { icon: LucideIcon; title: string }) {
  return (
    <section className="empty-state">
      <Icon size={26} />
      <h2>{title}</h2>
    </section>
  );
}

async function apiRequest<T>(path: string, token: string, options: ApiOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(options.body);
  }

  const response = await fetch(path, { ...options, headers, body });
  const text = await response.text();
  const parsed = text ? parseJson(text) : null;
  if (!response.ok) {
    const payload = isPlainObject(parsed) ? (parsed as Record<string, unknown>) : {};
    const mfaId = typeof payload.mfaId === "string" ? payload.mfaId : undefined;
    // An MFA challenge is a login step, not an expired session — leave the auth state alone.
    if (response.status === 401 && !mfaId && token) {
      localStorage.removeItem("pbj_token");
      if (typeof window !== "undefined") {
        window.dispatchEvent(new CustomEvent("pbj_unauthorized"));
      }
    }
    const apiError = payload as ApiError;
    throw new ApiRequestError(
      apiError.message || text || `${response.status} ${response.statusText}`,
      response.status,
      apiError.data,
      mfaId
    );
  }
  return parsed as T;
}

class ApiRequestError extends Error {
  readonly status: number;
  readonly data: unknown;
  readonly mfaId?: string;

  constructor(message: string, status: number, data?: unknown, mfaId?: string) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.data = data;
    this.mfaId = mfaId;
  }
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function buildQuery(params: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && String(value).trim() !== "") query.set(key, String(value));
  });
  return query.toString();
}

function recordColumns(collection: CollectionSchema | null) {
  if (!collection) return [];
  // The official UI hides the "verified" column for the _superusers collection.
  const isSuperusers = collection.name === "_superusers";
  const fieldNames = (collection.fields ?? [])
    .filter((field) => field.type !== "password")
    .filter((field) => !(isSuperusers && field.name === "verified"))
    .map((field) => field.name);
  // id/created/updated are ordinary schema fields since PB v0.23, so they may already
  // be present — dedupe or the header ends up wider than the rows and shifts out of line.
  const columns: string[] = [];
  for (const name of ["id", ...fieldNames, "created", "updated"]) {
    if (!columns.includes(name)) columns.push(name);
  }
  return columns;
}

function collectionPreferenceStoreKey(collection: CollectionSchema) {
  return `collection:${collection.id || collection.name}`;
}

function columnPreferenceKey(collection: CollectionSchema, column: string) {
  const field = (collection.fields ?? []).find((candidate) => candidate.name === column);
  return field?.id ? `field:${field.id}` : `system:${column}`;
}

function hiddenColumnPreferencesFor(collection: CollectionSchema, preferences: Record<string, string[]>) {
  const keys = [collectionPreferenceStoreKey(collection), collection.name];
  return Array.from(new Set(keys.flatMap((key) => preferences[key] ?? [])));
}

/**
 * Normalizes a stored column preference into a complete visibility snapshot.
 * Pre-v2 arrays only described manually hidden columns, so schema-hidden fields
 * need to be carried forward before the user changes an individual checkbox.
 */
function hiddenColumnPreferenceSnapshot(collection: CollectionSchema, values: string[]) {
  const hidden = new Set(normalizeColumnPreferences(collection, values));
  if (!values.includes(HIDDEN_COLUMNS_SNAPSHOT_MARKER)) {
    for (const field of collection.fields ?? []) {
      if (field.hidden) hidden.add(columnPreferenceKey(collection, field.name));
    }
  }
  return [HIDDEN_COLUMNS_SNAPSHOT_MARKER, ...hidden];
}

function normalizeColumnPreferences(collection: CollectionSchema, values: string[]) {
  const columns = recordColumns(collection);
  const knownColumns = new Set(columns);
  const fieldNameById = new Map((collection.fields ?? []).filter((field) => field.id).map((field) => [field.id!, field.name]));
  const normalized: string[] = [];
  for (const value of values) {
    const column = value.startsWith("field:")
      ? fieldNameById.get(value.slice("field:".length))
      : value.startsWith("system:")
        ? value.slice("system:".length)
        : value;
    if (!column || !knownColumns.has(column)) continue;
    const key = columnPreferenceKey(collection, column);
    if (!normalized.includes(key)) normalized.push(key);
  }
  return normalized;
}

function sameStringValues(left: string[], right: string[]) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function recordEditorPayload(collection: CollectionSchema, record?: RecordItem) {
  if (record) {
    return Object.fromEntries(
      Object.entries(record).filter(([key]) => !SYSTEM_RECORD_KEYS.has(key) && key !== "tokenKey" && key !== "passwordHash")
    );
  }
  return Object.fromEntries(
    (collection.fields ?? [])
      .filter(
        (field) =>
          field.type !== "file" &&
          (!field.system || (collection.type === "auth" && ["email", "emailVisibility", "verified", "password"].includes(field.name)))
      )
      .map((field) => [field.name, defaultValue(field)])
  );
}

/** A duplicate is a new record: it must not retain source files or automatic timestamps. */
function duplicateRecordPayload(collection: CollectionSchema, record?: RecordItem) {
  const payload = recordEditorPayload(collection, record);
  for (const field of collection.fields ?? []) {
    if (field.type === "file" || field.type === "autodate") {
      delete payload[field.name];
    }
  }
  return payload;
}

/** Copy/download/drafts are conveniences, never a reason to persist credentials. */
function sanitizeRecordForExport(record: Record<string, unknown>) {
  return sanitizeSensitiveValue(record) as Record<string, unknown>;
}

function sanitizeSensitiveValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitizeSensitiveValue);
  if (!isPlainObject(value)) return value;
  const sanitized: Record<string, unknown> = {};
  for (const [key, nested] of Object.entries(value)) {
    if (key === "expand" || sensitiveRecordKey(key)) continue;
    sanitized[key] = sanitizeSensitiveValue(nested);
  }
  return sanitized;
}

function sensitiveRecordKey(key: string) {
  const normalized = key
    .replace(/([a-z0-9])([A-Z])/g, "$1_$2")
    .replace(/[^a-zA-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .toLowerCase();
  return /(?:^|_)(?:password|passphrase|token|secret|credential|authorization|api_key|private_key)(?:$|_)/.test(
    normalized
  );
}

function defaultValue(field: FieldSchema) {
  if (field.type === "bool") return false;
  if (field.type === "number") return 0;
  if (field.type === "json") return null;
  if (field.type === "relation") return maxFiles(field) > 1 ? [] : "";
  return "";
}

function randomTokenSecret(length = 48) {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_";
  const bytes = new Uint32Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => alphabet[byte % alphabet.length]).join("");
}

function recordRequestBody(payload: Record<string, unknown>, files: Record<string, File[]>) {
  const entries = Object.entries(files).filter(([, value]) => value.length > 0);
  if (entries.length === 0) return payload;

  const form = new FormData();
  // Preserve nulls, arrays and nested JSON exactly like the official SDKs. The
  // server merges @jsonPayload with the multipart file fields before validation.
  form.append("@jsonPayload", JSON.stringify(payload));
  entries.forEach(([field, fieldFiles]) => {
    // `field+` appends uploads to existing file values; a bare field would replace
    // all files and make the UI's delete/restore workflow destructive.
    fieldFiles.forEach((file) => form.append(`${field}+`, file));
  });
  return form;
}

// maxFiles is now provided by domain/fields.ts as fieldMultiplicity.
// Keep the name as an alias for backward compatibility within App.tsx.
const maxFiles = fieldMultiplicity;

function mergeRecordItems(existing: RecordItem[], additions: RecordItem[]) {
  const next = [...existing];
  const positions = new Map(next.map((record, index) => [record.id, index]));
  for (const record of additions) {
    const position = positions.get(record.id);
    if (position === undefined) {
      positions.set(record.id, next.length);
      next.push(record);
    } else {
      next[position] = record;
    }
  }
  return next;
}

function mergeLogItems(existing: LogItem[], additions: LogItem[]) {
  const next = [...existing];
  const positions = new Map(next.map((log, index) => [log.id, index]));
  for (const log of additions) {
    const position = positions.get(log.id);
    if (position === undefined) {
      positions.set(log.id, next.length);
      next.push(log);
    } else {
      next[position] = log;
    }
  }
  return next;
}

function safeImageFilename(filename: string) {
  return /\.(?:gif|jpe?g|png|webp)$/i.test(filename);
}

function fileThumbnailSpec(field?: FieldSchema) {
  const configured = field?.thumbs ?? field?.options?.thumbs;
  const candidates = Array.isArray(configured)
    ? configured
    : typeof configured === "string"
      ? [configured]
      : [];
  return candidates.map((value) => String(value).trim()).find(Boolean) ?? "";
}

function normalizeRule(value: string | null) {
  return value === null ? null : value.trim();
}

/**
 * Describes what a collection save will actually do, so destructive edits (dropped
 * columns, renames, multi→single narrowing) are stated before they are applied.
 */
function collectionChanges(
  original: CollectionSchema,
  next: { name: string; fields: FieldSchema[]; rules: Record<RuleKey, string | null> },
  t: TFunction
) {
  const lines: string[] = [];
  let destructive = false;

  if (original.name !== next.name && next.name) {
    lines.push(
      t("changes.renamed_collection", {
        from: original.name,
        to: next.name,
        defaultValue: "• Collection renamed: {{from}} → {{to}}"
      })
    );
  }

  const originalFields = original.fields ?? [];
  for (const field of originalFields) {
    const match = next.fields.find((item) => (field.id && item.id === field.id) || item.name === field.name);
    if (!match) {
      destructive = true;
      lines.push(
        t("changes.deleted_field", {
          name: field.name,
          defaultValue: "• Field removed: {{name}} — its stored data will be permanently deleted"
        })
      );
      continue;
    }
    if (match.name !== field.name) {
      lines.push(
        t("changes.renamed_field", {
          from: field.name,
          to: match.name,
          defaultValue: "• Field renamed: {{from}} → {{to}}"
        })
      );
    }
    if (match.type !== field.type) {
      destructive = true;
      lines.push(
        t("changes.retyped_field", {
          name: match.name,
          from: field.type,
          to: match.type,
          defaultValue: "• Field type changed: {{name}} ({{from}} → {{to}}) — existing values may not convert"
        })
      );
    }
    const wasMulti = Number(field.maxSelect ?? 1) > 1;
    const isMulti = Number(match.maxSelect ?? 1) > 1;
    if (wasMulti && !isMulti) {
      destructive = true;
      lines.push(
        t("changes.narrowed_field", {
          name: match.name,
          defaultValue: "• Field {{name}} changed to single value — only the last item of each record is kept"
        })
      );
    }
  }

  const added = next.fields.filter(
    (field) => !originalFields.some((item) => (field.id && item.id === field.id) || item.name === field.name)
  );
  for (const field of added) {
    lines.push(
      t("changes.added_field", { name: field.name, defaultValue: "• Field added: {{name}}" })
    );
  }

  const describeRule = (value: string | null) =>
    value === null
      ? t("collections.rule_locked", "Superusers only")
      : value === ""
        ? t("changes.rule_public", "everyone")
        : value;

  for (const key of ["listRule", "viewRule", "createRule", "updateRule", "deleteRule"] as RuleKey[]) {
    const before = original[key] ?? null;
    const after = normalizeRule(next.rules[key]);
    if (before === after) continue;
    // Opening a rule up is the change most likely to be unintended.
    if (before === null) destructive = true;
    lines.push(
      t("changes.rule_changed", {
        rule: collectionRuleLabel(key, t),
        from: describeRule(before),
        to: describeRule(after),
        defaultValue: "• {{rule}}: {{from}} → {{to}}"
      })
    );
  }

  return { lines, destructive };
}

const FILTER_OPERATORS = ["=", "!=", "~", "!~", ">", ">=", "<", "<="];

/**
 * Turns a plain search term into a filter expression spanning the given fields.
 * Terms that already read as an expression are passed through untouched.
 */
function normalizeSearchTerm(term: string, fallbackFields: string[]) {
  const searchTerm = (term || "").trim();
  if (!searchTerm || fallbackFields.length === 0) return searchTerm;
  if (FILTER_OPERATORS.some((op) => searchTerm.includes(op))) return searchTerm;

  const isLiteral = Number.isNaN(Number(searchTerm)) && searchTerm !== "true" && searchTerm !== "false";
  const needle = isLiteral ? `"${searchTerm.replace(/^["'`]|["'`]$/gm, "")}"` : searchTerm;
  return fallbackFields.map((name) => `${name}~${needle}`).join("||");
}

function normalizeSearchFilter(term: string, collection?: CollectionSchema) {
  const fields = (collection?.fields ?? []).filter((field) => !field.hidden).map((field) => field.name);
  return normalizeSearchTerm(term, fields);
}

const LOG_SEARCH_FIELDS = ["level", "message", "data"];

// Mirrors the statements the official console guards behind a confirmation.
const DANGEROUS_SQL = ["alter", "insert", "create", "update", "delete", "drop", "detach", "pragma", "replace"];

function splitScopes(value: string | string[] | undefined) {
  const items = Array.isArray(value) ? value : String(value ?? "").split(",");
  return items.map((item) => item.trim()).filter(Boolean);
}

function defaultProviderPkce(name: string) {
  return !["bitbucket", "linear", "vk"].includes(name);
}

function waitForOAuthResult(
  expectedState: string,
  realtime: EventSource,
  popup: Window,
  messages: { closed: string; timeout: string },
  timeoutMs = 120000
) {
  return new Promise<{ state: string; code: string; error: string }>((resolve, reject) => {
    let settled = false;
    let intervalId = 0;
    let timeoutId = 0;

    const cleanup = () => {
      realtime.removeEventListener("@oauth2", onMessage);
      window.clearInterval(intervalId);
      window.clearTimeout(timeoutId);
    };

    const finish = (callback: () => void) => {
      if (settled) return;
      settled = true;
      cleanup();
      callback();
    };

    const handlePayload = (payload: unknown) => {
      if (!isPlainObject(payload)) return;
      if (String(payload.state ?? "") !== expectedState) return;
      finish(() =>
        resolve({
          state: String(payload.state ?? ""),
          code: String(payload.code ?? ""),
          error: String(payload.error ?? "")
        })
      );
    };

    const onMessage = (event: Event) => {
      const message = event as MessageEvent<string>;
      try {
        handlePayload(JSON.parse(message.data));
      } catch {
        // ignore invalid realtime payloads
      }
    };

    realtime.addEventListener("@oauth2", onMessage);
    intervalId = window.setInterval(() => {
      if (popup.closed) {
        finish(() => reject(new Error(messages.closed)));
      }
    }, 250);
    timeoutId = window.setTimeout(() => {
      finish(() => reject(new Error(messages.timeout)));
    }, timeoutMs);
  });
}

function waitForRealtimeClient(realtime: EventSource, timeoutMessage: string, timeoutMs = 15000) {
  return new Promise<string>((resolve, reject) => {
    let timeoutId = 0;
    const cleanup = () => {
      realtime.removeEventListener("PB_CONNECT", onConnect);
      realtime.removeEventListener("error", onError);
      window.clearTimeout(timeoutId);
    };
    const onConnect = (event: Event) => {
      try {
        const payload = JSON.parse((event as MessageEvent<string>).data);
        const clientId = String(payload.clientId ?? "");
        if (!clientId) return;
        cleanup();
        resolve(clientId);
      } catch {
        // keep waiting for a valid PB_CONNECT event
      }
    };
    const onError = () => {
      if (realtime.readyState !== EventSource.CLOSED) return;
      cleanup();
      reject(new Error(timeoutMessage));
    };
    realtime.addEventListener("PB_CONNECT", onConnect);
    realtime.addEventListener("error", onError);
    timeoutId = window.setTimeout(() => {
      cleanup();
      reject(new Error(timeoutMessage));
    }, timeoutMs);
  });
}

// formatValue and formatDate are now in utils/date.ts; keep local aliases.
const formatValue = sharedFormatValue;
const formatDate = sharedFormatDate;

/**
 * Extracts PocketBase's per-field validation errors ({ data: { field: { message } } })
 * so they can be shown next to the input that caused them.
 */
function fieldErrorsOf(error: unknown): Record<string, string> {
  if (!(error instanceof ApiRequestError) || !isPlainObject(error.data)) return {};
  const result: Record<string, string> = {};
  for (const [field, detail] of Object.entries(error.data)) {
    if (isPlainObject(detail) && typeof detail.message === "string") {
      result[field] = detail.message;
    }
  }
  return result;
}

/** Same level thresholds the official UI uses (slog levels). */
function logLevel(value: number) {
  if (value >= 8) return { label: "ERROR", kind: "danger" };
  if (value >= 4) return { label: "WARN", kind: "warning" };
  if (value >= 0) return { label: "INFO", kind: "success" };
  return { label: "DEBUG", kind: "" };
}

type LogChip = { key: string; value: string; kind?: string };

/**
 * Summarises log.data as chips: request logs get their well-known keys in a fixed
 * order, anything else falls back to the first few data entries so non-HTTP logs
 * (cron runs, app errors) still carry information in the list.
 */
function logDataChips(log: LogItem): LogChip[] {
  const data = log.data ?? {};
  const chips: LogChip[] = [];
  const isRequest = data.method !== undefined || data.status !== undefined;

  if (isRequest) {
    const status = Number(data.status ?? 0);
    if (data.method !== undefined) chips.push({ key: "method", value: String(data.method) });
    if (status) {
      chips.push({
        key: "status",
        value: String(status),
        kind: status >= 500 ? "danger" : status >= 400 ? "warning" : "success"
      });
    }
    if (data.execTime !== undefined) chips.push({ key: "execTime", value: formatExecTime(data.execTime) });
    if (data.auth !== undefined) chips.push({ key: "auth", value: String(data.auth) });
    if (data.authId !== undefined) chips.push({ key: "authId", value: String(data.authId) });
    if (data.userIP !== undefined) chips.push({ key: "userIP", value: String(data.userIP) });
  } else {
    for (const [key, value] of Object.entries(data)) {
      if (key === "error" || key === "details" || key === "__pb_truncated__") continue;
      if (chips.length >= 6) break;
      if (value === null || value === undefined || value === "") continue;
      chips.push({ key, value: typeof value === "object" ? JSON.stringify(value) : String(value) });
    }
  }

  // Errors are the reason someone opens this page — keep them last so they read as the outcome.
  if (data.details !== undefined) chips.push({ key: "details", value: String(data.details), kind: "warning" });
  if (data.error !== undefined) chips.push({ key: "error", value: String(data.error), kind: "danger" });

  return chips;
}

function formatExecTime(value: unknown) {
  const millis = Number(value);
  if (!Number.isFinite(millis)) return "";
  if (millis < 1000) return `${Math.round(millis)} ms`;
  return `${(millis / 1000).toFixed(2)} s`;
}

function formatBytes(value: number) {
  if (!Number.isFinite(value)) return "";
  const units = ["B", "KB", "MB", "GB"];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function isSettingsView(view: ViewName) {
  return ["settings", "mail", "storage", "backups", "crons", "export", "import", "sql"].includes(view);
}

function isSystemCollection(collection: CollectionSchema) {
  return Boolean(collection.system) || collection.name.startsWith("_");
}

function viewMeta(view: ViewName, collection: CollectionSchema | null, t: TFunction) {
  const titles: Record<ViewName, { title: string; eyebrow: string }> = {
    records: { title: collection?.name ?? t("nav.collections", "Collections"), eyebrow: collection?.type ?? t("common.admin_console", "Admin console") },
    schema: { title: collection?.name ?? t("nav.collections", "Collections"), eyebrow: t("collections.schema", "Schema") },
    settings: { title: t("settings.nav.application", "Application"), eyebrow: t("settings.nav.system", "System") },
    mail: { title: t("settings.nav.mail", "Mail settings"), eyebrow: t("settings.nav.system", "System") },
    storage: { title: t("settings.nav.storage", "Files storage"), eyebrow: t("settings.nav.system", "System") },
    backups: { title: t("settings.nav.backups", "Backups"), eyebrow: t("settings.nav.system", "System") },
    crons: { title: t("settings.nav.crons", "Crons"), eyebrow: t("settings.nav.system", "System") },
    export: { title: t("settings.nav.export", "Export collections"), eyebrow: t("settings.nav.sync", "Sync") },
    import: { title: t("settings.nav.import", "Import collections"), eyebrow: t("settings.nav.sync", "Sync") },
    sql: { title: t("settings.nav.sql", "SQL console"), eyebrow: t("settings.nav.debug", "Debug") },
    logs: { title: t("nav.logs", "Logs"), eyebrow: t("logs.observability", "Observability") }
  };
  return titles[view];
}

function sortedCollectionsForTransfer(collections: CollectionSchema[]) {
  return collections
    .map((collection) => sanitizeCollectionForTransfer(collection))
    .sort((left, right) => {
      const typeOrder = collectionTypeOrder(left.type) - collectionTypeOrder(right.type);
      if (typeOrder !== 0) return typeOrder;
      return left.name.localeCompare(right.name);
    });
}

function collectionTypeOrder(type: string) {
  if (type === "auth") return 0;
  if (type === "base") return 1;
  if (type === "view") return 2;
  return 3;
}

function sanitizeCollectionForTransfer(collection: CollectionSchema) {
  const sanitized = cloneJsonObject(collection) as CollectionSchema;
  delete sanitized.created;
  delete sanitized.updated;
  const oauth2 = sanitized.oauth2 as Record<string, unknown> | undefined;
  if (isPlainObject(oauth2)) {
    delete oauth2.providers;
  }
  return sanitized;
}

function parseCollectionsPayload(value: string) {
  if (!value.trim()) return null;
  try {
    const parsed = JSON.parse(value);
    const payload = Array.isArray(parsed) ? parsed : isPlainObject(parsed) && Array.isArray(parsed.collections) ? parsed.collections : null;
    if (!payload) return null;
    return payload.filter(isPlainObject).map((collection) => sanitizeCollectionForTransfer(collection as CollectionSchema));
  } catch {
    return null;
  }
}

type CollectionIdReplacement = {
  name: string;
  fromId: string;
  toId: string;
};

/**
 * A collection import addresses existing schemas by id first. A schema exported
 * from another PocketBase instance can therefore collide by name but still be
 * treated as a new collection. Only suggest a rewrite when the name and type
 * unambiguously identify the same local collection and the imported id is not
 * already owned by a different local collection.
 */
function collectionIdReplacementSuggestions(
  current: CollectionSchema[],
  imported: CollectionSchema[] | null
): CollectionIdReplacement[] {
  if (!imported?.length) return [];
  const currentById = new Map(current.map((collection) => [collection.id, collection]));
  const currentByName = new Map(current.map((collection) => [collection.name, collection]));
  const importNameCounts = new Map<string, number>();
  for (const collection of imported) {
    importNameCounts.set(collection.name, (importNameCounts.get(collection.name) ?? 0) + 1);
  }

  return imported.flatMap((collection) => {
    const fromId = String(collection.id ?? "").trim();
    const existing = currentByName.get(collection.name);
    if (
      !fromId ||
      !existing ||
      existing.id === fromId ||
      existing.type !== collection.type ||
      importNameCounts.get(collection.name) !== 1 ||
      currentById.has(fromId)
    ) {
      return [];
    }
    return [{ name: collection.name, fromId, toId: existing.id }];
  });
}

/** Rewrites only schema positions that are documented to store collection ids. */
function replaceCollectionIdsInImportPayload(value: string, replacements: CollectionIdReplacement[]) {
  if (replacements.length === 0) return "";
  try {
    const parsed = JSON.parse(value) as unknown;
    const payload = Array.isArray(parsed)
      ? parsed
      : isPlainObject(parsed) && Array.isArray(parsed.collections)
        ? parsed.collections
        : null;
    if (!payload) return "";

    const idMap = new Map(replacements.map((replacement) => [replacement.fromId, replacement.toId]));
    const replaceId = (candidate: unknown) =>
      typeof candidate === "string" ? (idMap.get(candidate) ?? candidate) : candidate;
    const replaceIds = (candidate: unknown) =>
      Array.isArray(candidate) ? candidate.map((id) => replaceId(id)) : candidate;

    for (const rawCollection of payload) {
      if (!isPlainObject(rawCollection)) continue;
      rawCollection.id = replaceId(rawCollection.id);
      if (!Array.isArray(rawCollection.fields)) continue;
      for (const rawField of rawCollection.fields) {
        if (!isPlainObject(rawField)) continue;
        rawField.collectionId = replaceId(rawField.collectionId);
        rawField.collectionIds = replaceIds(rawField.collectionIds);
        // The current schema keeps relation ids at the field root. Retain this
        // compatibility branch for exports created by older Java UI revisions.
        if (isPlainObject(rawField.options)) {
          rawField.options.collectionId = replaceId(rawField.options.collectionId);
          rawField.options.collectionIds = replaceIds(rawField.options.collectionIds);
        }
      }
    }
    return JSON.stringify(parsed, null, 2);
  } catch {
    return "";
  }
}

function collectionImportChanges(current: CollectionSchema[], imported: CollectionSchema[] | null, deleteMissing: boolean) {
  if (!imported?.length) return { added: [] as CollectionSchema[], changed: [] as { previous: CollectionSchema; next: CollectionSchema }[], deleted: [] as CollectionSchema[] };
  const currentCollections = sortedCollectionsForTransfer(current);
  const importedCollections = imported;
  const currentById = new Map(currentCollections.map((collection) => [collection.id, collection]));
  const importedById = new Map(importedCollections.map((collection) => [collection.id, collection]));
  const added = importedCollections.filter((collection) => !currentById.has(collection.id));
  const changed = importedCollections
    .filter((collection) => {
      const previous = currentById.get(collection.id);
      return previous && stableJsonStringify(previous) !== stableJsonStringify(collection);
    })
    .map((collection) => ({ previous: currentById.get(collection.id) as CollectionSchema, next: collection }));
  const deleted = deleteMissing ? currentCollections.filter((collection) => !importedById.has(collection.id)) : [];
  return { added, changed, deleted };
}

function collectionFieldChanges(previous: CollectionSchema, next: CollectionSchema): CollectionFieldChange[] {
  const previousFields = previous.fields ?? [];
  const nextFields = next.fields ?? [];
  const previousByIdentity = new Map(previousFields.map((field) => [fieldIdentity(field), field]));
  const nextByIdentity = new Map(nextFields.map((field) => [fieldIdentity(field), field]));
  const changes: CollectionFieldChange[] = [];

  for (const field of previousFields) {
    const incoming = nextByIdentity.get(fieldIdentity(field));
    if (!incoming) {
      changes.push({ kind: "Deleted", previous: field, changedKeys: [] });
      continue;
    }
    const changedKeys = Object.keys({ ...field, ...incoming }).filter((key) => {
      if (key === "id") return false;
      return stableJsonStringify(field[key as keyof FieldSchema]) !== stableJsonStringify(incoming[key as keyof FieldSchema]);
    });
    if (changedKeys.length > 0) changes.push({ kind: "Changed", previous: field, next: incoming, changedKeys });
  }

  for (const field of nextFields) {
    if (!previousByIdentity.has(fieldIdentity(field))) {
      changes.push({ kind: "Added", next: field, changedKeys: [] });
    }
  }
  return changes;
}

function fieldIdentity(field: FieldSchema) {
  return field.id ? `id:${field.id}` : `name:${field.name}`;
}

/**
 * Rebuilds only parseable index column lists. Replacing arbitrary text in raw
 * SQL corrupts partial-index predicates and expression literals when a field is
 * renamed, so non-simple/unknown SQL is deliberately left untouched.
 */
function renameIndexColumns(sql: string, renames: ReadonlyMap<string, string>) {
  const parsed = parseIndex(sql);
  if (!parsed.tableName || parsed.columns.length === 0) return sql;
  const columns = parsed.columns.map((column) => renames.get(column) ?? column);
  if (columns.every((column, index) => column === parsed.columns[index])) return sql;
  return buildIndex({ ...parsed, columns });
}

function stableJsonStringify(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map((item) => stableJsonStringify(item)).join(",")}]`;
  if (isPlainObject(value)) {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stableJsonStringify(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function downloadJsonFile(value: unknown, filename: string) {
  const blob = new Blob([JSON.stringify(value, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function compareSqlValues(left: unknown, right: unknown) {
  if (left === right) return 0;
  if (left === null || left === undefined) return -1;
  if (right === null || right === undefined) return 1;
  if (typeof left === "number" && typeof right === "number") return left - right;
  return String(left).localeCompare(String(right), undefined, { numeric: true, sensitivity: "base" });
}

function csvCell(value: unknown) {
  const serialized = value === null || value === undefined || typeof value === "string" ? value : JSON.stringify(value);
  const raw = serialized === null || serialized === undefined ? "" : String(serialized);
  // Spreadsheet applications evaluate quoted CSV cells that start with formula
  // sigils. Preserve untrusted SQL strings as literal text instead.
  const text = typeof serialized === "string" && /^\s*[=+\-@]/.test(raw) ? `'${raw}` : raw;
  return `"${text.replace(/"/g, '""')}"`;
}

function downloadCsvFile(columns: string[], rows: unknown[][], filename: string) {
  const source = [columns, ...rows];
  const csv = source.map((row) => row.map(csvCell).join(",")).join("\r\n");
  const blob = new Blob(["\ufeff", csv], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function settingsObject(settings: AppSettings | null, section: string) {
  const value = settings?.[section];
  return isPlainObject(value) ? value : {};
}

function parseSettingsDraft(draft: string, fallback: AppSettings | null) {
  try {
    const parsed = JSON.parse(draft || "{}");
    if (isPlainObject(parsed)) return parsed;
  } catch {
    // Keep rendering the last loaded settings if the advanced JSON is temporarily invalid.
  }
  return cloneJsonObject(fallback ?? {});
}

function cloneJsonObject(value: Record<string, unknown>) {
  return JSON.parse(JSON.stringify(value || {})) as Record<string, unknown>;
}

function setNestedSetting(target: Record<string, unknown>, path: string[], value: unknown) {
  let cursor = target;
  for (let index = 0; index < path.length - 1; index++) {
    const key = path[index];
    const next = cursor[key];
    if (!isPlainObject(next)) {
      cursor[key] = {};
    }
    cursor = cursor[key] as Record<string, unknown>;
  }
  cursor[path[path.length - 1]] = value;
}

function truthyText(value: unknown) {
  return value ? "enabled" : "disabled";
}

function readThemeMode(): ThemeMode {
  const value = localStorage.getItem(THEME_KEY);
  return value === "light" || value === "dark" || value === "auto" ? value : "auto";
}

function readSidebarWidth() {
  const value = Number.parseInt(localStorage.getItem(SIDEBAR_WIDTH_KEY) || "", 10);
  return Number.isFinite(value) ? clampSidebarWidth(value) : 240;
}

function settingsApplicationName(settings: AppSettings | null) {
  if (!settings || !isPlainObject(settings.meta)) return "";
  const value = settings.meta.appName;
  return typeof value === "string" ? value.trim() : "";
}

function settingsHideControls(settings: AppSettings | null) {
  return Boolean(settings && isPlainObject(settings.meta) && settings.meta.hideControls);
}

function settingsAccentColor(settings: AppSettings | null) {
  if (!settings || !isPlainObject(settings.meta)) return "";
  return normalizeAccentColor(settings.meta.accentColor);
}

function normalizeAccentColor(value: unknown) {
  if (typeof value !== "string") return "";
  const color = value.trim().toLowerCase();
  // CSS custom-property values must never be sourced from arbitrary settings
  // text. The native color input and PocketBase's own picker both use #RRGGBB.
  return /^#[0-9a-f]{6}$/.test(color) ? color : "";
}

function isDarkEnoughForWhiteText(color: string) {
  if (!/^#[0-9a-f]{6}$/i.test(color)) return false;
  const red = Number.parseInt(color.slice(1, 3), 16);
  const green = Number.parseInt(color.slice(3, 5), 16);
  const blue = Number.parseInt(color.slice(5, 7), 16);
  return (red * 299 + green * 587 + blue * 114) / 1000 < 128;
}

function resolveThemeMode(mode: ThemeMode): ResolvedTheme {
  if (mode === "auto") {
    return window.matchMedia?.("(prefers-color-scheme: dark)")?.matches ? "dark" : "light";
  }
  return mode;
}

function readStringArray(key: string) {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || "[]");
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function readSearchHistory(key: string) {
  // Filters can contain personal data. Keep suggestions only for this browser
  // session and clear the previous persistent implementation on first use.
  localStorage.removeItem(key);
  try {
    const parsed = JSON.parse(sessionStorage.getItem(key) || "[]");
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function writeSearchHistory(key: string, value: string) {
  const term = value.trim();
  const previous = readSearchHistory(key);
  const next = term ? [term, ...previous.filter((item) => item !== term)].slice(0, SEARCH_HISTORY_LIMIT) : previous;
  try {
    sessionStorage.setItem(key, JSON.stringify(next));
  } catch {
    // Search suggestions are an enhancement; quota/privacy mode must not block the query.
  }
  return next;
}

function readStringArrayRecord(key: string) {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || "{}");
    if (!isPlainObject(parsed)) return {};
    return Object.fromEntries(
      Object.entries(parsed).map(([name, value]) => [
        name,
        Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : []
      ])
    );
  } catch {
    return {};
  }
}

function readRecordDraft(key: string) {
  try {
    const parsed = JSON.parse(localStorage.getItem(key) || "null");
    if (!isPlainObject(parsed)) return null;
    const sanitized = sanitizeRecordForExport(parsed);
    if (JSON.stringify(sanitized) !== JSON.stringify(parsed)) {
      localStorage.setItem(key, JSON.stringify(sanitized));
    }
    return sanitized;
  } catch {
    localStorage.removeItem(key);
    return null;
  }
}

function parseFieldsPreview(value: string, t: TFunction) {
  try {
    const parsed = JSON.parse(value || "[]");
    if (!Array.isArray(parsed)) return { fields: [] as FieldSchema[], error: t("errors.fields_must_be_array", "Fields must be an array.") };
    return { fields: parsed as FieldSchema[], error: "" };
  } catch (error) {
    return { fields: [] as FieldSchema[], error: errorMessage(error) };
  }
}

function collectionModalTabs(type: string, t: TFunction) {
  if (type === "view") {
    return [
      { id: "query", label: t("sql.query", "Query") },
      { id: "rules", label: t("collections.api_rules", "API rules") }
    ];
  }
  if (type === "auth") {
    return [
      { id: "fields", label: t("collections.fields", "Fields") },
      { id: "rules", label: t("collections.api_rules", "API rules") },
      { id: "auth", label: t("common.options", "Options") },
      { id: "templates", label: t("parity.collection.email_templates", "Email templates") },
      { id: "tokens", label: t("parity.collection.token_options", "Token options") }
    ];
  }
  return [
    { id: "fields", label: t("collections.fields", "Fields") },
    { id: "rules", label: t("collections.api_rules", "API rules") }
  ];
}

function collectionRuleKeys(type: string): RuleKey[] {
  if (type === "view") return ["listRule", "viewRule"];
  if (type === "auth") return ["listRule", "viewRule", "createRule", "updateRule", "deleteRule", "authRule", "manageRule"];
  return ["listRule", "viewRule", "createRule", "updateRule", "deleteRule"];
}

function collectionRuleLabel(key: RuleKey, t: TFunction) {
  const labels: Record<RuleKey, string> = {
    listRule: t("collections.list_search_rule", "List/Search rule"),
    viewRule: t("collections.view_rule", "View rule"),
    createRule: t("collections.create_rule", "Create rule"),
    updateRule: t("collections.update_rule", "Update rule"),
    deleteRule: t("collections.delete_rule", "Delete rule"),
    authRule: t("parity.collection.auth_rule", "Auth rule"),
    manageRule: t("parity.collection.manage_rule", "Manage rule")
  };
  return labels[key];
}

function uniqueFieldName(fields: FieldSchema[], type: string) {
  const base = type.replace(/[^A-Za-z0-9_]/g, "_") || "field";
  const existing = new Set(fields.map((field) => field.name));
  if (!existing.has(base)) return base;
  // Official convention: append an incrementing suffix starting at 2.
  let index = 2;
  while (existing.has(`${base}_${index}`)) index++;
  return `${base}_${index}`;
}

function nextDuplicateCollectionName(name: string, collections: CollectionSchema[]) {
  const existing = new Set(collections.map((collection) => collection.name.toLowerCase()));
  const base = `${name}_copy`.replace(/[^A-Za-z0-9_]/g, "_").slice(0, 58) || "collection_copy";
  if (!existing.has(base.toLowerCase())) return base;
  let index = 2;
  while (existing.has(`${base}_${index}`.toLowerCase())) index += 1;
  return `${base}_${index}`.slice(0, 63);
}

function duplicateCollectionPayload(collection: CollectionSchema, name: string): CollectionPayload {
  const fields = (collection.fields ?? [])
    .filter((field) => !field.system)
    .map(({ id: _id, system: _system, ...field }) => ({ ...field }));
  const copyToken = (token?: TokenConfig): TokenConfig => ({ duration: token?.duration });
  return {
    name,
    type: collection.type,
    fields: collection.type === "view" ? [] : fields,
    ...(collection.type === "view"
      ? {}
      : { indexes: (collection.indexes ?? []).map((index, position) => duplicateIndexSql(index, name, position + 1)) }),
    listRule: collection.listRule ?? null,
    viewRule: collection.viewRule ?? null,
    createRule: collection.createRule ?? null,
    updateRule: collection.updateRule ?? null,
    deleteRule: collection.deleteRule ?? null,
    viewQuery: collection.viewQuery ?? null,
    ...(collection.type === "auth"
      ? {
          passwordAuth: collection.passwordAuth,
          otp: collection.otp,
          mfa: collection.mfa,
          oauth2: collection.oauth2,
          authAlert: collection.authAlert,
          authToken: copyToken(collection.authToken),
          passwordResetToken: copyToken(collection.passwordResetToken),
          verificationToken: copyToken(collection.verificationToken),
          emailChangeToken: copyToken(collection.emailChangeToken),
          fileToken: copyToken(collection.fileToken),
          verificationTemplate: collection.verificationTemplate,
          resetPasswordTemplate: collection.resetPasswordTemplate,
          confirmEmailChangeTemplate: collection.confirmEmailChangeTemplate,
          authRule: collection.authRule ?? null,
          manageRule: collection.manageRule ?? null
        }
      : {})
  };
}

function duplicateIndexSql(index: string, collectionName: string, position: number) {
  const identifier = `idx_${collectionName}_${position}`;
  const quote = index.includes("`") ? "`" : "";
  const named = index.replace(
    /(CREATE\s+(?:UNIQUE\s+)?INDEX\s+)(?:IF\s+NOT\s+EXISTS\s+)?(?:`[^`]+`|"[^"]+"|\[[^\]]+\]|[^\s(]+)/i,
    `$1${quote}${identifier}${quote}`
  );
  return named.replace(
    /(\bON\s+)(?:`[^`]+`|"[^"]+"|\[[^\]]+\]|[^\s(]+)/i,
    `$1${quote}${collectionName}${quote}`
  );
}

function splitCsv(value: string) {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function ipRuleAllows(ip: string, rule: string) {
  const trimmed = rule.trim();
  if (!trimmed) return false;
  const parts = trimmed.split("/");
  if (parts.length > 2) return false;
  const address = parseIpLiteral(ip);
  const network = parseIpLiteral(parts[0] ?? "");
  if (!address || !network || address.length !== network.length) return false;
  let prefix = address.length * 8;
  if (parts.length === 2) {
    const raw = parts[1] ?? "";
    if (!/^\d+$/.test(raw)) return false;
    prefix = Number(raw);
  }
  if (!Number.isSafeInteger(prefix) || prefix < 0 || prefix > address.length * 8) return false;
  const fullBytes = Math.floor(prefix / 8);
  const remainingBits = prefix % 8;
  for (let index = 0; index < fullBytes; index += 1) {
    if (address[index] !== network[index]) return false;
  }
  if (remainingBits === 0) return true;
  const mask = (0xff << (8 - remainingBits)) & 0xff;
  return (address[fullBytes] & mask) === (network[fullBytes] & mask);
}

function parseIpLiteral(value: string): number[] | null {
  const candidate = value.trim();
  if (!candidate || candidate.includes("%")) return null;
  if (!candidate.includes(":")) return parseIpv4Literal(candidate);
  if (!/^[0-9A-Fa-f:.]+$/.test(candidate)) return null;

  let normalized = candidate;
  if (normalized.includes(".")) {
    // Match the server's only IPv4-in-IPv6 normalization: an IPv4-mapped
    // literal written with the `::ffff:` marker.
    if (!normalized.toLowerCase().includes("::ffff:")) return null;
    const separator = normalized.lastIndexOf(":");
    const ipv4 = parseIpv4Literal(normalized.slice(separator + 1));
    if (!ipv4) return null;
    normalized = `${normalized.slice(0, separator)}:${((ipv4[0] << 8) | ipv4[1]).toString(16)}:${((ipv4[2] << 8) | ipv4[3]).toString(16)}`;
  }

  const compression = normalized.indexOf("::");
  if (compression !== -1 && normalized.indexOf("::", compression + 2) !== -1) return null;
  const left = compression === -1 ? normalized : normalized.slice(0, compression);
  const right = compression === -1 ? "" : normalized.slice(compression + 2);
  const leftGroups = left ? left.split(":") : [];
  const rightGroups = right ? right.split(":") : [];
  const groups = [...leftGroups, ...rightGroups];
  if (groups.some((group) => !/^[0-9A-Fa-f]{1,4}$/.test(group))) return null;
  if (compression === -1 && groups.length !== 8) return null;
  if (compression !== -1 && groups.length >= 8) return null;
  const expanded =
    compression === -1
      ? groups
      : [...leftGroups, ...Array.from({ length: 8 - groups.length }, () => "0"), ...rightGroups];
  return expanded.flatMap((group) => {
    const parsed = Number.parseInt(group, 16);
    return [parsed >> 8, parsed & 0xff];
  });
}

function parseIpv4Literal(value: string): number[] | null {
  const groups = value.split(".");
  if (groups.length !== 4) return null;
  const octets: number[] = [];
  for (const group of groups) {
    if (!/^\d+$/.test(group) || (group.length > 1 && group.startsWith("0"))) return null;
    const octet = Number(group);
    if (!Number.isSafeInteger(octet) || octet < 0 || octet > 255) return null;
    octets.push(octet);
  }
  return octets;
}

function parseSortValue(value: string): { field: string; direction: SortDirection } {
  const first = (value || "-created").split(",")[0]?.trim() || "-created";
  if (first.startsWith("-")) {
    return { field: first.slice(1) || "created", direction: "desc" };
  }
  return { field: first || "created", direction: "asc" };
}

function formatSortValue(field: string, direction: SortDirection) {
  const cleanField = field.trim() || "created";
  return direction === "desc" ? `-${cleanField}` : cleanField;
}

/**
 * The server alone can decide whether a changed row still satisfies an
 * arbitrary PocketBase filter. Keep the optimistic row update, but flag the
 * refresh affordance when its order or membership may have changed.
 */
function recordListMayNeedRefresh(query: QueryState) {
  return Boolean(query.filter.trim()) || query.sort.trim() !== "-created";
}

function compactSelectWidth(labels: string[]) {
  const maxLength = Math.max(2, ...labels.map((label) => Array.from(label).reduce((total, char) => total + charDisplayUnits(char), 0)));
  return `calc(${maxLength}ch + 54px)`;
}

function charDisplayUnits(char: string) {
  return /[\u2E80-\u9FFF\uAC00-\uD7AF\u3040-\u30FF\uFF00-\uFFEF]/.test(char) ? 2 : 1;
}

function errorMessage(error: unknown) {
  if (error instanceof Error) return error.message;
  return String(error);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export default App;
