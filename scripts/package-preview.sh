#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="$project_root/app/build/distributions"
source_apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
metadata="$project_root/app/build/outputs/apk/debug/output-metadata.json"
# Use the built APK's metadata, so names cannot drift when versions change.
version="$(python3 - "$source_apk" "$metadata" <<'PYTHON'
from pathlib import Path
from zipfile import ZipFile
import json
import re
import sys
apk_path = Path(sys.argv[1])
metadata = json.loads(Path(sys.argv[2]).read_text())
if metadata.get('applicationId') != 'com.pd2.thor':
    raise SystemExit('Unexpected APK application identity')
element = next((item for item in metadata['elements'] if item['outputFile'] == apk_path.name), None)
if element is None:
    raise SystemExit('Built APK is missing from output metadata')
version = element['versionName']
if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._-]{0,79}', version):
    raise SystemExit('Invalid version for artifact filename')
with ZipFile(apk_path) as apk:
    leftovers = [name for name in apk.namelist() if '.pd2-relocate-' in name or '.pd2-fetch-' in name]
if leftovers:
    raise SystemExit('APK contains temporary runtime archives: ' + ', '.join(leftovers))
print(version)
PYTHON
)"
filename="PD2-Android-$version-preview.apk"
mkdir -p "$destination"
cp "$source_apk" "$destination/$filename"
(cd "$destination" && sha256sum "$filename" > SHA256SUMS)
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s\n' "version=$version" "apk_path=$destination/$filename" "apk_name=$filename" >> "$GITHUB_OUTPUT"
fi
