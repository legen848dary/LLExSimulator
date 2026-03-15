#!/bin/bash
# =============================================================================
# local_clean_and_run.sh — Clean ledgers/state + demo client in one command
# =============================================================================
# Usage:
#   ./scripts/local_clean_and_run.sh [rate-per-second]
#
# Rate precedence:
#   1. explicit positional [rate-per-second]
#   2. FIX_DEMO_RATE environment variable
#   3. default 100 msg/s
#
# Steps
#   1. local_clean_ledgers.sh         — stop simulator/client and remove ledger/runtime state
#   2. local_llexsim.sh start         — start the existing simulator image (no rebuild)
#   3. local_fix_demo_client.sh run <rate>  — foreground demo FIX client
#
# The rate argument is passed directly to local_fix_demo_client.sh run.
# Default rate: 100 msg/s (same default as local_fix_demo_client.sh).
# =============================================================================

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASH_BIN="/bin/bash"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
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

RATE="${1:-${FIX_DEMO_RATE:-500}}"

require_script "${SCRIPTS_DIR}/local_llexsim.sh"
require_script "${SCRIPTS_DIR}/local_fix_demo_client.sh"
require_script "${SCRIPTS_DIR}/local_clean_ledgers.sh"

# Validate rate is a positive integer
if ! [[ "${RATE}" =~ ^[0-9]+$ ]] || [[ "${RATE}" -le 0 ]]; then
    error "Rate must be a positive integer (messages/sec). Got: '${RATE}'"
    echo "Usage: ./scripts/local_clean_and_run.sh [rate-per-second]"
    exit 1
fi

banner "Clean + Run  (rate=${RATE} msg/s)"

# ── Step 1: clean ledger/runtime state ────────────────────────────────────────
info "Step 1/3 — stopping services and cleaning ledgers/runtime state..."
"${BASH_BIN}" "${SCRIPTS_DIR}/local_clean_ledgers.sh"
success "Ledger/runtime state cleanup completed."

echo ""

# ── Step 2: start existing simulator image ────────────────────────────────────
info "Step 2/3 — starting existing simulator container image..."
"${BASH_BIN}" "${SCRIPTS_DIR}/local_llexsim.sh" start
success "Simulator is up with clean ledger/runtime state."

echo ""

# ── Step 3: run the demo FIX client in the foreground ────────────────────────
info "Step 3/3 — starting demo FIX client at ${RATE} msg/s (foreground, Ctrl+C to stop)..."
"${BASH_BIN}" "${SCRIPTS_DIR}/local_fix_demo_client.sh" run "${RATE}"

