#!/usr/bin/env bash
# check-readonly.sh — STATIC read-only guard for Diffuse (runs in CI and locally).
#
# Fails the build if the app could ever mutate on-device data. This is the
# source-and-manifest counterpart to scripts/readonly-guard.sh (which verifies the
# claim empirically on a live device). Together they enforce the invariant that
# Diffuse is strictly one-way: it reads SMS/MMS/call log/media and never writes.
#
#   scripts/check-readonly.sh        # exit 0 = clean, exit 1 = a violation was found
#
# No Android SDK required — it's just grep, so CI can run it on a bare runner.

set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
note() { printf '  - %s\n' "$*"; }

# grep for real code, not prose: drops `grep -rn` hits whose line (after
# `path:lineno:`) begins with a Kotlin/Java comment marker (//, *, /*). This keeps
# doc comments that mention these APIs from tripping the guard.
scan() { # $1 = pattern, $2 = dir
  grep -rnE --include='*.kt' --include='*.java' "$1" "$2" 2>/dev/null \
    | grep -vE ':[0-9]+:[[:space:]]*(//|\*|/\*)' || true
}

# Directories of first-party source to scan.
SRC_DIRS=(app/src lightui/src)
MANIFEST="app/src/main/AndroidManifest.xml"

# --- 1. Manifest: only READ_* / network permissions allowed --------------------
# Any permission whose name contains WRITE, MANAGE, or INSTALL touching user data
# is forbidden, as is the default-SMS machinery.
echo "[1/3] Checking $MANIFEST for write/manage permissions..."
if [[ -f "$MANIFEST" ]]; then
  bad_perms="$(grep -oE 'android\.permission\.[A-Z_]+' "$MANIFEST" \
    | grep -E 'WRITE|MANAGE_EXTERNAL_STORAGE|MANAGE_MEDIA|INSTALL|DELETE_PACKAGES' \
    || true)"
  if [[ -n "$bad_perms" ]]; then
    echo "VIOLATION: forbidden permission(s) declared:"
    printf '%s\n' "$bad_perms" | sort -u | while read -r p; do note "$p"; done
    fail=1
  fi
else
  echo "VIOLATION: $MANIFEST not found"; fail=1
fi

# --- 2. Source: no provider-mutating calls -------------------------------------
# Curated, low-false-positive patterns. We never write to a ContentProvider, never
# build a MediaStore write/delete/trash request, never open an output stream, and
# never open a provider file descriptor in a writable mode.
echo "[2/3] Scanning ${SRC_DIRS[*]} for provider-mutating calls..."
MUTATION_PATTERNS=(
  '(getContentResolver\(\)|contentResolver)\.(insert|update|delete|bulkInsert|applyBatch)\b'
  '\.openOutputStream\('
  'openFileDescriptor\([^)]*"[^"]*[wa][^"]*"'      # any mode containing w/a (write/append)
  'openAssetFileDescriptor\([^)]*"[^"]*[wa][^"]*"'
  'ContentProviderOperation\.(newInsert|newUpdate|newDelete|newAssertQuery)'
  'MediaStore\.(createWriteRequest|createDeleteRequest|createTrashRequest|createFavoriteRequest)'
)
for dir in "${SRC_DIRS[@]}"; do
  [[ -d "$dir" ]] || continue
  for pat in "${MUTATION_PATTERNS[@]}"; do
    hits="$(scan "$pat" "$dir")"
    if [[ -n "$hits" ]]; then
      echo "VIOLATION: mutating call matching /$pat/:"
      printf '%s\n' "$hits" | while read -r h; do note "$h"; done
      fail=1
    fi
  done
done

# --- 3. Source: never request the default-SMS role -----------------------------
echo "[3/3] Scanning for default-SMS / role-request escalation..."
ROLE_PATTERNS=(
  'ROLE_SMS'
  'createRequestRoleIntent'
  'ACTION_CHANGE_DEFAULT'          # Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT
  'EXTRA_PACKAGE_NAME.*sms'
)
for dir in "${SRC_DIRS[@]}"; do
  [[ -d "$dir" ]] || continue
  for pat in "${ROLE_PATTERNS[@]}"; do
    hits="$(scan "$pat" "$dir")"
    if [[ -n "$hits" ]]; then
      echo "VIOLATION: default-SMS escalation matching /$pat/:"
      printf '%s\n' "$hits" | while read -r h; do note "$h"; done
      fail=1
    fi
  done
done

echo
if [[ "$fail" -ne 0 ]]; then
  echo "READ-ONLY GUARD FAILED — Diffuse must never mutate on-device data."
  exit 1
fi
echo "READ-ONLY GUARD PASSED — no write surface found."
