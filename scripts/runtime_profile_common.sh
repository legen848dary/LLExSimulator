#!/usr/bin/env bash

if [[ -n "${LLEX_RUNTIME_PROFILE_COMMON_SOURCED:-}" ]]; then
    return 0
fi
LLEX_RUNTIME_PROFILE_COMMON_SOURCED=1

RUNTIME_PROFILE_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_PROFILE_PROJECT_ROOT="$(cd "${RUNTIME_PROFILE_COMMON_DIR}/.." && pwd)"
RUNTIME_PROFILE_RENDERER="${RUNTIME_PROFILE_RENDERER:-${RUNTIME_PROFILE_COMMON_DIR}/render_target_runtime_profile.py}"
TARGET_DROPLET_CONFIG_FILE="${TARGET_DROPLET_CONFIG_FILE:-${RUNTIME_PROFILE_PROJECT_ROOT}/config/target-droplet.properties}"
BASE_SIMULATOR_CONFIG_FILE="${BASE_SIMULATOR_CONFIG_FILE:-${RUNTIME_PROFILE_PROJECT_ROOT}/config/simulator.properties}"
RUNTIME_PROFILE_DIR="${RUNTIME_PROFILE_DIR:-${RUNTIME_PROFILE_PROJECT_ROOT}/build/runtime-profile}"
RUNTIME_PROFILE_ENV_FILE="${RUNTIME_PROFILE_ENV_FILE:-${RUNTIME_PROFILE_DIR}/docker-runtime.env}"
RUNTIME_PROFILE_COMPOSE_OVERRIDE_FILE="${RUNTIME_PROFILE_COMPOSE_OVERRIDE_FILE:-${RUNTIME_PROFILE_DIR}/docker-compose.override.yml}"
RUNTIME_PROFILE_CONFIG_DIR="${RUNTIME_PROFILE_CONFIG_DIR:-${RUNTIME_PROFILE_DIR}/config}"
RUNTIME_PROFILE_SUMMARY_FILE="${RUNTIME_PROFILE_SUMMARY_FILE:-${RUNTIME_PROFILE_DIR}/profile-summary.txt}"
RUNTIME_PROFILE_LOADED=false

runtime_profile_prepare() {
    if [[ ! -f "${RUNTIME_PROFILE_RENDERER}" || ! -f "${TARGET_DROPLET_CONFIG_FILE}" || ! -f "${BASE_SIMULATOR_CONFIG_FILE}" ]]; then
        return 0
    fi
    mkdir -p "${RUNTIME_PROFILE_DIR}"
    python3 "${RUNTIME_PROFILE_RENDERER}" \
        --target-config "${TARGET_DROPLET_CONFIG_FILE}" \
        --base-simulator-config "${BASE_SIMULATOR_CONFIG_FILE}" \
        --output-dir "${RUNTIME_PROFILE_DIR}"
}

runtime_profile_available_cpus() {
    local candidate=""

    if command -v docker >/dev/null 2>&1; then
        candidate="$(docker info --format '{{.NCPU}}' 2>/dev/null || true)"
        if [[ "${candidate}" =~ ^[0-9]+$ ]] && [[ "${candidate}" -gt 0 ]]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    fi

    candidate="$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)"
    if [[ "${candidate}" =~ ^[0-9]+$ ]] && [[ "${candidate}" -gt 0 ]]; then
        printf '%s\n' "${candidate}"
        return 0
    fi

    if command -v sysctl >/dev/null 2>&1; then
        candidate="$(sysctl -n hw.logicalcpu 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || true)"
        if [[ "${candidate}" =~ ^[0-9]+$ ]] && [[ "${candidate}" -gt 0 ]]; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    fi

    printf '1\n'
}

runtime_profile_resolve_local_cpuset() {
    local requested_cpu_count="$1"
    local available_cpu_count
    local effective_cpu_count

    available_cpu_count="$(runtime_profile_available_cpus)"
    effective_cpu_count="${requested_cpu_count}"
    if [[ "${available_cpu_count}" =~ ^[0-9]+$ ]] && [[ "${available_cpu_count}" -gt 0 ]] && [[ "${effective_cpu_count}" -gt "${available_cpu_count}" ]]; then
        effective_cpu_count="${available_cpu_count}"
    fi

    if [[ "${effective_cpu_count}" -le 1 ]]; then
        printf '0\n'
    else
        printf '0-%s\n' "$((effective_cpu_count - 1))"
    fi
}

runtime_profile_load_env() {
    local preexisting_cpuset="${LLEX_CPUSET:-}"

    if [[ ! -f "${RUNTIME_PROFILE_RENDERER}" || ! -f "${TARGET_DROPLET_CONFIG_FILE}" || ! -f "${BASE_SIMULATOR_CONFIG_FILE}" ]]; then
        if [[ -d "$(dirname "${BASE_SIMULATOR_CONFIG_FILE}")" ]]; then
            export LLEX_CONFIG_DIR="$(dirname "${BASE_SIMULATOR_CONFIG_FILE}")"
        fi
        RUNTIME_PROFILE_LOADED=true
        return 0
    fi

    runtime_profile_prepare
    set -a
    # shellcheck disable=SC1090
    source "${RUNTIME_PROFILE_ENV_FILE}"
    set +a

    export LLEX_CONFIG_DIR="${RUNTIME_PROFILE_CONFIG_DIR}"

    if [[ -n "${preexisting_cpuset}" ]]; then
        export LLEX_CPUSET="${preexisting_cpuset}"
    elif [[ -n "${LLEX_TARGET_CPU_COUNT:-}" ]]; then
        export LLEX_CPUSET="$(runtime_profile_resolve_local_cpuset "${LLEX_TARGET_CPU_COUNT}")"
    fi

    RUNTIME_PROFILE_LOADED=true
}

runtime_profile_show_summary() {
    runtime_profile_prepare
    if [[ ! -f "${RUNTIME_PROFILE_SUMMARY_FILE}" ]]; then
        echo "Runtime profile generation skipped (renderer or target config not available in this environment)."
        return 0
    fi
    cat "${RUNTIME_PROFILE_SUMMARY_FILE}"
}

