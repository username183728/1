#!/usr/bin/env python3
"""Guard: property dideklarasikan SETELAH blok init {} di class yang sama.

Kotlin menjalankan initializer property dan blok init sesuai urutan penulisan. Jika init
memakai property yang ditulis di bawahnya, nilainya masih null saat runtime
(contoh nyata: VideoEditorUi.railItems → NullPointerException saat membuka Video Editor).
Script ini menolak pola tersebut pada class UI (nama berakhiran Ui / Hub / Drawer / Shell).
"""
import pathlib, re, sys

SRC = pathlib.Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "java"
errors = []
for path in SRC.rglob("*.kt"):
    text = path.read_text(encoding="utf-8", errors="ignore")
    for m in re.finditer(r"\n(?:internal |private )?class (\w+(?:Ui|Hub|Drawer|Shell))\b[^{]*\{", text):
        start = m.end(); depth = 1; i = start
        while depth and i < len(text):
            ch = text[i]
            if ch == "{": depth += 1
            elif ch == "}": depth -= 1
            i += 1
        body = text[start:i]
        seen_init = False
        offset = text[:start].count("\n") + 1
        for n, line in enumerate(body.split("\n")):
            if re.match(r"    init \{", line):
                seen_init = True
            elif seen_init:
                pm = re.match(r"    (?:private |internal )?(?:val|var) (\w+)", line)
                if pm and " get()" not in line:
                    errors.append(f"::error file={path},line={offset + n}::Property '{pm.group(1)}' di {m.group(1)} ditulis setelah init {{}} — pindahkan ke atas init agar tidak null saat runtime.")
for e in errors:
    print(e)
if errors:
    sys.exit(1)
print("Init-order guard: OK")
