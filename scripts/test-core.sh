#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "$0")/.." && pwd)"
mkdir -p "$PROJECT_DIR/.test-build"
# Works with a JDK whose javac launcher is missing but jdk.compiler is installed.
java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -encoding UTF-8 -d "$PROJECT_DIR/.test-build" \
  "$PROJECT_DIR/app/src/main/java/com/qingji/app/core/LedgerCore.java" "$PROJECT_DIR/tests/CoreTests.java" \
  "$PROJECT_DIR/app/src/main/java/com/qingji/app/update/UpdatePolicy.java" "$PROJECT_DIR/tests/UpdateTests.java" \
  "$PROJECT_DIR/app/src/main/java/com/qingji/app/core/PageReadPolicy.java" "$PROJECT_DIR/tests/PageReadTests.java"
java -cp "$PROJECT_DIR/.test-build" CoreTests
java -cp "$PROJECT_DIR/.test-build" UpdateTests
java -cp "$PROJECT_DIR/.test-build" PageReadTests
