#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly RELEASE_CLASSIFIER="${SCRIPT_DIR}/classify-agent-release-paths.sh"
readonly VALIDATION_CLASSIFIER="${SCRIPT_DIR}/classify-ci-paths.sh"

assert_output() {
  local expected="$1"
  local output_file="$2"
  local key="$3"
  local actual
  actual="$(/usr/bin/sed -n "s/^${key}=//p" "${output_file}" | /usr/bin/tail -n 1)"
  if [[ "${actual}" != "${expected}" ]]; then
    printf 'Expected %s=%s, got %s\n' "${key}" "${expected}" "${actual}" >&2
    return 1
  fi
}

assert_detection_unknown_fallback() {
  local output_file
  output_file="$(/usr/bin/mktemp "${TMPDIR:-/tmp}/homeops-validation-fallback.XXXXXX")"
  trap '/bin/rm -f -- "${output_file}"' RETURN
  GITHUB_OUTPUT="${output_file}" "${VALIDATION_CLASSIFIER}"
  GITHUB_OUTPUT="${output_file}" "${RELEASE_CLASSIFIER}"
  for key in backend frontend agent infrastructure api_image web_image agent_artifact; do
    assert_output true "${output_file}" "${key}"
  done
  assert_output false "${output_file}" agent_release
  /bin/rm -f -- "${output_file}"
  trap - RETURN
}

run_release_case() {
  local expected="$1"
  local case_name="$2"
  local output_file
  shift 2
  output_file="$(/usr/bin/mktemp "${TMPDIR:-/tmp}/homeops-agent-release.XXXXXX")"
  trap '/bin/rm -f -- "${output_file}"' RETURN
  GITHUB_OUTPUT="${output_file}" "${RELEASE_CLASSIFIER}" "$@"
  if ! assert_output "${expected}" "${output_file}" agent_release; then
    printf 'Agent release classification case failed: %s\n' "${case_name}" >&2
    exit 1
  fi
  /bin/rm -f -- "${output_file}"
  trap - RETURN
}

assert_detection_unknown_fallback
run_release_case false docs-only docs/operations.md
run_release_case false readme-only README.md
run_release_case false backend-only backend/src/main/java/dev/homeops/HomeOpsApplication.java
run_release_case false frontend-only frontend/src/App.tsx
run_release_case true agent-source agent/internal/app/app.go
run_release_case true agent-dockerfile agent-artifact.Dockerfile
run_release_case true dockerignore .dockerignore
run_release_case true gitattributes .gitattributes
run_release_case false unrelated-infrastructure deploy/compose.example.yaml
run_release_case false rollout-mechanism deploy/scripts/rollout-homeops-agent.sh
run_release_case false launchagent-template deploy/launchd/dev.homeops.agent.plist.example
run_release_case false workflow-only .github/workflows/deploy.yml
run_release_case false classifier-mechanism scripts/classify-agent-release-paths.sh
run_release_case false unknown-path unknown/new-file.txt
run_release_case true mixed-docs-and-agent docs/operations.md agent/cmd/homeops-agent/main.go

printf 'HomeOps Agent release path classification tests passed\n'
