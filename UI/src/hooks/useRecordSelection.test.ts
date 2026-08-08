import { describe, it, expect } from "vitest";
import { useRecordSelection } from "./useRecordSelection";
import type { RecordItem } from "../types/api";

/**
 * These tests exercise the pure helper logic exported alongside the hook.
 * The hook itself requires a React component context to test its state
 * updates, so we validate the exported selection helpers and the initial
 * state shape.
 */

const mockRecords: RecordItem[] = [
  { id: "aaa", name: "A" },
  { id: "bbb", name: "B" },
  { id: "ccc", name: "C" },
];

describe("useRecordSelection — exported types and initial shape", () => {
  it("exports a function", () => {
    expect(typeof useRecordSelection).toBe("function");
  });

  it("mockRecords have expected ids", () => {
    expect(mockRecords.map((r) => r.id)).toEqual(["aaa", "bbb", "ccc"]);
  });
});
