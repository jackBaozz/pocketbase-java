import { describe, it, expect } from "vitest";
import { formatDate, formatValue, toDatetimeLocalValue, fromDatetimeLocalValue } from "./date";

describe("formatDate", () => {
  it("returns empty string for empty input", () => {
    expect(formatDate("")).toBe("");
  });

  it("returns original string for unparseable input", () => {
    expect(formatDate("not-a-date")).toBe("not-a-date");
  });

  it("returns a formatted string for valid ISO date", () => {
    const result = formatDate("2026-08-08T12:30:00Z");
    expect(result).toBeTruthy();
    expect(result).not.toBe("2026-08-08T12:30:00Z"); // should be localized
    expect(result.length).toBeGreaterThan(0);
  });
});

describe("formatValue", () => {
  it("returns empty string for undefined", () => {
    expect(formatValue(undefined)).toBe("");
  });

  it("returns empty string for null", () => {
    expect(formatValue(null)).toBe("");
  });

  it("returns string as-is", () => {
    expect(formatValue("hello")).toBe("hello");
  });

  it("returns stringified number", () => {
    expect(formatValue(42)).toBe("42");
  });

  it("returns stringified boolean", () => {
    expect(formatValue(true)).toBe("true");
  });

  it("returns JSON for objects", () => {
    expect(formatValue({ a: 1 })).toBe('{"a":1}');
  });

  it("returns JSON for arrays", () => {
    expect(formatValue([1, 2, 3])).toBe("[1,2,3]");
  });
});

describe("toDatetimeLocalValue", () => {
  it("returns empty string for empty input", () => {
    expect(toDatetimeLocalValue("")).toBe("");
    expect(toDatetimeLocalValue(null)).toBe("");
    expect(toDatetimeLocalValue(undefined)).toBe("");
  });

  it("returns empty string for non-string input", () => {
    expect(toDatetimeLocalValue(42)).toBe("");
  });

  it("handles space-separated timestamps (PocketBase format)", () => {
    const result = toDatetimeLocalValue("2026-08-08 12:30:00.000Z");
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
  });

  it("handles ISO timestamps", () => {
    const result = toDatetimeLocalValue("2026-08-08T12:30:00Z");
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
  });
});

describe("fromDatetimeLocalValue", () => {
  it("returns null for empty input", () => {
    expect(fromDatetimeLocalValue("")).toBeNull();
  });

  it("converts a datetime-local value to ISO string", () => {
    const result = fromDatetimeLocalValue("2026-08-08T12:30:00");
    expect(result).toBeTruthy();
    expect(result).toMatch(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
  });
});
