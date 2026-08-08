#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/compose.readiness-test.yaml"
readonly CURL_BIN=/usr/bin/curl
readonly INTERNAL_PROJECT=dev-homeops-ingress-internal
readonly EXTERNAL_PROJECT=dev-homeops-ingress-external
readonly INTERNAL_WEB_BIND=127.0.0.1:18081
readonly EXTERNAL_WEB_BIND=127.0.0.1:18082
TEST_ROOT=""
TLS_DIR=""
DB_PASSWORD=""
active_project=""
active_ingress_internal=""
active_web_bind=""

compose() {
  local project_name="$1"
  local ingress_internal="$2"
  local web_bind="$3"
  shift 3

  HOMEOPS_READINESS_DB_PASSWORD="${DB_PASSWORD}" \
  HOMEOPS_READINESS_TLS_DIR="${TLS_DIR}" \
  HOMEOPS_READINESS_INGRESS_INTERNAL="${ingress_internal}" \
  HOMEOPS_READINESS_WEB_BIND="${web_bind}" \
    docker compose \
      --project-name "${project_name}" \
      --file "${COMPOSE_FILE}" \
      "$@"
}

cleanup_project() {
  local project_name="$1"
  local ingress_internal="$2"
  local web_bind="$3"

  compose "${project_name}" "${ingress_internal}" "${web_bind}" down --remove-orphans >/dev/null 2>&1 || true
}

cleanup() {
  local status="$?"

  trap - EXIT INT TERM
  if [[ -n "${active_project}" ]]; then
    cleanup_project "${active_project}" "${active_ingress_internal}" "${active_web_bind}"
  fi
  if [[ -n "${TEST_ROOT}" ]] \
    && [[ -d "${TEST_ROOT}" ]] \
    && [[ "$(/usr/bin/basename "${TEST_ROOT}")" == homeops-host-ingress.* ]]
  then
    /bin/rm -rf -- "${TEST_ROOT}"
  fi
  exit "${status}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

assert_project_is_absent() {
  local project_name="$1"

  if [[ -n "$(docker ps --all --quiet --filter "label=com.docker.compose.project=${project_name}")" ]] \
    || docker network inspect "${project_name}_application" >/dev/null 2>&1
  then
    printf 'Host-ingress regression project already has Docker resources: %s\n' "${project_name}" >&2
    exit 1
  fi
}

assert_loopback_port_is_available() {
  local web_bind="$1"
  local port="${web_bind##*:}"

  if /usr/sbin/lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    printf 'Host-ingress regression port is already listening: %s\n' "${web_bind}" >&2
    exit 1
  fi
}

read_published_loopback_port() {
  local web_bind="$1"

  if [[ ! "${web_bind}" =~ ^127\.0\.0\.1:[1-9][0-9]*$ ]]; then
    printf 'Web readiness port must be a loopback address: %s\n' "${web_bind}" >&2
    return 1
  fi
  printf '%s\n' "${web_bind}"
}

host_facing_readiness() {
  local published_address="$1"

  # Tailscale Serve terminates browser TLS before forwarding to this loopback HTTP port.
  "${CURL_BIN}" \
    --fail \
    --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time 10 \
    --output /dev/null \
    "http://${published_address}/actuator/health/readiness"
}

verify_case() {
  local project_name="$1"
  local ingress_internal="$2"
  local web_bind="$3"
  local expected_host_result="$4"
  local published_address

  active_project="${project_name}"
  active_ingress_internal="${ingress_internal}"
  active_web_bind="${web_bind}"
  compose "${project_name}" "${ingress_internal}" "${web_bind}" config --quiet
  compose "${project_name}" "${ingress_internal}" "${web_bind}" up --detach --wait --wait-timeout 60 db
  compose "${project_name}" "${ingress_internal}" "${web_bind}" run --rm migration
  compose "${project_name}" "${ingress_internal}" "${web_bind}" up --detach --wait --wait-timeout 90 api web
  compose "${project_name}" "${ingress_internal}" "${web_bind}" exec --no-TTY web \
    wget -qO- http://127.0.0.1:8080/actuator/health/readiness \
    | /usr/bin/grep -q '"status":"UP"'

  published_address="$(read_published_loopback_port "${web_bind}")"
  if [[ "${expected_host_result}" == failure ]]; then
    if host_facing_readiness "${published_address}" >/dev/null 2>&1; then
      printf 'internal ingress unexpectedly accepted host-facing readiness\n' >&2
      return 1
    fi
  elif ! host_facing_readiness "${published_address}"; then
    printf 'non-internal ingress did not accept host-facing readiness\n' >&2
    return 1
  fi

  cleanup_project "${project_name}" "${ingress_internal}" "${web_bind}"
  active_project=""
  active_ingress_internal=""
  active_web_bind=""
}

assert_project_is_absent "${INTERNAL_PROJECT}"
assert_project_is_absent "${EXTERNAL_PROJECT}"
assert_loopback_port_is_available "${INTERNAL_WEB_BIND}"
assert_loopback_port_is_available "${EXTERNAL_WEB_BIND}"

TEST_ROOT="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/homeops-host-ingress.XXXXXX")"
TLS_DIR="${TEST_ROOT}/tls"
DB_PASSWORD="$(/usr/bin/openssl rand -hex 24)"
readonly TEST_ROOT TLS_DIR DB_PASSWORD

/bin/mkdir -p "${TLS_DIR}"
/usr/bin/openssl req \
  -x509 \
  -newkey rsa:2048 \
  -nodes \
  -keyout "${TLS_DIR}/server.key" \
  -out "${TLS_DIR}/server.crt" \
  -subj /CN=localhost \
  -days 1 \
  >/dev/null 2>&1
/bin/cp "${TLS_DIR}/server.crt" "${TLS_DIR}/ca.crt"
/bin/chmod 600 "${TLS_DIR}/server.key" "${TLS_DIR}/server.crt" "${TLS_DIR}/ca.crt"

compose "${EXTERNAL_PROJECT}" false "${EXTERNAL_WEB_BIND}" build api web
verify_case "${INTERNAL_PROJECT}" true "${INTERNAL_WEB_BIND}" failure
verify_case "${EXTERNAL_PROJECT}" false "${EXTERNAL_WEB_BIND}" success

printf 'Host-facing ingress regression passed\n'
