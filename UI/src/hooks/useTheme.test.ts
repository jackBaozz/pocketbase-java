import { describe, it, expect } from "vitest";
import { resolveThemeMode } from "./useTheme";

/**
 * These tests validate pure logic that doesn't require browser globals.
 * readThemeMode (uses localStorage) and useTheme (uses window/React) are
 * validated via the build + manual browser testing.
 */

describe("resolveThemeMode", () => {
  it("returns 'light' for 'light'", () => {
    expect(resolveThemeMode("light")).toBe("light");
  });

  it("returns 'dark' for 'dark'", () => {
    expect(resolveThemeMode("dark")).toBe("dark");
  });
});
