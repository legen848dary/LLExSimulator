#!/usr/bin/env bash
# =============================================================================
# droplet_llexsim.sh — Manage the deployed simulator directly on the droplet
# =============================================================================
# Usage:
#   ./scripts/droplet_llexsim.sh <start|stop|logs|health>
#
# Intended location:
#   /opt/llexsimulator/scripts/droplet_llexsim.sh
# =============================================================================

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${APP_DIR:-$(cd "${SCRIPTS_DIR}/.." && pwd)}"
COMPOSE_FILE="${COMPOSE_FILE:-${APP_DIR}/docker-compose.yml}"
SERVICE_NAME="${SERVICE_NAME:-llexsimulator}"
CONTAINER_NAME="${CONTAINER_NAME:-llexsimulator}"
WEB_PORT="${WEB_PORT:-8080}"
LOG_LINES="${LOG_LINES:-100}"
WAIT_SECONDS="${WAIT_SECONDS:-120}"

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

usage() {
    cat <<EOF
${BOLD}${CYAN}LLExSimulator droplet helper${RESET}

${BOLD}Usage:${RESET}
  ./scripts/droplet_llexsim.sh <command>

${BOLD}Commands:${RESET}
  ${GREEN}start${RESET}   Start/recreate the deployed simulator container and wait for health
  ${GREEN}stop${RESET}    Gracefully stop the deployed simulator container
  ${GREEN}logs${RESET}    Follow simulator logs (tail ${LOG_LINES} lines by default)
  ${GREEN}health${RESET}  Print the local health endpoint payload from the droplet
  ${GREEN}help${RESET}    Show this help

${BOLD}Environment overrides:${RESET}
  APP_DIR=${APP_DIR}
  COMPOSE_FILE=${COMPOSE_FILE}
  WEB_PORT=${WEB_PORT}
  LOG_LINES=${LOG_LINES}
  WAIT_SECONDS=${WAIT_SECONDS}
EOF
}

run_docker() {
    if docker info >/dev/null 2>&1; then
        docker "$@"
        return 0
    fi

    if command -v sudo >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
        sudo docker "$@"
        return 0
    fi

    error "Docker daemon is not accessible for user $(id -un)."
    error "Log in again after bootstrap, or use a passwordless sudo-capable user."
    exit 1
}

compose_cmd() {
    run_docker compose -f "${COMPOSE_FILE}" "$@"
}

require_dependencies() {
    if ! command -v docker >/dev/null 2>&1; then
        error "Docker is not installed or not in PATH."
        exit 1
    fi
    if ! command -v curl >/dev/null 2>&1; then
        error "curl is required for the health check."
        exit 1
    fi
    if ! run_docker compose version >/dev/null 2>&1; then
        error "Docker Compose v2 is required."
        exit 1
    fi
}

ensure_compose_file() {
    if [[ ! -f "${COMPOSE_FILE}" ]]; then
        error "Compose file not found: ${COMPOSE_FILE}"
        error "Run this from the deployed droplet app directory or set APP_DIR/COMPOSE_FILE explicitly."
        exit 1
    fi

    if ! grep -qE '^\s+llexsimulator:\s*$' "${COMPOSE_FILE}"; then
        error "Service '${SERVICE_NAME}' is missing from ${COMPOSE_FILE}"
        exit 1
    fi
}

health_url() {
    printf 'http://127.0.0.1:%s/api/health\n' "${WEB_PORT}"
}

container_status() {
    run_docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${CONTAINER_NAME}" 2>/dev/null || echo "missing"
}

wait_for_health() {
    local deadline=$((SECONDS + WAIT_SECONDS))
    local url
    url="$(health_url)"

    info "Waiting for simulator health at ${url} (timeout ${WAIT_SECONDS}s)..."
    while (( SECONDS < deadline )); do
        local status
        status="$(container_status)"
        case "${status}" in
            healthy)
                if curl -fsS "${url}" >/dev/null 2>&1; then
                    success "Simulator is healthy."
                    return 0
                fi
                ;;
            unhealthy|exited|dead)
                error "Container entered bad state: ${status}"
                compose_cmd logs --tail "${LOG_LINES}" "${SERVICE_NAME}" || true
                return 1
                ;;
        esac

        if curl -fsS "${url}" >/dev/null 2>&1; then
            success "Simulator health endpoint is responding."
            return 0
        fi

        sleep 2
    done

    error "Timed out waiting for ${url}"
    compose_cmd ps || true
    compose_cmd logs --tail "${LOG_LINES}" "${SERVICE_NAME}" || true
    return 1
}

cmd_start() {
    banner "Starting droplet simulator"
    require_dependencies
    ensure_compose_file

    info "Using compose file: ${COMPOSE_FILE}"
    compose_cmd up -d --force-recreate --remove-orphans "${SERVICE_NAME}"
    wait_for_health

    echo ""
    success "Simulator is running."
    echo -e "  ${BOLD}Health${RESET}  → $(health_url)"
}

cmd_stop() {
    banner "Stopping droplet simulator"
    require_dependencies
    ensure_compose_file

    if ! run_docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
        warn "Container '${CONTAINER_NAME}' does not exist. Nothing to stop."
        return 0
    fi

    compose_cmd stop --timeout 15 "${SERVICE_NAME}"
    success "Simulator stopped."
}

cmd_logs() {
    banner "Droplet simulator logs"
    require_dependencies
    ensure_compose_file

    info "Tailing ${SERVICE_NAME} logs (Ctrl+C to exit)..."
    compose_cmd logs -f --tail "${LOG_LINES}" "${SERVICE_NAME}"
}

cmd_health() {
    banner "Droplet simulator health"
    require_dependencies
    ensure_compose_file

    curl -fsS "$(health_url)"
    echo ""
}

COMMAND="${1:-help}"
shift || true

if [[ $# -ne 0 ]]; then
    error "This helper does not accept extra arguments."
    usage
    exit 1
fi

case "${COMMAND}" in
    start)
        cmd_start
        ;;
    stop)
        cmd_stop
        ;;
    logs)
        cmd_logs
        ;;
    health)
        cmd_health
        ;;
    help|--help|-h)
        usage
        ;;
    *)
        error "Unknown command: ${COMMAND}"
        usage
        exit 1
        ;;
esac

