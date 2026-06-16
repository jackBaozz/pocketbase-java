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
  FileArchive,
  FileUp,
  HardDrive,
  KeyRound,
  ListFilter,
  LogOut,
  Mail,
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
  Trash2,
  Upload,
  X
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent, ReactNode, RefObject } from "react";

type HealthResponse = {
  data: {
    canBackup: boolean;
    dataDir: string;
    superuserReady: boolean;
  };
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
  scopes?: string[];
  pkce?: boolean;
};

type OAuth2Config = {
  enabled?: boolean;
  providers?: OAuth2ProviderConfig[];
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
  name: string;
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
const DEFAULT_FIELDS = [{ name: "title", type: "text", required: true }];
const SYSTEM_RECORD_KEYS = new Set(["id", "collectionId", "collectionName", "created", "updated", "expand"]);

function App() {
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) || "");
  const [health, setHealth] = useState<HealthResponse["data"] | null>(null);
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
  const [deleteMissingCollections, setDeleteMissingCollections] = useState(false);
  const [testEmail, setTestEmail] = useState("");
  const [testEmailTemplate, setTestEmailTemplate] = useState("verification");
  const [testS3Target, setTestS3Target] = useState("storage");
  const backupUploadRef = useRef<HTMLInputElement>(null);

  const setupRequired = health ? !health.superuserReady : false;
  const authenticated = Boolean(token) && !setupRequired;
  const collectionView = view === "records" || view === "schema";
  const settingsView = isSettingsView(view);
  const selected = useMemo(
    () => collections.find((collection) => collection.name === selectedName) ?? null,
    [collections, selectedName]
  );

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

  const refreshHealth = useCallback(async () => {
    const data = await apiRequest<HealthResponse>("/api/health", "");
    setHealth(data.data);
    return data.data;
  }, []);

  const refreshCollections = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await apiRequest<ListResponse<CollectionSchema>>("/api/collections?perPage=500&sort=name", token);
      setCollections(data.items);
      setSelectedName((current) => {
        if (current && data.items.some((collection) => collection.name === current)) return current;
        return data.items.find((collection) => collection.name !== "_superusers")?.name ?? data.items[0]?.name ?? "";
      });
    } finally {
      setLoading(false);
    }
  }, [token]);

  const refreshRecords = useCallback(
    async (collectionName = selectedName, nextQuery = query) => {
      if (!token || !collectionName) return;
      setLoading(true);
      try {
        const qs = buildQuery({
          page: 1,
          perPage: nextQuery.perPage,
          sort: nextQuery.sort,
          filter: nextQuery.filter
        });
        const data = await apiRequest<ListResponse<RecordItem>>(
          `/api/collections/${encodeURIComponent(collectionName)}/records?${qs}`,
          token
        );
        setRecordPage(data);
        setRecords(data.items);
      } finally {
        setLoading(false);
      }
    },
    [query, selectedName, token]
  );

  const refreshBackups = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const data = await apiRequest<ListResponse<BackupInfo>>("/api/backups?perPage=200", token);
      setBackups(data.items);
    } finally {
      setLoading(false);
    }
  }, [token]);

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

  const refreshLogs = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const qs = buildQuery({
        page: 1,
        perPage: 100,
        sort: "-created",
        filter: logFilter
      });
      const [logData, statsData] = await Promise.all([
        apiRequest<ListResponse<LogItem>>(`/api/logs?${qs}`, token),
        apiRequest<LogStat[]>("/api/logs/stats", token)
      ]);
      setLogPage(logData);
      setLogs(logData.items);
      setLogStats(statsData);
    } finally {
      setLoading(false);
    }
  }, [logFilter, token]);

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
      const status = await refreshHealth();
      if (token && status.superuserReady) {
        await refreshCollections();
        await refreshOauthProviders();
      }
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }, [notify, refreshCollections, refreshHealth, refreshOauthProviders, token]);

  useEffect(() => {
    refreshAll();
  }, [refreshAll]);

  useEffect(() => {
    localStorage.setItem(PINNED_COLLECTIONS_KEY, JSON.stringify(pinnedCollectionNames));
  }, [pinnedCollectionNames]);

  useEffect(() => {
    localStorage.setItem(HIDDEN_COLUMNS_KEY, JSON.stringify(hiddenColumnsByCollection));
  }, [hiddenColumnsByCollection]);

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
    if (authenticated && (view === "settings" || view === "mail" || view === "storage")) {
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

  async function handleAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const email = authEmail.trim();
    const body = { email, identity: email, password: authPassword };
    setLoading(true);
    try {
      if (setupRequired) {
        await apiRequest("/api/bootstrap/superuser", "", { method: "POST", body });
        notify("Superuser created");
      }
      const auth = await apiRequest<AuthResponse>("/api/collections/_superusers/auth-with-password", "", {
        method: "POST",
        body
      });
      setAuthToken(auth.token);
      setAuthEmail("");
      setAuthPassword("");
      const status = await refreshHealth();
      if (status.superuserReady) {
        await refreshCollectionsWithToken(auth.token);
      }
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setLoading(false);
    }
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
  }

  async function saveCollection(payload: CollectionPayload) {
    try {
      if (collectionEditor?.mode === "edit" && collectionEditor.collection) {
        await api(`/api/collections/${encodeURIComponent(collectionEditor.collection.name)}`, {
          method: "PATCH",
          body: payload
        });
        notify("Collection saved");
      } else {
        await api("/api/collections", { method: "POST", body: payload });
        notify("Collection created");
      }
      setCollectionEditor(null);
      await refreshCollections();
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function deleteCollection(collection: CollectionSchema) {
    if (!window.confirm(`Delete collection ${collection.name}?`)) return;
    try {
      await api(`/api/collections/${encodeURIComponent(collection.name)}`, { method: "DELETE" });
      notify("Collection deleted");
      setSelectedName("");
      await refreshCollections();
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function startOAuthTest(provider: AuthMethodProvider) {
    if (!selected) return;
    if (!provider.authURL || !provider.state) {
      notify(`Provider ${provider.displayName || provider.name} is missing an auth URL`, "error");
      return;
    }
    const redirectURL = `${window.location.origin}/api/oauth2-redirect`;
    const popup = window.open(
      provider.authURL + encodeURIComponent(redirectURL),
      `pbj-oauth-${provider.name}`,
      "popup,width=720,height=820"
    );
    if (!popup) {
      notify("OAuth popup was blocked", "error");
      return;
    }

    setOauthTestingProvider(provider.name);
    try {
      const payload = await waitForOAuthResult(provider.state, popup);
      if (payload.error) throw new Error(payload.error);
      if (!payload.code) throw new Error("OAuth2 redirect did not provide an authorization code.");
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
      notify(`OAuth2 auth completed for ${provider.displayName || provider.name}`);
      await refreshRecords(selected.name);
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
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
      notify(id ? "Record saved" : "Record created");
      if (options.close !== false) {
        setRecordEditor(null);
      } else {
        setRecordEditor({ record: saved });
      }
      await refreshRecords(selected.name);
    } catch (error) {
      notify(errorMessage(error), "error");
      throw error;
    }
  }

  async function deleteRecord(record: RecordItem) {
    if (!selected || !window.confirm(`Delete record ${record.id}?`)) return;
    try {
      await api(`/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(record.id)}`, {
        method: "DELETE"
      });
      notify("Record deleted");
      await refreshRecords(selected.name);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function deleteSelectedRecords() {
    if (!selected || selectedRecordIds.length === 0) return;
    if (!window.confirm(`Delete ${selectedRecordIds.length} selected records?`)) return;
    try {
      await Promise.all(
        selectedRecordIds.map((id) =>
          api(`/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(id)}`, {
            method: "DELETE"
          })
        )
      );
      notify("Records deleted");
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

  function toggleRecordSelection(id: string) {
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
      notify("Backup created");
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
      notify("Backup uploaded");
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
      anchor.download = backup.name || backup.key;
      anchor.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function restoreBackup(backup: BackupInfo) {
    if (!window.confirm(`Restore ${backup.key}?`)) return;
    try {
      await api(`/api/backups/${encodeURIComponent(backup.key)}/restore`, { method: "POST" });
      notify("Backup restored");
      await refreshCollections();
      await refreshBackups();
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function deleteBackup(backup: BackupInfo) {
    if (!window.confirm(`Delete backup ${backup.key}?`)) return;
    try {
      await api(`/api/backups/${encodeURIComponent(backup.key)}`, { method: "DELETE" });
      notify("Backup deleted");
      await refreshBackups();
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function saveSettings() {
    try {
      const parsed = JSON.parse(settingsDraft || "{}") as AppSettings;
      const updated = await api<AppSettings>("/api/settings", { method: "PATCH", body: parsed });
      setSettings(updated);
      setSettingsDraft(JSON.stringify(updated, null, 2));
      notify("Settings saved");
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
      notify("Test email queued");
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
      notify("S3 connection check completed");
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
      if (!collectionsPayload) throw new Error("Import JSON must be an array or an object with collections.");
      await api("/api/collections/import", {
        method: "PUT",
        body: {
          deleteMissing: deleteMissingCollections,
          collections: collectionsPayload
        }
      });
      notify("Collections imported");
      await refreshCollections();
      setExportDraft(JSON.stringify(collectionsPayload, null, 2));
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }

  async function runSql() {
    setSqlError("");
    setLoading(true);
    try {
      const result = await api<SqlResult>("/api/sql", { method: "POST", body: { query: sqlQuery } });
      setSqlResult(result);
      notify("SQL executed");
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
      notify(`Triggered ${job.id}`);
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setLoading(false);
    }
  }

  const allColumns = useMemo(() => recordColumns(selected), [selected]);
  const columns = useMemo(
    () => allColumns.filter((column) => !hiddenColumns.includes(column)),
    [allColumns, hiddenColumns]
  );
  const pageMeta = viewMeta(view, selected);

  return (
    <div className="app-shell">
      <header className="app-header">
        <button
          className="logo"
          onClick={() => {
            if (selectedName) setView("records");
          }}
          aria-label="Open collections"
        >
          <span className="brand-mark">PB</span>
          <span className="brand-title">pocketbase-java</span>
        </button>
        <nav className="app-main-nav" aria-label="Primary">
          <button
            className={collectionView ? "header-link active" : "header-link"}
            onClick={() => setView("records")}
            disabled={!authenticated || !selectedName}
          >
            <Database size={15} />
            Collections
          </button>
          <button
            className={view === "logs" ? "header-link active" : "header-link"}
            onClick={() => setView("logs")}
            disabled={!authenticated}
          >
            <Activity size={15} />
            Logs
          </button>
          <button
            className={settingsView ? "header-link active" : "header-link"}
            onClick={() => setView("settings")}
            disabled={!authenticated}
          >
            <Settings size={15} />
            Settings
          </button>
        </nav>
        <div className="header-tools">
          <StatusPill health={health} loading={loading} />
          <button className="icon-button header-icon" onClick={refreshAll} title="Refresh" aria-label="Refresh">
            <RefreshCw size={17} />
          </button>
          <button className="icon-button header-icon danger" onClick={logout} title="Logout" aria-label="Logout" disabled={!token}>
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
              setSelectedName(collection.name);
              setView("records");
            }}
            onTogglePinned={togglePinnedCollection}
          />
        )}

        {authenticated && !setupRequired && settingsView && (
          <SettingsSidebar current={view} onSelect={setView} />
        )}

        <main className="workspace">
          <header className="topbar">
            <div>
              <p className="eyebrow">{pageMeta.eyebrow}</p>
              <h1>{pageMeta.title}</h1>
            </div>
          </header>

          {!authenticated ? (
            <AuthPanel
              setupRequired={setupRequired}
              email={authEmail}
              password={authPassword}
              loading={loading}
              dataDir={health?.dataDir}
              onEmail={setAuthEmail}
              onPassword={setAuthPassword}
              onSubmit={handleAuth}
            />
          ) : (
            <>
              {collectionView && (
                <div className="view-tabs" role="tablist" aria-label="Collection views">
                  <button className={view === "records" ? "active" : ""} onClick={() => setView("records")}>
                    <Database size={16} />
                    Records
                  </button>
                  <button className={view === "schema" ? "active" : ""} onClick={() => setView("schema")}>
                    <ListFilter size={16} />
                    Schema
                  </button>
                </div>
              )}

              {view === "backups" ? (
                <BackupView
                  backups={backups}
                  backupName={backupName}
                  canBackup={Boolean(health?.canBackup)}
                  loading={loading}
                  uploadRef={backupUploadRef}
                  onBackupName={setBackupName}
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
                  onDraft={setSettingsDraft}
                  onRefresh={refreshSettings}
                  onSave={saveSettings}
                />
              ) : view === "mail" ? (
                <MailSettingsView
                  settings={settings}
                  email={testEmail}
                  template={testEmailTemplate}
                  loading={loading}
                  onEmail={setTestEmail}
                  onTemplate={setTestEmailTemplate}
                  onTest={testEmailSettings}
                />
              ) : view === "storage" ? (
                <StorageSettingsView
                  settings={settings}
                  target={testS3Target}
                  loading={loading}
                  onTarget={setTestS3Target}
                  onTest={testS3Settings}
                />
              ) : view === "export" ? (
                <CollectionTransferView
                  mode="export"
                  draft={exportDraft}
                  deleteMissing={deleteMissingCollections}
                  loading={loading}
                  onDraft={setExportDraft}
                  onDeleteMissing={setDeleteMissingCollections}
                  onExport={() => setExportDraft(JSON.stringify(collections, null, 2))}
                  onImport={importCollections}
                  onCopy={(value) => {
                    navigator.clipboard.writeText(value).then(
                      () => notify("Copied"),
                      (error) => notify(errorMessage(error), "error")
                    );
                  }}
                />
              ) : view === "import" ? (
                <CollectionTransferView
                  mode="import"
                  draft={importDraft}
                  deleteMissing={deleteMissingCollections}
                  loading={loading}
                  onDraft={setImportDraft}
                  onDeleteMissing={setDeleteMissingCollections}
                  onExport={() => setExportDraft(JSON.stringify(collections, null, 2))}
                  onImport={importCollections}
                  onCopy={(value) => {
                    navigator.clipboard.writeText(value).then(
                      () => notify("Copied"),
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
                  onRefresh={refreshLogs}
                />
              ) : view === "crons" ? (
                <CronsView crons={crons} loading={loading} onRefresh={refreshCrons} onRun={runCron} />
              ) : selected ? (
                view === "records" ? (
                  <RecordsView
                    collection={selected}
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
                        () => notify("Copied"),
                        (error) => notify(errorMessage(error), "error")
                      );
                    }}
                  />
                )
              ) : (
                <EmptyState icon={Database} title="No collection selected" />
              )}
            </>
          )}
        </main>
      </div>

      {collectionEditor && (
        <CollectionModal
          state={collectionEditor}
          oauthProviders={oauthProviders}
          onClose={() => setCollectionEditor(null)}
          onSubmit={(payload) => saveCollection(payload)}
        />
      )}

      {recordEditor && selected && (
        <RecordModal
          collection={selected}
          state={recordEditor}
          onClose={() => setRecordEditor(null)}
          onSubmit={saveRecord}
        />
      )}

      {oauthResult && (
        <OAuthResultModal result={oauthResult} onClose={() => setOauthResult(null)} />
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
  dataDir?: string;
  onEmail: (value: string) => void;
  onPassword: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
};

function AuthPanel(props: AuthPanelProps) {
  return (
    <section className="auth-layout">
      <div className="auth-copy">
        <p className="eyebrow">{props.setupRequired ? "Bootstrap" : "Superuser"}</p>
        <h2>{props.setupRequired ? "Create the first superuser" : "Sign in to manage data"}</h2>
        <dl>
          <div>
            <dt>Runtime</dt>
            <dd>{props.dataDir ?? "pb_data"}</dd>
          </div>
        </dl>
      </div>
      <form className="auth-form" onSubmit={props.onSubmit}>
        <label>
          Email
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
          Password
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
          {props.setupRequired ? "Create and sign in" : "Sign in"}
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
  const pinned = props.pinnedNames
    .map((name) => props.collections.find((collection) => collection.name === name))
    .filter(Boolean) as CollectionSchema[];
  const pinnedSet = new Set(pinned.map((collection) => collection.name));
  const regular = props.collections.filter((collection) => !pinnedSet.has(collection.name) && !isSystemCollection(collection));
  const system = props.collections.filter((collection) => !pinnedSet.has(collection.name) && isSystemCollection(collection));

  return (
    <aside className="sidebar">
      <div className="sidebar-topline">
        <div className="sidebar-section-title">Collections</div>
        <button className="icon-button tiny" onClick={props.onCreate} title="New collection" aria-label="New collection">
          <Plus size={15} />
        </button>
      </div>

      <div className="search-box">
        <Search size={15} />
        <input
          id="collection-search"
          name="collectionSearch"
          autoComplete="off"
          value={props.search}
          onChange={(event) => props.onSearch(event.target.value)}
          placeholder="Search collections"
        />
      </div>

      <CollectionGroup
        title="Pinned"
        collections={pinned}
        currentName={props.currentName}
        pinnedNames={props.pinnedNames}
        onSelect={props.onSelect}
        onTogglePinned={props.onTogglePinned}
        empty="Pin frequently used collections"
      />
      <CollectionGroup
        title="Collections"
        collections={regular}
        currentName={props.currentName}
        pinnedNames={props.pinnedNames}
        onSelect={props.onSelect}
        onTogglePinned={props.onTogglePinned}
        empty="No collections"
      />
      <CollectionGroup
        title="System"
        collections={system}
        currentName={props.currentName}
        pinnedNames={props.pinnedNames}
        onSelect={props.onSelect}
        onTogglePinned={props.onTogglePinned}
        empty="No system collections"
      />

      <div className="sidebar-actions">
        <button className="primary" onClick={props.onCreate}>
          <Plus size={16} />
          New collection
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
  empty: string;
  onSelect: (collection: CollectionSchema) => void;
  onTogglePinned: (collection: CollectionSchema) => void;
};

function CollectionGroup(props: CollectionGroupProps) {
  return (
    <section className="sidebar-group">
      <div className="sidebar-section-title">{props.title}</div>
      <nav className="collection-nav" aria-label={props.title}>
        {props.collections.length === 0 ? (
          <p className="sidebar-empty">{props.empty}</p>
        ) : (
          props.collections.map((collection) => {
            const pinned = props.pinnedNames.includes(collection.name);
            return (
              <div className={props.currentName === collection.name ? "collection-nav-row active" : "collection-nav-row"} key={collection.id || collection.name}>
                <button className="collection-nav-main" onClick={() => props.onSelect(collection)}>
                  <span className="nav-icon">
                    {collection.type === "auth" ? <Shield size={16} /> : <Database size={16} />}
                  </span>
                  <span className="nav-text">
                    <strong>{collection.name}</strong>
                    <small>{collection.type}</small>
                  </span>
                </button>
                <button
                  className="icon-button pin-button"
                  onClick={() => props.onTogglePinned(collection)}
                  title={pinned ? "Unpin collection" : "Pin collection"}
                  aria-label={pinned ? "Unpin collection" : "Pin collection"}
                >
                  {pinned ? <PinOff size={14} /> : <Pin size={14} />}
                </button>
              </div>
            );
          })
        )}
      </nav>
    </section>
  );
}

const SETTINGS_NAV_GROUPS: Array<{
  title: string;
  items: Array<{ view: ViewName; label: string; icon: LucideIcon }>;
}> = [
  {
    title: "Application",
    items: [
      { view: "settings", label: "General", icon: Settings },
      { view: "mail", label: "Mail settings", icon: Mail },
      { view: "storage", label: "File storage", icon: HardDrive }
    ]
  },
  {
    title: "System",
    items: [
      { view: "backups", label: "Backups", icon: FileArchive },
      { view: "crons", label: "Crons", icon: Clock3 },
      { view: "export", label: "Export collections", icon: Download },
      { view: "import", label: "Import collections", icon: Upload },
      { view: "sql", label: "SQL console", icon: Code2 }
    ]
  }
];

function SettingsSidebar({ current, onSelect }: { current: ViewName; onSelect: (view: ViewName) => void }) {
  return (
    <aside className="sidebar settings-sidebar">
      {SETTINGS_NAV_GROUPS.map((group) => (
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
  onNew: () => void;
  onEdit: (record: RecordItem) => void;
  onDelete: (record: RecordItem) => void;
  onDeleteSelected: () => void;
  onToggleColumn: (column: string) => void;
  onResetColumns: () => void;
  onToggleSelected: (id: string) => void;
  onToggleAll: (checked: boolean) => void;
  onClearSelection: () => void;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function RecordsView(props: RecordsViewProps) {
  const [draft, setDraft] = useState(props.query);
  const [columnsOpen, setColumnsOpen] = useState(false);
  const selectedSet = useMemo(() => new Set(props.selectedIds), [props.selectedIds]);
  const allVisibleSelected =
    props.records.length > 0 && props.records.every((record) => selectedSet.has(record.id));

  useEffect(() => setDraft(props.query), [props.query]);

  function apply() {
    props.onQuery(draft);
    props.onApply(draft);
  }

  return (
    <section className="surface">
      <div className="surface-toolbar">
        <div className="query-grid">
          <label>
            Filter
            <input
              id="records-filter"
              name="filter"
              autoComplete="off"
              value={draft.filter}
              onChange={(event) => setDraft({ ...draft, filter: event.target.value })}
              placeholder='published = true'
            />
          </label>
          <label>
            Sort
            <input
              id="records-sort"
              name="sort"
              autoComplete="off"
              value={draft.sort}
              onChange={(event) => setDraft({ ...draft, sort: event.target.value })}
            />
          </label>
          <label>
            Per page
            <select
              id="records-per-page"
              name="perPage"
              value={draft.perPage}
              onChange={(event) => setDraft({ ...draft, perPage: Number(event.target.value) })}
            >
              {[25, 50, 100, 200].map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </label>
          <button className="subtle apply-button" onClick={apply} disabled={props.loading}>
            <ListFilter size={16} />
            Apply
          </button>
        </div>
        <div className="records-toolbar-actions">
          <div className="column-picker">
            <button className="subtle" onClick={() => setColumnsOpen((open) => !open)}>
              <Columns3 size={16} />
              Columns
            </button>
            {columnsOpen && (
              <div className="columns-popover">
                <div className="columns-popover-header">
                  <strong>Visible columns</strong>
                  <button className="icon-button tiny" onClick={props.onResetColumns} title="Reset columns" aria-label="Reset columns">
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
          <button className="primary" onClick={props.onNew}>
            <Plus size={16} />
            New record
          </button>
        </div>
      </div>

      {props.selectedIds.length > 0 && (
        <div className="bulkbar">
          <span>{props.selectedIds.length} selected</span>
          <button className="subtle" onClick={props.onClearSelection}>
            <X size={16} />
            Clear
          </button>
          <button className="danger subtle" onClick={props.onDeleteSelected}>
            <Trash2 size={16} />
            Delete selected
          </button>
        </div>
      )}

      <div className="table-meta">
        <span>{props.recordPage?.totalItems ?? props.records.length} records</span>
        <span>{props.collection.fields?.length ?? 0} fields</span>
        <span>{props.columns.length}/{props.allColumns.length} columns</span>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th className="select-col">
                <button
                  className="checkbox-button"
                  onClick={() => props.onToggleAll(!allVisibleSelected)}
                  title={allVisibleSelected ? "Clear selection" : "Select page"}
                  aria-label={allVisibleSelected ? "Clear selection" : "Select page"}
                >
                  {allVisibleSelected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                </button>
              </th>
              {props.columns.map((column) => (
                <th key={column}>{column}</th>
              ))}
              <th className="actions-col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {props.records.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={props.columns.length + 2}>
                  No records
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
                        onClick={() => props.onToggleSelected(record.id)}
                        title={selected ? "Unselect record" : "Select record"}
                        aria-label={selected ? "Unselect record" : "Select record"}
                      >
                        {selected ? <CheckSquare2 size={17} /> : <Square size={17} />}
                      </button>
                    </td>
                    {props.columns.map((column) => (
                      <td key={column}>
                        <CellValue
                          collection={props.collection}
                          column={column}
                          record={record}
                          onOpenFile={props.onOpenFile}
                        />
                      </td>
                    ))}
                    <td className="row-actions">
                      <button className="icon-button" onClick={() => props.onEdit(record)} title="Edit" aria-label="Edit">
                        <Edit3 size={16} />
                      </button>
                      <button
                        className="icon-button danger"
                        onClick={() => props.onDelete(record)}
                        title="Delete"
                        aria-label="Delete"
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
    </section>
  );
}

type CellValueProps = {
  collection: CollectionSchema;
  column: string;
  record: RecordItem;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function CellValue({ collection, column, record, onOpenFile }: CellValueProps) {
  const field = collection.fields?.find((item) => item.name === column);
  const value = record[column];

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
  const json = JSON.stringify(collection, null, 2);
  return (
    <section className="schema-layout">
      <div className="schema-summary">
        <div className="summary-row">
          <span>ID</span>
          <code>{collection.id}</code>
        </div>
        <div className="summary-row">
          <span>Type</span>
          <strong>{collection.type}</strong>
        </div>
        <div className="summary-row">
          <span>System</span>
          <strong>{collection.system ? "true" : "false"}</strong>
        </div>
        <div className="schema-actions">
          <button className="primary" onClick={onEdit}>
            <Edit3 size={16} />
            Edit schema
          </button>
          <button className="subtle" onClick={() => onCopy(json)}>
            <Copy size={16} />
            Copy JSON
          </button>
          <button className="danger subtle" onClick={onDelete} disabled={collection.system}>
            <Trash2 size={16} />
            Delete
          </button>
        </div>
      </div>

      {collection.type === "auth" && authMethods && (
        <div className="auth-methods-panel">
          <article className="auth-method-card">
            <header>
              <strong>Password</strong>
              <span>{authMethods.password.enabled ? "enabled" : "disabled"}</span>
            </header>
            <p>{authMethods.password.identityFields.join(", ") || "none"}</p>
          </article>
          <article className="auth-method-card">
            <header>
              <strong>OTP</strong>
              <span>{authMethods.otp.enabled ? "enabled" : "disabled"}</span>
            </header>
            <p>{authMethods.otp.enabled ? `${authMethods.otp.duration}s window` : "No one-time passwords"}</p>
          </article>
          <article className="auth-method-card">
            <header>
              <strong>MFA</strong>
              <span>{authMethods.mfa.enabled ? "enabled" : "disabled"}</span>
            </header>
            <p>{authMethods.mfa.enabled ? `${authMethods.mfa.duration}s challenge` : "No second factor"}</p>
          </article>
          <article className="auth-method-card auth-method-card-wide">
            <header>
              <strong>OAuth2</strong>
              <span>{authMethods.oauth2.enabled ? "enabled" : "disabled"}</span>
            </header>
            {authMethods.oauth2.providers.length === 0 ? (
              <p>No providers configured</p>
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
                        {oauthTestingProvider === provider.name ? "Waiting..." : "Test"}
                      </button>
                    </div>
                    <span>{provider.authURL ? "ready" : "missing credentials"}</span>
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
              {field.required && <span>required</span>}
              {field.unique && <span>unique</span>}
              {field.hidden && <span>hidden</span>}
              {field.protected && <span>protected</span>}
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
  backupName: string;
  canBackup: boolean;
  loading: boolean;
  uploadRef: RefObject<HTMLInputElement | null>;
  onBackupName: (value: string) => void;
  onCreate: () => void;
  onRefresh: () => void;
  onUpload: (file: File) => void;
  onDownload: (backup: BackupInfo) => void;
  onRestore: (backup: BackupInfo) => void;
  onDelete: (backup: BackupInfo) => void;
};

function BackupView(props: BackupViewProps) {
  return (
    <section className="surface">
      <div className="surface-toolbar">
        <div className="query-grid backup-controls">
          <label>
            Name
            <input
              value={props.backupName}
              onChange={(event) => props.onBackupName(event.target.value)}
              placeholder="backup.zip"
            />
          </label>
          <button className="primary apply-button" onClick={props.onCreate} disabled={!props.canBackup || props.loading}>
            <Archive size={16} />
            Create
          </button>
          <button className="subtle apply-button" onClick={() => props.uploadRef.current?.click()}>
            <Upload size={16} />
            Upload
          </button>
          <button className="icon-button" onClick={props.onRefresh} title="Refresh backups" aria-label="Refresh backups">
            <RefreshCw size={17} />
          </button>
          <input
            ref={props.uploadRef}
            className="hidden-input"
            type="file"
            accept=".zip,application/zip"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) props.onUpload(file);
            }}
          />
        </div>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Size</th>
              <th>Modified</th>
              <th className="actions-col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {props.backups.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={4}>
                  No backups
                </td>
              </tr>
            ) : (
              props.backups.map((backup) => (
                <tr key={backup.key}>
                  <td>
                    <code>{backup.name}</code>
                  </td>
                  <td>{formatBytes(backup.size)}</td>
                  <td>{formatDate(backup.modified)}</td>
                  <td className="row-actions">
                    <button
                      className="icon-button"
                      onClick={() => props.onDownload(backup)}
                      title="Download"
                      aria-label="Download"
                    >
                      <Download size={16} />
                    </button>
                    <button
                      className="icon-button"
                      onClick={() => props.onRestore(backup)}
                      title="Restore"
                      aria-label="Restore"
                    >
                      <FileUp size={16} />
                    </button>
                    <button
                      className="icon-button danger"
                      onClick={() => props.onDelete(backup)}
                      title="Delete"
                      aria-label="Delete"
                    >
                      <Trash2 size={16} />
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

type CronsViewProps = {
  crons: CronJob[];
  loading: boolean;
  onRefresh: () => void;
  onRun: (job: CronJob) => void;
};

function CronsView(props: CronsViewProps) {
  return (
    <section className="surface crons-surface">
      <div className="surface-toolbar">
        <div className="table-meta crons-meta">
          <span>{props.crons.length} jobs</span>
        </div>
        <button className="icon-button" onClick={props.onRefresh} title="Refresh crons" aria-label="Refresh crons">
          <RefreshCw size={17} />
        </button>
      </div>

      <div className="table-wrap">
        <table className="crons-table">
          <thead>
            <tr>
              <th>Job</th>
              <th>Expression</th>
              <th className="actions-col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {props.crons.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={3}>
                  No crons
                </td>
              </tr>
            ) : (
              props.crons.map((cron) => (
                <tr key={cron.id}>
                  <td>
                    <code>{cron.id}</code>
                  </td>
                  <td>
                    <code>{cron.expression}</code>
                  </td>
                  <td className="row-actions">
                    <button
                      className="icon-button"
                      onClick={() => props.onRun(cron)}
                      title="Run"
                      aria-label="Run"
                      disabled={props.loading}
                    >
                      <Play size={16} />
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

type SettingsViewProps = {
  settings: AppSettings | null;
  draft: string;
  loading: boolean;
  onDraft: (value: string) => void;
  onRefresh: () => void;
  onSave: () => void;
};

function SettingsView(props: SettingsViewProps) {
  const rawMeta = props.settings?.meta;
  const rawLogs = props.settings?.logs;
  const rawBackups = props.settings?.backups;
  const meta = isPlainObject(rawMeta) ? rawMeta : {};
  const logs = isPlainObject(rawLogs) ? rawLogs : {};
  const backups = isPlainObject(rawBackups) ? rawBackups : {};
  return (
    <section className="settings-page">
      <div className="settings-page-toolbar">
        <div className="top-actions">
          <button className="icon-button" onClick={props.onRefresh} title="Refresh settings" aria-label="Refresh settings">
            <RefreshCw size={17} />
          </button>
          <button className="primary" onClick={props.onSave} disabled={props.loading}>
            <Save size={16} />
            Save settings
          </button>
        </div>
      </div>

      <div className="settings-card-grid">
        <SettingValueCard title="Application" value={String(meta.appName ?? "pocketbase-java")} detail={String(meta.appURL ?? "")} />
        <SettingValueCard title="Sender" value={String(meta.senderName ?? "")} detail={String(meta.senderAddress ?? "")} />
        <SettingValueCard title="Logs retention" value={`${String(logs.maxDays ?? 5)} days`} detail={`min level ${String(logs.minLevel ?? 0)}`} />
        <SettingValueCard title="Auto backup" value={String(backups.cron || "disabled")} detail={`keep ${String(backups.cronMaxKeep ?? 3)}`} />
      </div>

      <section className="surface settings-editor">
        <label>
          Settings JSON
          <textarea value={props.draft} onChange={(event) => props.onDraft(event.target.value)} spellCheck={false} />
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
  return (
    <article className="setting-card">
      <span>{props.title}</span>
      <strong title={props.value}>{props.value || "not set"}</strong>
      <code title={props.detail}>{props.detail || "not set"}</code>
    </article>
  );
}

type MailSettingsViewProps = {
  settings: AppSettings | null;
  email: string;
  template: string;
  loading: boolean;
  onEmail: (value: string) => void;
  onTemplate: (value: string) => void;
  onTest: () => void;
};

function MailSettingsView(props: MailSettingsViewProps) {
  const meta = settingsObject(props.settings, "meta");
  const smtp = settingsObject(props.settings, "smtp");
  return (
    <section className="settings-page">
      <div className="settings-card-grid two">
        <SettingValueCard title="Sender name" value={String(meta.senderName ?? "")} detail={String(meta.senderAddress ?? "")} />
        <SettingValueCard title="SMTP" value={truthyText(smtp.enabled)} detail={`${String(smtp.host ?? "no host")}:${String(smtp.port ?? "")}`} />
      </div>
      <section className="surface settings-section">
        <div className="section-heading">
          <div>
            <h2>Test email</h2>
            <p>Send a test auth email with the current mail configuration.</p>
          </div>
          <Mail size={18} />
        </div>
        <div className="settings-form-grid">
          <label>
            Recipient
            <input value={props.email} onChange={(event) => props.onEmail(event.target.value)} placeholder="admin@example.com" />
          </label>
          <label>
            Template
            <select value={props.template} onChange={(event) => props.onTemplate(event.target.value)}>
              <option value="verification">verification</option>
              <option value="password-reset">password-reset</option>
              <option value="email-change">email-change</option>
              <option value="otp">otp</option>
              <option value="login-alert">login-alert</option>
            </select>
          </label>
          <button className="primary apply-button" onClick={props.onTest} disabled={props.loading || !props.email.trim()}>
            <Play size={16} />
            Send test
          </button>
        </div>
      </section>
    </section>
  );
}

type StorageSettingsViewProps = {
  settings: AppSettings | null;
  target: string;
  loading: boolean;
  onTarget: (value: string) => void;
  onTest: () => void;
};

function StorageSettingsView(props: StorageSettingsViewProps) {
  const storage = settingsObject(props.settings, "s3");
  const backups = settingsObject(props.settings, "backups");
  const backupS3 = isPlainObject(backups.s3) ? backups.s3 : {};
  return (
    <section className="settings-page">
      <div className="settings-card-grid two">
        <SettingValueCard title="Storage S3" value={truthyText(storage.enabled)} detail={String(storage.bucket ?? "no bucket")} />
        <SettingValueCard title="Backup S3" value={truthyText(backupS3.enabled)} detail={String(backupS3.bucket ?? "no bucket")} />
      </div>
      <section className="surface settings-section">
        <div className="section-heading">
          <div>
            <h2>S3 connection</h2>
            <p>Check the configured storage or backups filesystem target.</p>
          </div>
          <Server size={18} />
        </div>
        <div className="settings-form-grid compact">
          <label>
            Target
            <select value={props.target} onChange={(event) => props.onTarget(event.target.value)}>
              <option value="storage">storage</option>
              <option value="backups">backups</option>
            </select>
          </label>
          <button className="primary apply-button" onClick={props.onTest} disabled={props.loading}>
            <Play size={16} />
            Test S3
          </button>
        </div>
      </section>
    </section>
  );
}

type CollectionTransferViewProps = {
  mode: "export" | "import";
  draft: string;
  deleteMissing: boolean;
  loading: boolean;
  onDraft: (value: string) => void;
  onDeleteMissing: (value: boolean) => void;
  onExport: () => void;
  onImport: () => void;
  onCopy: (value: string) => void;
};

function CollectionTransferView(props: CollectionTransferViewProps) {
  const importing = props.mode === "import";
  return (
    <section className="surface transfer-surface">
      <div className="surface-toolbar">
        <div className="table-meta transfer-meta">
          <span>{importing ? "Paste a collections export" : "Current collection schema snapshot"}</span>
        </div>
        <div className="top-actions">
          <button className="subtle" onClick={props.onExport}>
            <RefreshCw size={16} />
            Refresh export
          </button>
          {!importing && (
            <button className="subtle" onClick={() => props.onCopy(props.draft)}>
              <Copy size={16} />
              Copy JSON
            </button>
          )}
          {importing && (
            <button className="primary" onClick={props.onImport} disabled={props.loading || !props.draft.trim()}>
              <Upload size={16} />
              Import
            </button>
          )}
        </div>
      </div>
      {importing && (
        <div className="bulkbar import-options">
          <label className="check-row">
            <input
              type="checkbox"
              checked={props.deleteMissing}
              onChange={(event) => props.onDeleteMissing(event.target.checked)}
            />
            Delete missing collections
          </label>
        </div>
      )}
      <div className="settings-editor">
        <label>
          Collections JSON
          <textarea
            value={props.draft}
            onChange={(event) => props.onDraft(event.target.value)}
            readOnly={!importing}
            spellCheck={false}
          />
        </label>
      </div>
    </section>
  );
}

type SqlViewProps = {
  query: string;
  result: SqlResult | null;
  error: string;
  loading: boolean;
  onQuery: (value: string) => void;
  onRun: () => void;
};

function SqlView(props: SqlViewProps) {
  const columns = props.result?.columns ?? [];
  const rows = props.result?.rows ?? [];
  return (
    <section className="sql-layout">
      <section className="surface sql-editor">
        <div className="surface-toolbar">
          <div className="table-meta transfer-meta">
            <span>Superuser SQL console</span>
          </div>
          <button className="primary" onClick={props.onRun} disabled={props.loading || !props.query.trim()}>
            <Play size={16} />
            Run query
          </button>
        </div>
        <label className="sql-textarea">
          Query
          <textarea value={props.query} onChange={(event) => props.onQuery(event.target.value)} spellCheck={false} />
        </label>
      </section>

      <section className="surface sql-result">
        <div className="table-meta">
          <span>{Number(props.result?.affectedRows ?? 0)} affected rows</span>
          <span>{rows.length} result rows</span>
          {props.error && <span className="danger">{props.error}</span>}
        </div>
        <div className="table-wrap">
          <table className="sql-table">
            <thead>
              <tr>
                {columns.length === 0 ? <th>Result</th> : columns.map((column) => <th key={column.name}>{column.name}</th>)}
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 ? (
                <tr>
                  <td className="empty-row" colSpan={Math.max(1, columns.length)}>
                    No rows
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
};

function LogsView(props: LogsViewProps) {
  const [selected, setSelected] = useState<LogItem | null>(null);
  const total = props.logPage?.totalItems ?? props.logs.length;
  const statsTotal = props.stats.reduce((sum, item) => sum + Number(item.total || 0), 0);

  useEffect(() => {
    if (selected && !props.logs.some((log) => log.id === selected.id)) {
      setSelected(null);
    }
  }, [props.logs, selected]);

  return (
    <section className="surface logs-surface">
      <div className="surface-toolbar">
        <div className="query-grid logs-controls">
          <label>
            Filter
            <input
              value={props.filter}
              onChange={(event) => props.onFilter(event.target.value)}
              placeholder="data.status >= 400"
            />
          </label>
          <button className="subtle apply-button" onClick={props.onRefresh} disabled={props.loading}>
            <ListFilter size={16} />
            Apply
          </button>
          <button className="icon-button" onClick={props.onRefresh} title="Refresh logs" aria-label="Refresh logs">
            <RefreshCw size={17} />
          </button>
        </div>
      </div>

      <div className="table-meta">
        <span>{total} logs</span>
        <span>{statsTotal} hourly events</span>
      </div>

      <div className="table-wrap">
        <table className="logs-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Method</th>
              <th>Status</th>
              <th>URL</th>
              <th>Auth</th>
              <th>Exec</th>
              <th className="actions-col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {props.logs.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={7}>
                  No logs
                </td>
              </tr>
            ) : (
              props.logs.map((log) => {
                const data = log.data ?? {};
                const status = Number(data.status ?? 0);
                return (
                  <tr key={log.id}>
                    <td>{formatDate(log.created)}</td>
                    <td>
                      <code>{String(data.method ?? "")}</code>
                    </td>
                    <td>
                      <span className={status >= 500 ? "status-code error" : status >= 400 ? "status-code warn" : "status-code ok"}>
                        {status || ""}
                      </span>
                    </td>
                    <td>
                      <code title={String(data.url ?? "")}>{String(data.url ?? "")}</code>
                    </td>
                    <td>
                      <code>{String(data.authId ?? data.auth ?? "")}</code>
                    </td>
                    <td>{formatExecTime(data.execTime)}</td>
                    <td className="row-actions">
                      <button className="icon-button" onClick={() => setSelected(log)} title="Inspect log" aria-label="Inspect log">
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

      {selected && <pre className="json-panel log-json">{JSON.stringify(selected, null, 2)}</pre>}
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
};

type RuleKey = "listRule" | "viewRule" | "createRule" | "updateRule" | "deleteRule";

type CollectionModalProps = {
  state: CollectionEditorState;
  oauthProviders: OAuthProviderMetadata[];
  onClose: () => void;
  onSubmit: (payload: CollectionPayload) => void;
};

function CollectionModal({ state, oauthProviders, onClose, onSubmit }: CollectionModalProps) {
  const collection = state.collection;
  const [name, setName] = useState(collection?.name ?? "");
  const [type, setType] = useState(collection?.type ?? "base");
  const [fields, setFields] = useState(JSON.stringify(collection?.fields ?? DEFAULT_FIELDS, null, 2));
  const [viewQuery, setViewQuery] = useState(collection?.viewQuery ?? "");
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
  const [rules, setRules] = useState({
    listRule: collection?.listRule ?? "",
    viewRule: collection?.viewRule ?? "",
    createRule: collection?.createRule ?? "",
    updateRule: collection?.updateRule ?? "",
    deleteRule: collection?.deleteRule ?? ""
  });
  const [error, setError] = useState("");
  const [activeTab, setActiveTab] = useState("fields");
  const tabs = useMemo(() => collectionModalTabs(type), [type]);

  useEffect(() => {
    if (!tabs.some((tab) => tab.id === activeTab)) {
      setActiveTab(tabs[0]?.id ?? "fields");
    }
  }, [activeTab, tabs]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const parsedFields = JSON.parse(fields || "[]") as FieldSchema[];
      if (!Array.isArray(parsedFields)) throw new Error("Fields must be an array.");
      onSubmit({
        name: name.trim(),
        type,
        fields: type === "view" ? [] : parsedFields,
        listRule: nullableRule(rules.listRule),
        viewRule: nullableRule(rules.viewRule),
        createRule: nullableRule(rules.createRule),
        updateRule: nullableRule(rules.updateRule),
        deleteRule: nullableRule(rules.deleteRule),
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
                providers: oauthProviderNames.map((provider) => ({
                  name: provider,
                  clientId: oauthProviderConfigs[provider]?.clientId?.trim() ?? "",
                  clientSecret: oauthProviderConfigs[provider]?.clientSecret?.trim() ?? "",
                  authURL: oauthProviderConfigs[provider]?.authURL?.trim() ?? "",
                  tokenURL: oauthProviderConfigs[provider]?.tokenURL?.trim() ?? "",
                  userInfoURL: oauthProviderConfigs[provider]?.userInfoURL?.trim() ?? "",
                  scopes: splitScopes(oauthProviderConfigs[provider]?.scopes),
                  pkce: oauthProviderConfigs[provider]?.pkce ?? true
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
        scopes: [],
        pkce: true
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
        pkce: true,
        ...current[name],
        ...patch
      }
    }));
  }

  const fieldsPreview = useMemo(() => parseFieldsPreview(fields), [fields]);

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

  return (
    <Modal title={state.mode === "edit" ? `Edit ${collection?.name}` : "New collection"} onClose={onClose} wide>
      <form className="modal-grid collection-upsert-form" onSubmit={submit}>
        <section className="collection-modal-head">
          <div className="collection-name-field">
            <label>
              Name{collection?.system ? " (system)" : ""}
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
          <div className="collection-type-switch" aria-label="Collection type">
            {[
              { id: "base", label: "Base", icon: Database },
              { id: "view", label: "View", icon: Code2 },
              { id: "auth", label: "Auth", icon: Shield }
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

        <nav className="collection-modal-tabs" aria-label="Collection editor tabs">
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
              <strong>Fields</strong>
              <span>{fieldsPreview.error ? "Invalid fields JSON" : `${fieldsPreview.fields.length} configured fields`}</span>
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
                <p className="sidebar-empty">No fields configured</p>
              ) : (
                fieldsPreview.fields.map((field, index) => (
                  <article className="field-builder-row" key={`${field.name}-${index}`}>
                    <div>
                      <strong>{field.name || "(unnamed)"}</strong>
                      <span>{field.type || "unknown"}</span>
                    </div>
                    <div className="chips">
                      {field.required && <span>required</span>}
                      {field.unique && <span>unique</span>}
                      {field.hidden && <span>hidden</span>}
                      {field.system && <span>system</span>}
                    </div>
                    <button
                      className="icon-button danger"
                      type="button"
                      onClick={() => removeField(index)}
                      title="Remove field"
                      aria-label="Remove field"
                    >
                      <Trash2 size={15} />
                    </button>
                  </article>
                ))
              )}
            </div>
          )}
          <label>
            Fields JSON
            <textarea value={fields} onChange={(event) => setFields(event.target.value)} spellCheck={false} />
          </label>
          </section>
        )}

        {activeTab === "query" && (
          <section className="collection-query-panel collection-tab-panel">
            <label>
              View query
              <textarea
                value={viewQuery}
                onChange={(event) => setViewQuery(event.target.value)}
                placeholder="select id, created, updated from posts"
                spellCheck={false}
              />
            </label>
          </section>
        )}

        {activeTab === "auth" && type === "auth" && (
          <section className="auth-config-grid collection-tab-panel">
            <article className="auth-config-card">
              <header>
                <strong>Password auth</strong>
              </header>
              <label className="check-row">
                <input type="checkbox" checked={passwordEnabled} onChange={(event) => setPasswordEnabled(event.target.checked)} />
                Enabled
              </label>
              <div className="stacked-checks">
                <label className="check-row">
                  <input
                    type="checkbox"
                    checked={identityFields.includes("email")}
                    onChange={() => toggleIdentityField("email")}
                  />
                  Email identity
                </label>
                <label className="check-row">
                  <input
                    type="checkbox"
                    checked={identityFields.includes("username")}
                    onChange={() => toggleIdentityField("username")}
                  />
                  Username identity
                </label>
              </div>
            </article>

            <article className="auth-config-card">
              <header>
                <strong>OTP</strong>
              </header>
              <label className="check-row">
                <input type="checkbox" checked={otpEnabled} onChange={(event) => setOtpEnabled(event.target.checked)} />
                Enabled
              </label>
              <div className="two-col compact">
                <label>
                  Duration (s)
                  <input
                    type="number"
                    min={60}
                    value={otpDuration}
                    onChange={(event) => setOtpDuration(event.target.value)}
                  />
                </label>
                <label>
                  Length
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
                <strong>MFA</strong>
              </header>
              <label className="check-row">
                <input type="checkbox" checked={mfaEnabled} onChange={(event) => setMfaEnabled(event.target.checked)} />
                Enabled
              </label>
              <label>
                Duration (s)
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
                <strong>OAuth2</strong>
              </header>
              <label className="check-row">
                <input type="checkbox" checked={oauthEnabled} onChange={(event) => setOauthEnabled(event.target.checked)} />
                Enabled
              </label>
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
                      scopes: [],
                      pkce: true
                    };
                    return (
                      <article className="oauth-provider-config-card" key={providerName}>
                        <header>
                          <strong>{oauthProviders.find((provider) => provider.name === providerName)?.displayName ?? providerName}</strong>
                        </header>
                        <div className="two-col oauth-provider-fields">
                          <label>
                            Client ID
                            <input
                              value={config.clientId ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { clientId: event.target.value })}
                            />
                          </label>
                          <label>
                            Client Secret
                            <input
                              value={config.clientSecret ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { clientSecret: event.target.value })}
                            />
                          </label>
                        </div>
                        <div className="two-col oauth-provider-fields">
                          <label>
                            Auth URL
                            <input
                              value={config.authURL ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { authURL: event.target.value })}
                            />
                          </label>
                          <label>
                            Token URL
                            <input
                              value={config.tokenURL ?? ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { tokenURL: event.target.value })}
                            />
                          </label>
                        </div>
                        <label>
                          User Info URL
                          <input
                            value={config.userInfoURL ?? ""}
                            onChange={(event) => updateOauthProviderConfig(providerName, { userInfoURL: event.target.value })}
                          />
                        </label>
                        <div className="two-col oauth-provider-fields">
                          <label>
                            Scopes
                            <input
                              value={Array.isArray(config.scopes) ? config.scopes.join(", ") : ""}
                              onChange={(event) => updateOauthProviderConfig(providerName, { scopes: splitScopes(event.target.value) })}
                              placeholder="openid, email, profile"
                            />
                          </label>
                          <label className="check-row oauth-pkce-toggle">
                            <input
                              type="checkbox"
                              checked={config.pkce ?? true}
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
                <strong>Available fields</strong>
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
                <strong>Request fields</strong>
                <div className="chips">
                  {["@request.auth.*", "@request.body.*", "@request.query.*", "@collection.*"].map((item) => (
                    <span key={item}>{item}</span>
                  ))}
                </div>
              </div>
            </div>
            <div className="rules-grid official">
              {collectionRuleKeys(type).map((key) => (
                <label key={key}>
                  {collectionRuleLabel(key)}
                  <textarea
                    value={rules[key]}
                    onChange={(event) => setRules({ ...rules, [key]: event.target.value })}
                    placeholder={key === "listRule" ? '@request.auth.id != ""' : ""}
                    spellCheck={false}
                    disabled={Boolean(collection?.system)}
                  />
                </label>
              ))}
            </div>
          </section>
        )}
        {error && <p className="form-error">{error}</p>}
        <div className="modal-actions">
          <button type="button" className="subtle" onClick={onClose}>
            <X size={16} />
            Cancel
          </button>
          <button className="primary" type="submit">
            <Save size={16} />
            Save
          </button>
        </div>
      </form>
    </Modal>
  );
}

type RecordModalProps = {
  collection: CollectionSchema;
  state: RecordEditorState;
  onClose: () => void;
  onSubmit: (
    payload: Record<string, unknown>,
    files: Record<string, File[]>,
    options?: { close?: boolean }
  ) => Promise<void> | void;
};

function RecordModal({ collection, state, onClose, onSubmit }: RecordModalProps) {
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
  const [saving, setSaving] = useState(false);
  const showTabs = Boolean(state.record?.id) && collection.type === "auth" && collection.name !== "_superusers";
  const changed = JSON.stringify(payload) !== JSON.stringify(basePayload) || Object.values(files).some((items) => items.length > 0);

  useEffect(() => {
    if (!changed) return;
    localStorage.setItem(draftKey, JSON.stringify(payload));
  }, [changed, draftKey, payload]);

  useEffect(() => {
    if (!showTabs && activeTab !== "main") setActiveTab("main");
  }, [activeTab, showTabs]);

  function updatePayload(field: FieldSchema, value: unknown) {
    setPayload((current) => {
      const next = { ...current, [field.name]: value };
      setJson(JSON.stringify(next, null, 2));
      return next;
    });
    setError("");
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
      if (!isPlainObject(parsedPayload)) throw new Error("Record payload must be an object.");
      await onSubmit(parsedPayload, files, { close });
      setBasePayload(parsedPayload);
      setPayload(parsedPayload);
      setFiles({});
      localStorage.removeItem(draftKey);
      setInitialDraft(null);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title={state.record ? `Edit ${state.record.id}` : `New ${collection.name}`} onClose={onClose} wide>
      <form className="modal-grid record-upsert-form" onSubmit={(event) => submit(event, true)}>
        {initialDraft && (
          <div className="draft-alert">
            <div>
              <strong>Unsaved draft</strong>
              <span>This record has locally saved changes.</span>
            </div>
            <button type="button" className="subtle" onClick={restoreDraft}>
              <RotateCcw size={15} />
              Restore draft
            </button>
            <button type="button" className="icon-button" onClick={discardDraft} title="Discard draft" aria-label="Discard draft">
              <X size={15} />
            </button>
          </div>
        )}

        {showTabs && (
          <nav className="record-modal-tabs" aria-label="Record editor tabs">
            <button type="button" className={activeTab === "main" ? "active" : ""} onClick={() => setActiveTab("main")}>
              Account
              {changed && activeTab !== "main" && <span className="tab-dot" />}
            </button>
            <button
              type="button"
              className={activeTab === "providers" ? "active" : ""}
              onClick={() => setActiveTab("providers")}
            >
              Auth providers
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
                  <h2>{collection.type === "auth" ? "Account" : "Fields"}</h2>
                  <p>{editableFields.length} editable fields</p>
                </div>
              </div>
              <div className="record-field-grid">
                {editableFields.length === 0 ? (
                  <p className="sidebar-empty">No editable fields</p>
                ) : (
                  editableFields.map((field) => (
                    <RecordFieldControl
                      key={field.name}
                      field={field}
                      value={payload[field.name]}
                      onChange={(value) => updatePayload(field, value)}
                    />
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
          <button type="button" className="subtle" onClick={onClose}>
            <X size={16} />
            Close
          </button>
          <button type="button" className="subtle" onClick={resetForm} disabled={!changed || saving}>
            <RotateCcw size={16} />
            Reset form
          </button>
          <span className="modal-actions-spacer" />
          <button className="primary" type="submit" disabled={saving}>
            <Save size={16} />
            {state.record ? "Save changes" : "Create"}
          </button>
          <button className="subtle" type="button" onClick={() => submit(null, false)} disabled={saving}>
            Save and continue
          </button>
        </div>
      </form>
    </Modal>
  );
}

function AuthProvidersPanel({ collection, record }: { collection: CollectionSchema; record?: RecordItem }) {
  const providers = collection.oauth2?.providers ?? [];
  return (
    <section className="auth-providers-panel">
      {providers.length === 0 ? (
        <EmptyState icon={Shield} title="No auth providers configured" />
      ) : (
        providers.map((provider) => (
          <article className="auth-provider-row" key={provider.name}>
            <div className="nav-icon">
              <Shield size={16} />
            </div>
            <div>
              <strong>{provider.name}</strong>
              <span>{provider.clientId ? "configured" : "missing credentials"}</span>
            </div>
            <code>{String(record?.[`${provider.name}Id`] ?? "not linked")}</code>
          </article>
        ))
      )}
    </section>
  );
}

type RecordFieldControlProps = {
  field: FieldSchema;
  value: unknown;
  onChange: (value: unknown) => void;
};

function RecordFieldControl({ field, value, onChange }: RecordFieldControlProps) {
  const commonMeta = (
    <span className="record-field-meta">
      {field.type}
      {field.required ? " / required" : ""}
      {field.unique ? " / unique" : ""}
    </span>
  );

  if (field.type === "bool") {
    return (
      <label className="record-field-card checkbox-field">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <input name={field.name} type="checkbox" checked={Boolean(value)} onChange={(event) => onChange(event.target.checked)} />
      </label>
    );
  }

  if (field.type === "number" || field.type === "autonumber") {
    return (
      <label className="record-field-card">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <input
          name={field.name}
          autoComplete="off"
          type="number"
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(event) => onChange(event.target.value === "" ? null : Number(event.target.value))}
        />
      </label>
    );
  }

  if (field.type === "json") {
    return (
      <label className="record-field-card wide">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <textarea
          name={field.name}
          className="compact-textarea"
          value={value === undefined ? "" : typeof value === "string" ? value : JSON.stringify(value, null, 2)}
          onChange={(event) => {
            const raw = event.target.value;
            try {
              onChange(raw.trim() ? JSON.parse(raw) : null);
            } catch {
              onChange(raw);
            }
          }}
          spellCheck={false}
        />
      </label>
    );
  }

  if (field.type === "editor") {
    return (
      <label className="record-field-card wide">
        <span>
          <strong>{field.name}</strong>
          {commonMeta}
        </span>
        <textarea
          name={field.name}
          className="compact-textarea"
          value={value === undefined || value === null ? "" : String(value)}
          onChange={(event) => onChange(event.target.value)}
        />
      </label>
    );
  }

  const inputType = field.type === "email" ? "email" : field.type === "url" ? "url" : field.type === "password" ? "password" : "text";
  const relationMulti = field.type === "relation" && maxFiles(field) > 1;
  return (
    <label className="record-field-card">
      <span>
        <strong>{field.name}</strong>
        {commonMeta}
      </span>
      <input
        name={field.name}
        autoComplete="off"
        type={inputType}
        value={fieldInputValue(value)}
        placeholder={relationMulti ? "id1, id2" : ""}
        onChange={(event) => onChange(relationMulti ? splitCsv(event.target.value) : event.target.value)}
      />
    </label>
  );
}

type ModalProps = {
  title: string;
  onClose: () => void;
  wide?: boolean;
  children: ReactNode;
};

function Modal({ title, onClose, wide, children }: ModalProps) {
  return (
    <div className="modal-backdrop" role="presentation">
      <section className={wide ? "modal wide" : "modal"} role="dialog" aria-modal="true" aria-label={title}>
        <header>
          <h2>{title}</h2>
          <button className="icon-button" onClick={onClose} title="Close" aria-label="Close">
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
  return (
    <Modal title={`OAuth2 Result: ${result.provider.displayName || result.provider.name}`} onClose={onClose} wide>
      <div className="modal-grid">
        <div className="summary-row compact">
          <span>Token</span>
          <code>{result.response.token}</code>
        </div>
        <label>
          Record
          <textarea value={JSON.stringify(result.response.record, null, 2)} readOnly spellCheck={false} />
        </label>
        <label>
          Meta
          <textarea value={JSON.stringify(result.response.meta ?? {}, null, 2)} readOnly spellCheck={false} />
        </label>
        <div className="modal-actions">
          <button type="button" className="subtle" onClick={onClose}>
            <X size={16} />
            Close
          </button>
        </div>
      </div>
    </Modal>
  );
}

function StatusPill({ health, loading }: { health: HealthResponse["data"] | null; loading: boolean }) {
  return (
    <span className={loading ? "status busy" : health ? "status ready" : "status offline"}>
      {loading ? "syncing" : health ? "online" : "offline"}
    </span>
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
    const apiError = isPlainObject(parsed) ? (parsed as ApiError) : {};
    throw new Error(apiError.message || text || `${response.status} ${response.statusText}`);
  }
  return parsed as T;
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
  return ["id", ...fieldNames, "created", "updated"];
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

function nullableRule(value: string) {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function splitScopes(value: string | string[] | undefined) {
  const items = Array.isArray(value) ? value : String(value ?? "").split(",");
  return items.map((item) => item.trim()).filter(Boolean);
}

function waitForOAuthResult(expectedState: string, popup: Window, timeoutMs = 120000) {
  sessionStorage.removeItem("pbj-oauth2-result");
  return new Promise<{ state: string; code: string; error: string }>((resolve, reject) => {
    let settled = false;
    let intervalId = 0;
    let timeoutId = 0;

    const cleanup = () => {
      window.removeEventListener("message", onMessage);
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
      if (payload.source !== "pocketbase-java-oauth2") return;
      if (String(payload.state ?? "") !== expectedState) return;
      finish(() =>
        resolve({
          state: String(payload.state ?? ""),
          code: String(payload.code ?? ""),
          error: String(payload.error ?? "")
        })
      );
    };

    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      handlePayload(event.data);
    };

    window.addEventListener("message", onMessage);
    intervalId = window.setInterval(() => {
      try {
        const raw = sessionStorage.getItem("pbj-oauth2-result");
        if (raw) {
          sessionStorage.removeItem("pbj-oauth2-result");
          handlePayload(JSON.parse(raw));
          return;
        }
      } catch {
        // ignore invalid storage payloads
      }
      if (popup.closed) {
        finish(() => reject(new Error("OAuth2 popup was closed before authentication completed.")));
      }
    }, 250);
    timeoutId = window.setTimeout(() => {
      finish(() => reject(new Error("OAuth2 popup timed out.")));
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

function viewMeta(view: ViewName, collection: CollectionSchema | null) {
  const titles: Record<ViewName, { title: string; eyebrow: string }> = {
    records: { title: collection?.name ?? "Collections", eyebrow: collection?.type ?? "Admin console" },
    schema: { title: collection?.name ?? "Collections", eyebrow: "Schema" },
    settings: { title: "Settings", eyebrow: "Application" },
    mail: { title: "Mail settings", eyebrow: "Application" },
    storage: { title: "File storage", eyebrow: "Application" },
    backups: { title: "Backups", eyebrow: "Maintenance" },
    crons: { title: "Crons", eyebrow: "Scheduler" },
    export: { title: "Export collections", eyebrow: "System" },
    import: { title: "Import collections", eyebrow: "System" },
    sql: { title: "SQL console", eyebrow: "System" },
    logs: { title: "Logs", eyebrow: "Observability" }
  };
  return titles[view];
}

function settingsObject(settings: AppSettings | null, section: string) {
  const value = settings?.[section];
  return isPlainObject(value) ? value : {};
}

function truthyText(value: unknown) {
  return value ? "enabled" : "disabled";
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

function parseFieldsPreview(value: string) {
  try {
    const parsed = JSON.parse(value || "[]");
    if (!Array.isArray(parsed)) return { fields: [] as FieldSchema[], error: "Fields must be an array." };
    return { fields: parsed as FieldSchema[], error: "" };
  } catch (error) {
    return { fields: [] as FieldSchema[], error: errorMessage(error) };
  }
}

function collectionModalTabs(type: string) {
  if (type === "view") {
    return [
      { id: "query", label: "Query" },
      { id: "rules", label: "API rules" }
    ];
  }
  if (type === "auth") {
    return [
      { id: "fields", label: "Fields" },
      { id: "rules", label: "API rules" },
      { id: "auth", label: "Options" }
    ];
  }
  return [
    { id: "fields", label: "Fields" },
    { id: "rules", label: "API rules" }
  ];
}

function collectionRuleKeys(type: string): RuleKey[] {
  if (type === "view") return ["listRule", "viewRule"];
  return ["listRule", "viewRule", "createRule", "updateRule", "deleteRule"];
}

function collectionRuleLabel(key: RuleKey) {
  const labels: Record<RuleKey, string> = {
    listRule: "List/Search rule",
    viewRule: "View rule",
    createRule: "Create rule",
    updateRule: "Update rule",
    deleteRule: "Delete rule"
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

function fieldInputValue(value: unknown) {
  if (value === undefined || value === null) return "";
  if (Array.isArray(value)) return value.map(String).join(", ");
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function splitCsv(value: string) {
  return value.split(",").map((item) => item.trim()).filter(Boolean);
}

function errorMessage(error: unknown) {
  if (error instanceof Error) return error.message;
  return String(error);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export default App;
