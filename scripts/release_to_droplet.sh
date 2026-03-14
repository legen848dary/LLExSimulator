#!/usr/bin/env bash
# =============================================================================
# release_to_droplet.sh — Build locally and deploy the Docker image to a droplet
# =============================================================================
# Usage:
#   ./scripts/release_to_droplet.sh <host-or-ip> <ssh-key-path> <ssh-user> [options]
#
# What it does:
#   1. Compiles the fat JAR locally and builds a droplet-targeted Docker image
#   2. Syncs runtime config to the remote droplet
#   3. Streams the Docker image to the droplet over SSH via docker save/load
#   4. Writes a deployment-specific docker-compose.yml on the droplet
#   5. Restarts the container remotely and waits for it to become healthy
#
# Required positional arguments:
#   1. Host / IP
#   2. SSH private key path
#   3. SSH user
# =============================================================================

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPTS_DIR="${PROJECT_ROOT}/scripts"
BUILD_SCRIPT="${SCRIPTS_DIR}/llexsim.sh"
REMOTE_SCRIPT_RENDERER="${SCRIPTS_DIR}/render_release_remote_script.py"
LOCAL_CONFIG_DIR="${PROJECT_ROOT}/config"
GRADLEW_BIN="${PROJECT_ROOT}/gradlew"

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
SYNC_CONFIG=true
SKIP_BUILD=false
BUILD_NO_CACHE=false
DRY_RUN=false
RELEASE_ID=""
GIT_COMMIT="unknown"
SSH_BIN="${SSH_BIN:-ssh}"
RSYNC_BIN="${RSYNC_BIN:-rsync}"
DOCKER_BIN="${DOCKER_BIN:-docker}"

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
${BOLD}${CYAN}Build locally and deploy LLExSimulator to a Docker-enabled droplet${RESET}

${BOLD}Usage:${RESET}
  ./scripts/${SCRIPT_NAME} <host-or-ip> <ssh-key-path> <ssh-user> [options]

${BOLD}Positional arguments:${RESET}
  ${GREEN}host-or-ip${RESET}               Droplet hostname or IP address
  ${GREEN}ssh-key-path${RESET}            Path to the SSH private key used for login
  ${GREEN}ssh-user${RESET}                SSH user for the droplet login

${BOLD}Options:${RESET}
  ${GREEN}--app-dir <path>${RESET}          Remote app directory (default: ${APP_DIR})
  ${GREEN}--image <name:tag>${RESET}        Docker image tag to deploy (default: ${IMAGE_NAME})
  ${GREEN}--container <name>${RESET}        Remote container name (default: ${CONTAINER_NAME})
  ${GREEN}--web-port <port>${RESET}         Host web/API port on the droplet (default: ${WEB_PORT})
  ${GREEN}--fix-port <port>${RESET}         Host FIX port on the droplet (default: ${FIX_PORT})
  ${GREEN}--public-web-port${RESET}         Bind the web/API port publicly instead of localhost-only
  ${GREEN}--public-fix-port${RESET}         Bind the FIX port publicly instead of localhost-only
  ${GREEN}--platform <os/arch>${RESET}      Target image platform for the droplet (default: ${TARGET_PLATFORM})
  ${GREEN}--wait-seconds <n>${RESET}        Health-check wait timeout (default: ${WAIT_SECONDS})
  ${GREEN}--cpuset <auto|none|range>${RESET} CPU pinning for remote compose (default: ${CPUSET_MODE})
  ${GREEN}--release-id <id>${RESET}         Override the generated release identifier
  ${GREEN}--no-build${RESET}                Skip the local rebuild and reuse the current local image
  ${GREEN}--no-cache${RESET}                Pass --no-cache to ./scripts/llexsim.sh build
  ${GREEN}--skip-config-sync${RESET}        Keep the droplet's existing ${APP_DIR}/config contents
  ${GREEN}--dry-run${RESET}                 Print the commands and remote script without executing them
  ${GREEN}help${RESET}                      Show this help

${BOLD}Examples:${RESET}
      ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root
      ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> deploy --no-cache
      ./scripts/${SCRIPT_NAME} your-droplet.example.com ~/.ssh/<your-private-key> ubuntu --no-build --skip-config-sync
      ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --platform linux/amd64
      ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --cpuset none --wait-seconds 180
      ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --public-fix-port
      ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --dry-run

${BOLD}Deployment flow:${RESET}
  1. Local fat-JAR build using ./gradlew shadowJar
  2. Local Docker image build using docker buildx for ${TARGET_PLATFORM}
  3. Optional config sync to ${APP_DIR}/config/
  4. docker save ${IMAGE_NAME} | ssh ${DROPLET_USER}@<host-or-ip> docker load
  5. Remote compose up using ${APP_DIR}/docker-compose.yml
  6. Wait for http://localhost:${WEB_PORT}/api/health on the droplet

