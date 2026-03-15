#!/usr/bin/env bash
# =============================================================================
# LLExSimulator — Disconnect Evidence Capture Helper
# =============================================================================
# Usage:
#   ./scripts/local_capture_disconnect_evidence.sh [output-dir]
#
# Environment overrides:
#   EVIDENCE_HOST=127.0.0.1
#   EVIDENCE_WEB_PORT=8080
#   EVIDENCE_LIMIT=10
#   EVIDENCE_LOG_LINES=200
# =============================================================================

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVIDENCE_HOST="${EVIDENCE_HOST:-127.0.0.1}"
EVIDENCE_WEB_PORT="${EVIDENCE_WEB_PORT:-8080}"
EVIDENCE_LIMIT="${EVIDENCE_LIMIT:-10}"
EVIDENCE_LOG_LINES="${EVIDENCE_LOG_LINES:-200}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
DEFAULT_BUNDLE_DIR="${PROJECT_ROOT}/logs/disconnect-evidence/${TIMESTAMP}"
BUNDLE_DIR="${1:-${DEFAULT_BUNDLE_DIR}}"
SIM_LOG="${PROJECT_ROOT}/logs/llexsimulator.log"
CLIENT_LOG_ROOT="${PROJECT_ROOT}/logs/fix-demo-client"
CLIENT_CONSOLE_LOG="${CLIENT_LOG_ROOT}/console.log"
CLIENT_MAIN_LOG="${CLIENT_LOG_ROOT}/fix-demo-client.log"
API_BASE="http://${EVIDENCE_HOST}:${EVIDENCE_WEB_PORT}"
SUMMARY_FILE="${BUNDLE_DIR}/README.txt"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }

usage() {
    cat <<EOF
${BOLD}${CYAN}LLExSimulator disconnect evidence capture helper${RESET}

${BOLD}Usage:${RESET}
  ./scripts/local_capture_disconnect_evidence.sh [output-dir]

${BOLD}Environment overrides:${RESET}
  EVIDENCE_HOST=127.0.0.1
  EVIDENCE_WEB_PORT=8080
  EVIDENCE_LIMIT=10
  EVIDENCE_LOG_LINES=200

${BOLD}Output:${RESET}
  Writes a timestamped bundle under:
    ${PROJECT_ROOT}/logs/disconnect-evidence/
  unless an explicit output directory is passed.
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

require_command() {
    local cmd="$1"
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        error "Required command not found in PATH: ${cmd}"
        exit 1
    fi
}

write_summary() {
    cat > "${SUMMARY_FILE}" <<EOF
LLExSimulator disconnect evidence bundle
Generated at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')
Project root: ${PROJECT_ROOT}
API base: ${API_BASE}
Recent disconnect limit: ${EVIDENCE_LIMIT}
Log tail lines: ${EVIDENCE_LOG_LINES}

Files:
  health.json
  sessions.json
  recent-disconnects.json
  simulator-log-tail.txt
  client-console-log-tail.txt
  client-main-log-tail.txt
  client-log-files.txt
  environment.txt
EOF
}

capture_api() {
    local path="$1"
    local output_file="$2"
    local url="${API_BASE}${path}"

    info "Capturing ${url}"
    if curl -fsS --max-time 5 "${url}" > "${output_file}" 2>"${output_file}.stderr"; then
        rm -f "${output_file}.stderr"
    else
        warn "API request failed: ${url}"
        {
            echo '{'
            echo '  "captureError": true,'
            printf '  "url": "%s",\n' "${url}"
            printf '  "stderrFile": "%s"\n' "$(basename "${output_file}.stderr")"
            echo '}'
        } > "${output_file}"
    fi
}

capture_tail() {
    local source_file="$1"
    local output_file="$2"

    if [[ -f "${source_file}" ]]; then
        info "Capturing tail of ${source_file}"
        tail -n "${EVIDENCE_LOG_LINES}" "${source_file}" > "${output_file}"
    else
        warn "Log file not found: ${source_file}"
        printf 'Missing log file: %s\n' "${source_file}" > "${output_file}"
    fi
}

capture_client_log_inventory() {
    local output_file="$1"
    if [[ -d "${CLIENT_LOG_ROOT}" ]]; then
        find "${CLIENT_LOG_ROOT}" -type f | sort > "${output_file}"
    else
        printf 'Missing client log directory: %s\n' "${CLIENT_LOG_ROOT}" > "${output_file}"
    fi
}

capture_environment() {
    local output_file="$1"
    {
        echo "timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
        echo "project_root=${PROJECT_ROOT}"
        echo "api_base=${API_BASE}"
        echo "evidence_limit=${EVIDENCE_LIMIT}"
        echo "evidence_log_lines=${EVIDENCE_LOG_LINES}"
        echo "shell=${SHELL:-unknown}"
        echo "uname=$(uname -a)"
        echo "pwd=$(pwd)"
        if command -v java >/dev/null 2>&1; then
            echo "java_version=$(java -version 2>&1 | head -n 1 || true)"
        else
            echo "java_version=java-not-found"
        fi
    } > "${output_file}"
}

require_command curl
require_command tail
require_command find

mkdir -p "${BUNDLE_DIR}"
write_summary
capture_environment "${BUNDLE_DIR}/environment.txt"

capture_api "/api/health" "${BUNDLE_DIR}/health.json"
capture_api "/api/sessions" "${BUNDLE_DIR}/sessions.json"
capture_api "/api/sessions/recent-disconnects?limit=${EVIDENCE_LIMIT}" "${BUNDLE_DIR}/recent-disconnects.json"

capture_tail "${SIM_LOG}" "${BUNDLE_DIR}/simulator-log-tail.txt"
capture_tail "${CLIENT_CONSOLE_LOG}" "${BUNDLE_DIR}/client-console-log-tail.txt"
capture_tail "${CLIENT_MAIN_LOG}" "${BUNDLE_DIR}/client-main-log-tail.txt"
capture_client_log_inventory "${BUNDLE_DIR}/client-log-files.txt"

success "Disconnect evidence captured in: ${BUNDLE_DIR}"
info "Send these files first: recent-disconnects.json, sessions.json, health.json, simulator-log-tail.txt, client-console-log-tail.txt"

