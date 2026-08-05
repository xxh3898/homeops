#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly SOURCE_BOOTSTRAP="${REPOSITORY_ROOT}/deploy/bootstrap/deploy-homeops-ci.sh.example"
readonly SOURCE_WORKER="${REPOSITORY_ROOT}/deploy/scripts/deploy-homeops.sh"
readonly SOURCE_VALIDATOR="${REPOSITORY_ROOT}/deploy/scripts/validate-https-origin.sh"
readonly SOURCE_REPORTER="${REPOSITORY_ROOT}/deploy/scripts/report-homeops-event.py"
readonly SOURCE_COMPOSE="${REPOSITORY_ROOT}/deploy/compose.example.yaml"
readonly SOURCE_ENV="${REPOSITORY_ROOT}/deploy/env.example"
readonly MOCK_DOCKER="${SCRIPT_DIR}/fixtures/mock-homeops-docker.sh"
readonly MOCK_CURL="${SCRIPT_DIR}/fixtures/mock-homeops-curl.sh"
readonly MOCK_LOCKF="${SCRIPT_DIR}/fixtures/mock-homeops-lockf.sh"
readonly MOCK_SLEEP="${SCRIPT_DIR}/fixtures/mock-homeops-sleep.sh"
readonly MOCK_STAT="${SCRIPT_DIR}/fixtures/mock-homeops-stat.sh"

readonly REVISION_ONE=1111111111111111111111111111111111111111
readonly REVISION_TWO=2222222222222222222222222222222222222222
readonly API_DIGEST_ONE=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
readonly WEB_DIGEST_ONE=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
readonly CONFIG_DIGEST_ONE=sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
readonly API_DIGEST_TWO=sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
readonly WEB_DIGEST_TWO=sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
readonly CONFIG_DIGEST_TWO=sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
readonly ZERO_DIGEST=sha256:0000000000000000000000000000000000000000000000000000000000000000
readonly REGISTRY_TOKEN='homeops-regression-secret-marker'
readonly SMOKE_ORIGIN='https://homeops.example.invalid:9443'

test_root="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/homeops-deploy-test.XXXXXX")"
cleanup() {
  if [[ -d "${test_root}" ]] \
    && [[ "$(/usr/bin/basename "${test_root}")" == homeops-deploy-test.* ]]
  then
    /bin/rm -rf -- "${test_root}"
  fi
}
trap cleanup EXIT INT TERM

runtime_worker="${test_root}/deploy-homeops.sh"
/usr/bin/sed \
  -e "s#readonly DOCKER_BIN=/usr/local/bin/docker#readonly DOCKER_BIN=${MOCK_DOCKER}#" \
  -e "s#readonly LOCKF_BIN=/usr/bin/lockf#readonly LOCKF_BIN=${MOCK_LOCKF}#" \
  -e "s#readonly CURL_BIN=/usr/bin/curl#readonly CURL_BIN=${MOCK_CURL}#" \
  -e "s#/usr/bin/stat#${MOCK_STAT}#g" \
  -e "s#/bin/sleep#${MOCK_SLEEP}#g" \
  -e 's#/bin/mv -fh --#/bin/mv -f --#g' \
  "${SOURCE_WORKER}" \
  >"${runtime_worker}"
/bin/chmod 700 "${runtime_worker}"

app_dir=
bootstrap_script=
docker_log=
curl_log=
case_output=
compose_plugin=

