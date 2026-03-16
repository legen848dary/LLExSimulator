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
    "__TARGET_PLATFORM__": shell_single_quote(os.environ.get("TARGET_PLATFORM", "unknown")),
    "__TARGET_PROFILE_NAME__": shell_single_quote(os.environ.get("TARGET_PROFILE_NAME", "unknown")),
    "__TARGET_CPU_COUNT__": shell_single_quote(os.environ.get("TARGET_CPU_COUNT", "")),
    "__TARGET_RAM_GB__": shell_single_quote(os.environ.get("TARGET_RAM_GB", "")),
    "__SIMULATOR_CPUS__": shell_single_quote(os.environ["SIMULATOR_CPUS"]),
    "__SIMULATOR_MEM_LIMIT__": shell_single_quote(os.environ["SIMULATOR_MEM_LIMIT"]),
    "__SIMULATOR_MEM_RESERVATION__": shell_single_quote(os.environ["SIMULATOR_MEM_RESERVATION"]),
    "__SIMULATOR_SHM_SIZE__": shell_single_quote(os.environ["SIMULATOR_SHM_SIZE"]),
    "__SIMULATOR_ARTIO_TMPFS_SIZE__": shell_single_quote(os.environ["SIMULATOR_ARTIO_TMPFS_SIZE"]),
    "__SIMULATOR_JAVA_XMS__": shell_single_quote(os.environ["SIMULATOR_JAVA_XMS"]),
    "__SIMULATOR_JAVA_XMX__": shell_single_quote(os.environ["SIMULATOR_JAVA_XMX"]),
    "__FIX_DEMO_JAVA_XMS__": shell_single_quote(os.environ["FIX_DEMO_JAVA_XMS"]),
    "__FIX_DEMO_JAVA_XMX__": shell_single_quote(os.environ["FIX_DEMO_JAVA_XMX"]),
    "__FIX_DEMO_MEM_LIMIT__": shell_single_quote(os.environ["FIX_DEMO_MEM_LIMIT"]),
    "__FIX_DEMO_MEM_RESERVATION__": shell_single_quote(os.environ["FIX_DEMO_MEM_RESERVATION"]),
    "__SIMULATOR_PROPERTIES_B64__": shell_single_quote(os.environ["SIMULATOR_PROPERTIES_B64"]),
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
TARGET_PLATFORM='__TARGET_PLATFORM__'
TARGET_PROFILE_NAME='__TARGET_PROFILE_NAME__'
TARGET_CPU_COUNT='__TARGET_CPU_COUNT__'
TARGET_RAM_GB='__TARGET_RAM_GB__'
SIMULATOR_CPUS='__SIMULATOR_CPUS__'
SIMULATOR_MEM_LIMIT='__SIMULATOR_MEM_LIMIT__'
SIMULATOR_MEM_RESERVATION='__SIMULATOR_MEM_RESERVATION__'
SIMULATOR_SHM_SIZE='__SIMULATOR_SHM_SIZE__'
SIMULATOR_ARTIO_TMPFS_SIZE='__SIMULATOR_ARTIO_TMPFS_SIZE__'
SIMULATOR_JAVA_XMS='__SIMULATOR_JAVA_XMS__'
SIMULATOR_JAVA_XMX='__SIMULATOR_JAVA_XMX__'
FIX_DEMO_JAVA_XMS='__FIX_DEMO_JAVA_XMS__'
FIX_DEMO_JAVA_XMX='__FIX_DEMO_JAVA_XMX__'
FIX_DEMO_MEM_LIMIT='__FIX_DEMO_MEM_LIMIT__'
FIX_DEMO_MEM_RESERVATION='__FIX_DEMO_MEM_RESERVATION__'
SIMULATOR_PROPERTIES_B64='__SIMULATOR_PROPERTIES_B64__'

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
config_file="${APP_DIR}/config/simulator.properties"

printf '%s' "${SIMULATOR_PROPERTIES_B64}" | base64 -d > "${config_file}"

cpuset_line=''
case "${CPUSET_MODE}" in
    auto)
        cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo 1)"
        desired_cpu_count="${TARGET_CPU_COUNT}"
        if ! [[ "${desired_cpu_count}" =~ ^[0-9]+$ ]] || [[ "${desired_cpu_count}" -le 0 ]]; then
            desired_cpu_count="${cpu_count}"
        fi
        if [[ "${cpu_count}" =~ ^[0-9]+$ ]] && [[ "${cpu_count}" -gt 0 ]] && [[ "${desired_cpu_count}" -gt "${cpu_count}" ]]; then
            desired_cpu_count="${cpu_count}"
        fi
        if [[ "${desired_cpu_count}" =~ ^[0-9]+$ ]] && [[ "${desired_cpu_count}" -gt 1 ]]; then
            cpuset_line="    cpuset: \"0-$((desired_cpu_count - 1))\""
        else
            cpuset_line='    cpuset: "0"'
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
      - /tmp/artio-state:size=${SIMULATOR_ARTIO_TMPFS_SIZE},mode=1777
    environment:
      JAVA_OPTS: >-
        -XX:+UseZGC
        -XX:+ZGenerational
        -Xms${SIMULATOR_JAVA_XMS} -Xmx${SIMULATOR_JAVA_XMX}
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
    shm_size: "${SIMULATOR_SHM_SIZE}"
    ulimits:
      memlock:
        soft: -1
        hard: -1
      nofile:
        soft: 65536
        hard: 65536
