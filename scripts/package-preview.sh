#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="$project_root/app/build/distributions"
source_apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
metadata="$project_root/app/build/outputs/apk/debug/output-metadata.json"
# Use the built APK's metadata, so names cannot drift when versions change.
version="$(python3 - "$source_apk" "$metadata" "$project_root/app/build.gradle" <<'PYTHON'
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
# AGP can retain an older output-metadata.json in an incremental build. Refuse
# to overwrite a previous preview with an APK named from that stale metadata.
config = Path(sys.argv[3]).read_text()
name = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", config)
code = re.search(r"versionCode\s+(\d+)", config)
if name is None or code is None or version != name.group(1) or element['versionCode'] != int(code.group(1)):
    raise SystemExit('APK metadata differs from configured version; remove output-metadata.json and rebuild before packaging')
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
