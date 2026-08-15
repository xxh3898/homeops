#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly CLASSIFIER="${SCRIPT_DIR}/classify-ci-paths.sh"

assert_output() {
  local expected="$1"
  local output_file="$2"
  local key="$3"
  local actual
  actual="$(/usr/bin/sed -n "s/^${key}=//p" "${output_file}" | /usr/bin/tail -n 1)"
  if [[ "${actual}" != "${expected}" ]]; then
    printf 'Expected %s=%s, got %s\n' "${key}" "${expected}" "${actual}" >&2
    exit 1
  fi
}

run_case() {
  local output_file
  output_file="$(/usr/bin/mktemp "${TMPDIR:-/tmp}/homeops-classifier.XXXXXX")"
  trap '/bin/rm -f -- "${output_file}"' RETURN
  GITHUB_OUTPUT="${output_file}" "${CLASSIFIER}" "${@:2}"
  IFS=',' read -r expected_backend expected_frontend expected_agent expected_infrastructure expected_api expected_web expected_artifact <<<"$1"
  assert_output "${expected_backend}" "${output_file}" backend
  assert_output "${expected_frontend}" "${output_file}" frontend
  assert_output "${expected_agent}" "${output_file}" agent
  assert_output "${expected_infrastructure}" "${output_file}" infrastructure
  assert_output "${expected_api}" "${output_file}" api_image
  assert_output "${expected_web}" "${output_file}" web_image
  assert_output "${expected_artifact}" "${output_file}" agent_artifact
  /bin/rm -f -- "${output_file}"
  trap - RETURN
}

run_case 'true,false,false,false,true,false,false' backend/src/main/java/dev/homeops/HomeOpsApplication.java
run_case 'false,true,false,false,false,true,false' frontend/src/App.tsx
run_case 'false,false,true,false,false,false,true' agent/cmd/homeops-agent/main.go
run_case 'false,false,false,true,false,true,false' deploy/nginx/default.conf
run_case 'false,false,false,true,true,true,true' .dockerignore
run_case 'false,false,false,true,false,false,true' agent-artifact.Dockerfile
run_case 'true,true,true,true,false,false,false' compose.test.yaml
run_case 'false,false,false,true,false,false,false' runtime-config.Dockerfile
run_case 'false,false,false,true,false,false,false' docs/architecture.md
run_case 'false,true,true,false,false,true,true' frontend/src/App.tsx agent/cmd/homeops-agent/main.go
run_case 'true,true,true,true,true,true,true'
run_case 'true,true,true,true,true,true,true' .github/workflows/validate.yml
run_case 'true,true,true,true,true,true,true' scripts/classify-agent-release-paths.sh
run_case 'true,true,true,true,true,true,true' unknown/new-file.txt

printf 'HomeOps CI path classification tests passed\n'
