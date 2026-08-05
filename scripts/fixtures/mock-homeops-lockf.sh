#!/bin/bash

set -Eeuo pipefail

if [[ "$#" -ne 4 || "$1" != -s || "$2" != -t || "$3" != 0 || "$4" != 9 ]]; then
  printf 'Unexpected mock lockf arguments\n' >&2
  exit 1
fi

exit "${FAKE_LOCKF_EXIT_CODE:-0}"
