#!/bin/bash

set -Eeuo pipefail

readonly DOCKER_BIN=/usr/local/bin/docker
readonly LOCKF_BIN=/usr/bin/lockf
readonly CURL_BIN=/usr/bin/curl
readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly ORIGIN_VALIDATOR="${SCRIPT_DIR}/validate-https-origin.sh"
readonly DEFAULT_APP_DIR="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly APP_DIR="${HOMEOPS_APP_DIR:-${DEFAULT_APP_DIR}}"
readonly RUNTIME_DIR="${HOMEOPS_RUNTIME_DIR:-${APP_DIR}}"
COMPOSE_FILE="${RUNTIME_DIR}/compose.yaml"
readonly ENV_FILE="${APP_DIR}/.env"
readonly STATE_FILE="${APP_DIR}/deployment.state"
readonly SMOKE_ORIGIN_FILE="${APP_DIR}/smoke.origin"
readonly OPERATION_LOCK="${APP_DIR}/.homeops-operation.lock"
readonly RUNTIME_CONFIG_ROOT="${APP_DIR}/runtime-config"
readonly RUNTIME_CONFIG_RELEASES="${RUNTIME_CONFIG_ROOT}/releases"
readonly RUNTIME_CONFIG_PENDING="${RUNTIME_CONFIG_ROOT}/pending"
readonly RUNTIME_CONFIG_CURRENT="${RUNTIME_CONFIG_ROOT}/current"
readonly ZERO_SHA=0000000000000000000000000000000000000000
readonly ZERO_DIGEST=sha256:0000000000000000000000000000000000000000000000000000000000000000
readonly CANDIDATE_API_DIGEST="${HOMEOPS_API_IMAGE_DIGEST:-${ZERO_DIGEST}}"
readonly CANDIDATE_WEB_DIGEST="${HOMEOPS_WEB_IMAGE_DIGEST:-${ZERO_DIGEST}}"
readonly CANDIDATE_RUNTIME_DIGEST="${HOMEOPS_RUNTIME_CONFIG_DIGEST:-${ZERO_DIGEST}}"

fail() {
  printf 'HomeOps deployment failed: %s\n' "$1" >&2
  exit 1
}

require_private_file() {
  local path="$1"
  local label="$2"
  local expected_owner

  if [[ ! -f "${path}" || -L "${path}" ]]; then
    fail "${label} is missing or unsafe"
  fi
  expected_owner="$(/usr/bin/id -un)"
  if [[ "$(/usr/bin/stat -f '%Su' "${path}")" != "${expected_owner}" ]] \
    || [[ "$(/usr/bin/stat -f '%OLp' "${path}")" != 600 ]]
  then
    fail "${label} must be operator-owned with mode 0600"
  fi
}

if [[ "$#" -ne 2 ]]; then
  fail "expected exact commit SHA and registry owner"
fi

readonly COMMIT_SHA="$1"
readonly REGISTRY_OWNER="$2"

if [[ ! "${COMMIT_SHA}" =~ ^[0-9a-f]{40}$ ]] \
  || [[ "${COMMIT_SHA}" == "${ZERO_SHA}" ]]
then
  fail "commit SHA must be a non-zero lowercase 40-character SHA"
fi
if [[ ! "${REGISTRY_OWNER}" =~ ^[a-z0-9][a-z0-9-]{0,38}$ ]]; then
  fail "registry owner has an unexpected format"
fi
if [[ ! "${APP_DIR}" =~ ^/ ]] || [[ ! "${RUNTIME_DIR}" =~ ^/ ]]; then
  fail "application and runtime directories must be absolute"
fi
if [[ "${CANDIDATE_API_DIGEST}" == "${ZERO_DIGEST}" ]] \
  || [[ ! "${CANDIDATE_API_DIGEST}" =~ ^sha256:[0-9a-f]{64}$ ]]
then
  fail "API image digest must be a non-zero lowercase SHA-256 digest"
fi
if [[ "${CANDIDATE_WEB_DIGEST}" == "${ZERO_DIGEST}" ]] \
  || [[ ! "${CANDIDATE_WEB_DIGEST}" =~ ^sha256:[0-9a-f]{64}$ ]]
then
  fail "Web image digest must be a non-zero lowercase SHA-256 digest"
fi
if [[ "${CANDIDATE_RUNTIME_DIGEST}" != "${ZERO_DIGEST}" ]] \
  && [[ ! "${CANDIDATE_RUNTIME_DIGEST}" =~ ^sha256:[0-9a-f]{64}$ ]]
