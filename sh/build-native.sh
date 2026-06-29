#!/bin/bash
set -e

# Resolve the project root directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

cd "$PROJECT_ROOT"

echo "===================================================="
echo "PocketBase Java - GraalVM Native Image Compiler"
echo "===================================================="

# Check if mise is available
if command -v mise &> /dev/null; then
    echo "Checking mise Java version..."
    # Check if 25-graalvm is available in mise ls
    if mise ls java | grep -q "25-graalvm"; then
        echo "Found 25-graalvm in mise. Running native compilation..."
        echo "Command: mise exec java@25-graalvm -- mvn -Pnative clean package -DskipTests $*"
        mise exec java@25-graalvm -- mvn -Pnative clean package -DskipTests "$@"
    else
        echo "⚠️  Warning: java@25-graalvm is not found/configured in mise."
        echo "List of available java versions in mise:"
        mise ls java
        echo ""
        echo "Trying to run compilation using system java..."
        mvn -Pnative clean package -DskipTests "$@"
    fi
else
    echo "⚠️  Warning: 'mise' command not found."
    echo "Checking current Java version..."
    if java -version 2>&1 | grep -qi "graalvm"; then
        echo "Current system Java is GraalVM. Proceeding..."
        mvn -Pnative clean package -DskipTests "$@"
    else
        echo "❌ Error: GraalVM is required for native compilation."
        echo "Current Java version:"
        java -version
        echo "Please install and configure GraalVM (e.g. 25-graalvm via mise) and try again."
        exit 1
    fi
fi

echo "===================================================="
echo "Build complete! Check target/ directory for binary."
echo "===================================================="
