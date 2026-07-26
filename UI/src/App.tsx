import {
  Activity,
  Archive,
  CheckSquare2,
  ChevronRight,
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
  HardDrive,
  KeyRound,
  ListFilter,
  Lock,
  LogOut,
  Mail,
  Minus,
  Moon,
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
  X
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, ReactNode, RefObject } from "react";
import type { TFunction } from "i18next";
import { AuthActionPages } from "./AuthActionPages";
import { DropdownSelect } from "./components/DropdownSelect";
import { FieldEditor } from "./components/FieldEditor";

import { useTranslation } from "react-i18next";
import { LanguageSelector } from "./components/LanguageSelector";
import { ApiPreview } from "./components/ApiPreview";
import { CodeEditor, buildRuleCompletions } from "./components/CodeEditor";
import { ConfirmDialog } from "./components/ConfirmDialog";
import { IndexManager } from "./components/IndexManager";
import type { ConfirmRequest } from "./components/ConfirmDialog";
import { RecordFieldControl } from "./components/RecordFieldControl";
import { recordSummary } from "./components/RelationPicker";
import type { RelationFetcher } from "./components/RelationPicker";


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

type FieldSchema = {
  id?: string;
  name: string;
  type: string;
  required?: boolean;
  unique?: boolean;
  hidden?: boolean;
  system?: boolean;
  presentable?: boolean;
  collectionId?: string;
  collectionIds?: string[];
  minSelect?: number;
  maxSelect?: number;
  maxFiles?: number;
  maxSize?: number;
  mimeTypes?: string[];
  thumbs?: string[];
  protected?: boolean;
  options?: Record<string, unknown>;
};

type PasswordAuthConfig = {
  enabled?: boolean;
  identityFields?: string[];
};

type OtpConfig = {
  enabled?: boolean;
  duration?: number;
  length?: number;
};

type MfaConfig = {
  enabled?: boolean;
  duration?: number;
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
};

function adminRouteFromHash(hash: string): AdminRoute | null {
  if (!hash || !hash.startsWith("#/")) return null;
  const segments = hash
    .slice(2)
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
    return { view: "logs" };
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
    return { view: settingsRoutes[segments[1] ?? ""] ?? "settings" };
  }
  if (segments[0] === "collections") {
    const collectionName = segments[1];
    const view = segments[2] === "schema" ? "schema" : "records";
    return collectionName ? { view, collectionName } : { view: "records" };
  }
  return null;
}

function adminHashFor(view: ViewName, collectionName?: string) {
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
  if (view === "logs") return "#/logs";
  if (settingsRoutes[view]) return settingsRoutes[view]!;
  const base = collectionName ? `#/collections/${encodeURIComponent(collectionName)}` : "#/collections";
  return view === "schema" ? `${base}/schema` : `${base}/records`;
}

type CollectionEditorState = {
  mode: "create" | "edit";
  collection?: CollectionSchema;
};

type RecordEditorState = {
  record?: RecordItem;
};

