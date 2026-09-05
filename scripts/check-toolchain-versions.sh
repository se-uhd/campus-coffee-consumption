#!/usr/bin/env bash
# Fail if a pinned toolchain version drifts or is not an LTS release. Four checks:
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
#  3. detekt matches Kotlin. A detekt 2.0 alpha refuses to run against any Kotlin other than the one it was
#     compiled with, so the catalog's `kotlin` and `detekt` entries must move together. detekt publishes the
#     Kotlin it was built against in its Gradle module metadata, so the pair is checked against the real
#     artifact instead of a hand-maintained table. A Dependabot rule stops Kotlin being bumped on its own
#     (.github/dependabot.yml); this catches a hand edit that moves one without the other.
#  4. The Qodana linter matches the Qodana action. qodana-action refuses a linter image from a different
#     Qodana release, and dependabot bumps the action (a `uses:` reference) but not the linter tag in
#     qodana.yaml (a plain string in a config it does not parse), so the pins drift apart on their own.
#     Compares the pins only; no network.
#  5. The temporary Tomcat override is still needed. Spring Boot 4.1.1 manages a Tomcat with three critical
#     authentication-bypass advisories, so java-conventions.gradle.kts pins a newer one through a project
#     property. An override like that is exactly the kind of thing that outlives its reason silently, so this
#     compares it against the Tomcat the pinned Spring Boot actually manages and fails once the BOM has
#     caught up, telling you to delete it. Skips silently when no override is present, so removing it is all
#     that is needed. Reads the BOM from Maven Central.
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

### 3. detekt is built against the pinned Kotlin version ###

# Both entries live in the version catalog and must name the same Kotlin release.
kotlin_version=$(sed -n 's/^kotlin = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)
[ -n "$kotlin_version" ] || fail "could not read the 'kotlin' version from gradle/libs.versions.toml"

detekt_version=$(sed -n 's/^detekt = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)
[ -n "$detekt_version" ] || fail "could not read the 'detekt' version from gradle/libs.versions.toml"

echo "Kotlin/detekt pin: kotlin=$kotlin_version detekt=$detekt_version"

# detekt-core is the engine that enforces the match at runtime; its Gradle module metadata records the
# kotlin-compiler it was built against. Network: skip with a warning if Maven Central cannot be reached so
# an offline local run is not blocked; CI always has network and enforces it.
detekt_module_url="https://repo1.maven.org/maven2/dev/detekt/detekt-core/${detekt_version}/detekt-core-${detekt_version}.module"
detekt_module=$(curl -fsSL --max-time 20 "$detekt_module_url" 2>/dev/null || true)
if [ -z "$detekt_module" ]; then
  echo "::warning::could not fetch the detekt $detekt_version module metadata; skipping the Kotlin/detekt check (re-run online)"
else
  detekt_kotlin=$(printf '%s' "$detekt_module" | jq -r '
    [ .variants[]?.dependencies[]?
      | select(.group == "org.jetbrains.kotlin" and .module == "kotlin-compiler")
      | .version.requires ] | first // "none"')
  if [ "$detekt_kotlin" = "none" ]; then
    fail "could not read the Kotlin version detekt $detekt_version was built against from $detekt_module_url"
  fi
  [ "$detekt_kotlin" = "$kotlin_version" ] ||
    fail "detekt $detekt_version was built against Kotlin $detekt_kotlin but the catalog pins Kotlin $kotlin_version; a detekt 2.0 alpha only runs on the exact Kotlin it was compiled with, so bump both together"
  echo "detekt $detekt_version matches the pinned Kotlin ($kotlin_version)."
fi

### 4. The Qodana linters match the Qodana action ###

# The qodana-action refuses to run a linter image from a different Qodana release ("non-compatible Qodana
# linter ... with the current CLI"). Dependabot bumps the action, because it is a `uses:` reference it
# parses, but not the linter tag in qodana.yaml, which is a plain string in a config it does not read. So
# the action moves on its own and the two pins silently drift apart; this check catches that.
# Purely local: it compares the pins, so no network is involved.
action_versions=$(sed -n 's|.*uses: *JetBrains/qodana-action@v\([0-9][^[:space:]]*\).*|\1|p' .github/workflows/qodana.yml | sort -u)
[ -n "$action_versions" ] || fail "could not read the qodana-action version from .github/workflows/qodana.yml"
[ "$(printf '%s\n' "$action_versions" | wc -l)" -eq 1 ] ||
  fail "the qodana.yml jobs pin different qodana-action versions ($(printf '%s' "$action_versions" | tr '\n' ' ')); use one version for every job"

# The linters are tagged by Qodana release (major.minor, e.g. 2026.2); the action carries a third segment
# (v2026.2.0), so compare only the release part.
action_release=$(printf '%s' "$action_versions" | cut -d. -f1,2)

jvm_linter=$(sed -n 's|^linter: *jetbrains/qodana-jvm-community:\(.*\)$|\1|p' qodana.yaml)
[ -n "$jvm_linter" ] || fail "could not read the JVM linter tag from qodana.yaml"

echo "Qodana release: action=$action_versions jvm-linter=$jvm_linter"

[ "$jvm_linter" = "$action_release" ] ||
  fail "qodana.yaml pins the JVM linter at $jvm_linter but qodana-action is $action_versions (Qodana $action_release); bump the linter tag to $action_release"
echo "Qodana linters match the action (Qodana $action_release)."

# --- 5. the temporary Tomcat override ------------------------------------------------------------------
conventions=build-logic/src/main/kotlin/de/seuhd/campuscoffee/java-conventions.gradle.kts
tomcat_override=$(sed -n 's|^extra\["tomcat.version"\] = "\(.*\)"$|\1|p' "$conventions")

if [ -z "$tomcat_override" ]; then
  echo "No Tomcat override in place; nothing to check."
else
  boot_version=$(sed -n 's|^spring-boot = "\(.*\)"$|\1|p' gradle/libs.versions.toml)
  [ -n "$boot_version" ] || fail "could not read the spring-boot version from gradle/libs.versions.toml"

  bom_url="https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/$boot_version/spring-boot-dependencies-$boot_version.pom"
  bom=$(curl -fsSL --max-time 20 "$bom_url" 2>/dev/null || true)
  if [ -z "$bom" ]; then
    # Offline or Maven Central unreachable: skip rather than fail, matching the other network checks.
    echo "Could not fetch the Spring Boot BOM; skipping the Tomcat override check."
  else
    bom_tomcat=$(printf '%s' "$bom" | sed -n 's|.*<tomcat\.version>\([^<]*\)</tomcat\.version>.*|\1|p' | head -1)
    [ -n "$bom_tomcat" ] || fail "could not read <tomcat.version> from $bom_url"

    echo "Tomcat: override=$tomcat_override spring-boot-$boot_version manages=$bom_tomcat"

    # The override earns its place only while it is strictly newer than what the BOM manages.
    oldest=$(printf '%s\n%s\n' "$tomcat_override" "$bom_tomcat" | sort -V | head -1)
    if [ "$bom_tomcat" = "$tomcat_override" ] || [ "$oldest" = "$tomcat_override" ]; then
      fail "Spring Boot $boot_version now manages Tomcat $bom_tomcat, which is not older than the override $tomcat_override; delete the extra[\"tomcat.version\"] line in $conventions and this check"
    fi
    echo "The Tomcat override is still newer than the BOM; keep it."
  fi
fi
