#!/usr/bin/env bash
# =============================================================================
# remote_setup_https_for_hostname.sh — Configure Nginx + Certbot for a droplet hostname
# =============================================================================
# Usage:
#   ./scripts/remote_setup_https_for_hostname.sh <host-or-ip> <ssh-key-path> <ssh-user> --fqdn <hostname> [options]
#
# What it does on the remote Ubuntu droplet:
#   1. Installs Nginx, Certbot, and the Certbot Nginx plugin
#   2. Creates an Nginx reverse-proxy vhost for the given hostname
#   3. Proxies HTTPS traffic to the app running on localhost:<app-port>
#   4. Obtains and installs a Let's Encrypt certificate
#   5. Opens ports 80/443 in UFW and can optionally clean up an old direct app-port rule
#
# Required positional arguments:
#   1. Host / IP used for SSH
#   2. SSH private key path
#   3. SSH user
# =============================================================================

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DROPLET_HOST=""
SSH_KEY_PATH=""
DROPLET_USER=""
FQDN=""
CERTBOT_EMAIL=""
APP_PORT="${APP_PORT:-8080}"
CLOSE_DIRECT_WEB_PORT=false
STATUS_ONLY=false
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
${BOLD}${CYAN}Configure HTTPS hosting for LLExSimulator on an Ubuntu droplet${RESET}

${BOLD}Usage:${RESET}
  ./scripts/${SCRIPT_NAME} <host-or-ip> <ssh-key-path> <ssh-user> --fqdn <hostname> [options]

${BOLD}Positional arguments:${RESET}
  ${GREEN}host-or-ip${RESET}               Droplet hostname or IP address used for SSH
  ${GREEN}ssh-key-path${RESET}            Path to the SSH private key used for login
  ${GREEN}ssh-user${RESET}                SSH user for the droplet login

${BOLD}Required options:${RESET}
  ${GREEN}--fqdn <hostname>${RESET}         Public hostname to serve over HTTPS (example: sim.example.com)

${BOLD}Optional options:${RESET}
  ${GREEN}--email <address>${RESET}         Email for Let's Encrypt expiry notices (recommended)
  ${GREEN}--app-port <port>${RESET}         Local app port on the droplet (default: ${APP_PORT})
  ${GREEN}--close-direct-web-port${RESET}   Remove the direct UFW allow rule for the app port after Nginx is enabled
  ${GREEN}--status-only${RESET}             Read-only check: report backend/nginx/cert/UFW status without changing anything
  ${GREEN}--dry-run${RESET}                 Print the SSH command and remote script without executing them
  ${GREEN}help${RESET}                      Show this help

${BOLD}Examples:${RESET}
  ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --fqdn sim.example.com --email ops@example.com
  ./scripts/${SCRIPT_NAME} your-droplet.example.net ~/.ssh/<your-private-key> ubuntu --fqdn sim.example.com --app-port 8080
  ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --fqdn sim.example.com --status-only
  ./scripts/${SCRIPT_NAME} 203.0.113.10 ~/.ssh/<your-private-key> root --fqdn sim.example.com --dry-run

${BOLD}Important:${RESET}
  - Your DNS ${BOLD}A${RESET} record for the FQDN must already point to the droplet's public IP.
  - Use either direct ${BOLD}root${RESET} SSH access or a passwordless ${BOLD}sudo${RESET} user.
  - DNS maps the hostname to the droplet IP only.
  - Nginx on the droplet maps that hostname to ${BOLD}http://127.0.0.1:${APP_PORT}${RESET}.
  - The FIX port remains separate, local-only by default, and is not served over HTTPS.
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
            --fqdn)
                require_option_value "$1" "$#"
                FQDN="$2"
                shift 2
                ;;
            --email)
                require_option_value "$1" "$#"
                CERTBOT_EMAIL="$2"
                shift 2
                ;;
            --app-port)
                require_option_value "$1" "$#"
                APP_PORT="$2"
                shift 2
                ;;
            --close-direct-web-port)
                CLOSE_DIRECT_WEB_PORT=true
                shift
                ;;
            --status-only)
                STATUS_ONLY=true
                shift
                ;;
            --dry-run)
                DRY_RUN=true
                shift
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
    done
}

