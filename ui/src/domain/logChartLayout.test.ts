import { describe, expect, it } from "vitest";
import { logChartAxisWidth } from "./logChartLayout";

describe("logs chart axis", () => {
  it("retains the compact baseline for small values", () => {
    expect(logChartAxisWidth([])).toBe(44);
    expect(logChartAxisWidth(["0", "100"])).toBe(44);
  });
  it("reserves space for grouping separators and six-digit or larger values", () => {
    expect(logChartAxisWidth(["0", "100,000"])).toBe(70);
    expect(logChartAxisWidth(["0", "1,000,000,000"])).toBeGreaterThan(100);
  });
});
