import { describe, expect, it } from "vitest";
import { wordAt, filterCompletions, buildRuleCompletions } from "./CodeEditor";

const WORD_CHARS = /[\p{Alphabetic}\p{Number}_@:."'{}]/u;

describe("CodeEditor autocomplete logic", () => {
  it("extracts word and cursor prefix correctly", () => {
    const text = "@request.auth.id > 10";
    // Caret right after '@request.au' (length is 11)
    const caret = 11;
    const match = wordAt(text, caret, WORD_CHARS);
    expect(match.word).toBe("@request.auth.id");
    expect(match.prefix).toBe("@request.au");
    expect(match.start).toBe(0);
    expect(match.end).toBe(16);

    // Caret at the beginning of the word
    const startMatch = wordAt(text, 0, WORD_CHARS);
    expect(startMatch.word).toBe("@request.auth.id");
    expect(startMatch.prefix).toBe("");

    // Caret at the end of the word
    const endMatch = wordAt(text, 16, WORD_CHARS);
    expect(endMatch.word).toBe("@request.auth.id");
    expect(endMatch.prefix).toBe("@request.auth.id");
  });

  it("filters and ranks completions with prefix matches first", () => {
    const candidates = [
      "author.id",
      "author.title",
      "@request.auth.id",
      "@request.auth.collectionId",
      "title",
      "subtitle"
    ];

    const results = filterCompletions(candidates, "auth");
    // "author.id" and "author.title" start with "auth" -> should appear before substring matches
    expect(results[0]).toBe("author.id");
    expect(results[1]).toBe("author.title");
    expect(results).toContain("@request.auth.id");
    expect(results).toContain("@request.auth.collectionId");
    expect(results).not.toContain("title");
  });

  it("excludes candidate if candidate matches query exactly", () => {
    const candidates = ["id", "created", "updated"];
    const results = filterCompletions(candidates, "id");
    expect(results).not.toContain("id");
  });

  it("generates rule completions with collection fields and modifiers", () => {
    const completions = buildRuleCompletions({
      name: "posts",
      type: "base",
      fields: [
        { name: "title", type: "text" },
        { name: "tags", type: "select" }
      ]
    });

    expect(completions).toContain("title");
    expect(completions).toContain("title:lower");
    expect(completions).toContain("tags:each");
    expect(completions).toContain("tags:length");
    expect(completions).toContain("@request.auth.id");
    expect(completions).toContain("@request.body.title:isset");
    expect(completions).toContain("@request.body.title:changed");
  });
});
