#!/usr/bin/env bash
# =============================================================================
# remote_setup_droplet_for_docker.sh — Bootstrap a fresh Ubuntu droplet for Docker
# =============================================================================
# Usage:
#   ./scripts/remote_setup_droplet_for_docker.sh <host-or-ip> <ssh-key-path> <ssh-user> [options]
#
# What it does on the remote droplet:
#   1. Creates a persistent 2 GB swap file when the droplet has no active swap
#   2. Installs Docker Engine from Docker's official Ubuntu apt repository
#   3. Installs useful deployment helpers (git, rsync, jq, curl, ufw, fail2ban)
#   4. Enables Docker and containerd services
#   5. Creates the application directory structure used for deployments
#   6. Optionally enables a basic firewall with SSH only by default
#
# Required positional arguments:
#   1. Host / IP
#   2. SSH private key path
#   3. SSH user
# =============================================================================

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DROPLET_HOST=""
DROPLET_USER=""
SSH_KEY_PATH=""
APP_DIR="${APP_DIR:-/opt/llexsimulator}"
WEB_PORT="${WEB_PORT:-8080}"
FIX_PORT="${FIX_PORT:-9880}"
ENABLE_FIREWALL=true
ENABLE_FAIL2BAN=true
OPEN_WEB_PORT=false
OPEN_FIX_PORT=false
DRY_RUN=false

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
${BOLD}${CYAN}Bootstrap a DigitalOcean Ubuntu droplet for Docker deployments${RESET}

${BOLD}Usage:${RESET}
  ./scripts/${SCRIPT_NAME} <host-or-ip> <ssh-key-path> <ssh-user> [options]

${BOLD}Positional arguments:${RESET}
  ${GREEN}host-or-ip${RESET}               Droplet hostname or IP address
  ${GREEN}ssh-key-path${RESET}            Path to the SSH private key used for login
  ${GREEN}ssh-user${RESET}                SSH user for the droplet login

${BOLD}Options:${RESET}
  ${GREEN}--app-dir <path>${RESET}          Remote app directory (default: ${APP_DIR})
  ${GREEN}--web-port <port>${RESET}         Web/API port value for optional firewall opening (default: ${WEB_PORT})
  ${GREEN}--fix-port <port>${RESET}         FIX port value for optional firewall opening (default: ${FIX_PORT})
  ${GREEN}--open-web-port${RESET}           Open ${WEB_PORT}/tcp in UFW explicitly
  ${GREEN}--open-fix-port${RESET}           Open ${FIX_PORT}/tcp in UFW explicitly
  ${GREEN}--skip-firewall${RESET}           Do not configure UFW
  ${GREEN}--skip-fail2ban${RESET}           Do not install/enable fail2ban
  ${GREEN}--dry-run${RESET}                 Print the SSH command and remote provisioning script only
  ${GREEN}help${RESET}                      Show this help

${BOLD}Examples:${RESET}
          ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root
          ./scripts/${SCRIPT_NAME} your-droplet.example.com ~/.ssh/<your-private-key> ubuntu --dry-run
          ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> deploy --app-dir /opt/llexsimulator

${BOLD}Notes:${RESET}
  - The script supports either direct ${BOLD}root${RESET} SSH access or a passwordless ${BOLD}sudo${RESET} user.
  - Fresh droplets without swap receive a persistent 2 GB /swapfile automatically.
  - UFW opens SSH by default.
  - Web/FIX ports remain closed unless you pass ${GREEN}--open-web-port${RESET} and/or ${GREEN}--open-fix-port${RESET}.
  - The remote deployment directory layout created is:
      ${APP_DIR}/
      ${APP_DIR}/config/
      ${APP_DIR}/logs/
      ${APP_DIR}/releases/
      ${APP_DIR}/scripts/
EOF
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
                APP_DIR="$2"
                shift 2
                ;;
            --web-port)
                WEB_PORT="$2"
                shift 2
                ;;
            --fix-port)
                FIX_PORT="$2"
                shift 2
                ;;
            --open-web-port)
                OPEN_WEB_PORT=true
                shift
                ;;
            --open-fix-port)
                OPEN_FIX_PORT=true
                shift
                ;;
            --skip-firewall)
                ENABLE_FIREWALL=false
                shift
                ;;
            --skip-fail2ban)
                ENABLE_FAIL2BAN=false
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

