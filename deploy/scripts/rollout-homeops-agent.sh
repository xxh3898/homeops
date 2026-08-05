#!/bin/bash

set -Eeuo pipefail

readonly LOCKF_BIN=/usr/bin/lockf
readonly LAUNCHCTL_BIN=/bin/launchctl
readonly APP_DIR="${HOMEOPS_AGENT_APP_DIR:?HOMEOPS_AGENT_APP_DIR is required}"
readonly AGENT_ROOT="${APP_DIR}/agent"
readonly RELEASES_DIR="${AGENT_ROOT}/releases"
readonly CURRENT_LINK="${AGENT_ROOT}/current"
readonly PREVIOUS_LINK="${AGENT_ROOT}/previous"
readonly PENDING_LINK="${AGENT_ROOT}/pending"
readonly PROOF_FILE="${AGENT_ROOT}/version-proof"
readonly OPERATION_LOCK="${AGENT_ROOT}/.rollout.lock"
readonly AGENT_LABEL=dev.homeops.agent
readonly USER_DOMAIN="gui/$(/usr/bin/id -u)"

fail() {
  printf 'HomeOps Agent rollout failed: %s\n' "$1" >&2
  exit 1
}

require_regular_owned_file() {
  local path="$1"
  local mode="$2"
  local expected_owner
  expected_owner="$(/usr/bin/id -un)"
  [[ -f "${path}" && ! -L "${path}" ]] \
    && [[ "$(/usr/bin/stat -f '%Su' "${path}")" == "${expected_owner}" ]] \
    && [[ "$(/usr/bin/stat -f '%OLp' "${path}")" == "${mode}" ]]
}

release_target_for_sha() {
  printf 'releases/%s\n' "$1"
}

validate_release() {
  local sha="$1"
  local release_dir="${RELEASES_DIR}/${sha}"
  local entry_count

  [[ -d "${release_dir}" && ! -L "${release_dir}" ]] || return 1
  [[ -z "$(/usr/bin/find "${release_dir}" -type l -print -quit)" ]] || return 1
  entry_count="$(/usr/bin/find "${release_dir}" -mindepth 1 -maxdepth 1 -print | /usr/bin/wc -l | /usr/bin/tr -d '[:space:]')"
  [[ "${entry_count}" == 2 ]] || return 1
  require_regular_owned_file "${release_dir}/homeops-agent" 700 || return 1
  require_regular_owned_file "${release_dir}/homeops-agent.sha256" 600 || return 1
  [[ "$(/usr/bin/awk 'NR == 1 { print } END { if (NR != 1) exit 1 }' "${release_dir}/homeops-agent.sha256")" \
    =~ ^[0-9a-f]{64}[[:space:]][[:space:]]homeops-agent$ ]] || return 1
  (cd "${release_dir}" && /usr/bin/shasum -a 256 -c homeops-agent.sha256 >/dev/null) || return 1
}

read_pointer_sha() {
  local link="$1"
  local target
  local sha
  [[ -L "${link}" ]] || return 1
  target="$(/usr/bin/readlink "${link}")"
  if [[ ! "${target}" =~ ^releases/([0-9a-f]{40})$ ]]; then
    return 1
  fi
  sha="${BASH_REMATCH[1]}"
  validate_release "${sha}" || return 1
  printf '%s\n' "${sha}"
}

replace_pointer() {
  local link="$1"
  local sha="$2"
  local temporary="${AGENT_ROOT}/.$(/usr/bin/basename "${link}").$$"
  /bin/ln -s "$(release_target_for_sha "${sha}")" "${temporary}"
  /bin/mv -fh -- "${temporary}" "${link}"
}

clear_pending() {
  if [[ -L "${PENDING_LINK}" ]]; then
    /bin/rm -f -- "${PENDING_LINK}"
  fi
}

proof_is_fresh_for() {
  local sha="$1"
  local minimum_sent_at="$2"
  local sent_at
  [[ -f "${PROOF_FILE}" && ! -L "${PROOF_FILE}" ]] || return 1
  require_regular_owned_file "${PROOF_FILE}" 600 || return 1
  [[ "$(/usr/bin/wc -l <"${PROOF_FILE}" | /usr/bin/tr -d '[:space:]')" == 2 ]] || return 1
  /usr/bin/grep -Fxq "VERSION=${sha}" "${PROOF_FILE}" || return 1
  sent_at="$(/usr/bin/sed -n 's/^SENT_AT_UNIX=//p' "${PROOF_FILE}")"
  [[ "${sent_at}" =~ ^[0-9]{10,}$ ]] || return 1
  [[ "${sent_at}" -ge "${minimum_sent_at}" ]]
}

