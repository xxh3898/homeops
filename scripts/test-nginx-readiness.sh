#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/compose.readiness-test.yaml"
readonly PROJECT_NAME=dev-homeops-readiness
TEST_ROOT=""
TLS_DIR=""
DB_PASSWORD=""
project_owned=false

compose() {
  HOMEOPS_READINESS_DB_PASSWORD="${DB_PASSWORD}" \
  HOMEOPS_READINESS_TLS_DIR="${TLS_DIR}" \
    docker compose \
      --project-name "${PROJECT_NAME}" \
      --file "${COMPOSE_FILE}" \
      "$@"
}

cleanup() {
  local status="$?"

  trap - EXIT INT TERM
  if [[ "${project_owned}" == true ]]; then
    if [[ "${status}" -ne 0 ]]; then
      compose ps >&2 || true
      compose logs --no-color --tail 80 db migration api web >&2 || true
    fi
    compose down --remove-orphans >/dev/null 2>&1 || true
  fi
  if [[ -n "${TEST_ROOT}" ]] \
    && [[ -d "${TEST_ROOT}" ]] \
    && [[ "$(/usr/bin/basename "${TEST_ROOT}")" == homeops-readiness.* ]]
  then
    /bin/rm -rf -- "${TEST_ROOT}"
  fi
  exit "${status}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ -n "$(docker ps --all --quiet --filter "label=com.docker.compose.project=${PROJECT_NAME}")" ]] \
  || docker network inspect "${PROJECT_NAME}_application" >/dev/null 2>&1
then
  printf 'Readiness test project already has Docker resources\n' >&2
  exit 1
fi
project_owned=true

TEST_ROOT="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/homeops-readiness.XXXXXX")"
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

compose config --quiet
compose build api
compose build web
compose up --detach --wait --wait-timeout 60 db
compose run --rm migration
compose up --detach --wait --wait-timeout 90 api web
compose exec --no-TTY web \
  wget -qO- http://127.0.0.1:8080/actuator/health/readiness \
  | /usr/bin/grep -q '"status":"UP"'

printf 'Nginx Web-to-API readiness regression test passed\n'
