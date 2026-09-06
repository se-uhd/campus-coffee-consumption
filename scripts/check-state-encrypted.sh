#!/usr/bin/env bash
#
# Fails when a committed OpenTofu state file is not encrypted. The state in infra/ is committed to git and
# must only ever be the encrypted form (infra/encryption.tf enforces it on every write); this guard, run by
# CI, makes a plain-text state impossible to merge by accident. An encrypted state is a JSON envelope whose
# top-level keys are serial, lineage, meta, encrypted_data, and encryption_version; a plain-text one carries
# the "resources" array instead.

set -euo pipefail
cd "$(dirname "$0")/.."

status=0
shopt -s nullglob
for f in infra/*.tfstate*; do
  if ! grep -q '"encrypted_data"' "$f" || grep -q '"resources"' "$f"; then
    echo "$f is not an encrypted OpenTofu state file" >&2
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "state files are encrypted (or absent)"
exit "$status"
