import {
  Archive,
  Check,
  ChevronRight,
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
  RefreshCw,
  Save,
  Search,
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

type AuthMethodsResponse = {
  password?: {
    enabled?: boolean;
    identityFields?: string[];
  };
  oauth2?: unknown[];
  mfa?: {
    enabled?: boolean;
  };
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
  created?: string;
  updated?: string;
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
};

type BackupInfo = {
  key: string;
  name: string;
  size: number;
  modified: string;
};

type QueryState = {
  page: number;
  filter: string;
  sort: string;
  expand: string;
  fields: string;
  perPage: number;
};

type ViewName = "dashboard" | "records" | "schema" | "backups";
type SchemaSection = "fields" | "rules" | "auth" | "api" | "json";
type RuleMode = "locked" | "public" | "custom";
type RuleKey = "listRule" | "viewRule" | "createRule" | "updateRule" | "deleteRule";

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

type ApiOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
};

type RuleEditorState = {
  mode: RuleMode;
  value: string;
};

type RuleEditorSet = Record<RuleKey, RuleEditorState>;
type FileStrategy = "append" | "replace";

type FieldDraft = {
  clientKey: string;
  id?: string;
  name: string;
  type: string;
  required: boolean;
  unique: boolean;
  hidden: boolean;
  system: boolean;
  presentable: boolean;
  collectionId: string;
  maxSelect: string;
  maxSize: string;
  mimeTypes: string;
  thumbs: string;
  values: string;
  protectedFile: boolean;
  rawOptions: Record<string, unknown>;
};

type CollectionPayload = {
  name: string;
  type: string;
  fields: FieldSchema[];
  listRule: string | null;
  viewRule: string | null;
  createRule: string | null;
  updateRule: string | null;
  deleteRule: string | null;
};

const TOKEN_KEY = "pbj_token";
const DEFAULT_FIELDS = [{ name: "title", type: "text", required: true }];
const SYSTEM_RECORD_KEYS = new Set(["id", "collectionId", "collectionName", "created", "updated", "expand"]);
const RULE_KEYS: RuleKey[] = ["listRule", "viewRule", "createRule", "updateRule", "deleteRule"];
const FIELD_TYPE_OPTIONS = ["text", "email", "password", "bool", "number", "select", "json", "relation", "file"];

