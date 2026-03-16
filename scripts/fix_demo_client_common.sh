#!/usr/bin/env bash
# =============================================================================
# Shared helpers for Docker-based FIX demo client lifecycle scripts.
# Works both from the local repo checkout and from a deployed droplet app dir.
# =============================================================================

set -euo pipefail

FIX_DEMO_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIX_DEMO_ROOT="${FIX_DEMO_ROOT:-$(cd "${FIX_DEMO_SCRIPTS_DIR}/.." && pwd)}"
RUNTIME_PROFILE_HELPER="${FIX_DEMO_SCRIPTS_DIR}/runtime_profile_common.sh"
FIX_DEMO_COMPOSE_FILE="${FIX_DEMO_COMPOSE_FILE:-${FIX_DEMO_ROOT}/docker-compose.yml}"
FIX_DEMO_SERVICE_NAME="${FIX_DEMO_SERVICE_NAME:-fix-demo-client}"
FIX_DEMO_SIMULATOR_SERVICE="${FIX_DEMO_SIMULATOR_SERVICE:-llexsimulator}"
FIX_DEMO_CONTAINER_NAME="${FIX_DEMO_CONTAINER_NAME:-llexsimulator-fix-demo-client}"
FIX_DEMO_DEFAULT_RATE="${FIX_DEMO_RATE:-100}"
FIX_DEMO_LOGS_DIR="${FIX_DEMO_ROOT}/logs/fix-demo-client"
FIX_DEMO_LOG_TAIL_LINES="${FIX_DEMO_LOG_TAIL_LINES:-100}"

# shellcheck source=./runtime_profile_common.sh
source "${RUNTIME_PROFILE_HELPER}"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
banner()  {
    echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"
    echo -e "${BOLD}  $*${RESET}"
    echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}\n"
}

require_docker_compose() {
    if ! command -v docker >/dev/null 2>&1; then
        error "Docker is not installed or not in PATH."
        exit 1
    fi
    if ! docker info >/dev/null 2>&1; then
        error "Docker daemon is not running."
        exit 1
    fi
    if ! docker compose version >/dev/null 2>&1; then
        error "Docker Compose v2 is required."
        exit 1
    fi
}

ensure_compose_file() {
    if [[ ! -f "${FIX_DEMO_COMPOSE_FILE}" ]]; then
        error "Compose file not found: ${FIX_DEMO_COMPOSE_FILE}"
        error "Run this script from the checked-out repo or from the deployed app directory."
        exit 1
    fi

    if ! grep -qE '^\s+fix-demo-client:\s*$' "${FIX_DEMO_COMPOSE_FILE}"; then
        error "Service '${FIX_DEMO_SERVICE_NAME}' is missing from ${FIX_DEMO_COMPOSE_FILE}"
        exit 1
    fi

    if ! grep -qE '^\s+llexsimulator:\s*$' "${FIX_DEMO_COMPOSE_FILE}"; then
        error "Service '${FIX_DEMO_SIMULATOR_SERVICE}' is missing from ${FIX_DEMO_COMPOSE_FILE}"
        exit 1
    fi
}

ensure_logs_dir() {
    mkdir -p "${FIX_DEMO_LOGS_DIR}"
}

compose_cmd() {
    runtime_profile_load_env
    local -a compose_args=( -f "${FIX_DEMO_COMPOSE_FILE}" )
    if [[ -f "${RUNTIME_PROFILE_COMPOSE_OVERRIDE_FILE}" ]]; then
        compose_args+=( -f "${RUNTIME_PROFILE_COMPOSE_OVERRIDE_FILE}" )
    fi
    docker compose "${compose_args[@]}" "$@"
}

resolve_rate() {
    local raw="${1:-${FIX_DEMO_DEFAULT_RATE}}"
    if ! [[ "${raw}" =~ ^[0-9]+$ ]] || [[ "${raw}" -le 0 ]]; then
        error "Rate must be a positive integer messages/sec. Got: '${raw}'"
        exit 1
    fi
    printf '%s\n' "${raw}"
}

fix_demo_container_id() {
    compose_cmd ps -q "${FIX_DEMO_SERVICE_NAME}" 2>/dev/null | head -n 1
}

