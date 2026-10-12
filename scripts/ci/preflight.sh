#!/usr/bin/env bash
set -euo pipefail

echo "== GITLS CI preflight =="

req_file() {
  if [ ! -f "$1" ]; then
    echo "::error::File wajib tidak ditemukan di root repository: $1 (kemungkinan terlewat/terfilter saat upload atau project ada di subfolder)."
    exit 1
  fi
}
req_grep() {
  if ! grep -q -e "$1" "$2"; then
    echo "::error file=$2::Pola wajib tidak ditemukan di $2: $1"
    exit 1
  fi
}

req_file settings.gradle
req_file build.gradle
req_file gradle/wrapper/gradle-wrapper.properties
[ -x ./gradlew ] || chmod +x ./gradlew
req_file app/build.gradle
req_file app/src/main/AndroidManifest.xml

# Fail early if the repository was packaged one directory too deep.
# GitHub Actions expects the Gradle project files at repository root.
for nested in gitls_final_work gitls_finance GITLS_PlayStore_Ready; do
  if [ -f "$nested/gradlew" ] || [ -d "$nested/app" ]; then
    echo "::error::Gradle project is nested under $nested/. Isi repository harus file project di ROOT (gradlew, settings.gradle, app/), bukan di dalam subfolder."
    exit 1
  fi
done
# Juga deteksi jika user hanya upload folder tanpa flatten
if [ ! -f ./gradlew ] && [ -d . ] ; then
  candidates=$(find . -maxdepth 2 -type f -name gradlew 2>/dev/null | head -5 || true)
  if [ -n "$candidates" ]; then
    echo "::error::gradlew ditemukan di subfolder, bukan di root. Path: $candidates"
    echo "::error::Pindahkan seluruh isi project ke root repository GitHub (bukan di dalam folder gitls_finance/)."
    exit 1
  fi
fi