then
  fail "runtime config digest has an unexpected format"
fi
if [[ ! -x "${DOCKER_BIN}" ]]; then
  fail "Docker CLI is not executable"
fi
if [[ ! -x "${LOCKF_BIN}" ]]; then
  fail "lockf is not executable"
fi
if [[ ! -x "${CURL_BIN}" ]]; then
  fail "curl is not executable"
fi
if [[ ! -x "${ORIGIN_VALIDATOR}" ]]; then
  fail "HTTPS origin validator is not executable"
fi
if [[ ! -f "${COMPOSE_FILE}" || -L "${COMPOSE_FILE}" ]]; then
  fail "production Compose file is missing or unsafe"
fi
require_private_file "${ENV_FILE}" "production environment file"
require_private_file "${SMOKE_ORIGIN_FILE}" "tailnet smoke origin file"
if [[ -L "${OPERATION_LOCK}" ]] \
  || { [[ -e "${OPERATION_LOCK}" ]] && [[ ! -f "${OPERATION_LOCK}" ]]; }
then
  fail "operation lock must be a regular non-symlink file"
fi

umask 077
exec 9>>"${OPERATION_LOCK}"
/bin/chmod 600 "${OPERATION_LOCK}"
if "${LOCKF_BIN}" -s -t 0 9; then
  :
else
  status="$?"
  if [[ "${status}" -eq 75 ]]; then
    printf 'Another HomeOps operation is already running\n' >&2
    exit 75
  fi
  fail "operation lock failed"
fi

