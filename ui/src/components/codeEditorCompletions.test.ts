import { describe, expect, it } from "vitest";
import { createElement } from "react";
import { CompletionRequests, filterCompletions } from "./codeEditorCompletions";
import type { CompletionItem } from "./codeEditorCompletions";

function deferred() {
  let resolve!: (items: CompletionItem[]) => void;
  const promise = new Promise<CompletionItem[]>((done) => { resolve = done; });
  return { promise, resolve };
}

describe("CodeEditor completion providers", () => {
  it("supports arrays, synchronous providers and promises", async () => {
    const requests = new CompletionRequests();
    expect(await requests.load(["title", "subtitle", "id"], "tit")).toEqual(["title", "subtitle"]);
    expect(await requests.load((prefix) => [prefix + "le"], "tit")).toEqual(["title"]);
    expect(await requests.load(async (prefix) => [prefix + "le"], "tit")).toEqual(["title"]);
  });

  it("preserves custom labels and uses values for insertion and deduplication", async () => {
    const title = { value: "title", label: "Post title" };
    expect(filterCompletions([title], "post")).toEqual([title]);
    expect(await new CompletionRequests().load(() => [title, "title", ""], "tit")).toEqual([title]);
  });

  it("matches labeled array candidates by insertion value regardless of label text", async () => {
    const userId = { value: "@request.auth.id", label: "Current user ID" };
    expect(filterCompletions([userId], "@request")).toEqual([userId]);
    expect(filterCompletions([userId], "@REQUEST.AUTH")).toEqual([userId]);
    expect(filterCompletions([userId], "auth")).toEqual([userId]);
    expect(await new CompletionRequests().load([userId], "@request")).toEqual([userId]);
  });

  it("ranks prefix matches from either value or label before substring matches", () => {
    const valuePrefix = { value: "authId", label: "Current user ID" };
    const labelPrefix = { value: "user", label: "Authenticated user" };
    const bothPrefixes = { value: "authEmail", label: "Authentication email" };
    const valueSubstring = { value: "@request.auth.id", label: "User ID" };
    const labelSubstring = { value: "id", label: "Current auth ID" };
    expect(filterCompletions([
      valueSubstring, labelSubstring, valuePrefix, bothPrefixes, labelPrefix,
    ], "auth")).toEqual([
      labelPrefix, valuePrefix, bothPrefixes, labelSubstring, valueSubstring,
    ]);
  });

  it("keeps value deduplication and excludes already completed values with labels", () => {
    const userId = { value: "@request.auth.id", label: "Current user ID" };
    expect(filterCompletions([
      userId, { ...userId, label: "Signed-in user ID" }, userId.value,
      { value: "", label: "@request" },
    ], "@request")).toEqual([userId]);
    expect(filterCompletions([userId], "@REQUEST.AUTH.ID")).toEqual([]);
    expect(filterCompletions([userId], "")).toEqual([]);
    expect(filterCompletions([userId], "unknown")).toEqual([]);
  });

  it("matches insertion values with empty or React node labels", () => {
    const emptyLabel = { value: "@request.auth.id", label: "" };
    const nodeLabel = { value: "@request.auth.email", label: createElement("span", null, "User email") };
    expect(filterCompletions([nodeLabel, emptyLabel], "@request")).toEqual([emptyLabel, nodeLabel]);
  });

  it("ignores an old request that resolves after the latest one", async () => {
    const requests = new CompletionRequests();
    const old = deferred();
    const pending = requests.load(() => old.promise, "t");
    expect(await requests.load(async () => ["updated"], "u")).toEqual(["updated"]);
    old.resolve(["title"]);
    expect(await pending).toBeNull();
  });

  it("invalidates a pending request immediately when editing or closing", async () => {
    const requests = new CompletionRequests();
    const source = deferred();
    const pending = requests.load(() => source.promise, "t");
    requests.invalidate();
    source.resolve(["title"]);
    expect(await pending).toBeNull();
  });

  it("contains provider failures and allows subsequent completion requests", async () => {
    const requests = new CompletionRequests();
    expect(await requests.load(() => { throw new Error("failed"); }, "t")).toBeNull();
    expect(await requests.load(async () => { throw new Error("failed"); }, "t")).toBeNull();
    expect(await requests.load(() => undefined, "t")).toEqual([]);
    expect(await requests.load(async () => ["title"], "t")).toEqual(["title"]);
  });

  it("limits provider output without altering provider ranking", async () => {
    const items = Array.from({ length: 100 }, (_, i) => `field${i}`);
    expect(await new CompletionRequests().load(async () => items, "field")).toEqual(items.slice(0, 30));
  });
});