# Guard: file sumber SISA upload lama. Uploader hanya menambah/menimpa file dan tidak pernah
# menghapus, sehingga file dari versi/proyek lama (mis. app/src/main/java/com/gitls/uploader/
# KeystoreTool.kt) tetap ada di repository dan ikut dikompilasi Gradle. Hasilnya build gagal
# dengan "Unresolved reference 'R'" / library tidak ditemukan, baru ketahuan setelah ~3 menit.
# Hanya source di com/example/aidetest/ yang valid. File .kt/.java di luar itu dibuang dari
# workspace CI (checkout sementara, repository GitHub tidak diubah) dan dilaporkan sebagai
# warning supaya bisa dihapus manual dari repository.
stale_count=0
while IFS= read -r -d '' f; do
  case "$f" in
    app/src/main/java/com/example/aidetest/*|app/src/main/kotlin/com/example/aidetest/*) ;;
    app/src/test/java/com/example/aidetest/*|app/src/test/kotlin/com/example/aidetest/*) ;;
    app/src/androidTest/java/com/example/aidetest/*|app/src/androidTest/kotlin/com/example/aidetest/*) ;;
    *)
      echo "::warning file=$f::File sumber sisa upload lama (di luar com/example/aidetest) dibuang dari build CI. Hapus file ini dari repository GitHub."
      rm -f -- "$f"
      stale_count=$((stale_count + 1))
      ;;
  esac
done < <(find app/src -type f \( -name '*.kt' -o -name '*.java' \) -print0)
echo "Stale source guard: OK (${stale_count} file sisa dibuang dari workspace CI)"

# Guard: package harus com.example.aidetest dan tidak boleh ada tipe top-level ganda
# (class/object/interface/typealias bernama sama di dua file = "Redeclaration", biasanya
# karena file lama yang sudah dipindah/di-rename masih tertinggal di repository).
python3 - <<'PY'
import pathlib, re, sys, collections
decl = re.compile(r"^(?:(?:public|internal|private|protected|abstract|open|final|sealed|data|enum|annotation|inline|value)\s+)*(class|object|interface|typealias)\s+(\w+)", re.M)
seen = collections.defaultdict(list)
errors = []
for p in sorted(pathlib.Path("app/src").rglob("*.kt")):
    text = p.read_text(encoding="utf-8")
    m = re.search(r"^package\s+([\w.]+)", text, re.M)
    pkg = m.group(1) if m else ""
    if not pkg.startswith("com.example.aidetest"):
        errors.append(f"::error file={p}::package '{pkg or '(kosong)'}' bukan com.example.aidetest. Kemungkinan file sisa dari upload lama; hapus file ini.")
    for d in decl.finditer(text):
        line = text[d.start():text.find("\n", d.start())]
        if re.match(r"private\b", line):
            continue
        seen[(pkg, d.group(2))].append(str(p))
for (pkg, name), files in seen.items():
    if len(files) > 1:
        errors.append(f"::error file={files[0]}::Tipe '{name}' dideklarasikan di {len(files)} file: {', '.join(files)}. Hapus file lama yang tertinggal.")
for e in errors:
    print(e)
if errors:
    sys.exit(1)
print("Package/redeclaration guard: OK")
PY

# Guard: reject imports that point to a physical folder instead of the declared Kotlin package.
# Example of the exact failure this prevents: a source file stored in core/ declares
# `package com.example.aidetest`, but another file imports `com.example.aidetest.core.Motion`.
python3 - <<'PY'
import pathlib, re, sys
root = pathlib.Path("app/src/main")
packages = set()
for p in root.rglob("*.kt"):
    text = p.read_text(encoding="utf-8")
    m = re.search(r"^package\s+([\w.]+)", text, re.M)
    if m:
        packages.add(m.group(1))
errors = []
for p in root.rglob("*.kt"):
    text = p.read_text(encoding="utf-8")
    for i, line in enumerate(text.splitlines(), 1):
        m = re.match(r"\s*import\s+([\w.]+)", line)
        if not m:
            continue
        imp = m.group(1)
        parts = imp.split('.')
        # Imports ending in a type are valid only if their package prefix is a real
        # declared package. This catches com.example.aidetest.core.Motion when
        # com.example.aidetest.core is not a declared package.
        pkg = '.'.join(parts[:-1])
        if pkg.startswith("com.example.aidetest") and pkg not in packages:
            errors.append((str(p), i, imp))
for f, i, imp in errors:
    print(f"::error file={f},line={i}::Import '{imp}' menunjuk package Kotlin yang tidak dideklarasikan. Periksa package declaration sebelum build.")
if errors:
    sys.exit(1)
print("Kotlin import/package guard: OK")
PY

# MDI icon registry guard: missing glyph names must fail before Gradle starts.
python3 scripts/ci/check_icons.py
python3 scripts/ci/check_init_order.py

# Build-toolchain invariants used by this project.
req_grep "distributionUrl=.*gradle-8\.11\.1-bin\.zip" gradle/wrapper/gradle-wrapper.properties
req_grep "com.android.tools.build:gradle:8\.10\.0" build.gradle
req_grep "ext.kotlin_version = '2\.2\.21'" build.gradle
req_grep "compileSdkVersion 36" app/build.gradle
req_grep "targetSdkVersion 36" app/build.gradle
req_grep "sourceCompatibility JavaVersion.VERSION_17" app/build.gradle
req_grep "targetCompatibility JavaVersion.VERSION_17" app/build.gradle
req_grep "JvmTarget.fromTarget(\"17\")" app/build.gradle
req_grep "JavaLanguageVersion.of(17)" app/build.gradle

# Never allow credentials to be committed accidentally.
# Do not scan source-code strings for PEM marker text because the app
# legitimately contains a key-generator tool.
if grep -RInE --exclude-dir=.git --exclude='*.png' --exclude='*.jpg' \
  '(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})' .; then
  echo "::error::Possible GitHub token found in repository."
  exit 1
fi

# Signing/private key material must never be bundled in source.
private_key_found=$(find . -type f \
  \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pem' \
  -o -name 'keystore.properties' \) \
  -not -path './.git/*' \
  -print -quit || true)
if [ -n "$private_key_found" ]; then
  echo "::error::Bundled signing/private-key material found: $private_key_found"
  exit 1
fi
echo "Signing material source guard: OK"

# Basic XML validation for Android resources.
python3 - <<'PY'
import pathlib, xml.etree.ElementTree as ET
root = pathlib.Path(".")
bad = []
for p in root.glob("app/src/main/**/*.xml"):
    try:
        ET.parse(p)
    except Exception as e:
        bad.append((str(p), str(e)))
if bad:
    for p, e in bad:
        print(f"::error file={p}::{e}")
    raise SystemExit(1)
print("XML validation: OK")
PY

# Resource reference guard: gagal cepat (1 detik) dengan pesan jelas jika ada XML
# yang merujuk resource lokal yang tidak ada, mis. file sisa dari upload lama
# (uploader hanya menambah/menimpa file, tidak pernah menghapus).
python3 - <<'PY'
import pathlib, re, sys, xml.etree.ElementTree as ET
res = pathlib.Path("app/src/main/res")
defined = {}
def add(t, n): defined.setdefault(t, set()).add(n.replace(".", "_"))
for d in res.iterdir():
    if not d.is_dir(): continue
    kind = d.name.split("-")[0]
    for f in d.iterdir():
        if kind == "values":
            if f.suffix != ".xml": continue
            for e in ET.parse(f).getroot():
                n = e.get("name")
                if not n: continue
                tag = e.get("type") if e.tag == "item" else e.tag
                tag = {"string-array": "array", "integer-array": "array"}.get(tag, tag)
                add(tag, n)
        else:
            add(kind, f.stem)
for f in res.rglob("*.xml"):
    for m in re.finditer(r"@\+id/(\w+)", f.read_text(encoding="utf-8")): add("id", m.group(1))
