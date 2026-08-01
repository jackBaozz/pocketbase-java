import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { GripVertical, Pencil, Plus, Search, X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useModalInteraction } from "./useModalInteraction";
import "./RelationPicker.css";

export type RelationFieldSchema = {
  name: string;
  type: string;
  hidden?: boolean;
  presentable?: boolean;
  collectionId?: string;
  collectionIds?: string[];
  options?: Record<string, unknown>;
};

export type RelationCollection = {
  id: string;
  name: string;
  type?: string;
  fields?: RelationFieldSchema[];
};

export type RelationRecord = Record<string, unknown> & { id: string };

type RecordPage = {
  page: number;
  totalItems: number;
  items: RelationRecord[];
};

export type RelationFetcher = (
  collectionName: string,
  params: { page: number; perPage: number; filter: string; expand?: string }
) => Promise<RecordPage>;

const PER_PAGE = 50;
const MAX_SUMMARY_RELATION_DEPTH = 3;
// The records endpoint rejects filters over 3500 characters. Keep generated
// relation search filters below that contract and bound their traversal too.
const MAX_GENERATED_FILTER_LENGTH = 3400;
const MAX_SEARCHABLE_FIELDS = 96;
const MAX_SEARCHABLE_COLLECTION_SCOPES = 128;
const SUMMARY_FALLBACK_TYPES = new Set(["text", "email", "url", "editor", "select", "number", "bool", "date", "autodate"]);

function collectionKey(collection: RelationCollection) {
  return collection.id || collection.name;
}

export function relationTarget(field: RelationFieldSchema, collections: RelationCollection[]) {
  const optionCollectionId = typeof field.options?.collectionId === "string" ? field.options.collectionId : "";
  const optionCollectionIds = Array.isArray(field.options?.collectionIds)
    ? field.options.collectionIds.filter((value): value is string => typeof value === "string")
    : [];
  const candidates = [field.collectionId, ...(field.collectionIds ?? []), optionCollectionId, ...optionCollectionIds].filter(Boolean);
  return collections.find((collection) => candidates.includes(collection.id) || candidates.includes(collection.name));
}

function summaryFields(collection?: RelationCollection) {
  const fields = collection?.fields ?? [];
  const visible = fields.filter((field) => !field.hidden);
  const presentable = visible.filter((field) => field.presentable);
  return presentable.length ? presentable : visible.filter((field) => field.name !== "id" && SUMMARY_FALLBACK_TYPES.has(field.type));
}

function relationIds(value: unknown) {
  const source = Array.isArray(value) ? value : [value];
  return source
    .filter((item) => typeof item === "string" || typeof item === "number")
    .map((item) => String(item))
    .filter(Boolean);
}

function isRelationRecord(value: unknown): value is RelationRecord {
  return Boolean(value && typeof value === "object" && typeof (value as Record<string, unknown>).id === "string");
}

function expandedRelationRecords(record: RelationRecord, fieldName: string) {
  const rawExpand = record.expand ?? record["@expand"];
  if (!rawExpand || typeof rawExpand !== "object") return [];
  const value = (rawExpand as Record<string, unknown>)[fieldName];
  return (Array.isArray(value) ? value : [value]).filter(isRelationRecord);
}

function formatSummaryValue(value: unknown): string {
  if (value === undefined || value === null || value === "") return "";
  if (Array.isArray(value)) return value.map(formatSummaryValue).filter(Boolean).join(", ");
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch {
      return "";
    }
  }
  return String(value);
}

function recordSummaryValue(
  record: RelationRecord,
  field: RelationFieldSchema,
  collections: RelationCollection[],
  depth: number,
  visited: Set<string>
): string {
  const value = record[field.name];
  if (field.type !== "relation") return formatSummaryValue(value);

  const target = relationTarget(field, collections);
  const expanded = expandedRelationRecords(record, field.name);
  if (target && expanded.length > 0) {
    return expanded
      .map((item) => recordSummaryInternal(item, target, collections, depth + 1, visited))
      .filter(Boolean)
      .join(", ");
  }
  return relationIds(value).join(", ");
}