${BOLD}Security defaults:${RESET}
  - Web/API binds to 127.0.0.1:${WEB_PORT} by default for Nginx/HTTPS fronting.
  - FIX binds to 127.0.0.1:${FIX_PORT} by default and is not internet-facing.
  - Demo FIX client is available as an on-demand Docker Compose service (`fix-demo-client`) and is not started by default.
  - CPU auto-pinning leaves one vCPU free for the host on multi-core droplets (for example, a 2 vCPU droplet pins the simulator to one core).
  - Droplet releases build a ${TARGET_PLATFORM} image by default so an Apple Silicon laptop can deploy safely to an amd64 Ubuntu host.
  - Use ${GREEN}--public-web-port${RESET} and/or ${GREEN}--public-fix-port${RESET} only if you explicitly want public exposure.
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
            --no-build)
                SKIP_BUILD=true
                shift
                ;;
            --no-cache)
                BUILD_NO_CACHE=true
                shift
                ;;
            --skip-config-sync)
                SYNC_CONFIG=false
                shift
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
    [[ "${TARGET_PLATFORM}" =~ ^[a-z0-9._-]+/[a-z0-9._-]+$ ]] || { error "--platform must look like os/arch (example: linux/amd64)."; exit 1; }

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
        if [[ "${SYNC_CONFIG}" == true && ! -d "${LOCAL_CONFIG_DIR}" ]]; then
            warn "Dry-run mode: local config directory does not exist at ${LOCAL_CONFIG_DIR}."
        fi
        return 0
    fi

    require_local_binary "${SSH_BIN}"
    require_local_binary "${RSYNC_BIN}"
    require_local_binary "${DOCKER_BIN}"
    require_local_binary python3
    require_local_binary java

    if ! "${DOCKER_BIN}" buildx version >/dev/null 2>&1; then
        error "Docker Buildx is required for droplet releases. Install/enable the Docker buildx plugin first."
        exit 1
    fi

    [[ -f "${SSH_KEY_PATH}" ]] || { error "SSH key not found: ${SSH_KEY_PATH}"; exit 1; }

    if [[ "${SKIP_BUILD}" == false ]]; then
        [[ -x "${GRADLEW_BIN}" ]] || { error "Gradle wrapper is missing or not executable: ${GRADLEW_BIN}"; exit 1; }
    fi

    [[ -f "${REMOTE_SCRIPT_RENDERER}" ]] || { error "Remote script renderer is missing: ${REMOTE_SCRIPT_RENDERER}"; exit 1; }

    if [[ "${SYNC_CONFIG}" == true && ! -d "${LOCAL_CONFIG_DIR}" ]]; then
        error "Local config directory not found: ${LOCAL_CONFIG_DIR}"
        exit 1
    fi
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

rsync_ssh_command() {
    printf 'ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i %q' "${SSH_KEY_PATH}"
}

sync_remote_config() {
    if [[ "${SYNC_CONFIG}" != true ]]; then
        warn "Skipping config sync; remote config under ${APP_DIR}/config will be preserved."
        return 0
    fi

    banner "Syncing remote config"
    info "Syncing ${LOCAL_CONFIG_DIR}/ to $(ssh_target):${APP_DIR}/config/"

    local rsync_shell
    rsync_shell="$(rsync_ssh_command)"
    local -a cmd=(
        "${RSYNC_BIN}"
        -az
        --delete
        -e "${rsync_shell}"
        "${LOCAL_CONFIG_DIR}/"
        "$(ssh_target):${APP_DIR}/config/"
    )

    if [[ "${DRY_RUN}" == true ]]; then
        print_command "${cmd[@]}"
        return 0
    fi

    "${cmd[@]}"
    success "Remote config sync completed."
}

build_local_image() {
    if [[ "${SKIP_BUILD}" == true ]]; then
        banner "Skipping local rebuild"
        info "Reusing existing local image ${IMAGE_NAME}"
        return 0
    fi

    banner "Building local image"
    info "Compiling fat JAR for release (./gradlew shadowJar -x test)..."

    if [[ "${DRY_RUN}" == true ]]; then
        print_command "${PROJECT_ROOT}/gradlew" --no-daemon shadowJar -x test
        if [[ "${BUILD_NO_CACHE}" == true ]]; then
            print_command "${DOCKER_BIN}" buildx build --platform "${TARGET_PLATFORM}" --load --no-cache -t "${IMAGE_NAME}" -f "${PROJECT_ROOT}/Dockerfile" "${PROJECT_ROOT}"
        else
            print_command "${DOCKER_BIN}" buildx build --platform "${TARGET_PLATFORM}" --load -t "${IMAGE_NAME}" -f "${PROJECT_ROOT}/Dockerfile" "${PROJECT_ROOT}"
        fi
        return 0
    fi

    (
        cd "${PROJECT_ROOT}"
        ./gradlew --no-daemon shadowJar -x test
    )

    info "Building droplet image for ${TARGET_PLATFORM} via docker buildx..."
    if [[ "${BUILD_NO_CACHE}" == true ]]; then
        "${DOCKER_BIN}" buildx build --platform "${TARGET_PLATFORM}" --load --no-cache -t "${IMAGE_NAME}" -f "${PROJECT_ROOT}/Dockerfile" "${PROJECT_ROOT}"
    else
        "${DOCKER_BIN}" buildx build --platform "${TARGET_PLATFORM}" --load -t "${IMAGE_NAME}" -f "${PROJECT_ROOT}/Dockerfile" "${PROJECT_ROOT}"
    fi
}

