#!/bin/bash

set -Eeuo pipefail

if [[ "${1:-}" == --config ]]; then
  shift 2
fi

command_name="${1:-}"
shift || true

if [[ "${command_name}" == compose ]] \
  && [[ -n "${DOCKER_CONFIG:-}" ]] \
  && [[ ! -x "${DOCKER_CONFIG}/cli-plugins/docker-compose" ]]
then
  printf 'unknown flag: --project-directory\n' >&2
  exit 125
fi

log_command() {
  if [[ -n "${FAKE_DOCKER_LOG:-}" ]]; then
    if [[ "${command_name}" == compose ]]; then
      printf 'compose api=%s web=%s %s\n' \
        "${HOMEOPS_API_IMAGE:-}" \
        "${HOMEOPS_WEB_IMAGE:-}" \
        "$*" \
        >>"${FAKE_DOCKER_LOG}"
    else
      printf '%s %s\n' "${command_name}" "$*" >>"${FAKE_DOCKER_LOG}"
    fi
  fi
}

log_command "$@"

case "${command_name}" in
  info)
    if [[ "$#" -ne 2 || "$1" != --format ]] \
      || [[ "$2" != *'.ClientInfo.Plugins'* ]]
    then
      printf 'Unexpected mock Docker info arguments\n' >&2
      exit 1
    fi
    printf '%s\n' "${FAKE_COMPOSE_PLUGIN:-}"
    ;;
  login)
    /bin/cat >/dev/null
    ;;
  logout|pull|rm)
    ;;
  create)
    printf 'mock-homeops-runtime-config\n'
    ;;
  cp)
    if [[ "$#" -ne 2 ]]; then
      printf 'Unexpected mock Docker cp arguments\n' >&2
      exit 1
    fi
    destination="$2"
    /bin/mkdir -p "${destination}/scripts"
    /bin/cp "${FAKE_RUNTIME_COMPOSE}" "${destination}/compose.yaml"
    /bin/cp "${FAKE_RUNTIME_WORKER}" "${destination}/scripts/deploy-homeops.sh"
    /bin/cp "${FAKE_RUNTIME_VALIDATOR}" "${destination}/scripts/validate-https-origin.sh"
    /bin/chmod 700 \
      "${destination}/scripts/deploy-homeops.sh" \
      "${destination}/scripts/validate-https-origin.sh"
    if [[ "${FAKE_RUNTIME_EXTRA_FILE:-false}" == true ]]; then
      printf 'unexpected\n' >"${destination}/unexpected"
    fi
    if [[ "${FAKE_RUNTIME_SYMLINK:-false}" == true ]]; then
      /bin/ln -s compose.yaml "${destination}/unexpected-link"
    fi
    if [[ "${FAKE_RUNTIME_INVALID_VALIDATOR:-false}" == true ]]; then
      printf '\nif\n' >>"${destination}/scripts/validate-https-origin.sh"
    fi
    ;;
  image)
    if [[ "$#" -ne 4 || "$1" != inspect || "$2" != --format ]]; then
      printf 'Unexpected mock Docker image arguments\n' >&2
      exit 1
    fi
    format="$3"
    image="$4"
    if [[ "${format}" == *org.opencontainers.image.revision* ]]; then
      case "${image}" in
        *homeops-runtime-config*)
          printf '%s\n' "${FAKE_CONFIG_REVISION}"
          ;;
        *"${FAKE_CANDIDATE_API_DIGEST}"*)
          printf '%s\n' "${FAKE_API_REVISION_OVERRIDE:-${FAKE_CANDIDATE_REVISION}}"
          ;;
        *"${FAKE_CANDIDATE_WEB_DIGEST}"*)
          printf '%s\n' "${FAKE_WEB_REVISION_OVERRIDE:-${FAKE_CANDIDATE_REVISION}}"
          ;;
        *"${FAKE_PREVIOUS_API_DIGEST:-not-a-digest}"*|*"${FAKE_PREVIOUS_WEB_DIGEST:-not-a-digest}"*)
          printf '%s\n' "${FAKE_PREVIOUS_REVISION}"
          ;;
        *)
          printf 'Unknown image revision request\n' >&2
          exit 1
          ;;
      esac
    elif [[ "${format}" == *dev.homeops.runtime-config.project* ]]; then
      printf '%s\n' "${FAKE_CONFIG_PROJECT:-homeops}"
    else
      printf 'Unexpected mock Docker image format\n' >&2
      exit 1
    fi
    ;;
  inspect)
    container_id="${!#}"
    case "${container_id}" in
      unhealthy-*) printf 'unhealthy\n' ;;
      healthy-*) printf 'healthy\n' ;;
      *)
        printf 'Unexpected mock container ID\n' >&2
        exit 1
        ;;
    esac
    ;;
  compose)
    arguments=" $* "
    if [[ "${arguments}" == *" version "* ]]; then
      printf 'Docker Compose version mock\n'
    elif [[ "${arguments}" == *" config --quiet "* ]]; then
      if [[ "${FAKE_CONFIG_FAIL:-false}" == true ]]; then
        exit 1
      fi
    elif [[ "${arguments}" == *" --profile operations run --rm migration "* ]]; then
      if [[ "${FAKE_MIGRATION_FAIL:-false}" == true ]]; then
        exit 1
      fi
    elif [[ "${arguments}" == *" ps --quiet api "* ]]; then
      if [[ "${FAKE_ALWAYS_UNHEALTHY:-false}" == true ]] \
        || { [[ -n "${FAKE_UNHEALTHY_API_DIGEST:-}" ]] \
          && [[ "${HOMEOPS_API_IMAGE:-}" == *"${FAKE_UNHEALTHY_API_DIGEST}"* ]]; }
      then
        printf 'unhealthy-api\n'
      else
        printf 'healthy-api\n'
      fi
    elif [[ "${arguments}" == *" ps --quiet web "* ]]; then
      printf 'healthy-web\n'
    elif [[ "${arguments}" == *" pull "* ]] \
      || [[ "${arguments}" == *" up "* ]] \
      || [[ "${arguments}" == *" stop api web "* ]]
    then
      :
    else
      printf 'Unexpected mock Docker Compose arguments: %s\n' "$*" >&2
      exit 1
    fi
    ;;
  *)
    printf 'Unexpected mock Docker command: %s\n' "${command_name}" >&2
    exit 1
    ;;
esac
