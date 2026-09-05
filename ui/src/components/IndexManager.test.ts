import { describe, expect, it } from "vitest";
import { parseIndex, buildIndex, splitIdentifierParts, unquoteIdentifier } from "./IndexManager";

describe("IndexManager utils", () => {
  it("splits identifiers by dots outside quotes", () => {
    expect(splitIdentifierParts("")).toEqual([]);
    expect(splitIdentifierParts("posts")).toEqual(["posts"]);
    expect(splitIdentifierParts("schema.posts")).toEqual(["schema", "posts"]);
    expect(splitIdentifierParts("a.b.c")).toEqual(["a", "b", "c"]);
    expect(splitIdentifierParts('"schema.name".[index.name]')).toEqual(['"schema.name"', "[index.name]"]);
    expect(splitIdentifierParts("`a.b.c`")).toEqual(["`a.b.c`"]);
    expect(splitIdentifierParts('"unclosed')).toEqual([]);
  });

  it("unquotes identifiers", () => {
    expect(unquoteIdentifier("posts")).toBe("posts");
    expect(unquoteIdentifier("`posts`")).toBe("posts");
    expect(unquoteIdentifier('"posts"')).toBe("posts");
    expect(unquoteIdentifier("'posts'")).toBe("posts");
    expect(unquoteIdentifier("[posts]")).toBe("posts");
    expect(unquoteIdentifier('""')).toBe("");
  });

  it("parses valid index expressions", () => {
    const simple = parseIndex("CREATE INDEX idx_title ON posts (title)");
    expect(simple.indexName).toBe("idx_title");
    expect(simple.tableName).toBe("posts");
    expect(simple.columns).toEqual(["title"]);
    expect(simple.unique).toBe(false);
    expect(simple.optional).toBe(false);

    const complex = parseIndex(
      'CREATE UNIQUE INDEX IF NOT EXISTS "schemaname".[indexname] on \'posts\' (col0, `col1`) where status = 1'
    );
    expect(complex.unique).toBe(true);
    expect(complex.optional).toBe(true);
    expect(complex.indexName).toBe("indexname");
    expect(complex.tableName).toBe("posts");
    expect(complex.columns).toEqual(["col0", "col1"]);
    expect(complex.where).toBe("status = 1");

    const quotedDotted = parseIndex("CREATE INDEX `a.b.c` ON posts (title)");
    expect(quotedDotted.indexName).toBe("a.b.c");
  });

  it("rejects invalid multi-part names instead of silently converting them", () => {
    const invalidMultiPart = parseIndex("CREATE INDEX a.b.c ON posts (title)");
    expect(invalidMultiPart.indexName).toBe("");
    expect(invalidMultiPart.tableName).toBe("posts");

    const noName = parseIndex("CREATE INDEX ON ()");
    expect(noName.indexName).toBe("");
    expect(noName.tableName).toBe("");
    expect(noName.columns).toEqual([]);

    const invalidMultiNoTable = parseIndex("CREATE INDEX a.b.c ON ()");
    expect(invalidMultiNoTable.indexName).toBe("");
    expect(invalidMultiNoTable.tableName).toBe("");
    expect(invalidMultiNoTable.columns).toEqual([]);

    const emptySchemaTable = parseIndex("CREATE INDEX idx_name ON .posts (title)");
    expect(emptySchemaTable.tableName).toBe("");

    const emptySchemaIndex = parseIndex("CREATE INDEX .idx_name ON posts (title)");
    expect(emptySchemaIndex.indexName).toBe("");
  });

  it("builds index SQL with proper escaping", () => {
    const sql = buildIndex({
      unique: true,
      optional: true,
      indexName: "idx_custom",
      tableName: "posts",
      columns: ["title", "lower(slug)"],
      where: "active = true"
    });
    expect(sql).toBe("CREATE UNIQUE INDEX IF NOT EXISTS `idx_custom` ON `posts` (`title`, lower(slug)) WHERE active = true");
  });
});
