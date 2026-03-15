#!/usr/bin/env bash
# =============================================================================
# Demo FIX Client lifecycle helper
# =============================================================================
# Uses the Docker Compose `fix-demo-client` service so the exact same workflow
# works locally and on a deployed droplet without requiring localhost/FQDN args.
# =============================================================================

set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./fix_demo_client_common.sh
source "${SCRIPTS_DIR}/fix_demo_client_common.sh"

cmd_help() {
    cat << EOF

${BOLD}${CYAN}Demo FIX Client lifecycle helper${RESET}

${BOLD}Usage:${RESET}
  ./scripts/local_fix_demo_client.sh <command> [rate-per-second]

${BOLD}Commands:${RESET}
  ${GREEN}start [rate]${RESET}   Start in background via Docker Compose (default rate: ${FIX_DEMO_DEFAULT_RATE} msg/s)
  ${GREEN}run [rate]${RESET}     Start, stream logs in foreground, stop on Ctrl+C
  ${GREEN}stop${RESET}           Stop and remove the demo client container
  ${GREEN}restart [rate]${RESET} Restart the demo client at a new rate
  ${GREEN}status${RESET}         Show container status
  ${GREEN}logs${RESET}           Tail demo client container logs
  ${GREEN}help${RESET}           Show this help

${BOLD}Environment Overrides:${RESET}
  FIX_DEMO_RATE=100
  FIX_CLIENT_BEGIN_STRING=FIX.4.4
  FIX_CLIENT_SENDER_COMP_ID=CLIENT1
  FIX_CLIENT_TARGET_COMP_ID=LLEXSIM
  FIX_CLIENT_SYMBOL=AAPL
  FIX_CLIENT_SIDE=BUY
  FIX_CLIENT_ORDER_QTY=100
  FIX_CLIENT_PRICE=100.25
  FIX_CLIENT_PORT=9880

${BOLD}Notes:${RESET}
  - The demo client connects to ${BOLD}llexsimulator${RESET} inside the Docker network by default.
  - No localhost or droplet hostname argument is required when using the Docker workflow.

${BOLD}Examples:${RESET}
  ./scripts/local_fix_demo_client.sh start
  ./scripts/local_fix_demo_client.sh start 250
  ./scripts/local_fix_demo_client.sh run 1000
  FIX_CLIENT_SYMBOL=MSFT ./scripts/local_fix_demo_client.sh start 500
  ./scripts/local_fix_demo_client.sh stop

EOF
}

COMMAND="${1:-help}"
shift || true

case "${COMMAND}" in
    start)
        if [[ $# -gt 1 ]]; then
            error "Usage: ./scripts/local_fix_demo_client.sh start [rate-per-second]"
            exit 1
        fi
        start_fix_demo_client "${1:-}"
        ;;
    run)
        if [[ $# -gt 1 ]]; then
            error "Usage: ./scripts/local_fix_demo_client.sh run [rate-per-second]"
            exit 1
        fi
        run_fix_demo_client_foreground "${1:-}"
        ;;
    stop)
        [[ $# -eq 0 ]] || { error "Usage: ./scripts/local_fix_demo_client.sh stop"; exit 1; }
        stop_fix_demo_client
        ;;
    restart)
        if [[ $# -gt 1 ]]; then
            error "Usage: ./scripts/local_fix_demo_client.sh restart [rate-per-second]"
            exit 1
        fi
        stop_fix_demo_client
        start_fix_demo_client "${1:-}"
        ;;
    status)
        [[ $# -eq 0 ]] || { error "Usage: ./scripts/local_fix_demo_client.sh status"; exit 1; }
        status_fix_demo_client
        ;;
    logs)
        [[ $# -eq 0 ]] || { error "Usage: ./scripts/local_fix_demo_client.sh logs"; exit 1; }
        logs_fix_demo_client
        ;;
    help|--help|-h)
        cmd_help
        ;;
    *)
        error "Unknown command: '${COMMAND}'"
        cmd_help
        exit 1
        ;;
esac

