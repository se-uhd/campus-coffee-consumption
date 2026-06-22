#!/usr/bin/env bash
#
# Generates the frontend TypeScript DTOs from the committed backend OpenAPI spec.
#
# Input : frontend/src-gen/api-docs.json   (the OpenAPI spec; refreshed when the API changes, see
#                                            frontend/src-gen/README.md)
# Output: frontend/src/app/api/model/*.ts  (MODELS ONLY — no api/client/supporting service stubs)
#
# The generated files are kept in the tree (not gitignored) so a standalone `npm run build` works
# without a generation step. This script is a hash-skip wrapper: it regenerates only when the spec has
# changed since the last run, so `gradle build` (which calls it via the generateFrontendDtos task) is a
# fast no-op when the spec is unchanged.
#
# All node/npx tooling runs through the frontend's pinned devDependency
# (@openapitools/openapi-generator-cli) via `npx`, so the generator version is reproducible.
set -euo pipefail

# Resolve the repository root from this script's location (scripts/ is a direct child of the root).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

SPEC="frontend/src-gen/api-docs.json"
HASH_FILE="frontend/src-gen/.api-docs.hash"
OUT_DIR="frontend/src/app/api"
MODEL_DIR="${OUT_DIR}/model"

if [[ ! -f "${SPEC}" ]]; then
  echo "ERROR: OpenAPI spec not found at ${SPEC} (see frontend/src-gen/README.md to produce it)." >&2
  exit 1
fi

# sha256 of the committed spec. Use shasum (macOS) or sha256sum (Linux), whichever is present.
if command -v shasum >/dev/null 2>&1; then
  CURRENT_HASH="$(shasum -a 256 "${SPEC}" | awk '{print $1}')"
else
  CURRENT_HASH="$(sha256sum "${SPEC}" | awk '{print $1}')"
fi

# Hash-skip: regenerate only when the spec changed AND the model dir already exists.
if [[ -f "${HASH_FILE}" && -d "${MODEL_DIR}" ]]; then
  PREVIOUS_HASH="$(cat "${HASH_FILE}")"
  if [[ "${CURRENT_HASH}" == "${PREVIOUS_HASH}" ]]; then
    echo "DTOs up to date"
    exit 0
  fi
fi

echo "Generating frontend DTOs from ${SPEC} ..."

# Run the openapi-generator-cli from the frontend dir so it resolves the pinned devDependency.
# --global-property models  -> models only (no apis, no supporting files, no client).
(
  cd frontend
  npx --no-install @openapitools/openapi-generator-cli generate \
    -i src-gen/api-docs.json \
    -g typescript-angular \
    --global-property models \
    -o src/app/api
)

# Record the spec hash so the next run is a no-op when nothing changed.
echo "${CURRENT_HASH}" > "${HASH_FILE}"
echo "DTOs generated into ${MODEL_DIR}"