prepare_case() {
  local case_name="$1"
  local case_root="${test_root}/${case_name}"

  app_dir="${case_root}/app"
  bootstrap_script="${case_root}/deploy-homeops-ci.sh"
  docker_log="${case_root}/docker.log"
  curl_log="${case_root}/curl.log"
  case_output="${case_root}/output.log"
  compose_plugin="${case_root}/docker-compose"
  /bin/mkdir -p "${app_dir}"
  /bin/cp "${MOCK_DOCKER}" "${compose_plugin}"
  /bin/chmod 700 "${compose_plugin}"
  /bin/cp "${SOURCE_ENV}" "${app_dir}/.env"
  /bin/chmod 600 "${app_dir}/.env"
  printf '%s\n' "${SMOKE_ORIGIN}" >"${app_dir}/smoke.origin"
  /bin/chmod 600 "${app_dir}/smoke.origin"
  : >"${docker_log}"
  : >"${curl_log}"
  : >"${case_output}"

  /usr/bin/sed \
    -e "s#readonly DOCKER_BIN=/usr/local/bin/docker#readonly DOCKER_BIN=${MOCK_DOCKER}#" \
    -e "s#readonly LOCKF_BIN=/usr/bin/lockf#readonly LOCKF_BIN=${MOCK_LOCKF}#" \
    -e "s#readonly APP_DIR=/Users/REPLACE_ME/Server/apps/homeops#readonly APP_DIR=${app_dir}#" \
    -e 's#readonly RUNTIME_CONFIG_REPOSITORY=ghcr.io/REPLACE_ME/homeops-runtime-config#readonly RUNTIME_CONFIG_REPOSITORY=ghcr.io/example/homeops-runtime-config#' \
    -e "s#/usr/bin/stat#${MOCK_STAT}#g" \
    "${SOURCE_BOOTSTRAP}" \
    >"${bootstrap_script}"
  /bin/chmod 700 "${bootstrap_script}"
}

run_bootstrap() {
  local revision="$1"
  local api_digest="$2"
  local web_digest="$3"
  local config_digest="$4"

  printf '%s' "${REGISTRY_TOKEN}" \
    | /usr/bin/env \
        SSH_ORIGINAL_COMMAND="deploy-homeops-v2 ${revision} ${api_digest} ${web_digest} ${config_digest} example testbot" \
        FAKE_RUNTIME_COMPOSE="${SOURCE_COMPOSE}" \
        FAKE_RUNTIME_WORKER="${runtime_worker}" \
        FAKE_RUNTIME_VALIDATOR="${SOURCE_VALIDATOR}" \
        FAKE_RUNTIME_REPORTER="${SOURCE_REPORTER}" \
        FAKE_DOCKER_LOG="${docker_log}" \
        FAKE_COMPOSE_PLUGIN="${FAKE_COMPOSE_PLUGIN_OVERRIDE:-${compose_plugin}}" \
        FAKE_CURL_LOG="${curl_log}" \
        FAKE_CONFIG_REVISION="${revision}" \
        FAKE_CONFIG_PROJECT="${FAKE_CONFIG_PROJECT:-homeops}" \
        FAKE_CANDIDATE_REVISION="${revision}" \
        FAKE_CANDIDATE_API_DIGEST="${api_digest}" \
        FAKE_CANDIDATE_WEB_DIGEST="${web_digest}" \
        FAKE_PREVIOUS_REVISION="${FAKE_PREVIOUS_REVISION:-${REVISION_ONE}}" \
        FAKE_PREVIOUS_API_DIGEST="${FAKE_PREVIOUS_API_DIGEST:-not-a-digest}" \
        FAKE_PREVIOUS_WEB_DIGEST="${FAKE_PREVIOUS_WEB_DIGEST:-not-a-digest}" \
        FAKE_API_REVISION_OVERRIDE="${FAKE_API_REVISION_OVERRIDE:-}" \
        FAKE_WEB_REVISION_OVERRIDE="${FAKE_WEB_REVISION_OVERRIDE:-}" \
        FAKE_UNHEALTHY_API_DIGEST="${FAKE_UNHEALTHY_API_DIGEST:-}" \
        FAKE_ALWAYS_UNHEALTHY="${FAKE_ALWAYS_UNHEALTHY:-false}" \
        FAKE_CURL_FAIL_ONCE_FILE="${FAKE_CURL_FAIL_ONCE_FILE:-}" \
        FAKE_MIGRATION_FAIL="${FAKE_MIGRATION_FAIL:-false}" \
        FAKE_CONFIG_FAIL="${FAKE_CONFIG_FAIL:-false}" \
        FAKE_RUNTIME_EXTRA_FILE="${FAKE_RUNTIME_EXTRA_FILE:-false}" \
        FAKE_RUNTIME_SYMLINK="${FAKE_RUNTIME_SYMLINK:-false}" \
        FAKE_RUNTIME_INVALID_VALIDATOR="${FAKE_RUNTIME_INVALID_VALIDATOR:-false}" \
        FAKE_LOCKF_EXIT_CODE="${FAKE_LOCKF_EXIT_CODE:-0}" \
        /bin/bash "${bootstrap_script}"
}