function recordSummaryInternal(
  record: RelationRecord,
  collection: RelationCollection | undefined,
  collections: RelationCollection[],
  depth: number,
  visited: Set<string>
): string {
  if (!collection || depth > MAX_SUMMARY_RELATION_DEPTH) return record.id;
  const key = `${collectionKey(collection)}:${record.id}`;
  if (visited.has(key)) return record.id;
  const nextVisited = new Set(visited);
  nextVisited.add(key);
  const parts = summaryFields(collection)
    .map((field) => recordSummaryValue(record, field, collections, depth, nextVisited))
    .filter(Boolean);
  return parts.length ? parts.join(" / ") : record.id;
}

/**
 * Renders a record as the official UI does: the presentable fields joined together,
 * falling back to the first non-empty text-ish field and finally the id.
 */
export function recordSummary(record: RelationRecord, collection?: RelationCollection, collections: RelationCollection[] = []): string {
  return recordSummaryInternal(record, collection, collections, 0, new Set());
}

/** Nested expand paths necessary to render presentable relation fields as summaries. */
export function relationSummaryExpandPaths(collection?: RelationCollection, collections: RelationCollection[] = []) {
  if (!collection) return [];
  const paths = new Set<string>();
  const visit = (current: RelationCollection, prefix: string, depth: number, ancestors: Set<string>) => {
    if (depth >= MAX_SUMMARY_RELATION_DEPTH) return;
    for (const field of summaryFields(current)) {
      if (field.type !== "relation") continue;
      const target = relationTarget(field, collections);
      if (!target) continue;
      const path = prefix ? `${prefix}.${field.name}` : field.name;
      paths.add(path);
      const targetKey = collectionKey(target);
      if (ancestors.has(targetKey)) continue;
      const nextAncestors = new Set(ancestors);
      nextAncestors.add(targetKey);
      visit(target, path, depth + 1, nextAncestors);
    }
  };
  visit(collection, "", 0, new Set([collectionKey(collection)]));
  return [...paths];
}

/** Expands every visible relation cell plus the nested fields needed by its summary. */
export function recordListRelationExpandPaths(collection?: RelationCollection, collections: RelationCollection[] = []) {
  if (!collection) return [];
  const paths = new Set<string>();
  for (const field of collection.fields ?? []) {
    if (field.type !== "relation" || field.hidden) continue;
    paths.add(field.name);
    const target = relationTarget(field, collections);
    for (const path of relationSummaryExpandPaths(target, collections)) paths.add(`${field.name}.${path}`);
  }
  return [...paths];
}

function quoteFilterLiteral(value: string) {
  return `"${value.replaceAll("\\", "\\\\").replaceAll("\"", "\\\"")}"`;
}

