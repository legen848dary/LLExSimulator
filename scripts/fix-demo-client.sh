#!/usr/bin/env bash
# =============================================================================
# Demo FIX Client lifecycle helper
# =============================================================================
# Usage:
#   ./scripts/fix-demo-client.sh <command> [rate-per-second]
#
# Commands:
#   start [rate]   Start the demo client in background (default: 100 msg/s)
#   run   [rate]   Run the demo client in foreground
#   stop           Stop the background demo client
#   restart [rate] Stop, then start again
#   status         Show client PID/log status
#   logs           Tail the client console log
#   help           Show this help
# =============================================================================

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_PATH="${PROJECT_ROOT}/build/libs/LLExSimulator-1.0-SNAPSHOT.jar"
MAIN_CLASS="com.llexsimulator.client.FixDemoClientMain"
LOG_ROOT="${PROJECT_ROOT}/logs/fix-demo-client"
QFJ_LOG_DIR="${LOG_ROOT}/quickfixj"
PID_FILE="${LOG_ROOT}/demo-fix-client.pid"
CONSOLE_LOG="${LOG_ROOT}/console.log"
DEFAULT_RATE="100"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
banner()  { echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; echo -e "${BOLD}  Demo FIX Client — $*${RESET}"; echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}\n"; }

require_java() {
    if ! command -v java &>/dev/null; then
        error "Java is not installed or not in PATH."
        exit 1
    fi
}

ensure_dirs() {
    mkdir -p "${LOG_ROOT}/archive" "${QFJ_LOG_DIR}"
}

client_pid() {
    if [[ -f "${PID_FILE}" ]]; then
        cat "${PID_FILE}"
    fi
}