assert_failure() {
  local expected_exit="$1"
  shift
  local exit_code

  set +e
  "$@" >"${case_output}" 2>&1
  exit_code="$?"
  set -e
  if [[ "${exit_code}" -ne "${expected_exit}" ]]; then
    printf 'Expected exit %s, got %s\n' "${expected_exit}" "${exit_code}" >&2
    /bin/cat "${case_output}" >&2
    exit 1
  fi
}

assert_secret_absent() {
  if /usr/bin/grep -Fq -- "${REGISTRY_TOKEN}" \
    "${case_output}" "${docker_log}" "${curl_log}"
  then
    printf 'Registry token leaked into deployment regression output\n' >&2
    exit 1
  fi
}

assert_state_revision() {
  local expected_revision="$1"
  local expected_api_digest="$2"
  local expected_web_digest="$3"
  local expected_config_digest="$4"
  local state_file="${app_dir}/deployment.state"

  test -f "${state_file}"
  test ! -L "${state_file}"
  test "$("${MOCK_STAT}" -f '%OLp' "${state_file}")" = 600
  /usr/bin/grep -Fxq "CURRENT_SHA=${expected_revision}" "${state_file}"
  /usr/bin/grep -Fxq "API_IMAGE_DIGEST=${expected_api_digest}" "${state_file}"
  /usr/bin/grep -Fxq "WEB_IMAGE_DIGEST=${expected_web_digest}" "${state_file}"
  /usr/bin/grep -Fxq "RUNTIME_CONFIG_DIGEST=${expected_config_digest}" "${state_file}"
}

prepare_case initial-success
run_bootstrap \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}" \
  >"${case_output}" 2>&1
assert_state_revision \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}"
test -L "${app_dir}/runtime-config/current"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/current")" \
  = "releases/${CONFIG_DIGEST_ONE#sha256:}"
test ! -e "${app_dir}/runtime-config/pending"
/usr/bin/grep -Fxq "${SMOKE_ORIGIN}/" "${curl_log}"
/usr/bin/grep -Fq -- '--profile operations run --rm migration' "${docker_log}"
assert_secret_absent

prepare_case compose-plugin-missing
FAKE_COMPOSE_PLUGIN_OVERRIDE="${app_dir}/missing-docker-compose" \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
/usr/bin/grep -Fq -- 'Docker Compose plugin is missing or unsafe' "${case_output}"
test ! -e "${app_dir}/runtime-config/pending"
test ! -e "${app_dir}/deployment.state"
assert_secret_absent

prepare_case initial-smoke-failure
smoke_failure_marker="${test_root}/initial-smoke-failure.marker"
FAKE_CURL_FAIL_ONCE_FILE="${smoke_failure_marker}" \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test -f "${smoke_failure_marker}"
test ! -e "${app_dir}/deployment.state"
test ! -e "${app_dir}/runtime-config/current"
test -L "${app_dir}/runtime-config/pending"
/usr/bin/grep -Fq -- 'stop api web' "${docker_log}"
assert_secret_absent