type ToastState = {
  kind: "ok" | "error";
  message: string;
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
const THEME_KEY = "pbj_theme";
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
  const [health, setHealth] = useState<HealthResponse["data"] | null>(null);
  const [setupRequired, setSetupRequired] = useState(true);
  const [confirmState, setConfirmState] = useState<PendingConfirm | null>(null);
  const [apiPreviewOpen, setApiPreviewOpen] = useState(false);
  const [mfaChallenge, setMfaChallenge] = useState<MfaChallenge | null>(null);
  const [otpCode, setOtpCode] = useState("");
  const [collections, setCollections] = useState<CollectionSchema[]>([]);
  const [selectedName, setSelectedName] = useState<string>("");
  const [records, setRecords] = useState<RecordItem[]>([]);
  const [recordPage, setRecordPage] = useState<ListResponse<RecordItem> | null>(null);
  const [query, setQuery] = useState<QueryState>({ filter: "", sort: "-created", perPage: 50 });
  const [view, setView] = useState<ViewName>("records");
  const [collectionSearch, setCollectionSearch] = useState("");
  const [loading, setLoading] = useState(false);
  const [toast, setToast] = useState<ToastState | null>(null);
  const [authEmail, setAuthEmail] = useState("");
  const [authPassword, setAuthPassword] = useState("");
  const [collectionEditor, setCollectionEditor] = useState<CollectionEditorState | null>(null);
  const [recordEditor, setRecordEditor] = useState<RecordEditorState | null>(null);
  const [backups, setBackups] = useState<BackupInfo[]>([]);
  const [backupName, setBackupName] = useState("");
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [settingsDraft, setSettingsDraft] = useState("");
  const [logs, setLogs] = useState<LogItem[]>([]);
  const [logPage, setLogPage] = useState<ListResponse<LogItem> | null>(null);
  const [logFilter, setLogFilter] = useState("");
  const [logStats, setLogStats] = useState<LogStat[]>([]);
  const [crons, setCrons] = useState<CronJob[]>([]);
  const [oauthProviders, setOauthProviders] = useState<OAuthProviderMetadata[]>([]);
  const [authMethods, setAuthMethods] = useState<AuthMethodsResponse | null>(null);
  const [oauthResult, setOauthResult] = useState<OAuthResultState | null>(null);
  const [oauthTestingProvider, setOauthTestingProvider] = useState<string>("");
  const [themeMode, setThemeMode] = useState<ThemeMode>(readThemeMode);
  const [resolvedTheme, setResolvedTheme] = useState<ResolvedTheme>(() => resolveThemeMode(readThemeMode()));
  const [pinnedCollectionNames, setPinnedCollectionNames] = useState<string[]>(() =>
    readStringArray(PINNED_COLLECTIONS_KEY)
  );
  const [hiddenColumnsByCollection, setHiddenColumnsByCollection] = useState<Record<string, string[]>>(() =>
    readStringArrayRecord(HIDDEN_COLUMNS_KEY)
  );
  const [selectedRecordIds, setSelectedRecordIds] = useState<string[]>([]);
  const [sqlQuery, setSqlQuery] = useState("select 1");
  const [sqlResult, setSqlResult] = useState<SqlResult | null>(null);
  const [sqlError, setSqlError] = useState("");
  const [exportDraft, setExportDraft] = useState("");
  const [importDraft, setImportDraft] = useState("");
  const [deleteMissingCollections, setDeleteMissingCollections] = useState(true);
  const [testEmail, setTestEmail] = useState("");
  const [testEmailTemplate, setTestEmailTemplate] = useState("verification");
  const [testS3Target, setTestS3Target] = useState("storage");
  const backupUploadRef = useRef<HTMLInputElement>(null);
  const lastSelectedId = useRef<string | null>(null);

  const authenticated = Boolean(token) && !setupRequired;
  const collectionView = view === "records" || view === "schema";
  const settingsView = isSettingsView(view);
  const selected = useMemo(
    () => collections.find((collection) => collection.name === selectedName) ?? null,
    [collections, selectedName]
  );

  const navigateTo = useCallback(
    (nextView: ViewName, collectionName = selectedName) => {
      if ((nextView === "records" || nextView === "schema") && collectionName) {
        setSelectedName(collectionName);
      }
      setView(nextView);
      const nextHash = adminHashFor(nextView, collectionName);
      if (window.location.hash !== nextHash) {
        window.location.hash = nextHash;
      }
    },
    [selectedName]
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
    setView(route.view);
  }, [authenticated, collections, hash]);

  const visibleCollections = useMemo(() => {
    const search = collectionSearch.trim().toLowerCase();
    if (!search) return collections;
    return collections.filter((collection) => {
      return collection.name.toLowerCase().includes(search) || collection.type.toLowerCase().includes(search);
    });
  }, [collectionSearch, collections]);

  const hiddenColumns = useMemo(() => {
    if (!selected) return [];
    return hiddenColumnsByCollection[selected.name] ?? [];
  }, [hiddenColumnsByCollection, selected]);

  const notify = useCallback((message: string, kind: ToastState["kind"] = "ok") => {
    setToast({ message, kind });
    window.clearTimeout((notify as unknown as { timer?: number }).timer);
    (notify as unknown as { timer?: number }).timer = window.setTimeout(() => setToast(null), 3200);
  }, []);

  const api = useCallback(
    async <T,>(path: string, options: ApiOptions = {}): Promise<T> => {
      return apiRequest<T>(path, token, options);
    },
    [token]
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
        return data.items.find((collection) => collection.name !== "_superusers")?.name ?? data.items[0]?.name ?? "";
      });
    } finally {
      setLoading(false);
    }
  }, [token]);

  const refreshRecords = useCallback(
    async (collectionName = selectedName, nextQuery = query, page = 1) => {
      if (!token || !collectionName) return;
      setLoading(true);
      try {
        const collection = collections.find((item) => item.name === collectionName);
        // Eagerly expand first-level relations so cells can show summaries instead of raw ids.
        const relationFields = (collection?.fields ?? [])
          .filter((field) => field.type === "relation" && !field.hidden)
          .map((field) => field.name);
        const qs = buildQuery({
          page,
          perPage: nextQuery.perPage,
          sort: nextQuery.sort,
          filter: normalizeSearchFilter(nextQuery.filter, collection),
          ...(relationFields.length ? { expand: relationFields.join(",") } : {})
        });
        const data = await apiRequest<ListResponse<RecordItem>>(
          `/api/collections/${encodeURIComponent(collectionName)}/records?${qs}`,
          token
        );
        setRecordPage(data);
        setRecords((prev) => (page > 1 ? [...prev, ...data.items] : data.items));
      } finally {
        setLoading(false);
      }
    },
    [collections, query, selectedName, token]
  );

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
        filter: normalizeSearchFilter(params.filter, collection)
      });
      return apiRequest(`/api/collections/${encodeURIComponent(collectionName)}/records?${qs}`, token);
    },
    [collections, token]
  );

  const loadMoreRecords = useCallback(async () => {
    if (!selectedName || !recordPage) return;
    await refreshRecords(selectedName, query, recordPage.page + 1);
  }, [query, recordPage, refreshRecords, selectedName]);

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

  const refreshLogs = useCallback(
    async (page = 1) => {
      if (!token) return;
      setLoading(true);
      try {
        const filter = normalizeSearchTerm(logFilter, LOG_SEARCH_FIELDS);
        const qs = buildQuery({ page, perPage: 50, sort: "-created", filter });
        const statsQs = buildQuery({ filter });
        const [logData, statsData] = await Promise.all([
          apiRequest<ListResponse<LogItem>>(`/api/logs?${qs}`, token),
          // Keep the chart in sync with the active filter, like the official UI does.
          apiRequest<LogStat[]>(`/api/logs/stats${statsQs ? `?${statsQs}` : ""}`, token)
        ]);
        setLogPage(logData);
        setLogs((prev) => (page > 1 ? [...prev, ...logData.items] : logData.items));
        setLogStats(statsData);
      } finally {
        setLoading(false);
      }
    },
    [logFilter, token]
  );

  const loadMoreLogs = useCallback(async () => {
    if (!logPage) return;
    await refreshLogs(logPage.page + 1);
  }, [logPage, refreshLogs]);

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

  const refreshAll = useCallback(async () => {
    try {
      const [, bootstrap] = await Promise.all([refreshHealth(), refreshBootstrapStatus()]);
      if (token && !bootstrap.required) {
        await refreshCollections();
        await refreshOauthProviders();
      }
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }, [notify, refreshBootstrapStatus, refreshCollections, refreshHealth, refreshOauthProviders, token]);

  useEffect(() => {
    refreshAll();
  }, [refreshAll]);

  useEffect(() => {
    const handleUnauthorized = () => {
      logout();
    };
    window.addEventListener("pbj_unauthorized", handleUnauthorized);
    return () => window.removeEventListener("pbj_unauthorized", handleUnauthorized);
  }, []);

  useEffect(() => {
    localStorage.setItem(PINNED_COLLECTIONS_KEY, JSON.stringify(pinnedCollectionNames));
  }, [pinnedCollectionNames]);

  useEffect(() => {
    localStorage.setItem(HIDDEN_COLUMNS_KEY, JSON.stringify(hiddenColumnsByCollection));
  }, [hiddenColumnsByCollection]);

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

  useEffect(() => {
    setSelectedRecordIds([]);
  }, [records, selectedName]);

  useEffect(() => {
    if (authenticated && selectedName && view === "records") {
      refreshRecords(selectedName).catch((error) => notify(errorMessage(error), "error"));
    }
  }, [authenticated, notify, refreshRecords, selectedName, view]);

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
    if (authenticated && view === "export") {
      setExportDraft(JSON.stringify(collections, null, 2));
    }
  }, [authenticated, collections, view]);

  useEffect(() => {
    if (authenticated && view === "logs") {
      refreshLogs().catch((error) => notify(errorMessage(error), "error"));
    }
  }, [authenticated, notify, refreshLogs, view]);

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

  async function completeAuth(auth: AuthResponse) {
    setAuthToken(auth.token);
    setAuthEmail("");
    setAuthPassword("");
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

  async function handleAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const email = authEmail.trim();
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
      if (error instanceof ApiRequestError && error.mfaId) {
        try {
          await requestOtp(email, error.mfaId);
        } catch (otpError) {
          notify(errorMessage(otpError), "error");
        }
      } else {
        notify(errorMessage(error), "error");
      }
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
    setToken(nextToken);
    if (nextToken) {
      localStorage.setItem(TOKEN_KEY, nextToken);
    } else {
      localStorage.removeItem(TOKEN_KEY);
    }
  }

  function logout() {
    setAuthToken("");
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

  async function saveCollection(payload: CollectionPayload) {
    try {
      if (collectionEditor?.mode === "edit" && collectionEditor.collection) {
        await api(`/api/collections/${encodeURIComponent(collectionEditor.collection.name)}`, {
          method: "PATCH",
          body: payload
        });
        notify(t("notifications.collection_saved", "Collection saved"));
      } else {
        await api("/api/collections", { method: "POST", body: payload });
        notify(t("notifications.collection_created", "Collection created"));
      }
      setCollectionEditor(null);
      await refreshCollections();
    } catch (error) {
      notify(errorMessage(error), "error");
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
      const id = recordEditor?.record?.id;
      const path = id
        ? `/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(id)}`
        : `/api/collections/${encodeURIComponent(selected.name)}/records`;
      const saved = await api<RecordItem>(path, { method: id ? "PATCH" : "POST", body });
      notify(id ? t("notifications.record_saved", "Record saved") : t("notifications.record_created", "Record created"));
      if (options.close !== false) {
        setRecordEditor(null);
      } else if (id) {
        setRecordEditor({ record: saved });
      }
      await refreshRecords(selected.name);
    } catch (error) {
      notify(errorMessage(error), "error");
      throw error;
    }
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
      await refreshRecords(selected.name);
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
      await Promise.all(
        selectedRecordIds.map((id) =>
          api(`/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(id)}`, {
            method: "DELETE"
          })
        )
      );
      notify(t("notifications.records_deleted", "Records deleted"));
      setSelectedRecordIds([]);
      await refreshRecords(selected.name);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  function togglePinnedCollection(collection: CollectionSchema) {
    setPinnedCollectionNames((current) => {
      if (current.includes(collection.name)) return current.filter((name) => name !== collection.name);
      return [collection.name, ...current];
    });
  }

  function toggleRecordSelection(id: string, extendRange = false) {
    // Shift+Click applies the new state across the whole span since the last click (PB v0.39.8).
    if (extendRange && lastSelectedId.current && lastSelectedId.current !== id) {
      const ids = records.map((record) => record.id);
      const from = ids.indexOf(lastSelectedId.current);
      const to = ids.indexOf(id);
      if (from !== -1 && to !== -1) {
        const span = ids.slice(Math.min(from, to), Math.max(from, to) + 1);
        setSelectedRecordIds((current) => {
          const selecting = !current.includes(id);
          const next = new Set(current);
          for (const item of span) {
            if (selecting) next.add(item);
            else next.delete(item);
          }
          return [...next];
        });
        lastSelectedId.current = id;
        return;
      }
    }
    lastSelectedId.current = id;
    setSelectedRecordIds((current) => {
      if (current.includes(id)) return current.filter((item) => item !== id);
      return [...current, id];
    });
  }

  function toggleCurrentPageSelection(checked: boolean) {
    if (!checked) {
      setSelectedRecordIds([]);
      return;
    }
    setSelectedRecordIds(records.map((record) => record.id));
  }

  function toggleColumn(column: string) {
    if (!selected) return;
    setHiddenColumnsByCollection((current) => {
      const existing = new Set(current[selected.name] ?? []);
      if (existing.has(column)) {
        existing.delete(column);
      } else {
        existing.add(column);
      }
      return { ...current, [selected.name]: Array.from(existing) };
    });
  }

  function resetColumns() {
    if (!selected) return;
    setHiddenColumnsByCollection((current) => {
      const next = { ...current };
      delete next[selected.name];
      return next;
    });
  }

  async function openFile(record: RecordItem, filename: string) {
    if (!selected) return;
    try {
      let tokenQuery = "";
      try {
        const fileToken = await api<{ token: string }>("/api/files/token", { method: "POST" });
        tokenQuery = fileToken.token ? `?token=${encodeURIComponent(fileToken.token)}` : "";
      } catch {
        tokenQuery = "";
      }
      const url = `/api/files/${encodeURIComponent(selected.name)}/${encodeURIComponent(record.id)}/${encodeURIComponent(filename)}${tokenQuery}`;
      window.open(url, "_blank", "noopener,noreferrer");
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function createBackup() {
    try {
      await api("/api/backups", { method: "POST", body: backupName.trim() ? { name: backupName.trim() } : {} });
      setBackupName("");
      notify(t("notifications.backup_created", "Backup created"));
      await refreshBackups();
    } catch (error) {
      notify(errorMessage(error), "error");
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
    try {
      await api(`/api/backups/${encodeURIComponent(backup.key)}/restore`, { method: "POST" });
      notify(t("notifications.backup_restored", "Backup restored"));
      await refreshCollections();
      await refreshBackups();
    } catch (error) {
      notify(errorMessage(error), "error");
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

  async function saveSettings() {
    try {
      const parsed = JSON.parse(settingsDraft || "{}") as AppSettings;
      // Rules are resolved in order, so normalise priority on save like the official UI.
      const limits = isPlainObject(parsed.rateLimits) ? parsed.rateLimits : null;
      if (limits && Array.isArray(limits.rules)) {
        limits.rules = sortRateLimitRules(limits.rules as RateLimitRule[]);
      }
      await api<AppSettings>("/api/settings", { method: "PATCH", body: parsed });
      // Re-read instead of trusting the PATCH echo: the server normalises settings
      // (dedupes rate limit rules, redacts secrets) and the response can differ.
      await refreshSettings();
      notify(t("notifications.settings_saved", "Settings saved"));
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function testEmailSettings() {
    try {
      await api("/api/settings/test/email", {
        method: "POST",
        body: {
          email: testEmail.trim(),
          template: testEmailTemplate || "verification"
        }
      });
      notify(t("notifications.test_email_queued", "Test email queued"));
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function testS3Settings() {
    try {
      await api("/api/settings/test/s3", {
        method: "POST",
        body: {
          filesystem: testS3Target
        }
      });
      notify(t("notifications.s3_check_completed", "S3 connection check completed"));
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function importCollections() {
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
      setExportDraft(JSON.stringify(collectionsPayload, null, 2));
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function runSql() {
    const statement = DANGEROUS_SQL.find((keyword) =>
      new RegExp(`^\\s*${keyword}\\b`, "i").test(sqlQuery)
    );
    if (statement) {
      const confirmed = await confirm({
        title: t("confirm.run_sql_title", "Run write statement"),
        message: t("confirm.run_sql_body", {
          statement: statement.toUpperCase(),
          defaultValue:
            "This query starts with {{statement}} and can modify or destroy data. Are you sure you want to execute it?"
        }),
        confirmLabel: t("actions.execute", "Execute"),
        danger: true
      });
      if (!confirmed) return;
    }
    setSqlError("");
    setLoading(true);
    try {
      const result = await api<SqlResult>("/api/sql", { method: "POST", body: { query: sqlQuery } });
      setSqlResult(result);
      notify(t("notifications.sql_executed", "SQL executed"));
      await refreshCollections();
    } catch (error) {
      const message = errorMessage(error);
      setSqlError(message);
      notify(message, "error");
    } finally {
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
  const showWorkspaceTopbar = !authenticated || (!collectionView && !settingsView && view !== "logs");

  if (hash.startsWith('#/pbinstall/') || hash.startsWith('#/request-password-reset') || hash.startsWith('#/auth/confirm-')) {
    return <AuthActionPages />;
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <button
          className="logo"
          onClick={() => {
            if (selectedName) navigateTo("records");
          }}
          aria-label={t("nav.open_collections", "Open collections")}
        >
          <span className="brand-mark">PB</span>
          <span className="brand-title">pocketbase-java</span>
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
          <ThemeSelector mode={themeMode} resolvedTheme={resolvedTheme} onChange={(nextMode) => {
            setThemeMode(nextMode);
            localStorage.setItem(THEME_KEY, nextMode);
          }} />
          <StatusPill health={health} loading={loading} />
          <button className="icon-button header-icon" onClick={refreshAll} title={t("actions.refresh")} aria-label={t("actions.refresh")}>
            <RefreshCw size={17} />
          </button>
          <button className="icon-button header-icon danger" onClick={logout} title={t("actions.logout")} aria-label={t("actions.logout")} disabled={!token}>
            <LogOut size={17} />
          </button>
        </div>
      </header>

      <div className={view === "logs" ? "app-body app-body-wide" : "app-body"}>
        {authenticated && !setupRequired && collectionView && (
          <CollectionSidebar
            collections={visibleCollections}
            currentName={selectedName}
            pinnedNames={pinnedCollectionNames}
            search={collectionSearch}
            onSearch={setCollectionSearch}
            onCreate={() => setCollectionEditor({ mode: "create" })}
            onSelect={(collection) => {
              navigateTo("records", collection.name);
            }}
            onTogglePinned={togglePinnedCollection}
          />
        )}

        {authenticated && !setupRequired && settingsView && (
          <SettingsSidebar current={view} onSelect={navigateTo} />
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
              mfaChallenge={mfaChallenge}
              otpCode={otpCode}
              onEmail={setAuthEmail}
              onPassword={setAuthPassword}
              onOtpCode={setOtpCode}
              onSubmit={handleAuth}
              onOtpSubmit={handleOtpSubmit}
              onResendOtp={resendOtp}
              onCancelMfa={cancelMfa}
            />
          ) : (
            <>
              {collectionView && (
                <div className="view-tabs" role="tablist" aria-label={t("collections.views", "Collection views")}>
                  <button className={view === "records" ? "active" : ""} onClick={() => navigateTo("records")}>
                    <Database size={16} />
                    {t("collections.records", "Records")}
                  </button>
                  <button className={view === "schema" ? "active" : ""} onClick={() => navigateTo("schema")}>
                    <ListFilter size={16} />
                    {t("collections.schema", "Schema")}
                  </button>
                </div>
              )}

              {view === "backups" ? (
                <BackupView
                  backups={backups}
                  settings={settings}
                  draft={settingsDraft}
                  backupName={backupName}
                  canBackup={Boolean(health?.canBackup)}
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
                  loading={loading}
                  collections={collections}
                  onDraft={setSettingsDraft}
                  onRefresh={refreshSettings}
                  onSave={saveSettings}
                />
              ) : view === "mail" ? (
                <MailSettingsView
                  settings={settings}
                  draft={settingsDraft}
                  email={testEmail}
                  template={testEmailTemplate}
                  loading={loading}
                  onDraft={setSettingsDraft}
                  onSave={saveSettings}
                  onEmail={setTestEmail}
                  onTemplate={setTestEmailTemplate}
                  onTest={testEmailSettings}
                />
              ) : view === "storage" ? (
                <StorageSettingsView
                  settings={settings}
                  draft={settingsDraft}
                  target={testS3Target}
                  loading={loading}
                  onDraft={setSettingsDraft}
                  onSave={saveSettings}
                  onTarget={setTestS3Target}
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
                  loading={loading}
                  sqlCompletions={sqlCompletions}
                  onQuery={setSqlQuery}
                  onRun={runSql}
                />
              ) : view === "logs" ? (
                <LogsView
                  logs={logs}
                  logPage={logPage}
                  filter={logFilter}
                  stats={logStats}
                  loading={loading}
                  onFilter={setLogFilter}
                  onRefresh={() => refreshLogs()}
                  onLoadMore={loadMoreLogs}
                />
              ) : view === "crons" ? (
                <CronsView crons={crons} loading={loading} onRefresh={refreshCrons} onRun={runCron} />
              ) : selected ? (
                view === "records" ? (
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
                    onQuery={setQuery}
                    onApply={(nextQuery) => refreshRecords(selected.name, nextQuery)}
                    onRefresh={() => refreshRecords(selected.name, query)}
                    onLoadMore={loadMoreRecords}
                    onEditCollection={() => setCollectionEditor({ mode: "edit", collection: selected })}
                    onApiPreview={() => setApiPreviewOpen(true)}
                    onNew={() => setRecordEditor({})}
                    onEdit={(record) => setRecordEditor({ record })}
                    onDelete={deleteRecord}
                    onDeleteSelected={deleteSelectedRecords}
                    onToggleColumn={toggleColumn}
                    onResetColumns={resetColumns}
                    onToggleSelected={toggleRecordSelection}
                    onToggleAll={toggleCurrentPageSelection}
                    onClearSelection={() => setSelectedRecordIds([])}
                    onOpenFile={openFile}
                  />
                ) : (
                  <SchemaView
                    collection={selected}
                    authMethods={authMethods}
                    oauthTestingProvider={oauthTestingProvider}
                    onEdit={() => setCollectionEditor({ mode: "edit", collection: selected })}
                    onDelete={() => deleteCollection(selected)}
                    onOAuthTest={startOAuthTest}
                    onCopy={(value) => {
                      navigator.clipboard.writeText(value).then(
                        () => notify(t("notifications.copied", "Copied")),
                        (error) => notify(errorMessage(error), "error")
                      );
                    }}
                  />
                )
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
          onSubmit={(payload) => saveCollection(payload)}
        />
      )}

      {recordEditor && selected && (
        <RecordModal
          collection={selected}
          collections={collections}
          state={recordEditor}
          onClose={() => setRecordEditor(null)}
          onConfirm={confirm}
          fetchRecords={fetchRelationRecords}
          onSubmit={saveRecord}
        />
      )}

      {oauthResult && (
        <OAuthResultModal result={oauthResult} onClose={() => setOauthResult(null)} />
      )}

      {apiPreviewOpen && selected && (
        <ApiPreview collection={selected} baseUrl={window.location.origin} onClose={() => setApiPreviewOpen(false)} />
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

      {toast && <div className={`toast ${toast.kind}`}>{toast.message}</div>}
    </div>
  );
}

type AuthPanelProps = {
  setupRequired: boolean;
  email: string;
  password: string;
  loading: boolean;
  mfaChallenge: MfaChallenge | null;
  otpCode: string;
  onEmail: (value: string) => void;
  onPassword: (value: string) => void;
  onOtpCode: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onOtpSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onResendOtp: () => void;
  onCancelMfa: () => void;
};

function AuthPanel(props: AuthPanelProps) {
  const { t } = useTranslation();

  if (props.mfaChallenge) {
    return (
      <section className="auth-layout">
        <div className="auth-copy">
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
            {t("auth.otp_code", "One-time code")}
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
    <section className="auth-layout">
      <div className="auth-copy">
        <p className="eyebrow">{props.setupRequired ? t("auth.bootstrap", "Bootstrap") : t("auth.superuser", "Superuser")}</p>
        <h2>{props.setupRequired ? t("auth.create_first_superuser", "Create the first superuser") : t("auth.sign_in_manage", "Sign in to manage data")}</h2>
      </div>
      <form className="auth-form" onSubmit={props.onSubmit}>
        <label>
          {t("auth.email", "Email")}
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
          {t("auth.password", "Password")}
          <input
            id="superuser-password"
            name="password"
            type="password"
            autoComplete={props.setupRequired ? "new-password" : "current-password"}
            required
            minLength={8}
            value={props.password}
            onChange={(event) => props.onPassword(event.target.value)}
          />
        </label>
        <button className="primary submit" type="submit" disabled={props.loading}>
          <KeyRound size={16} />
          {props.setupRequired ? t("auth.create_and_sign_in", "Create and sign in") : t("auth.sign_in", "Sign in")}
        </button>
      </form>
    </section>
  );
}

type CollectionSidebarProps = {
  collections: CollectionSchema[];
  currentName: string;
  pinnedNames: string[];
  search: string;
  onSearch: (value: string) => void;
  onCreate: () => void;
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
  const system = props.collections.filter((collection) => !pinnedSet.has(collection.name) && isSystemCollection(collection));
  const noMatches = props.search.trim().length > 0 && props.collections.length === 0;

  return (
    <aside className="sidebar collections-sidebar">
      <div className="search-box sidebar-search">
        <Search size={15} />
        <input
          id="collection-search"
          name="collectionSearch"
          autoComplete="off"
          value={props.search}
          onChange={(event) => props.onSearch(event.target.value)}
          placeholder={t("collections.search_placeholder", "Search collections...")}
        />
        {props.search && (
          <button className="icon-button tiny" onClick={() => props.onSearch("")} title={t("actions.clear_search", "Clear search")} aria-label={t("actions.clear_search", "Clear search")}>
            <X size={14} />
          </button>
        )}
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
            />
          )}
        </nav>
      )}

      <div className="sidebar-actions">
        <button className="subtle outline-button" onClick={props.onCreate}>
          <Plus size={16} />
          {t("actions.new_collection", "New collection")}
        </button>
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
};

function CollectionGroup(props: CollectionGroupProps) {
  const { t } = useTranslation();
  return (
    <section className="sidebar-group">
      <div className="sidebar-section-title">{props.title}</div>
      {props.collections.map((collection) => {
        const pinned = props.pinnedNames.includes(collection.name);
        return (
          <div className={props.currentName === collection.name ? "collection-nav-row active" : "collection-nav-row"} key={collection.id || collection.name}>
            <button className="collection-nav-main" onClick={() => props.onSelect(collection)} title={collection.name}>
              <span className="nav-icon">
                {collection.type === "auth" ? <Shield size={16} /> : <Database size={16} />}
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
    </section>
  );
}

const getSettingsNavGroups = (t: any): Array<{
  title: string;
  items: Array<{ view: ViewName; label: string; icon: LucideIcon }>;
}> => [
  {
    title: t("settings.nav.application", "Application").toUpperCase(),
    items: [
      { view: "settings", label: t("settings.nav.general", "General"), icon: Settings },
      { view: "mail", label: t("settings.nav.mail", "Mail settings"), icon: Mail },
      { view: "storage", label: t("settings.nav.storage", "File storage"), icon: HardDrive }
    ]
  },
  {
    title: t("settings.nav.system", "System").toUpperCase(),
    items: [
      { view: "backups", label: t("settings.nav.backups", "Backups"), icon: FileArchive },
      { view: "crons", label: t("settings.nav.crons", "Crons"), icon: Clock3 },
      { view: "export", label: t("settings.nav.export", "Export collections"), icon: Download },
      { view: "import", label: t("settings.nav.import", "Import collections"), icon: Upload },
      { view: "sql", label: t("settings.nav.sql", "SQL console"), icon: Code2 }
    ]
  }
];

function SettingsSidebar({ current, onSelect }: { current: ViewName; onSelect: (view: ViewName) => void }) {
  const { t } = useTranslation();
  return (
    <aside className="sidebar settings-sidebar">
      {getSettingsNavGroups(t).map((group) => (
        <section className="sidebar-group" key={group.title}>
          <div className="sidebar-section-title">{group.title}</div>
          <nav className="settings-nav" aria-label={group.title}>
            {group.items.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={item.view}
                  className={current === item.view ? "active" : ""}
                  onClick={() => onSelect(item.view)}
                >
                  <span className="nav-icon">
                    <Icon size={16} />
                  </span>
                  <span className="nav-text">
                    <strong>{item.label}</strong>
                  </span>
                  <ChevronRight size={15} />
                </button>
              );
            })}
          </nav>
        </section>
      ))}
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
  onQuery: (query: QueryState) => void;
  onApply: (query: QueryState) => void;
  onRefresh: () => void | Promise<void>;
  onLoadMore: () => void | Promise<void>;
  onEditCollection: () => void;
  onApiPreview: () => void;
  onNew: () => void;
  onEdit: (record: RecordItem) => void;
  onDelete: (record: RecordItem) => void;
  onDeleteSelected: () => void;
  onToggleColumn: (column: string) => void;
  onResetColumns: () => void;
  onToggleSelected: (id: string, extendRange?: boolean) => void;
  onToggleAll: (checked: boolean) => void;
  onClearSelection: () => void;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function RecordsView(props: RecordsViewProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState(props.query);
  const [columnsOpen, setColumnsOpen] = useState(false);
  const selectedSet = useMemo(() => new Set(props.selectedIds), [props.selectedIds]);
  const allVisibleSelected =
    props.records.length > 0 && props.records.every((record) => selectedSet.has(record.id));
  const canCreateRecord = props.collection.type !== "view";
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

  function apply() {
    props.onQuery(draft);
    props.onApply(draft);
  }

  function updateSort(field: string, direction = sortState.direction) {
    setDraft({ ...draft, sort: formatSortValue(field, direction) });
  }

  function updateSortDirection(direction: SortDirection) {
    setDraft({ ...draft, sort: formatSortValue(sortState.field || sortableColumns[0] || "created", direction) });
  }

  return (
    <section className="records-page">
      <header className="page-header records-page-header">
        <nav className="breadcrumbs" aria-label={t("common.breadcrumb", "Breadcrumb")}>
          <span>{t("nav.collections")}</span>
          <span title={props.collection.name}>{props.collection.name}</span>
        </nav>
        <div className="page-header-secondary-btns">
          <button
            className="icon-button page-circle"
            onClick={props.onApiPreview}
            title={t("collections.api_preview", "API Preview")}
            aria-label={t("collections.api_preview", "API Preview")}
          >
            <Code2 size={17} />
          </button>
          <button className="icon-button page-circle" onClick={props.onEditCollection} title={t("collections.collection_settings", "Collection settings")} aria-label={t("collections.collection_settings", "Collection settings")}>
            <Settings size={17} />
          </button>
          <button className="icon-button page-circle" onClick={props.onRefresh} title={t("actions.refresh_records", "Refresh records")} aria-label={t("actions.refresh_records", "Refresh records")}>
            <RefreshCw size={17} />
          </button>
        </div>
        {canCreateRecord && (
          <div className="page-header-primary-btns">
            <button className="primary new-record-btn" onClick={props.onNew}>
              <Plus size={16} />
              <span>{t("actions.add_record")}</span>
            </button>
          </div>
        )}
      </header>

      <div className="records-searchbar-row">
        <div className="searchbar records-searchbar">
          <Search size={17} />
          <input
            id="records-filter"
            name="filter"
            autoComplete="off"
            aria-label={t("logs.search_aria", "Search term or filter")}
            value={draft.filter}
            onChange={(event) => setDraft({ ...draft, filter: event.target.value })}
            onKeyDown={(event) => {
              if (event.key === "Enter") apply();
            }}
            placeholder={t("records.search_placeholder", "Search term or filter...")}
          />
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

      {props.selectedIds.length > 0 && (
        <div className="bulkbar">
          <span>{t("transfer.selected_count", { count: props.selectedIds.length, defaultValue: "{{count}} selected" })}</span>
          <button className="subtle" onClick={props.onClearSelection}>
            <X size={16} />
            {t("actions.clear", "Clear")}
          </button>
          <button className="danger subtle" onClick={props.onDeleteSelected}>
            <Trash2 size={16} />
            {t("actions.delete_selected", "Delete selected")}
          </button>
        </div>
      )}

      <div className="page-table-wrapper">
        <table className="records-table responsive-table">
          <thead>
            <tr>
              <th className="select-col">
                <button
                  className="checkbox-button"
                  onClick={() => props.onToggleAll(!allVisibleSelected)}
                  title={allVisibleSelected ? t("actions.clear_selection", "Clear selection") : t("actions.select_page", "Select page")}
                  aria-label={allVisibleSelected ? t("actions.clear_selection", "Clear selection") : t("actions.select_page", "Select page")}
                >
                  {allVisibleSelected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                </button>
              </th>
              {props.columns.map((column) => (
                <th key={column}>{column}</th>
              ))}
              <th className="actions-col">{t("collections.actions")}</th>
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
                          const next = { ...draft, filter: "" };
                          setDraft(next);
                          props.onQuery(next);
                          props.onApply(next);
                        }}
                      >
                        {t("actions.clear_search", "Clear search")}
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ) : (
              props.records.map((record) => {
                const selected = selectedSet.has(record.id);
                return (
                  <tr className={selected ? "selected" : ""} key={record.id}>
                    <td className="select-col">
                      <button
                        className="checkbox-button"
                        onClick={(event) => props.onToggleSelected(record.id, event.shiftKey)}
                        title={selected ? t("actions.unselect_record", "Unselect record") : t("actions.select_record", "Select record")}
                        aria-label={selected ? t("actions.unselect_record", "Unselect record") : t("actions.select_record", "Select record")}
                      >
                        {selected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                      </button>
                    </td>
                    {props.columns.map((column) => (
                      <td key={column}>
                        <CellValue
                          collection={props.collection}
                          collections={props.collections}
                          column={column}
                          record={record}
                          onOpenFile={props.onOpenFile}
                        />
                      </td>
                    ))}
                    <td className="row-actions">
                      <button className="icon-button" onClick={() => props.onEdit(record)} title={t("actions.edit", "Edit")} aria-label={t("actions.edit", "Edit")}>
                        <Edit3 size={16} />
                      </button>
                      <button
                        className="icon-button danger"
                        onClick={() => props.onDelete(record)}
                        title={t("actions.delete", "Delete")}
                        aria-label={t("actions.delete", "Delete")}
                      >
                        <Trash2 size={16} />
                      </button>
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
      <footer className="page-footer">
        <span>
          {t("common.loaded_of_total", {
            loaded: props.records.length,
            count: props.recordPage?.totalItems ?? props.records.length,
            defaultValue: "Showing {{loaded}} of {{count}}"
          })}
        </span>
        <span>{t("collections.fields_count", { count: props.collection.fields?.length ?? 0, defaultValue: "{{count}} fields" })}</span>
        <span>{t("collections.columns_count", { shown: props.columns.length, total: props.allColumns.length, defaultValue: "{{shown}}/{{total}} columns" })}</span>
      </footer>
    </section>
  );
}

type CellValueProps = {
  collection: CollectionSchema;
  column: string;
  record: RecordItem;
  collections?: CollectionSchema[];
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function CellValue({ collection, collections, column, record, onOpenFile }: CellValueProps) {
  const field = collection.fields?.find((item) => item.name === column);
  const value = record[column];

  if (field?.type === "relation" && value) {
    const ids = (Array.isArray(value) ? value : [value]).map(String).filter(Boolean);
    const target = collections?.find((item) => item.id === field.collectionId);
    const expand = isPlainObject(record.expand) ? (record.expand as Record<string, unknown>) : undefined;
    const expanded = expand?.[column];
    const expandedList = expanded === undefined ? [] : Array.isArray(expanded) ? expanded : [expanded];
    const summaries = new Map<string, string>();
    for (const item of expandedList) {
      if (isPlainObject(item) && typeof item.id === "string") {
        summaries.set(item.id, recordSummary(item as RecordItem, target));
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
    return (
      <div className="file-list">
        {files.filter(Boolean).map((filename) => (
          <button
            className="file-pill"
            key={String(filename)}
            onClick={() => onOpenFile(record, String(filename))}
            title={String(filename)}
          >
            <Download size={13} />
            {String(filename)}
          </button>
        ))}
      </div>
    );
  }

  if (typeof value === "boolean") {
    return <span className={value ? "bool yes" : "bool no"}>{value ? "true" : "false"}</span>;
  }

  return <code>{formatValue(value)}</code>;
}

type SchemaViewProps = {
  collection: CollectionSchema;
  authMethods: AuthMethodsResponse | null;
  oauthTestingProvider: string;
  onEdit: () => void;
  onDelete: () => void;
  onOAuthTest: (provider: AuthMethodProvider) => void;
  onCopy: (value: string) => void;
};

function SchemaView({ collection, authMethods, oauthTestingProvider, onEdit, onDelete, onOAuthTest, onCopy }: SchemaViewProps) {
  const { t } = useTranslation();
  const json = JSON.stringify(collection, null, 2);
  return (
    <section className="schema-layout">
      <div className="schema-summary">
        <div className="summary-row">
          <span>{t("collections.id")}</span>
          <code>{collection.id}</code>
        </div>
        <div className="summary-row">
          <span>{t("collections.type")}</span>
          <strong>{collection.type}</strong>
        </div>
        <div className="summary-row">
          <span>{t("collections.system")}</span>
          <strong>{collection.system ? "true" : "false"}</strong>
        </div>
        <div className="schema-actions">
          <button className="primary" onClick={onEdit}>
            <Edit3 size={16} />
            {t("actions.edit_schema", "Edit schema")}
          </button>
          <button className="subtle" onClick={() => onCopy(json)}>
            <Copy size={16} />
            {t("actions.copy_json", "Copy JSON")}
          </button>
          <button className="danger subtle" onClick={onDelete} disabled={collection.system}>
            <Trash2 size={16} />
            {t("actions.delete", "Delete")}
          </button>
        </div>
      </div>

      {collection.type === "auth" && authMethods && (
        <div className="auth-methods-panel">
          <article className="auth-method-card">
            <header>
              <strong>{t("auth.password")}</strong>
              <span>{authMethods.password.enabled ? t("common.enabled_status", "enabled") : t("common.disabled_status", "disabled")}</span>
            </header>
            <p>{authMethods.password.identityFields.join(", ") || t("common.none", "none")}</p>
          </article>
          <article className="auth-method-card">
            <header>
              <strong>{t("auth.otp")}</strong>
              <span>{authMethods.otp.enabled ? t("common.enabled_status", "enabled") : t("common.disabled_status", "disabled")}</span>
            </header>
            <p>{authMethods.otp.enabled ? t("auth.otp_window", { seconds: authMethods.otp.duration, defaultValue: "{{seconds}}s window" }) : t("auth.no_otp", "No one-time passwords")}</p>
          </article>
          <article className="auth-method-card">
            <header>
              <strong>{t("auth.mfa")}</strong>
              <span>{authMethods.mfa.enabled ? t("common.enabled_status", "enabled") : t("common.disabled_status", "disabled")}</span>
            </header>
            <p>{authMethods.mfa.enabled ? t("auth.mfa_challenge", { seconds: authMethods.mfa.duration, defaultValue: "{{seconds}}s challenge" }) : t("auth.no_second_factor", "No second factor")}</p>
          </article>
          <article className="auth-method-card auth-method-card-wide">
            <header>
              <strong>{t("settings.oauth2")}</strong>
              <span>{authMethods.oauth2.enabled ? t("common.enabled_status", "enabled") : t("common.disabled_status", "disabled")}</span>
            </header>
            {authMethods.oauth2.providers.length === 0 ? (
              <p>{t("settings.no_providers")}</p>
            ) : (
              <div className="provider-chip-list">
                {authMethods.oauth2.providers.map((provider) => (
                  <div className="provider-chip provider-chip-detailed" key={provider.name}>
                    <div className="provider-chip-header">
                      <strong>{provider.displayName || provider.name}</strong>
                      <button
                        className="subtle provider-test-button"
                        onClick={() => onOAuthTest(provider)}
                        disabled={!provider.authURL || oauthTestingProvider === provider.name}
                      >
                        {oauthTestingProvider === provider.name ? t("common.waiting", "Waiting...") : t("actions.test", "Test")}
                      </button>
                    </div>
                    <span>{provider.authURL ? t("common.ready", "ready") : t("settings.missing_credentials", "missing credentials")}</span>
                  </div>
                ))}
              </div>
            )}
          </article>
        </div>
      )}

      <div className="field-grid">
        {(collection.fields ?? []).map((field) => (
          <article className="field-row" key={field.id || field.name}>
            <div>
              <strong>{field.name}</strong>
              <span>{field.type}</span>
            </div>
            <div className="chips">
              {field.required && <span>{t("collections.required")}</span>}
              {field.unique && <span>{t("collections.unique")}</span>}
              {field.hidden && <span>{t("collections.hidden")}</span>}
              {field.protected && <span>{t("collections.protected")}</span>}
            </div>
          </article>
        ))}
      </div>

      <pre className="json-panel">{json}</pre>
    </section>
  );
}

type BackupViewProps = {
  backups: BackupInfo[];
  settings: AppSettings | null;
  draft: string;
  backupName: string;
  canBackup: boolean;
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
            <button className="icon-button page-circle" onClick={props.onRefresh} title={t("actions.refresh_backups", "Refresh backups")} aria-label={t("actions.refresh_backups", "Refresh backups")}>
              <RefreshCw size={17} />
            </button>
            <button className="icon-button page-circle" onClick={() => props.uploadRef.current?.click()} title={t("actions.upload_backup", "Upload backup")} aria-label={t("actions.upload_backup", "Upload backup")}>
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
          <button className="primary" onClick={() => setCreateOpen(true)} disabled={!props.canBackup || props.loading}>
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
                  <button className="icon-button" onClick={() => props.onRestore(backup)} title={t("actions.restore", "Restore")} aria-label={t("actions.restore", "Restore")}>
                    <FileUp size={16} />
                  </button>
                  <button className="icon-button danger" onClick={() => props.onDelete(backup)} title={t("actions.delete", "Delete")} aria-label={t("actions.delete", "Delete")}>
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
            <label className="check-row switch-row">
              <input
                id="enable-auto-backups"
                name="enableAutoBackups"
                type="checkbox"
                checked={autoBackupsEnabled}
                onChange={(event) => toggleAutoBackups(event.target.checked)}
              />
              {t("settings.enable_auto_backups", "Enable auto backups")}
            </label>
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
              <label className="check-row switch-row">
                <input
                  id="backups-s3-enabled"
                  name="backups.s3.enabled"
                  type="checkbox"
                  checked={backupS3Enabled}
                  onChange={(event) => updateSetting(["backups", "s3", "enabled"], event.target.checked)}
                />
                {t("settings.store_backups_s3", "Store backups in S3 storage")}
              </label>
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
                      <input
                        id="backups-s3-secret"
                        name="backups.s3.secret"
                        type="password"
                        autoComplete="new-password"
                        value={String(backupS3.secret ?? "")}
                        placeholder={hasBackupS3Secret ? "" : "* * * * * *"}
                        onChange={(event) => updateSetting(["backups", "s3", "secret"], event.target.value)}
                      />
                    </label>
                  </div>
                  <label className="check-row switch-row">
                    <input
                      id="backups-s3-force-path-style"
                      name="backups.s3.forcePathStyle"
                      type="checkbox"
                      checked={Boolean(backupS3.forcePathStyle)}
                      onChange={(event) => updateSetting(["backups", "s3", "forcePathStyle"], event.target.checked)}
                    />
                    {t("settings.force_path_style", "Force path-style addressing")}
                  </label>
                </>
              )}
            </section>

            <div className="backup-options-actions">
              <button className="primary" type="button" onClick={props.onSave} disabled={props.loading}>
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
              <button type="button" className="primary" onClick={startBackup} disabled={!props.canBackup || props.loading}>
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
  const sortedCrons = useMemo(() => [...props.crons].sort((left, right) => left.id.localeCompare(right.id)), [props.crons]);

  async function runCron(job: CronJob) {
    if (runningCronId) return;
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
          <button className="icon-button page-circle" onClick={props.onRefresh} title={t("actions.refresh_crons", "Refresh crons")} aria-label={t("actions.refresh_crons", "Refresh crons")}>
            <RefreshCw size={17} />
          </button>
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
                    disabled={props.loading || Boolean(runningCronId)}
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
  loading: boolean;
  collections: CollectionSchema[];
  onDraft: (value: string) => void;
  onRefresh: () => void;
  onSave: () => void;
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
  const logs = settingsObject(draftSettings, "logs");
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

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.application", "Application")}
        actions={
          <>
          <button className="icon-button" onClick={props.onRefresh} title={t("actions.refresh_settings", "Refresh settings")} aria-label={t("actions.refresh_settings", "Refresh settings")}>
            <RefreshCw size={17} />
          </button>
          <button className="primary" onClick={props.onSave} disabled={props.loading}>
            <Save size={16} />
            {t("actions.save_settings", "Save settings")}
          </button>
          </>
        }
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
          <label>
            {t("settings.accent_color", "Accent color")}
            <input
              id="meta-accent-color"
              name="meta.accentColor"
              type="color"
              value={String(meta.accentColor ?? "#1055c9")}
              onChange={(event) => updateSetting(["meta", "accentColor"], event.target.value)}
            />
          </label>
        </div>

        <div className="settings-switch-row">
          <label className="check-row switch-row">
            <input
              id="meta-hide-controls"
              name="meta.hideControls"
              type="checkbox"
              checked={Boolean(meta.hideControls)}
              onChange={(event) => updateSetting(["meta", "hideControls"], event.target.checked)}
            />
            {t("settings.hide_controls", "Hide/Lock collection and record controls")}
          </label>
        </div>

        <section className="settings-accordion-grid">
          <article className="settings-accordion-card">
            <header>
              <div>
                <strong>{t("nav.logs", "Logs")}</strong>
                <span>{t("settings.logs_desc", "Retention and request metadata")}</span>
              </div>
              <Activity size={18} />
            </header>
            <div className="settings-form-row two">
              <label>
                {t("settings.max_days", "Max days")}
                <input
                  id="logs-max-days"
                  name="logs.maxDays"
                  type="number"
                  min="0"
                  value={String(logs.maxDays ?? 5)}
                  onChange={(event) => updateNumber(["logs", "maxDays"], event.target.value)}
                />
              </label>
              <label>
                {t("settings.min_level", "Min level")}
                <input
                  id="logs-min-level"
                  name="logs.minLevel"
                  type="number"
                  value={String(logs.minLevel ?? 0)}
                  onChange={(event) => updateNumber(["logs", "minLevel"], event.target.value)}
                />
              </label>
            </div>
            <label className="check-row switch-row">
              <input
                id="logs-log-ip"
                name="logs.logIP"
                type="checkbox"
                checked={Boolean(logs.logIP)}
                onChange={(event) => updateSetting(["logs", "logIP"], event.target.checked)}
              />
              {t("settings.log_request_ip", "Log request IP")}
            </label>
            <label className="check-row switch-row">
              <input
                id="logs-log-auth-id"
                name="logs.logAuthId"
                type="checkbox"
                checked={Boolean(logs.logAuthId)}
                onChange={(event) => updateSetting(["logs", "logAuthId"], event.target.checked)}
              />
              {t("settings.log_auth_id", "Log auth record id")}
            </label>
          </article>

          <article className="settings-accordion-card">
            <header>
              <div>
                <strong>{t("settings.batch_requests", "Batch requests")}</strong>
                <span>{t("settings.batch_desc", "Server-side batch request limits")}</span>
              </div>
              <Archive size={18} />
            </header>
            <label className="check-row switch-row">
              <input
                id="batch-enabled"
                name="batch.enabled"
                type="checkbox"
                checked={Boolean(batch.enabled)}
                onChange={(event) => updateSetting(["batch", "enabled"], event.target.checked)}
              />
              {t("settings.enable_batch_api", "Enable batch API")}
            </label>
            <div className="settings-form-row three">
              <label>
                {t("settings.max_requests", "Max requests")}
                <input
                  id="batch-max-requests"
                  name="batch.maxRequests"
                  type="number"
                  value={String(batch.maxRequests ?? 50)}
                  onChange={(event) => updateNumber(["batch", "maxRequests"], event.target.value)}
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
                />
              </label>
            </div>
          </article>

          <article className="settings-accordion-card">
            <header>
              <div>
                <strong>{t("settings.trusted_proxy", "Trusted proxy")}</strong>
                <span>{t("settings.trusted_proxy_desc", "Forwarded IP header handling")}</span>
              </div>
              <Server size={18} />
            </header>
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
            <label className="check-row switch-row">
              <input
                id="trusted-proxy-leftmost"
                name="trustedProxy.useLeftmostIP"
                type="checkbox"
                checked={Boolean(trustedProxy.useLeftmostIP)}
                onChange={(event) => updateSetting(["trustedProxy", "useLeftmostIP"], event.target.checked)}
              />
              {t("settings.use_leftmost_ip", "Use leftmost IP")}
            </label>
          </article>

          <article className="settings-accordion-card">
            <header>
              <div>
                <strong>{t("settings.superusers", "Superusers")}</strong>
                <span>{t("settings.superusers_desc", "Restrict dashboard access by IP")}</span>
              </div>
              <Shield size={18} />
            </header>
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
          </article>

          <article className="settings-accordion-card rate-limit-card">
            <header>
              <div>
                <strong>{t("settings.rate_limiting", "Rate limiting")}</strong>
                <span>{t("settings.rate_limiting_desc", "Throttle requests per rule")}</span>
              </div>
              <Activity size={18} />
            </header>

            <label className="check-row switch-row">
              <input
                id="rate-limits-enabled"
                name="rateLimits.enabled"
                type="checkbox"
                checked={Boolean(rateLimits.enabled)}
                onChange={(event) => updateSetting(["rateLimits", "enabled"], event.target.checked)}
              />
              {t("settings.enable_rate_limiting", "Enable rate limiting")}
            </label>

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
          </article>
        </section>
      </section>

      <section className="surface settings-editor advanced-settings-editor">
        <label>
          {t("settings.advanced_json", "Advanced JSON")}
          <textarea
            id="settings-json"
            name="settingsJson"
            value={props.draft}
            onChange={(event) => props.onDraft(event.target.value)}
            spellCheck={false}
          />
        </label>
      </section>
    </section>
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
  loading: boolean;
  onDraft: (value: string) => void;
  onSave: () => void;
  onEmail: (value: string) => void;
  onTemplate: (value: string) => void;
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

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.mail", "Mail settings")}
        actions={
          <button className="primary" onClick={props.onSave} disabled={props.loading}>
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
          <label className="check-row switch-row">
            <input
              id="smtp-enabled"
              name="smtp.enabled"
              type="checkbox"
              checked={Boolean(smtp.enabled)}
              onChange={(event) => updateSetting(["smtp", "enabled"], event.target.checked)}
            />
            {t("settings.use_smtp", "Use SMTP mail server")}
          </label>
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
                <input
                  id="smtp-password"
                  name="smtp.password"
                  type="password"
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
          <button className="primary apply-button" onClick={props.onTest} disabled={props.loading || !props.email.trim()}>
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
  loading: boolean;
  onDraft: (value: string) => void;
  onSave: () => void;
  onTarget: (value: string) => void;
  onTest: () => void;
};

function StorageSettingsView(props: StorageSettingsViewProps) {
  const { t } = useTranslation();
  const draftSettings = useMemo(() => parseSettingsDraft(props.draft, props.settings), [props.draft, props.settings]);
  const storage = settingsObject(draftSettings, "s3");
  const originalStorage = settingsObject(props.settings, "s3");
  const storageEnabled = Boolean(storage.enabled);
  const hasS3Secret = Object.prototype.hasOwnProperty.call(storage, "secret");
  const changedStorageMode = Boolean(originalStorage.enabled) !== storageEnabled;

  function updateSetting(path: string[], value: unknown) {
    const next = cloneJsonObject(draftSettings);
    setNestedSetting(next, path, value);
    props.onDraft(JSON.stringify(next, null, 2));
  }

  return (
    <section className="settings-page">
      <SettingsPageHeader
        section={t("settings.nav.storage", "File storage")}
        actions={
          <button className="primary" onClick={props.onSave} disabled={props.loading}>
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
        <label className="check-row switch-row">
          <input
            id="s3-enabled"
            name="s3.enabled"
            type="checkbox"
            checked={storageEnabled}
            onChange={(event) => updateSetting(["s3", "enabled"], event.target.checked)}
          />
          {t("settings.use_s3_storage", "Use S3 storage")}
        </label>
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
                <input
                  id="s3-secret"
                  name="s3.secret"
                  type="password"
                  autoComplete="new-password"
                  value={String(storage.secret ?? "")}
                  placeholder={hasS3Secret ? "" : "* * * * * *"}
                  onChange={(event) => updateSetting(["s3", "secret"], event.target.value)}
                />
              </label>
            </div>
            <label className="check-row switch-row">
              <input
                id="s3-force-path-style"
                name="s3.forcePathStyle"
                type="checkbox"
                checked={Boolean(storage.forcePathStyle)}
                onChange={(event) => updateSetting(["s3", "forcePathStyle"], event.target.checked)}
              />
              {t("settings.force_path_style", "Force path-style addressing")}
            </label>
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
          <button className="primary apply-button" onClick={props.onTest} disabled={props.loading}>
            <Play size={16} />
            {t("actions.test_s3", "Test S3")}
          </button>
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
  onImport: () => Promise<void> | void;
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

  async function confirmImport() {
    await props.onImport();
    setReviewOpen(false);
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
                <button className="subtle" onClick={props.onExport}>
                  <RefreshCw size={16} />
                  {t("actions.refresh", "Refresh")}
                </button>
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
            <label className="check-row switch-row">
              <input
                type="checkbox"
                name="deleteMissingCollections"
                checked={props.deleteMissing}
                onChange={(event) => props.onDeleteMissing(event.target.checked)}
              />
              {t("transfer.delete_missing", "Delete missing collections")}
            </label>
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

type SqlViewProps = {
  query: string;
  result: SqlResult | null;
  error: string;
  loading: boolean;
  sqlCompletions: string[];
  onQuery: (value: string) => void;
  onRun: () => void;
};

function SqlView(props: SqlViewProps) {
  const { t } = useTranslation();
  const columns = props.result?.columns ?? [];
  const rows = props.result?.rows ?? [];
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
        </section>

        <section className="surface sql-result">
          <div className="table-meta">
            <span>{t("sql.affected_rows", { count: Number(props.result?.affectedRows ?? 0), defaultValue: "{{count}} affected rows" })}</span>
            <span>{t("sql.result_rows", { count: rows.length, defaultValue: "{{count}} result rows" })}</span>
            {props.error && <span className="danger">{props.error}</span>}
          </div>
          <div className="table-wrap">
            <table className="sql-table">
              <thead>
                <tr>
                  {columns.length === 0 ? <th>{t("sql.result", "Result")}</th> : columns.map((column) => <th key={column.name}>{column.name}</th>)}
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
                  rows.map((row, rowIndex) => (
                    <tr key={rowIndex}>
                      {columns.map((column, columnIndex) => (
                        <td key={column.name}>
                          <code>{formatValue(Array.isArray(row) ? row[columnIndex] : "")}</code>
                        </td>
                      ))}
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
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
  loading: boolean;
  onFilter: (value: string) => void;
  onRefresh: () => void;
  onLoadMore: () => void | Promise<void>;
};

function LogsView(props: LogsViewProps) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<LogItem | null>(null);
  const total = props.logPage?.totalItems ?? props.logs.length;
  const hasMoreLogs = Boolean(props.logPage && props.logs.length > 0 && props.logs.length < total);
  const statsTotal = props.stats.reduce((sum, item) => sum + Number(item.total || 0), 0);
  const maxStat = Math.max(1, ...props.stats.map((item) => Number(item.total || 0)));
  const chartStats = props.stats.slice(-28);

  useEffect(() => {
    if (selected && !props.logs.some((log) => log.id === selected.id)) {
      setSelected(null);
    }
  }, [props.logs, selected]);

  return (
    <section className="logs-page">
      <div className="logs-chart-strip">
        <div className="logs-chart-bars" aria-label={t("logs.activity", "Log activity")}>
          {chartStats.length === 0 ? (
            <span className="logs-chart-empty">{t("logs.no_activity", "No log activity")}</span>
          ) : (
            chartStats.map((item) => {
              const totalValue = Number(item.total || 0);
              return (
                <span
                  key={item.date}
                  style={{ height: `${Math.max(8, (totalValue / maxStat) * 100)}%` }}
                  title={`${item.date}: ${totalValue}`}
                />
              );
            })
          )}
        </div>
      </div>

      <header className="page-header logs-page-header">
        <nav className="breadcrumbs" aria-label={t("common.breadcrumb", "Breadcrumb")}>
          <span>{t("nav.logs", "Logs")}</span>
        </nav>
        <button className="icon-button page-circle" onClick={props.onRefresh} title={t("actions.refresh_logs", "Refresh logs")} aria-label={t("actions.refresh_logs", "Refresh logs")}>
          <RefreshCw size={17} />
        </button>
        <div className="searchbar logs-searchbar">
          <Search size={17} />
          <input
            id="logs-filter"
            name="logsFilter"
            autoComplete="off"
            aria-label={t("logs.search_aria", "Search term or filter")}
            value={props.filter}
            onChange={(event) => props.onFilter(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") props.onRefresh();
            }}
            placeholder={t("logs.search_placeholder", "Search term or filter like `level > 0`")}
          />
        </div>
        <button className="subtle apply-button" onClick={props.onRefresh} disabled={props.loading}>
          <ListFilter size={16} />
          {t("actions.apply", "Apply")}
        </button>
        <div className="logs-header-meta">
          <span>{t("logs.hourly_events", { count: statsTotal, defaultValue: "{{count}} hourly events" })}</span>
        </div>
      </header>

      <div className="page-table-wrapper">
        <table className="logs-table">
          <thead>
            <tr>
              <th className="log-level-col">{t("logs.level", "Level")}</th>
              <th>{t("logs.message", "Message")}</th>
              <th>{t("logs.time", "Created")}</th>
              <th className="actions-col">{t("collections.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {props.logs.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={4}>
                  {t("logs.no_logs", "No logs")}
                </td>
              </tr>
            ) : (
              props.logs.map((log) => {
                const level = logLevel(log.level);
                return (
                  <tr key={log.id} onClick={() => setSelected(log)} className="log-row">
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
                          setSelected(log);
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
      </div>

      {hasMoreLogs && (
        <div className="load-more-row">
          <button className="subtle" onClick={() => props.onLoadMore()} disabled={props.loading}>
            {props.loading
              ? t("common.loading", "Loading...")
              : t("logs.load_older", { count: total - props.logs.length, defaultValue: "Load older ({{count}} remaining)" })}
          </button>
        </div>
      )}
      <footer className="page-footer">
        <span>{t("common.total_count", { count: total, defaultValue: "Total: {{count}}" })}</span>
        <span>{t("logs.visible_count", { count: props.logs.length, defaultValue: "{{count}} visible" })}</span>
      </footer>

      {selected && (
        <Modal title={t("logs.log_title", { id: selected.id, defaultValue: "Log {{id}}" })} onClose={() => setSelected(null)} wide>
          <pre className="json-panel log-json">{JSON.stringify(selected, null, 2)}</pre>
        </Modal>
      )}
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
  viewQuery?: string | null;
  indexes?: string[];
};

type RuleKey = "listRule" | "viewRule" | "createRule" | "updateRule" | "deleteRule";

type CollectionModalProps = {
  state: CollectionEditorState;
  oauthProviders: OAuthProviderMetadata[];
  allCollections: CollectionSchema[];
  onClose: () => void;
  onConfirm: (request: ConfirmRequest) => Promise<boolean>;
  onSubmit: (payload: CollectionPayload) => void;
};

function CollectionModal({ state, oauthProviders, allCollections, onClose, onConfirm, onSubmit }: CollectionModalProps) {
  const { t } = useTranslation();
  const collection = state.collection;
  const [name, setName] = useState(collection?.name ?? "");
  const [type, setType] = useState(collection?.type ?? "base");
  const [fields, setFields] = useState(JSON.stringify(collection?.fields ?? DEFAULT_FIELDS, null, 2));
  const [viewQuery, setViewQuery] = useState(collection?.viewQuery ?? "");
  const [indexes, setIndexes] = useState<string[]>(collection?.indexes ?? []);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);
  const [dragArmed, setDragArmed] = useState(false);
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
  const [otpDuration, setOtpDuration] = useState(String(collection?.otp?.duration ?? 300));
  const [otpLength, setOtpLength] = useState(String(collection?.otp?.length ?? 6));
  const [mfaEnabled, setMfaEnabled] = useState(collection?.mfa?.enabled ?? false);
  const [mfaDuration, setMfaDuration] = useState(String(collection?.mfa?.duration ?? 1800));
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
    deleteRule: collection?.deleteRule ?? null
  });
  const [ruleMemory, setRuleMemory] = useState<Partial<Record<RuleKey, string>>>({});
  const [error, setError] = useState("");
  const [activeTab, setActiveTab] = useState("fields");
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
    oauthEnabled,
    oauthProviderNames,
    oauthProviderConfigs,
    oauthMappedFields
  });
  const initialSnapshot = useRef(snapshot);
  const hasChanges = snapshot !== initialSnapshot.current;

  async function requestClose() {
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
    onClose();
  }

  useEffect(() => {
    if (!tabs.some((tab) => tab.id === activeTab)) {
      setActiveTab(tabs[0]?.id ?? "fields");
    }
  }, [activeTab, tabs]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const parsedFields = JSON.parse(fields || "[]") as FieldSchema[];
      if (!Array.isArray(parsedFields)) throw new Error(t("errors.fields_must_be_array", "Fields must be an array."));

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

      onSubmit({
        name: name.trim(),
        type,
        fields: type === "view" ? [] : parsedFields,
        ...(type === "view" ? {} : { indexes }),
        listRule: normalizeRule(rules.listRule),
        viewRule: normalizeRule(rules.viewRule),
        createRule: normalizeRule(rules.createRule),
        updateRule: normalizeRule(rules.updateRule),
        deleteRule: normalizeRule(rules.deleteRule),
        ...(type === "view" ? { viewQuery: viewQuery.trim() } : {}),
        ...(type === "auth"
          ? {
              passwordAuth: {
                enabled: passwordEnabled,
                identityFields
              },
              otp: {
                enabled: otpEnabled,
                duration: Number(otpDuration || 300),
                length: Number(otpLength || 6)
              },
              mfa: {
                enabled: mfaEnabled,
                duration: Number(mfaDuration || 1800)
              },
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
    } catch (err) {
      setError(errorMessage(err));
    }
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
      if (current.includes(name)) {
        return current.filter((item) => item !== name);
      }
      return [...current, name];
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

  const fieldsPreview = useMemo(() => parseFieldsPreview(fields, t), [fields, t]);

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
    updateFields([
      ...current,
      {
        name: fieldName,
        type: fieldType,
        required: false,
        unique: false,
        hidden: false,
        system: false
      }
    ]);
  }

  function removeField(index: number) {
    if (fieldsPreview.error) {
      setError(fieldsPreview.error);
      return;
    }
    updateFields(fieldsPreview.fields.filter((_, currentIndex) => currentIndex !== index));
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
      onClose={requestClose}
      wide
    >
      <form className="modal-grid collection-upsert-form" onSubmit={submit}>
        <section className="collection-modal-head">
          <div className="collection-name-field">
            <label>
              {t("common.name", "Name")}{collection?.system ? ` (${t("collections.system", "system")})` : ""}
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
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
              { id: "auth", label: t("collections.type_auth", "Auth"), icon: Shield }
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
                    />
                  </div>
                ))
              )}
            </div>
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
          </section>
        )}

        {activeTab === "auth" && type === "auth" && (
          <section className="auth-config-grid collection-tab-panel">
            <article className="auth-config-card">
              <header>
                <strong>{t("collections.password_auth", "Password auth")}</strong>
              </header>
              <label className="check-row">
                <input type="checkbox" checked={passwordEnabled} onChange={(event) => setPasswordEnabled(event.target.checked)} />
                {t("common.enabled", "Enabled")}
              </label>
              <div className="stacked-checks">
                <label className="check-row">
                  <input
                    type="checkbox"
                    checked={identityFields.includes("email")}
                    onChange={() => toggleIdentityField("email")}
                  />
                  {t("collections.email_identity", "Email identity")}
                </label>
                <label className="check-row">
                  <input
                    type="checkbox"
                    checked={identityFields.includes("username")}
                    onChange={() => toggleIdentityField("username")}
                  />
                  {t("collections.username_identity", "Username identity")}
                </label>
              </div>
            </article>

            <article className="auth-config-card">
              <header>
                <strong>{t("auth.otp")}</strong>
              </header>
              <label className="check-row">
                <input type="checkbox" checked={otpEnabled} onChange={(event) => setOtpEnabled(event.target.checked)} />
                {t("common.enabled", "Enabled")}
              </label>
              <div className="two-col compact">
                <label>
                  {t("collections.duration_seconds", "Duration (s)")}
                  <input
                    type="number"
                    min={60}
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
              <label className="check-row">
                <input type="checkbox" checked={mfaEnabled} onChange={(event) => setMfaEnabled(event.target.checked)} />
                {t("common.enabled", "Enabled")}
              </label>
              <label>
                {t("collections.duration_seconds", "Duration (s)")}
                <input
                  type="number"
                  min={60}
                  value={mfaDuration}
                  onChange={(event) => setMfaDuration(event.target.value)}
                />
              </label>
            </article>

            <article className="auth-config-card auth-config-card-wide">
              <header>
                <strong>{t("settings.oauth2")}</strong>
              </header>
              <label className="check-row">
                <input type="checkbox" checked={oauthEnabled} onChange={(event) => setOauthEnabled(event.target.checked)} />
                {t("common.enabled", "Enabled")}
              </label>
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
                      </article>
                    );
                  })}
                </div>
              )}
            </article>
          </section>
        )}
        {activeTab === "rules" && (
          <section className="collection-rules-panel collection-tab-panel">
            <div className="rules-helper">
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
              {collectionRuleKeys(type).map((key) => {
                const value = rules[key];
                const locked = value === null;
                const readOnly = Boolean(collection?.system);
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
                      {!readOnly &&
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
                        disabled={readOnly}
                        name={key}
                        ariaLabel={collectionRuleLabel(key, t)}
                      />
                    )}
                  </div>
                );
              })}
            </div>
          </section>
        )}
        {error && <p className="form-error">{error}</p>}
        <div className="modal-actions">
          <button type="button" className="subtle" onClick={requestClose}>
            <X size={16} />
            {t("actions.cancel", "Cancel")}
          </button>
          <button className="primary" type="submit">
            <Save size={16} />
            {t("actions.save", "Save")}
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
  onClose: () => void;
  onConfirm: (request: ConfirmRequest) => Promise<boolean>;
  fetchRecords: RelationFetcher;
  onSubmit: (
    payload: Record<string, unknown>,
    files: Record<string, File[]>,
    options?: { close?: boolean }
  ) => Promise<void> | void;
};

function RecordModal({ collection, collections, state, onClose, onConfirm, fetchRecords, onSubmit }: RecordModalProps) {
  const { t } = useTranslation();
  const fileFields = (collection.fields ?? []).filter((field) => field.type === "file" && !field.hidden);
  const editableFields = (collection.fields ?? []).filter(
    (field) => field.type !== "file" && !field.hidden && !field.system
  );
  const initialPayload = useMemo(() => recordEditorPayload(collection, state.record), [collection, state.record]);
  const draftKey = `pbj_record_draft_${collection.id || collection.name}_${state.record?.id || "new"}`;
  const [basePayload, setBasePayload] = useState<Record<string, unknown>>(() => initialPayload);
  const [payload, setPayload] = useState<Record<string, unknown>>(() => initialPayload);
  const [json, setJson] = useState(JSON.stringify(initialPayload, null, 2));
  const [initialDraft, setInitialDraft] = useState<Record<string, unknown> | null>(() => readRecordDraft(draftKey));
  const [activeTab, setActiveTab] = useState<"main" | "providers">("main");
  const [files, setFiles] = useState<Record<string, File[]>>({});
  const [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const editing = Boolean(state.record);
  const showTabs = Boolean(state.record?.id) && collection.type === "auth" && collection.name !== "_superusers";
  const changed = JSON.stringify(payload) !== JSON.stringify(basePayload) || Object.values(files).some((items) => items.length > 0);
  const canSubmit = !saving && (!editing || changed);

  useEffect(() => {
    if (!changed) return;
    localStorage.setItem(draftKey, JSON.stringify(payload));
  }, [changed, draftKey, payload]);

  useEffect(() => {
    if (!showTabs && activeTab !== "main") setActiveTab("main");
  }, [activeTab, showTabs]);

  async function requestClose() {
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
    onClose();
  }

  function updatePayload(field: FieldSchema, value: unknown) {
    setPayload((current) => {
      const next = { ...current, [field.name]: value };
      setJson(JSON.stringify(next, null, 2));
      return next;
    });
    setError("");
    // Editing a field clears its own error, so stale markers don't linger.
    setFieldErrors((current) => {
      if (!current[field.name]) return current;
      const next = { ...current };
      delete next[field.name];
      return next;
    });
  }

  function updateJson(value: string) {
    setJson(value);
    try {
      const parsed = JSON.parse(value || "{}") as Record<string, unknown>;
      if (isPlainObject(parsed)) {
        setPayload(parsed);
        setError("");
      }
    } catch {
      // Keep the raw JSON text so the submit path can surface the exact validation error.
    }
  }

  function restoreDraft() {
    if (!initialDraft) return;
    setPayload(initialDraft);
    setJson(JSON.stringify(initialDraft, null, 2));
    setInitialDraft(null);
    setError("");
  }

  function discardDraft() {
    localStorage.removeItem(draftKey);
    setInitialDraft(null);
  }

  function resetForm() {
    setPayload(basePayload);
    setJson(JSON.stringify(basePayload, null, 2));
    setFiles({});
    localStorage.removeItem(draftKey);
    setInitialDraft(null);
    setError("");
  }

  async function submit(event: FormEvent<HTMLFormElement> | null, close = true) {
    event?.preventDefault();
    if (saving) return;
    setSaving(true);
    try {
      const parsedPayload = JSON.parse(json || "{}") as Record<string, unknown>;
      if (!isPlainObject(parsedPayload)) throw new Error(t("errors.record_payload_object", "Record payload must be an object."));
      await onSubmit(parsedPayload, files, { close });
      if (!close && !editing) {
        const nextPayload = recordEditorPayload(collection);
        setBasePayload(nextPayload);
        setPayload(nextPayload);
        setJson(JSON.stringify(nextPayload, null, 2));
        setFiles({});
        localStorage.removeItem(draftKey);
        setInitialDraft(null);
        return;
      }
      setBasePayload(parsedPayload);
      setPayload(parsedPayload);
      setFiles({});
      localStorage.removeItem(draftKey);
      setInitialDraft(null);
    } catch (err) {
      setError(errorMessage(err));
      setFieldErrors(fieldErrorsOf(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal
      title={
        state.record
          ? t("records.edit_record_title", { id: state.record.id, defaultValue: "Edit {{id}}" })
          : t("records.new_record_title", { name: collection.name, defaultValue: "New {{name}}" })
      }
      onClose={requestClose}
      wide
    >
      <form className="modal-grid record-upsert-form" onSubmit={(event) => submit(event, true)}>
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
          </nav>
        )}

        {activeTab === "providers" ? (
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
                      className={`record-field-slot${fieldErrors[field.name] ? " has-error" : ""}`}
                    >
                      <RecordFieldControl
                        field={field}
                        value={payload[field.name]}
                        collections={collections}
                        fetchRecords={fetchRecords}
                        onChange={(value) => updatePayload(field, value)}
                      />
                      {fieldErrors[field.name] && (
                        <p className="record-field-error">{fieldErrors[field.name]}</p>
                      )}
                    </div>
                  ))
                )}
              </div>

              {fileFields.length > 0 && (
                <div className="file-upload-grid record-file-grid">
                  {fileFields.map((field) => (
                    <label key={field.name}>
                      {field.name}
                      <input
                        name={field.name}
                        type="file"
                        multiple={maxFiles(field) > 1}
                        accept={(field.mimeTypes ?? []).join(",")}
                        onChange={(event) =>
                          setFiles({ ...files, [field.name]: Array.from(event.target.files ?? []) })
                        }
                      />
                    </label>
                  ))}
                </div>
              )}
            </section>

            <section className="record-json-panel">
              <label>
                JSON
                <textarea
                  name={`${collection.name}RecordJson`}
                  value={json}
                  onChange={(event) => updateJson(event.target.value)}
                  spellCheck={false}
                />
              </label>
            </section>
          </div>
        )}
        {error && <p className="form-error">{error}</p>}
        <div className="modal-actions record-footer-actions">
          <button type="button" className="subtle" onClick={requestClose}>
            <X size={16} />
            {t("actions.close", "Close")}
          </button>
          <button type="button" className="subtle" onClick={resetForm} disabled={!changed || saving}>
            <RotateCcw size={16} />
            {t("actions.reset_form", "Reset form")}
          </button>
          <span className="modal-actions-spacer" />
          <button className="primary" type="submit" disabled={!canSubmit}>
            <Save size={16} />
            {state.record ? t("actions.save_changes", "Save changes") : t("actions.create", "Create")}
          </button>
          {!editing && (
            <button className="subtle" type="button" onClick={() => submit(null, false)} disabled={!canSubmit}>
              {t("actions.save_and_continue", "Save and continue")}
            </button>
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
  children: ReactNode;
};

function Modal({ title, onClose, wide, children }: ModalProps) {
  const { t } = useTranslation();
  return (
    <div className="modal-backdrop" role="presentation">
      <section className={wide ? "modal wide" : "modal"} role="dialog" aria-modal="true" aria-label={title}>
        <header>
          <h2>{title}</h2>
          <button className="icon-button" onClick={onClose} title={t("actions.close", "Close")} aria-label={t("actions.close", "Close")}>
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

function StatusPill({ health, loading }: { health: HealthResponse["data"] | null; loading: boolean }) {
  const { t } = useTranslation();
  return (
    <span className={loading ? "status busy" : health ? "status ready" : "status offline"}>
      {loading ? t("status.syncing", "syncing") : health ? t("status.online", "online") : t("status.offline", "offline")}
    </span>
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
  const fieldNames = (collection.fields ?? [])
    .filter((field) => field.type !== "password" && !field.hidden)
    .map((field) => field.name);
  // id/created/updated are ordinary schema fields since PB v0.23, so they may already
  // be present — dedupe or the header ends up wider than the rows and shifts out of line.
  const columns: string[] = [];
  for (const name of ["id", ...fieldNames, "created", "updated"]) {
    if (!columns.includes(name)) columns.push(name);
  }
  return columns;
}

function recordEditorPayload(collection: CollectionSchema, record?: RecordItem) {
  if (record) {
    return Object.fromEntries(Object.entries(record).filter(([key]) => !SYSTEM_RECORD_KEYS.has(key)));
  }
  return Object.fromEntries(
    (collection.fields ?? [])
      .filter((field) => field.type !== "file" && !field.system)
      .map((field) => [field.name, defaultValue(field)])
  );
}

function defaultValue(field: FieldSchema) {
  if (field.type === "bool") return false;
  if (field.type === "number") return 0;
  if (field.type === "json") return null;
  if (field.type === "relation") return maxFiles(field) > 1 ? [] : "";
  return "";
}

function recordRequestBody(payload: Record<string, unknown>, files: Record<string, File[]>) {
  const entries = Object.entries(files).filter(([, value]) => value.length > 0);
  if (entries.length === 0) return payload;

  const form = new FormData();
  Object.entries(payload).forEach(([key, value]) => {
    if (value === undefined || value === null) return;
    form.append(key, typeof value === "string" ? value : JSON.stringify(value));
  });
  entries.forEach(([field, fieldFiles]) => {
    fieldFiles.forEach((file) => form.append(field, file));
  });
  return form;
}

function maxFiles(field: FieldSchema) {
  const direct = field.maxSelect ?? field.maxFiles;
  const optionValue = Number(field.options?.maxSelect ?? field.options?.maxFiles ?? 1);
  return Math.max(1, Number(direct ?? optionValue ?? 1));
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

function formatValue(value: unknown) {
  if (value === undefined || value === null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}

function formatDate(value: string) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}

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
      if (key === "error" || key === "details") continue;
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
    settings: { title: t("nav.settings", "Settings"), eyebrow: t("settings.nav.application", "Application") },
    mail: { title: t("settings.nav.mail", "Mail settings"), eyebrow: t("settings.nav.application", "Application") },
    storage: { title: t("settings.nav.storage", "File storage"), eyebrow: t("settings.nav.application", "Application") },
    backups: { title: t("settings.nav.backups", "Backups"), eyebrow: t("settings.maintenance", "Maintenance") },
    crons: { title: t("settings.nav.crons", "Crons"), eyebrow: t("settings.scheduler", "Scheduler") },
    export: { title: t("settings.nav.export", "Export collections"), eyebrow: t("settings.nav.system", "System") },
    import: { title: t("settings.nav.import", "Import collections"), eyebrow: t("settings.nav.system", "System") },
    sql: { title: t("settings.nav.sql", "SQL console"), eyebrow: t("settings.nav.system", "System") },
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
    return isPlainObject(parsed) ? parsed : null;
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
      { id: "auth", label: t("common.options", "Options") }
    ];
  }
  return [
    { id: "fields", label: t("collections.fields", "Fields") },
    { id: "rules", label: t("collections.api_rules", "API rules") }
  ];
}

function collectionRuleKeys(type: string): RuleKey[] {
  if (type === "view") return ["listRule", "viewRule"];
  return ["listRule", "viewRule", "createRule", "updateRule", "deleteRule"];
}

function collectionRuleLabel(key: RuleKey, t: TFunction) {
  const labels: Record<RuleKey, string> = {
    listRule: t("collections.list_search_rule", "List/Search rule"),
    viewRule: t("collections.view_rule", "View rule"),
    createRule: t("collections.create_rule", "Create rule"),
    updateRule: t("collections.update_rule", "Update rule"),
    deleteRule: t("collections.delete_rule", "Delete rule")
  };
  return labels[key];
}

function uniqueFieldName(fields: FieldSchema[], type: string) {
  const base = type.replace(/[^A-Za-z0-9_]/g, "_") || "field";
  const existing = new Set(fields.map((field) => field.name));
  if (!existing.has(base)) return base;
  let index = fields.length + 1;
  while (existing.has(`${base}_${index}`)) index++;
  return `${base}_${index}`;
}

function splitCsv(value: string) {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
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