EOF_COMPOSE_BODY
printf '    cpus: %s\n' "${SIMULATOR_CPUS}"
if [[ -n "${cpuset_line}" ]]; then
    printf '%s\n' "${cpuset_line}"
fi
cat <<EOF_COMPOSE_TAIL
    mem_limit: ${SIMULATOR_MEM_LIMIT}
    mem_reservation: ${SIMULATOR_MEM_RESERVATION}
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8080/api/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s

  fix-demo-client:
    image: ${IMAGE_NAME}
    container_name: llexsimulator-fix-demo-client
    profiles: ["demo-client"]
    depends_on:
      llexsimulator:
        condition: service_healthy
    entrypoint: ["java"]
    command:
      - "-Xms${FIX_DEMO_JAVA_XMS}"
      - "-Xmx${FIX_DEMO_JAVA_XMX}"
      - "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"
      - "-Dlog4j2.asyncLoggerRingBufferSize=262144"
      - "-Dllexsim.log.dir=/app/logs/fix-demo-client"
      - "-Dllexsim.log.name=fix-demo-client"
      - "-Dfix.demo.logDir=/app/logs/fix-demo-client/quickfixj"
      - "-Dfix.demo.host=\${FIX_CLIENT_HOST:-llexsimulator}"
      - "-Dfix.demo.port=\${FIX_CLIENT_PORT:-9880}"
      - "-Dfix.demo.beginString=\${FIX_CLIENT_BEGIN_STRING:-FIX.4.4}"
      - "-Dfix.demo.senderCompId=\${FIX_CLIENT_SENDER_COMP_ID:-CLIENT1}"
      - "-Dfix.demo.targetCompId=\${FIX_CLIENT_TARGET_COMP_ID:-LLEXSIM}"
      - "-Dfix.demo.defaultApplVerId=\${FIX_CLIENT_DEFAULT_APPL_VER_ID:-FIX.4.4}"
      - "-Dfix.demo.symbol=\${FIX_CLIENT_SYMBOL:-AAPL}"
      - "-Dfix.demo.side=\${FIX_CLIENT_SIDE:-BUY}"
      - "-Dfix.demo.orderQty=\${FIX_CLIENT_ORDER_QTY:-100}"
      - "-Dfix.demo.price=\${FIX_CLIENT_PRICE:-100.25}"
      - "-Dfix.demo.rawLoggingEnabled=\${FIX_CLIENT_RAW_LOGGING_ENABLED:-false}"
      - "-Dfix.demo.heartBtInt=\${FIX_CLIENT_HEARTBTINT:-30}"
      - "-Dfix.demo.reconnectIntervalSec=\${FIX_CLIENT_RECONNECT_INTERVAL_SEC:-5}"
      - "-cp"
      - "app.jar"
      - "com.llexsimulator.client.FixDemoClientMain"
      - "\${FIX_DEMO_RATE:-100}"
    volumes:
      - ${APP_DIR}/logs:/app/logs
    mem_limit: ${FIX_DEMO_MEM_LIMIT}
    mem_reservation: ${FIX_DEMO_MEM_RESERVATION}
    restart: "no"
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
target_platform=${TARGET_PLATFORM}
app_dir=${APP_DIR}
cpuset=${CPUSET_MODE}
target_profile_name=${TARGET_PROFILE_NAME}
target_cpu_count=${TARGET_CPU_COUNT}
target_ram_gb=${TARGET_RAM_GB}
simulator_cpus=${SIMULATOR_CPUS}
simulator_mem_limit=${SIMULATOR_MEM_LIMIT}
simulator_mem_reservation=${SIMULATOR_MEM_RESERVATION}
simulator_heap_xms=${SIMULATOR_JAVA_XMS}
simulator_heap_xmx=${SIMULATOR_JAVA_XMX}
fix_demo_heap_xms=${FIX_DEMO_JAVA_XMS}
fix_demo_heap_xmx=${FIX_DEMO_JAVA_XMX}
fix_demo_mem_limit=${FIX_DEMO_MEM_LIMIT}
fix_demo_mem_reservation=${FIX_DEMO_MEM_RESERVATION}
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