prepare_case migration-failure
FAKE_MIGRATION_FAIL=true \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/deployment.state"
test ! -e "${app_dir}/runtime-config/current"
test -L "${app_dir}/runtime-config/pending"
if /usr/bin/grep -Fq -- 'up -d --remove-orphans api web' "${docker_log}"; then
  printf 'Application cutover occurred after a failed migration\n' >&2
  exit 1
fi
assert_secret_absent

prepare_case application-identity-failure
FAKE_API_REVISION_OVERRIDE="${REVISION_TWO}" \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/deployment.state"
test ! -e "${app_dir}/runtime-config/current"
test -L "${app_dir}/runtime-config/pending"
if /usr/bin/grep -Fq -- 'up -d db' "${docker_log}"; then
  printf 'Database started before candidate image identity validation completed\n' >&2
  exit 1
fi
assert_secret_absent

prepare_case web-identity-failure
FAKE_WEB_REVISION_OVERRIDE="${REVISION_TWO}" \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/deployment.state"
test ! -e "${app_dir}/runtime-config/current"
test -L "${app_dir}/runtime-config/pending"
if /usr/bin/grep -Fq -- 'up -d db' "${docker_log}"; then
  printf 'Database started before Web image identity validation completed\n' >&2
  exit 1
fi
assert_secret_absent

prepare_case compose-config-failure
FAKE_CONFIG_FAIL=true \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/deployment.state"
test ! -e "${app_dir}/runtime-config/current"
test -L "${app_dir}/runtime-config/pending"
if /usr/bin/grep -Fq -- ' pull api web migration' "${docker_log}"; then
  printf 'Images were pulled after Compose validation failed\n' >&2
  exit 1
fi
assert_secret_absent

prepare_case rollback-success
run_bootstrap \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}" \
  >"${case_output}" 2>&1
: >"${docker_log}"
: >"${curl_log}"
FAKE_PREVIOUS_REVISION="${REVISION_ONE}" \
FAKE_PREVIOUS_API_DIGEST="${API_DIGEST_ONE}" \
FAKE_PREVIOUS_WEB_DIGEST="${WEB_DIGEST_ONE}" \
FAKE_UNHEALTHY_API_DIGEST="${API_DIGEST_TWO}" \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_TWO}" \
      "${API_DIGEST_TWO}" \
      "${WEB_DIGEST_TWO}" \
      "${CONFIG_DIGEST_TWO}"
assert_state_revision \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/current")" \
  = "releases/${CONFIG_DIGEST_ONE#sha256:}"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/pending")" \
  = "releases/${CONFIG_DIGEST_TWO#sha256:}"
/usr/bin/grep -Fq -- "api=ghcr.io/example/homeops-api@${API_DIGEST_ONE}" "${docker_log}"
/usr/bin/grep -Fq -- 'previous application rollback succeeded' "${case_output}"
assert_secret_absent

prepare_case rollback-failure
run_bootstrap \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}" \
  >"${case_output}" 2>&1
: >"${docker_log}"
: >"${curl_log}"
FAKE_PREVIOUS_REVISION="${REVISION_ONE}" \
FAKE_PREVIOUS_API_DIGEST="${API_DIGEST_ONE}" \
FAKE_PREVIOUS_WEB_DIGEST="${WEB_DIGEST_ONE}" \
FAKE_ALWAYS_UNHEALTHY=true \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_TWO}" \
      "${API_DIGEST_TWO}" \
      "${WEB_DIGEST_TWO}" \
      "${CONFIG_DIGEST_TWO}"
assert_state_revision \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/current")" \
  = "releases/${CONFIG_DIGEST_ONE#sha256:}"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/pending")" \
  = "releases/${CONFIG_DIGEST_TWO#sha256:}"
/usr/bin/grep -Fq -- 'rollback was unavailable or failed' "${case_output}"
assert_secret_absent

prepare_case identical-retry
run_bootstrap \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}" \
  >"${case_output}" 2>&1
