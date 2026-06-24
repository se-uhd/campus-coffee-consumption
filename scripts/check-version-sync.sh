#!/usr/bin/env bash
# Fail if the project version drifts between the Gradle build and CHANGELOG.md. The `version` property in
# the root gradle.properties (Gradle sets it on project.version for every module) is the source of truth;
# the latest `## [x.y.z]` header in CHANGELOG.md must agree with it. Run locally or in CI; emits GitHub
# Actions error annotations on mismatch.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
  echo "::error::$*" >&2
  exit 1
}

# Source of truth: the project version Gradle reads from the root gradle.properties.
gradle=$(sed -n 's/^version *= *\([0-9][^[:space:]]*\)/\1/p' gradle.properties)
[ -n "$gradle" ] || fail "could not read the version from gradle.properties"

# The latest released version header in CHANGELOG.md (## [x.y.z]) must match it, comparing the full version
# token (including any pre-release/build suffix). The [Unreleased] header has no leading digit, so it is
# skipped, and the first match is the newest release.
changelog=$(sed -n 's/^## \[\([0-9][^]]*\)\].*/\1/p' CHANGELOG.md | head -n1)
[ -n "$changelog" ] || fail "could not read a released version (## [x.y.z]) from CHANGELOG.md"

echo "Project version: gradle=$gradle changelog=$changelog"

[ "$gradle" = "$changelog" ] ||
  fail "latest CHANGELOG.md version ($changelog) differs from the Gradle build version ($gradle)"

echo "Project versions are consistent ($gradle)."