wait_for_proof() {
  local sha="$1"
  local minimum_sent_at="$2"
  local attempt=0
  while [[ "${attempt}" -lt 30 ]]; do
    if proof_is_fresh_for "${sha}" "${minimum_sent_at}"; then
      return 0
    fi
    /bin/sleep 2
    attempt=$((attempt + 1))
  done
  return 1
}

restart_agent() {
  "${LAUNCHCTL_BIN}" kickstart -k "${USER_DOMAIN}/${AGENT_LABEL}"
}

if [[ "$#" -ne 1 ]] || [[ ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  fail "expected exactly one full lowercase Agent commit SHA"
fi
readonly CANDIDATE_SHA="$1"

[[ -d "${APP_DIR}" && ! -L "${APP_DIR}" ]] || fail "Agent application directory is missing or unsafe"
[[ -x "${LOCKF_BIN}" && -x "${LAUNCHCTL_BIN}" ]] || fail "required host executable is unavailable"
if [[ -L "${AGENT_ROOT}" ]] \
  || { [[ -e "${AGENT_ROOT}" ]] && [[ ! -d "${AGENT_ROOT}" ]]; } \
  || [[ -L "${RELEASES_DIR}" ]] \
  || { [[ -e "${RELEASES_DIR}" ]] && [[ ! -d "${RELEASES_DIR}" ]]; }
then
  fail "Agent release directory is missing or unsafe"
fi
/bin/mkdir -p "${RELEASES_DIR}"
/bin/chmod 700 "${AGENT_ROOT}" "${RELEASES_DIR}"
if [[ -e "${OPERATION_LOCK}" && ! -f "${OPERATION_LOCK}" ]] || [[ -L "${OPERATION_LOCK}" ]]; then
  fail "Agent rollout lock must be a regular non-symlink file"
fi
exec 9>>"${OPERATION_LOCK}"
/bin/chmod 600 "${OPERATION_LOCK}"
if "${LOCKF_BIN}" -s -t 0 9; then
	:
else
	status="$?"
  [[ "${status}" -eq 75 ]] && exit 75
  fail "Agent rollout lock failed"
fi
if [[ -e "${PENDING_LINK}" || -L "${PENDING_LINK}" ]]; then
  fail "an incomplete Agent rollout transaction requires recovery"
fi
validate_release "${CANDIDATE_SHA}" || fail "candidate Agent release is invalid"

current_sha=""
if [[ -e "${CURRENT_LINK}" || -L "${CURRENT_LINK}" ]]; then
  current_sha="$(read_pointer_sha "${CURRENT_LINK}")" || fail "current Agent pointer is invalid"
fi
if [[ -e "${PREVIOUS_LINK}" || -L "${PREVIOUS_LINK}" ]]; then
  read_pointer_sha "${PREVIOUS_LINK}" >/dev/null || fail "previous Agent pointer is invalid"
fi
if [[ "${current_sha}" == "${CANDIDATE_SHA}" ]]; then
  printf 'HomeOps Agent rollout already has revision %s\n' "${CANDIDATE_SHA}"
  exit 0
fi

/bin/ln -s "$(release_target_for_sha "${CANDIDATE_SHA}")" "${PENDING_LINK}"
minimum_sent_at=$(( $(/bin/date +%s) + 1 ))
/bin/sleep 1
if [[ -n "${current_sha}" ]]; then
  replace_pointer "${PREVIOUS_LINK}" "${current_sha}"
fi
replace_pointer "${CURRENT_LINK}" "${CANDIDATE_SHA}"

if restart_agent && wait_for_proof "${CANDIDATE_SHA}" "${minimum_sent_at}"; then
  clear_pending
  printf 'HomeOps Agent rollout completed for revision %s\n' "${CANDIDATE_SHA}"
  exit 0
fi

if [[ -n "${current_sha}" ]]; then
  replace_pointer "${CURRENT_LINK}" "${current_sha}"
  restart_agent || true
else
  /bin/rm -f -- "${CURRENT_LINK}"
  "${LAUNCHCTL_BIN}" bootout "${USER_DOMAIN}/${AGENT_LABEL}" >/dev/null 2>&1 || true
fi
clear_pending
fail "candidate Agent restart or fresh snapshot confirmation failed; previous release restored"
