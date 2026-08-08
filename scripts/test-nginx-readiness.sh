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

compose exec --no-TTY db psql \
  --username homeops \
  --dbname homeops \
  --set ON_ERROR_STOP=1 \
  --command "INSERT INTO agent_event (id, agent_id, event_type, agent_version, occurred_at, summary) VALUES ('10000000-0000-0000-0000-000000000001', 'readiness-test-agent', 'CONNECTED', 'readiness-test', CURRENT_TIMESTAMP, 'Agent connected')" \
  >/dev/null
compose exec --no-TTY db psql \
  --username homeops \
  --dbname homeops \
  --set ON_ERROR_STOP=1 \
  --command "INSERT INTO agent_event (id, agent_id, event_type, agent_version, occurred_at, summary) VALUES ('10000000-0000-0000-0000-000000000003', 'readiness-test-agent', 'CONNECTED', 'readiness-test', '2010-01-01T00:00:00Z', 'Older agent connection')" \
  >/dev/null

compose exec --no-TTY db psql \
  --username homeops \
  --dbname homeops \
  --set ON_ERROR_STOP=1 \
  --command 'BEGIN' \
  --command "INSERT INTO agent_event (id, agent_id, event_type, agent_version, occurred_at, summary) VALUES ('10000000-0000-0000-0000-000000000002', 'readiness-test-agent', 'CONNECTED', 'readiness-test', '2000-01-01T00:00:00Z', 'Delayed agent connection')" \
  --command 'SELECT pg_sleep(5)' \
  --command 'COMMIT' \
  >/dev/null &
readonly delayed_writer_pid=$!

writer_is_active=false
for _ in {1..10}; do
  active_writer_count="$(compose exec --no-TTY db psql \
    --username homeops \
    --dbname homeops \
    --tuples-only \
    --no-align \
    --command "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND query LIKE 'SELECT pg_sleep(5)%' AND state = 'active'")"
  if [[ "${active_writer_count}" == 1 ]]; then
    writer_is_active=true
    break
  fi
  /bin/sleep 1
done
if [[ "${writer_is_active}" != true ]]; then
  printf 'Delayed Activity writer did not reach its uncommitted state\n' >&2
  exit 1
fi

activity_response="$(
  compose exec --no-TTY web wget \
    --quiet \
    --output-document=- \
    --header='Tailscale-User-Login: owner@example.invalid' \
    'http://127.0.0.1:8080/api/v1/activity?limit=1'
)"
printf '%s' "${activity_response}" | /usr/bin/grep -q '"type":"AGENT"'
activity_cursor="$(printf '%s' "${activity_response}" | python3 -c 'import json, sys; print(json.load(sys.stdin)["nextCursor"] or "")')"
if [[ -z "${activity_cursor}" ]]; then
  printf 'Activity snapshot did not produce a cursor\n' >&2
  exit 1
fi
wait "${delayed_writer_pid}"
next_activity_response="$(
  compose exec --no-TTY web wget \
    --quiet \
    --output-document=- \
    --header='Tailscale-User-Login: owner@example.invalid' \
    "http://127.0.0.1:8080/api/v1/activity?limit=25&cursor=${activity_cursor}"
)"
if printf '%s' "${next_activity_response}" | /usr/bin/grep -q 'Delayed agent connection'; then
  printf 'Activity cursor included an event committed after its snapshot\n' >&2
  exit 1
fi
printf '%s' "${next_activity_response}" | /usr/bin/grep -q 'Older agent connection'

/bin/sleep 6
if compose logs --no-color api | /usr/bin/grep -q 'Unexpected error occurred in scheduled task'; then
  printf 'Service-check scheduler raised an unexpected error\n' >&2
  exit 1
fi

printf 'Nginx Web-to-API readiness and Activity regression test passed\n'
