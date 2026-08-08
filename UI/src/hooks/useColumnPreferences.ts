/**
 * useColumnPreferences — per-collection column visibility preferences,
 * persisted to localStorage by collection id.
 *
 * Uses a snapshot marker (__pbj_hidden_columns_v2__) to remember that the
 * user has explicitly chosen their column set. Without the marker, schema-
 * hidden fields default to hidden; with it, only the user's explicit choices
 * apply (so a previously-revealed hidden field stays visible).
 *
 * Fully aligned with the App.tsx implementation.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import type { CollectionSchema, FieldSchema } from "../types/api";

const HIDDEN_COLUMNS_KEY = "pbj_hidden_columns";
const HIDDEN_COLUMNS_SNAPSHOT_MARKER = "__pbj_hidden_columns_v2__";

// ── Pure helpers ──

function collectionPreferenceStoreKey(collection: CollectionSchema): string {
  return `collection:${collection.id || collection.name}`;
}

function columnPreferenceKey(collection: CollectionSchema, column: string): string {
  const field = (collection.fields ?? []).find((candidate) => candidate.name === column);
  return field?.id ? `field:${field.id}` : `system:${column}`;
}

function recordColumnNames(collection: CollectionSchema): string[] {
  const isSuperusers = collection.name === "_superusers";
  const fieldNames = (collection.fields ?? [])
    .filter((field) => field.type !== "password")
    .filter((field) => !(isSuperusers && field.name === "verified"))
    .map((field) => field.name);
  const columns: string[] = [];
  for (const name of ["id", ...fieldNames, "created", "updated"]) {
    if (!columns.includes(name)) columns.push(name);
  }
  return columns;
}

function hiddenColumnPreferencesFor(
  collection: CollectionSchema,
  preferences: Record<string, string[]>
): string[] {
  const keys = [collectionPreferenceStoreKey(collection), collection.name];
  return Array.from(new Set(keys.flatMap((key) => preferences[key] ?? [])));
}

function normalizeColumnPreferences(collection: CollectionSchema, values: string[]): string[] {
  const columns = recordColumnNames(collection);
  const knownColumns = new Set(columns);
  const fieldNameById = new Map(
    (collection.fields ?? []).filter((field) => field.id).map((field) => [field.id!, field.name])
  );
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

/**
 * Builds the effective hidden set. If the user has no snapshot marker yet,
 * schema-hidden fields are added by default. Once the marker exists, only
 * the user's explicit choices apply.
 */
function hiddenColumnPreferenceSnapshot(collection: CollectionSchema, values: string[]): string[] {
  const hidden = new Set(normalizeColumnPreferences(collection, values));
  if (!values.includes(HIDDEN_COLUMNS_SNAPSHOT_MARKER)) {
    for (const field of collection.fields ?? []) {
      if (field.hidden) hidden.add(columnPreferenceKey(collection, field.name));
    }
  }
  return [HIDDEN_COLUMNS_SNAPSHOT_MARKER, ...hidden];
}

function readStringArrayRecord(key: string): Record<string, string[]> {
  try {
    return JSON.parse(localStorage.getItem(key) || "{}");
  } catch {
    return {};
  }
}

// ── Hook ──

export function useColumnPreferences(collection: CollectionSchema | null) {
  const [hiddenColumnsByCollection, setHiddenColumnsByCollection] = useState<Record<string, string[]>>(() =>
    readStringArrayRecord(HIDDEN_COLUMNS_KEY)
  );

  useEffect(() => {
    localStorage.setItem(HIDDEN_COLUMNS_KEY, JSON.stringify(hiddenColumnsByCollection));
  }, [hiddenColumnsByCollection]);

  const hiddenColumns = useMemo(() => {
    if (!collection) return [];
    const preferences = hiddenColumnPreferencesFor(collection, hiddenColumnsByCollection);
    const hidden = new Set(hiddenColumnPreferenceSnapshot(collection, preferences).slice(1));
    return recordColumnNames(collection).filter((column) =>
      hidden.has(columnPreferenceKey(collection, column))
    );
  }, [collection, hiddenColumnsByCollection]);

  const toggleColumn = useCallback((column: string) => {
    if (!collection) return;
    setHiddenColumnsByCollection((current) => {
      const storeKey = collectionPreferenceStoreKey(collection);
      const key = columnPreferenceKey(collection, column);
      const source = hiddenColumnPreferencesFor(collection, current);
      const existing = new Set(hiddenColumnPreferenceSnapshot(collection, source).slice(1));
      if (existing.has(key)) {
        existing.delete(key);
      } else {
        existing.add(key);
      }
      const next = { ...current };
      next[storeKey] = [HIDDEN_COLUMNS_SNAPSHOT_MARKER, ...existing];
      if (storeKey !== collection.name) delete next[collection.name];
      return next;
    });
  }, [collection]);

  const resetColumns = useCallback(() => {
    if (!collection) return;
    setHiddenColumnsByCollection((current) => {
      const next = { ...current };
      delete next[collectionPreferenceStoreKey(collection)];
      delete next[collection.name];
      return next;
    });
  }, [collection]);

  return useMemo(
    () => ({
      hiddenColumns,
      toggleColumn,
      resetColumns,
      /** Migrate legacy name-keyed preferences to id-keyed snapshots. */
      setHiddenColumnsByCollection,
    }),
    [hiddenColumns, toggleColumn, resetColumns]
  );
}

export type ColumnPreferences = ReturnType<typeof useColumnPreferences>;
