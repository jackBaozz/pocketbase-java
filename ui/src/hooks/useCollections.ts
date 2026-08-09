/**
 * useCollections — collection list state, selection, search filter,
 * pinned collections, and derived grouping (regular / system / pinned).
 *
 * Extracted from the root App component. Aligned with App's full behavior
 * including pin-to-front ordering and localStorage persistence.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import type { CollectionSchema } from "../types/api";

const PINNED_COLLECTIONS_KEY = "pbj_pinned_collections";

function readStringArray(key: string): string[] {
  try {
    return JSON.parse(localStorage.getItem(key) || "[]");
  } catch {
    return [];
  }
}

export function isSystemCollection(collection: CollectionSchema): boolean {
  return Boolean(collection.system) || collection.name.startsWith("_");
}

export function useCollections() {
  const [collections, setCollections] = useState<any[]>([]);
  const [selectedName, setSelectedName] = useState<string>("");
  const [collectionSearch, setCollectionSearch] = useState("");
  const [pinnedCollectionNames, setPinnedCollectionNames] = useState<string[]>(() =>
    readStringArray(PINNED_COLLECTIONS_KEY)
  );

  useEffect(() => {
    localStorage.setItem(PINNED_COLLECTIONS_KEY, JSON.stringify(pinnedCollectionNames));
  }, [pinnedCollectionNames]);

  const selected = useMemo<any>(
    () => collections.find((collection: any) => collection.name === selectedName) ?? null,
    [collections, selectedName]
  );

  const visibleCollections = useMemo(() => {
    const search = collectionSearch.trim().toLowerCase();
    if (!search) return collections;
    return collections.filter((collection: any) =>
      collection.name.toLowerCase().includes(search) || collection.type.toLowerCase().includes(search)
    );
  }, [collectionSearch, collections]);

  const pinnedSet = useMemo(() => new Set(pinnedCollectionNames), [pinnedCollectionNames]);

  const pinned = useMemo(
    () =>
      pinnedCollectionNames
        .map((name) => visibleCollections.find((collection) => collection.name === name))
        .filter(Boolean) as CollectionSchema[],
    [pinnedCollectionNames, visibleCollections]
  );

  const regular = useMemo(
    () =>
      visibleCollections.filter(
        (collection) => !pinnedSet.has(collection.name) && !isSystemCollection(collection)
      ),
    [visibleCollections, pinnedSet]
  );

  const system = useMemo(
    () =>
      visibleCollections.filter(
        (collection) => !pinnedSet.has(collection.name) && isSystemCollection(collection)
      ),
    [visibleCollections, pinnedSet]
  );

  const togglePinned = useCallback((collection: any) => {
    setPinnedCollectionNames((current) => {
      if (current.includes(collection.name)) {
        return current.filter((name) => name !== collection.name);
      }
      // Pin to the front, matching App's behavior.
      return [collection.name, ...current];
    });
  }, []);

  return useMemo(
    () => ({
      collections,
      setCollections,
      selectedName,
      setSelectedName,
      selected,
      collectionSearch,
      setCollectionSearch,
      visibleCollections,
      pinned,
      regular,
      system,
      pinnedCollectionNames,
      togglePinned,
    }),
    [
      collections,
      selectedName,
      selected,
      collectionSearch,
      visibleCollections,
      pinned,
      regular,
      system,
      pinnedCollectionNames,
      togglePinned,
    ]
  );
}

export type CollectionsState = ReturnType<typeof useCollections>;
