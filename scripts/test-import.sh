#!/usr/bin/env bash
set -euo pipefail
pd2_project_root="$(cd "$(dirname "$0")/.." && pwd)"
pd2_test_classes="$(mktemp -d)"
trap 'rm -rf "$pd2_test_classes"' EXIT
javac -d "$pd2_test_classes" \
  "$pd2_project_root/app/src/main/java/com/winlator/pd2/Pd2ImportIO.java" \
  "$pd2_project_root/app/src/main/java/com/winlator/pd2/Pd2InstallValidator.java" \
  "$pd2_project_root/tests/java/com/winlator/pd2/Pd2ImportTests.java"
java -ea -cp "$pd2_test_classes" com.winlator.pd2.Pd2ImportTests
