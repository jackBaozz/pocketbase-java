#!/bin/bash
set -e

# Checks our embedded server routes against a defined baseline

# Target version we are tracking parity with
BASELINE_VERSION="v0.22.x"

echo "PocketBase Java - Route Parity Checker"
echo "Targeting PocketBase baseline: ${BASELINE_VERSION}"
echo "----------------------------------------"

# A basic script to simulate a manifest sync or check
echo "Checking implemented routes..."
if grep -q "/api/collections" README.md; then
    echo "✅ Collections API documented"
else
    echo "❌ Collections API missing from documentation"
    exit 1
fi

if grep -q "/api/realtime" README.md; then
    echo "✅ Realtime API documented"
else
    echo "❌ Realtime API missing from documentation"
    exit 1
fi

if grep -q "/api/batch" README.md; then
    echo "✅ Batch API documented"
else
    echo "❌ Batch API missing from documentation"
    exit 1
fi

echo "----------------------------------------"
echo "Sync check completed successfully!"