function App() {
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY) || "");
  const [health, setHealth] = useState<HealthResponse["data"] | null>(null);
  const [collections, setCollections] = useState<CollectionSchema[]>([]);
  const [selectedName, setSelectedName] = useState<string>("");
  const [records, setRecords] = useState<RecordItem[]>([]);
  const [recordPage, setRecordPage] = useState<ListResponse<RecordItem> | null>(null);
  const [query, setQuery] = useState<QueryState>({
    page: 1,
    filter: "",
    sort: "-created",
    expand: "",
    fields: "",
    perPage: 50
  });
  const [view, setView] = useState<ViewName>("dashboard");
  const [schemaSection, setSchemaSection] = useState<SchemaSection>("fields");
  const [collectionSearch, setCollectionSearch] = useState("");
  const [loading, setLoading] = useState(false);
  const [toast, setToast] = useState<ToastState | null>(null);
  const [authEmail, setAuthEmail] = useState("");
  const [authPassword, setAuthPassword] = useState("");
  const [collectionEditor, setCollectionEditor] = useState<CollectionEditorState | null>(null);
  const [recordEditor, setRecordEditor] = useState<RecordEditorState | null>(null);
  const [activeRecordId, setActiveRecordId] = useState("");
  const [backups, setBackups] = useState<BackupInfo[]>([]);
  const [backupName, setBackupName] = useState("");
  const [collectionCounts, setCollectionCounts] = useState<Record<string, number>>({});
  const [backupCount, setBackupCount] = useState<number | null>(null);
  const [authMethods, setAuthMethods] = useState<AuthMethodsResponse | null>(null);
  const [authMethodsLoading, setAuthMethodsLoading] = useState(false);
  const backupUploadRef = useRef<HTMLInputElement>(null);

  const setupRequired = health ? !health.superuserReady : false;
  const authenticated = Boolean(token) && !setupRequired;
  const selected = useMemo(
    () => collections.find((collection) => collection.name === selectedName) ?? null,
    [collections, selectedName]
  );
  const selectedRecord = useMemo(
    () => records.find((record) => record.id === activeRecordId) ?? null,
    [activeRecordId, records]
  );

  const visibleCollections = useMemo(() => {
    const search = collectionSearch.trim().toLowerCase();
    if (!search) return collections;
    return collections.filter((collection) => {
      return (
        collection.name.toLowerCase().includes(search) ||
        collection.type.toLowerCase().includes(search) ||
        (collection.system ? "system".includes(search) : false)
      );
    });
  }, [collectionSearch, collections]);

  const groupedCollections = useMemo(() => groupCollections(visibleCollections), [visibleCollections]);

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

  const refreshCollectionCounts = useCallback(async (items: CollectionSchema[], authToken: string) => {
    if (!authToken || items.length === 0) {
      setCollectionCounts({});
      return;
    }
    const settled = await Promise.allSettled(
      items.map(async (collection) => {
        const data = await apiRequest<ListResponse<RecordItem>>(
          `/api/collections/${encodeURIComponent(collection.name)}/records?page=1&perPage=1&fields=id`,
          authToken
        );
        return [collection.name, data.totalItems] as const;
      })
    );
    const next: Record<string, number> = {};
    settled.forEach((entry) => {
      if (entry.status === "fulfilled") {
        next[entry.value[0]] = entry.value[1];
      }
    });
    setCollectionCounts(next);
  }, []);

  const refreshBackupSummary = useCallback(
    async (authToken: string) => {
      if (!authToken || !health?.canBackup) {
        setBackupCount(null);
        return;
      }
      try {
        const data = await apiRequest<ListResponse<BackupInfo>>("/api/backups?page=1&perPage=1", authToken);
        setBackupCount(data.totalItems);
      } catch {
        setBackupCount(null);
      }
    },
    [health?.canBackup]
  );

  const refreshCollections = useCallback(
    async (authToken = token) => {
      if (!authToken) return [];
      setLoading(true);
      try {
        const data = await apiRequest<ListResponse<CollectionSchema>>("/api/collections?perPage=500&sort=name", authToken);
        setCollections(data.items);
        setSelectedName((current) => {
          if (current && data.items.some((collection) => collection.name === current)) return current;
          return data.items.find((collection) => !collection.system)?.name ?? data.items[0]?.name ?? "";
        });
        await refreshCollectionCounts(data.items, authToken);
        await refreshBackupSummary(authToken);
        return data.items;
      } finally {
        setLoading(false);
      }
    },
    [refreshBackupSummary, refreshCollectionCounts, token]
  );

  const refreshRecords = useCallback(
    async (collectionName = selectedName, nextQuery = query) => {
      if (!token || !collectionName) return;
      setLoading(true);
      try {
        const qs = buildQuery({
          page: nextQuery.page,
          perPage: nextQuery.perPage,
          sort: nextQuery.sort,
          filter: nextQuery.filter,
          expand: nextQuery.expand,
          fields: nextQuery.fields
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
      setBackupCount(data.totalItems);
    } finally {
      setLoading(false);
    }
  }, [token]);

  const refreshAll = useCallback(async () => {
    try {
      const status = await refreshHealth();
      if (token && status.superuserReady) {
        await refreshCollections(token);
      } else {
        setCollections([]);
        setRecords([]);
        setRecordPage(null);
        setCollectionCounts({});
        setBackupCount(null);
      }
    } catch (error) {
      notify(errorMessage(error), "error");
    }
  }, [notify, refreshCollections, refreshHealth, token]);

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
    if (!authenticated || view !== "schema" || !selected || selected.type !== "auth") {
      setAuthMethods(null);
      setAuthMethodsLoading(false);
      return;
    }

    let cancelled = false;
    setAuthMethodsLoading(true);
    api<AuthMethodsResponse>(`/api/collections/${encodeURIComponent(selected.name)}/auth-methods`)
      .then((data) => {
        if (!cancelled) {
          setAuthMethods(data);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setAuthMethods(null);
          notify(errorMessage(error), "error");
        }
      })
      .finally(() => {
        if (!cancelled) {
          setAuthMethodsLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [api, authenticated, notify, selected, view]);

  useEffect(() => {
    setActiveRecordId((current) => {
      if (current && records.some((record) => record.id === current)) return current;
      return records[0]?.id ?? "";
    });
  }, [records]);

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
        await refreshCollections(auth.token);
      }
    } catch (error) {
      notify(errorMessage(error), "error");
    } finally {
      setLoading(false);
    }
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
    setSelectedName("");
    setView("dashboard");
    setActiveRecordId("");
    setCollectionCounts({});
    setBackupCount(null);
    setAuthMethods(null);
    setAuthMethodsLoading(false);
  }

  function openCollection(name: string, nextView: ViewName = "records") {
    setSelectedName(name);
    setView(nextView);
    setActiveRecordId("");
    setQuery((current) => ({ ...current, page: 1 }));
    if (nextView === "schema") {
      setSchemaSection("fields");
    }
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
      setView("dashboard");
      await refreshCollections();
    } catch (error) {
      notify(errorMessage(error), "error");
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
      await refreshCollectionCounts(collections, token);
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
      await refreshCollectionCounts(collections, token);
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

  const columns = useMemo(() => recordColumns(selected), [selected]);
  const pageHeading = useMemo(() => {
    if (view === "dashboard") {
      return {
        eyebrow: "Admin console",
        title: "Overview",
        subtitle: `${collections.length} collections wired into the embedded runtime`
      };
    }
    if (view === "backups") {
      return {
        eyebrow: "Settings",
        title: "Backups",
        subtitle: health?.canBackup
          ? "Snapshot, restore and upload data archives"
          : "Backup operations are unavailable in the current runtime"
      };
    }
    return {
      eyebrow: selected?.type ?? "Collection",
      title: selected?.name ?? "Collections",
      subtitle: selected
        ? `${selected.fields?.length ?? 0} fields · ${collectionCounts[selected.name] ?? 0} records`
        : "Select a collection to inspect data and schema"
    };
  }, [collectionCounts, collections.length, health?.canBackup, selected, view]);

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

        <button
          className={view === "dashboard" ? "sidebar-link active" : "sidebar-link"}
          onClick={() => setView("dashboard")}
          disabled={!authenticated}
        >
          <Database size={16} />
          <span>Overview</span>
        </button>

        <div className="search-box">
          <Search size={15} />
          <input
            value={collectionSearch}
            onChange={(event) => setCollectionSearch(event.target.value)}
            placeholder="Search collections"
          />
        </div>

        <div className="sidebar-section">
          <div className="sidebar-heading">
            <span>Collections</span>
            <strong>{collections.length}</strong>
          </div>
          <div className="sidebar-groups">
            {groupedCollections.map((group) => (
              <section key={group.key} className="sidebar-group">
                <div className="group-heading">
                  <span>{group.label}</span>
                  <strong>{group.items.length}</strong>
                </div>
                <nav className="collection-nav" aria-label={`${group.label} collections`}>
                  {group.items.map((collection) => (
                    <button
                      key={collection.id || collection.name}
                      className={selectedName === collection.name && view !== "dashboard" && view !== "backups" ? "active" : ""}
                      onClick={() => openCollection(collection.name, "records")}
                      disabled={!authenticated}
                    >
                      <span className="nav-icon">{collection.type === "auth" ? <Shield size={16} /> : <Database size={16} />}</span>
                      <span className="nav-text">
                        <strong>{collection.name}</strong>
                        <small>
                          {collection.system ? "system" : collection.type}
                          {" · "}
                          {collectionCounts[collection.name] ?? 0} rec
                        </small>
                      </span>
                      <ChevronRight size={15} />
                    </button>
                  ))}
                </nav>
              </section>
            ))}
          </div>
        </div>

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
        </div>
      </aside>

      <main className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">{pageHeading.eyebrow}</p>
            <h1>{pageHeading.title}</h1>
            <p className="page-copy">{pageHeading.subtitle}</p>
          </div>
          <div className="top-actions">
            {authenticated && (
              <button className="primary" onClick={() => setCollectionEditor({ mode: "create" })}>
                <Plus size={16} />
                New collection
              </button>
            )}
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
        ) : view === "dashboard" ? (
          <DashboardView
            collections={collections}
            collectionCounts={collectionCounts}
            health={health}
            backupCount={backupCount}
            selected={selected}
            onOpenCollection={(name) => openCollection(name, "records")}
            onOpenSchema={(name) => openCollection(name, "schema")}
            onOpenBackups={() => setView("backups")}
            onCreateCollection={() => setCollectionEditor({ mode: "create" })}
          />
        ) : view === "backups" ? (
          <SettingsView canBackup={Boolean(health?.canBackup)}>
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
          </SettingsView>
        ) : selected ? (
          <>
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

            {view === "records" ? (
              <RecordsView
                collection={selected}
                records={records}
                columns={columns}
                query={query}
                recordPage={recordPage}
                loading={loading}
                activeRecordId={activeRecordId}
                selectedRecord={selectedRecord}
                onQuery={setQuery}
                onApply={(nextQuery) => refreshRecords(selected.name, nextQuery)}
                onPageChange={(page) => {
                  const nextQuery = { ...query, page };
                  setQuery(nextQuery);
                  refreshRecords(selected.name, nextQuery);
                }}
                onSelectRecord={setActiveRecordId}
                onNew={() => setRecordEditor({})}
                onEdit={(record) => setRecordEditor({ record })}
                onDelete={deleteRecord}
                onOpenFile={openFile}
              />
            ) : (
              <SchemaView
                collection={selected}
                section={schemaSection}
                authMethods={authMethods}
                authMethodsLoading={authMethodsLoading}
                onSection={setSchemaSection}
                onEdit={() => setCollectionEditor({ mode: "edit", collection: selected })}
                onDelete={() => deleteCollection(selected)}
                onCopy={(value) => {
                  navigator.clipboard.writeText(value).then(
                    () => notify("Copied"),
                    (error) => notify(errorMessage(error), "error")
                  );
                }}
              />
            )}
          </>
        ) : (
          <EmptyState icon={Database} title="No collection selected" />
        )}
      </main>

      {collectionEditor && (
        <CollectionModal
          state={collectionEditor}
          collections={collections}
          onClose={() => setCollectionEditor(null)}
          onSubmit={(payload) => saveCollection(payload)}
        />
      )}

      {recordEditor && selected && (
        <RecordModal
          collection={selected}
          collections={collections}
          token={token}
          state={recordEditor}
          onClose={() => setRecordEditor(null)}
          onSubmit={saveRecord}
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

type DashboardViewProps = {
  collections: CollectionSchema[];
  collectionCounts: Record<string, number>;
  health: HealthResponse["data"] | null;
  backupCount: number | null;
  selected: CollectionSchema | null;
  onOpenCollection: (name: string) => void;
  onOpenSchema: (name: string) => void;
  onOpenBackups: () => void;
  onCreateCollection: () => void;
};

function DashboardView(props: DashboardViewProps) {
  const stats = useMemo(() => {
    const base = props.collections.filter((collection) => !collection.system && collection.type === "base").length;
    const auth = props.collections.filter((collection) => !collection.system && collection.type === "auth").length;
    const system = props.collections.filter((collection) => collection.system).length;
    const fields = props.collections.reduce((sum, collection) => sum + (collection.fields?.length ?? 0), 0);
    const records = Object.values(props.collectionCounts).reduce((sum, count) => sum + count, 0);
    return { base, auth, system, fields, records };
  }, [props.collectionCounts, props.collections]);

  const recentCollections = useMemo(() => {
    return [...props.collections]
      .sort((left, right) => String(right.updated ?? "").localeCompare(String(left.updated ?? "")))
      .slice(0, 8);
  }, [props.collections]);

  return (
    <div className="dashboard-stack">
      <section className="surface overview-surface">
        <div className="surface-toolbar overview-toolbar">
          <div>
            <p className="eyebrow">Workspace</p>
            <h2>Collections and maintenance</h2>
          </div>
          <div className="top-actions">
            <button className="primary" onClick={props.onCreateCollection}>
              <Plus size={16} />
              New collection
            </button>
            <button className="subtle" onClick={props.onOpenBackups}>
              <Archive size={16} />
              Backups
            </button>
          </div>
        </div>

        <div className="stats-grid">
          <article className="stat-card">
            <span>Collections</span>
            <strong>{props.collections.length}</strong>
            <small>{stats.base} base / {stats.auth} auth / {stats.system} system</small>
          </article>
          <article className="stat-card">
            <span>Records</span>
            <strong>{stats.records}</strong>
            <small>Aggregated from collection list endpoints</small>
          </article>
          <article className="stat-card">
            <span>Fields</span>
            <strong>{stats.fields}</strong>
            <small>Schema fields across all collections</small>
          </article>
          <article className="stat-card">
            <span>Backups</span>
            <strong>{props.backupCount ?? 0}</strong>
            <small>{props.health?.canBackup ? "Snapshot runtime data" : "Unavailable in current runtime"}</small>
          </article>
        </div>
      </section>

      <section className="dashboard-grid">
        <section className="surface">
          <div className="surface-toolbar compact-toolbar">
            <div>
              <p className="eyebrow">Collections</p>
              <h2>Recent collections</h2>
            </div>
          </div>
          <div className="overview-list">
            {recentCollections.length === 0 ? (
              <EmptyState icon={Database} title="No collections yet" />
            ) : (
              recentCollections.map((collection) => (
                <button
                  key={collection.id}
                  className="overview-row"
                  onClick={() => props.onOpenCollection(collection.name)}
                >
                  <span className="nav-icon">{collection.type === "auth" ? <Shield size={16} /> : <Database size={16} />}</span>
                  <span className="overview-row-copy">
                    <strong>{collection.name}</strong>
                    <small>
                      {collection.system ? "system" : collection.type}
                      {" · "}
                      {collection.fields?.length ?? 0} fields
                      {" · "}
                      {props.collectionCounts[collection.name] ?? 0} records
                    </small>
                  </span>
                  <ChevronRight size={16} />
                </button>
              ))
            )}
          </div>
        </section>

        <section className="surface">
          <div className="surface-toolbar compact-toolbar">
            <div>
              <p className="eyebrow">Selection</p>
              <h2>{props.selected?.name ?? "Current focus"}</h2>
            </div>
          </div>
          {props.selected ? (
            <div className="summary-grid">
              <div className="summary-row">
                <span>Type</span>
                <strong>{props.selected.system ? "system" : props.selected.type}</strong>
              </div>
              <div className="summary-row">
                <span>Fields</span>
                <strong>{props.selected.fields?.length ?? 0}</strong>
              </div>
              <div className="summary-row">
                <span>Records</span>
                <strong>{props.collectionCounts[props.selected.name] ?? 0}</strong>
              </div>
              <div className="summary-row">
                <span>Updated</span>
                <strong>{formatDate(props.selected.updated ?? "") || "n/a"}</strong>
              </div>
              <div className="stack-actions">
                <button className="primary" onClick={() => props.onOpenCollection(props.selected!.name)}>
                  <Database size={16} />
                  Open records
                </button>
                <button className="subtle" onClick={() => props.onOpenSchema(props.selected!.name)}>
                  <ListFilter size={16} />
                  Open schema
                </button>
              </div>
            </div>
          ) : (
            <EmptyState icon={Database} title="Select a collection" />
          )}
        </section>
      </section>
    </div>
  );
}

function SettingsView({ canBackup, children }: { canBackup: boolean; children: ReactNode }) {
  const items = [
    { key: "application", label: "Application", active: false, disabled: true },
    { key: "storage", label: "Storage", active: false, disabled: true },
    { key: "mail", label: "Mail", active: false, disabled: true },
    { key: "backups", label: "Backups", active: true, disabled: false }
  ];

  return (
    <div className="settings-layout">
      <aside className="surface settings-nav">
        <div className="surface-toolbar compact-toolbar">
          <div>
            <p className="eyebrow">Settings</p>
            <h2>Runtime</h2>
          </div>
        </div>
        <div className="settings-nav-list">
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              className={item.active ? "settings-nav-button active" : "settings-nav-button"}
              disabled={item.disabled}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="settings-note">
          <p>{canBackup ? "Backups are wired into the embedded runtime." : "Backup endpoints are currently unavailable."}</p>
          <p>Application, storage and mail settings are not exposed by the current Java runtime yet.</p>
        </div>
      </aside>

      <div className="settings-content">{children}</div>
    </div>
  );
}

type RecordsViewProps = {
  collection: CollectionSchema;
  records: RecordItem[];
  columns: string[];
  query: QueryState;
  recordPage: ListResponse<RecordItem> | null;
  loading: boolean;
  activeRecordId: string;
  selectedRecord: RecordItem | null;
  onQuery: (query: QueryState) => void;
  onApply: (query: QueryState) => void;
  onPageChange: (page: number) => void;
  onSelectRecord: (id: string) => void;
  onNew: () => void;
  onEdit: (record: RecordItem) => void;
  onDelete: (record: RecordItem) => void;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function RecordsView(props: RecordsViewProps) {
  const [draft, setDraft] = useState(props.query);

  useEffect(() => setDraft(props.query), [props.query]);

  function apply() {
    const next = { ...draft, page: 1 };
    props.onQuery(next);
    props.onApply(next);
  }

  return (
    <section className="records-layout">
      <div className="surface">
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
              Expand
              <input
                value={draft.expand}
                onChange={(event) => setDraft({ ...draft, expand: event.target.value })}
                placeholder="author,comments.author"
              />
            </label>
            <label>
              Fields
              <input
                value={draft.fields}
                onChange={(event) => setDraft({ ...draft, fields: event.target.value })}
                placeholder="id,title,expand.author.name"
              />
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
          <span>
            page {props.recordPage?.page ?? props.query.page} / {Math.max(props.recordPage?.totalPages ?? 1, 1)}
          </span>
          {props.query.expand && <span>expand: {props.query.expand}</span>}
          {props.query.fields && <span>fields: {props.query.fields}</span>}
          <span>{props.selectedRecord ? `Inspecting ${props.selectedRecord.id}` : "Pick a row for details"}</span>
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
                  <tr
                    key={record.id}
                    className={props.activeRecordId === record.id ? "selected-row" : ""}
                    onClick={() => props.onSelectRecord(record.id)}
                  >
                    {props.columns.map((column) => (
                      <td key={column}>
                        <RecordFieldValue
                          collection={props.collection}
                          column={column}
                          record={record}
                          onOpenFile={props.onOpenFile}
                        />
                      </td>
                    ))}
                    <td className="row-actions">
                      <button
                        className="icon-button"
                        onClick={(event) => {
                          event.stopPropagation();
                          props.onEdit(record);
                        }}
                        title="Edit"
                        aria-label="Edit"
                      >
                        <Edit3 size={16} />
                      </button>
                      <button
                        className="icon-button danger"
                        onClick={(event) => {
                          event.stopPropagation();
                          props.onDelete(record);
                        }}
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

        <div className="pagination-bar">
          <span>
            Showing {props.records.length} of {props.recordPage?.totalItems ?? props.records.length}
          </span>
          <div className="pagination-actions">
            <button
              className="subtle"
              type="button"
              disabled={(props.recordPage?.page ?? props.query.page) <= 1 || props.loading}
              onClick={() => props.onPageChange(Math.max(1, (props.recordPage?.page ?? props.query.page) - 1))}
            >
              Prev
            </button>
            <button
              className="subtle"
              type="button"
              disabled={
                (props.recordPage?.page ?? props.query.page) >= Math.max(props.recordPage?.totalPages ?? 1, 1) ||
                props.loading
              }
              onClick={() => props.onPageChange((props.recordPage?.page ?? props.query.page) + 1)}
            >
              Next
            </button>
          </div>
        </div>
      </div>

      <RecordInspector
        collection={props.collection}
        record={props.selectedRecord}
        onEdit={props.onEdit}
        onDelete={props.onDelete}
        onOpenFile={props.onOpenFile}
      />
    </section>
  );
}

type RecordInspectorProps = {
  collection: CollectionSchema;
  record: RecordItem | null;
  onEdit: (record: RecordItem) => void;
  onDelete: (record: RecordItem) => void;
  onOpenFile: (record: RecordItem, filename: string) => void;
};

function RecordInspector(props: RecordInspectorProps) {
  if (!props.record) {
    return (
      <aside className="surface inspector">
        <div className="surface-toolbar compact-toolbar">
          <div>
            <p className="eyebrow">Preview</p>
            <h2>Record details</h2>
          </div>
        </div>
        <EmptyState icon={Database} title="Select a record" />
      </aside>
    );
  }

  const record = props.record;
  const visibleFields = (props.collection.fields ?? []).filter((field) => {
    return field.type !== "password" && !field.hidden && record[field.name] !== undefined;
  });
  const expanded = isPlainObject(record.expand) ? record.expand : null;

  return (
    <aside className="surface inspector">
      <div className="surface-toolbar compact-toolbar">
        <div>
          <p className="eyebrow">Preview</p>
          <h2>{record.id}</h2>
        </div>
        <div className="top-actions">
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
        </div>
      </div>

      <div className="inspector-content">
        <div className="summary-grid">
          <div className="summary-row">
            <span>Collection</span>
            <strong>{record.collectionName ?? props.collection.name}</strong>
          </div>
          <div className="summary-row">
            <span>Created</span>
            <strong>{formatDate(String(record.created ?? "")) || "n/a"}</strong>
          </div>
          <div className="summary-row">
            <span>Updated</span>
            <strong>{formatDate(String(record.updated ?? "")) || "n/a"}</strong>
          </div>
        </div>

        <div className="inspector-section">
          <h3>Fields</h3>
          <div className="inspector-fields">
            {visibleFields.map((field) => (
              <div className="inspector-field" key={field.id || field.name}>
                <span>{field.name}</span>
                <div className="inspector-field-value">
                  <RecordFieldValue
                    collection={props.collection}
                    column={field.name}
                    record={record}
                    onOpenFile={props.onOpenFile}
                    mode="panel"
                  />
                </div>
              </div>
            ))}
          </div>
        </div>

        {expanded && Object.keys(expanded).length > 0 && (
          <div className="inspector-section">
            <h3>Expand</h3>
            <div className="expand-grid">
              {Object.entries(expanded).map(([key, value]) => (
                <div className="expand-card" key={key}>
                  <span>{key}</span>
                  <pre className="value-block">{JSON.stringify(value, null, 2)}</pre>
                </div>
              ))}
            </div>
          </div>
        )}

        <div className="inspector-section">
          <h3>JSON</h3>
          <pre className="json-panel inspector-json">{JSON.stringify(record, null, 2)}</pre>
        </div>
      </div>
    </aside>
  );
}

type RecordFieldValueProps = {
  collection: CollectionSchema;
  column: string;
  record: RecordItem;
  onOpenFile: (record: RecordItem, filename: string) => void;
  mode?: "table" | "panel";
};

function RecordFieldValue({ collection, column, record, onOpenFile, mode = "table" }: RecordFieldValueProps) {
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

  if ((field?.type === "relation" || field?.type === "select") && Array.isArray(value)) {
    return (
      <div className="token-list">
        {value.map((item, index) => (
          <span className="token-pill" key={`${column}-${index}`}>
            {String(item)}
          </span>
        ))}
      </div>
    );
  }

  if ((field?.type === "relation" || field?.type === "select") && typeof value === "string" && value) {
    return <span className="token-pill">{value}</span>;
  }

  if (mode === "panel" && (Array.isArray(value) || isPlainObject(value))) {
    return <pre className="value-block">{JSON.stringify(value, null, 2)}</pre>;
  }

  return <code>{formatValue(value)}</code>;
}

type SchemaViewProps = {
  collection: CollectionSchema;
  section: SchemaSection;
  authMethods: AuthMethodsResponse | null;
  authMethodsLoading: boolean;
  onSection: (section: SchemaSection) => void;
  onEdit: () => void;
  onDelete: () => void;
  onCopy: (value: string) => void;
};

function SchemaView({
  collection,
  section,
  authMethods,
  authMethodsLoading,
  onSection,
  onEdit,
  onDelete,
  onCopy
}: SchemaViewProps) {
  const json = JSON.stringify(collection, null, 2);
  const visibleFields = (collection.fields ?? []).filter((field) => !field.hidden).length;
  const relationFields = (collection.fields ?? []).filter((field) => field.type === "relation").length;
  const fileFields = (collection.fields ?? []).filter((field) => field.type === "file").length;

  return (
    <section className="schema-workspace">
      <aside className="surface schema-summary-card">
        <div className="surface-toolbar compact-toolbar">
          <div>
            <p className="eyebrow">Collection</p>
            <h2>{collection.name}</h2>
          </div>
        </div>

        <div className="summary-grid">
          <div className="summary-row">
            <span>ID</span>
            <code>{collection.id}</code>
          </div>
          <div className="summary-row">
            <span>Type</span>
            <strong>{collection.system ? "system" : collection.type}</strong>
          </div>
          <div className="summary-row">
            <span>Fields</span>
            <strong>{collection.fields?.length ?? 0}</strong>
          </div>
          <div className="summary-row">
            <span>Visible</span>
            <strong>{visibleFields}</strong>
          </div>
          <div className="summary-row">
            <span>Relation fields</span>
            <strong>{relationFields}</strong>
          </div>
          <div className="summary-row">
            <span>File fields</span>
            <strong>{fileFields}</strong>
          </div>
        </div>

        <div className="stack-actions">
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
      </aside>

      <section className="surface schema-main">
        <div className="surface-toolbar compact-toolbar">
          <div className="view-tabs nested-tabs" role="tablist" aria-label="Schema sections">
            <button className={section === "fields" ? "active" : ""} onClick={() => onSection("fields")}>
              <Database size={16} />
              Fields
            </button>
            <button className={section === "rules" ? "active" : ""} onClick={() => onSection("rules")}>
              <Shield size={16} />
              Rules
            </button>
            {collection.type === "auth" && (
              <button className={section === "auth" ? "active" : ""} onClick={() => onSection("auth")}>
                <KeyRound size={16} />
                Auth
              </button>
            )}
            <button className={section === "api" ? "active" : ""} onClick={() => onSection("api")}>
              <Copy size={16} />
              API
            </button>
            <button className={section === "json" ? "active" : ""} onClick={() => onSection("json")}>
              <Copy size={16} />
              JSON
            </button>
          </div>
        </div>

        {section === "fields" ? (
          <div className="field-card-list">
            {(collection.fields ?? []).map((field) => (
              <article className="field-card" key={field.id || field.name}>
                <div className="field-card-head">
                  <div>
                    <strong>{field.name}</strong>
                    <small>{field.type}</small>
                  </div>
                  <div className="chips">
                    {field.required && <span>required</span>}
                    {field.unique && <span>unique</span>}
                    {field.hidden && <span>hidden</span>}
                    {field.system && <span>system</span>}
                    {Boolean(field.protected) && <span>protected</span>}
                  </div>
                </div>
                <div className="field-meta-grid">
                  {fieldDetailRows(field).map((detail) => (
                    <div className="field-meta-row" key={`${field.name}-${detail.label}`}>
                      <span>{detail.label}</span>
                      <code>{detail.value}</code>
                    </div>
                  ))}
                </div>
              </article>
            ))}
          </div>
        ) : section === "rules" ? (
          <div className="rule-card-list">
            {RULE_KEYS.map((key) => {
              const value = collection[key];
              const state = ruleState(value);
              return (
                <article className={`rule-card ${state.className}`} key={key}>
                  <div className="rule-card-head">
                    <strong>{ruleLabel(key)}</strong>
                    <span>{state.label}</span>
                  </div>
                  <p>{state.description}</p>
                  {state.expression && <code>{state.expression}</code>}
                </article>
              );
            })}
          </div>
        ) : section === "auth" ? (
          <div className="auth-card-list">
            {authCards(collection, authMethods, authMethodsLoading).map((card) => (
              <article className="rule-card" key={card.title}>
                <div className="rule-card-head">
                  <strong>{card.title}</strong>
                  <span>{card.label}</span>
                </div>
                <p>{card.description}</p>
                {card.code && <code>{card.code}</code>}
              </article>
            ))}
          </div>
        ) : section === "api" ? (
          <div className="api-card-list">
            {collectionApiCards(collection).map((card) => (
              <article className="api-card" key={card.title}>
                <div className="rule-card-head">
                  <strong>{card.title}</strong>
                  <span>{card.label}</span>
                </div>
                <p>{card.description}</p>
                <div className="endpoint-list">
                  {card.endpoints.map((endpoint) => (
                    <div className="endpoint-row" key={`${card.title}-${endpoint.method}-${endpoint.path}`}>
                      <span className={`method method-${endpoint.method.toLowerCase()}`}>{endpoint.method}</span>
                      <code>{endpoint.path}</code>
                    </div>
                  ))}
                </div>
                {card.hint && <small className="api-hint">{card.hint}</small>}
              </article>
            ))}
          </div>
        ) : (
          <pre className="json-panel">{json}</pre>
        )}
      </section>
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

type CollectionModalProps = {
  state: CollectionEditorState;
  collections: CollectionSchema[];
  onClose: () => void;
  onSubmit: (payload: CollectionPayload) => void;
};

function CollectionModal({ state, collections, onClose, onSubmit }: CollectionModalProps) {
  const collection = state.collection;
  const [name, setName] = useState(collection?.name ?? "");
  const [type, setType] = useState(collection?.type ?? "base");
  const [fields, setFields] = useState<FieldDraft[]>(() => initialFieldDrafts(collection));
  const [rules, setRules] = useState<RuleEditorSet>(() => ({
    listRule: ruleEditorFromValue(collection?.listRule),
    viewRule: ruleEditorFromValue(collection?.viewRule),
    createRule: ruleEditorFromValue(collection?.createRule),
    updateRule: ruleEditorFromValue(collection?.updateRule),
    deleteRule: ruleEditorFromValue(collection?.deleteRule)
  }));
  const [error, setError] = useState("");

  function updateField(clientKey: string, patch: Partial<FieldDraft>) {
    setFields((current) => current.map((field) => (field.clientKey === clientKey ? { ...field, ...patch } : field)));
  }

  function removeField(clientKey: string) {
    setFields((current) => current.filter((field) => field.clientKey !== clientKey));
  }

  function addField() {
    setFields((current) => [...current, newFieldDraft(current.length + 1)]);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const payloadFields = fields.map(fieldDraftToSchema);
      validateCollectionDraft(name, payloadFields, rules);
      onSubmit({
        name: name.trim(),
        type,
        fields: payloadFields,
        listRule: ruleEditorToValue(rules.listRule),
        viewRule: ruleEditorToValue(rules.viewRule),
        createRule: ruleEditorToValue(rules.createRule),
        updateRule: ruleEditorToValue(rules.updateRule),
        deleteRule: ruleEditorToValue(rules.deleteRule)
      });
    } catch (err) {
      setError(errorMessage(err));
    }
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
              <option value="view">view</option>
            </select>
          </label>
        </div>

        <div className="editor-section">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Fields</p>
              <h3>Schema editor</h3>
            </div>
            <button type="button" className="subtle" onClick={addField}>
              <Plus size={16} />
              Add field
            </button>
          </div>
          <div className="field-editor-list">
            {fields.map((field, index) => (
              <article className="field-editor-card" key={field.clientKey}>
                <div className="field-editor-head">
                  <div>
                    <strong>{field.name || `field_${index + 1}`}</strong>
                    <small>{field.type}</small>
                  </div>
                  <button
                    type="button"
                    className="icon-button danger"
                    onClick={() => removeField(field.clientKey)}
                    disabled={field.system}
                    title="Remove field"
                    aria-label="Remove field"
                  >
                    <Trash2 size={16} />
                  </button>
                </div>

                <div className="field-editor-grid">
                  <label>
                    Name
                    <input
                      value={field.name}
                      onChange={(event) => updateField(field.clientKey, { name: event.target.value })}
                      disabled={field.system}
                    />
                  </label>
                  <label>
                    Type
                    <select
                      value={field.type}
                      onChange={(event) => updateField(field.clientKey, { type: event.target.value })}
                      disabled={field.system}
                    >
                      {FIELD_TYPE_OPTIONS.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>

                <div className="toggle-grid">
                  <ToggleField
                    label="Required"
                    checked={field.required}
                    onChange={(checked) => updateField(field.clientKey, { required: checked })}
                  />
                  <ToggleField
                    label="Unique"
                    checked={field.unique}
                    onChange={(checked) => updateField(field.clientKey, { unique: checked })}
                  />
                  <ToggleField
                    label="Hidden"
                    checked={field.hidden}
                    onChange={(checked) => updateField(field.clientKey, { hidden: checked })}
                  />
                </div>

                {field.type === "select" && (
                  <div className="field-editor-grid">
                    <label>
                      Values
                      <input
                        value={field.values}
                        onChange={(event) => updateField(field.clientKey, { values: event.target.value })}
                        placeholder="draft,published,archived"
                      />
                    </label>
                    <label>
                      Max select
                      <input
                        value={field.maxSelect}
                        onChange={(event) => updateField(field.clientKey, { maxSelect: event.target.value })}
                        placeholder="1"
                      />
                    </label>
                  </div>
                )}

                {field.type === "relation" && (
                  <div className="field-editor-grid">
                    <label>
                      Target collection
                      <select
                        value={field.collectionId}
                        onChange={(event) => updateField(field.clientKey, { collectionId: event.target.value })}
                      >
                        <option value="">Select collection</option>
                        {collections.map((target) => (
                          <option key={target.id} value={target.id}>
                            {target.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      Max select
                      <input
                        value={field.maxSelect}
                        onChange={(event) => updateField(field.clientKey, { maxSelect: event.target.value })}
                        placeholder="1"
                      />
                    </label>
                  </div>
                )}

                {field.type === "file" && (
                  <>
                    <div className="field-editor-grid">
                      <label>
                        Max files
                        <input
                          value={field.maxSelect}
                          onChange={(event) => updateField(field.clientKey, { maxSelect: event.target.value })}
                          placeholder="1"
                        />
                      </label>
                      <label>
                        Max size (bytes)
                        <input
                          value={field.maxSize}
                          onChange={(event) => updateField(field.clientKey, { maxSize: event.target.value })}
                          placeholder="10485760"
                        />
                      </label>
                    </div>
                    <div className="field-editor-grid">
                      <label>
                        MIME types
                        <input
                          value={field.mimeTypes}
                          onChange={(event) => updateField(field.clientKey, { mimeTypes: event.target.value })}
                          placeholder="image/*,application/pdf"
                        />
                      </label>
                      <label>
                        Thumbs
                        <input
                          value={field.thumbs}
                          onChange={(event) => updateField(field.clientKey, { thumbs: event.target.value })}
                          placeholder="100x100,300x0"
                        />
                      </label>
                    </div>
                    <div className="toggle-grid">
                      <ToggleField
                        label="Protected file"
                        checked={field.protectedFile}
                        onChange={(checked) => updateField(field.clientKey, { protectedFile: checked })}
                      />
                    </div>
                  </>
                )}
              </article>
            ))}
          </div>
        </div>

        <div className="editor-section">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Access rules</p>
              <h3>PocketBase-style API permissions</h3>
            </div>
          </div>
          <div className="rule-editor-grid">
            {RULE_KEYS.map((key) => (
              <article className="rule-editor-card" key={key}>
                <label>
                  {ruleLabel(key)}
                  <select
                    value={rules[key].mode}
                    onChange={(event) =>
                      setRules((current) => ({
                        ...current,
                        [key]: { ...current[key], mode: event.target.value as RuleMode }
                      }))
                    }
                  >
                    <option value="locked">Admin only</option>
                    <option value="public">Public</option>
                    <option value="custom">Custom rule</option>
                  </select>
                </label>
                {rules[key].mode === "custom" && (
                  <label>
                    Expression
                    <textarea
                      value={rules[key].value}
                      onChange={(event) =>
                        setRules((current) => ({
                          ...current,
                          [key]: { ...current[key], value: event.target.value }
                        }))
                      }
                      spellCheck={false}
                    />
                  </label>
                )}
              </article>
            ))}
          </div>
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

type ToggleFieldProps = {
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
};

function ToggleField({ label, checked, onChange }: ToggleFieldProps) {
  return (
    <label className="toggle-field">
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
      <span>{label}</span>
    </label>
  );
}

type RecordModalProps = {
  collection: CollectionSchema;
  collections: CollectionSchema[];
  token: string;
  state: RecordEditorState;
  onClose: () => void;
  onSubmit: (payload: Record<string, unknown>, files: Record<string, File[]>) => void;
};

function RecordModal({ collection, collections, token, state, onClose, onSubmit }: RecordModalProps) {
  const editableFields = useMemo(() => editableRecordFields(collection), [collection]);
  const initialPayload = useMemo(() => recordEditorPayload(collection, state.record), [collection, state.record]);
  const [tab, setTab] = useState<"fields" | "json">("fields");
  const [formValues, setFormValues] = useState<Record<string, unknown>>(initialPayload);
  const [jsonFields, setJsonFields] = useState<Record<string, string>>(() => initialJsonFieldInputs(editableFields, initialPayload));
  const [files, setFiles] = useState<Record<string, File[]>>({});
  const [fileStrategies, setFileStrategies] = useState<Record<string, FileStrategy>>(() =>
    Object.fromEntries(
      editableFields
        .filter((field) => field.type === "file")
        .map((field) => [field.name, defaultFileStrategy(field, Boolean(state.record))])
    )
  );
  const [fileRemovals, setFileRemovals] = useState<Record<string, string[]>>({});
  const [relationOptions, setRelationOptions] = useState<Record<string, RecordItem[]>>({});
  const [error, setError] = useState("");

  useEffect(() => {
    let cancelled = false;
    const relationFields = editableFields.filter((field) => field.type === "relation");
    if (!token || relationFields.length === 0) {
      setRelationOptions({});
      return;
    }

    Promise.all(
      relationFields.map(async (field) => {
        const target = relationTargetCollection(field, collections);
        if (!target) return [field.name, []] as const;
        try {
          const data = await apiRequest<ListResponse<RecordItem>>(
            `/api/collections/${encodeURIComponent(target.name)}/records?perPage=50&sort=-updated`,
            token
          );
          return [field.name, data.items] as const;
        } catch {
          return [field.name, []] as const;
        }
      })
    ).then((entries) => {
      if (cancelled) return;
      setRelationOptions(Object.fromEntries(entries));
    });

    return () => {
      cancelled = true;
    };
  }, [collections, editableFields, token]);

  const payloadPreview = useMemo(() => {
    try {
      return JSON.stringify(
        buildRecordPayload(collection, editableFields, formValues, jsonFields, state.record),
        null,
        2
      );
    } catch (previewError) {
      return errorMessage(previewError);
    }
  }, [collection, editableFields, formValues, jsonFields, state.record]);

  function setValue(name: string, value: unknown) {
    setFormValues((current) => ({ ...current, [name]: value }));
  }

  function toggleFileRemoval(fieldName: string, filename: string) {
    setFileRemovals((current) => {
      const currentValues = current[fieldName] ?? [];
      const nextValues = currentValues.includes(filename)
        ? currentValues.filter((item) => item !== filename)
        : [...currentValues, filename];
      return { ...current, [fieldName]: nextValues };
    });
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      const payload = buildRecordPayload(collection, editableFields, formValues, jsonFields, state.record);
      editableFields
        .filter((field) => field.type === "file")
        .forEach((field) => {
          const removals = fileRemovals[field.name] ?? [];
          if (removals.length > 0) {
            payload[`${field.name}-`] = removals;
          }
        });

      const uploadMap: Record<string, File[]> = {};
      Object.entries(files).forEach(([fieldName, fieldFiles]) => {
        if (fieldFiles.length === 0) return;
        const field = editableFields.find((item) => item.name === fieldName);
        const strategy = fileStrategies[fieldName] ?? (field ? defaultFileStrategy(field, Boolean(state.record)) : "replace");
        uploadMap[strategy === "append" ? `${fieldName}+` : fieldName] = fieldFiles;
      });
      onSubmit(payload, uploadMap);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Modal title={state.record ? `Edit ${state.record.id}` : `New ${collection.name}`} onClose={onClose} wide>
      <form className="modal-grid" onSubmit={submit}>
        <div className="record-modal-layout">
          <section className="editor-section">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Record</p>
                <h3>{state.record ? "Edit fields" : "Create a record"}</h3>
              </div>
              <div className="view-tabs nested-tabs" role="tablist" aria-label="Record editor tabs">
                <button className={tab === "fields" ? "active" : ""} type="button" onClick={() => setTab("fields")}>
                  <Edit3 size={16} />
                  Fields
                </button>
                <button className={tab === "json" ? "active" : ""} type="button" onClick={() => setTab("json")}>
                  <Copy size={16} />
                  Payload
                </button>
              </div>
            </div>

            {tab === "fields" ? (
              <div className="record-form-list">
                {editableFields.map((field) => {
                  const value = formValues[field.name];
                  const multiple = fieldSupportsMultiple(field);
                  const selectValues = fieldSelectValues(field);
                  const existingFiles = fileListValue(state.record?.[field.name]);
                  const relationRecords = relationOptions[field.name] ?? [];

                  if (field.type === "bool") {
                    return (
                      <article className="record-field-card" key={field.name}>
                        <div className="field-editor-head">
                          <div>
                            <strong>{field.name}</strong>
                            <small>{field.type}</small>
                          </div>
                          <div className="chips">
                            {field.required && <span>required</span>}
                            {field.unique && <span>unique</span>}
                          </div>
                        </div>
                        <ToggleField
                          label={Boolean(value) ? "Enabled" : "Disabled"}
                          checked={Boolean(value)}
                          onChange={(checked) => setValue(field.name, checked)}
                        />
                      </article>
                    );
                  }

                  if (field.type === "select") {
                    return (
                      <article className="record-field-card" key={field.name}>
                        <div className="field-editor-head">
                          <div>
                            <strong>{field.name}</strong>
                            <small>{field.type}</small>
                          </div>
                          <div className="chips">
                            {field.required && <span>required</span>}
                            {multiple && <span>multiple</span>}
                          </div>
                        </div>
                        <label>
                          Value
                          <select
                            multiple={multiple}
                            value={
                              multiple
                                ? ensureStringArray(value)
                                : typeof value === "string"
                                  ? value
                                  : ""
                            }
                            onChange={(event) =>
                              setValue(
                                field.name,
                                multiple
                                  ? Array.from(event.target.selectedOptions).map((option) => option.value)
                                  : event.target.value
                              )
                            }
                          >
                            {!multiple && <option value="">Select value</option>}
                            {selectValues.map((option) => (
                              <option key={option} value={option}>
                                {option}
                              </option>
                            ))}
                          </select>
                        </label>
                      </article>
                    );
                  }

                  if (field.type === "relation") {
                    const target = relationTargetCollection(field, collections);
                    return (
                      <article className="record-field-card" key={field.name}>
                        <div className="field-editor-head">
                          <div>
                            <strong>{field.name}</strong>
                            <small>{field.type}</small>
                          </div>
                          <div className="chips">
                            {target && <span>{target.name}</span>}
                            {multiple && <span>multiple</span>}
                          </div>
                        </div>
                        {relationRecords.length > 0 ? (
                          <label>
                            Related record
                            <select
                              multiple={multiple}
                              value={
                                multiple
                                  ? ensureStringArray(value)
                                  : typeof value === "string"
                                    ? value
                                    : ""
                              }
                              onChange={(event) =>
                                setValue(
                                  field.name,
                                  multiple
                                    ? Array.from(event.target.selectedOptions).map((option) => option.value)
                                    : event.target.value
                                )
                              }
                            >
                              {!multiple && <option value="">Select record</option>}
                              {relationRecords.map((record) => (
                                <option key={record.id} value={record.id}>
                                  {recordOptionLabel(record)}
                                </option>
                              ))}
                            </select>
                          </label>
                        ) : (
                          <label>
                            Record ids
                            <input
                              value={
                                multiple
                                  ? ensureStringArray(value).join(",")
                                  : typeof value === "string"
                                    ? value
                                    : ""
                              }
                              onChange={(event) =>
                                setValue(
                                  field.name,
                                  multiple ? splitCsv(event.target.value) : event.target.value
                                )
                              }
                              placeholder={multiple ? "id1,id2" : "record id"}
                            />
                          </label>
                        )}
                      </article>
                    );
                  }

                  if (field.type === "json") {
                    return (
                      <article className="record-field-card" key={field.name}>
                        <div className="field-editor-head">
                          <div>
                            <strong>{field.name}</strong>
                            <small>{field.type}</small>
                          </div>
                          <div className="chips">
                            {field.required && <span>required</span>}
                          </div>
                        </div>
                        <label>
                          JSON
                          <textarea
                            value={jsonFields[field.name] ?? ""}
                            onChange={(event) =>
                              setJsonFields((current) => ({ ...current, [field.name]: event.target.value }))
                            }
                            spellCheck={false}
                          />
                        </label>
                      </article>
                    );
                  }

                  if (field.type === "file") {
                    return (
                      <article className="record-field-card" key={field.name}>
                        <div className="field-editor-head">
                          <div>
                            <strong>{field.name}</strong>
                            <small>{field.type}</small>
                          </div>
                          <div className="chips">
                            {multiple && <span>multiple</span>}
                            {Boolean(field.protected) && <span>protected</span>}
                          </div>
                        </div>

                        {existingFiles.length > 0 && (
                          <div className="record-existing-files">
                            {existingFiles.map((filename) => {
                              const removed = (fileRemovals[field.name] ?? []).includes(filename);
                              return (
                                <label className={removed ? "file-choice removed" : "file-choice"} key={filename}>
                                  <input
                                    type="checkbox"
                                    checked={!removed}
                                    onChange={() => toggleFileRemoval(field.name, filename)}
                                  />
                                  <span>{filename}</span>
                                </label>
                              );
                            })}
                          </div>
                        )}

                        {state.record && multiple && existingFiles.length > 0 && (
                          <label>
                            Upload mode
                            <select
                              value={fileStrategies[field.name] ?? defaultFileStrategy(field, true)}
                              onChange={(event) =>
                                setFileStrategies((current) => ({
                                  ...current,
                                  [field.name]: event.target.value as FileStrategy
                                }))
                              }
                            >
                              <option value="append">Append to existing</option>
                              <option value="replace">Replace existing</option>
                            </select>
                          </label>
                        )}

                        <label>
                          Upload
                          <input
                            type="file"
                            multiple={multiple}
                            accept={fieldMimeTypes(field).join(",")}
                            onChange={(event) =>
                              setFiles((current) => ({ ...current, [field.name]: Array.from(event.target.files ?? []) }))
                            }
                          />
                        </label>

                        {(files[field.name] ?? []).length > 0 && (
                          <div className="record-upload-list">
                            {(files[field.name] ?? []).map((file) => (
                              <span key={`${field.name}-${file.name}`}>{file.name}</span>
                            ))}
                          </div>
                        )}
                      </article>
                    );
                  }

                  return (
                    <article className="record-field-card" key={field.name}>
                      <div className="field-editor-head">
                        <div>
                          <strong>{field.name}</strong>
                          <small>{field.type}</small>
                        </div>
                        <div className="chips">
                          {field.required && <span>required</span>}
                          {field.unique && <span>unique</span>}
                        </div>
                      </div>
                      <label>
                        Value
                        <input
                          type={field.type === "email" ? "email" : field.type === "password" ? "password" : field.type === "number" ? "number" : "text"}
                          value={typeof value === "string" || typeof value === "number" ? String(value) : ""}
                          onChange={(event) => setValue(field.name, event.target.value)}
                          placeholder={field.type === "password" && state.record ? "Leave blank to keep current password" : ""}
                        />
                      </label>
                    </article>
                  );
                })}
              </div>
            ) : (
              <div className="payload-preview-stack">
                <div className="summary-grid">
                  <div className="summary-row">
                    <span>Collection</span>
                    <strong>{collection.name}</strong>
                  </div>
                  <div className="summary-row">
                    <span>Fields</span>
                    <strong>{editableFields.length}</strong>
                  </div>
                  <div className="summary-row">
                    <span>Pending uploads</span>
                    <strong>{Object.values(files).reduce((sum, list) => sum + list.length, 0)}</strong>
                  </div>
                  <div className="summary-row">
                    <span>Pending removals</span>
                    <strong>{Object.values(fileRemovals).reduce((sum, list) => sum + list.length, 0)}</strong>
                  </div>
                </div>
                <pre className="json-panel preview-json">{payloadPreview}</pre>
              </div>
            )}
          </section>

          <aside className="surface record-modal-side">
            <div className="surface-toolbar compact-toolbar">
              <div>
                <p className="eyebrow">Summary</p>
                <h2>{state.record ? state.record.id : "New record"}</h2>
              </div>
            </div>
            <div className="summary-grid">
              <div className="summary-row">
                <span>Collection</span>
                <strong>{collection.name}</strong>
              </div>
              <div className="summary-row">
                <span>Mode</span>
                <strong>{state.record ? "Update" : "Create"}</strong>
              </div>
              <div className="summary-row">
                <span>Created</span>
                <strong>{state.record?.created ? formatDate(String(state.record.created)) : "new"}</strong>
              </div>
              <div className="summary-row">
                <span>Updated</span>
                <strong>{state.record?.updated ? formatDate(String(state.record.updated)) : "pending"}</strong>
              </div>
            </div>
          </aside>
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

function editableRecordFields(collection: CollectionSchema) {
  return (collection.fields ?? []).filter((field) => !field.hidden && !field.system);
}

function defaultValue(field: FieldSchema) {
  if (field.type === "bool") return false;
  if (field.type === "number") return 0;
  if (field.type === "json") return null;
  if (field.type === "relation") return maxFiles(field) > 1 ? [] : "";
  return "";
}

function initialJsonFieldInputs(fields: FieldSchema[], payload: Record<string, unknown>) {
  return Object.fromEntries(
    fields
      .filter((field) => field.type === "json")
      .map((field) => [field.name, payload[field.name] == null ? "" : JSON.stringify(payload[field.name], null, 2)])
  );
}

function buildRecordPayload(
  collection: CollectionSchema,
  fields: FieldSchema[],
  values: Record<string, unknown>,
  jsonFields: Record<string, string>,
  existing?: RecordItem
) {
  const payload: Record<string, unknown> = {};

  fields.forEach((field) => {
    const rawValue = values[field.name];
    if (field.type === "password") {
      const password = String(rawValue ?? "");
      if (existing && !password.trim()) {
        return;
      }
      payload[field.name] = password;
      return;
    }

    if (field.type === "json") {
      const source = (jsonFields[field.name] ?? "").trim();
      payload[field.name] = source ? JSON.parse(source) : null;
      return;
    }

    if (field.type === "bool") {
      payload[field.name] = Boolean(rawValue);
      return;
    }

    if (field.type === "relation" || field.type === "select") {
      payload[field.name] = fieldSupportsMultiple(field)
        ? ensureStringArray(rawValue)
        : typeof rawValue === "string"
          ? rawValue
          : "";
      return;
    }

    if (field.type === "file") {
      return;
    }

    payload[field.name] =
      typeof rawValue === "string" || typeof rawValue === "number" ? String(rawValue) : rawValue ?? "";
  });

  validateRecordPayload(collection, fields, payload, existing);
  return payload;
}

function validateRecordPayload(
  collection: CollectionSchema,
  fields: FieldSchema[],
  payload: Record<string, unknown>,
  existing?: RecordItem
) {
  fields.forEach((field) => {
    if (!field.required) return;
    if (field.type === "file") return;
    if (field.type === "password" && existing && !String(payload[field.name] ?? "").trim()) {
      return;
    }
    const value = payload[field.name];
    if (isEmptyRecordValue(value)) {
      throw new Error(`${collection.name}.${field.name} is required.`);
    }
  });
}

function isEmptyRecordValue(value: unknown) {
  if (value == null) return true;
  if (typeof value === "string") return value.trim() === "";
  if (Array.isArray(value)) return value.length === 0;
  return false;
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

function fieldSupportsMultiple(field: FieldSchema) {
  return maxFiles(field) > 1;
}

function fieldSelectValues(field: FieldSchema) {
  return asArray(field.options?.values);
}

function relationTargetCollection(field: FieldSchema, collections: CollectionSchema[]) {
  const candidates = [
    field.collectionId,
    asText(field.options?.collectionId),
    asText(field.options?.collection),
    asText(field.options?.collectionName),
    ...asArray(field.collectionIds),
    ...asArray(field.options?.collectionIds)
  ].filter(Boolean) as string[];
  return collections.find((collection) => candidates.includes(collection.id) || candidates.includes(collection.name)) ?? null;
}

function fieldMimeTypes(field: FieldSchema) {
  return field.mimeTypes ?? asArray(field.options?.mimeTypes);
}

function ensureStringArray(value: unknown) {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).filter(Boolean);
  }
  if (typeof value === "string" && value.trim()) {
    return [value.trim()];
  }
  return [];
}

function fileListValue(value: unknown) {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).filter(Boolean);
  }
  if (typeof value === "string" && value.trim()) {
    return [value.trim()];
  }
  return [];
}

function defaultFileStrategy(field: FieldSchema, editing: boolean): FileStrategy {
  if (editing && fieldSupportsMultiple(field)) {
    return "append";
  }
  return "replace";
}

function recordOptionLabel(record: RecordItem) {
  const candidate = record.title ?? record.name ?? record.email ?? record.label ?? record.username;
  return candidate ? `${String(candidate)} · ${record.id}` : record.id;
}

function groupCollections(collections: CollectionSchema[]) {
  const groups = [
    { key: "system", label: "System", items: [] as CollectionSchema[] },
    { key: "auth", label: "Auth", items: [] as CollectionSchema[] },
    { key: "base", label: "Base", items: [] as CollectionSchema[] },
    { key: "view", label: "View", items: [] as CollectionSchema[] },
    { key: "other", label: "Other", items: [] as CollectionSchema[] }
  ];

  collections.forEach((collection) => {
    if (collection.system) {
      groups[0].items.push(collection);
      return;
    }
    if (collection.type === "auth") {
      groups[1].items.push(collection);
      return;
    }
    if (collection.type === "base") {
      groups[2].items.push(collection);
      return;
    }
    if (collection.type === "view") {
      groups[3].items.push(collection);
      return;
    }
    groups[4].items.push(collection);
  });

  return groups.filter((group) => group.items.length > 0);
}

function initialFieldDrafts(collection?: CollectionSchema) {
  const source = collection?.fields?.length ? collection.fields : DEFAULT_FIELDS;
  return source.map(fieldDraftFromSchema);
}

function fieldDraftFromSchema(field: Partial<FieldSchema>, index = 0): FieldDraft {
  const rawOptions = isPlainObject(field.options) ? { ...field.options } : {};
  return {
    clientKey: field.id ?? makeDraftKey(index),
    id: field.id,
    name: field.name ?? "",
    type: field.type ?? "text",
    required: Boolean(field.required),
    unique: Boolean(field.unique),
    hidden: Boolean(field.hidden),
    system: Boolean(field.system),
    presentable: Boolean(field.presentable),
    collectionId:
      firstDefinedText(
        field.collectionId,
        asText(rawOptions.collectionId),
        asText(rawOptions.collection),
        asArray(rawOptions.collectionIds)[0]
      ) ?? "",
    maxSelect: stringNumber(
      field.maxSelect ?? field.maxFiles ?? asNumber(rawOptions.maxSelect) ?? asNumber(rawOptions.maxFiles)
    ),
    maxSize: stringNumber(field.maxSize ?? asNumber(rawOptions.maxSize)),
    mimeTypes: joinCsv(field.mimeTypes ?? asArray(rawOptions.mimeTypes)),
    thumbs: joinCsv(field.thumbs ?? asArray(rawOptions.thumbs)),
    values: joinCsv(asArray(rawOptions.values)),
    protectedFile: Boolean(field.protected ?? rawOptions.protected),
    rawOptions
  };
}

function newFieldDraft(index: number): FieldDraft {
  return {
    clientKey: makeDraftKey(index),
    name: "",
    type: "text",
    required: false,
    unique: false,
    hidden: false,
    system: false,
    presentable: false,
    collectionId: "",
    maxSelect: "",
    maxSize: "",
    mimeTypes: "",
    thumbs: "",
    values: "",
    protectedFile: false,
    rawOptions: {}
  };
}

function fieldDraftToSchema(field: FieldDraft): FieldSchema {
  const options = { ...field.rawOptions };
  delete options.collectionId;
  delete options.collectionIds;
  delete options.collection;
  delete options.collectionName;
  delete options.maxSelect;
  delete options.maxFiles;
  delete options.maxSize;
  delete options.mimeTypes;
  delete options.thumbs;
  delete options.values;
  delete options.protected;

  const next: FieldSchema = {
    id: field.id,
    name: field.name.trim(),
    type: field.type,
    required: field.required,
    unique: field.unique,
    hidden: field.hidden,
    system: field.system,
    presentable: field.presentable,
    options
  };

  const maxSelect = parsePositiveNumber(field.maxSelect);
  const maxSize = parsePositiveNumber(field.maxSize);
  const values = splitCsv(field.values);
  const mimeTypes = splitCsv(field.mimeTypes);
  const thumbs = splitCsv(field.thumbs);

  if (field.type === "select" && values.length > 0) {
    next.options = { ...next.options, values };
  }

  if (field.type === "relation") {
    if (field.collectionId.trim()) {
      next.collectionId = field.collectionId.trim();
      next.options = { ...next.options, collectionId: next.collectionId };
    }
    if (maxSelect) {
      next.maxSelect = maxSelect;
      next.options = { ...next.options, maxSelect };
    }
  }

  if (field.type === "select" && maxSelect) {
    next.maxSelect = maxSelect;
    next.options = { ...next.options, maxSelect };
  }

  if (field.type === "file") {
    if (maxSelect) {
      next.maxFiles = maxSelect;
      next.options = { ...next.options, maxFiles: maxSelect };
    }
    if (maxSize) {
      next.maxSize = maxSize;
      next.options = { ...next.options, maxSize };
    }
    if (mimeTypes.length > 0) {
      next.mimeTypes = mimeTypes;
      next.options = { ...next.options, mimeTypes };
    }
    if (thumbs.length > 0) {
      next.thumbs = thumbs;
      next.options = { ...next.options, thumbs };
    }
    if (field.protectedFile) {
      next.protected = true;
      next.options = { ...next.options, protected: true };
    }
  }

  if (!next.options || Object.keys(next.options).length === 0) {
    next.options = {};
  }

  return next;
}

function validateCollectionDraft(name: string, fields: FieldSchema[], rules: RuleEditorSet) {
  if (!name.trim()) {
    throw new Error("Collection name is required.");
  }
  if (!/^[A-Za-z_][A-Za-z0-9_]{0,62}$/.test(name.trim())) {
    throw new Error("Collection name must use letters, numbers and underscore.");
  }

  const names = new Set<string>();
  for (const field of fields) {
    if (!field.name.trim()) {
      throw new Error("Each field needs a name.");
    }
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,62}$/.test(field.name.trim())) {
      throw new Error(`Field "${field.name}" must use letters, numbers and underscore.`);
    }
    if (names.has(field.name.trim())) {
      throw new Error(`Duplicate field name "${field.name}".`);
    }
    names.add(field.name.trim());
  }

  for (const key of RULE_KEYS) {
    const rule = rules[key];
    if (rule.mode === "custom" && !rule.value.trim()) {
      throw new Error(`${ruleLabel(key)} custom rule cannot be empty.`);
    }
  }
}

function ruleEditorFromValue(value: string | null | undefined): RuleEditorState {
  if (value === null || value === undefined) {
    return { mode: "locked", value: "" };
  }
  if (!value.trim()) {
    return { mode: "public", value: "" };
  }
  return { mode: "custom", value };
}

function ruleEditorToValue(rule: RuleEditorState) {
  if (rule.mode === "locked") return null;
  if (rule.mode === "public") return "";
  return rule.value.trim();
}

function ruleLabel(key: RuleKey) {
  switch (key) {
    case "listRule":
      return "List rule";
    case "viewRule":
      return "View rule";
    case "createRule":
      return "Create rule";
    case "updateRule":
      return "Update rule";
    case "deleteRule":
      return "Delete rule";
  }
}

function ruleState(value: string | null | undefined) {
  if (value === null || value === undefined) {
    return {
      label: "Admin only",
      description: "Only superusers can use this API operation.",
      expression: "",
      className: "locked"
    };
  }
  if (!value.trim()) {
    return {
      label: "Public",
      description: "The operation is open to public requests and authenticated users.",
      expression: "",
      className: "public"
    };
  }
  return {
    label: "Custom",
    description: "The operation is filtered through an expression rule.",
    expression: value,
    className: "custom"
  };
}

function fieldDetailRows(field: FieldSchema) {
  const rows: Array<{ label: string; value: string }> = [];
  if (field.type === "relation") {
    rows.push({
      label: "Target",
      value:
        field.collectionId ??
        asText(field.options?.collectionId) ??
        asText(field.options?.collection) ??
        asArray(field.options?.collectionIds).join(", ") ??
        "n/a"
    });
  }
  if (field.type === "select") {
    const values = asArray(field.options?.values);
    if (values.length > 0) {
      rows.push({ label: "Values", value: values.join(", ") });
    }
  }
  if (field.type === "file") {
    const mimeTypes = field.mimeTypes ?? asArray(field.options?.mimeTypes);
    const thumbs = field.thumbs ?? asArray(field.options?.thumbs);
    rows.push({ label: "Max files", value: String(field.maxFiles ?? field.maxSelect ?? asNumber(field.options?.maxFiles) ?? 1) });
    if (field.maxSize ?? asNumber(field.options?.maxSize)) {
      rows.push({ label: "Max size", value: formatBytes(Number(field.maxSize ?? asNumber(field.options?.maxSize) ?? 0)) });
    }
    if (mimeTypes.length > 0) {
      rows.push({ label: "MIME", value: mimeTypes.join(", ") });
    }
    if (thumbs.length > 0) {
      rows.push({ label: "Thumbs", value: thumbs.join(", ") });
    }
  }
  if (rows.length === 0) {
    rows.push({ label: "Config", value: "default" });
  }
  return rows;
}

function collectionApiCards(collection: CollectionSchema) {
  const relationNames = (collection.fields ?? [])
    .filter((field) => field.type === "relation")
    .map((field) => field.name);
  const selectFields = recordColumns(collection).slice(0, 4).join(",");
  const expandHint = relationNames.slice(0, 2).join(",");
  const cards = [
    {
      title: "Records",
      label: "CRUD",
      description: "Standard record listing, creation, view, update and deletion endpoints.",
      endpoints: [
        { method: "GET", path: `/api/collections/${collection.name}/records` },
        { method: "POST", path: `/api/collections/${collection.name}/records` },
        { method: "GET", path: `/api/collections/${collection.name}/records/:id` },
        { method: "PATCH", path: `/api/collections/${collection.name}/records/:id` },
        { method: "DELETE", path: `/api/collections/${collection.name}/records/:id` }
      ],
      hint: expandHint
        ? `Useful query options: expand=${expandHint} and fields=${selectFields}`
        : `Useful query options: sort=-created and fields=${selectFields}`
    },
    {
      title: "Collection",
      label: "Admin",
      description: "Superuser collection metadata endpoints used by the schema workbench.",
      endpoints: [
        { method: "GET", path: `/api/collections/${collection.name}` },
        { method: "PATCH", path: `/api/collections/${collection.name}` },
        { method: "DELETE", path: `/api/collections/${collection.name}` }
      ],
      hint: "Collection list endpoints also support filter, sort and fields."
    },
    {
      title: "Realtime & Batch",
      label: "Advanced",
      description: "Embedded runtime support for batch mutations and realtime subscriptions.",
      endpoints: [
        { method: "GET", path: "/api/realtime" },
        { method: "POST", path: "/api/realtime" },
        { method: "POST", path: "/api/batch" }
      ],
      hint: "Realtime accepts subscriptions[] and options.query, matching PocketBase client conventions."
    }
  ];

  if (collection.type === "auth") {
    cards.splice(1, 0, {
      title: "Authentication",
      label: "Auth",
      description: "Password auth and refresh endpoints exposed for auth collections.",
      endpoints: [
        { method: "POST", path: `/api/collections/${collection.name}/auth-with-password` },
        { method: "POST", path: `/api/collections/${collection.name}/auth-refresh` },
        { method: "GET", path: `/api/collections/${collection.name}/auth-methods` }
      ],
      hint: "Current Java runtime exposes password auth, auth refresh and auth methods."
    });
  }

  if ((collection.fields ?? []).some((field) => field.type === "file")) {
    cards.push({
      title: "Files",
      label: "Storage",
      description: "File fields are uploaded through record endpoints and served through the files API.",
      endpoints: [
        { method: "POST", path: `/api/collections/${collection.name}/records` },
        { method: "PATCH", path: `/api/collections/${collection.name}/records/:id` },
        { method: "GET", path: `/api/files/${collection.name}/:recordId/:filename` },
        { method: "POST", path: "/api/files/token" }
      ],
      hint: "Appending files uses fieldName+ and removals use fieldName- in multipart payloads."
    });
  }

  return cards;
}

function authCards(
  collection: CollectionSchema,
  authMethods: AuthMethodsResponse | null,
  loading: boolean
) {
  if (loading) {
    return [
      {
        title: "Auth methods",
        label: "Loading",
        description: "Fetching auth capabilities from the embedded runtime."
      }
    ];
  }

  const identityFields = authMethods?.password?.identityFields?.join(", ") || "email";
  const oauthProviders = Array.isArray(authMethods?.oauth2) ? authMethods?.oauth2.length : 0;
  const mfaEnabled = Boolean(authMethods?.mfa?.enabled);

  return [
    {
      title: "Password auth",
      label: authMethods?.password?.enabled === false ? "Disabled" : "Enabled",
      description: "Current Java runtime supports password-based auth for auth collections.",
      code: `identityFields = ${identityFields}`
    },
    {
      title: "MFA",
      label: mfaEnabled ? "Enabled" : "Disabled",
      description: mfaEnabled
        ? "The runtime reports MFA support for this collection."
        : "The current runtime exposes MFA as disabled."
    },
    {
      title: "OAuth2",
      label: oauthProviders > 0 ? `${oauthProviders} providers` : "Unavailable",
      description: oauthProviders > 0
        ? "OAuth2 providers are configured for this collection."
        : "OAuth2 is not exposed by the current Java runtime yet."
    },
    {
      title: "Collection type",
      label: collection.system ? "System auth" : "Auth",
      description: "Auth collections expose auth-with-password, auth-refresh and auth-methods endpoints.",
      code: `/api/collections/${collection.name}/auth-with-password`
    }
  ];
}

function makeDraftKey(seed: number) {
  return `field_${seed}_${Math.random().toString(36).slice(2, 8)}`;
}

function asText(value: unknown) {
  return typeof value === "string" ? value : undefined;
}

function asNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function asArray(value: unknown) {
  if (!Array.isArray(value)) return [];
  return value.map((item) => String(item)).filter(Boolean);
}

function joinCsv(values: string[]) {
  return values.join(",");
}

function splitCsv(value: string) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function stringNumber(value: number | undefined) {
  return value === undefined || value === null || Number.isNaN(value) ? "" : String(value);
}

function parsePositiveNumber(value: string) {
  if (!value.trim()) return undefined;
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    throw new Error(`Invalid numeric value "${value}".`);
  }
  return parsed;
}

function firstDefinedText(...values: Array<string | undefined>) {
  return values.find((value) => value !== undefined && value !== "");
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
