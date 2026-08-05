#!/bin/bash

set -Eeuo pipefail

if [[ "${FAKE_LAUNCHCTL_FAIL:-false}" == true ]]; then
  exit 1
fi
if [[ "${1:-}" == kickstart ]]; then
  printf 'VERSION=%s\nSENT_AT_UNIX=9999999999\n' "${FAKE_AGENT_PROOF_SHA}" >"${FAKE_AGENT_PROOF_FILE}"
  /bin/chmod 600 "${FAKE_AGENT_PROOF_FILE}"
fi
