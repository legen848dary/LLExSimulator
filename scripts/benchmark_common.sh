#!/usr/bin/env bash
# =============================================================================
# Shared helpers for local/droplet benchmark runner scripts.
# =============================================================================

set -euo pipefail

BENCHMARK_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./fix_demo_client_common.sh
source "${BENCHMARK_SCRIPTS_DIR}/fix_demo_client_common.sh"

BENCHMARK_INVOCATION="${BENCHMARK_INVOCATION:-./scripts/run_benchmark_local.sh}"
BENCHMARK_CONFIG_FILE="${BENCHMARK_CONFIG_FILE:-${RUNTIME_PROFILE_CONFIG_DIR}/simulator.properties}"
BENCHMARK_RATE_DEFAULT="${BENCHMARK_RATE:-500}"
BENCHMARK_DURATION_DEFAULT="${BENCHMARK_DURATION_SEC:-30}"
BENCHMARK_WEB_PORT_DEFAULT="${WEB_PORT:-8080}"
BENCHMARK_WAIT_SECONDS="${BENCHMARK_WAIT_SECONDS:-90}"
BENCHMARK_SIMULATOR_CONTAINER="${BENCHMARK_SIMULATOR_CONTAINER:-llexsimulator}"
BENCHMARK_REPORT_RENDERER="${BENCHMARK_REPORT_RENDERER:-${BENCHMARK_SCRIPTS_DIR}/render_benchmark_report.py}"
BENCHMARK_ARTIFACTS_ROOT="${BENCHMARK_ARTIFACTS_ROOT:-${FIX_DEMO_ROOT}/logs/benchmark-reports}"
BENCHMARK_SIMULATOR_LOG_LINES="${BENCHMARK_SIMULATOR_LOG_LINES:-200}"
BENCHMARK_CLIENT_LOG_LINES="${BENCHMARK_CLIENT_LOG_LINES:-200}"

BENCHMARK_RATE="${BENCHMARK_RATE_DEFAULT}"
BENCHMARK_DURATION_SEC="${BENCHMARK_DURATION_DEFAULT}"
BENCHMARK_WEB_PORT="${BENCHMARK_WEB_PORT_DEFAULT}"

BENCHMARK_CONFIG_CHANGED=false
BENCHMARK_CONFIG_BACKUP_FILE=""
BENCHMARK_SIMULATOR_WAS_RUNNING=false
BENCHMARK_DEMO_CLIENT_STARTED=false
BENCHMARK_CLEANUP_DONE=false
BENCHMARK_RUN_ID=""
BENCHMARK_ARTIFACTS_DIR=""
BENCHMARK_REPORT_FILE=""

benchmark_usage() {
    cat <<EOF
${BOLD}${CYAN}Benchmark runner${RESET}

${BOLD}Usage:${RESET}
  ${BENCHMARK_INVOCATION} [rate-per-second] [duration-seconds]
  ${BENCHMARK_INVOCATION} --rate <msg/s> --duration <seconds> [--web-port <port>]

${BOLD}Defaults:${RESET}
  rate     = ${BENCHMARK_RATE_DEFAULT} msg/s
  duration = ${BENCHMARK_DURATION_DEFAULT} seconds
  web port = ${BENCHMARK_WEB_PORT_DEFAULT}

${BOLD}What it does:${RESET}
  1. Temporarily enables ${BOLD}benchmark.mode.enabled=true${RESET} in ${BENCHMARK_CONFIG_FILE}
  2. Starts/restarts the simulator only if needed
  3. Resets /api/statistics
  4. Runs the Docker demo FIX client at the requested rate
  5. Sleeps for the requested duration
  6. Captures health, stats, Docker usage, and log tails under ${BENCHMARK_ARTIFACTS_ROOT}/<timestamp>/
  7. Renders a colorful self-contained HTML report
  8. Restores the original benchmark-mode config and simulator state

${BOLD}Examples:${RESET}
  ${BENCHMARK_INVOCATION}
  ${BENCHMARK_INVOCATION} 1000 60
  ${BENCHMARK_INVOCATION} --rate 2000 --duration 45 --web-port 8080
EOF
}

