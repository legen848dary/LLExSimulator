#!/bin/bash
# =============================================================================
# local_clean_ledgers.sh — Remove local FIX/Aeron ledger/state directories only
# =============================================================================
# Usage:
#   ./scripts/local_clean_ledgers.sh [--dry-run]
#
# Steps
#   1. local_stop_all.sh        — stop any background demo client and simulator
#   2. remove QuickFIX/J client ledger dirs (messages/store)
#   3. remove configured Artio log/state dir
#   4. remove configured Aeron dir and sibling archive dir
#
# This script is narrower than local_llexsim.sh clean: it preserves normal logs and
# build output, and only removes runtime ledger/state paths.
# =============================================================================

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS_DIR="${PROJECT_ROOT}/scripts"
STOP_ALL_SCRIPT="${SCRIPTS_DIR}/local_stop_all.sh"
BASH_BIN="/bin/bash"
LOCAL_CONFIG_PATH="${PROJECT_ROOT}/config/simulator.properties"
CLASSPATH_CONFIG_PATH="${PROJECT_ROOT}/src/main/resources/simulator.properties"
DEFAULT_FIX_LOG_DIR="logs/quickfixj"
DEFAULT_AERON_DIR="/tmp/aeron-llexsim"
DRY_RUN=false
ARTIO_PARENT_WOULD_BE_EMPTIED=false

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
banner()  { echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"; \
            echo -e "${BOLD}  $*${RESET}"; \
            echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}\n"; }

usage() {
    cat << EOF

${BOLD}${CYAN}Ledger cleanup helper${RESET}

${BOLD}Usage:${RESET}
  ./scripts/local_clean_ledgers.sh [--dry-run]

${BOLD}Behavior:${RESET}
  - Stops the background demo client and simulator first
  - Removes demo-client QuickFIX/J ledgers under logs/fix-demo-client/quickfixj/
  - Removes the configured Artio fix.log.dir
  - Removes parent-level Artio residue when fix.log.dir is under a dedicated artio-state root
  - Removes the configured Aeron directory and its sibling archive directory
  - Deletes only paths inside the workspace or /tmp

${BOLD}Options:${RESET}
  ${GREEN}--dry-run${RESET}   Show what would be removed without deleting anything
  ${GREEN}help${RESET}        Show this help

EOF
}

require_script() {
    local script_path="$1"
    if [[ ! -f "${script_path}" ]]; then
        error "Required script not found: ${script_path}"
        exit 1
    fi
}

resolve_property() {
    local key="$1"
    local default_value="$2"
    local source_file=""

    if [[ -f "${LOCAL_CONFIG_PATH}" ]]; then
        source_file="${LOCAL_CONFIG_PATH}"
    elif [[ -f "${CLASSPATH_CONFIG_PATH}" ]]; then
        source_file="${CLASSPATH_CONFIG_PATH}"
    fi

    if [[ -n "${source_file}" ]]; then
        local value
        value=$(awk -F'=' -v wanted_key="${key}" '
            $0 !~ /^[[:space:]]*#/ && index($0, "=") > 0 {
                raw_key=$1
                gsub(/^[[:space:]]+|[[:space:]]+$/, "", raw_key)
                if (raw_key == wanted_key) {
                    raw_value=substr($0, index($0, "=") + 1)
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", raw_value)
                    print raw_value
                }
            }
        ' "${source_file}" | tail -n 1)
        if [[ -n "${value}" ]]; then
            printf '%s\n' "${value}"
            return 0
        fi
    fi

    printf '%s\n' "${default_value}"
}

