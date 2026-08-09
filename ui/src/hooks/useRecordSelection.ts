/**
 * useRecordSelection — multi-select state for the records list,
 * including Shift+Click range selection with toggle-direction awareness.
 *
 * Extracted from the root App component and aligned with its full
 * Shift+Click semantics: the direction (select vs deselect) is determined
 * by whether the anchor id was already selected before the range action.
 */
import { useCallback, useMemo, useRef, useState } from "react";
import type { RecordItem } from "../types/api";

export function useRecordSelection() {
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const lastSelectedId = useRef<string | null>(null);

  const selectedSet = useMemo(() => new Set(selectedIds), [selectedIds]);

  /**
   * Toggle a single record, or extend the selection across a range.
   * When extendRange is true, applies the toggle direction (select/deselect)
   * based on whether `id` was already selected.
   */
  const toggleSelected = useCallback(
    (id: string, records: RecordItem[], extendRange = false) => {
      if (extendRange && lastSelectedId.current && lastSelectedId.current !== id) {
        const ids = records.map((record) => record.id).filter(Boolean) as string[];
        const from = ids.indexOf(lastSelectedId.current);
        const to = ids.indexOf(id);
        if (from !== -1 && to !== -1) {
          const span = ids.slice(Math.min(from, to), Math.max(from, to) + 1);
          setSelectedIds((current) => {
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
      setSelectedIds((current) => {
        if (current.includes(id)) return current.filter((item) => item !== id);
        return [...current, id];
      });
    },
    []
  );

  /**
   * Select all or none. When selecting all, optionally set an anchor id
   * (used by Ctrl/Cmd+A which has an active row, vs the header checkbox
   * which deliberately leaves no range anchor).
   */
  const toggleAll = useCallback(
    (records: RecordItem[], checked: boolean, anchorId?: string) => {
      if (!checked) {
        setSelectedIds([]);
        lastSelectedId.current = null;
        return;
      }
      const ids = records.map((record) => record.id).filter(Boolean) as string[];
      setSelectedIds(ids);
      const allIds = records.map((record) => record.id);
      lastSelectedId.current = anchorId && allIds.includes(anchorId) ? anchorId : null;
    },
    []
  );

  const clearSelection = useCallback(() => {
    setSelectedIds([]);
    lastSelectedId.current = null;
  }, []);

  /** Prune stale ids that no longer exist in the current record set. */
  const syncWithRecords = useCallback((records: RecordItem[]) => {
    const ids = new Set(records.map((record) => record.id));
    setSelectedIds((current) => current.filter((id) => ids.has(id)));
  }, []);

  return useMemo(
    () => ({
      selectedIds,
      setSelectedIds,
      selectedSet,
      toggleSelected,
      toggleAll,
      clearSelection,
      syncWithRecords,
    }),
    [selectedIds, selectedSet, toggleSelected, toggleAll, clearSelection, syncWithRecords]
  );
}

export type RecordSelection = ReturnType<typeof useRecordSelection>;
