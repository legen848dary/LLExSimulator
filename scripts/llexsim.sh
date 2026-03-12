#!/usr/bin/env bash
# =============================================================================
# LLExSimulator — Docker Lifecycle Manager
# =============================================================================
# Usage:
#   ./scripts/llexsim.sh <command> [options]
#
# Commands:
#   build       Build the Docker image (runs Gradle + Docker build)
#   start       Start the simulator (builds image if not present)
#   stop        Gracefully stop the running container
#   restart     Stop, then start the simulator
#   status      Show container status and health
#   logs        Tail container logs (Ctrl+C to exit)
#   clean       Stop and remove containers, volumes, and dangling images
#   purge       clean + remove the built image entirely
#   fix-connect Test FIX connectivity via nc (requires netcat)
#   help        Show this help message
# =============================================================================

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
COMPOSE_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/docker-compose.yml"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_NAME="llexsimulator:1.0-SNAPSHOT"
CONTAINER_NAME="llexsimulator"
WEB_PORT="${WEB_PORT:-8080}"
FIX_PORT="${FIX_PORT:-9880}"
LOG_LINES="${LOG_LINES:-100}"

# ── Colours ───────────────────────────────────────────────────────────────────
RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
banner()  { echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; echo -e "${BOLD}  LLExSimulator — $*${RESET}"; echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}\n"; }

# ── Helpers ───────────────────────────────────────────────────────────────────
require_docker() {
    if ! command -v docker &>/dev/null; then
        error "Docker is not installed or not in PATH."
        exit 1
    fi
    if ! docker info &>/dev/null; then
        error "Docker daemon is not running. Please start Docker Desktop."
        exit 1
    fi
}

require_compose() {
    require_docker
    if ! docker compose version &>/dev/null; then
        error "Docker Compose (v2) is required. Install Docker Desktop >= 3.6."
        exit 1
    fi
}

image_exists() {
    docker image inspect "${IMAGE_NAME}" &>/dev/null
}

container_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "${CONTAINER_NAME}" 2>/dev/null)" = "true" ]
}

container_exists() {
    docker inspect "${CONTAINER_NAME}" &>/dev/null
}

wait_healthy() {
    local max_wait=60
    local waited=0
    info "Waiting for simulator to become healthy (max ${max_wait}s)..."
    while [ $waited -lt $max_wait ]; do
        local health
        health=$(docker inspect -f '{{.State.Health.Status}}' "${CONTAINER_NAME}" 2>/dev/null || echo "none")
        case "$health" in
            healthy)
                success "Container is healthy!"
                return 0
                ;;
            unhealthy)
                error "Container reported unhealthy. Check logs with: $0 logs"
                return 1
                ;;
            *)
                printf "."
                sleep 2
                waited=$((waited + 2))
                ;;
        esac
    done
    echo ""
    warn "Health check timed out after ${max_wait}s. Container may still be starting."
    warn "Check logs with: ./scripts/llexsim.sh logs"
}

# ── Commands ──────────────────────────────────────────────────────────────────

cmd_build() {
    banner "Building Docker Image"
    require_compose
    cd "${PROJECT_ROOT}"

    if [[ "${1:-}" == "--no-cache" ]]; then
        info "Building fat JAR and Docker image (no cache)..."
        docker compose --progress plain -f "${COMPOSE_FILE}" build --no-cache
    else
        info "Building fat JAR and Docker image (cached layers enabled)..."
        docker compose --progress plain -f "${COMPOSE_FILE}" build
    fi

    success "Image built: ${IMAGE_NAME}"
    docker images "${IMAGE_NAME}" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedAt}}"
}