normalize_path() {
    local raw_path="$1"
    if [[ "${raw_path}" = /* ]]; then
        printf '%s\n' "${raw_path}"
    else
        printf '%s\n' "${PROJECT_ROOT}/${raw_path}"
    fi
}

is_safe_target() {
    local path="$1"

    [[ -n "${path}" ]] || return 1
    [[ "${path}" != "/" ]] || return 1
    [[ "${path}" != "/tmp" ]] || return 1
    [[ "${path}" != "${PROJECT_ROOT}" ]] || return 1

    case "${path}" in
        "${PROJECT_ROOT}"/*|/tmp/*)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

remove_target() {
    local target="$1"
    local label="$2"

    if ! is_safe_target "${target}"; then
        warn "Skipping unsafe ${label} path: ${target}"
        return 0
    fi

    if [[ ! -e "${target}" ]]; then
        info "${label} not present: ${target}"
        return 0
    fi

    if [[ "${DRY_RUN}" == true ]]; then
        info "[dry-run] Would remove ${label}: ${target}"
        return 0
    fi

    info "Removing ${label}: ${target}"
    rm -rf "${target}"
    success "Removed ${label}."
}

is_dedicated_artio_root() {
    local fix_log_dir="$1"
    local artio_parent_dir="$2"

    [[ "$(basename "${fix_log_dir}")" == "data" ]] || return 1
    [[ "$(basename "${artio_parent_dir}")" == "artio-state" ]] || return 1
    is_safe_target "${artio_parent_dir}"
}

remove_artio_parent_residue() {
    local fix_log_dir="$1"
    local artio_parent_dir="$2"

    if ! is_dedicated_artio_root "${fix_log_dir}" "${artio_parent_dir}"; then
        return 0
    fi

    if [[ ! -d "${artio_parent_dir}" ]]; then
        return 0
    fi

    local children=()
    local child
    shopt -s nullglob dotglob
    for child in "${artio_parent_dir}"/*; do
        [[ "${child}" == "${fix_log_dir}" ]] && continue
        children+=("${child}")
    done
    shopt -u nullglob dotglob

    if [[ ${#children[@]} -eq 0 ]]; then
        info "No Artio parent residue present: ${artio_parent_dir}"
        return 0
    fi

    if [[ "${DRY_RUN}" == true ]]; then
        info "[dry-run] Would remove remaining Artio root contents under: ${artio_parent_dir}"
        for child in "${children[@]}"; do
            info "[dry-run]   ${child}"
        done
        ARTIO_PARENT_WOULD_BE_EMPTIED=true
        return 0
    fi

    info "Removing remaining Artio root contents under: ${artio_parent_dir}"
    for child in "${children[@]}"; do
        rm -rf "${child}"
    done
    success "Removed Artio parent residue."
}

remove_if_empty() {
    local target="$1"
    local label="$2"

    if ! is_safe_target "${target}"; then
        return 0
    fi

    if [[ ! -d "${target}" ]]; then
        return 0
    fi

    if find "${target}" -mindepth 1 -print -quit 2>/dev/null | grep -q .; then
        info "Leaving non-empty ${label}: ${target}"
        return 0
    fi

    if [[ "${DRY_RUN}" == true ]]; then
        info "[dry-run] Would remove empty ${label}: ${target}"
        return 0
    fi

    rmdir "${target}" 2>/dev/null || true
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --dry-run)
                DRY_RUN=true
                ;;
            help|--help|-h)
                usage
                exit 0
                ;;
            *)
                error "Unknown argument: $1"
                usage
                exit 1
                ;;
        esac
        shift
    done
}

main() {
    parse_args "$@"
    require_script "${STOP_ALL_SCRIPT}"

    local quickfix_messages_dir="${PROJECT_ROOT}/logs/fix-demo-client/quickfixj/messages"
    local quickfix_store_dir="${PROJECT_ROOT}/logs/fix-demo-client/quickfixj/store"
    local fix_log_dir
    fix_log_dir="$(normalize_path "$(resolve_property "fix.log.dir" "${DEFAULT_FIX_LOG_DIR}")")"
    local artio_parent_dir
    artio_parent_dir="$(dirname "${fix_log_dir}")"
    local aeron_dir
    aeron_dir="$(normalize_path "$(resolve_property "aeron.dir" "${DEFAULT_AERON_DIR}")")"
    local aeron_archive_dir
    aeron_archive_dir="${aeron_dir}-archive"

    banner "Clean Ledgers"

    if [[ "${DRY_RUN}" == true ]]; then
        info "Dry-run mode enabled — nothing will be deleted."
    fi

    if [[ "${DRY_RUN}" == true ]]; then
        info "[dry-run] Would stop simulator and demo client before deleting ledger/state paths."
    else
        info "Stopping simulator and demo client before deleting ledger/state paths..."
        "${BASH_BIN}" "${STOP_ALL_SCRIPT}"
    fi
    echo ""

    remove_target "${quickfix_messages_dir}" "QuickFIX/J message ledger"
    remove_target "${quickfix_store_dir}" "QuickFIX/J session store"
    remove_target "${fix_log_dir}" "Artio FIX state directory"
    remove_artio_parent_residue "${fix_log_dir}" "${artio_parent_dir}"
    remove_target "${aeron_dir}" "Aeron directory"
    remove_target "${aeron_archive_dir}" "Aeron archive directory"

    if [[ "${DRY_RUN}" == true && "${ARTIO_PARENT_WOULD_BE_EMPTIED}" == true ]]; then
        info "[dry-run] Would remove empty Artio parent directory: ${artio_parent_dir}"
    else
        remove_if_empty "${artio_parent_dir}" "Artio parent directory"
    fi
    remove_if_empty "$(dirname "${quickfix_messages_dir}")" "QuickFIX/J ledger root"

    echo ""
    success "Ledger cleanup completed."
}

main "$@"

