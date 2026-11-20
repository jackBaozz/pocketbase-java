import { describe, expect, it } from "vitest";
import { reorderOAuthProviders } from "./oauthProviders";

describe("OAuth2 provider ordering", () => {
  const names = ["github", "google", "oidc"];
  it("moves providers in either direction without mutating the input", () => {
    expect(reorderOAuthProviders(names, "oidc", "github")).toEqual(["oidc", "github", "google"]);
    expect(reorderOAuthProviders(names, "github", "oidc")).toEqual(["google", "oidc", "github"]);
    expect(names).toEqual(["github", "google", "oidc"]);
  });
  it("ignores stale or external drag sources and self drops", () => {
    for (const [source, target] of [["missing", "github"], ["github", "missing"], ["github", "github"]]) {
      expect(reorderOAuthProviders(names, source, target)).toEqual(names);
    }
  });
  it("handles empty and single-provider selections", () => {
    expect(reorderOAuthProviders([], "github", "google")).toEqual([]);
    expect(reorderOAuthProviders(["github"], "github", "github")).toEqual(["github"]);
  });
});