current_sha="${ZERO_SHA}"
previous_sha="${ZERO_SHA}"
current_api_digest="${ZERO_DIGEST}"
previous_api_digest="${ZERO_DIGEST}"
current_web_digest="${ZERO_DIGEST}"
previous_web_digest="${ZERO_DIGEST}"
current_runtime_digest="${ZERO_DIGEST}"
previous_runtime_digest="${ZERO_DIGEST}"
if [[ -e "${STATE_FILE}" || -L "${STATE_FILE}" ]]; then
  require_private_file "${STATE_FILE}" "deployment state"
  state_keys="$(/usr/bin/awk -F= 'NF == 2 { print $1 }' "${STATE_FILE}" | LC_ALL=C /usr/bin/sort)"
  if [[ "${state_keys}" != $'API_IMAGE_DIGEST\nCURRENT_SHA\nPREVIOUS_API_IMAGE_DIGEST\nPREVIOUS_RUNTIME_CONFIG_DIGEST\nPREVIOUS_SHA\nPREVIOUS_WEB_IMAGE_DIGEST\nRUNTIME_CONFIG_DIGEST\nWEB_IMAGE_DIGEST' ]]; then
    fail "deployment state keys are invalid"
  fi
  current_sha="$(/usr/bin/sed -n 's/^CURRENT_SHA=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  previous_sha="$(/usr/bin/sed -n 's/^PREVIOUS_SHA=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  current_api_digest="$(/usr/bin/sed -n 's/^API_IMAGE_DIGEST=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  previous_api_digest="$(/usr/bin/sed -n 's/^PREVIOUS_API_IMAGE_DIGEST=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  current_web_digest="$(/usr/bin/sed -n 's/^WEB_IMAGE_DIGEST=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  previous_web_digest="$(/usr/bin/sed -n 's/^PREVIOUS_WEB_IMAGE_DIGEST=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  current_runtime_digest="$(/usr/bin/sed -n 's/^RUNTIME_CONFIG_DIGEST=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  previous_runtime_digest="$(/usr/bin/sed -n 's/^PREVIOUS_RUNTIME_CONFIG_DIGEST=//p' "${STATE_FILE}" | /usr/bin/tail -n 1)"
  if [[ ! "${current_sha}" =~ ^[0-9a-f]{40}$ ]] \
    || [[ ! "${previous_sha}" =~ ^[0-9a-f]{40}$ ]] \
    || [[ ! "${current_api_digest}" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || [[ ! "${previous_api_digest}" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || [[ ! "${current_web_digest}" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || [[ ! "${previous_web_digest}" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || [[ ! "${current_runtime_digest}" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || [[ ! "${previous_runtime_digest}" =~ ^sha256:[0-9a-f]{64}$ ]]
  then
    fail "deployment state values are invalid"
  fi
fi

if [[ "${current_runtime_digest}" != "${ZERO_DIGEST}" ]]; then
  expected_current_target="releases/${current_runtime_digest#sha256:}"
  if [[ ! -L "${RUNTIME_CONFIG_CURRENT}" ]] \
    || [[ "$(/usr/bin/readlink "${RUNTIME_CONFIG_CURRENT}")" != "${expected_current_target}" ]]
  then
    fail "runtime config current pointer does not match deployment state"
  fi
fi

if [[ "${CANDIDATE_RUNTIME_DIGEST}" != "${ZERO_DIGEST}" ]]; then
  expected_runtime_dir="${RUNTIME_CONFIG_RELEASES}/${CANDIDATE_RUNTIME_DIGEST#sha256:}"
  if [[ "${RUNTIME_DIR}" != "${expected_runtime_dir}" ]]; then
    fail "runtime directory does not match the candidate digest"
  fi
  if [[ ! -L "${RUNTIME_CONFIG_PENDING}" ]] \
    || [[ "$(/usr/bin/readlink "${RUNTIME_CONFIG_PENDING}")" != "releases/${CANDIDATE_RUNTIME_DIGEST#sha256:}" ]]
  then
    fail "runtime config pending pointer does not match the candidate"
  fi
fi

API_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-api@${CANDIDATE_API_DIGEST}"
WEB_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-web@${CANDIDATE_WEB_DIGEST}"

compose() {
  HOMEOPS_API_IMAGE="${API_IMAGE}" \
  HOMEOPS_WEB_IMAGE="${WEB_IMAGE}" \
    "${DOCKER_BIN}" compose \
      --project-directory "${APP_DIR}" \
      --env-file "${ENV_FILE}" \
      --file "${COMPOSE_FILE}" \
      "$@"
}

validate_image_revision() {
  local expected_revision="$1"
  local image="$2"
  local actual_revision

  actual_revision="$("${DOCKER_BIN}" image inspect \
    --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' \
    "${image}")" || return 1
  [[ "${actual_revision}" == "${expected_revision}" ]]
}

wait_for_health() {
  attempt=0
  while [[ "${attempt}" -lt 24 ]]; do
    api_id="$(compose ps --quiet api)"
    web_id="$(compose ps --quiet web)"
    if [[ -n "${api_id}" && -n "${web_id}" ]]; then
      api_health="$("${DOCKER_BIN}" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${api_id}")"
      web_health="$("${DOCKER_BIN}" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${web_id}")"
      if [[ "${api_health}" == healthy && "${web_health}" == healthy ]]; then
        return 0
      fi
    fi
    /bin/sleep 5
    attempt=$((attempt + 1))
  done
  return 1
}

public_smoke() {
  local asset_path
  local html
  local origin

  if [[ "$(/usr/bin/wc -l <"${SMOKE_ORIGIN_FILE}" | /usr/bin/tr -d '[:space:]')" != 1 ]]; then
    return 1
  fi
  IFS= read -r origin <"${SMOKE_ORIGIN_FILE}"
  if ! "${ORIGIN_VALIDATOR}" "${origin}"; then
    return 1
  fi
  html="$("${CURL_BIN}" \
    --fail \
    --silent \
    --connect-timeout 5 \
    --max-time 20 \
    --retry 3 \
    --retry-delay 2 \
    "${origin}/")" || return 1
  [[ -n "${html}" ]] || return 1
  asset_path="$(printf '%s' "${html}" \
    | /usr/bin/sed -n 's/.*src="\(\/assets\/[A-Za-z0-9._-]*\.js\)".*/\1/p' \
    | /usr/bin/head -n 1)"
  [[ "${asset_path}" =~ ^/assets/[A-Za-z0-9._-]+\.js$ ]] || return 1
  "${CURL_BIN}" \
    --fail \
    --silent \
    --connect-timeout 5 \
    --max-time 20 \
    --retry 3 \
    --retry-delay 2 \
    --output /dev/null \
    "${origin}${asset_path}" || return 1
  "${CURL_BIN}" \
    --fail \
    --silent \
    --connect-timeout 5 \
    --max-time 20 \
    --retry 3 \
    --retry-delay 2 \
    --output /dev/null \
    "${origin}/actuator/health/readiness"
}

rollback_previous_application() {
  if [[ "${current_sha}" == "${ZERO_SHA}" ]] \
    || [[ "${current_api_digest}" == "${ZERO_DIGEST}" ]] \
    || [[ "${current_web_digest}" == "${ZERO_DIGEST}" ]]
  then
    return 1
  fi
  API_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-api@${current_api_digest}"
  WEB_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-web@${current_web_digest}"
  if [[ "${current_runtime_digest}" != "${ZERO_DIGEST}" ]]; then
    rollback_compose="${RUNTIME_CONFIG_RELEASES}/${current_runtime_digest#sha256:}/compose.yaml"
    if [[ ! -f "${rollback_compose}" || -L "${rollback_compose}" ]]; then
      return 1
    fi
    COMPOSE_FILE="${rollback_compose}"
  fi
  compose config --quiet \
    && compose pull api web \
    && validate_image_revision "${current_sha}" "${API_IMAGE}" \
    && validate_image_revision "${current_sha}" "${WEB_IMAGE}" \
    && compose up -d --remove-orphans api web \
    && wait_for_health \
    && public_smoke
}

compose config --quiet
compose pull api web migration
if ! validate_image_revision "${COMMIT_SHA}" "${API_IMAGE}" \
  || ! validate_image_revision "${COMMIT_SHA}" "${WEB_IMAGE}"
then
  fail "candidate application image revision is invalid"
fi
compose up -d db
compose stop api
if ! compose --profile operations run --rm migration; then
  if rollback_previous_application; then
    fail "migration failed; current application recovery succeeded"
  else
    if [[ "${current_sha}" == "${ZERO_SHA}" ]]; then
      compose stop api web >/dev/null 2>&1 || true
    fi
    fail "migration failed; current application recovery was unavailable or failed"
  fi
fi
compose up -d --remove-orphans api web

if ! wait_for_health || ! public_smoke; then
  if rollback_previous_application; then
    fail "candidate verification failed; previous application rollback succeeded"
  else
    if [[ "${current_sha}" == "${ZERO_SHA}" ]]; then
      compose stop api web >/dev/null 2>&1 || true
    fi
    fail "candidate verification failed; previous application rollback was unavailable or failed"
  fi
fi

state_temp="$(/usr/bin/mktemp "${APP_DIR}/.deployment-state.XXXXXX")"
current_temp=""
cleanup_transaction_files() {
  if [[ -n "${state_temp}" ]]; then
    /bin/rm -f -- "${state_temp}"
  fi
  if [[ -n "${current_temp}" ]]; then
    /bin/rm -f -- "${current_temp}"
  fi
}
trap cleanup_transaction_files EXIT INT TERM
{
  printf 'API_IMAGE_DIGEST=%s\n' "${CANDIDATE_API_DIGEST}"
  printf 'CURRENT_SHA=%s\n' "${COMMIT_SHA}"
  printf 'WEB_IMAGE_DIGEST=%s\n' "${CANDIDATE_WEB_DIGEST}"
  printf 'PREVIOUS_API_IMAGE_DIGEST=%s\n' "${current_api_digest}"
  printf 'PREVIOUS_SHA=%s\n' "${current_sha}"
  printf 'PREVIOUS_WEB_IMAGE_DIGEST=%s\n' "${current_web_digest}"
  printf 'RUNTIME_CONFIG_DIGEST=%s\n' "${CANDIDATE_RUNTIME_DIGEST}"
  printf 'PREVIOUS_RUNTIME_CONFIG_DIGEST=%s\n' "${current_runtime_digest}"
} >"${state_temp}"
/bin/chmod 600 "${state_temp}"

if [[ "${CANDIDATE_RUNTIME_DIGEST}" != "${ZERO_DIGEST}" ]]; then
  current_temp="${RUNTIME_CONFIG_ROOT}/.current.$$"
  /bin/ln -s "releases/${CANDIDATE_RUNTIME_DIGEST#sha256:}" "${current_temp}"
  /bin/mv -fh -- "${current_temp}" "${RUNTIME_CONFIG_CURRENT}"
  current_temp=""
fi

/bin/mv -f -- "${state_temp}" "${STATE_FILE}"
state_temp=""

if [[ "${CANDIDATE_RUNTIME_DIGEST}" != "${ZERO_DIGEST}" ]]; then
  /bin/rm -f -- "${RUNTIME_CONFIG_PENDING}"
fi
trap - EXIT INT TERM

printf 'HomeOps deployment completed for revision %s\n' "${COMMIT_SHA}"
