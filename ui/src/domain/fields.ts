/**
 * Field domain logic — resolves the multiplicity/limit for a field type.
 *
 * Extracted from duplicate `maxFiles()` functions in App.tsx,
 * RecordFieldControl.tsx, and FileFieldControl.tsx.
 *
 * The old code had an inconsistency: App.tsx resolved `maxSelect ?? maxFiles`
 * (preferring relation semantics) while the controls resolved `maxFiles ??
 * maxSelect` (preferring file semantics). This module unifies them with a
 * single function that picks the right key based on the field type, which is
 * what PocketBase itself does.
 */

import type { FieldSchema, FileField } from "../types/api";

/**
 * Returns the effective multiplicity limit for a field:
 *
 * - For `relation` fields: `maxSelect` ( PocketBase stores relation max there).
 * - For `file` fields: `maxFiles` (PocketBase stores file max there).
 * - Falls back to `options.maxSelect` / `options.maxFiles` for legacy schemas.
 * - Always returns at least 1.
 */
export function fieldMultiplicity(field: FieldSchema | FileField): number {
  // FileField doesn't carry a `type`, so default to file semantics.
  const isRelation = "type" in field && field.type === "relation";
  const direct = isRelation
    ? (field as FieldSchema).maxSelect ?? (field as FieldSchema).maxFiles
    : (field as FieldSchema).maxFiles ?? (field as FieldSchema).maxSelect;
  const option = Number(
    isRelation
      ? field.options?.maxSelect ?? field.options?.maxFiles ?? 1
      : field.options?.maxFiles ?? field.options?.maxSelect ?? 1
  );
  return Math.max(1, Number(direct ?? option ?? 1));
}

/** Default empty value for a field, based on its type and multiplicity. */
export function fieldDefault(field: FieldSchema): unknown {
  if (field.type === "bool") return false;
  if (field.type === "number") return 0;
  if (field.type === "json") return null;
  if (field.type === "relation") return fieldMultiplicity(field) > 1 ? [] : "";
  if (field.type === "file") return fieldMultiplicity(field) > 1 ? [] : "";
  if (field.type === "select") return fieldMultiplicity(field) > 1 ? [] : "";
  return "";
}

/**
 * A self-referencing cascade can remove records other than the one selected in
 * the Admin UI. Those removals cannot be reconciled from a single DELETE response,
 * so the list needs a server refresh instead of a local optimistic removal.
 */
export function hasSelfReferentialCascadeDelete(
  collection:
    | { id?: string; name?: string; fields?: FieldSchema[] }
    | null
    | undefined
): boolean {
  if (!collection) return false;
  const collectionKeys = new Set(
    [collection.id, collection.name].filter((value): value is string => Boolean(value))
  );
  if (collectionKeys.size === 0) return false;

  return (collection.fields ?? []).some((field) => {
    if (field.type !== "relation" || (field.cascadeDelete !== true && field.options?.cascadeDelete !== true)) {
      return false;
    }
    const optionCollectionId = typeof field.options?.collectionId === "string" ? field.options.collectionId : "";
    const optionCollectionIds = Array.isArray(field.options?.collectionIds)
      ? field.options.collectionIds.filter((value): value is string => typeof value === "string")
      : [];
    return [field.collectionId, ...(field.collectionIds ?? []), optionCollectionId, ...optionCollectionIds]
      .filter((id): id is string => typeof id === "string" && id.length > 0)
      .some((id) => collectionKeys.has(id));
  });
}