validate_inputs() {
    [[ -n "${DROPLET_HOST}" ]] || { error "Droplet host cannot be empty."; exit 1; }
    [[ -n "${DROPLET_USER}" ]] || { error "Droplet user cannot be empty."; exit 1; }
    [[ -n "${APP_DIR}" ]] || { error "Remote app directory cannot be empty."; exit 1; }
    [[ "${WEB_PORT}" =~ ^[0-9]+$ ]] || { error "--web-port must be numeric."; exit 1; }
    [[ "${FIX_PORT}" =~ ^[0-9]+$ ]] || { error "--fix-port must be numeric."; exit 1; }

    require_local_binary ssh

    if [[ "${DRY_RUN}" == false && ! -f "${SSH_KEY_PATH}" ]]; then
        error "SSH key not found: ${SSH_KEY_PATH}"
        exit 1
    fi

    if [[ "${DRY_RUN}" == true && ! -f "${SSH_KEY_PATH}" ]]; then
        warn "Dry-run mode: SSH key does not exist locally at ${SSH_KEY_PATH}; continuing anyway."
    fi
}

ssh_target() {
    printf '%s@%s' "${DROPLET_USER}" "${DROPLET_HOST}"
}

build_remote_script() {
    cat <<EOF
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
APP_DIR='${APP_DIR}'
WEB_PORT='${WEB_PORT}'
FIX_PORT='${FIX_PORT}'
SWAP_FILE='/swapfile'
SWAP_SIZE_MB='2048'
ENABLE_FIREWALL='${ENABLE_FIREWALL}'
ENABLE_FAIL2BAN='${ENABLE_FAIL2BAN}'
OPEN_WEB_PORT='${OPEN_WEB_PORT}'
OPEN_FIX_PORT='${OPEN_FIX_PORT}'
DEPLOY_USER='${DROPLET_USER}'

log() {
    printf '[remote] %s\n' "\$*"
}

run_root() {
    if [[ "\$(id -u)" -eq 0 ]]; then
        "\$@"
    else
        sudo "\$@"
    fi
}

require_root_access() {
    if [[ "\$(id -u)" -eq 0 ]]; then
        return 0
    fi
    if ! command -v sudo >/dev/null 2>&1; then
        echo 'This bootstrap requires root or a passwordless sudo-capable user.' >&2
        exit 1
    fi
    if ! sudo -n true >/dev/null 2>&1; then
        echo 'Passwordless sudo is required for remote bootstrap when not logging in as root.' >&2
        exit 1
    fi
}

ensure_swap() {
    if swapon --noheadings --show=NAME 2>/dev/null | grep -q '.'; then
        log 'Swap already active; leaving existing swap configuration unchanged.'
        swapon --show
        return 0
    fi

    log "No active swap detected; provisioning \${SWAP_SIZE_MB} MB at \${SWAP_FILE}."

    if [[ ! -f "\${SWAP_FILE}" ]]; then
        if command -v fallocate >/dev/null 2>&1; then
            if ! run_root fallocate -l "\${SWAP_SIZE_MB}M" "\${SWAP_FILE}"; then
                log 'fallocate failed; falling back to dd.'
                run_root dd if=/dev/zero of="\${SWAP_FILE}" bs=1M count="\${SWAP_SIZE_MB}" status=progress
            fi
        else
            run_root dd if=/dev/zero of="\${SWAP_FILE}" bs=1M count="\${SWAP_SIZE_MB}" status=progress
        fi
    else
        log "Swap file already exists at \${SWAP_FILE}; reusing it."
    fi

    run_root chmod 600 "\${SWAP_FILE}"
    run_root mkswap "\${SWAP_FILE}" >/dev/null
    run_root swapon "\${SWAP_FILE}"

    if ! awk '\$1 == "/swapfile" && \$3 == "swap" { found=1 } END { exit(found ? 0 : 1) }' /etc/fstab; then
        printf '%s\n' '/swapfile none swap sw 0 0' | run_root tee -a /etc/fstab >/dev/null
    fi

    log 'Swap enabled:'
    swapon --show
}

if [[ ! -r /etc/os-release ]]; then
    echo 'This machine does not appear to be Ubuntu/Linux with /etc/os-release.' >&2
    exit 1
fi

. /etc/os-release
if [[ "\${ID:-}" != 'ubuntu' ]]; then
    echo "This bootstrap script currently supports Ubuntu only (found: \${ID:-unknown})." >&2
    exit 1
fi

require_root_access

if ! id -u "\${DEPLOY_USER}" >/dev/null 2>&1; then
    echo "Requested deploy user does not exist on the droplet: \${DEPLOY_USER}" >&2
    exit 1
fi

DEPLOY_GROUP="\$(id -gn "\${DEPLOY_USER}")"

ensure_swap

run_root apt-get update
run_root apt-get install -y ca-certificates curl gnupg lsb-release jq git rsync unzip tar ufw python3

if [[ "\${ENABLE_FAIL2BAN}" == 'true' ]]; then
    run_root apt-get install -y fail2ban
fi

run_root install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | run_root tee /etc/apt/keyrings/docker.asc >/dev/null
run_root chmod a+r /etc/apt/keyrings/docker.asc

ARCH="\$(dpkg --print-architecture)"
CODENAME="\${VERSION_CODENAME:-}"
if [[ -z "\${CODENAME}" ]]; then
    echo 'Could not determine Ubuntu codename.' >&2
    exit 1
fi

printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' "\${ARCH}" "\${CODENAME}" \
    | run_root tee /etc/apt/sources.list.d/docker.list >/dev/null

run_root apt-get update
run_root apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

run_root systemctl enable --now containerd
run_root systemctl enable --now docker

if [[ "\${DEPLOY_USER}" != 'root' ]]; then
    run_root usermod -aG docker "\${DEPLOY_USER}"
fi

run_root install -d -m 0755 -o "\${DEPLOY_USER}" -g "\${DEPLOY_GROUP}" \
    "\${APP_DIR}" "\${APP_DIR}/config" "\${APP_DIR}/logs" "\${APP_DIR}/releases" "\${APP_DIR}/scripts"
run_root chown -R "\${DEPLOY_USER}:\${DEPLOY_GROUP}" "\${APP_DIR}"

if [[ "\${ENABLE_FIREWALL}" == 'true' ]]; then
    run_root ufw allow OpenSSH
    if [[ "\${OPEN_WEB_PORT}" == 'true' ]]; then
        run_root ufw allow "\${WEB_PORT}/tcp"
    fi
    if [[ "\${OPEN_FIX_PORT}" == 'true' ]]; then
        run_root ufw allow "\${FIX_PORT}/tcp"
    fi
    run_root ufw --force enable
fi

if [[ "\${ENABLE_FAIL2BAN}" == 'true' ]]; then
    run_root systemctl enable --now fail2ban
fi

log 'Docker version:'
docker --version
log 'Docker Compose version:'
docker compose version
log 'Deployment helpers installed:'
for cmd in git rsync jq curl ufw; do
    command -v "\${cmd}" >/dev/null 2>&1 && printf '  - %s -> %s\n' "\${cmd}" "\$(command -v "\${cmd}")"
done
log 'Application directory ready:'
printf '  - %s\n' "\${APP_DIR}" "\${APP_DIR}/config" "\${APP_DIR}/logs" "\${APP_DIR}/releases" "\${APP_DIR}/scripts"
log "Deployment user ready: \${DEPLOY_USER} (group: \${DEPLOY_GROUP})"
if [[ "\${DEPLOY_USER}" != 'root' ]]; then
    log "Docker group membership granted to \${DEPLOY_USER}; new SSH sessions can use docker without sudo."
fi
EOF
}