client_running() {
    local pid
    pid="$(client_pid || true)"
    [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null
}

ensure_jar() {
    if [[ ! -f "${JAR_PATH}" ]] || find "${PROJECT_ROOT}/src/main" "${PROJECT_ROOT}/build.gradle.kts" "${PROJECT_ROOT}/settings.gradle.kts" -type f -newer "${JAR_PATH}" | grep -q .; then
        info "Application JAR is missing or stale — building shadow JAR..."
        (cd "${PROJECT_ROOT}" && ./gradlew --no-daemon shadowJar -x test)
    fi
}

rate_arg() {
    local raw="${1:-${DEFAULT_RATE}}"
    if ! [[ "${raw}" =~ ^[0-9]+$ ]] || [[ "${raw}" -le 0 ]]; then
        error "Rate must be a positive integer messages/sec. Got: '${raw}'"
        exit 1
    fi
    echo "${raw}"
}

java_cmd() {
    local rate="$1"
    java \
      ${FIX_DEMO_JAVA_OPTS:-} \
      -Dllexsim.log.dir="${LOG_ROOT}" \
      -Dllexsim.log.name="fix-demo-client" \
      -Dfix.demo.logDir="${QFJ_LOG_DIR}" \
      -Dfix.demo.host="${FIX_CLIENT_HOST:-localhost}" \
      -Dfix.demo.port="${FIX_CLIENT_PORT:-9880}" \
      -Dfix.demo.beginString="${FIX_CLIENT_BEGIN_STRING:-FIX.4.2}" \
      -Dfix.demo.senderCompId="${FIX_CLIENT_SENDER_COMP_ID:-CLIENT1}" \
      -Dfix.demo.targetCompId="${FIX_CLIENT_TARGET_COMP_ID:-LLEXSIM}" \
      -Dfix.demo.defaultApplVerId="${FIX_CLIENT_DEFAULT_APPL_VER_ID:-FIX.5.0}" \
      -Dfix.demo.symbol="${FIX_CLIENT_SYMBOL:-AAPL}" \
      -Dfix.demo.side="${FIX_CLIENT_SIDE:-BUY}" \
      -Dfix.demo.orderQty="${FIX_CLIENT_ORDER_QTY:-100}" \
      -Dfix.demo.price="${FIX_CLIENT_PRICE:-100.25}" \
      -Dfix.demo.heartBtInt="${FIX_CLIENT_HEARTBTINT:-30}" \
      -Dfix.demo.reconnectIntervalSec="${FIX_CLIENT_RECONNECT_INTERVAL_SEC:-5}" \
      -cp "${JAR_PATH}" \
      "${MAIN_CLASS}" "${rate}"
}

exec_java_cmd() {
    local rate="$1"
    exec java \
      ${FIX_DEMO_JAVA_OPTS:-} \
      -Dllexsim.log.dir="${LOG_ROOT}" \
      -Dllexsim.log.name="fix-demo-client" \
      -Dfix.demo.logDir="${QFJ_LOG_DIR}" \
      -Dfix.demo.host="${FIX_CLIENT_HOST:-localhost}" \
      -Dfix.demo.port="${FIX_CLIENT_PORT:-9880}" \
      -Dfix.demo.beginString="${FIX_CLIENT_BEGIN_STRING:-FIX.4.2}" \
      -Dfix.demo.senderCompId="${FIX_CLIENT_SENDER_COMP_ID:-CLIENT1}" \
      -Dfix.demo.targetCompId="${FIX_CLIENT_TARGET_COMP_ID:-LLEXSIM}" \
      -Dfix.demo.defaultApplVerId="${FIX_CLIENT_DEFAULT_APPL_VER_ID:-FIX.5.0}" \
      -Dfix.demo.symbol="${FIX_CLIENT_SYMBOL:-AAPL}" \
      -Dfix.demo.side="${FIX_CLIENT_SIDE:-BUY}" \
      -Dfix.demo.orderQty="${FIX_CLIENT_ORDER_QTY:-100}" \
      -Dfix.demo.price="${FIX_CLIENT_PRICE:-100.25}" \
      -Dfix.demo.heartBtInt="${FIX_CLIENT_HEARTBTINT:-30}" \
      -Dfix.demo.reconnectIntervalSec="${FIX_CLIENT_RECONNECT_INTERVAL_SEC:-5}" \
      -cp "${JAR_PATH}" \
      "${MAIN_CLASS}" "${rate}"
}

cmd_start() {
    local rate
    rate="$(rate_arg "${1:-}")"

    banner "Starting"
    require_java
    ensure_dirs
    ensure_jar

    if client_running; then
        warn "Demo client is already running (pid=$(client_pid))."
        cmd_status
        return 0
    fi

    info "Starting demo client in background at ${rate} NewOrderSingles/sec..."
    nohup "${PROJECT_ROOT}/scripts/fix-demo-client.sh" run "${rate}" >"${CONSOLE_LOG}" 2>&1 &
    echo $! >"${PID_FILE}"
    sleep 1

    if client_running; then
        success "Demo client started (pid=$(client_pid))."
        echo -e "  ${BOLD}Rate${RESET}        → ${rate} msg/s"
        echo -e "  ${BOLD}Console log${RESET} → ${CONSOLE_LOG}"
        echo -e "  ${BOLD}FIX host${RESET}    → ${FIX_CLIENT_HOST:-localhost}:${FIX_CLIENT_PORT:-9880}"
    else
        error "Demo client failed to start. Check: ${CONSOLE_LOG}"
        rm -f "${PID_FILE}"
        exit 1
    fi
}

cmd_run() {
    local rate
    rate="$(rate_arg "${1:-}")"

    banner "Foreground Run"
    require_java
    ensure_dirs
    ensure_jar

    info "Running demo client in foreground at ${rate} NewOrderSingles/sec..."
    exec_java_cmd "${rate}"
}

cmd_stop() {
    banner "Stopping"

    if ! client_running; then
        warn "Demo client is not running."
        rm -f "${PID_FILE}"
        return 0
    fi

    local pid
    pid="$(client_pid)"
    info "Sending SIGTERM to pid ${pid}..."
    kill "${pid}"

    for _ in {1..20}; do
        if ! kill -0 "${pid}" 2>/dev/null; then
            rm -f "${PID_FILE}"
            success "Demo client stopped."
            return 0
        fi
        sleep 0.5
    done

    warn "Client did not exit in time — sending SIGKILL."
    kill -9 "${pid}" 2>/dev/null || true
    rm -f "${PID_FILE}"
    success "Demo client force-stopped."
}

cmd_restart() {
    cmd_stop
    cmd_start "${1:-}"
}

cmd_status() {
    banner "Status"
    if client_running; then
        local pid
        pid="$(client_pid)"
        success "Demo client is running (pid=${pid})."
        ps -p "${pid}" -o pid=,etime=,command=
    else
        warn "Demo client is not running."
    fi
    echo ""
    echo -e "${BOLD}Console log:${RESET} ${CONSOLE_LOG}"
}

cmd_logs() {
    ensure_dirs
    if [[ ! -f "${CONSOLE_LOG}" ]]; then
        error "No console log found yet at ${CONSOLE_LOG}"
        exit 1
    fi
    info "Tailing ${CONSOLE_LOG} (Ctrl+C to exit)..."
    tail -n 100 -f "${CONSOLE_LOG}"
}

cmd_help() {
    cat << EOF

${BOLD}${CYAN}Demo FIX Client lifecycle helper${RESET}

${BOLD}Usage:${RESET}
  ./scripts/fix-demo-client.sh <command> [rate-per-second]

${BOLD}Commands:${RESET}
  ${GREEN}start [rate]${RESET}   Start in background (default rate: ${DEFAULT_RATE} msg/s)
  ${GREEN}run [rate]${RESET}     Run in foreground (default rate: ${DEFAULT_RATE} msg/s)
  ${GREEN}stop${RESET}           Stop the background client
  ${GREEN}restart [rate]${RESET} Restart the background client
  ${GREEN}status${RESET}         Show PID and log location
  ${GREEN}logs${RESET}           Tail the client console log
  ${GREEN}help${RESET}           Show this help

${BOLD}Environment Overrides:${RESET}
  FIX_CLIENT_HOST=localhost
  FIX_CLIENT_PORT=9880
  FIX_CLIENT_BEGIN_STRING=FIX.4.2
  FIX_CLIENT_SENDER_COMP_ID=CLIENT1
  FIX_CLIENT_TARGET_COMP_ID=LLEXSIM
  FIX_CLIENT_SYMBOL=AAPL
  FIX_CLIENT_SIDE=BUY
  FIX_CLIENT_ORDER_QTY=100
  FIX_CLIENT_PRICE=100.25
  FIX_DEMO_JAVA_OPTS="-Xms128m -Xmx256m"

${BOLD}Examples:${RESET}
  ./scripts/fix-demo-client.sh start
  ./scripts/fix-demo-client.sh start 250
  ./scripts/fix-demo-client.sh run 1000
  FIX_CLIENT_BEGIN_STRING=FIX.4.4 ./scripts/fix-demo-client.sh start 500
  ./scripts/fix-demo-client.sh stop

EOF
}

COMMAND="${1:-help}"
shift || true

case "${COMMAND}" in
    start)   cmd_start "$@"   ;;
    run)     cmd_run "$@"     ;;
    stop)    cmd_stop "$@"    ;;
    restart) cmd_restart "$@" ;;
    status)  cmd_status "$@"  ;;
    logs)    cmd_logs "$@"    ;;
    help|--help|-h) cmd_help   ;;
    *)
        error "Unknown command: '${COMMAND}'"
        cmd_help
        exit 1
        ;;
esac

