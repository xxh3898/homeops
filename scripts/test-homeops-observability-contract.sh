#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly AVAILABILITY_PREFIX='uptime'
readonly AVAILABILITY_SUFFIX='kuma'
readonly DASHBOARD_PREFIX='net'
readonly DASHBOARD_SUFFIX='data'
readonly NETWORK_WORD_PREFIX='inter'
readonly FORBIDDEN_PATTERN="(^|[^[:alnum:]])(${AVAILABILITY_PREFIX}[[:space:]_.-]*${AVAILABILITY_SUFFIX}|${DASHBOARD_PREFIX}[[:space:]_.-]*${DASHBOARD_SUFFIX})"

assert_forbidden_variant() {
  local candidate="$1"

  if ! /usr/bin/printf '%s\n' "${candidate}" | /usr/bin/grep -Eiq -- "${FORBIDDEN_PATTERN}"; then
    printf 'Forbidden observability variant was not detected: %s\n' "${candidate}" >&2
    exit 1
  fi
}

assert_allowed_variant() {
  local candidate="$1"

  if /usr/bin/printf '%s\n' "${candidate}" | /usr/bin/grep -Eiq -- "${FORBIDDEN_PATTERN}"; then
    printf 'Allowed observability term was rejected: %s\n' "${candidate}" >&2
    exit 1
  fi
}

assert_forbidden_variant "${AVAILABILITY_PREFIX}${AVAILABILITY_SUFFIX}"
assert_forbidden_variant "${AVAILABILITY_PREFIX} ${AVAILABILITY_SUFFIX}"
assert_forbidden_variant "${AVAILABILITY_PREFIX}-${AVAILABILITY_SUFFIX}"
assert_forbidden_variant "${AVAILABILITY_PREFIX}_${AVAILABILITY_SUFFIX}"
assert_forbidden_variant 'UpTiMe'-'KuMa'
assert_forbidden_variant "${DASHBOARD_PREFIX}${DASHBOARD_SUFFIX}"
assert_forbidden_variant "${DASHBOARD_PREFIX} ${DASHBOARD_SUFFIX}"
assert_forbidden_variant "${DASHBOARD_PREFIX}-${DASHBOARD_SUFFIX}"
assert_forbidden_variant "${DASHBOARD_PREFIX}_${DASHBOARD_SUFFIX}"
assert_forbidden_variant 'NeT'_'DaTa'
assert_allowed_variant 'netstat'
assert_allowed_variant "${NETWORK_WORD_PREFIX}${DASHBOARD_PREFIX}-${DASHBOARD_SUFFIX}"

path_violation=0
while IFS= read -r -d '' candidate; do
  if /usr/bin/printf '%s\n' "${candidate}" | /usr/bin/grep -Eiq -- "${FORBIDDEN_PATTERN}"; then
    printf 'Forbidden observability reference remains in tracked path: %s\n' "${candidate}" >&2
    path_violation=1
  fi
done < <(git -C "${REPOSITORY_ROOT}" ls-files -z -- .)

content_status=0
set +e
content_matches="$(git -C "${REPOSITORY_ROOT}" grep -InI -E -i \
  -e "${FORBIDDEN_PATTERN}" -- . 2>&1)"
content_status=$?
set -e

case "${content_status}" in
  0)
    printf 'Forbidden observability references remain in tracked content:\n%s\n' \
      "${content_matches}" >&2
    path_violation=1
    ;;
  1)
    ;;
  *)
    printf 'Tracked observability content scan failed:\n%s\n' "${content_matches}" >&2
    exit 1
    ;;
esac

if [[ "${path_violation}" -ne 0 ]]; then
  exit 1
fi

printf 'HomeOps observability contract checks passed\n'
