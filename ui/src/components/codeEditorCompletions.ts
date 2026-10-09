import type { ReactNode } from "react";

export type CompletionItem = string | { value: string; label?: ReactNode };
type CompletionResult = readonly CompletionItem[] | null | undefined;
export type CompletionSource =
  | readonly CompletionItem[]
  | ((prefix: string) => CompletionResult | Promise<CompletionResult>);

export const completionValue = (item: CompletionItem): string =>
  typeof item === "string" ? item : item.value;

const MAX_SUGGESTIONS = 30;

/** Keep prefix matches first and prefer shorter keys, as in the existing editor. */
export function filterCompletions<T extends CompletionItem>(candidates: readonly T[], word: string): T[] {
  const needle = word.toLowerCase();
  if (!needle) return [];
  const prefixed: T[] = [];
  const contained: T[] = [];
  const seen = new Set<string>();
  for (const candidate of candidates) {
    const value = completionValue(candidate);
    if (!value || seen.has(value)) continue;
    seen.add(value);
    const loweredValue = value.toLowerCase();
    if (loweredValue === needle) continue;
    const valueAt = loweredValue.indexOf(needle);
    const label = typeof candidate === "string" ? undefined : candidate.label;
    // Labels supplement value matching; they must never hide valid code prefixes.
    const labelAt = typeof label === "string" ? label.toLowerCase().indexOf(needle) : -1;
    if (valueAt === 0 || labelAt === 0) prefixed.push(candidate);
    else if (valueAt > 0 || labelAt > 0) contained.push(candidate);
  }
  const byLength = (a: T, b: T) => {
    const first = completionValue(a);
    const second = completionValue(b);
    return first.length - second.length || first.localeCompare(second);
  };
  return prefixed.sort(byLength).concat(contained.sort(byLength)).slice(0, MAX_SUGGESTIONS);
}

/** Latest request wins; close, blur, edits and unmount invalidate in-flight providers. */
export class CompletionRequests {
  private generation = 0;

  invalidate(): void {
    this.generation++;
  }

  async load(source: CompletionSource, prefix: string): Promise<CompletionItem[] | null> {
    const generation = ++this.generation;
    try {
      const candidates = typeof source === "function"
        ? (await source(prefix)) ?? []
        : filterCompletions(source, prefix);
      if (generation !== this.generation) return null;
      const seen = new Set<string>();
      return candidates.filter((item) => {
        const value = completionValue(item);
        if (!value || seen.has(value)) return false;
        seen.add(value);
        return true;
      }).slice(0, MAX_SUGGESTIONS);
    } catch {
      // Completion is optional: provider errors must not break editing or reject unhandled.
      return null;
    }
  }
}
