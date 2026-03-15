#!/bin/bash
# =============================================================================
# local_stop_all.sh — Stop the demo client and Dockerized simulator together
# =============================================================================
# Usage:
#   ./scripts/local_stop_all.sh
#
# Steps
#   1. local_fix_demo_client.sh stop  — stop any background demo FIX client
#   2. local_llexsim.sh stop          — gracefully stop the Dockerized simulator
#
# The script is intentionally idempotent: if one or both targets are already
# stopped, it reports that state and still exits successfully.
# =============================================================================

set -uo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_SCRIPT="${SCRIPTS_DIR}/local_fix_demo_client.sh"
SIMULATOR_SCRIPT="${SCRIPTS_DIR}/local_llexsim.sh"
BASH_BIN="/bin/bash"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
banner()  { echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; \
            echo -e "${BOLD}  $*${RESET}"; \
            echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}\n"; }

require_script() {
    local script_path="$1"
    if [[ ! -f "${script_path}" ]]; then
        error "Required script not found: ${script_path}"
        exit 1
    fi
}

stop_client() {
    info "Stopping background demo FIX client..."
    if "${BASH_BIN}" "${CLIENT_SCRIPT}" stop; then
        success "Demo FIX client stop step completed."
    else
        error "Failed to stop the demo FIX client."
        return 1
    fi
}

stop_simulator() {
    if ! command -v docker &>/dev/null; then
        warn "Docker is not installed or not in PATH — skipping simulator stop."
        return 0
    fi

    if ! docker info &>/dev/null; then
        warn "Docker daemon is not running — assuming simulator is already down."
        return 0
    fi

    info "Stopping Dockerized simulator..."
    if "${BASH_BIN}" "${SIMULATOR_SCRIPT}" stop; then
        success "Simulator stop step completed."
    else
        error "Failed to stop the simulator container."
        return 1
    fi
}

main() {
    local failures=0

    require_script "${CLIENT_SCRIPT}"
    require_script "${SIMULATOR_SCRIPT}"

    banner "Stop All"

    stop_client || failures=$((failures + 1))
    echo ""
    stop_simulator || failures=$((failures + 1))

    echo ""
    if [[ ${failures} -eq 0 ]]; then
        success "Stop-all completed."
        return 0
    fi

    error "Stop-all completed with ${failures} failure(s). Review the output above."
    return 1
}

main "$@"

