import {
  Activity,
  Archive,
  Check,
  ChevronRight,
  Clock3,
  Copy,
  Database,
  Download,
  Edit3,
  FileArchive,
  FileUp,
  KeyRound,
  ListFilter,
  LogOut,
  Plus,
  Play,
  RefreshCw,
  Save,
  Search,
  Settings,
  Shield,
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

type QueryState = {
  filter: string;
  sort: string;
  perPage: number;
};

type ViewName = "records" | "schema" | "backups" | "settings" | "logs" | "crons";

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
  const backupUploadRef = useRef<HTMLInputElement>(null);

  const setupRequired = health ? !health.superuserReady : false;
  const authenticated = Boolean(token) && !setupRequired;
  const collectionView = view === "records" || view === "schema";
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
    if (authenticated && view === "settings") {
      refreshSettings().catch((error) => notify(errorMessage(error), "error"));
    }
  }, [authenticated, notify, refreshSettings, view]);

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

  async function saveRecord(payload: Record<string, unknown>, files: Record<string, File[]>) {
    if (!selected) return;
    try {
      const body = recordRequestBody(payload, files);
      const id = recordEditor?.record?.id;
      const path = id
        ? `/api/collections/${encodeURIComponent(selected.name)}/records/${encodeURIComponent(id)}`
        : `/api/collections/${encodeURIComponent(selected.name)}/records`;
      await api(path, { method: id ? "PATCH" : "POST", body });
      notify(id ? "Record saved" : "Record created");
      setRecordEditor(null);
      await refreshRecords(selected.name);
    } catch (error) {
      notify(errorMessage(error), "error");
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

  const columns = useMemo(() => recordColumns(selected), [selected]);
  const pageTitle =
    view === "backups"
      ? "Backups"
      : view === "settings"
        ? "Settings"
        : view === "logs"
          ? "Logs"
          : view === "crons"
            ? "Crons"
            : selected?.name ?? "Collections";
  const pageEyebrow =
    view === "backups"
      ? "Maintenance"
      : view === "settings"
        ? "System"
        : view === "logs"
          ? "Observability"
          : view === "crons"
            ? "Scheduler"
            : selected?.type ?? "Admin console";

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">PB</div>
          <div>
            <strong>pocketbase-java</strong>
            <span>{health ? (setupRequired ? "setup" : "ready") : "checking"}</span>
          </div>
        </div>

        <div className="search-box">
          <Search size={15} />
          <input
            value={collectionSearch}
            onChange={(event) => setCollectionSearch(event.target.value)}
            placeholder="Search collections"
          />
        </div>

        <nav className="collection-nav" aria-label="Collections">
          {visibleCollections.map((collection) => (
            <button
              key={collection.id || collection.name}
              className={selectedName === collection.name && collectionView ? "active" : ""}
              onClick={() => {
                setSelectedName(collection.name);
                setView("records");
              }}
              disabled={!authenticated}
            >
              <span className="nav-icon">{collection.type === "auth" ? <Shield size={16} /> : <Database size={16} />}</span>
              <span className="nav-text">
                <strong>{collection.name}</strong>
                <small>{collection.type}</small>
              </span>
              <ChevronRight size={15} />
            </button>
          ))}
        </nav>

        <div className="sidebar-actions">
          <button className="primary" onClick={() => setCollectionEditor({ mode: "create" })} disabled={!authenticated}>
            <Plus size={16} />
            Collection
          </button>
          <button
            className={view === "backups" ? "active subtle" : "subtle"}
            onClick={() => setView("backups")}
            disabled={!authenticated}
          >
            <FileArchive size={16} />
            Backups
          </button>
          <button
            className={view === "settings" ? "active subtle" : "subtle"}
            onClick={() => setView("settings")}
            disabled={!authenticated}
          >
            <Settings size={16} />
            Settings
          </button>
          <button
            className={view === "logs" ? "active subtle" : "subtle"}
            onClick={() => setView("logs")}
            disabled={!authenticated}
          >
            <Activity size={16} />
            Logs
          </button>
          <button
            className={view === "crons" ? "active subtle" : "subtle"}
            onClick={() => setView("crons")}
            disabled={!authenticated}
          >
            <Clock3 size={16} />
            Crons
          </button>
        </div>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">{pageEyebrow}</p>
            <h1>{pageTitle}</h1>
          </div>
          <div className="top-actions">
            <StatusPill health={health} loading={loading} />
            <button className="icon-button" onClick={refreshAll} title="Refresh" aria-label="Refresh">
              <RefreshCw size={17} />
            </button>
            <button className="icon-button danger" onClick={logout} title="Logout" aria-label="Logout" disabled={!token}>
              <LogOut size={17} />
            </button>
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
                  query={query}
                  recordPage={recordPage}
                  loading={loading}
                  onQuery={setQuery}
                  onApply={(nextQuery) => refreshRecords(selected.name, nextQuery)}
                  onNew={() => setRecordEditor({})}
                  onEdit={(record) => setRecordEditor({ record })}
                  onDelete={deleteRecord}
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

type RecordsViewProps = {
  collection: CollectionSchema;
  records: RecordItem[];
  columns: string[];
  query: QueryState;
  recordPage: ListResponse<RecordItem> | null;
  loading: boolean;
  onQuery: (query: QueryState) => void;
  onApply: (query: QueryState) => void;
  onNew: () => void;
  onEdit: (record: RecordItem) => void;
  onDelete: (record: RecordItem) => void;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function RecordsView(props: RecordsViewProps) {
  const [draft, setDraft] = useState(props.query);

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
              value={draft.filter}
              onChange={(event) => setDraft({ ...draft, filter: event.target.value })}
              placeholder='published = true'
            />
          </label>
          <label>
            Sort
            <input value={draft.sort} onChange={(event) => setDraft({ ...draft, sort: event.target.value })} />
          </label>
          <label>
            Per page
            <select
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
        <button className="primary" onClick={props.onNew}>
          <Plus size={16} />
          Record
        </button>
      </div>

      <div className="table-meta">
        <span>{props.recordPage?.totalItems ?? props.records.length} records</span>
        <span>{props.collection.fields?.length ?? 0} fields</span>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              {props.columns.map((column) => (
                <th key={column}>{column}</th>
              ))}
              <th className="actions-col">Actions</th>
            </tr>
          </thead>
          <tbody>
            {props.records.length === 0 ? (
              <tr>
                <td className="empty-row" colSpan={props.columns.length + 1}>
                  No records
                </td>
              </tr>
            ) : (
              props.records.map((record) => (
                <tr key={record.id}>
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
              ))
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
  const meta = isPlainObject(rawMeta) ? rawMeta : {};
  const logs = isPlainObject(rawLogs) ? rawLogs : {};
  return (
    <section className="surface settings-surface">
      <div className="surface-toolbar">
        <div className="settings-summary">
          <div className="summary-row compact">
            <span>App</span>
            <strong>{String(meta.appName ?? "pocketbase-java")}</strong>
          </div>
          <div className="summary-row compact">
            <span>URL</span>
            <code>{String(meta.appURL ?? "")}</code>
          </div>
          <div className="summary-row compact">
            <span>Logs</span>
            <strong>{String(logs.maxDays ?? 7)} days</strong>
          </div>
        </div>
        <div className="row-actions">
          <button className="icon-button" onClick={props.onRefresh} title="Refresh settings" aria-label="Refresh settings">
            <RefreshCw size={17} />
          </button>
          <button className="primary" onClick={props.onSave} disabled={props.loading}>
            <Save size={16} />
            Save
          </button>
        </div>
      </div>
      <div className="settings-editor">
        <label>
          Settings JSON
          <textarea value={props.draft} onChange={(event) => props.onDraft(event.target.value)} spellCheck={false} />
        </label>
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
};

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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const parsedFields = JSON.parse(fields || "[]") as FieldSchema[];
      if (!Array.isArray(parsedFields)) throw new Error("Fields must be an array.");
      onSubmit({
        name: name.trim(),
        type,
        fields: parsedFields,
        listRule: nullableRule(rules.listRule),
        viewRule: nullableRule(rules.viewRule),
        createRule: nullableRule(rules.createRule),
        updateRule: nullableRule(rules.updateRule),
        deleteRule: nullableRule(rules.deleteRule),
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

  return (
    <Modal title={state.mode === "edit" ? `Edit ${collection?.name}` : "New collection"} onClose={onClose} wide>
      <form className="modal-grid" onSubmit={submit}>
        <div className="two-col">
          <label>
            Name
            <input
              value={name}
              onChange={(event) => setName(event.target.value)}
              required
              pattern="[A-Za-z_][A-Za-z0-9_]{0,62}"
            />
          </label>
          <label>
            Type
            <select value={type} onChange={(event) => setType(event.target.value)} disabled={collection?.system}>
              <option value="base">base</option>
              <option value="auth">auth</option>
            </select>
          </label>
        </div>
        <label>
          Fields JSON
          <textarea value={fields} onChange={(event) => setFields(event.target.value)} spellCheck={false} />
        </label>
        {type === "auth" && (
          <section className="auth-config-grid">
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
        <div className="rules-grid">
          {(["listRule", "viewRule", "createRule", "updateRule", "deleteRule"] as const).map((key) => (
            <label key={key}>
              {key}
              <input value={rules[key]} onChange={(event) => setRules({ ...rules, [key]: event.target.value })} />
            </label>
          ))}
        </div>
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
  onSubmit: (payload: Record<string, unknown>, files: Record<string, File[]>) => void;
};

function RecordModal({ collection, state, onClose, onSubmit }: RecordModalProps) {
  const fileFields = (collection.fields ?? []).filter((field) => field.type === "file" && !field.hidden);
  const [json, setJson] = useState(JSON.stringify(recordEditorPayload(collection, state.record), null, 2));
  const [files, setFiles] = useState<Record<string, File[]>>({});
  const [error, setError] = useState("");

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const payload = JSON.parse(json || "{}") as Record<string, unknown>;
      if (!isPlainObject(payload)) throw new Error("Record payload must be an object.");
      onSubmit(payload, files);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Modal title={state.record ? `Edit ${state.record.id}` : `New ${collection.name}`} onClose={onClose} wide>
      <form className="modal-grid" onSubmit={submit}>
        <label>
          JSON
          <textarea value={json} onChange={(event) => setJson(event.target.value)} spellCheck={false} />
        </label>
        {fileFields.length > 0 && (
          <div className="file-upload-grid">
            {fileFields.map((field) => (
              <label key={field.name}>
                {field.name}
                <input
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

function errorMessage(error: unknown) {
  if (error instanceof Error) return error.message;
  return String(error);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export default App;