cmd_start() {
    banner "Starting LLExSimulator"
    require_compose
    cd "${PROJECT_ROOT}"

    if container_running; then
        warn "Simulator is already running."
        cmd_status
        return 0
    fi

    if ! image_exists; then
        info "Image not found — building first..."
        cmd_build
    fi

    # Ensure logs directory exists (mounted into container)
    mkdir -p "${PROJECT_ROOT}/logs"

    info "Starting containers..."
    docker compose -f "${COMPOSE_FILE}" up -d --force-recreate

    wait_healthy

    echo ""
    success "LLExSimulator is running!"
    echo -e "  ${BOLD}Web UI / REST API${RESET}  →  http://localhost:${WEB_PORT}"
    echo -e "  ${BOLD}FIX Acceptor${RESET}       →  tcp://localhost:${FIX_PORT}"
    echo -e "  ${BOLD}Health check${RESET}       →  http://localhost:${WEB_PORT}/api/health"
    echo ""
    info "Tail logs with: ./scripts/llexsim.sh logs"
}

cmd_stop() {
    banner "Stopping LLExSimulator"
    require_compose
    cd "${PROJECT_ROOT}"

    if ! container_exists; then
        warn "No container found — nothing to stop."
        return 0
    fi

    info "Sending SIGTERM (graceful shutdown)..."
    docker compose -f "${COMPOSE_FILE}" stop --timeout 15

    success "Simulator stopped."
}

cmd_restart() {
    banner "Restarting LLExSimulator"
    cmd_stop
    sleep 2
    cmd_start
}

cmd_status() {
    banner "Simulator Status"
    require_docker

    if ! container_exists; then
        warn "Container '${CONTAINER_NAME}' does not exist."
        echo "  Run: ./scripts/llexsim.sh start"
        return 0
    fi

    echo -e "${BOLD}Container:${RESET}"
    docker inspect "${CONTAINER_NAME}" --format \
        "  Name:    {{.Name}}
  Status:  {{.State.Status}}
  Health:  {{.State.Health.Status}}
  Started: {{.State.StartedAt}}
  Image:   {{.Config.Image}}"

    echo ""
    echo -e "${BOLD}Port Bindings:${RESET}"
    docker inspect "${CONTAINER_NAME}" --format \
        '{{range $p, $b := .NetworkSettings.Ports}}  {{$p}} -> {{(index $b 0).HostPort}}
{{end}}' 2>/dev/null || true

    echo ""
    if container_running; then
        echo -e "${BOLD}Live Health:${RESET}"
        local health_url="http://localhost:${WEB_PORT}/api/health"
        if curl -sf "${health_url}" 2>/dev/null | python3 -m json.tool 2>/dev/null; then
            success "API is reachable at ${health_url}"
        else
            warn "API not reachable at ${health_url} — container may still be starting"
        fi
    fi

    echo ""
    echo -e "${BOLD}Resource Usage:${RESET}"
    docker stats "${CONTAINER_NAME}" --no-stream --format \
        "  CPU:     {{.CPUPerc}}
  Memory:  {{.MemUsage}}
  Net I/O: {{.NetIO}}" 2>/dev/null || warn "Could not get stats (container not running?)"
}

cmd_logs() {
    require_docker
    if ! container_exists; then
        error "Container '${CONTAINER_NAME}' not found. Start with: ./scripts/llexsim.sh start"
        exit 1
    fi
    info "Showing last ${LOG_LINES} lines (Ctrl+C to stop following)..."
    docker logs "${CONTAINER_NAME}" --tail "${LOG_LINES}" -f
}

