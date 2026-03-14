#!/usr/bin/env bash
# =============================================================================
# clean-and-run.sh — Clean runtime state + demo client in one command
# =============================================================================
# Usage:
#   ./scripts/clean-and-run.sh [rate-per-second]
#
# Steps
#   1. fix-demo-client.sh stop  — stop any background demo FIX client
#   2. llexsim.sh clean         — remove containers, volumes, and runtime logs
#   3. llexsim.sh start         — start the existing simulator image (no rebuild)
#   4. fix-demo-client.sh run <rate>  — foreground demo FIX client
#
# The rate argument is passed directly to fix-demo-client.sh run.
# Default rate: 100 msg/s (same default as fix-demo-client.sh).
# =============================================================================

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

RATE="${1:-100}"

require_script "${SCRIPTS_DIR}/llexsim.sh"
require_script "${SCRIPTS_DIR}/fix-demo-client.sh"

# Validate rate is a positive integer
if ! [[ "${RATE}" =~ ^[0-9]+$ ]] || [[ "${RATE}" -le 0 ]]; then
    error "Rate must be a positive integer (messages/sec). Got: '${RATE}'"
    echo "Usage: $0 [rate-per-second]"
    exit 1
fi

banner "Clean + Run  (rate=${RATE} msg/s)"

# ── Step 1: stop any existing background demo client ─────────────────────────
info "Step 1/3 — stopping any running demo FIX client..."
"${SCRIPTS_DIR}/fix-demo-client.sh" stop || true
success "Demo FIX client is stopped."

echo ""

# ── Step 2: clean simulator runtime state and start existing image ───────────
info "Step 2/3 — cleaning simulator runtime state and starting existing container image..."
"${SCRIPTS_DIR}/llexsim.sh" clean
"${SCRIPTS_DIR}/llexsim.sh" start
success "Simulator is up with a clean runtime state."

echo ""

# ── Step 3: run the demo FIX client in the foreground ────────────────────────
info "Step 3/3 — starting demo FIX client at ${RATE} msg/s (foreground, Ctrl+C to stop)..."
"${SCRIPTS_DIR}/fix-demo-client.sh" run "${RATE}"