run_bootstrap \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}" \
  >"${case_output}" 2>&1
assert_state_revision \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/current")" \
  = "releases/${CONFIG_DIGEST_ONE#sha256:}"
test ! -e "${app_dir}/runtime-config/pending"
assert_secret_absent

prepare_case runtime-identity-failure
FAKE_CONFIG_PROJECT=other-project \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/runtime-config/pending"
test ! -e "${app_dir}/deployment.state"
assert_secret_absent

prepare_case runtime-shape-failure
FAKE_RUNTIME_EXTRA_FILE=true \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/runtime-config/pending"
test ! -e "${app_dir}/deployment.state"
assert_secret_absent

prepare_case runtime-symlink-failure
FAKE_RUNTIME_SYMLINK=true \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/runtime-config/pending"
test ! -e "${app_dir}/deployment.state"
assert_secret_absent

prepare_case runtime-validator-syntax-failure
FAKE_RUNTIME_INVALID_VALIDATOR=true \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/runtime-config/pending"
test ! -e "${app_dir}/deployment.state"
assert_secret_absent

prepare_case invalid-state
run_bootstrap \
  "${REVISION_ONE}" \
  "${API_DIGEST_ONE}" \
  "${WEB_DIGEST_ONE}" \
  "${CONFIG_DIGEST_ONE}" \
  >"${case_output}" 2>&1
printf 'UNKNOWN=value\n' >"${app_dir}/deployment.state"
/bin/chmod 600 "${app_dir}/deployment.state"
FAKE_PREVIOUS_REVISION="${REVISION_ONE}" \
FAKE_PREVIOUS_API_DIGEST="${API_DIGEST_ONE}" \
FAKE_PREVIOUS_WEB_DIGEST="${WEB_DIGEST_ONE}" \
  assert_failure 1 \
    run_bootstrap \
      "${REVISION_TWO}" \
      "${API_DIGEST_TWO}" \
      "${WEB_DIGEST_TWO}" \
      "${CONFIG_DIGEST_TWO}"
/usr/bin/grep -Fxq 'UNKNOWN=value' "${app_dir}/deployment.state"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/current")" \
  = "releases/${CONFIG_DIGEST_ONE#sha256:}"
test "$(/usr/bin/readlink "${app_dir}/runtime-config/pending")" \
  = "releases/${CONFIG_DIGEST_TWO#sha256:}"
assert_secret_absent

prepare_case existing-pending
/bin/mkdir -p "${app_dir}/runtime-config/releases"
/bin/ln -s releases/unresolved "${app_dir}/runtime-config/pending"
assert_failure 1 \
  run_bootstrap \
    "${REVISION_ONE}" \
    "${API_DIGEST_ONE}" \
    "${WEB_DIGEST_ONE}" \
    "${CONFIG_DIGEST_ONE}"
/usr/bin/grep -Fq -- 'requires recovery' "${case_output}"
test -L "${app_dir}/runtime-config/pending"
assert_secret_absent

prepare_case zero-digest
assert_failure 1 \
  run_bootstrap \
    "${REVISION_ONE}" \
    "${ZERO_DIGEST}" \
    "${WEB_DIGEST_ONE}" \
    "${CONFIG_DIGEST_ONE}"
test ! -e "${app_dir}/runtime-config/pending"
assert_secret_absent

prepare_case lock-contention
FAKE_LOCKF_EXIT_CODE=75 \
  assert_failure 75 \
    run_bootstrap \
      "${REVISION_ONE}" \
      "${API_DIGEST_ONE}" \
      "${WEB_DIGEST_ONE}" \
      "${CONFIG_DIGEST_ONE}"
/usr/bin/grep -Fq -- 'Another HomeOps bootstrap is already running' "${case_output}"
test ! -e "${app_dir}/runtime-config/pending"
assert_secret_absent

printf 'HomeOps deployment runtime regression tests passed\n'
