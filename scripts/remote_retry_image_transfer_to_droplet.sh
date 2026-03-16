#!/usr/bin/env bash
# =============================================================================
# remote_retry_image_transfer_to_droplet.sh — Resume droplet deployment from image transfer onward
# =============================================================================
# Usage:
#   ./scripts/remote_retry_image_transfer_to_droplet.sh <host-or-ip> <ssh-key-path> <ssh-user> [options]
#
# What it does:
#   1. Reuses an already-built local Docker image (no Gradle build, no docker build)
#   2. Re-streams the image to the remote droplet via docker save/load
#   3. Rewrites the remote docker-compose.yml for the requested release settings
#   4. Restarts the simulator remotely and waits for it to become healthy
#
# Typical use case:
#   - Your previous ./scripts/remote_release_to_droplet.sh run already built the image
#   - The deployment failed during or after "Transferring Docker image"
#   - You want to retry the transfer/restart without rebuilding locally
# =============================================================================

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS_DIR="${PROJECT_ROOT}/scripts"
REMOTE_SCRIPT_RENDERER="${SCRIPTS_DIR}/remote_render_release_remote_script.py"
RUNTIME_PROFILE_DIR="${RUNTIME_PROFILE_DIR:-${PROJECT_ROOT}/build/runtime-profile/remote-retry}"
RUNTIME_PROFILE_HELPER="${SCRIPTS_DIR}/runtime_profile_common.sh"

DROPLET_HOST=""
DROPLET_USER=""
SSH_KEY_PATH=""
APP_DIR="${APP_DIR:-/opt/llexsimulator}"
IMAGE_NAME="${IMAGE_NAME:-llexsimulator:1.0-SNAPSHOT}"
CONTAINER_NAME="${CONTAINER_NAME:-llexsimulator}"
WEB_PORT="${WEB_PORT:-8080}"
FIX_PORT="${FIX_PORT:-9880}"
PUBLIC_WEB_PORT=false
PUBLIC_FIX_PORT=false
TARGET_PLATFORM="${TARGET_PLATFORM:-linux/amd64}"
WAIT_SECONDS="${WAIT_SECONDS:-120}"
CPUSET_MODE="${CPUSET_MODE:-auto}"
DRY_RUN=false
RELEASE_ID=""
GIT_COMMIT="unknown"
SSH_BIN="${SSH_BIN:-ssh}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
SIMULATOR_PROPERTIES_B64=""

# shellcheck source=./runtime_profile_common.sh
source "${RUNTIME_PROFILE_HELPER}"

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'
CYAN=$'\033[0;36m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${RESET}  $*"; }
error()   { echo -e "${RED}[ERROR]${RESET} $*" >&2; }
banner()  {
    echo -e "\n${BOLD}${CYAN}══════════════════════════════════════════${RESET}"
    echo -e "${BOLD}  $*${RESET}"
    echo -e "${BOLD}${CYAN}══════════════════════════════════════════${RESET}\n"
}

usage() {
    cat <<EOF
${BOLD}${CYAN}Retry droplet deployment starting from Docker image transfer${RESET}

${BOLD}Usage:${RESET}
  ./scripts/${SCRIPT_NAME} <host-or-ip> <ssh-key-path> <ssh-user> [options]

${BOLD}Positional arguments:${RESET}
  ${GREEN}host-or-ip${RESET}               Droplet hostname or IP address
  ${GREEN}ssh-key-path${RESET}            Path to the SSH private key used for login
  ${GREEN}ssh-user${RESET}                SSH user for the droplet login

${BOLD}Options:${RESET}
  ${GREEN}--app-dir <path>${RESET}          Remote app directory (default: ${APP_DIR})
  ${GREEN}--image <name:tag>${RESET}        Existing local Docker image tag to resend (default: ${IMAGE_NAME})
  ${GREEN}--container <name>${RESET}        Remote container name (default: ${CONTAINER_NAME})
  ${GREEN}--web-port <port>${RESET}         Host web/API port on the droplet (default: ${WEB_PORT})
  ${GREEN}--fix-port <port>${RESET}         Host FIX port on the droplet (default: ${FIX_PORT})
  ${GREEN}--public-web-port${RESET}         Bind the web/API port publicly instead of localhost-only
  ${GREEN}--public-fix-port${RESET}         Bind the FIX port publicly instead of localhost-only
  ${GREEN}--platform <os/arch>${RESET}      Expected platform of the already-built local image (default: ${TARGET_PLATFORM})
  ${GREEN}--wait-seconds <n>${RESET}        Health-check wait timeout after restart (default: ${WAIT_SECONDS})
  ${GREEN}--cpuset <auto|none|range>${RESET} CPU pinning for remote compose (default: ${CPUSET_MODE})
  ${GREEN}--release-id <id>${RESET}         Override the generated release identifier
  ${GREEN}--dry-run${RESET}                 Print the commands and remote script without executing them
  ${GREEN}help${RESET}                      Show this help

${BOLD}Examples:${RESET}
  ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root
  ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> ubuntu --image llexsimulator:1.0-SNAPSHOT
  ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --release-id retry-20260315
  ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --public-web-port

${BOLD}Notes:${RESET}
  - This script ${BOLD}does not rebuild${RESET} the fat JAR or Docker image.
  - It assumes the desired image already exists locally under ${IMAGE_NAME}.
  - It also skips config sync and helper-script sync; use ./scripts/remote_release_to_droplet.sh for a full deploy.
  - Best for recovering after a network interruption during ${BOLD}Transferring Docker image${RESET}.
EOF
}

