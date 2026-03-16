#!/usr/bin/env python3
"""Render a runtime profile from config/target-droplet.properties.

Outputs:
  - docker-runtime.env         Docker Compose environment overrides
  - docker-compose.override.yml Concrete local Docker Compose resource overrides
  - config/simulator.properties tuned simulator config
  - profile-summary.txt        Human-readable derived values
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path


def parse_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in raw_line:
            continue
        key, value = raw_line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def update_properties_text(text: str, updates: dict[str, str]) -> str:
    remaining = dict(updates)
    output_lines: list[str] = []

    for raw_line in text.splitlines():
        stripped = raw_line.strip()
        if stripped and not stripped.startswith("#") and "=" in raw_line:
            key, _ = raw_line.split("=", 1)
            normalized_key = key.strip()
            if normalized_key in remaining:
                output_lines.append(f"{normalized_key}={remaining.pop(normalized_key)}")
                continue
        output_lines.append(raw_line)

    if remaining:
        if output_lines and output_lines[-1] != "":
            output_lines.append("")
        for key in sorted(remaining):
            output_lines.append(f"{key}={remaining[key]}")

    return "\n".join(output_lines) + "\n"


def require_positive_int(raw: str, label: str) -> int:
    try:
        value = int(raw)
    except ValueError as exc:
        raise SystemExit(f"{label} must be an integer. Got: {raw!r}") from exc
    if value <= 0:
        raise SystemExit(f"{label} must be > 0. Got: {raw!r}")
    return value


def require_positive_float(raw: str, label: str) -> float:
    try:
        value = float(raw)
    except ValueError as exc:
        raise SystemExit(f"{label} must be numeric. Got: {raw!r}") from exc
    if value <= 0.0:
        raise SystemExit(f"{label} must be > 0. Got: {raw!r}")
    return value


def round_down_to_multiple(value: int, multiple: int) -> int:
    return max(multiple, (value // multiple) * multiple)


def derive_profile(cpu_count: int, ram_gb: float) -> dict[str, str]:
    total_ram_mb = int(round(ram_gb * 1024))
    host_reserve_mb = min(1024, max(384, round_down_to_multiple(int(total_ram_mb * 0.20), 64)))

    if total_ram_mb <= 3072:
        client_mem_limit_mb = 256
        client_mem_reservation_mb = 128
        client_xms_mb = 128
        client_xmx_mb = 256
        shm_size_mb = 256
    elif total_ram_mb <= 6144:
        client_mem_limit_mb = 384
        client_mem_reservation_mb = 192
        client_xms_mb = 128
        client_xmx_mb = 256
        shm_size_mb = 512
    else:
        client_mem_limit_mb = 512
        client_mem_reservation_mb = 256
        client_xms_mb = 192
        client_xmx_mb = 384
        shm_size_mb = 512

    simulator_mem_limit_mb = total_ram_mb - host_reserve_mb - client_mem_limit_mb
    if simulator_mem_limit_mb < 768:
        raise SystemExit(
            "Target RAM is too small after reserving host and demo-client overhead. "
            f"Need at least ~1.5 GB total; got {ram_gb} GB"
        )

    if total_ram_mb <= 2304:
        heap_target_mb = 512
        ring_buffer_size = 65536
        order_pool_size = 65536
        profile_name = "compact"
    elif total_ram_mb <= 4608:
        heap_target_mb = 1024
        ring_buffer_size = 131072
        order_pool_size = 131072
        profile_name = "balanced"
    elif total_ram_mb <= 8192:
        heap_target_mb = 1536
        ring_buffer_size = 131072
        order_pool_size = 131072
        profile_name = "performance"
    else:
        heap_target_mb = 2048
        ring_buffer_size = 262144
        order_pool_size = 262144
        profile_name = "high-capacity"

    max_safe_heap_mb = round_down_to_multiple(simulator_mem_limit_mb - 384, 64)
    simulator_heap_mb = min(heap_target_mb, max_safe_heap_mb)
    simulator_heap_mb = max(384, simulator_heap_mb)

    simulator_mem_reservation_mb = min(
        simulator_mem_limit_mb - 128,
        max(simulator_heap_mb + 128, round_down_to_multiple(int(simulator_mem_limit_mb * 0.55), 64)),
    )

    return {
        "profile_name": profile_name,
        "target_cpu_count": str(cpu_count),
        "target_ram_gb": f"{ram_gb:g}",
        "total_ram_mb": str(total_ram_mb),
        "host_reserve_mb": str(host_reserve_mb),
        "llex_cpus": f"{cpu_count:.1f}",
        "llex_java_xms": f"{simulator_heap_mb}m",
        "llex_java_xmx": f"{simulator_heap_mb}m",
        "llex_mem_limit": f"{simulator_mem_limit_mb}m",
        "llex_mem_reservation": f"{simulator_mem_reservation_mb}m",
        "llex_shm_size": f"{shm_size_mb}m",
        "llex_artio_tmpfs_size": "64m",
        "fix_demo_java_xms": f"{client_xms_mb}m",
        "fix_demo_java_xmx": f"{client_xmx_mb}m",
        "fix_demo_mem_limit": f"{client_mem_limit_mb}m",
        "fix_demo_mem_reservation": f"{client_mem_reservation_mb}m",
        "sim_wait_strategy": "SLEEPING",
        "sim_ring_buffer_size": str(ring_buffer_size),
        "sim_order_pool_size": str(order_pool_size),
    }


def write_env_file(path: Path, profile: dict[str, str]) -> None:
    lines = [
        "# Generated by scripts/render_target_runtime_profile.py",
        f"LLEX_TARGET_PROFILE_NAME={profile['profile_name']}",
        f"LLEX_TARGET_CPU_COUNT={profile['target_cpu_count']}",
        f"LLEX_TARGET_RAM_GB={profile['target_ram_gb']}",
        f"LLEX_CPUS={profile['llex_cpus']}",
        f"LLEX_JAVA_XMS={profile['llex_java_xms']}",
        f"LLEX_JAVA_XMX={profile['llex_java_xmx']}",
        f"LLEX_MEM_LIMIT={profile['llex_mem_limit']}",
        f"LLEX_MEM_RESERVATION={profile['llex_mem_reservation']}",
        f"LLEX_SHM_SIZE={profile['llex_shm_size']}",
        f"LLEX_ARTIO_TMPFS_SIZE={profile['llex_artio_tmpfs_size']}",
        f"FIX_DEMO_JAVA_XMS={profile['fix_demo_java_xms']}",
        f"FIX_DEMO_JAVA_XMX={profile['fix_demo_java_xmx']}",
        f"FIX_DEMO_MEM_LIMIT={profile['fix_demo_mem_limit']}",
        f"FIX_DEMO_MEM_RESERVATION={profile['fix_demo_mem_reservation']}",
        f"SIM_WAIT_STRATEGY={profile['sim_wait_strategy']}",
        f"SIM_RING_BUFFER_SIZE={profile['sim_ring_buffer_size']}",
        f"SIM_ORDER_POOL_SIZE={profile['sim_order_pool_size']}",
        "",
    ]
    path.write_text("\n".join(lines))


def write_summary_file(path: Path, profile: dict[str, str], target_config_path: Path) -> None:
    summary = f"""LLExSimulator runtime profile
