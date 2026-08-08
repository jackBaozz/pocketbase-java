import { describe, it, expect } from "vitest";
import { isSystemCollection } from "./useCollections";
import type { CollectionSchema } from "../types/api";

describe("isSystemCollection", () => {
  it("returns true for collections with system flag", () => {
    const col = { id: "pbc_1", name: "_superusers", type: "auth", system: true } as CollectionSchema;
    expect(isSystemCollection(col)).toBe(true);
  });

  it("returns true for collections starting with underscore even without flag", () => {
    const col = { id: "pbc_2", name: "_externalAuths", type: "auth", system: false } as CollectionSchema;
    expect(isSystemCollection(col)).toBe(true);
  });

  it("returns false for user-created base collections", () => {
    const col = { id: "pbc_3", name: "posts", type: "base", system: false } as CollectionSchema;
    expect(isSystemCollection(col)).toBe(false);
  });

  it("returns false for user-created auth collections", () => {
    const col = { id: "pbc_4", name: "users", type: "auth", system: false } as CollectionSchema;
    expect(isSystemCollection(col)).toBe(false);
  });
});