fix_demo_container_exists() {
    [[ -n "$(fix_demo_container_id)" ]]
}

fix_demo_container_running() {
    local container_id
    container_id="$(fix_demo_container_id)"
    [[ -n "${container_id}" ]] && [[ "$(docker inspect -f '{{.State.Running}}' "${container_id}" 2>/dev/null || echo false)" == "true" ]]
}

remove_fix_demo_client_quietly() {
    compose_cmd stop "${FIX_DEMO_SERVICE_NAME}" >/dev/null 2>&1 || true
    compose_cmd rm -f "${FIX_DEMO_SERVICE_NAME}" >/dev/null 2>&1 || true
}

start_fix_demo_client() {
    local rate
    rate="$(resolve_rate "${1:-}")"

    banner "Starting Demo FIX Client"
    require_docker_compose
    ensure_compose_file
    ensure_logs_dir

    if fix_demo_container_exists; then
        info "Refreshing existing demo client container so the new rate takes effect..."
        remove_fix_demo_client_quietly
    fi

    info "Compose file: ${FIX_DEMO_COMPOSE_FILE}"
    info "Starting at ${rate} NewOrderSingles/sec..."
    FIX_DEMO_RATE="${rate}" compose_cmd --profile demo-client up -d --build "${FIX_DEMO_SERVICE_NAME}"

    success "Demo FIX client started."
    echo -e "  ${BOLD}Rate${RESET}         → ${rate} msg/s"
    echo -e "  ${BOLD}Service${RESET}      → ${FIX_DEMO_SERVICE_NAME}"
    echo -e "  ${BOLD}Container${RESET}    → ${FIX_DEMO_CONTAINER_NAME}"
    echo -e "  ${BOLD}Simulator host${RESET} → internal Docker DNS name 'llexsimulator'"
}

stop_fix_demo_client() {
    banner "Stopping Demo FIX Client"
    require_docker_compose
    ensure_compose_file

    if ! fix_demo_container_exists; then
        warn "Demo FIX client is not running."
        return 0
    fi

    info "Stopping and removing ${FIX_DEMO_SERVICE_NAME}..."
    remove_fix_demo_client_quietly
    success "Demo FIX client stopped."
}

status_fix_demo_client() {
    banner "Demo FIX Client Status"
    require_docker_compose
    ensure_compose_file

    if ! fix_demo_container_exists; then
        warn "Demo FIX client container does not exist yet."
        echo "  Run: ./scripts/fix_demo_client_start.sh 100"
        return 0
    fi

    compose_cmd ps "${FIX_DEMO_SERVICE_NAME}"
}

logs_fix_demo_client() {
    require_docker_compose
    ensure_compose_file

    if ! fix_demo_container_exists; then
        error "Demo FIX client is not running. Start it first with ./scripts/fix_demo_client_start.sh <rate>."
        exit 1
    fi

    info "Tailing ${FIX_DEMO_SERVICE_NAME} logs (Ctrl+C to exit)..."
    compose_cmd logs -f --tail "${FIX_DEMO_LOG_TAIL_LINES}" "${FIX_DEMO_SERVICE_NAME}"
}

run_fix_demo_client_foreground() {
    local rate
    FIX_DEMO_FOREGROUND_CLEANUP_REQUIRED=false
    rate="$(resolve_rate "${1:-}")"

    cleanup() {
        if [[ "${FIX_DEMO_FOREGROUND_CLEANUP_REQUIRED:-false}" == true ]]; then
            FIX_DEMO_FOREGROUND_CLEANUP_REQUIRED=false
            remove_fix_demo_client_quietly
        fi
    }

    trap cleanup EXIT INT TERM

    start_fix_demo_client "${rate}"
    FIX_DEMO_FOREGROUND_CLEANUP_REQUIRED=true
    echo ""
    info "Streaming demo client logs in the foreground (Ctrl+C to stop only the client)..."
    compose_cmd logs -f --tail "${FIX_DEMO_LOG_TAIL_LINES}" "${FIX_DEMO_SERVICE_NAME}" || true
    cleanup
    trap - EXIT INT TERM
}

