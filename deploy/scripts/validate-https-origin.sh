#!/bin/bash

set -Eeuo pipefail

fail() {
  printf 'HTTPS origin must contain a valid DNS host and optional port without a path\n' >&2
  exit 64
}

if [[ "$#" -ne 1 ]]; then
  fail
fi

readonly ORIGIN="$1"
if [[ ! "${ORIGIN}" =~ ^https://([A-Za-z0-9.-]+)(:([0-9]+))?$ ]]; then
  fail
fi

readonly HOST_NAME="${BASH_REMATCH[1]}"
readonly PORT="${BASH_REMATCH[3]:-}"
if [[ "${#HOST_NAME}" -gt 253 ]] \
  || [[ ! "${HOST_NAME}" =~ ^[A-Za-z0-9] ]] \
  || [[ ! "${HOST_NAME}" =~ [A-Za-z0-9]$ ]]
then
  fail
fi

IFS='.' read -r -a host_labels <<<"${HOST_NAME}"
for label in "${host_labels[@]}"; do
  if [[ ! "${label}" =~ ^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$ ]]; then
    fail
  fi
done

if [[ -n "${PORT}" ]]; then
  if [[ ! "${PORT}" =~ ^[1-9][0-9]{0,4}$ ]] \
    || (( 10#${PORT} > 65535 ))
  then
    fail
  fi
fi
