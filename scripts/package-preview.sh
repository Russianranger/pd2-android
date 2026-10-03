#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="$project_root/app/build/distributions"
source_apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
# Build-time relocation scratch archives must never be shipped.
python3 - "$source_apk" <<'PYTHON'
from zipfile import ZipFile
import sys
with ZipFile(sys.argv[1]) as apk:
    leftovers = [name for name in apk.namelist() if '.pd2-relocate-' in name or '.pd2-fetch-' in name]
if leftovers:
    raise SystemExit('APK contains temporary runtime archives: ' + ', '.join(leftovers))
PYTHON
mkdir -p "$destination"
cp "$source_apk" "$destination/PD2-Android-0.1.0-preview.apk"
(cd "$destination" && sha256sum PD2-Android-0.1.0-preview.apk > SHA256SUMS)
