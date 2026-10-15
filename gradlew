#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
GRADLE_VERSION=8.13
TOOLS_DIR="$PROJECT_DIR/.tools"
GRADLE_DIR="$TOOLS_DIR/gradle-$GRADLE_VERSION"
if [[ ! -x "$GRADLE_DIR/bin/gradle" ]]; then
  mkdir -p "$TOOLS_DIR"
  curl --fail --location --retry 2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TOOLS_DIR/gradle.zip"
  curl --fail --location "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip.sha256" -o "$TOOLS_DIR/gradle.sha256"
  python3 - "$TOOLS_DIR" <<'PY'
import hashlib, pathlib, sys, zipfile
p = pathlib.Path(sys.argv[1])
actual = hashlib.sha256((p / 'gradle.zip').read_bytes()).hexdigest()
expected = (p / 'gradle.sha256').read_text().strip()
if actual != expected:
    raise SystemExit('Gradle checksum mismatch')
with zipfile.ZipFile(p / 'gradle.zip') as z:
    z.extractall(p)
PY
  chmod +x "$GRADLE_DIR/bin/gradle"
fi
exec "$GRADLE_DIR/bin/gradle" -p "$PROJECT_DIR" "$@"