require_option_value() {
    local option_name="$1"
    local remaining_args="$2"
    if [[ "${remaining_args}" -lt 2 ]]; then
        error "Missing value for ${option_name}"
        exit 1
    fi
}

parse_args() {
    if [[ $# -eq 0 ]]; then
        usage
        exit 1
    fi

    case "${1:-}" in
        help|--help|-h)
            usage
            exit 0
            ;;
    esac

    if [[ $# -lt 3 ]]; then
        error "Missing required arguments: <host-or-ip> <ssh-key-path> <ssh-user>"
        echo ""
        usage
        exit 1
    fi

    DROPLET_HOST="$1"
    SSH_KEY_PATH="$2"
    DROPLET_USER="$3"
    shift 3

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --app-dir)
                require_option_value "$1" "$#"
                APP_DIR="$2"
                shift 2
                ;;
            --image)
                require_option_value "$1" "$#"
                IMAGE_NAME="$2"
                shift 2
                ;;
            --container)
                require_option_value "$1" "$#"
                CONTAINER_NAME="$2"
                shift 2
                ;;
            --web-port)
                require_option_value "$1" "$#"
                WEB_PORT="$2"
                shift 2
                ;;
            --fix-port)
                require_option_value "$1" "$#"
                FIX_PORT="$2"
                shift 2
                ;;
            --public-web-port)
                PUBLIC_WEB_PORT=true
                shift
                ;;
            --public-fix-port)
                PUBLIC_FIX_PORT=true
                shift
                ;;
            --platform)
                require_option_value "$1" "$#"
                TARGET_PLATFORM="$2"
                shift 2
                ;;
            --wait-seconds)
                require_option_value "$1" "$#"
                WAIT_SECONDS="$2"
                shift 2
                ;;
            --cpuset)
                require_option_value "$1" "$#"
                CPUSET_MODE="$2"
                shift 2
                ;;
            --release-id)
                require_option_value "$1" "$#"
                RELEASE_ID="$2"
                shift 2
                ;;
            --dry-run)
                DRY_RUN=true
                shift
                ;;
            *)
                error "Unknown argument: $1"
                usage
                exit 1
                ;;
        esac
    done
}

require_local_binary() {
    local binary="$1"
    if ! command -v "${binary}" >/dev/null 2>&1; then
        error "Required local dependency is missing: ${binary}"
        exit 1
    fi
}

compute_release_id() {
    if [[ -n "${RELEASE_ID}" ]]; then
        return 0
    fi

    local timestamp
    timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

    if command -v git >/dev/null 2>&1 && git -C "${PROJECT_ROOT}" rev-parse --short HEAD >/dev/null 2>&1; then
        GIT_COMMIT="$(git -C "${PROJECT_ROOT}" rev-parse --short HEAD)"
        RELEASE_ID="${timestamp}-${GIT_COMMIT}"
    else
        RELEASE_ID="${timestamp}"
    fi
}

