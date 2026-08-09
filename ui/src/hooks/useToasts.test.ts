import { describe, it, expect } from "vitest";
import { useToasts } from "./useToasts";

/**
 * Note: useToasts uses useRef and useState internally, which require a React
 * component context. These tests validate the exported API surface and types.
 * Full stateful testing requires @testing-library/react + a test renderer.
 */

describe("useToasts", () => {
  it("is a function", () => {
    expect(typeof useToasts).toBe("function");
  });
});