run_remote_bootstrap() {
    local -a ssh_cmd=(
        ssh
        -o BatchMode=yes
        -o StrictHostKeyChecking=accept-new
        -i "${SSH_KEY_PATH}"
        "$(ssh_target)"
        bash -s --
    )

    if [[ "${DRY_RUN}" == true ]]; then
        banner "Dry Run"
        info "Would execute provisioning against $(ssh_target) using key ${SSH_KEY_PATH}"
        echo ""
        echo "SSH command:"
        printf '  %q' "${ssh_cmd[@]}"
        echo ""
        echo ""
        echo "Remote provisioning script:"
        echo "----------------------------------------"
        build_remote_script
        echo "----------------------------------------"
        return 0
    fi

    banner "Provisioning droplet $(ssh_target)"
    info "Connecting to ${DROPLET_HOST} and installing Docker + deployment tools..."
    build_remote_script | "${ssh_cmd[@]}"
}

main() {
    parse_args "$@"
    validate_inputs

    banner "Droplet Docker Bootstrap"
    info "Project root: ${PROJECT_ROOT}"
    info "Target: $(ssh_target)"
    info "Remote app dir: ${APP_DIR}"
    info "Swap: create a persistent 2 GB /swapfile when the droplet has no active swap"
    if [[ "${ENABLE_FIREWALL}" == true ]]; then
        info "Firewall: enabled for SSH only by default"
        if [[ "${OPEN_WEB_PORT}" == true ]]; then
            info "Firewall extra allow: ${WEB_PORT}/tcp"
        else
            warn "Firewall: web port ${WEB_PORT}/tcp remains closed"
        fi
        if [[ "${OPEN_FIX_PORT}" == true ]]; then
            info "Firewall extra allow: ${FIX_PORT}/tcp"
        else
            warn "Firewall: FIX port ${FIX_PORT}/tcp remains closed"
        fi
    else
        warn "Firewall changes disabled."
    fi
    if [[ "${ENABLE_FAIL2BAN}" == true ]]; then
        info "Fail2ban: enabled"
    else
        warn "Fail2ban installation disabled."
    fi

    run_remote_bootstrap
    success "Droplet bootstrap completed."
}

main "$@"

