/**
 * Date utilities — shared formatting and conversion helpers.
 *
 * Extracted from duplicate functions in App.tsx and RecordFieldControl.tsx.
 */

/**
 * Formats an ISO timestamp as a localized date-time string using the browser
 * locale. Returns the original string if parsing fails.
 */
export function formatDate(value: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

/**
 * Converts an ISO timestamp to a `datetime-local` input value
 * (e.g. "2026-08-08T12:30:00"). Returns "" for empty/unparseable values.
 * Handles space-separated timestamps by converting the space to a "T".
 */
export function toDatetimeLocalValue(value: unknown): string {
  if (typeof value !== "string" || value.trim() === "") return "";
  const date = new Date(value.replaceAll(" ", "T"));
  if (Number.isNaN(date.getTime())) return "";
  const pad = (part: number) => String(part).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/**
 * Converts a `datetime-local` input value to an ISO 8601 string.
 * Returns null for empty input (so the server treats it as cleared).
 */
export function fromDatetimeLocalValue(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString().replace("T", " ");
}

/**
 * Formats a value for display in a table cell or code block.
 * Objects/arrays are JSON-stringified; primitives are stringified directly.
 */
export function formatValue(value: unknown): string {
  if (value === undefined || value === null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  return JSON.stringify(value);
}
