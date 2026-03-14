#!/usr/bin/env python3
import os
from textwrap import dedent


def shell_single_quote(value: str) -> str:
    return value.replace("'", "'\"'\"'")


replacements = {
    "__APP_DIR__": shell_single_quote(os.environ["APP_DIR"]),
    "__IMAGE_NAME__": shell_single_quote(os.environ["IMAGE_NAME"]),
    "__CONTAINER_NAME__": shell_single_quote(os.environ["CONTAINER_NAME"]),
    "__WEB_PORT__": shell_single_quote(os.environ["WEB_PORT"]),
    "__FIX_PORT__": shell_single_quote(os.environ["FIX_PORT"]),
    "__WAIT_SECONDS__": shell_single_quote(os.environ["WAIT_SECONDS"]),
    "__RELEASE_ID__": shell_single_quote(os.environ["RELEASE_ID"]),
    "__CPUSET_MODE__": shell_single_quote(os.environ["CPUSET_MODE"]),
    "__SOURCE_GIT_COMMIT__": shell_single_quote(os.environ["SOURCE_GIT_COMMIT"]),
    "__PUBLIC_WEB_PORT__": shell_single_quote(os.environ["PUBLIC_WEB_PORT"]),
    "__PUBLIC_FIX_PORT__": shell_single_quote(os.environ["PUBLIC_FIX_PORT"]),
}

template = dedent(r'''
set -euo pipefail

APP_DIR='__APP_DIR__'
IMAGE_NAME='__IMAGE_NAME__'
CONTAINER_NAME='__CONTAINER_NAME__'
WEB_PORT='__WEB_PORT__'
FIX_PORT='__FIX_PORT__'
WAIT_SECONDS='__WAIT_SECONDS__'
RELEASE_ID='__RELEASE_ID__'
CPUSET_MODE='__CPUSET_MODE__'
SOURCE_GIT_COMMIT='__SOURCE_GIT_COMMIT__'
PUBLIC_WEB_PORT='__PUBLIC_WEB_PORT__'
PUBLIC_FIX_PORT='__PUBLIC_FIX_PORT__'

log() {
    printf '[remote] %s\n' "$*"
}

wait_healthy() {
    local max_wait="${WAIT_SECONDS}"
    local waited=0

    while [[ "${waited}" -lt "${max_wait}" ]]; do
        local status
        status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${CONTAINER_NAME}" 2>/dev/null || echo missing)"
        case "${status}" in
            healthy)
                return 0
                ;;
            unhealthy|exited|dead)
                log "Container entered bad state: ${status}"
                docker logs --tail 200 "${CONTAINER_NAME}" || true
                return 1
                ;;
            *)
                sleep 3
                waited=$((waited + 3))
                ;;
        esac
    done

    log "Timed out waiting for container health after ${WAIT_SECONDS}s"
    docker ps -a --filter "name=^/${CONTAINER_NAME}$" || true
    docker logs --tail 200 "${CONTAINER_NAME}" || true
    return 1
}

mkdir -p "${APP_DIR}" "${APP_DIR}/config" "${APP_DIR}/logs" "${APP_DIR}/releases"
release_dir="${APP_DIR}/releases/${RELEASE_ID}"
mkdir -p "${release_dir}"

cpuset_line=''
case "${CPUSET_MODE}" in
    auto)
        cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo 1)"
        if [[ "${cpu_count}" =~ ^[0-9]+$ ]]; then
            if [[ "${cpu_count}" -ge 4 ]]; then
                cpuset_line='    cpuset: "0-3"'
            elif [[ "${cpu_count}" -ge 2 ]]; then
                cpuset_line="    cpuset: \"0-$((cpu_count - 1))\""
            fi
        fi
        ;;
    none|'')
        cpuset_line=''
        ;;
    *)
        cpuset_line="    cpuset: \"${CPUSET_MODE}\""
        ;;
esac

compose_file="${APP_DIR}/docker-compose.yml"
web_port_binding="127.0.0.1:${WEB_PORT}:8080"
fix_port_binding="127.0.0.1:${FIX_PORT}:9880"
if [[ "${PUBLIC_WEB_PORT}" == 'true' ]]; then
    web_port_binding="${WEB_PORT}:8080"
fi
if [[ "${PUBLIC_FIX_PORT}" == 'true' ]]; then
    fix_port_binding="${FIX_PORT}:9880"
fi

{
cat <<EOF_COMPOSE_HEAD
services:
  llexsimulator:
    image: ${IMAGE_NAME}
    container_name: ${CONTAINER_NAME}
    ports:
EOF_COMPOSE_HEAD
printf '      - "%s"\n' "${web_port_binding}"
printf '      - "%s"\n' "${fix_port_binding}"
cat <<EOF_COMPOSE_BODY
    volumes:
      - ${APP_DIR}/config:/app/config:ro
      - ${APP_DIR}/logs:/app/logs
    tmpfs:
      - /tmp/artio-state:size=64m,mode=1777
    environment:
      JAVA_OPTS: >-
        -XX:+UseZGC
        -XX:+ZGenerational
        -Xms1g -Xmx1g
        -XX:+AlwaysPreTouch
        -XX:+DisableExplicitGC
        -XX:+PerfDisableSharedMem
        -Daeron.dir=/dev/shm/aeron-llexsim
        -Daeron.ipc.term.buffer.length=8388608
        -Daeron.threading.mode=SHARED
        -Daeron.shared.idle.strategy=backoff
        -Dagrona.disable.bounds.checks=true
        --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
        --add-opens java.base/sun.nio.ch=ALL-UNNAMED
        --add-opens java.base/java.nio=ALL-UNNAMED
        --add-opens java.base/java.lang=ALL-UNNAMED
    shm_size: "512mb"
    ulimits:
      memlock:
        soft: -1
        hard: -1
      nofile:
        soft: 65536
        hard: 65536
EOF_COMPOSE_BODY
if [[ -n "${cpuset_line}" ]]; then
    printf '%s\n' "${cpuset_line}"
fi
cat <<EOF_COMPOSE_TAIL
    mem_limit: 2g
    mem_reservation: 1g
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
EOF_COMPOSE_TAIL
} > "${compose_file}"

cp "${compose_file}" "${release_dir}/docker-compose.yml"

image_id="$(docker image inspect --format '{{.Id}}' "${IMAGE_NAME}" 2>/dev/null || echo unknown)"
cat > "${release_dir}/release-manifest.txt" <<EOF_MANIFEST
release_id=${RELEASE_ID}
created_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source_git_commit=${SOURCE_GIT_COMMIT}
image_name=${IMAGE_NAME}
image_id=${image_id}
container_name=${CONTAINER_NAME}
web_port=${WEB_PORT}
fix_port=${FIX_PORT}
public_web_port=${PUBLIC_WEB_PORT}
public_fix_port=${PUBLIC_FIX_PORT}
app_dir=${APP_DIR}
cpuset=${CPUSET_MODE}
EOF_MANIFEST

log 'Starting/recreating remote service with docker compose...'
docker compose -f "${compose_file}" up -d --force-recreate --remove-orphans

log 'Waiting for remote container health...'
wait_healthy

log 'Health endpoint response:'
curl -fsS "http://localhost:${WEB_PORT}/api/health"
printf '\n'

log 'Deployed release metadata:'
printf '  - %s\n' "${release_dir}/release-manifest.txt" "${release_dir}/docker-compose.yml"
''').lstrip('\n')

for token, value in replacements.items():
    template = template.replace(token, value)

print(template, end='')
