# shellcheck shell=bash
#
# Shared helpers of the deploy scripts. Source this file after cd-ing to the repository root.
#
# deploy.prod.env (gitignored; copy deploy.env.example to start one) is the local source of truth for the
# secrets and the non-secret deploy configuration. It is read line by line, never sourced: the multi-line
# LOGIN_PRIVATE_KEY_PEM cannot be sourced, and nothing in it should reach the shell's environment by accident.

env_file="deploy.prod.env"
[ -f "$env_file" ] || {
  echo "$env_file not found. Copy deploy.env.example to $env_file and fill it in." >&2
  exit 1
}

# Secret values are written to files under one scratch directory that is removed when the script exits, so a
# failed gcloud call in the middle of a sync cannot leave a plaintext secret behind.
secret_scratch_dir="$(mktemp -d)"
trap 'rm -rf "$secret_scratch_dir"' EXIT INT TERM

# Read a single-line value; an absent key reads as empty (grep's exit status must not abort the caller).
val() { grep "^$1=" "$env_file" | head -1 | cut -d= -f2- || true; }

# Fail when a key is absent from the file. An optional key (one that may be empty) still has to be present:
# `val` cannot tell an absent key from an empty one, and an empty value has its own meaning (an empty
# ALERT_EMAIL means "create no uptime check"), so a missing line must not be read as a deliberate empty one.
present() {
  grep -q "^$1=" "$env_file" || {
    echo "$1 is missing from $env_file. See deploy.env.example." >&2
    exit 1
  }
}

# Fail when a key is missing, empty, still the example placeholder, or listed twice. A duplicate is an error
# rather than a last-one-wins merge because `val` reads the first occurrence: a second line, appended by hand
# during a rotation, would be read by nobody and the rotation would look done while the old value is used.
require() {
  local v count
  present "$1"
  count="$(grep -c "^$1=" "$env_file")"
  if [ "$count" -gt 1 ]; then
    echo "$1 is set $count times in $env_file. Keep one line: the first one is the one that is read." >&2
    exit 1
  fi
  v="$(val "$1")"
  if [ -z "$v" ] || [ "$v" = "REPLACE_ME" ]; then
    echo "$1 is not set in $env_file." >&2
    exit 1
  fi
}

# Print the multi-line LOGIN_PRIVATE_KEY_PEM value (from after the '=' on its line through the END marker).
# The value is buffered and printed only once the marker has been seen, so a missing marker (a PKCS#1 key, or
# a truncated one) fails with no output instead of streaming the rest of the file, which holds every later
# secret. The marker only ends the scan once the key line has opened it, so an END marker sitting above the
# key cannot end the scan before it starts.
login_key() {
  awk '
    /^LOGIN_PRIVATE_KEY_PEM=/ { sub(/^LOGIN_PRIVATE_KEY_PEM=/, ""); open = 1 }
    open { buffer = buffer $0 "\n" }
    open && /-----END PRIVATE KEY-----/ { complete = 1; exit }
    END {
      if (!complete || buffer == "") {
        print "LOGIN_PRIVATE_KEY_PEM must be a PKCS#8 PEM ending in -----END PRIVATE KEY-----" > "/dev/stderr"
        exit 1
      }
      printf "%s", buffer
    }' "$env_file"
}

# Sync one secret into Secret Manager: create it if missing, add a new version only when the value differs
# from the current latest, so re-deploys do not churn versions. Values flow through a file in the scratch
# directory above (umask 077) and are never echoed.
# Usage: printf '%s' "$value" | sync_secret <secret-name>
sync_secret() {
  local name="$1" tmp
  tmp="$secret_scratch_dir/$name"
  cat >"$tmp"
  if ! gcloud secrets describe "$name" >/dev/null 2>&1; then
    gcloud secrets create "$name" --data-file="$tmp" --replication-policy=automatic >/dev/null
    echo "  created $name"
  elif ! gcloud secrets versions access latest --secret="$name" 2>/dev/null | cmp -s - "$tmp"; then
    gcloud secrets versions add "$name" --data-file="$tmp" >/dev/null
    echo "  updated $name (new version)"
  else
    echo "  $name unchanged"
  fi
  rm -f "$tmp"
}
