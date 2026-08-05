#!/bin/bash

set -Eeuo pipefail

if [[ "$#" -ne 3 || "$1" != -f ]]; then
  printf 'Unexpected mock stat arguments\n' >&2
  exit 1
fi

case "$2" in
  %Su)
    /usr/bin/id -un
    ;;
  %OLp)
    if [[ "$(/usr/bin/uname -s)" == Darwin ]]; then
      /usr/bin/stat -f '%OLp' "$3"
    else
      /usr/bin/stat -c '%a' "$3"
    fi
    ;;
  *)
    printf 'Unexpected mock stat format\n' >&2
    exit 1
    ;;
esac
