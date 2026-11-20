/** Reserve label width (including grouping separators) instead of clipping at five digits. */
export function logChartAxisWidth(labels: readonly string[]): number {
  return Math.max(44, ...labels.map((label) => label.length * 8 + 14));
}
