#!/usr/bin/env bash
set -euo pipefail
ORIGINAL_APK="com.google.android.youtube.tv_7.12.300-712300320_minAPI24(armeabi-v7a)(nodpi)_apkmirror.com.apk"
EXPECTED_SHA256="643e41f19606575b3be40125635e8da23742d669e21068f307fbd54c7c8f15c7"
OUT="${1:-ROLLBACK_RESTORED.apk}"
cp -f -- "$ORIGINAL_APK" "$OUT"
ACTUAL="$(sha256sum "$OUT" | awk '{print tolower($1)}')"
if [[ "$ACTUAL" != "$EXPECTED_SHA256" ]]; then
  echo "rollback hash mismatch: $ACTUAL != $EXPECTED_SHA256"
  exit 2
fi
echo "rollback restored $OUT sha256=$ACTUAL"