ensure_local_image_present() {
    if [[ "${DRY_RUN}" == true ]]; then
        return 0
    fi

    if ! "${DOCKER_BIN}" image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
        error "Local image not found: ${IMAGE_NAME}"
        if [[ "${SKIP_BUILD}" == true ]]; then
            error "Re-run without --no-build, or build the image manually first."
        fi
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
        error "This usually means the image was built for the wrong CPU architecture for the droplet."
        exit 1
    fi

    success "Local image platform verified: ${actual_platform}"
}

prepare_remote_directories() {
    banner "Preparing remote directories"
    local remote_command
    remote_command=$(cat <<EOF
mkdir -p '${APP_DIR}' '${APP_DIR}/config' '${APP_DIR}/logs' '${APP_DIR}/releases'
EOF
)
    run_ssh_command "${remote_command}"
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

    "${DOCKER_BIN}" save "${IMAGE_NAME}" | "${ssh_cmd[@]}"
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

    banner "Droplet release"
    info "Project root: ${PROJECT_ROOT}"
    info "Release ID: ${RELEASE_ID}"
    info "Target: $(ssh_target)"
    info "Image: ${IMAGE_NAME}"
    info "Target image platform: ${TARGET_PLATFORM}"
    info "Remote app dir: ${APP_DIR}"
    info "Remote ports: web=${WEB_PORT}, fix=${FIX_PORT}"
    if [[ "${PUBLIC_WEB_PORT}" == true ]]; then
        warn "Web/API port will be bound publicly on the droplet."
    else
        info "Web/API port will bind to localhost only for Nginx/HTTPS fronting."
    fi
    if [[ "${PUBLIC_FIX_PORT}" == true ]]; then
        warn "FIX port will be bound publicly on the droplet."
    else
        info "FIX port will bind to localhost only. Use SSH tunneling for local demo clients."
    fi
    info "CPU pinning mode: ${CPUSET_MODE}"
    info "Config sync: ${SYNC_CONFIG}"
    info "Skip local build: ${SKIP_BUILD}"

    build_local_image
    ensure_local_image_present
    verify_local_image_platform
    prepare_remote_directories
    sync_remote_config
    stream_image_to_remote
    deploy_remote_release

    success "Release finished successfully."
    echo ""
    echo "Endpoints:"
    if [[ "${PUBLIC_WEB_PORT}" == true ]]; then
        echo "  Web UI / API: http://${DROPLET_HOST}:${WEB_PORT}"
        echo "  Health:       http://${DROPLET_HOST}:${WEB_PORT}/api/health"
    else
        echo "  Web UI / API: local-only on droplet -> http://127.0.0.1:${WEB_PORT}"
        echo "  Health:       local-only on droplet -> http://127.0.0.1:${WEB_PORT}/api/health"
        echo "  Public Web:   expected via Nginx/HTTPS hostname on 80/443"
    fi
    if [[ "${PUBLIC_FIX_PORT}" == true ]]; then
        echo "  FIX:          tcp://${DROPLET_HOST}:${FIX_PORT}"
    else
        echo "  FIX:          local-only on droplet -> tcp://127.0.0.1:${FIX_PORT}"
        echo "  SSH tunnel:   ssh -N -L ${FIX_PORT}:127.0.0.1:${FIX_PORT} ${DROPLET_USER}@${DROPLET_HOST}"
    fi
    echo ""
    echo "Droplet demo client (on-demand Docker Compose service):"
    echo "  Start:        ssh -i ${SSH_KEY_PATH} ${DROPLET_USER}@${DROPLET_HOST} 'cd ${APP_DIR} && FIX_DEMO_RATE=100 docker compose --profile demo-client up -d fix-demo-client'"
    echo "  Logs:         ssh -i ${SSH_KEY_PATH} ${DROPLET_USER}@${DROPLET_HOST} 'cd ${APP_DIR} && docker compose logs -f fix-demo-client'"
    echo "  Stop:         ssh -i ${SSH_KEY_PATH} ${DROPLET_USER}@${DROPLET_HOST} 'cd ${APP_DIR} && docker compose stop fix-demo-client && docker compose rm -f fix-demo-client'"
}

main "$@"

