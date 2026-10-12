#!/usr/bin/env python3
"""Static MDI icon contract check for the GITLS source tree."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app" / "src" / "main" / "java"
REGISTRY = SRC / "com" / "example" / "aidetest" / "core" / "MdiGlyphs.kt"

registry_text = REGISTRY.read_text(encoding="utf-8")
registered = set(re.findall(r'"([a-z0-9]+(?:-[a-z0-9]+)+|[a-z0-9]+)"\s+to\s+mdi\(', registry_text))
registered.discard("mdi")

refs: list[tuple[str, int, str]] = []
# Direct MdiIconView calls are the highest-signal contract.
for path in SRC.rglob("*.kt"):
    text = path.read_text(encoding="utf-8", errors="ignore")
    for m in re.finditer(r'setIconName\(\s*"([a-z0-9-]+)"\s*\)', text):
        refs.append((str(path.relative_to(ROOT)), text[:m.start()].count("\n") + 1, m.group(1)))

# Explicit icon-like strings passed to common UI helper calls.
patterns = [
    r'settingRowClickable\([^\n]*?"([a-z0-9]+(?:-[a-z0-9]+)+)"\s*(?:\)|\{)',
    r'HomeCategory\([^\n]*?,\s*"([a-z0-9]+(?:-[a-z0-9]+)+)"\s*,',
    r'\.setIconName\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)',
]
for path in SRC.rglob("*.kt"):
    text = path.read_text(encoding="utf-8", errors="ignore")
    for pattern in patterns[:2]:
        for m in re.finditer(pattern, text):
            name = m.group(1)
            refs.append((str(path.relative_to(ROOT)), text[:m.start()].count("\n") + 1, name))

# Remove dynamic variable matches and deduplicate.
literals = {(f, ln, n) for f, ln, n in refs if "_" not in n and n not in {"icon", "name"}}
unknown = sorted({n for _, _, n in literals if n not in registered})

if unknown:
    print("::error::Undefined MDI icon names detected:")
    for name in unknown:
        for f, ln, n in sorted(literals):
            if n == name:
                print(f"::error file={f},line={ln}::MDI icon '{name}' is not registered in core/MdiGlyphs.kt")
    raise SystemExit(1)

print(f"MDI icon contract: OK ({len(registered)} registered glyphs, {len(literals)} literal references checked)")
