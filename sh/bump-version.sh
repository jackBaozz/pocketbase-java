#!/usr/bin/env bash
#
# bump-version.sh — Synchronize the project version across all version metadata.
#
# Usage:
#   ./sh/bump-version.sh 0.3.5
#   ./sh/bump-version.sh v0.4.0     # 'v' prefix is stripped automatically
#
# Files updated:
#   pom.xml                                                       — Maven <revision> property
#   UI/package.json, UI/package-lock.json                         — npm package metadata
#   UI/src/App.tsx                                                — footer "PocketBase v…"
#   src/main/java/.../client/PocketBaseClient.java                — SDK User-Agent
#   README.md, README_zh.md                                       — runnable JAR examples
#
set -euo pipefail

# ── Resolve version ───────────────────────────────────────────────────────────

if [ $# -ne 1 ]; then
  echo "Usage: ./sh/bump-version.sh <version>"
  echo "  e.g. ./sh/bump-version.sh 0.3.5"
  echo "       ./sh/bump-version.sh v0.4.0"
  exit 1
fi

RAW_VERSION="$1"
# Strip optional leading 'v'
NEW_VERSION="${RAW_VERSION#v}"

if [[ ! "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9._-]+)?$ ]]; then
  echo "Error: '$NEW_VERSION' does not look like a semver string (e.g. 0.3.5)"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# ── Helpers ───────────────────────────────────────────────────────────────────

fail() {
  echo "Error: $*" >&2
  exit 1
}

require_file() {
  [ -f "$1" ] || fail "required version file is missing: $1"
}

require_exact_count() {
  local file="$1"
  local label="$2"
  local actual="$3"
  local expected="$4"
  [ "$actual" -eq "$expected" ] || fail "$file: expected $expected $label match(es), found $actual"
}

require_at_least_one() {
  local file="$1"
  local label="$2"
  local actual="$3"
  [ "$actual" -gt 0 ] || fail "$file: expected at least one $label match"
}

read_maven_revision() {
  perl -0ne '
    if (m{<revision>\s*([^<[:space:]]+)\s*</revision>}) {
      print "$1\n";
      exit;
    }
  ' pom.xml
}

for version_file in \
  pom.xml \
  UI/package.json \
  UI/package-lock.json \
  UI/src/App.tsx \
  src/main/java/io/github/jackbaozz/pocketbase/client/PocketBaseClient.java \
  README.md \
  README_zh.md; do
  require_file "$version_file"
done

# Maven's <revision> property is the canonical project version. Do not read the
# project's <version> element: it intentionally contains the literal ${revision}.
CURRENT_VERSION="$(read_maven_revision)"
[ -n "$CURRENT_VERSION" ] || fail "could not detect <revision> from pom.xml"

if [[ ! "$CURRENT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9._-]+)?$ ]]; then
  fail "pom.xml contains an invalid Maven revision: '$CURRENT_VERSION'"
fi

if [ "$CURRENT_VERSION" = "$NEW_VERSION" ]; then
  echo "Maven revision is already $NEW_VERSION; synchronizing the remaining version metadata."
else
  echo "Bumping version: $CURRENT_VERSION → $NEW_VERSION"
fi
echo ""

# ── Update files ──────────────────────────────────────────────────────────────

# 1. Maven revision property. The project <version> must remain ${revision}.
pom_matches="$(CURRENT_VERSION="$CURRENT_VERSION" perl -0ne '
  my $current = $ENV{CURRENT_VERSION};
  my $matches = () = /<revision>\s*\Q$current\E\s*<\/revision>/g;
  print $matches;
' pom.xml)"
require_exact_count "pom.xml" "<revision>" "$pom_matches" 1
if [ "$CURRENT_VERSION" != "$NEW_VERSION" ]; then
  CURRENT_VERSION="$CURRENT_VERSION" NEW_VERSION="$NEW_VERSION" perl -0pi -e '
    my $current = $ENV{CURRENT_VERSION};
    my $new = $ENV{NEW_VERSION};
    s{(<revision>\s*)\Q$current\E(\s*</revision>)}{$1 . $new . $2}e;
  ' pom.xml
fi
echo "  ✓  pom.xml — Maven <revision>"

# 2. npm package metadata. package-lock is updated structurally so dependency
# versions that happen to equal the application version are never touched.
package_matches="$(perl -0ne '
  my $matches = () = /\A\s*\{\s*"name"\s*:\s*"pocketbase-java-admin-ui"\s*,\s*"private"\s*:\s*(?:true|false)\s*,\s*"version"\s*:\s*"[^"]+"/g;
  print $matches;
' UI/package.json)"
require_exact_count "UI/package.json" "root package version" "$package_matches" 1
NEW_VERSION="$NEW_VERSION" perl -0pi -e '
  my $new = $ENV{NEW_VERSION};
  s{\A(\s*\{\s*"name"\s*:\s*"pocketbase-java-admin-ui"\s*,\s*"private"\s*:\s*(?:true|false)\s*,\s*"version"\s*:\s*")[^"]+(")}{$1 . $new . $2}e;
' UI/package.json
echo "  ✓  UI/package.json — npm package version"

lock_root_matches="$(perl -0ne '
  my $matches = () = /\A\s*\{\s*"name"\s*:\s*"pocketbase-java-admin-ui"\s*,\s*"version"\s*:\s*"[^"]+"/g;
  print $matches;
' UI/package-lock.json)"
lock_package_matches="$(perl -0ne '
  my $matches = () = /"packages"\s*:\s*\{\s*""\s*:\s*\{\s*"name"\s*:\s*"pocketbase-java-admin-ui"\s*,\s*"version"\s*:\s*"[^"]+"/g;
  print $matches;
' UI/package-lock.json)"
require_exact_count "UI/package-lock.json" "root lockfile version" "$lock_root_matches" 1
require_exact_count "UI/package-lock.json" "root package lockfile version" "$lock_package_matches" 1
NEW_VERSION="$NEW_VERSION" perl -0pi -e '
  my $new = $ENV{NEW_VERSION};
  s{\A(\s*\{\s*"name"\s*:\s*"pocketbase-java-admin-ui"\s*,\s*"version"\s*:\s*")[^"]+(")}{$1 . $new . $2}e;
  s{("packages"\s*:\s*\{\s*""\s*:\s*\{\s*"name"\s*:\s*"pocketbase-java-admin-ui"\s*,\s*"version"\s*:\s*")[^"]+(")}{$1 . $new . $2}e;
' UI/package-lock.json
echo "  ✓  UI/package-lock.json — npm lockfile metadata (2 occurrences)"

# 3. Admin UI footer version(s).
footer_matches="$(perl -0ne '
  my $matches = () = /PocketBase v[0-9]+(?:\.[0-9]+){2}(?:[.-][A-Za-z0-9._-]+)?/g;
  print $matches;
' UI/src/App.tsx)"
require_at_least_one "UI/src/App.tsx" "footer version" "$footer_matches"
NEW_VERSION="$NEW_VERSION" perl -0pi -e '
  s{PocketBase v[0-9]+(?:\.[0-9]+){2}(?:[.-][A-Za-z0-9._-]+)?}{"PocketBase v" . $ENV{NEW_VERSION}}ge;
' UI/src/App.tsx
echo "  ✓  UI/src/App.tsx — $footer_matches footer version occurrence(s)"

# 4. Java SDK User-Agent.
user_agent_matches="$(perl -0ne '
  my $matches = () = /\.header\(\s*"User-Agent"\s*,\s*"pocketbase-java\/[^"]+"\s*\)/g;
  print $matches;
' src/main/java/io/github/jackbaozz/pocketbase/client/PocketBaseClient.java)"
require_exact_count "src/main/java/io/github/jackbaozz/pocketbase/client/PocketBaseClient.java" "SDK User-Agent" "$user_agent_matches" 1
NEW_VERSION="$NEW_VERSION" perl -0pi -e '
  s{(\.header\(\s*"User-Agent"\s*,\s*"pocketbase-java\/)[^"]+("\s*\))}{$1 . $ENV{NEW_VERSION} . $2}e;
' src/main/java/io/github/jackbaozz/pocketbase/client/PocketBaseClient.java
echo "  ✓  PocketBaseClient.java — SDK User-Agent"

# 5. Runnable JAR examples in both READMEs.
for readme in README.md README_zh.md; do
  jar_matches="$(perl -0ne '
    my $matches = () = /pocketbase-java-[0-9]+(?:\.[0-9]+){2}(?:[.-][A-Za-z0-9._-]+)?-all\.jar/g;
    print $matches;
  ' "$readme")"
  require_at_least_one "$readme" "JAR filename" "$jar_matches"
  NEW_VERSION="$NEW_VERSION" perl -0pi -e '
    s{pocketbase-java-[0-9]+(?:\.[0-9]+){2}(?:[.-][A-Za-z0-9._-]+)?-all\.jar}{"pocketbase-java-" . $ENV{NEW_VERSION} . "-all.jar"}ge;
  ' "$readme"
  echo "  ✓  $readme — $jar_matches JAR filename occurrence(s)"
done

# Verify that the canonical Maven property was updated before reporting success.
[ "$(read_maven_revision)" = "$NEW_VERSION" ] || fail "pom.xml <revision> was not updated to $NEW_VERSION"

echo ""
echo "Done. Version metadata synchronized to $NEW_VERSION."
echo ""
echo "Next steps:"
echo "  cd UI && npm run build   # rebuild Admin UI with new version in footer"
echo "  mvn clean package         # rebuild JAR with new version"