function unquoteSearchTerm(value: string) {
  const trimmed = value.trim();
  if (trimmed.length > 1 && ["\"", "'", "`"].includes(trimmed[0]) && trimmed.at(-1) === trimmed[0]) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function looksLikeFilterExpression(value: string) {
  return /&&|\|\||[?!]?(?:=|~)|[<>]=?/.test(value);
}

type SearchField = { path: string; type: string };

function searchableFields(collection: RelationCollection, collections: RelationCollection[]) {
  const result = new Map<string, SearchField>();
  let visitedScopes = 0;
  const add = (path: string, type: string) => {
    if (result.has(path) || result.size < MAX_SEARCHABLE_FIELDS) {
      result.set(path, { path, type });
    }
  };
  const visit = (current: RelationCollection, prefix: string, depth: number, ancestors: Set<string>) => {
    if (visitedScopes >= MAX_SEARCHABLE_COLLECTION_SCOPES || result.size >= MAX_SEARCHABLE_FIELDS) return;
    visitedScopes += 1;
    const idPath = prefix ? `${prefix}.id` : "id";
    add(idPath, "text");
    const nested: Array<{ path: string; target: RelationCollection }> = [];
    for (const field of current.fields ?? []) {
      if (result.size >= MAX_SEARCHABLE_FIELDS) return;
      if (field.hidden || field.name === "id") continue;
      const path = prefix ? `${prefix}.${field.name}` : field.name;
      if (field.type === "relation") {
        const target = relationTarget(field, collections);
        const targetKey = target ? collectionKey(target) : "";
        if (target && depth < MAX_SUMMARY_RELATION_DEPTH && !ancestors.has(targetKey)) {
          nested.push({ path, target });
        }
        continue;
      }
      // Keep the record-page search as broad as its former visible-field search,
      // while excluding password data even when a custom schema exposes it.
      if (field.type !== "password") add(path, field.type);
    }
    // Prioritize fields at the current level, then spend the remaining budget
    // on relations. This keeps simple searches useful in a wide relation graph.
    for (const { path, target } of nested) {
      if (visitedScopes >= MAX_SEARCHABLE_COLLECTION_SCOPES || result.size >= MAX_SEARCHABLE_FIELDS) return;
      const nextAncestors = new Set(ancestors);
      nextAncestors.add(collectionKey(target));
      visit(target, path, depth + 1, nextAncestors);
    }
  };
  visit(collection, "", 0, new Set([collectionKey(collection)]));
  return [...result.values()];
}

function fieldAtPath(collection: RelationCollection, path: string, collections: RelationCollection[]) {
  const parts = path.split(".").filter(Boolean);
  let current: RelationCollection | undefined = collection;
  for (let index = 0; index < parts.length; index += 1) {
    const part = parts[index];
    if (part === "id" && index === parts.length - 1) return { name: part, type: "text" } as RelationFieldSchema;
    const field = current?.fields?.find((item) => item.name === part);
    if (!field) return undefined;
    if (index === parts.length - 1) return field;
    if (field.type !== "relation") return undefined;
    current = relationTarget(field, collections);
  }
  return undefined;
}

function searchFieldsForPath(collection: RelationCollection, path: string, collections: RelationCollection[]) {
  const field = fieldAtPath(collection, path, collections);
  if (!field) return [];
  if (field.type !== "relation") return [{ path, type: field.type }];
  const target = relationTarget(field, collections);
  return target
    ? searchableFields(target, collections).map((item) => ({ ...item, path: `${path}.${item.path}` }))
    : [];
}

function filterClause(field: SearchField, term: string) {
  const value = unquoteSearchTerm(term);
  if (field.type === "number" && /^[-+]?\d+(?:\.\d+)?$/.test(value)) return `${field.path} = ${value}`;
  if (field.type === "bool" && /^(true|false)$/i.test(value)) return `${field.path} = ${value.toLowerCase()}`;
  if (field.type === "number" || field.type === "bool") return "";
  return `${field.path} ?~ ${quoteFilterLiteral(value)}`;
}

/**
 * Keeps explicit PocketBase filters intact while making picker search aware of
 * visible fields and nested relation paths. `field:value` narrows the
 * search to a particular field (or a relation's presentable fields).
 */
export function relationSearchFilter(term: string, collection?: RelationCollection, collections: RelationCollection[] = []) {
  const raw = term.trim();
  if (!raw || !collection || looksLikeFilterExpression(raw)) return raw;
  const scoped = raw.match(/^([A-Za-z_][A-Za-z0-9_.-]*)\s*:\s*(.+)$/s);
  const scopedFields = scoped ? searchFieldsForPath(collection, scoped[1], collections) : [];
  // A colon may simply be part of a value (for example, an URL). Only treat it
  // as a field qualifier when the path actually exists in this collection.
  const fields = scopedFields.length ? scopedFields : searchableFields(collection, collections);
  const needle = scopedFields.length && scoped ? scoped[2] : raw;
  const clauses: string[] = [];
  let filterLength = 2; // outer parentheses
  for (const field of fields) {
    const clause = filterClause(field, needle);
    if (!clause) continue;
    const nextLength = filterLength + clause.length + (clauses.length ? 4 : 0); // " || "
    if (nextLength > MAX_GENERATED_FILTER_LENGTH) continue;
    clauses.push(clause);
    filterLength = nextLength;
  }
  return clauses.length ? `(${clauses.join(" || ")})` : raw;
}

function mergeRelationRecords(existing: RelationRecord[], additions: RelationRecord[]) {
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

type RelationPickerProps = {
  field: RelationFieldSchema & { collectionId?: string; maxSelect?: number };
  value: unknown;
  collections: RelationCollection[];
  fetchRecords: RelationFetcher;
  onChange: (value: unknown) => void;
  /** Opens a nested record form and receives the server's saved record. */
  onCreateRecord?: (target: RelationCollection, onSaved: (record: RelationRecord) => void) => void;
  /** Opens a nested record form for an existing relation record. */
  onEditRecord?: (target: RelationCollection, id: string, onSaved: (record: RelationRecord) => void) => void;
};

export function RelationPicker({
  field,
  value,
  collections,
  fetchRecords,
  onChange,
  onCreateRecord,
  onEditRecord
}: RelationPickerProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [items, setItems] = useState<RelationRecord[]>([]);
  const [page, setPage] = useState<RecordPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [resolved, setResolved] = useState<Record<string, RelationRecord>>({});
  const [draggedId, setDraggedId] = useState("");
  const [dropTargetId, setDropTargetId] = useState("");
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const attempted = useRef(new Set<string>());
  const loadRequestId = useRef(0);
  const { dialogRef, onBackdropMouseDown, onBackdropMouseUp } = useModalInteraction<HTMLDivElement>(
    () => setOpen(false),
    { active: open }
  );

  const target = useMemo(() => relationTarget(field, collections), [collections, field]);
  const maxSelect = Math.max(1, Number(field.maxSelect ?? 1));
  const isMulti = maxSelect > 1;

  const selectedIds = useMemo(() => {
    if (Array.isArray(value)) return value.map(String).filter(Boolean);
    return value ? [String(value)] : [];
  }, [value]);

  const load = useCallback(
    async (nextPage: number, term: string) => {
      const requestId = ++loadRequestId.current;
      if (!target) return;
      setLoading(true);
      setError("");
      try {
        const data = await fetchRecords(target.name, {
          page: nextPage,
          perPage: PER_PAGE,
          filter: relationSearchFilter(term, target, collections),
          expand: relationSummaryExpandPaths(target, collections).join(",")
        });
        if (requestId !== loadRequestId.current) return;
        setPage(data);
        setItems((prev) => (nextPage > 1 ? mergeRelationRecords(prev, data.items) : data.items));
      } catch (err) {
        if (requestId !== loadRequestId.current) return;
        setError(err instanceof Error ? err.message : String(err));
      } finally {
        if (requestId === loadRequestId.current) setLoading(false);
      }
    },
    [collections, fetchRecords, target]
  );

  useEffect(() => {
    if (!open) {
      loadRequestId.current += 1;
      return;
    }
    void load(1, search);
  }, [load, open, search]);

  useEffect(
    () => () => {
      if (searchTimer.current) window.clearTimeout(searchTimer.current);
    },
    []
  );

  useEffect(() => {
    attempted.current.clear();
    setResolved({});
  }, [target?.id, target?.name]);

  // Resolve already-selected ids so the chips show summaries instead of raw ids.
  // Ids are marked as attempted before the request so a deleted record — which never
  // comes back in the response — cannot re-trigger this effect forever.
  useEffect(() => {
    const missing = selectedIds.filter((id) => !attempted.current.has(id));
    if (!target || missing.length === 0) return;
    let cancelled = false;
    for (const id of missing) attempted.current.add(id);
    const filter = missing.map((id) => `id = ${quoteFilterLiteral(id)}`).join(" || ");
    fetchRecords(target.name, {
      page: 1,
      perPage: missing.length,
      filter,
      expand: relationSummaryExpandPaths(target, collections).join(",")
    })
      .then((data) => {
        if (cancelled) return;
        setResolved((prev) => {
          const next = { ...prev };
          for (const item of data.items) next[item.id] = item;
          return next;
        });
      })
      .catch(() => {
        // Allow a retry on the next selection change rather than pinning a transient failure.
        if (!cancelled) for (const id of missing) attempted.current.delete(id);
      });
    return () => {
      cancelled = true;
    };
  }, [collections, fetchRecords, selectedIds, target]);

  useEffect(() => {
    if (items.length === 0) return;
    setResolved((prev) => {
      const next = { ...prev };
      for (const item of items) {
        next[item.id] = item;
        attempted.current.add(item.id);
      }
      return next;
    });
  }, [items]);

  function emit(ids: string[]) {
    onChange(isMulti ? ids : (ids[0] ?? ""));
  }

  function toggle(record: RelationRecord) {
    if (!isMulti) {
      emit([record.id]);
      setOpen(false);
      return;
    }
    if (selectedIds.includes(record.id)) {
      emit(selectedIds.filter((id) => id !== record.id));
      return;
    }
    // Mirrors the official picker: past the limit the oldest pick is pushed out.
    const next = [...selectedIds, record.id];
    emit(next.length > maxSelect ? next.slice(next.length - maxSelect) : next);
  }

  function onSearchInput(term: string) {
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => setSearch(term), 250);
  }

  function mergeRelatedRecord(record: RelationRecord) {
    setResolved((current) => ({ ...current, [record.id]: record }));
    setItems((current) => current.map((item) => (item.id === record.id ? { ...item, ...record } : item)));
  }

  function selectCreatedRecord(record: RelationRecord) {
    mergeRelatedRecord(record);
    if (!isMulti) {
      emit([record.id]);
      return;
    }
    const next = selectedIds.includes(record.id) ? selectedIds : [...selectedIds, record.id];
    emit(next.length > maxSelect ? next.slice(next.length - maxSelect) : next);
  }

  function startCreate() {
    if (!target || !onCreateRecord) return;
    // The nested record editor uses the normal modal layer. Close this picker
    // first so its higher backdrop cannot mask the editor.
    setOpen(false);
    onCreateRecord(target, selectCreatedRecord);
  }

  function startEdit(id: string) {
    if (!target || !onEditRecord) return;
    setOpen(false);
    onEditRecord(target, id, mergeRelatedRecord);
  }

  function moveSelected(fromId: string, toId: string) {
    if (!isMulti || !fromId || fromId === toId) return;
    const from = selectedIds.indexOf(fromId);
    const to = selectedIds.indexOf(toId);
    if (from < 0 || to < 0) return;
    const next = [...selectedIds];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    emit(next);
  }

  const canManageRecords = target?.type !== "view" && Boolean(onCreateRecord || onEditRecord);

  const hasMore = Boolean(page && items.length < page.totalItems);

  if (!target) {
    // Without a resolvable target collection, fall back to raw id entry.
    return (
      <input
        name={field.name}
        autoComplete="off"
        value={selectedIds.join(", ")}
        placeholder={isMulti ? "id1, id2" : "id"}
        onChange={(event) => {
          const ids = event.target.value
            .split(",")
            .map((item) => item.trim())
            .filter(Boolean);
          emit(ids);
        }}
      />
    );
  }

  return (
    <div className="relation-picker">
      <div className="relation-selected">
        {selectedIds.length === 0 && (
          <span className="relation-empty">{t("fields.no_records_selected", "No records selected")}</span>
        )}
        {selectedIds.map((id) => (
          <span
            key={id}
            className={`relation-chip${draggedId === id ? " is-dragging" : ""}${dropTargetId === id ? " is-drop-target" : ""}`}
            onDragOver={(event) => {
              if (!isMulti || !draggedId || draggedId === id) return;
              event.preventDefault();
              setDropTargetId(id);
            }}
            onDragLeave={() => {
              if (dropTargetId === id) setDropTargetId("");
            }}
            onDrop={(event) => {
              event.preventDefault();
              const fromId = draggedId || event.dataTransfer.getData("text/plain");
              moveSelected(fromId, id);
              setDraggedId("");
              setDropTargetId("");
            }}
          >
            {isMulti && selectedIds.length > 1 && (
              <button
                type="button"
                className="relation-chip-drag"
                draggable
                onDragStart={(event) => {
                  event.dataTransfer.effectAllowed = "move";
                  event.dataTransfer.setData("text/plain", id);
                  setDraggedId(id);
                }}
                onDragEnd={() => {
                  setDraggedId("");
                  setDropTargetId("");
                }}
                title={t("collections.drag_to_reorder", "Drag to reorder")}
                aria-label={t("collections.drag_to_reorder", "Drag to reorder")}
              >
                <GripVertical size={13} />
              </button>
            )}
            <span className="relation-chip-label">{resolved[id] ? recordSummary(resolved[id], target, collections) : id}</span>
            {onEditRecord && target.type !== "view" && (
              <button
                type="button"
                className="relation-chip-edit"
                onClick={() => startEdit(id)}
                title={t("actions.edit", "Edit")}
                aria-label={t("actions.edit", "Edit")}
              >
                <Pencil size={12} />
              </button>
            )}
            <button
              type="button"
              className="relation-chip-remove"
              onClick={() => emit(selectedIds.filter((item) => item !== id))}
              title={t("actions.remove", "Remove")}
              aria-label={t("actions.remove", "Remove")}
            >
              <X size={12} />
            </button>
          </span>
        ))}
      </div>
      <button type="button" className="subtle relation-open" onClick={() => setOpen(true)}>
        <Plus size={14} />
        {t("fields.select_records", "Select records")}
        <em className="relation-target">{target.name}</em>
      </button>

      {open && (
        <div
          className="relation-modal-backdrop"
          role="presentation"
          onMouseDown={onBackdropMouseDown}
          onMouseUp={onBackdropMouseUp}
        >
          <div ref={dialogRef} className="relation-modal" role="dialog" aria-modal="true" tabIndex={-1}>
            <header className="relation-modal-header">
              <strong>{t("fields.select_from", { collection: target.name, defaultValue: "Select from {{collection}}" })}</strong>
              <div className="relation-modal-header-actions">
                {canManageRecords && onCreateRecord && (
                  <button type="button" className="subtle compact" onClick={startCreate}>
                    <Plus size={14} />
                    {t("actions.new_record", "New record")}
                  </button>
                )}
                <button
                  type="button"
                  className="icon-button"
                  onClick={() => setOpen(false)}
                  title={t("actions.close", "Close")}
                  aria-label={t("actions.close", "Close")}
                >
                  <X size={16} />
                </button>
              </div>
            </header>
            <div className="relation-modal-search">
              <Search size={15} />
              <input
                autoFocus
                type="text"
                placeholder={t("actions.search", "Search")}
                defaultValue={search}
                onChange={(event) => onSearchInput(event.target.value)}
              />
            </div>
            {isMulti && (
              <p className="relation-modal-hint">
                {t("fields.selected_of_max", {
                  count: selectedIds.length,
                  max: maxSelect,
                  defaultValue: "{{count}} of {{max}} selected"
                })}
              </p>
            )}
            {error && <p className="form-error">{error}</p>}
            <div className="relation-modal-list">
              {items.length === 0 && !loading ? (
                <p className="relation-empty">{t("common.no_results", "No results")}</p>
              ) : (
                items.map((item) => {
                  const active = selectedIds.includes(item.id);
                  return (
                    <div className="relation-option-row" key={item.id}>
                      <button
                        type="button"
                        className={`relation-option${active ? " active" : ""}`}
                        onClick={() => toggle(item)}
                      >
                        <span className="relation-option-label">{recordSummary(item, target, collections)}</span>
                        <code>{item.id}</code>
                      </button>
                      {onEditRecord && target.type !== "view" && (
                        <button
                          type="button"
                          className="icon-button tiny relation-option-edit"
                          onClick={() => startEdit(item.id)}
                          title={t("actions.edit", "Edit")}
                          aria-label={t("actions.edit", "Edit")}
                        >
                          <Pencil size={14} />
                        </button>
                      )}
                    </div>
                  );
                })
              )}
            </div>
            <footer className="relation-modal-footer">
              {hasMore ? (
                <button
                  type="button"
                  className="subtle"
                  disabled={loading}
                  onClick={() => void load((page?.page ?? 1) + 1, search)}
                >
                  {loading ? t("common.loading", "Loading...") : t("records.load_more_short", "Load more")}
                </button>
              ) : (
                <span />
              )}
              <button type="button" className="primary" onClick={() => setOpen(false)}>
                {t("actions.done", "Done")}
              </button>
            </footer>
          </div>
        </div>
      )}
    </div>
  );
}