require_local_binary() {
    local binary="$1"
    if ! command -v "${binary}" >/dev/null 2>&1; then
        error "Required local dependency is missing: ${binary}"
        exit 1
    fi
}

is_ipv4_literal() {
    [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
}

resolve_ipv4_list() {
    local name="$1"

    if ! command -v python3 >/dev/null 2>&1; then
        return 0
    fi

    python3 - "$name" <<'PY'
import socket
import sys

name = sys.argv[1]
try:
    _, _, addrs = socket.gethostbyname_ex(name)
except OSError:
    sys.exit(0)
for addr in sorted(set(addrs)):
    print(addr)
PY
}

compare_dns_resolution() {
    local -a fqdn_ips=()
    local -a host_ips=()
    local fqdn_ip
    local host_ip

    if command -v python3 >/dev/null 2>&1; then
        while IFS= read -r fqdn_ip; do
            [[ -n "${fqdn_ip}" ]] && fqdn_ips+=("${fqdn_ip}")
        done < <(resolve_ipv4_list "${FQDN}")

        if is_ipv4_literal "${DROPLET_HOST}"; then
            host_ips+=("${DROPLET_HOST}")
        else
            while IFS= read -r host_ip; do
                [[ -n "${host_ip}" ]] && host_ips+=("${host_ip}")
            done < <(resolve_ipv4_list "${DROPLET_HOST}")
        fi
    fi

    if [[ ${#fqdn_ips[@]} -eq 0 ]]; then
        warn "Could not resolve ${FQDN} locally. Let's Encrypt will fail until DNS points to the droplet."
        return 0
    fi

    if [[ ${#host_ips[@]} -eq 0 ]]; then
        warn "Could not resolve SSH target ${DROPLET_HOST} locally to compare DNS mapping."
        return 0
    fi

    for fqdn_ip in "${fqdn_ips[@]}"; do
        for host_ip in "${host_ips[@]}"; do
            if [[ "${fqdn_ip}" == "${host_ip}" ]]; then
                success "DNS check: ${FQDN} resolves to ${fqdn_ip}, matching the droplet target."
                return 0
            fi
        done
    done

    warn "DNS check: ${FQDN} resolves to [${fqdn_ips[*]}], but the SSH target resolves to [${host_ips[*]}]."
    warn "Continue only if the FQDN really points at this droplet publicly."
}

validate_inputs() {
    [[ -n "${DROPLET_HOST}" ]] || { error "Droplet host cannot be empty."; exit 1; }
    [[ -n "${SSH_KEY_PATH}" ]] || { error "SSH key path cannot be empty."; exit 1; }
    [[ -n "${DROPLET_USER}" ]] || { error "Droplet user cannot be empty."; exit 1; }
    [[ -n "${FQDN}" ]] || { error "--fqdn is required."; exit 1; }
    [[ "${APP_PORT}" =~ ^[0-9]+$ ]] || { error "--app-port must be numeric."; exit 1; }

    require_local_binary ssh

    if [[ "${DRY_RUN}" == false && ! -f "${SSH_KEY_PATH}" ]]; then
        error "SSH key not found: ${SSH_KEY_PATH}"
        exit 1
    fi

    if [[ "${DRY_RUN}" == true && ! -f "${SSH_KEY_PATH}" ]]; then
        warn "Dry-run mode: SSH key does not exist locally at ${SSH_KEY_PATH}; continuing anyway."
    fi

    if [[ ! "${FQDN}" =~ ^[A-Za-z0-9.-]+$ ]] || [[ "${FQDN}" != *.* ]]; then
        error "--fqdn must look like a real hostname (example: sim.example.com)."
        exit 1
    fi

    if [[ -n "${CERTBOT_EMAIL}" && ! "${CERTBOT_EMAIL}" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
        error "--email must look like a valid email address."
        exit 1
    fi
}

ssh_target() {
    printf '%s@%s' "${DROPLET_USER}" "${DROPLET_HOST}"
}

build_remote_script() {
    cat <<EOF
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
FQDN='${FQDN}'
APP_PORT='${APP_PORT}'
CERTBOT_EMAIL='${CERTBOT_EMAIL}'
CLOSE_DIRECT_WEB_PORT='${CLOSE_DIRECT_WEB_PORT}'
STATUS_ONLY='${STATUS_ONLY}'
NGINX_SITE_NAME='llexsimulator-${FQDN}'
NGINX_SITE_PATH="/etc/nginx/sites-available/llexsimulator-${FQDN}"

log() {
    printf '[remote] %s\n' "\$*"
}

log_status() {
    local label="\$1"
    local value="\$2"
    printf '[remote] %-26s %s\n' "\${label}:" "\${value}"
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
        echo 'This HTTPS setup requires root or a passwordless sudo-capable user.' >&2
        exit 1
    fi
    if ! sudo -n true >/dev/null 2>&1; then
        echo 'Passwordless sudo is required for HTTPS setup when not logging in as root.' >&2
        exit 1
    fi
}

if [[ ! -r /etc/os-release ]]; then
    echo 'This machine does not appear to be Ubuntu/Linux with /etc/os-release.' >&2
    exit 1
fi

. /etc/os-release
if [[ "\${ID:-}" != 'ubuntu' ]]; then
    echo "This HTTPS bootstrap currently supports Ubuntu only (found: \${ID:-unknown})." >&2
    exit 1
fi

require_root_access

if [[ "\${STATUS_ONLY}" == 'true' ]]; then
    cert_exists='no'
    if [[ -s "/etc/letsencrypt/live/\${FQDN}/fullchain.pem" && -s "/etc/letsencrypt/live/\${FQDN}/privkey.pem" ]]; then
        cert_exists='yes'
    fi

    nginx_installed='no'
    if command -v nginx >/dev/null 2>&1; then
        nginx_installed='yes'
    fi

    nginx_active='no'
    if run_root systemctl is-active --quiet nginx 2>/dev/null; then
        nginx_active='yes'
    fi

    nginx_config_ok='no'
    if command -v nginx >/dev/null 2>&1 && run_root nginx -t >/tmp/llex-nginx-status.out 2>&1; then
        nginx_config_ok='yes'
    fi

    backend_health='unreachable'
    if curl -fsS "http://127.0.0.1:\${APP_PORT}/api/health" >/tmp/llex-backend-health.json 2>/dev/null; then
        backend_health='healthy'
    fi

    site_file='missing'
    [[ -f "\${NGINX_SITE_PATH}" ]] && site_file='present'

    site_enabled='missing'
    if [[ -L "/etc/nginx/sites-enabled/\${NGINX_SITE_NAME}" ]]; then
        site_enabled='enabled'
    fi

    ufw_enabled='unknown'
    if command -v ufw >/dev/null 2>&1; then
        if run_root ufw status | grep -q '^Status: active'; then
            ufw_enabled='active'
        else
            ufw_enabled='inactive'
        fi
    fi

    allow_80='unknown'
    allow_443='unknown'
    if command -v ufw >/dev/null 2>&1; then
        ufw_status="\$(run_root ufw status 2>/dev/null || true)"
        if printf '%s\n' "\${ufw_status}" | grep -Eq '(^|[[:space:]])80(/tcp)?[[:space:]].*ALLOW|Nginx Full[[:space:]].*ALLOW'; then
            allow_80='yes'
        else
            allow_80='no'
        fi
        if printf '%s\n' "\${ufw_status}" | grep -Eq '(^|[[:space:]])443(/tcp)?[[:space:]].*ALLOW|Nginx Full[[:space:]].*ALLOW'; then
            allow_443='yes'
        else
            allow_443='no'
        fi
    fi

    log 'HTTPS status check'
    log_status 'FQDN' "\${FQDN}"
    log_status 'Backend on localhost' "\${backend_health}"
    log_status 'Nginx installed' "\${nginx_installed}"
    log_status 'Nginx active' "\${nginx_active}"
    log_status 'Nginx config test' "\${nginx_config_ok}"
    log_status 'Managed site file' "\${site_file} (\${NGINX_SITE_PATH})"
    log_status 'Managed site enabled' "\${site_enabled} (/etc/nginx/sites-enabled/\${NGINX_SITE_NAME})"
    log_status 'Certificate present' "\${cert_exists} (/etc/letsencrypt/live/\${FQDN}/)"
    log_status 'UFW status' "\${ufw_enabled}"
    log_status 'Port 80 allowed' "\${allow_80}"
    log_status 'Port 443 allowed' "\${allow_443}"

    if [[ -f /tmp/llex-backend-health.json ]]; then
        log 'Backend health payload:'
        cat /tmp/llex-backend-health.json
        printf '\n'
        rm -f /tmp/llex-backend-health.json
    fi

    if [[ -f /tmp/llex-nginx-status.out ]]; then
        log 'nginx -t output:'
        cat /tmp/llex-nginx-status.out
        rm -f /tmp/llex-nginx-status.out
    fi

    exit 0
fi

run_root apt-get update
run_root apt-get install -y nginx certbot python3-certbot-nginx curl ufw
run_root systemctl enable --now nginx

if curl -fsS "http://127.0.0.1:\${APP_PORT}/api/health" >/dev/null 2>&1; then
    log "Backend health check succeeded on 127.0.0.1:\${APP_PORT}."
else
    log "Warning: backend did not answer on 127.0.0.1:\${APP_PORT}. Nginx/Certbot setup will continue."
fi

render_http_config() {
    cat <<'EOF_HTTP'
server {
    listen 80;
    listen [::]:80;
    server_name __FQDN__;

    client_max_body_size 16m;

    location / {
        proxy_pass http://127.0.0.1:__APP_PORT__;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
EOF_HTTP
}

render_https_config() {
    cat <<'EOF_HTTPS'
server {
    listen 80;
    listen [::]:80;
    server_name __FQDN__;

    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name __FQDN__;

    ssl_certificate /etc/letsencrypt/live/__FQDN__/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/__FQDN__/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    client_max_body_size 16m;

    location / {
        proxy_pass http://127.0.0.1:__APP_PORT__;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
EOF_HTTPS
}

render_config_to_file() {
    local mode="\$1"
    local target_file="\$2"

    if [[ "\${mode}" == 'http' ]]; then
        render_http_config
    else
        render_https_config
    fi | sed \
        -e "s|__FQDN__|\${FQDN}|g" \
        -e "s|__APP_PORT__|\${APP_PORT}|g" \
        > "\${target_file}"
}

install_managed_config() {
    local rendered_file="\$1"

    if [[ -f "\${NGINX_SITE_PATH}" ]] && cmp -s "\${rendered_file}" "\${NGINX_SITE_PATH}"; then
        log "Nginx site config already up to date: \${NGINX_SITE_PATH}"
        rm -f "\${rendered_file}"
        return 0
    fi

    run_root install -m 0644 "\${rendered_file}" "\${NGINX_SITE_PATH}"
    rm -f "\${rendered_file}"
    log "Updated Nginx site config: \${NGINX_SITE_PATH}"
}

ensure_nginx_site_enabled() {
    run_root ln -sfn "\${NGINX_SITE_PATH}" "/etc/nginx/sites-enabled/\${NGINX_SITE_NAME}"
    run_root rm -f /etc/nginx/sites-enabled/default
}

cert_exists=false
if [[ -s "/etc/letsencrypt/live/\${FQDN}/fullchain.pem" && -s "/etc/letsencrypt/live/\${FQDN}/privkey.pem" ]]; then
    cert_exists=true
fi

ssl_support_files_present=false
if [[ -s /etc/letsencrypt/options-ssl-nginx.conf && -s /etc/letsencrypt/ssl-dhparams.pem ]]; then
    ssl_support_files_present=true
fi

run_root ufw allow 'Nginx Full'

if [[ "\${CLOSE_DIRECT_WEB_PORT}" == 'true' ]]; then
    run_root ufw delete allow "\${APP_PORT}/tcp" >/dev/null 2>&1 || true
fi

if [[ "\${cert_exists}" != 'true' || "\${ssl_support_files_present}" != 'true' ]]; then
    log "No reusable TLS certificate/config found for \${FQDN}; preparing HTTP-only site for certificate issuance."

    http_config_tmp="\$(mktemp)"
    render_config_to_file http "\${http_config_tmp}"
    install_managed_config "\${http_config_tmp}"
    ensure_nginx_site_enabled
    run_root nginx -t
    run_root systemctl reload nginx

    if [[ -n "\${CERTBOT_EMAIL}" ]]; then
        run_root certbot certonly --nginx -d "\${FQDN}" --non-interactive --agree-tos --email "\${CERTBOT_EMAIL}"
    else
        run_root certbot certonly --nginx -d "\${FQDN}" --non-interactive --agree-tos --register-unsafely-without-email
    fi

    cert_exists=true
    ssl_support_files_present=true
else
    log "Existing TLS certificate found for \${FQDN}; reusing it."
fi

if [[ "\${cert_exists}" != 'true' ]]; then
    echo "Certificate issuance did not succeed for \${FQDN}." >&2
    exit 1
fi

https_config_tmp="\$(mktemp)"
render_config_to_file https "\${https_config_tmp}"
install_managed_config "\${https_config_tmp}"
ensure_nginx_site_enabled

run_root nginx -t
run_root systemctl reload nginx

log 'HTTPS setup complete.'
log 'Nginx vhost:'
printf '  - %s\n' "\${NGINX_SITE_PATH}" "/etc/nginx/sites-enabled/\${NGINX_SITE_NAME}"
log 'Certificate location:'
printf '  - %s\n' "/etc/letsencrypt/live/\${FQDN}/fullchain.pem" "/etc/letsencrypt/live/\${FQDN}/privkey.pem"
log 'Smoke test:'
curl -I "https://${FQDN}" || true
EOF
}

run_remote_setup() {
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
        if [[ "${STATUS_ONLY}" == true ]]; then
            info "Would check HTTPS/Nginx status on $(ssh_target) for ${FQDN} using key ${SSH_KEY_PATH}"
        else
            info "Would configure Nginx + Certbot on $(ssh_target) for ${FQDN} using key ${SSH_KEY_PATH}"
        fi
        echo ""
        echo "SSH command:"
        printf '  %q' "${ssh_cmd[@]}"
        echo ""
        echo ""
        echo "Remote HTTPS setup script:"
        echo "----------------------------------------"
        build_remote_script
        echo "----------------------------------------"
        return 0
    fi

    if [[ "${STATUS_ONLY}" == true ]]; then
        banner "Checking HTTPS status on $(ssh_target)"
        info "Inspecting backend, Nginx, certificate, and firewall state for ${FQDN}"
    else
        banner "Configuring HTTPS on $(ssh_target)"
        info "Installing Nginx/Certbot and configuring ${FQDN} -> http://127.0.0.1:${APP_PORT}"
    fi
    build_remote_script | "${ssh_cmd[@]}"
}

main() {
    parse_args "$@"
    validate_inputs

    banner "Hostname HTTPS Setup"
    info "Project root: ${PROJECT_ROOT}"
    info "Target: $(ssh_target)"
    info "FQDN: ${FQDN}"
    info "Backend target: http://127.0.0.1:${APP_PORT}"
    if [[ -n "${CERTBOT_EMAIL}" ]]; then
        info "Certbot email: ${CERTBOT_EMAIL}"
    else
        warn "No Certbot email provided; the script will register unsafely without email."
    fi
    if [[ "${STATUS_ONLY}" == true ]]; then
        info "Mode: status only (no remote changes will be made)"
    elif [[ "${CLOSE_DIRECT_WEB_PORT}" == true ]]; then
        info "Will remove any old direct UFW allow rule for ${APP_PORT}/tcp after enabling Nginx."
    else
        info "This script leaves any existing ${APP_PORT}/tcp UFW rule unchanged; with the current release script the web port is localhost-only anyway."
    fi

    compare_dns_resolution
    run_remote_setup
    success "Hostname HTTPS setup completed."
}

main "$@"

