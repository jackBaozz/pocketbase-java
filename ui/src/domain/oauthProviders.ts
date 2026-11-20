/** Reorder names only so credentials and settings remain keyed by provider identity. */
export function reorderOAuthProviders(names: readonly string[], source: string, target: string): string[] {
  const from = names.indexOf(source);
  const to = names.indexOf(target);
  const next = [...names];
  if (from < 0 || to < 0 || from === to) return next;
  next.splice(to, 0, next.splice(from, 1)[0]);
  return next;
}
