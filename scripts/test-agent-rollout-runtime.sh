#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly SOURCE_WORKER="${REPOSITORY_ROOT}/deploy/scripts/rollout-homeops-agent.sh"
readonly MOCK_LOCKF="${SCRIPT_DIR}/fixtures/mock-homeops-lockf.sh"
readonly MOCK_LAUNCHCTL="${SCRIPT_DIR}/fixtures/mock-homeops-launchctl.sh"
readonly ONE=1111111111111111111111111111111111111111
readonly TWO=2222222222222222222222222222222222222222

test_root="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/homeops-agent-rollout.XXXXXX")"
cleanup() {
  if [[ -d "${test_root}" ]] && [[ "$(/usr/bin/basename "${test_root}")" == homeops-agent-rollout.* ]]; then
    /bin/rm -rf -- "${test_root}"
  fi
}
trap cleanup EXIT INT TERM

worker="${test_root}/rollout-homeops-agent.sh"
/usr/bin/sed \
  -e "s#readonly LOCKF_BIN=/usr/bin/lockf#readonly LOCKF_BIN=${MOCK_LOCKF}#" \
  -e "s#readonly LAUNCHCTL_BIN=/bin/launchctl#readonly LAUNCHCTL_BIN=${MOCK_LAUNCHCTL}#" \
  -e 's#/bin/sleep 1#:#g' \
  -e 's#/bin/sleep 2#:#g' \
  "${SOURCE_WORKER}" >"${worker}"
/bin/chmod 700 "${worker}"

app_dir=""
proof_file=""
prepare_case() {
  local name="$1"
  app_dir="${test_root}/${name}/app"
  proof_file="${app_dir}/agent/version-proof"
  /bin/mkdir -p "${app_dir}/agent/releases"
  /bin/chmod 700 "${app_dir}" "${app_dir}/agent" "${app_dir}/agent/releases"
  make_release "${ONE}"
  make_release "${TWO}"
}

make_release() {
  local sha="$1"
  local release="${app_dir}/agent/releases/${sha}"
  /bin/mkdir -p "${release}"
  /bin/cp /usr/bin/true "${release}/homeops-agent"
  (cd "${release}" && /usr/bin/shasum -a 256 homeops-agent >homeops-agent.sha256)
  /bin/chmod 700 "${release}" "${release}/homeops-agent"
  /bin/chmod 600 "${release}/homeops-agent.sha256"
}

run_worker() {
  local sha="$1"
  /usr/bin/env \
    HOMEOPS_AGENT_APP_DIR="${app_dir}" \
    FAKE_AGENT_PROOF_FILE="${proof_file}" \
    FAKE_AGENT_PROOF_SHA="${sha}" \
    FAKE_LOCKF_EXIT_CODE="${FAKE_LOCKF_EXIT_CODE:-0}" \
    FAKE_LAUNCHCTL_FAIL="${FAKE_LAUNCHCTL_FAIL:-false}" \
    /bin/bash "${worker}" "${sha}"
}

assert_failure() {
  set +e
  "$@" >"${test_root}/failure.log" 2>&1
  local status="$?"
  set -e
  [[ "${status}" -ne 0 ]] || { printf 'Expected failure\n' >&2; exit 1; }
}

prepare_case first-install
run_worker "${ONE}"
test "$(/usr/bin/readlink "${app_dir}/agent/current")" = "releases/${ONE}"
test ! -e "${app_dir}/agent/previous"
test ! -e "${app_dir}/agent/pending"

prepare_case promotion
run_worker "${ONE}"
run_worker "${TWO}"
test "$(/usr/bin/readlink "${app_dir}/agent/current")" = "releases/${TWO}"
test "$(/usr/bin/readlink "${app_dir}/agent/previous")" = "releases/${ONE}"

prepare_case rollback
run_worker "${ONE}"
FAKE_LAUNCHCTL_FAIL=true assert_failure run_worker "${TWO}"
test "$(/usr/bin/readlink "${app_dir}/agent/current")" = "releases/${ONE}"
test "$(/usr/bin/readlink "${app_dir}/agent/previous")" = "releases/${ONE}"
test ! -e "${app_dir}/agent/pending"

prepare_case checksum
printf 'corrupt\n' >"${app_dir}/agent/releases/${ONE}/homeops-agent.sha256"
/bin/chmod 600 "${app_dir}/agent/releases/${ONE}/homeops-agent.sha256"
assert_failure run_worker "${ONE}"
test ! -e "${app_dir}/agent/current"

prepare_case lock
FAKE_LOCKF_EXIT_CODE=75 assert_failure run_worker "${ONE}"
test ! -e "${app_dir}/agent/current"

printf 'Agent rollout runtime regression tests passed\n'
