#!/bin/bash

set -Eeuo pipefail

if [[ "$#" -ne 1 || "$1" != 5 ]]; then
  printf 'Unexpected mock sleep arguments\n' >&2
  exit 1
fi