cmd_clean() {
    banner "Cleaning Up"
    require_compose
    cd "${PROJECT_ROOT}"

    info "Stopping and removing containers..."
    docker compose -f "${COMPOSE_FILE}" down --volumes --remove-orphans 2>/dev/null || true

    info "Removing dangling images..."
    docker image prune -f 2>/dev/null || true

    info "Removing local logs directory contents..."
    rm -rf "${PROJECT_ROOT}/logs"/* 2>/dev/null || true

    success "Clean complete. Image '${IMAGE_NAME}' is preserved."
    info "Run 'start' to bring up a fresh instance."
}

cmd_purge() {
    banner "Full Purge"
    cmd_clean

    info "Removing image ${IMAGE_NAME}..."
    docker rmi "${IMAGE_NAME}" 2>/dev/null && success "Image removed." || warn "Image not found — already removed."

    info "Removing Gradle build cache..."
    rm -rf "${PROJECT_ROOT}/build" 2>/dev/null || true

    success "Purge complete. Run 'build' to rebuild from source."
}

cmd_fix_connect() {
    banner "FIX Connectivity Test"
    if ! command -v nc &>/dev/null; then
        error "'nc' (netcat) is required for this test. Install via: brew install netcat"
        exit 1
    fi

    info "Testing TCP connection to FIX port ${FIX_PORT}..."
    if nc -z -w 3 localhost "${FIX_PORT}" 2>/dev/null; then
        success "FIX port ${FIX_PORT} is open and accepting connections."
        echo ""
        info "To connect with a FIX client, configure:"
        echo "  Host:          localhost"
        echo "  Port:          ${FIX_PORT}"
        echo "  BeginString:   FIX.4.2  |  FIX.4.4  |  FIXT.1.1"
        echo "  SenderCompID:  CLIENT1  (or CLIENT2 for FIX 5.0 SP2)"
        echo "  TargetCompID:  LLEXSIM"
    else
        error "Could not connect to FIX port ${FIX_PORT}. Is the simulator running?"
        echo "  Run: ./scripts/llexsim.sh start"
        exit 1
    fi
}

cmd_help() {
    cat << EOF

${BOLD}${CYAN}LLExSimulator — Docker Lifecycle Manager${RESET}

${BOLD}Usage:${RESET}
  ./scripts/llexsim.sh <command> [options]

${BOLD}Commands:${RESET}
  ${GREEN}build${RESET}        Build the Docker image from source (Gradle + Docker)
  ${GREEN}start${RESET}        Start the simulator (auto-builds if image missing)
  ${GREEN}stop${RESET}         Gracefully stop the running container
  ${GREEN}restart${RESET}      Stop then start (rolling restart)
  ${GREEN}status${RESET}       Show container status, health, and resource usage
  ${GREEN}logs${RESET}         Tail container logs in real-time (Ctrl+C to exit)
  ${GREEN}clean${RESET}        Remove containers + volumes; keep built image
  ${GREEN}purge${RESET}        Full clean: removes containers, volumes, image, build dir
  ${GREEN}fix-connect${RESET}  Test FIX port connectivity with nc
  ${GREEN}help${RESET}         Show this help

${BOLD}Environment Variables:${RESET}
  WEB_PORT    Web UI / REST API port  (default: 8080)
  FIX_PORT    FIX acceptor port       (default: 9880)
  LOG_LINES   Lines shown by 'logs'   (default: 100)

${BOLD}Examples:${RESET}
  # First-time setup
  ./scripts/llexsim.sh build
  ./scripts/llexsim.sh build --no-cache
  ./scripts/llexsim.sh start

  # Daily use
  ./scripts/llexsim.sh status
  ./scripts/llexsim.sh logs
  ./scripts/llexsim.sh restart

  # Override configuration at runtime
  WEB_PORT=9090 ./scripts/llexsim.sh start

  # Full cleanup before a clean rebuild
  ./scripts/llexsim.sh purge
  ./scripts/llexsim.sh build && ./scripts/llexsim.sh start

${BOLD}Web UI:${RESET}
  http://localhost:${WEB_PORT}

${BOLD}REST API:${RESET}
  http://localhost:${WEB_PORT}/api/health
  http://localhost:${WEB_PORT}/api/statistics
  http://localhost:${WEB_PORT}/api/fill-profiles
  http://localhost:${WEB_PORT}/api/sessions

EOF
}

# ── Dispatch ──────────────────────────────────────────────────────────────────
COMMAND="${1:-help}"
shift || true

case "${COMMAND}" in
    build)       cmd_build "$@"       ;;
    start)       cmd_start "$@"       ;;
    stop)        cmd_stop "$@"        ;;
    restart)     cmd_restart "$@"     ;;
    status)      cmd_status "$@"      ;;
    logs)        cmd_logs "$@"        ;;
    clean)       cmd_clean "$@"       ;;
    purge)       cmd_purge "$@"       ;;
    fix-connect) cmd_fix_connect "$@" ;;
    help|--help|-h) cmd_help         ;;
    *)
        error "Unknown command: '${COMMAND}'"
        cmd_help
        exit 1
        ;;
esac

