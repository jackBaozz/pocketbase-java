#!/usr/bin/env bash
#
# bump-version.sh — Update the project version number across all files.
#
# Usage:
#   sh/bump-version.sh 0.3.3
#   sh/bump-version.sh v0.3.3     # 'v' prefix is stripped automatically
#
# Files updated:
#   pom.xml                  — Maven <version>
#   UI/package.json          — npm "version"
#   UI/src/App.tsx           — footer "PocketBase v…" (×2)
#   README.md                — jar filename in run command
#   README_zh.md             — jar filename in run command
#
set -euo pipefail

# ── Resolve version ───────────────────────────────────────────────────────────

if [ $# -ne 1 ]; then
  echo "Usage: sh/bump-version.sh <version>"
  echo "  e.g. sh/bump-version.sh 0.3.3"
  echo "       sh/bump-version.sh v0.4.0"
  exit 1
fi

RAW_VERSION="$1"
# Strip optional leading 'v'
NEW_VERSION="${RAW_VERSION#v}"

if [[ ! "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9._-]+)?$ ]]; then
  echo "Error: '$NEW_VERSION' does not look like a semver string (e.g. 0.3.3)"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Detect current version from pom.xml
OLD_VERSION=$(grep -m1 '<version>' pom.xml | sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')

if [ -z "$OLD_VERSION" ]; then
  echo "Error: could not detect current version from pom.xml"
  exit 1
fi

if [ "$OLD_VERSION" = "$NEW_VERSION" ]; then
  echo "Current version is already $NEW_VERSION — nothing to do."
  exit 0
fi

echo "Bumping version: $OLD_VERSION → $NEW_VERSION"
echo ""

# ── Helper ────────────────────────────────────────────────────────────────────

# replace <file> <old> <new> — updates a single occurrence, prints a checkmark.
bump() {
  local file="$1"
  local old="$2"
  local new="$3"

  if [ ! -f "$file" ]; then
    echo "  ⚠  $file — not found, skipped"
    return
  fi

  local count
  count=$(grep -F -c "$old" "$file" 2>/dev/null || true)

  if [ "$count" -eq 0 ]; then
    echo "  ⊘  $file — '$old' not found, skipped"
    return
  fi

  # Use perl for portable in-place replacement (works on macOS + Linux).
  perl -pi -e "s/\Q$old\E/$new/g" "$file"
  echo "  ✓  $file — $count occurrence(s) updated"
}

# ── Update files ──────────────────────────────────────────────────────────────

# 1. Maven (pom.xml) — <version>0.3.2</version>
bump "pom.xml" "$OLD_VERSION" "$NEW_VERSION"

# 2. Admin UI package.json — "version": "0.3.2"
bump "UI/package.json" "$OLD_VERSION" "$NEW_VERSION"

# 3. App.tsx — footer credits "PocketBase v0.3.2" (2 occurrences)
bump "UI/src/App.tsx" "PocketBase v$OLD_VERSION" "PocketBase v$NEW_VERSION"

# 4. README.md — jar filename "pocketbase-java-0.3.2-all.jar"
bump "README.md" "pocketbase-java-$OLD_VERSION-all.jar" "pocketbase-java-$NEW_VERSION-all.jar"

# 5. README_zh.md — same jar filename
bump "README_zh.md" "pocketbase-java-$OLD_VERSION-all.jar" "pocketbase-java-$NEW_VERSION-all.jar"

echo ""
echo "Done. Version bumped from $OLD_VERSION to $NEW_VERSION."
echo ""
echo "Next steps:"
echo "  cd UI && npm run build   # rebuild Admin UI with new version in footer"
echo "  mvn clean package         # rebuild JAR with new version"