parse_benchmark_args() {
    local positional=()

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --rate)
                [[ $# -ge 2 ]] || { error "Missing value for --rate"; exit 1; }
                BENCHMARK_RATE="$2"
                shift 2
                ;;
            --duration|--duration-sec)
                [[ $# -ge 2 ]] || { error "Missing value for $1"; exit 1; }
                BENCHMARK_DURATION_SEC="$2"
                shift 2
                ;;
            --web-port)
                [[ $# -ge 2 ]] || { error "Missing value for --web-port"; exit 1; }
                BENCHMARK_WEB_PORT="$2"
                shift 2
                ;;
            help|--help|-h)
                benchmark_usage
                exit 0
                ;;
            *)
                positional+=("$1")
                shift
                ;;
        esac
    done

    if [[ ${#positional[@]} -ge 1 ]]; then
        BENCHMARK_RATE="${positional[0]}"
    fi
    if [[ ${#positional[@]} -ge 2 ]]; then
        BENCHMARK_DURATION_SEC="${positional[1]}"
    fi
    if [[ ${#positional[@]} -gt 2 ]]; then
        error "Too many positional arguments."
        benchmark_usage
        exit 1
    fi

    validate_positive_integer "rate" "${BENCHMARK_RATE}"
    validate_positive_integer "duration" "${BENCHMARK_DURATION_SEC}"
    validate_positive_integer "web-port" "${BENCHMARK_WEB_PORT}"
}

validate_positive_integer() {
    local label="$1"
    local raw="$2"
    if ! [[ "${raw}" =~ ^[0-9]+$ ]] || [[ "${raw}" -le 0 ]]; then
        error "${label} must be a positive integer. Got: '${raw}'"
        exit 1
    fi
}

ensure_benchmark_dependencies() {
    runtime_profile_load_env
    BENCHMARK_CONFIG_FILE="${BENCHMARK_CONFIG_FILE:-${RUNTIME_PROFILE_CONFIG_DIR}/simulator.properties}"
    require_docker_compose
    ensure_compose_file

    if ! command -v curl >/dev/null 2>&1; then
        error "curl is required for benchmark scripts."
        exit 1
    fi
    if ! command -v python3 >/dev/null 2>&1; then
        error "python3 is required for benchmark scripts."
        exit 1
    fi
    if [[ ! -f "${BENCHMARK_CONFIG_FILE}" ]]; then
        error "Benchmark config file not found: ${BENCHMARK_CONFIG_FILE}"
        exit 1
    fi
    if [[ ! -f "${BENCHMARK_REPORT_RENDERER}" ]]; then
        error "Benchmark report renderer not found: ${BENCHMARK_REPORT_RENDERER}"
        exit 1
    fi
}

init_benchmark_artifacts() {
    BENCHMARK_RUN_ID="$(date +%Y%m%d-%H%M%S)"
    BENCHMARK_ARTIFACTS_DIR="${BENCHMARK_ARTIFACTS_ROOT}/${BENCHMARK_RUN_ID}"
    BENCHMARK_REPORT_FILE="${BENCHMARK_ARTIFACTS_DIR}/report.html"
    mkdir -p "${BENCHMARK_ARTIFACTS_DIR}"
}

current_benchmark_mode_value() {
    BENCHMARK_CONFIG_FILE="${BENCHMARK_CONFIG_FILE}" python3 - <<'PY'
import os
from pathlib import Path
path = Path(os.environ["BENCHMARK_CONFIG_FILE"])
value = ""
for raw in path.read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    if '=' not in line:
        continue
    key, val = line.split('=', 1)
    if key.strip() == 'benchmark.mode.enabled':
        value = val.strip().lower()
print(value)
PY
}

set_benchmark_mode_value() {
    local target_value="$1"
    BENCHMARK_CONFIG_FILE="${BENCHMARK_CONFIG_FILE}" BENCHMARK_CONFIG_TARGET_VALUE="${target_value}" python3 - <<'PY'
import os
from pathlib import Path
path = Path(os.environ["BENCHMARK_CONFIG_FILE"])
target_value = os.environ["BENCHMARK_CONFIG_TARGET_VALUE"]
lines = path.read_text().splitlines()
updated = []
found = False
for raw in lines:
    line = raw.strip()
    if not found and line and not line.startswith('#') and '=' in raw:
        key, _ = raw.split('=', 1)
        if key.strip() == 'benchmark.mode.enabled':
            updated.append(f'benchmark.mode.enabled={target_value}')
            found = True
            continue
    updated.append(raw)
if not found:
    if updated and updated[-1] != '':
        updated.append('')
    updated.append(f'benchmark.mode.enabled={target_value}')
path.write_text('\n'.join(updated) + '\n')
PY
}

simulator_container_exists() {
    docker inspect "${BENCHMARK_SIMULATOR_CONTAINER}" >/dev/null 2>&1
}

simulator_container_running() {
    [[ "$(docker inspect -f '{{.State.Running}}' "${BENCHMARK_SIMULATOR_CONTAINER}" 2>/dev/null || echo false)" == "true" ]]
}

restart_benchmark_simulator() {
    info "Restarting simulator with benchmark mode configuration..."
    compose_cmd up -d --force-recreate "${FIX_DEMO_SIMULATOR_SERVICE}" >/dev/null
}

stop_benchmark_simulator() {
    compose_cmd stop --timeout 15 "${FIX_DEMO_SIMULATOR_SERVICE}" >/dev/null 2>&1 || true
}

wait_for_benchmark_health() {
    local deadline=$((SECONDS + BENCHMARK_WAIT_SECONDS))
    local health_url="http://127.0.0.1:${BENCHMARK_WEB_PORT}/api/health"

    info "Waiting for simulator health at ${health_url}..."
    while (( SECONDS < deadline )); do
        if curl -fsS "${health_url}" >/dev/null 2>&1; then
            success "Simulator health endpoint is up."
            return 0
        fi
        sleep 2
    done

    error "Timed out waiting for ${health_url}"
    return 1
}

reset_benchmark_statistics() {
    curl -fsS -X POST "http://127.0.0.1:${BENCHMARK_WEB_PORT}/api/statistics/reset" >/dev/null
}

capture_benchmark_health() {
    curl -fsS "http://127.0.0.1:${BENCHMARK_WEB_PORT}/api/health" > "${BENCHMARK_ARTIFACTS_DIR}/health.json"
}

capture_benchmark_statistics() {
    curl -fsS "http://127.0.0.1:${BENCHMARK_WEB_PORT}/api/statistics" > "${BENCHMARK_ARTIFACTS_DIR}/statistics.json"
}

capture_benchmark_metadata() {
    cat > "${BENCHMARK_ARTIFACTS_DIR}/metadata.txt" <<EOF
label=${1}
rate=${BENCHMARK_RATE}
duration_sec=${BENCHMARK_DURATION_SEC}
web_port=${BENCHMARK_WEB_PORT}
config_file=${BENCHMARK_CONFIG_FILE}
compose_file=${FIX_DEMO_COMPOSE_FILE}
compose_override_file=${RUNTIME_PROFILE_COMPOSE_OVERRIDE_FILE}
simulator_container=${BENCHMARK_SIMULATOR_CONTAINER}
demo_client_container=${FIX_DEMO_CONTAINER_NAME}
simulator_was_running=${BENCHMARK_SIMULATOR_WAS_RUNNING}
benchmark_mode_changed=${BENCHMARK_CONFIG_CHANGED}
target_profile_name=${LLEX_TARGET_PROFILE_NAME:-unknown}
target_cpu_count=${LLEX_TARGET_CPU_COUNT:-unknown}
target_ram_gb=${LLEX_TARGET_RAM_GB:-unknown}
run_id=${BENCHMARK_RUN_ID}
started_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
}

capture_benchmark_resource_limits() {
    docker inspect "${BENCHMARK_SIMULATOR_CONTAINER}" --format '
container={{.Name}}
cpuset={{.HostConfig.CpusetCpus}}
nano_cpus={{.HostConfig.NanoCpus}}
memory_bytes={{.HostConfig.Memory}}
memory_reservation_bytes={{.HostConfig.MemoryReservation}}
shm_size_bytes={{.HostConfig.ShmSize}}
' | sed '/^$/d' > "${BENCHMARK_ARTIFACTS_DIR}/resource-limits.txt"
}

capture_benchmark_logs() {
    local simulator_log_file="${FIX_DEMO_ROOT}/logs/llexsimulator.log"
    local client_log_file="${FIX_DEMO_ROOT}/logs/fix-demo-client/fix-demo-client.log"

    if [[ -f "${simulator_log_file}" ]]; then
        tail -n "${BENCHMARK_SIMULATOR_LOG_LINES}" "${simulator_log_file}" > "${BENCHMARK_ARTIFACTS_DIR}/simulator-log-tail.txt"
    else
        printf 'Simulator log file not found: %s\n' "${simulator_log_file}" > "${BENCHMARK_ARTIFACTS_DIR}/simulator-log-tail.txt"
    fi

    if [[ -f "${client_log_file}" ]]; then
        tail -n "${BENCHMARK_CLIENT_LOG_LINES}" "${client_log_file}" > "${BENCHMARK_ARTIFACTS_DIR}/demo-client-log-tail.txt"
    else
        printf 'Demo client log file not found: %s\n' "${client_log_file}" > "${BENCHMARK_ARTIFACTS_DIR}/demo-client-log-tail.txt"
    fi

    compose_cmd logs --tail "${BENCHMARK_SIMULATOR_LOG_LINES}" "${FIX_DEMO_SIMULATOR_SERVICE}" > "${BENCHMARK_ARTIFACTS_DIR}/simulator-docker-logs.txt" 2>&1 || true
    compose_cmd logs --tail "${BENCHMARK_CLIENT_LOG_LINES}" "${FIX_DEMO_SERVICE_NAME}" > "${BENCHMARK_ARTIFACTS_DIR}/demo-client-docker-logs.txt" 2>&1 || true
    compose_cmd ps > "${BENCHMARK_ARTIFACTS_DIR}/docker-compose-ps.txt" 2>&1 || true
}

capture_benchmark_docker_stats() {
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}' \
        "${BENCHMARK_SIMULATOR_CONTAINER}" "${FIX_DEMO_CONTAINER_NAME}" > "${BENCHMARK_ARTIFACTS_DIR}/docker-stats.txt"
}

render_benchmark_report() {
    python3 "${BENCHMARK_REPORT_RENDERER}" "${BENCHMARK_ARTIFACTS_DIR}" "${BENCHMARK_REPORT_FILE}"
}

print_benchmark_statistics() {
    local stats_file="${BENCHMARK_ARTIFACTS_DIR}/statistics.json"

    echo -e "${BOLD}Final statistics JSON:${RESET}"
    cat "${stats_file}"
    echo ""

    echo -e "${BOLD}Summary:${RESET}"
    python3 - "${stats_file}" <<'PY'
import json
import sys
from pathlib import Path
stats = json.loads(Path(sys.argv[1]).read_text())
for key in [
    'ordersReceived',
    'execReportsSent',
    'throughputPerSec',
    'p80LatencyUs',
    'p90LatencyUs',
    'p99LatencyUs',
    'fillRatePct',
    'activeProfile',
]:
    print(f'  - {key}: {stats.get(key)}')
PY
}

print_benchmark_docker_stats() {
    echo ""
    if [[ -f "${BENCHMARK_ARTIFACTS_DIR}/resource-limits.txt" ]]; then
        echo -e "${BOLD}Configured container limits:${RESET}"
        cat "${BENCHMARK_ARTIFACTS_DIR}/resource-limits.txt"
        echo ""
    fi
    echo -e "${BOLD}Docker resource snapshot:${RESET}"
    cat "${BENCHMARK_ARTIFACTS_DIR}/docker-stats.txt"
}

prepare_benchmark_mode() {
    local current_mode
    current_mode="$(current_benchmark_mode_value)"
    if [[ "${current_mode}" == "true" ]]; then
        info "benchmark.mode.enabled is already true in ${BENCHMARK_CONFIG_FILE}"
        return 0
    fi

    BENCHMARK_CONFIG_BACKUP_FILE="$(mktemp /tmp/llex-simulator.properties.benchmark.XXXXXX)"
    cp "${BENCHMARK_CONFIG_FILE}" "${BENCHMARK_CONFIG_BACKUP_FILE}"
    set_benchmark_mode_value true
    BENCHMARK_CONFIG_CHANGED=true
    info "Temporarily enabled benchmark.mode.enabled=true in ${BENCHMARK_CONFIG_FILE}"
}

restore_benchmark_mode_if_needed() {
    if [[ "${BENCHMARK_CONFIG_CHANGED}" != true ]]; then
        return 0
    fi
    if [[ -n "${BENCHMARK_CONFIG_BACKUP_FILE}" && -f "${BENCHMARK_CONFIG_BACKUP_FILE}" ]]; then
        cp "${BENCHMARK_CONFIG_BACKUP_FILE}" "${BENCHMARK_CONFIG_FILE}"
        rm -f "${BENCHMARK_CONFIG_BACKUP_FILE}"
        BENCHMARK_CONFIG_BACKUP_FILE=""
        BENCHMARK_CONFIG_CHANGED=false
    fi
}

benchmark_cleanup() {
    local exit_code=$?
    if [[ "${BENCHMARK_CLEANUP_DONE}" == true ]]; then
        return "${exit_code}"
    fi
    BENCHMARK_CLEANUP_DONE=true

    set +e

    if [[ "${BENCHMARK_DEMO_CLIENT_STARTED}" == true ]]; then
        remove_fix_demo_client_quietly
        BENCHMARK_DEMO_CLIENT_STARTED=false
    fi

    if [[ "${BENCHMARK_SIMULATOR_WAS_RUNNING}" == true ]]; then
        if [[ "${BENCHMARK_CONFIG_CHANGED}" == true ]]; then
            restore_benchmark_mode_if_needed
            restart_benchmark_simulator >/dev/null 2>&1 || warn "Failed to restore simulator to its original config state."
        fi
    else
        restore_benchmark_mode_if_needed
        if simulator_container_running; then
            stop_benchmark_simulator
        fi
    fi

    return "${exit_code}"
}

run_benchmark_flow() {
    local label="$1"

    banner "${label}"
    ensure_benchmark_dependencies
    init_benchmark_artifacts
    trap benchmark_cleanup EXIT INT TERM

    BENCHMARK_SIMULATOR_WAS_RUNNING=false
    if simulator_container_running; then
        BENCHMARK_SIMULATOR_WAS_RUNNING=true
    fi

    prepare_benchmark_mode

    if [[ "${BENCHMARK_SIMULATOR_WAS_RUNNING}" == true ]]; then
        if [[ "${BENCHMARK_CONFIG_CHANGED}" == true ]]; then
            restart_benchmark_simulator
        else
            info "Simulator already running — reusing current process."
        fi
    else
        info "Simulator is not running — starting it now."
        restart_benchmark_simulator
    fi

    wait_for_benchmark_health
    capture_benchmark_metadata "${label}"
    capture_benchmark_health

    if fix_demo_container_exists; then
        warn "A demo client container already exists — replacing it for this benchmark run."
        remove_fix_demo_client_quietly
    fi

    info "Resetting simulator statistics..."
    reset_benchmark_statistics

    info "Running benchmark at ${BENCHMARK_RATE} msg/s for ${BENCHMARK_DURATION_SEC}s..."
    start_fix_demo_client "${BENCHMARK_RATE}"
    BENCHMARK_DEMO_CLIENT_STARTED=true
    sleep "${BENCHMARK_DURATION_SEC}"

    capture_benchmark_statistics
    capture_benchmark_resource_limits
    capture_benchmark_docker_stats
    capture_benchmark_logs
    render_benchmark_report

    print_benchmark_statistics
    print_benchmark_docker_stats
    echo ""
    echo -e "${BOLD}Artifacts:${RESET} ${BENCHMARK_ARTIFACTS_DIR}"
    echo -e "${BOLD}HTML report:${RESET} ${BENCHMARK_REPORT_FILE}"

    remove_fix_demo_client_quietly
    BENCHMARK_DEMO_CLIENT_STARTED=false
    success "Benchmark run completed."
}