validate_inputs() {
    [[ -n "${DROPLET_HOST}" ]] || { error "Droplet host cannot be empty."; exit 1; }
    [[ -n "${DROPLET_USER}" ]] || { error "Droplet user cannot be empty."; exit 1; }
    [[ -n "${APP_DIR}" ]] || { error "Remote app directory cannot be empty."; exit 1; }
    [[ -n "${IMAGE_NAME}" ]] || { error "Image name cannot be empty."; exit 1; }
    [[ -n "${CONTAINER_NAME}" ]] || { error "Container name cannot be empty."; exit 1; }
    [[ "${WEB_PORT}" =~ ^[0-9]+$ ]] || { error "--web-port must be numeric."; exit 1; }
    [[ "${FIX_PORT}" =~ ^[0-9]+$ ]] || { error "--fix-port must be numeric."; exit 1; }
    [[ "${WAIT_SECONDS}" =~ ^[0-9]+$ ]] || { error "--wait-seconds must be numeric."; exit 1; }
    [[ -n "${RELEASE_ID}" ]] || { error "Release ID cannot be empty."; exit 1; }
    [[ "${TARGET_PLATFORM}" =~ ^[a-z0-9._-]+/[a-z0-9._-]+$ ]] || {
        error "--platform must look like os/arch (example: linux/amd64)."
        exit 1
    }

    case "${CPUSET_MODE}" in
        auto|none)
            ;;
        *)
            [[ "${CPUSET_MODE}" =~ ^[0-9,-]+$ ]] || {
                error "--cpuset must be one of: auto, none, or a range such as 0-3"
                exit 1
            }
            ;;
    esac

    if [[ "${DRY_RUN}" == true ]]; then
        if [[ ! -f "${SSH_KEY_PATH}" ]]; then
            warn "Dry-run mode: SSH key does not exist locally at ${SSH_KEY_PATH}; continuing anyway."
        fi
        return 0
    fi

    require_local_binary "${SSH_BIN}"
    require_local_binary "${DOCKER_BIN}"
    require_local_binary python3

    [[ -f "${SSH_KEY_PATH}" ]] || { error "SSH key not found: ${SSH_KEY_PATH}"; exit 1; }
    [[ -f "${REMOTE_SCRIPT_RENDERER}" ]] || { error "Remote script renderer is missing: ${REMOTE_SCRIPT_RENDERER}"; exit 1; }
}

ssh_target() {
    printf '%s@%s' "${DROPLET_USER}" "${DROPLET_HOST}"
}

ssh_options() {
    printf '%s\n' -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i "${SSH_KEY_PATH}"
}

print_command() {
    printf '  %q' "$@"
    echo
}

prepare_target_runtime_profile() {
    runtime_profile_load_env
    SIMULATOR_PROPERTIES_B64="$(base64 < "${RUNTIME_PROFILE_CONFIG_DIR}/simulator.properties" | tr -d '\n')"
}

run_ssh_command() {
    local remote_command="$1"
    local -a cmd=("${SSH_BIN}")
    local opt
    while IFS= read -r opt; do
        cmd+=("${opt}")
    done < <(ssh_options)
    cmd+=("$(ssh_target)" "${remote_command}")

    if [[ "${DRY_RUN}" == true ]]; then
        print_command "${cmd[@]}"
        return 0
    fi

    "${cmd[@]}"
}

run_ssh_script() {
    local -a cmd=("${SSH_BIN}")
    local opt
    while IFS= read -r opt; do
        cmd+=("${opt}")
    done < <(ssh_options)
    cmd+=("$(ssh_target)" bash -s --)

    if [[ "${DRY_RUN}" == true ]]; then
        print_command "${cmd[@]}"
        echo ""
        build_remote_deploy_script
        return 0
    fi

    build_remote_deploy_script | "${cmd[@]}"
}

prepare_remote_directories() {
    banner "Preparing remote directories"
    local remote_command
    remote_command=$(cat <<EOF
set -euo pipefail
APP_DIR='${APP_DIR}'
DEPLOY_USER='${DROPLET_USER}'

run_root() {
    if [[ "\$(id -u)" -eq 0 ]]; then
        "\$@"
    else
        sudo "\$@"
    fi
}

if ! id -u "\${DEPLOY_USER}" >/dev/null 2>&1; then
    echo "Remote deploy user does not exist: \${DEPLOY_USER}" >&2
    exit 1
fi

if [[ "\$(id -u)" -ne 0 ]]; then
    if ! command -v sudo >/dev/null 2>&1; then
        echo 'Preparing /opt application directories requires root or a passwordless sudo-capable user.' >&2
        exit 1
    fi
    if ! sudo -n true >/dev/null 2>&1; then
        echo 'Passwordless sudo is required to prepare the remote app directory when not logging in as root.' >&2
        exit 1
    fi
fi

DEPLOY_GROUP="\$(id -gn "\${DEPLOY_USER}")"
run_root install -d -m 0755 -o "\${DEPLOY_USER}" -g "\${DEPLOY_GROUP}" \
    "\${APP_DIR}" "\${APP_DIR}/config" "\${APP_DIR}/logs" "\${APP_DIR}/releases" "\${APP_DIR}/scripts"
run_root chown -R "\${DEPLOY_USER}:\${DEPLOY_GROUP}" "\${APP_DIR}"
EOF
)
    run_ssh_command "${remote_command}"
}

