#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "$0")/.." && pwd)"
mkdir -p "$PROJECT_DIR/.test-build"
java -m jdk.compiler/com.sun.tools.javac.Main -d "$PROJECT_DIR/.test-build" "$PROJECT_DIR/scripts/CheckSyntax.java"
java -cp "$PROJECT_DIR/.test-build" CheckSyntax "$PROJECT_DIR/app/src/main/java"
python3 - "$PROJECT_DIR" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
p=pathlib.Path(sys.argv[1]); files=list((p/'app/src/main').rglob('*.xml'))
for f in files: ET.parse(f)
print(f'XML parsed: {len(files)} files')
PY
