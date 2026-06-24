#!/usr/bin/env bash
# Fail if a pinned toolchain version drifts or is not an LTS release. Two checks:
#
#  1. Java major consistency. The Gradle toolchain and the Kotlin jvmTarget read it from the version catalog
#     (gradle/libs.versions.toml, `java`), the source of truth; mise.toml (local + CI tool provisioning) and
#     the Dockerfile runtime image must agree. (Java 25 is an LTS.)
#  2. Node is an ACTIVE LTS, and the frontend tracks it. The Node runtime is pinned in mise.toml (`node`),
#     the source of truth; @types/node and the package.json engines floor must track that major, and the
#     major must be an active Node LTS line. Node LTS is calendar-based (even majors only, and only from the
#     October after release), so the major is validated against the official Node release schedule: this
#     rejects odd "Current" lines (e.g. 25, never LTS) AND even-but-not-yet-LTS lines (e.g. 26, which stays
#     "Current" until October 2026). Dependabot does not manage mise.toml, so a non-LTS Node can only enter
#     via a human edit, which this guard then catches in CI.
#
# Run locally or in CI; emits GitHub Actions error annotations on mismatch.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
  echo "::error::$*" >&2
  exit 1
}

### 1. Java major consistency ###

# Source of truth: the catalog entry the convention plugins resolve (java = "25").
catalog=$(sed -n 's/^java = "\([0-9][0-9]*\)".*/\1/p' gradle/libs.versions.toml)
[ -n "$catalog" ] || fail "could not read the 'java' version from gradle/libs.versions.toml"

# mise.toml: java = 'temurin-25' (distribution-NN); capture the trailing major.
mise=$(sed -n "s/^java *= *.*-\([0-9][0-9]*\).*/\1/p" mise.toml)
[ -n "$mise" ] || fail "could not read the Java version from mise.toml"

# Dockerfile runtime stage: FROM eclipse-temurin:25-jre-...
docker=$(sed -n 's/^FROM eclipse-temurin:\([0-9][0-9]*\).*/\1/p' Dockerfile)
[ -n "$docker" ] || fail "could not read the Java version from the Dockerfile runtime image"

echo "Java major version: catalog=$catalog mise=$mise dockerfile=$docker"

[ "$catalog" = "$mise" ] || fail "mise.toml Java version ($mise) differs from the version catalog ($catalog)"
[ "$catalog" = "$docker" ] || fail "Dockerfile Java version ($docker) differs from the version catalog ($catalog)"

echo "Java versions are consistent ($catalog)."

### 2. Node is an active LTS, tracked by the frontend ###

# Source of truth: mise.toml (node = '24'), tolerating single or double quotes or none.
node_major=$(sed -n "s/^node *= *[\"']*\([0-9][0-9]*\).*/\1/p" mise.toml)
[ -n "$node_major" ] || fail "could not read the 'node' version from mise.toml"

# @types/node must track the runtime major (its major == the Node major).
types_node=$(sed -n 's/.*"@types\/node": *"[^0-9]*\([0-9][0-9]*\).*/\1/p' frontend/package.json)
[ -n "$types_node" ] || fail "could not read @types/node from frontend/package.json"

# The engines.node floor must be on the same major.
engines_node=$(sed -n 's/.*"node": *"[^0-9]*\([0-9][0-9]*\).*/\1/p' frontend/package.json)
[ -n "$engines_node" ] || fail "could not read engines.node from frontend/package.json"

echo "Node major version: mise=$node_major @types/node=$types_node engines=$engines_node"

[ "$node_major" = "$types_node" ] ||
  fail "@types/node major ($types_node) differs from the Node runtime ($node_major); @types/node must track the runtime"
[ "$node_major" = "$engines_node" ] ||
  fail "frontend engines.node major ($engines_node) differs from the Node runtime ($node_major)"

# Validate the pinned major against the official Node release schedule. Network: skip with a warning if it
# cannot be reached so an offline local run is not blocked; CI always has network and enforces it.
schedule=$(curl -fsSL --max-time 20 https://raw.githubusercontent.com/nodejs/Release/main/schedule.json 2>/dev/null || true)
if [ -z "$schedule" ]; then
  echo "::warning::could not fetch the Node release schedule; skipping the Node LTS check (re-run online)"
else
  today=$(date -u +%Y-%m-%d)
  lts=$(printf '%s' "$schedule" | jq -r --arg v "v$node_major" '.[$v].lts // "none"')
  end=$(printf '%s' "$schedule" | jq -r --arg v "v$node_major" '.[$v].end // "none"')
  if [ "$lts" = "none" ]; then
    fail "Node $node_major is not an LTS line (odd majors are never LTS); pin an even, active-LTS major in mise.toml"
  fi
  if [[ "$lts" > "$today" ]]; then
    fail "Node $node_major is still 'Current' (it does not reach LTS until $lts); pin a major that is already LTS"
  fi
  if [ "$end" != "none" ] && [[ "$today" > "$end" ]]; then
    fail "Node $node_major reached end-of-life on $end; move to a newer Node LTS"
  fi
  echo "Node $node_major is an active LTS (lts $lts, eol $end)."
fi