verify_remote_runtime() {
    banner "Remote preflight"
    info "Checking Docker, Docker Compose, and write access on $(ssh_target)..."

    local remote_command
    remote_command=$(cat <<EOF
set -euo pipefail
APP_DIR='${APP_DIR}'

if ! command -v docker >/dev/null 2>&1; then
    echo 'Docker is not installed on the droplet. Run ./scripts/remote_setup_droplet_for_docker.sh first.' >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is not accessible for user \$(id -un). Re-run the bootstrap script or reconnect with a user that has docker access." >&2
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    echo 'Docker Compose v2 is not available on the droplet. Run the bootstrap script again.' >&2
    exit 1
fi

for path in "\${APP_DIR}" "\${APP_DIR}/config" "\${APP_DIR}/logs" "\${APP_DIR}/releases" "\${APP_DIR}/scripts"; do
    if [[ ! -d "\${path}" ]]; then
        echo "Expected remote directory is missing: \${path}" >&2
        exit 1
    fi
    if [[ ! -w "\${path}" ]]; then
        echo "Remote directory is not writable by user \$(id -un): \${path}" >&2
        exit 1
    fi
done

echo "docker_access_user=\$(id -un)"
docker compose version
EOF
)
    run_ssh_command "${remote_command}"
}

ensure_local_image_present() {
    if [[ "${DRY_RUN}" == true ]]; then
        return 0
    fi

    if ! "${DOCKER_BIN}" image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
        error "Local image not found: ${IMAGE_NAME}"
        error "Run ./scripts/remote_release_to_droplet.sh first, or rebuild locally before retrying transfer."
        exit 1
    fi
}

verify_local_image_platform() {
    if [[ "${DRY_RUN}" == true ]]; then
        return 0
    fi

    local actual_platform
    actual_platform="$("${DOCKER_BIN}" image inspect "${IMAGE_NAME}" --format '{{.Os}}/{{.Architecture}}' 2>/dev/null | head -n 1)"

    if [[ -z "${actual_platform}" ]]; then
        error "Could not determine local image platform for ${IMAGE_NAME}"
        exit 1
    fi

    if [[ "${actual_platform}" != "${TARGET_PLATFORM}" ]]; then
        error "Local image platform mismatch: expected ${TARGET_PLATFORM}, got ${actual_platform}"
        error "This usually means the local image is not built for the droplet architecture."
        exit 1
    fi

    success "Local image platform verified: ${actual_platform}"
}

stream_image_to_remote() {
    banner "Transferring Docker image"
    info "Streaming ${IMAGE_NAME} to $(ssh_target) via docker save/load..."
    info "Expected image platform: ${TARGET_PLATFORM}"

    local -a ssh_cmd=("${SSH_BIN}")
    local opt
    while IFS= read -r opt; do
        ssh_cmd+=("${opt}")
    done < <(ssh_options)
    ssh_cmd+=("$(ssh_target)" "docker load")

    if [[ "${DRY_RUN}" == true ]]; then
        print_command "${DOCKER_BIN}" save "${IMAGE_NAME}"
        echo "  |"
        print_command "${ssh_cmd[@]}"
        return 0
    fi

    local image_size_bytes
    image_size_bytes="$("${DOCKER_BIN}" image inspect "${IMAGE_NAME}" --format '{{.Size}}' 2>/dev/null | head -n 1)"

    if command -v pv >/dev/null 2>&1; then
        if [[ -n "${image_size_bytes}" && "${image_size_bytes}" =~ ^[0-9]+$ ]]; then
            info "Showing transfer progress with pv (image size ≈ ${image_size_bytes} bytes)..."
            "${DOCKER_BIN}" save "${IMAGE_NAME}" | pv -pterab -s "${image_size_bytes}" | "${ssh_cmd[@]}"
        else
            info "Showing transfer progress with pv..."
            "${DOCKER_BIN}" save "${IMAGE_NAME}" | pv -pterab | "${ssh_cmd[@]}"
        fi
    else
        warn "'pv' is not installed locally; streaming without a progress bar. Install it with: brew install pv"
        "${DOCKER_BIN}" save "${IMAGE_NAME}" | "${ssh_cmd[@]}"
    fi

    success "Remote image load completed."
}

