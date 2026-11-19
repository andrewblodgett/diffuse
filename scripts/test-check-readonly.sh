#!/usr/bin/env bash
# test-check-readonly.sh — proves scripts/check-readonly.sh still catches what it claims to.
#
# Copies the app's sources into a scratch directory, plants one known violation at a time,
# and asserts the guard fails for each (and passes on the untouched copy). A guard that
# silently stops matching is worse than none, because the README promises it works.
#
#   scripts/test-check-readonly.sh     # exit 0 = every planted violation was caught

set -euo pipefail
cd "$(dirname "$0")/.."

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fresh_copy() {
  rm -rf "$work/repo"
  mkdir -p "$work/repo/app/src/main" "$work/repo/lightui"
  cp -r scripts "$work/repo/"
  cp -r app/src/main/kotlin app/src/main/AndroidManifest.xml "$work/repo/app/src/main/"
  cp -r lightui/src "$work/repo/lightui/"
}

guard_passes() { bash "$work/repo/scripts/check-readonly.sh" >/dev/null 2>&1; }

failures=0
expect_caught() { # $1 = description; the violation has already been planted
  if guard_passes; then
    echo "NOT CAUGHT: $1"
    failures=$((failures + 1))
  else
    echo "caught:     $1"
  fi
}

SRC="$work/repo/app/src/main/kotlin/com/diffuse"
plant_file() { # $1 = file name, $2 = body
  printf 'package com.diffuse\n\n%s\n' "$2" > "$SRC/$1"
}

fresh_copy
if guard_passes; then echo "clean copy passes"; else echo "FAIL: the guard rejects the clean sources"; exit 1; fi

fresh_copy
sed -i 's|^\(        resolver.forEachRow(MMS_URI\)|        resolver.delete(MMS_URI, null, null)\n\1|' "$SRC/backup/provider/MmsSource.kt"
grep -q 'resolver.delete(MMS_URI' "$SRC/backup/provider/MmsSource.kt" || { echo "FAIL: could not plant"; exit 1; }
expect_caught "delete through a field named 'resolver'"

fresh_copy
plant_file Planted.kt 'fun wipe(cr: android.content.ContentResolver, smsUri: android.net.Uri) { cr.update(smsUri, null, null, null) }'
expect_caught "update with a URI-shaped first argument"

fresh_copy
plant_file Planted.kt 'fun add(c: android.content.Context) { val r = c.contentResolver; r.insert(android.provider.Telephony.Sms.CONTENT_URI, null) }'
expect_caught "insert into telephony"

fresh_copy
plant_file Planted.kt 'fun bulk(x: android.content.ContentResolver, u: android.net.Uri) { x.bulkInsert(u, arrayOf()) }'
expect_caught "bulkInsert"

fresh_copy
plant_file Planted.kt 'fun out(c: android.content.Context, u: android.net.Uri) = c.contentResolver.openOutputStream(u)'
expect_caught "openOutputStream"

fresh_copy
plant_file Planted.kt 'fun fd(c: android.content.Context, u: android.net.Uri) = c.contentResolver.openFileDescriptor(u, "rw")'
expect_caught "writable file descriptor"

fresh_copy
plant_file Planted.kt 'val role = android.app.role.RoleManager.ROLE_SMS'
expect_caught "default-SMS role request"

fresh_copy
sed -i 's|<uses-permission android:name="android.permission.READ_SMS" />|&\n    <uses-permission android:name="android.permission.WRITE_SMS" />|' \
  "$work/repo/app/src/main/AndroidManifest.xml"
expect_caught "WRITE_SMS in the manifest"

echo
if [[ "$failures" -ne 0 ]]; then
  echo "GUARD SELF-TEST FAILED: $failures planted violation(s) slipped through."
  exit 1
fi
echo "GUARD SELF-TEST PASSED."
