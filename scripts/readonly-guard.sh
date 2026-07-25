#!/usr/bin/env bash
# readonly-guard.sh — prove the backup app never mutates on-device data.
#
# Snapshots row counts and a SHA-256 fingerprint of every content provider the
# app reads. Fingerprints are computed over the FULL row data (so any edit is
# caught) but only the HASH is printed — no message bodies, numbers, or file
# names are ever stored. Run before a backup and after; identical output == the
# app provably changed nothing.
#
#   scripts/readonly-guard.sh > before.txt   # phone at rest
#   ./gradlew installDebug && <run a backup>
#   scripts/readonly-guard.sh > after.txt
#   diff before.txt after.txt                # must be empty
#
# Caveat: this measures whether data changed, not who changed it. Run the two
# snapshots close together with the phone otherwise idle, or a genuinely new
# incoming SMS / freshly taken photo will show as a (legitimate) diff.

set -euo pipefail
ADB="${ADB:-adb}"

providers=(
  "sms|content://sms"
  "mms|content://mms"
  "mms_part|content://mms/part"
  "call_log|content://call_log/calls"
  "images|content://media/external/images/media"
  "video|content://media/external/video/media"
)

printf '# readonly-guard snapshot  %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '# device: %s\n' "$($ADB shell getprop ro.serialno 2>/dev/null | tr -d '\r')"

for entry in "${providers[@]}"; do
  name="${entry%%|*}"
  uri="${entry#*|}"
  # Full-row query (no --projection => all columns). Sort so provider row order
  # can't cause a false diff. Hash the content; print only count + hash.
  out="$($ADB shell content query --uri "$uri" 2>&1 || true)"
  if printf '%s' "$out" | grep -qiE 'Error|Exception|Permission Denial|Unknown URI'; then
    printf '%-10s ERROR: %s\n' "$name" "$(printf '%s' "$out" | head -1)"
    continue
  fi
  count="$(printf '%s\n' "$out" | grep -c '^Row:' || true)"
  hash="$(printf '%s\n' "$out" | grep '^Row:' | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
  printf '%-10s count=%-7s sha256=%s\n' "$name" "$count" "$hash"
done