build_remote_deploy_script() {
    APP_DIR="${APP_DIR}" \
    IMAGE_NAME="${IMAGE_NAME}" \
    CONTAINER_NAME="${CONTAINER_NAME}" \
    WEB_PORT="${WEB_PORT}" \
    FIX_PORT="${FIX_PORT}" \
    WAIT_SECONDS="${WAIT_SECONDS}" \
    RELEASE_ID="${RELEASE_ID}" \
    CPUSET_MODE="${CPUSET_MODE}" \
    SOURCE_GIT_COMMIT="${GIT_COMMIT}" \
    PUBLIC_WEB_PORT="${PUBLIC_WEB_PORT}" \
    PUBLIC_FIX_PORT="${PUBLIC_FIX_PORT}" \
    TARGET_PLATFORM="${TARGET_PLATFORM}" \
    TARGET_PROFILE_NAME="${LLEX_TARGET_PROFILE_NAME}" \
    TARGET_CPU_COUNT="${LLEX_TARGET_CPU_COUNT}" \
    TARGET_RAM_GB="${LLEX_TARGET_RAM_GB}" \
    SIMULATOR_CPUS="${LLEX_CPUS}" \
    SIMULATOR_MEM_LIMIT="${LLEX_MEM_LIMIT}" \
    SIMULATOR_MEM_RESERVATION="${LLEX_MEM_RESERVATION}" \
    SIMULATOR_SHM_SIZE="${LLEX_SHM_SIZE}" \
    SIMULATOR_ARTIO_TMPFS_SIZE="${LLEX_ARTIO_TMPFS_SIZE}" \
    SIMULATOR_JAVA_XMS="${LLEX_JAVA_XMS}" \
    SIMULATOR_JAVA_XMX="${LLEX_JAVA_XMX}" \
    FIX_DEMO_JAVA_XMS="${FIX_DEMO_JAVA_XMS}" \
    FIX_DEMO_JAVA_XMX="${FIX_DEMO_JAVA_XMX}" \
    FIX_DEMO_MEM_LIMIT="${FIX_DEMO_MEM_LIMIT}" \
    FIX_DEMO_MEM_RESERVATION="${FIX_DEMO_MEM_RESERVATION}" \
    SIMULATOR_PROPERTIES_B64="${SIMULATOR_PROPERTIES_B64}" \
    python3 "${REMOTE_SCRIPT_RENDERER}"
}

deploy_remote_release() {
    banner "Remote deployment"
    info "Writing remote compose file and restarting the service on $(ssh_target)..."
    run_ssh_script
    success "Remote deployment completed."
}

main() {
    parse_args "$@"
    compute_release_id
    validate_inputs
    prepare_target_runtime_profile

    banner "Retry image transfer to droplet"
    info "Project root: ${PROJECT_ROOT}"
    info "Release ID: ${RELEASE_ID}"
    info "Target: $(ssh_target)"
    info "Image: ${IMAGE_NAME}"
    info "Target image platform: ${TARGET_PLATFORM}"
    info "Remote app dir: ${APP_DIR}"
    info "Target sizing profile: ${TARGET_DROPLET_CONFIG_FILE}"
    info "Remote ports: web=${WEB_PORT}, fix=${FIX_PORT}"
    if [[ "${PUBLIC_WEB_PORT}" == true ]]; then
        warn "Web/API port will be bound publicly on the droplet."
    else
        info "Web/API port will bind to localhost only for Nginx/HTTPS fronting."
    fi
    if [[ "${PUBLIC_FIX_PORT}" == true ]]; then
        warn "FIX port will be bound publicly on the droplet."
    else
        info "FIX port will bind to localhost only."
    fi
    info "CPU pinning mode: ${CPUSET_MODE}"
    info "No local rebuild will be performed."

    ensure_local_image_present
    verify_local_image_platform
    prepare_remote_directories
    verify_remote_runtime
    stream_image_to_remote
    deploy_remote_release

    success "Retry deployment finished successfully."
    echo ""
    echo "Endpoints:"
    if [[ "${PUBLIC_WEB_PORT}" == true ]]; then
        echo "  Web UI / API: http://${DROPLET_HOST}:${WEB_PORT}"
        echo "  Health:       http://${DROPLET_HOST}:${WEB_PORT}/api/health"
    else
        echo "  Web UI / API: local-only on droplet -> http://127.0.0.1:${WEB_PORT}"
        echo "  Health:       local-only on droplet -> http://127.0.0.1:${WEB_PORT}/api/health"
    fi
    if [[ "${PUBLIC_FIX_PORT}" == true ]]; then
        echo "  FIX:          tcp://${DROPLET_HOST}:${FIX_PORT}"
    else
        echo "  FIX:          local-only on droplet -> tcp://127.0.0.1:${FIX_PORT}"
    fi
    runtime_profile_show_summary
}

main "$@"