source_config={target_config_path}
profile_name={profile['profile_name']}
target_cpu_count={profile['target_cpu_count']}
target_ram_gb={profile['target_ram_gb']}
total_ram_mb={profile['total_ram_mb']}
host_reserve_mb={profile['host_reserve_mb']}
simulator_cpus={profile['llex_cpus']}
simulator_heap={profile['llex_java_xms']}
simulator_mem_limit={profile['llex_mem_limit']}
simulator_mem_reservation={profile['llex_mem_reservation']}
simulator_shm_size={profile['llex_shm_size']}
simulator_wait_strategy={profile['sim_wait_strategy']}
ring_buffer_size={profile['sim_ring_buffer_size']}
order_pool_size={profile['sim_order_pool_size']}
fix_demo_heap_xms={profile['fix_demo_java_xms']}
fix_demo_heap_xmx={profile['fix_demo_java_xmx']}
fix_demo_mem_limit={profile['fix_demo_mem_limit']}
fix_demo_mem_reservation={profile['fix_demo_mem_reservation']}
"""
    path.write_text(summary)


def write_compose_override_file(path: Path, profile: dict[str, str], generated_config_dir: Path, logs_dir: Path) -> None:
    lines = [
        "services:",
        "  llexsimulator:",
        "    volumes:",
        f"      - {generated_config_dir}:/app/config:ro",
        f"      - {logs_dir}:/app/logs",
        "    tmpfs:",
        f"      - /tmp/artio-state:size={profile['llex_artio_tmpfs_size']},mode=1777",
        "    environment:",
        "      JAVA_OPTS: >-",
        "        -XX:+UseZGC",
        "        -XX:+ZGenerational",
        f"        -Xms{profile['llex_java_xms']} -Xmx{profile['llex_java_xmx']}",
        "        -XX:+AlwaysPreTouch",
        "        -XX:+DisableExplicitGC",
        "        -XX:+PerfDisableSharedMem",
        "        -Daeron.dir=/dev/shm/aeron-llexsim",
        "        -Daeron.ipc.term.buffer.length=8388608",
        "        -Daeron.threading.mode=SHARED",
        "        -Daeron.shared.idle.strategy=backoff",
        "        -Dagrona.disable.bounds.checks=true",
        "        --add-exports java.base/jdk.internal.misc=ALL-UNNAMED",
        "        --add-opens java.base/sun.nio.ch=ALL-UNNAMED",
        "        --add-opens java.base/java.nio=ALL-UNNAMED",
        "        --add-opens java.base/java.lang=ALL-UNNAMED",
        f"    shm_size: \"{profile['llex_shm_size']}\"",
        f"    cpuset: \"0-{max(0, int(profile['target_cpu_count']) - 1)}\"",
        f"    cpus: {profile['llex_cpus']}",
        f"    mem_limit: \"{profile['llex_mem_limit']}\"",
        f"    mem_reservation: \"{profile['llex_mem_reservation']}\"",
        "",
        "  fix-demo-client:",
        "    command:",
        f"      - \"-Xms{profile['fix_demo_java_xms']}\"",
        f"      - \"-Xmx{profile['fix_demo_java_xmx']}\"",
        '      - "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"',
        '      - "-Dlog4j2.asyncLoggerRingBufferSize=262144"',
        '      - "-Dllexsim.log.dir=/app/logs/fix-demo-client"',
        '      - "-Dllexsim.log.name=fix-demo-client"',
        '      - "-Dfix.demo.logDir=/app/logs/fix-demo-client/quickfixj"',
        '      - "-Dfix.demo.host=${FIX_CLIENT_HOST:-llexsimulator}"',
        '      - "-Dfix.demo.port=${FIX_CLIENT_PORT:-9880}"',
        '      - "-Dfix.demo.beginString=${FIX_CLIENT_BEGIN_STRING:-FIX.4.4}"',
        '      - "-Dfix.demo.senderCompId=${FIX_CLIENT_SENDER_COMP_ID:-CLIENT1}"',
        '      - "-Dfix.demo.targetCompId=${FIX_CLIENT_TARGET_COMP_ID:-LLEXSIM}"',
        '      - "-Dfix.demo.defaultApplVerId=${FIX_CLIENT_DEFAULT_APPL_VER_ID:-FIX.4.4}"',
        '      - "-Dfix.demo.symbol=${FIX_CLIENT_SYMBOL:-AAPL}"',
        '      - "-Dfix.demo.side=${FIX_CLIENT_SIDE:-BUY}"',
        '      - "-Dfix.demo.orderQty=${FIX_CLIENT_ORDER_QTY:-100}"',
        '      - "-Dfix.demo.price=${FIX_CLIENT_PRICE:-100.25}"',
        '      - "-Dfix.demo.rawLoggingEnabled=${FIX_CLIENT_RAW_LOGGING_ENABLED:-false}"',
        '      - "-Dfix.demo.heartBtInt=${FIX_CLIENT_HEARTBTINT:-30}"',
        '      - "-Dfix.demo.reconnectIntervalSec=${FIX_CLIENT_RECONNECT_INTERVAL_SEC:-5}"',
        '      - "-cp"',
        '      - "app.jar"',
        '      - "com.llexsimulator.client.FixDemoClientMain"',
        '      - "${FIX_DEMO_RATE:-500}"',
        f"    mem_limit: \"{profile['fix_demo_mem_limit']}\"",
        f"    mem_reservation: \"{profile['fix_demo_mem_reservation']}\"",
        "    volumes:",
        f"      - {logs_dir}:/app/logs",
        "",
    ]
    path.write_text("\n".join(lines))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-config", required=True)
    parser.add_argument("--base-simulator-config", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()

    target_config_path = Path(args.target_config).resolve()
    base_simulator_config_path = Path(args.base_simulator_config).resolve()
    output_dir = Path(args.output_dir).resolve()

    if not target_config_path.is_file():
        raise SystemExit(f"Target config file not found: {target_config_path}")
    if not base_simulator_config_path.is_file():
        raise SystemExit(f"Base simulator config file not found: {base_simulator_config_path}")

    target_props = parse_properties(target_config_path)
    cpu_count = require_positive_int(target_props.get("target.cpu.count", ""), "target.cpu.count")
    ram_gb = require_positive_float(target_props.get("target.ram.gb", ""), "target.ram.gb")

    profile = derive_profile(cpu_count, ram_gb)

    config_dir = output_dir / "config"
    config_dir.mkdir(parents=True, exist_ok=True)

    env_path = output_dir / "docker-runtime.env"
    compose_override_path = output_dir / "docker-compose.override.yml"
    summary_path = output_dir / "profile-summary.txt"
    generated_simulator_config_path = config_dir / "simulator.properties"

    simulator_updates = {
        "wait.strategy": profile["sim_wait_strategy"],
        "ring.buffer.size": profile["sim_ring_buffer_size"],
        "order.pool.size": profile["sim_order_pool_size"],
    }

    base_text = base_simulator_config_path.read_text()
    generated_simulator_text = update_properties_text(base_text, simulator_updates)
    generated_simulator_config_path.write_text(generated_simulator_text)

    project_root = base_simulator_config_path.parent.parent

    write_env_file(env_path, profile)
    write_compose_override_file(compose_override_path, profile, config_dir, project_root / "logs")
    write_summary_file(summary_path, profile, target_config_path)


if __name__ == "__main__":
    main()

