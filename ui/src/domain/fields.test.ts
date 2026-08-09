import { describe, it, expect } from "vitest";
import { fieldMultiplicity, fieldDefault } from "./fields";
import type { FieldSchema } from "../types/api";

describe("fieldMultiplicity", () => {
  it("returns 1 for a plain text field with no multiplicity options", () => {
    expect(fieldMultiplicity({ name: "title", type: "text" })).toBe(1);
  });

  it("resolves maxSelect for relation fields (relation semantics first)", () => {
    const field: FieldSchema = {
      name: "author",
      type: "relation",
      maxSelect: 3,
      maxFiles: 5, // should be ignored for relations
    };
    expect(fieldMultiplicity(field)).toBe(3);
  });

  it("resolves maxFiles for file fields (file semantics first)", () => {
    const field: FieldSchema = {
      name: "avatar",
      type: "file",
      maxFiles: 2,
      maxSelect: 5, // should be ignored for files
    };
    expect(fieldMultiplicity(field)).toBe(2);
  });

  it("falls back to options.maxSelect for legacy relation schemas", () => {
    const field: FieldSchema = {
      name: "tags",
      type: "relation",
      options: { maxSelect: 4 },
    };
    expect(fieldMultiplicity(field)).toBe(4);
  });

  it("falls back to options.maxFiles for legacy file schemas", () => {
    const field: FieldSchema = {
      name: "doc",
      type: "file",
      options: { maxFiles: 3 },
    };
    expect(fieldMultiplicity(field)).toBe(3);
  });

  it("always returns at least 1 even if all values are 0 or undefined", () => {
    expect(fieldMultiplicity({ name: "x", type: "text", maxSelect: 0 })).toBe(1);
    expect(fieldMultiplicity({ name: "x", type: "file", maxFiles: 0 })).toBe(1);
  });

  it("treats FileField (no type) with file semantics", () => {
    // No `type` → file semantics: maxFiles is preferred.
    expect(fieldMultiplicity({ name: "img", maxFiles: 3 })).toBe(3);
    // maxSelect is used as fallback when maxFiles is absent.
    expect(fieldMultiplicity({ name: "img", maxSelect: 3 })).toBe(3);
  });
});

describe("fieldDefault", () => {
  it("returns false for bool", () => {
    expect(fieldDefault({ name: "active", type: "bool" })).toBe(false);
  });

  it("returns 0 for number", () => {
    expect(fieldDefault({ name: "count", type: "number" })).toBe(0);
  });

  it("returns null for json", () => {
    expect(fieldDefault({ name: "meta", type: "json" })).toBe(null);
  });

  it("returns [] for multi-relation", () => {
    expect(fieldDefault({ name: "tags", type: "relation", maxSelect: 3 })).toEqual([]);
  });

  it("returns empty string for single relation", () => {
    expect(fieldDefault({ name: "author", type: "relation", maxSelect: 1 })).toBe("");
  });

  it("returns [] for multi-select", () => {
    expect(fieldDefault({ name: "colors", type: "select", maxSelect: 3 })).toEqual([]);
  });

  it("returns empty string for text", () => {
    expect(fieldDefault({ name: "title", type: "text" })).toBe("");
  });
});
