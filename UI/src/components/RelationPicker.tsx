import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Plus, Search, X } from "lucide-react";
import { useTranslation } from "react-i18next";

type FieldSchema = {
  name: string;
  type: string;
  hidden?: boolean;
  presentable?: boolean;
};

type CollectionLike = {
  id: string;
  name: string;
  fields?: FieldSchema[];
};

type RecordLike = Record<string, unknown> & { id: string };

type RecordPage = {
  page: number;
  totalItems: number;
  items: RecordLike[];
};

export type RelationFetcher = (
  collectionName: string,
  params: { page: number; perPage: number; filter: string }
) => Promise<RecordPage>;

const PER_PAGE = 50;

/**
 * Renders a record as the official UI does: the presentable fields joined together,
 * falling back to the first non-empty text-ish field and finally the id.
 */
export function recordSummary(record: RecordLike, collection?: CollectionLike) {
  const fields = collection?.fields ?? [];
  const presentable = fields.filter((field) => field.presentable && !field.hidden);
  const source = presentable.length
    ? presentable
    : fields.filter(
        (field) =>
          !field.hidden &&
          !field.presentable &&
          ["text", "email", "url", "editor", "select", "number"].includes(field.type) &&
          field.name !== "id"
      );

  const parts = source
    .map((field) => record[field.name])
    .filter((value) => value !== undefined && value !== null && value !== "")
    .map((value) => (Array.isArray(value) ? value.join(", ") : String(value)));

  return parts.length ? parts.join(" / ") : record.id;
}

type RelationPickerProps = {
  field: FieldSchema & { collectionId?: string; maxSelect?: number };
  value: unknown;
  collections: CollectionLike[];
  fetchRecords: RelationFetcher;
  onChange: (value: unknown) => void;
};

export function RelationPicker({ field, value, collections, fetchRecords, onChange }: RelationPickerProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [items, setItems] = useState<RecordLike[]>([]);
  const [page, setPage] = useState<RecordPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [resolved, setResolved] = useState<Record<string, RecordLike>>({});
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const attempted = useRef(new Set<string>());

  const target = useMemo(
    () => collections.find((collection) => collection.id === field.collectionId),
    [collections, field.collectionId]
  );
  const maxSelect = Math.max(1, Number(field.maxSelect ?? 1));
  const isMulti = maxSelect > 1;

  const selectedIds = useMemo(() => {
    if (Array.isArray(value)) return value.map(String).filter(Boolean);
    return value ? [String(value)] : [];
  }, [value]);

  const load = useCallback(
    async (nextPage: number, term: string) => {
      if (!target) return;
      setLoading(true);
      setError("");
      try {
        const data = await fetchRecords(target.name, { page: nextPage, perPage: PER_PAGE, filter: term });
        setPage(data);
        setItems((prev) => (nextPage > 1 ? [...prev, ...data.items] : data.items));
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      } finally {
        setLoading(false);
      }
    },
    [fetchRecords, target]
  );

  useEffect(() => {
    if (!open) return;
    void load(1, search);
  }, [load, open, search]);

  // Resolve already-selected ids so the chips show summaries instead of raw ids.
  // Ids are marked as attempted before the request so a deleted record — which never
  // comes back in the response — cannot re-trigger this effect forever.
  useEffect(() => {
    const missing = selectedIds.filter((id) => !attempted.current.has(id));
    if (!target || missing.length === 0) return;
    let cancelled = false;
    for (const id of missing) attempted.current.add(id);
    const filter = missing.map((id) => `id="${id}"`).join("||");
    fetchRecords(target.name, { page: 1, perPage: missing.length, filter })
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
  }, [fetchRecords, selectedIds, target]);

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

  function toggle(record: RecordLike) {
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
          <span key={id} className="relation-chip">
            <span className="relation-chip-label">{resolved[id] ? recordSummary(resolved[id], target) : id}</span>
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
        <div className="relation-modal-backdrop" onClick={() => setOpen(false)}>
          <div className="relation-modal" onClick={(event) => event.stopPropagation()}>
            <header className="relation-modal-header">
              <strong>
                {t("fields.select_from", { collection: target.name, defaultValue: "Select from {{collection}}" })}
              </strong>
              <button
                type="button"
                className="icon-button"
                onClick={() => setOpen(false)}
                title={t("actions.close", "Close")}
                aria-label={t("actions.close", "Close")}
              >
                <X size={16} />
              </button>
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
                    <button
                      type="button"
                      key={item.id}
                      className={`relation-option${active ? " active" : ""}`}
                      onClick={() => toggle(item)}
                    >
                      <span className="relation-option-label">{recordSummary(item, target)}</span>
                      <code>{item.id}</code>
                    </button>
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
