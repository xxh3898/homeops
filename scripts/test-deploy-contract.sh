#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && /bin/pwd -P)"
readonly VALIDATE_WORKFLOW="${REPOSITORY_ROOT}/.github/workflows/validate.yml"
readonly DEPLOY_WORKFLOW="${REPOSITORY_ROOT}/.github/workflows/deploy.yml"
readonly AGENT_RELEASE_CLASSIFIER="${REPOSITORY_ROOT}/scripts/classify-agent-release-paths.sh"
readonly OBSERVABILITY_CONTRACT="${REPOSITORY_ROOT}/scripts/test-homeops-observability-contract.sh"
readonly BOOTSTRAP="${REPOSITORY_ROOT}/deploy/bootstrap/deploy-homeops-ci.sh.example"
readonly WORKER="${REPOSITORY_ROOT}/deploy/scripts/deploy-homeops.sh"
readonly ORIGIN_VALIDATOR="${REPOSITORY_ROOT}/deploy/scripts/validate-https-origin.sh"
readonly EVENT_REPORTER="${REPOSITORY_ROOT}/deploy/scripts/report-homeops-event.py"
readonly RUNTIME_CONFIG_DOCKERFILE="${REPOSITORY_ROOT}/runtime-config.Dockerfile"
readonly COMPOSE_EXAMPLE="${REPOSITORY_ROOT}/deploy/compose.example.yaml"
readonly ENV_EXAMPLE="${REPOSITORY_ROOT}/deploy/env.example"
readonly AGENT_DOCKERFILE="${REPOSITORY_ROOT}/agent-artifact.Dockerfile"
readonly AGENT_BOOTSTRAP="${REPOSITORY_ROOT}/deploy/bootstrap/deploy-homeops-agent-rollout-ci.sh.example"
readonly AGENT_WORKER="${REPOSITORY_ROOT}/deploy/scripts/rollout-homeops-agent.sh"
readonly NGINX_CONFIG="${REPOSITORY_ROOT}/deploy/nginx/default.conf"
readonly DOCKER_BIN="${DOCKER_BIN:-docker}"
readonly PYTHON_BIN="${PYTHON_BIN:-python3}"

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

assert_job_contains() {
  local file="$1"
  local job="$2"
  local next_job="$3"
  local expected="$4"
  local job_definition

  if [[ -n "${next_job}" ]]; then
    job_definition="$(/usr/bin/sed -n "/^  ${job}:$/,/^  ${next_job}:$/p" "${file}")"
  else
    job_definition="$(/usr/bin/sed -n "/^  ${job}:$/,\$p" "${file}")"
  fi
  if [[ -z "${job_definition}" ]] || ! /usr/bin/grep -Fq -- "${expected}" <<<"${job_definition}"; then
    printf 'Missing %s job contract in %s: %s\n' \
      "${job}" "${file#"${REPOSITORY_ROOT}/"}" "${expected}" >&2
    exit 1
  fi
}

