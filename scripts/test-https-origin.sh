#!/bin/bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && /bin/pwd -P)"
readonly VALIDATOR="${SCRIPT_DIR}/../deploy/scripts/validate-https-origin.sh"

assert_valid() {
  if ! "${VALIDATOR}" "$1" >/dev/null 2>&1; then
    printf 'Expected valid HTTPS origin: %s\n' "$1" >&2
    exit 1
  fi
}

assert_invalid() {
  local exit_code

  set +e
  "${VALIDATOR}" "$1" >/dev/null 2>&1
  exit_code="$?"
  set -e
  if [[ "${exit_code}" -ne 64 ]]; then
    printf 'Expected invalid HTTPS origin to exit 64: %s\n' "$1" >&2
    exit 1
  fi
}

assert_valid 'https://homeops.example.invalid'
assert_valid 'https://homeops.example.invalid:9443'
assert_valid 'https://A-b.c1:443'
assert_valid 'https://127.0.0.1:65535'
assert_valid "https://$(printf 'a%.0s' {1..63}).example.invalid"

assert_invalid 'http://homeops.example.invalid:9443'
assert_invalid 'https://homeops.example.invalid/'
assert_invalid 'https://homeops.example.invalid/path'
assert_invalid 'https://homeops.example.invalid?ready=true'
assert_invalid 'https://homeops.example.invalid#ready'
assert_invalid 'https://user@homeops.example.invalid'
assert_invalid 'https://homeops.example.invalid:0'
assert_invalid 'https://homeops.example.invalid:09443'
assert_invalid 'https://homeops.example.invalid:65536'
assert_invalid 'https://homeops.example.invalid:123456'
assert_invalid 'https://homeops.example.invalid:'
assert_invalid 'https://-homeops.example.invalid'
assert_invalid 'https://homeops-.example.invalid'
assert_invalid 'https://homeops..example.invalid'
assert_invalid 'https://home_ops.example.invalid'
assert_invalid 'https://homeops.example.invalid.'
assert_invalid 'https://[::1]:9443'
assert_invalid $'https://homeops.example.invalid:9443\nhttps://other.example.invalid'
assert_invalid "https://$(printf 'a%.0s' {1..64}).example.invalid"
assert_invalid "https://$(printf 'a%.0s' {1..63}).$(printf 'b%.0s' {1..63}).$(printf 'c%.0s' {1..63}).$(printf 'd%.0s' {1..62})"

set +e
"${VALIDATOR}" >/dev/null 2>&1
missing_argument_exit_code="$?"
"${VALIDATOR}" \
  'https://homeops.example.invalid' \
  'https://other.example.invalid' \
  >/dev/null 2>&1
extra_argument_exit_code="$?"
set -e
if [[ "${missing_argument_exit_code}" -ne 64 ]] \
  || [[ "${extra_argument_exit_code}" -ne 64 ]]
then
  printf 'HTTPS origin validator must require exactly one argument\n' >&2
  exit 1
fi

printf 'HTTPS origin validation tests passed\n'