LIB = ("abc_", "mtrl_", "material_", "m3_", "design_", "notification_", "tooltip_", "common_", "androidx_")
CHECK = {"drawable", "mipmap", "color", "layout", "xml", "string", "dimen"}
errors = []
files = list(res.rglob("*.xml")) + [pathlib.Path("app/src/main/AndroidManifest.xml")]
for f in files:
    text = f.read_text(encoding="utf-8")
    for m in re.finditer(r"@(?!\+|android:)(\w+)/([\w.]+)", text):
        t, n = m.group(1), m.group(2).replace(".", "_")
        if t in CHECK and not n.startswith(LIB) and n not in defined.get(t, set()):
            line = text[:m.start()].count("\n") + 1
            errors.append((str(f), line, f"@{t}/{m.group(2)}"))
for f, line, ref in errors:
    print(f"::error file={f},line={line}::Resource {ref} dirujuk tetapi tidak ada. Tambahkan resource-nya atau hapus/perbaiki file ini (mungkin file sisa dari upload lama).")
if errors: sys.exit(1)
print("Resource reference guard: OK")
PY

# Guard: `::lateinitProp.isInitialized` hanya valid di class pemilik properti.
# Di file extension (MainActivity.xxx) ini bikin error kompilasi "Backing field ... is not accessible".
# Gunakan accessor aman seperti `bitAnim` (lihat MainActivity.kt).
python3 - <<'PY'
import pathlib, re, sys
bad = []
for p in pathlib.Path("app/src/main/java").rglob("*.kt"):
    if p.name == "MainActivity.kt":
        continue
    for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
        if re.search(r"::\w+\.isInitialized", line):
            bad.append((str(p), i))
for f, i in bad:
    print(f"::error file={f},line={i}::::prop.isInitialized dipakai di luar class pemilik (pakai accessor aman di MainActivity)")
if bad:
    sys.exit(1)
print("lateinit guard: OK")
PY

# Verify application id and version are present.
req_grep 'applicationId "com.gitls.app"' app/build.gradle
req_grep 'versionName' app/build.gradle

echo "Preflight: OK"

# --- Performance / lifecycle safety ---------------------------------------
PERF_FILE="app/src/main/java/com/example/aidetest/BitAnimationController.kt"
if [[ -f "$PERF_FILE" ]]; then
  if grep -q 'SENSOR_DELAY_GAME' "$PERF_FILE"; then
    echo "ERROR: Bit accelerometer must not use SENSOR_DELAY_GAME; use a lower-power lifecycle-aware rate."
    exit 1
  fi
  grep -q 'setLifecycleActive' "$PERF_FILE" || { echo "ERROR: Bit sensor lifecycle guard missing."; exit 1; }
fi
MAIN_FILE="app/src/main/java/com/example/aidetest/MainActivity.kt"
if [[ -f "$MAIN_FILE" ]]; then
  grep -q 'setLifecycleActive(false)' "$MAIN_FILE" || { echo "ERROR: Bit sensor is not stopped from MainActivity.onPause()."; exit 1; }
  grep -q 'setLifecycleActive(true)' "$MAIN_FILE" || { echo "ERROR: Bit sensor is not resumed from MainActivity.onResume()."; exit 1; }
fi

# --- Bot animation safety -------------------------------------------------
BOT_FILE="app/src/main/java/com/example/aidetest/BitAnimationController.kt"
if [[ -f "$BOT_FILE" ]]; then
  if grep -nE 'lookAt\(|translationX\(|translationY\(' "$BOT_FILE" >/dev/null; then
    if grep -nA55 'fun lookAt' "$BOT_FILE" | grep -E 'translationX\(|translationY\(|scaleX\(|scaleY\(|rotation\(' >/dev/null; then
      echo "ERROR: Bot gaze must not transform the whole navbar view."
      exit 1
    fi
  fi
fi

# --- Bit theme safety ------------------------------------------------------
BOT_MENU_FILE="app/src/main/java/com/example/aidetest/core/BotMenuButton.kt"
BIT_THEME_FILE="app/src/main/java/com/example/aidetest/MainActivityHome.kt"
if [[ -f "$BOT_MENU_FILE" ]]; then
  grep -q 'KeyPath("BODY", "\*\*")' "$BOT_MENU_FILE" || { echo "ERROR: navbar Bit BODY theme callback missing."; exit 1; }
  grep -q 'KeyPath("EYES", "\*\*")' "$BOT_MENU_FILE" || { echo "ERROR: navbar Bit EYES theme callback missing."; exit 1; }
  grep -q 'isDarkTheme' "$BOT_MENU_FILE" || { echo "ERROR: navbar Bit theme must read isDarkTheme."; exit 1; }
fi
if [[ -f "$BIT_THEME_FILE" ]]; then
  grep -q 'val body = if (isDarkTheme) Color.WHITE else Color.BLACK' "$BIT_THEME_FILE" || { echo "ERROR: Bit light/dark body palette rule missing."; exit 1; }
  grep -q 'val eyes = if (isDarkTheme) Color.BLACK else Color.WHITE' "$BIT_THEME_FILE" || { echo "ERROR: Bit light/dark eye palette rule missing."; exit 1; }
fi