assert_nginx_location_contains() {
  local file="$1"
  local location_path="$2"
  local expected="$3"
  local location_definition

  location_definition="$(/usr/bin/awk -v start="    location = ${location_path} {" '
    $0 == start { capture = 1 }
    capture { print }
    capture && $0 == "    }" { exit }
  ' "${file}")"
  if [[ -z "${location_definition}" ]] \
    || ! /usr/bin/grep -Fq -- "${expected}" <<<"${location_definition}"
  then
    printf 'Missing Nginx location contract in %s for %s: %s\n' \
      "${file#"${REPOSITORY_ROOT}/"}" "${location_path}" "${expected}" >&2
    exit 1
  fi
}

assert_rendered_production_topology() {
  if ! "${DOCKER_BIN}" compose --profile operations --env-file "${ENV_EXAMPLE}" --file "${COMPOSE_EXAMPLE}" config --format json |
    "${PYTHON_BIN}" -c '
import json
import sys

compose = json.load(sys.stdin)
services = compose.get("services", {})
networks = compose.get("networks", {})
required_services = ("api", "web", "db", "migration")

missing_services = [service for service in required_services if service not in services]
if missing_services:
    raise SystemExit("missing rendered services: " + ", ".join(missing_services))

def attached_networks(service):
    attachments = services[service].get("networks", {})
    if not isinstance(attachments, dict) or not attachments:
        raise SystemExit(f"{service} must have rendered network attachments")
    missing_networks = [network for network in attachments if network not in networks]
    if missing_networks:
        raise SystemExit(f"{service} references undefined networks: " + ", ".join(missing_networks))
    return set(attachments)

def is_internal(network):
    return bool(networks[network].get("internal", False))

attachments = {service: attached_networks(service) for service in required_services}
if "application" not in networks or not is_internal("application"):
    raise SystemExit("application network must render with internal=true")
if "egress" not in networks or is_internal("egress"):
    raise SystemExit("egress network must render with internal=false")
if "ingress" not in networks or is_internal("ingress"):
    raise SystemExit("ingress network must render with internal=false")

expected_attachments = {
    "db": {"application"},
    "migration": {"application"},
    "api": {"application", "egress"},
    "web": {"application", "ingress"},
}
for service, expected in expected_attachments.items():
    if attachments[service] != expected:
        raise SystemExit(
            f"{service} networks must be {sorted(expected)}, got {sorted(attachments[service])}"
        )

for network, expected_services in (("egress", {"api"}), ("ingress", {"web"})):
    attached_services = {
        service for service, service_networks in attachments.items() if network in service_networks
    }
    if attached_services != expected_services:
        raise SystemExit(
            f"{network} consumers must be {sorted(expected_services)}, got {sorted(attached_services)}"
        )

for left, right, purpose in (
    ("web", "api", "web to api"),
    ("api", "db", "api to db"),
):
    shared_internal = attachments[left] & attachments[right]
    shared_internal = {network for network in shared_internal if is_internal(network)}
    if not shared_internal:
        raise SystemExit(f"{purpose} requires a shared internal network")
'; then
    printf 'Rendered production network topology contract failed\n' >&2
    exit 1
  fi
}

/bin/bash "${OBSERVABILITY_CONTRACT}"

assert_contains "${VALIDATE_WORKFLOW}" 'value: ${{ jobs.changes.outputs.agent_release }}'
assert_contains "${VALIDATE_WORKFLOW}" 'HEAD_SHA: ${{ github.sha }}'
assert_contains "${VALIDATE_WORKFLOW}" 'git diff --no-renames --name-only -z "${base_sha}" "${HEAD_SHA}"'
assert_contains "${VALIDATE_WORKFLOW}" 'if [[ "${REF_NAME}" == "refs/heads/main" ]]; then'
assert_contains "${VALIDATE_WORKFLOW}" './scripts/classify-agent-release-paths.sh "${changed_paths[@]}"'
assert_contains "${VALIDATE_WORKFLOW}" 'Changed paths are unavailable; running full validation and disabling Agent release.'
assert_contains "${VALIDATE_WORKFLOW}" 'Changed path detection failed; running full validation and disabling Agent release.'
assert_absent "${VALIDATE_WORKFLOW}" './scripts/classify-ci-paths.sh .github/workflows/validate.yml'
assert_contains "${AGENT_RELEASE_CLASSIFIER}" 'agent/*|agent-artifact.Dockerfile|.dockerignore|.gitattributes)'
assert_absent "${AGENT_RELEASE_CLASSIFIER}" 'force_all'

assert_contains "${DEPLOY_WORKFLOW}" 'api_digest: ${{ steps.publish-api.outputs.digest }}'
assert_contains "${DEPLOY_WORKFLOW}" 'web_digest: ${{ steps.publish-web.outputs.digest }}'
assert_contains "${DEPLOY_WORKFLOW}" 'deploy-homeops-v2 ${GITHUB_SHA} ${API_IMAGE_DIGEST} ${WEB_IMAGE_DIGEST} ${RUNTIME_CONFIG_DIGEST} ${REGISTRY_OWNER} ${registry_user}'
assert_contains "${DEPLOY_WORKFLOW}" './deploy/scripts/validate-https-origin.sh "${HOMEOPS_SMOKE_URL}"'
assert_contains "${DEPLOY_WORKFLOW}" 'persist-credentials: false'
assert_contains "${DEPLOY_WORKFLOW}" 'needs.validate.outputs.agent_release == '\''true'\'''
assert_contains "${DEPLOY_WORKFLOW}" 'vars.MAC_MINI_DEPLOY_ENABLED == '\''true'\'''
assert_contains "${DEPLOY_WORKFLOW}" 'vars.HOMEOPS_AGENT_ROLLOUT_ENABLED == '\''true'\'''
assert_absent "${DEPLOY_WORKFLOW}" 'vars.HOMEOPS_DEPLOY_HOST'
assert_absent "${DEPLOY_WORKFLOW}" 'vars.HOMEOPS_DEPLOY_USER'
assert_job_contains "${DEPLOY_WORKFLOW}" deploy rollout-agent 'ping: ${{ secrets.HOMEOPS_DEPLOY_HOST }}'
assert_job_contains "${DEPLOY_WORKFLOW}" deploy rollout-agent 'DEPLOY_HOST: ${{ secrets.HOMEOPS_DEPLOY_HOST }}'
assert_job_contains "${DEPLOY_WORKFLOW}" deploy rollout-agent 'DEPLOY_USER: ${{ secrets.HOMEOPS_DEPLOY_USER }}'
assert_job_contains "${DEPLOY_WORKFLOW}" deploy rollout-agent 'Deployment target configuration is missing or invalid'
assert_job_contains "${DEPLOY_WORKFLOW}" rollout-agent '' 'ping: ${{ secrets.HOMEOPS_DEPLOY_HOST }}'
assert_job_contains "${DEPLOY_WORKFLOW}" rollout-agent '' 'DEPLOY_HOST: ${{ secrets.HOMEOPS_DEPLOY_HOST }}'
assert_job_contains "${DEPLOY_WORKFLOW}" rollout-agent '' 'DEPLOY_USER: ${{ secrets.HOMEOPS_DEPLOY_USER }}'
assert_job_contains "${DEPLOY_WORKFLOW}" rollout-agent '' 'Deployment target configuration is missing or invalid'
assert_contains "${DEPLOY_WORKFLOW}" 'rollout-homeops-agent-v1 ${GITHUB_SHA} ${AGENT_DIGEST} ${REGISTRY_OWNER} ${registry_user}'
assert_contains "${DEPLOY_WORKFLOW}" 'HOMEOPS_AGENT_ROLLOUT_SSH_KEY'
assert_absent "${DEPLOY_WORKFLOW}" 'needs.validate.outputs.agent_artifact == '\''true'\'''
assert_absent "${DEPLOY_WORKFLOW}" 'deploy-homeops-v1'
assert_absent "${DEPLOY_WORKFLOW}" '^https://[^/[:space:]]+$'

assert_contains "${BOOTSTRAP}" '^deploy-homeops-v2[[:space:]]'
assert_contains "${BOOTSTRAP}" 'HOMEOPS_API_IMAGE_DIGEST="${API_DIGEST}"'
assert_contains "${BOOTSTRAP}" 'HOMEOPS_WEB_IMAGE_DIGEST="${WEB_DIGEST}"'
assert_contains "${BOOTSTRAP}" 'HOMEOPS_RUNTIME_CONFIG_DIGEST="${RUNTIME_DIGEST}"'
assert_contains "${BOOTSTRAP}" 'deployment digests must be non-zero'
assert_contains "${BOOTSTRAP}" 'validate-https-origin.sh'
assert_contains "${BOOTSTRAP}" 'if [[ "${entry_count}" != 5 ]]'
assert_contains "${BOOTSTRAP}" 'report-homeops-event.py'
assert_contains "${BOOTSTRAP}" 'fail "runtime config script syntax is invalid"'
assert_contains "${BOOTSTRAP}" '{{range .ClientInfo.Plugins}}{{if eq .Name "compose"}}{{.Path}}{{end}}{{end}}'
assert_contains "${BOOTSTRAP}" '"${DOCKER_BIN}" compose version >/dev/null 2>&1'
assert_contains "${BOOTSTRAP}" 'install_compose_plugin "${docker_config_dir}"'

assert_contains "${WORKER}" 'API_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-api@${CANDIDATE_API_DIGEST}"'
assert_contains "${WORKER}" 'WEB_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-web@${CANDIDATE_WEB_DIGEST}"'
assert_contains "${WORKER}" 'API_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-api@${current_api_digest}"'
assert_contains "${WORKER}" 'WEB_IMAGE="ghcr.io/${REGISTRY_OWNER}/homeops-web@${current_web_digest}"'
assert_contains "${WORKER}" 'validate_image_revision "${COMMIT_SHA}" "${API_IMAGE}"'
assert_contains "${WORKER}" 'validate_image_revision "${COMMIT_SHA}" "${WEB_IMAGE}"'
assert_contains "${WORKER}" 'validate_image_revision "${current_sha}" "${API_IMAGE}"'
assert_contains "${WORKER}" 'validate_image_revision "${current_sha}" "${WEB_IMAGE}"'
assert_contains "${WORKER}" 'readonly ORIGIN_VALIDATOR="${SCRIPT_DIR}/validate-https-origin.sh"'
assert_contains "${WORKER}" 'if ! "${ORIGIN_VALIDATOR}" "${origin}"; then'
assert_contains "${WORKER}" "\$'API_IMAGE_DIGEST\\nCURRENT_SHA\\nPREVIOUS_API_IMAGE_DIGEST\\nPREVIOUS_RUNTIME_CONFIG_DIGEST\\nPREVIOUS_SHA\\nPREVIOUS_WEB_IMAGE_DIGEST\\nRUNTIME_CONFIG_DIGEST\\nWEB_IMAGE_DIGEST'"
assert_contains "${WORKER}" "printf 'API_IMAGE_DIGEST=%s\\n' \"\${CANDIDATE_API_DIGEST}\""
assert_contains "${WORKER}" "printf 'PREVIOUS_API_IMAGE_DIGEST=%s\\n' \"\${current_api_digest}\""
assert_contains "${WORKER}" "printf 'WEB_IMAGE_DIGEST=%s\\n' \"\${CANDIDATE_WEB_DIGEST}\""
assert_contains "${WORKER}" "printf 'PREVIOUS_WEB_IMAGE_DIGEST=%s\\n' \"\${current_web_digest}\""
assert_absent "${WORKER}" 'homeops-api:${COMMIT_SHA}'
assert_absent "${WORKER}" 'homeops-web:${COMMIT_SHA}'
assert_absent "${WORKER}" 'homeops-api:${current_sha}'
assert_absent "${WORKER}" 'homeops-web:${current_sha}'
assert_absent "${WORKER}" '^https://[A-Za-z0-9][A-Za-z0-9.-]{0,252}$'

assert_contains "${ORIGIN_VALIDATOR}" '^https://([A-Za-z0-9.-]+)(:([0-9]+))?$'
assert_contains "${ORIGIN_VALIDATOR}" '10#${PORT} > 65535'
assert_contains "${RUNTIME_CONFIG_DOCKERFILE}" 'COPY deploy/scripts/deploy-homeops.sh ./scripts/deploy-homeops.sh'
assert_contains "${RUNTIME_CONFIG_DOCKERFILE}" 'COPY deploy/scripts/validate-https-origin.sh ./scripts/validate-https-origin.sh'
assert_contains "${RUNTIME_CONFIG_DOCKERFILE}" 'COPY deploy/scripts/report-homeops-event.py ./scripts/report-homeops-event.py'
assert_absent "${RUNTIME_CONFIG_DOCKERFILE}" 'COPY deploy/scripts ./scripts'
assert_contains "${RUNTIME_CONFIG_DOCKERFILE}" './scripts/validate-https-origin.sh'
assert_contains "${EVENT_REPORTER}" 'X-HomeOps-Ingestion-Signature'
assert_contains "${EVENT_REPORTER}" 'NoRedirect()'

assert_contains "${COMPOSE_EXAMPLE}" 'HOMEOPS_INGESTION_MAXIMUM_REQUEST_AGE: ${HOMEOPS_INGESTION_MAXIMUM_REQUEST_AGE:-5m}'
assert_contains "${COMPOSE_EXAMPLE}" 'HOMEOPS_INGESTION_ALLOWED_FUTURE_SKEW: ${HOMEOPS_INGESTION_ALLOWED_FUTURE_SKEW:-1m}'
assert_contains "${COMPOSE_EXAMPLE}" 'HOMEOPS_MONITORING_MAX_CONCURRENT_CHECKS: ${HOMEOPS_MONITORING_MAX_CONCURRENT_CHECKS:-4}'
assert_rendered_production_topology
assert_contains "${ENV_EXAMPLE}" 'HOMEOPS_API_IMAGE=ghcr.io/example/homeops-api@sha256:'
assert_contains "${ENV_EXAMPLE}" 'HOMEOPS_WEB_IMAGE=ghcr.io/example/homeops-web@sha256:'
assert_contains "${ENV_EXAMPLE}" 'HOMEOPS_INGESTION_MAXIMUM_REQUEST_AGE=5m'
assert_contains "${ENV_EXAMPLE}" 'HOMEOPS_INGESTION_ALLOWED_FUTURE_SKEW=1m'
assert_contains "${ENV_EXAMPLE}" 'HOMEOPS_MONITORING_MAX_CONCURRENT_CHECKS=4'

assert_contains "${AGENT_DOCKERFILE}" 'GOOS=darwin GOARCH=arm64'
assert_contains "${AGENT_DOCKERFILE}" 'sha256sum homeops-agent >homeops-agent.sha256'
assert_contains "${AGENT_BOOTSTRAP}" '^rollout-homeops-agent-v1[[:space:]]'
assert_contains "${AGENT_BOOTSTRAP}" 'AGENT_REPOSITORY=ghcr.io/REPLACE_ME/homeops-agent'
assert_contains "${AGENT_BOOTSTRAP}" 'Agent artifact revision is invalid'
assert_contains "${AGENT_BOOTSTRAP}" 'create "${agent_image}" /homeops-agent'
assert_contains "${AGENT_WORKER}" 'readonly AGENT_LABEL=dev.homeops.agent'
assert_contains "${AGENT_WORKER}" 'candidate Agent restart or fresh snapshot confirmation failed; previous release restored'
assert_absent "${AGENT_WORKER}" 'SSH_ORIGINAL_COMMAND'

assert_contains "${NGINX_CONFIG}" 'location = /api/v1/internal/agent/log-requests/next {'
assert_contains "${NGINX_CONFIG}" 'location = /api/v1/internal/agent/log-results {'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/log-requests/next" \
  'limit_except GET { deny all; }'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/log-requests/next" \
  'proxy_set_header X-HomeOps-Agent-Verified $ssl_client_verify;'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/log-results" \
  'limit_except POST { deny all; }'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/log-results" \
  'proxy_set_header X-HomeOps-Agent-Verified $ssl_client_verify;'
assert_contains "${NGINX_CONFIG}" 'location = /api/v1/internal/agent/control-requests/next {'
assert_contains "${NGINX_CONFIG}" 'location = /api/v1/internal/agent/control-results {'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/control-requests/next" \
  'limit_except GET { deny all; }'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/control-requests/next" \
  'proxy_read_timeout 5s;'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/control-requests/next" \
  'proxy_set_header X-HomeOps-Agent-Verified $ssl_client_verify;'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/control-results" \
  'limit_except POST { deny all; }'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/control-results" \
  'client_max_body_size 4k;'
assert_nginx_location_contains \
  "${NGINX_CONFIG}" "/api/v1/internal/agent/control-results" \
  'proxy_set_header X-HomeOps-Agent-Verified $ssl_client_verify;'
assert_absent "${NGINX_CONFIG}" 'location /api/v1/internal/agent/'

printf 'Deployment contract checks passed\n'
