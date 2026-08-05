#!/bin/bash

set -Eeuo pipefail

url="${!#}"
if [[ -n "${FAKE_CURL_LOG:-}" ]]; then
  printf '%s\n' "${url}" >>"${FAKE_CURL_LOG}"
fi

if [[ -n "${FAKE_CURL_FAIL_ONCE_FILE:-}" ]] \
  && [[ ! -e "${FAKE_CURL_FAIL_ONCE_FILE}" ]]
then
  : >"${FAKE_CURL_FAIL_ONCE_FILE}"
  exit 22
fi

case "${url}" in
  https://*/)
    printf '<!doctype html><script type="module" src="/assets/app.js"></script>\n'
    ;;
  https://*/assets/app.js|https://*/actuator/health/readiness)
    ;;
  *)
    printf 'Unexpected mock curl URL\n' >&2
    exit 1
    ;;
esac
