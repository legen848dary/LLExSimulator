#!/usr/bin/env bash
# =============================================================================
# rebuild-and-run.sh — Full clean rebuild + demo client in one command
# =============================================================================
# Usage:
#   ./scripts/rebuild-and-run.sh [rate-per-second]
#
# Steps
#   1. llexsim.sh rebuild  — purge, Gradle build, Docker image, start container
#   2. fix-demo-client.sh run <rate>  — foreground demo FIX client
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

RATE="${1:-100}"

# Validate rate is a positive integer
if ! [[ "${RATE}" =~ ^[0-9]+$ ]] || [[ "${RATE}" -le 0 ]]; then
    error "Rate must be a positive integer (messages/sec). Got: '${RATE}'"
    echo "Usage: $0 [rate-per-second]"
    exit 1
fi

banner "Rebuild + Run  (rate=${RATE} msg/s)"

# ── Step 1: full clean rebuild of the simulator ───────────────────────────────
info "Step 1/2 — rebuilding simulator (purge → build → start)..."
"${SCRIPTS_DIR}/llexsim.sh" rebuild
success "Simulator is up."

echo ""

# ── Step 2: run the demo FIX client in the foreground ────────────────────────
info "Step 2/2 — starting demo FIX client at ${RATE} msg/s (foreground, Ctrl+C to stop)..."
"${SCRIPTS_DIR}/fix-demo-client.sh" run "${RATE}"

