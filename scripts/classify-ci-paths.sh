#!/bin/bash

set -Eeuo pipefail

backend=false
frontend=false
agent=false
infrastructure=false
api_image=false
web_image=false
agent_artifact=false
force_all=false

if [[ "$#" -eq 0 ]]; then
  force_all=true
fi

for path in "$@"; do
  case "${path}" in
    .github/workflows/*|scripts/classify-ci-paths.sh|scripts/test-classify-ci-paths.sh|.gitattributes)
      force_all=true
      ;;
    backend/*)
      backend=true
      api_image=true
      ;;
    frontend/*)
      frontend=true
      web_image=true
      ;;
    agent/*)
      agent=true
      agent_artifact=true
      ;;
    deploy/nginx/*)
      infrastructure=true
      web_image=true
      ;;
    deploy/*|compose.*.yaml|runtime-config.Dockerfile|.env.*.example|README.md|SECURITY.md|CONTRIBUTING.md)
      infrastructure=true
      ;;
    docs/*|LICENSE|.editorconfig|.gitignore|.dockerignore)
      infrastructure=true
      ;;
    *)
      force_all=true
      ;;
  esac
done

if [[ "${force_all}" == true ]]; then
  backend=true
  frontend=true
  agent=true
  infrastructure=true
  api_image=true
  web_image=true
  agent_artifact=true
fi

output_file="${GITHUB_OUTPUT:-/dev/stdout}"
{
  printf 'backend=%s\n' "${backend}"
  printf 'frontend=%s\n' "${frontend}"
  printf 'agent=%s\n' "${agent}"
  printf 'infrastructure=%s\n' "${infrastructure}"
  printf 'api_image=%s\n' "${api_image}"
  printf 'web_image=%s\n' "${web_image}"
  printf 'agent_artifact=%s\n' "${agent_artifact}"
} >>"${output_file}"

