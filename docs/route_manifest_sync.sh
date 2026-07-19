#!/usr/bin/env bash
set -euo pipefail

BASELINE_VERSION="v0.39.7"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "PocketBase Java route parity check (${BASELINE_VERSION})"
cd "${ROOT_DIR}"

mvn -gs settings.xml -s settings.xml -Dtest=RouteConformanceTest test

echo "Route manifest and registered route checks passed for ${BASELINE_VERSION}."
