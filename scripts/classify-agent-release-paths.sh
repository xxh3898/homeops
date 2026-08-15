#!/bin/bash

set -Eeuo pipefail

agent_release=false

for path in "$@"; do
  case "${path}" in
    agent/*|agent-artifact.Dockerfile|.dockerignore|.gitattributes)
      agent_release=true
      ;;
  esac
done

output_file="${GITHUB_OUTPUT:-/dev/stdout}"
printf 'agent_release=%s\n' "${agent_release}" >>"${output_file}"
