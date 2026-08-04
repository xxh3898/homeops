#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly DEPLOY_WORKFLOW="${REPOSITORY_ROOT}/.github/workflows/deploy.yml"
readonly BOOTSTRAP="${REPOSITORY_ROOT}/deploy/bootstrap/deploy-homeops-ci.sh.example"
readonly WORKER="${REPOSITORY_ROOT}/deploy/scripts/deploy-homeops.sh"
readonly ENV_EXAMPLE="${REPOSITORY_ROOT}/deploy/env.example"

assert_contains() {
  local file="$1"
  local expected="$2"

  if ! /usr/bin/grep -Fq -- "${expected}" "${file}"; then
    printf 'Missing deployment contract in %s: %s\n' "${file#"${REPOSITORY_ROOT}/"}" "${expected}" >&2
    exit 1
  fi
}

assert_absent() {
  local file="$1"
  local forbidden="$2"

  if /usr/bin/grep -Fq -- "${forbidden}" "${file}"; then
    printf 'Mutable deployment contract remains in %s: %s\n' "${file#"${REPOSITORY_ROOT}/"}" "${forbidden}" >&2
    exit 1
  fi
}

assert_contains "${DEPLOY_WORKFLOW}" 'api_digest: ${{ steps.publish-api.outputs.digest }}'
assert_contains "${DEPLOY_WORKFLOW}" 'web_digest: ${{ steps.publish-web.outputs.digest }}'
assert_contains "${DEPLOY_WORKFLOW}" 'deploy-homeops-v2 ${GITHUB_SHA} ${API_IMAGE_DIGEST} ${WEB_IMAGE_DIGEST} ${RUNTIME_CONFIG_DIGEST} ${REGISTRY_OWNER} ${registry_user}'
assert_absent "${DEPLOY_WORKFLOW}" 'deploy-homeops-v1'

assert_contains "${BOOTSTRAP}" '^deploy-homeops-v2[[:space:]]'
assert_contains "${BOOTSTRAP}" 'HOMEOPS_API_IMAGE_DIGEST="${API_DIGEST}"'
assert_contains "${BOOTSTRAP}" 'HOMEOPS_WEB_IMAGE_DIGEST="${WEB_DIGEST}"'
assert_contains "${BOOTSTRAP}" 'HOMEOPS_RUNTIME_CONFIG_DIGEST="${RUNTIME_DIGEST}"'
assert_contains "${BOOTSTRAP}" 'deployment digests must be non-zero'

assert_contains "${WORKER}" 'API_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-api@${CANDIDATE_API_DIGEST}"'
assert_contains "${WORKER}" 'WEB_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-web@${CANDIDATE_WEB_DIGEST}"'
assert_contains "${WORKER}" 'API_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-api@${current_api_digest}"'
assert_contains "${WORKER}" 'WEB_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-web@${current_web_digest}"'
assert_contains "${WORKER}" 'validate_image_revision "${COMMIT_SHA}" "${API_IMAGE}"'
assert_contains "${WORKER}" 'validate_image_revision "${COMMIT_SHA}" "${WEB_IMAGE}"'
assert_contains "${WORKER}" 'validate_image_revision "${current_sha}" "${API_IMAGE}"'
assert_contains "${WORKER}" 'validate_image_revision "${current_sha}" "${WEB_IMAGE}"'
assert_contains "${WORKER}" "\$'API_IMAGE_DIGEST\\nCURRENT_SHA\\nPREVIOUS_API_IMAGE_DIGEST\\nPREVIOUS_RUNTIME_CONFIG_DIGEST\\nPREVIOUS_SHA\\nPREVIOUS_WEB_IMAGE_DIGEST\\nRUNTIME_CONFIG_DIGEST\\nWEB_IMAGE_DIGEST'"
assert_contains "${WORKER}" "printf 'API_IMAGE_DIGEST=%s\\n' \"\${CANDIDATE_API_DIGEST}\""
assert_contains "${WORKER}" "printf 'PREVIOUS_API_IMAGE_DIGEST=%s\\n' \"\${current_api_digest}\""
assert_contains "${WORKER}" "printf 'WEB_IMAGE_DIGEST=%s\\n' \"\${CANDIDATE_WEB_DIGEST}\""
assert_contains "${WORKER}" "printf 'PREVIOUS_WEB_IMAGE_DIGEST=%s\\n' \"\${current_web_digest}\""
assert_absent "${WORKER}" 'homeops-api:${COMMIT_SHA}'
assert_absent "${WORKER}" 'homeops-web:${COMMIT_SHA}'
assert_absent "${WORKER}" 'homeops-api:${current_sha}'
assert_absent "${WORKER}" 'homeops-web:${current_sha}'

assert_contains "${ENV_EXAMPLE}" 'HOMEOPS_API_IMAGE=ghcr.io/example/homeops-api@sha256:'
assert_contains "${ENV_EXAMPLE}" 'HOMEOPS_WEB_IMAGE=ghcr.io/example/homeops-web@sha256:'

printf 'Deployment digest contract checks passed\n'
