#!/usr/bin/env python3
from __future__ import annotations

import html
import json
import sys
from pathlib import Path


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def read_json(path: Path) -> dict:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def read_metadata(path: Path) -> dict[str, str]:
    data: dict[str, str] = {}
    if not path.exists():
        return data
    for raw in path.read_text(encoding="utf-8").splitlines():
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        data[key.strip()] = value.strip()
    return data


def card(title: str, body: str, accent: str = "blue") -> str:
    return f"""
    <section class=\"card accent-{accent}\">
      <h2>{html.escape(title)}</h2>
      {body}
    </section>
    """


def metric_tile(label: str, value: str, tone: str = "neutral") -> str:
    return f"""
    <div class=\"metric tone-{tone}\">
      <div class=\"metric-label\">{html.escape(label)}</div>
      <div class=\"metric-value\">{html.escape(value)}</div>
    </div>
    """


def pre_block(text: str) -> str:
    escaped = html.escape(text.strip() if text.strip() else "(empty)")
    return f"<pre>{escaped}</pre>"


def table_from_mapping(mapping: dict[str, str]) -> str:
    rows = []
    for key, value in mapping.items():
        rows.append(
            f"<tr><th>{html.escape(str(key))}</th><td>{html.escape(str(value))}</td></tr>"
        )
    return "<table class=\"kv\">" + "".join(rows) + "</table>"


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: render_benchmark_report.py <artifacts-dir> <output-html>", file=sys.stderr)
        return 1

    artifacts_dir = Path(sys.argv[1]).resolve()
    output_html = Path(sys.argv[2]).resolve()

    metadata = read_metadata(artifacts_dir / "metadata.txt")
    health = read_json(artifacts_dir / "health.json")
    stats = read_json(artifacts_dir / "statistics.json")
    docker_stats = read_text(artifacts_dir / "docker-stats.txt")
    resource_limits = read_text(artifacts_dir / "resource-limits.txt")
    compose_ps = read_text(artifacts_dir / "docker-compose-ps.txt")
    simulator_log = read_text(artifacts_dir / "simulator-log-tail.txt")
    client_log = read_text(artifacts_dir / "demo-client-log-tail.txt")
    simulator_docker_log = read_text(artifacts_dir / "simulator-docker-logs.txt")
    client_docker_log = read_text(artifacts_dir / "demo-client-docker-logs.txt")

    title = f"LLExSimulator Benchmark Report · {metadata.get('run_id', artifacts_dir.name)}"

    p99 = stats.get("p99LatencyUs", "n/a")
    p90 = stats.get("p90LatencyUs", "n/a")
    p80 = stats.get("p80LatencyUs", "n/a")
    tps = stats.get("throughputPerSec", "n/a")
    fill_rate = stats.get("fillRatePct", "n/a")
    active_profile = stats.get("activeProfile", "n/a")
    orders = stats.get("ordersReceived", "n/a")
    exec_reports = stats.get("execReportsSent", "n/a")

    summary_tiles = "".join([
        metric_tile("Rate", f"{metadata.get('rate', 'n/a')} msg/s", "blue"),
        metric_tile("Duration", f"{metadata.get('duration_sec', 'n/a')} s", "blue"),
        metric_tile("Throughput", f"{tps}/s", "green"),
        metric_tile("Orders", str(orders), "green"),
        metric_tile("Exec Reports", str(exec_reports), "green"),
        metric_tile("p80", f"{p80} µs", "amber"),
        metric_tile("p90", f"{p90} µs", "amber"),
        metric_tile("p99", f"{p99} µs", "red"),
        metric_tile("Fill Rate", f"{fill_rate}%", "green"),
        metric_tile("Profile", str(active_profile), "purple"),
    ])

    artifact_listing = {}
    for child in sorted(artifacts_dir.iterdir()):
        artifact_listing[child.name] = str(child)

    html_doc = f"""<!doctype html>
<html lang=\"en\">
<head>
  <meta charset=\"utf-8\">
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">
  <title>{html.escape(title)}</title>
  <style>
    :root {{
      --bg: #0b1220;
      --panel: #111a2b;
      --panel-soft: #16233a;
      --text: #e7eefc;
      --muted: #9fb2d1;
      --blue: #4da3ff;
      --green: #31d0aa;
      --amber: #f5b942;
      --red: #ff6b81;
      --purple: #9b7bff;
      --border: rgba(255,255,255,0.08);
      --shadow: 0 18px 40px rgba(0,0,0,0.25);
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
      background: linear-gradient(180deg, #09101c, #0f1728 30%, #0b1220 100%);
      color: var(--text);
      line-height: 1.5;
    }}
    .wrap {{ max-width: 1400px; margin: 0 auto; padding: 32px 20px 48px; }}
    .hero {{
      background: linear-gradient(135deg, rgba(77,163,255,0.18), rgba(155,123,255,0.16));
      border: 1px solid var(--border);
      border-radius: 24px;
      padding: 28px 28px 20px;
      box-shadow: var(--shadow);
      margin-bottom: 24px;
    }}
    .hero h1 {{ margin: 0 0 8px; font-size: 32px; }}
    .hero p {{ margin: 0; color: var(--muted); }}
    .grid {{ display: grid; gap: 18px; }}
    .summary {{ grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); margin: 22px 0 10px; }}
    .metric {{
      border-radius: 18px;
      padding: 16px 18px;
      border: 1px solid var(--border);
      background: rgba(255,255,255,0.04);
      min-height: 96px;
    }}
    .metric-label {{ font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--muted); }}
    .metric-value {{ font-size: 28px; font-weight: 700; margin-top: 10px; }}
    .tone-blue {{ box-shadow: inset 0 0 0 1px rgba(77,163,255,0.18); }}
    .tone-green {{ box-shadow: inset 0 0 0 1px rgba(49,208,170,0.18); }}
    .tone-amber {{ box-shadow: inset 0 0 0 1px rgba(245,185,66,0.18); }}
    .tone-red {{ box-shadow: inset 0 0 0 1px rgba(255,107,129,0.18); }}
    .tone-purple {{ box-shadow: inset 0 0 0 1px rgba(155,123,255,0.18); }}
    .cards {{ grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); }}
    .card {{
      background: var(--panel);
      border: 1px solid var(--border);
      border-radius: 22px;
      padding: 20px;
      box-shadow: var(--shadow);
    }}
    .card h2 {{ margin: 0 0 14px; font-size: 20px; }}
    .accent-blue {{ border-top: 3px solid var(--blue); }}
    .accent-green {{ border-top: 3px solid var(--green); }}
    .accent-amber {{ border-top: 3px solid var(--amber); }}
    .accent-red {{ border-top: 3px solid var(--red); }}
    .accent-purple {{ border-top: 3px solid var(--purple); }}
    table.kv {{ width: 100%; border-collapse: collapse; overflow: hidden; }}
    table.kv th, table.kv td {{ text-align: left; padding: 10px 12px; border-bottom: 1px solid rgba(255,255,255,0.06); vertical-align: top; }}
    table.kv th {{ width: 35%; color: var(--muted); font-weight: 600; }}
    pre {{
      margin: 0;
      padding: 14px;
      border-radius: 16px;
      background: #0a1020;
      border: 1px solid rgba(255,255,255,0.07);
      color: #d7e4ff;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      max-height: 380px;
    }}
    .subgrid {{ display: grid; gap: 18px; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); margin-top: 18px; }}
    .badge-row {{ display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; }}
    .badge {{ border-radius: 999px; padding: 8px 12px; background: rgba(255,255,255,0.05); border: 1px solid var(--border); color: var(--muted); font-size: 13px; }}
  </style>
</head>
<body>
  <div class=\"wrap\">
    <section class=\"hero\">
      <h1>{html.escape(title)}</h1>
      <p>Readable benchmark evidence bundle with headline latency metrics, runtime metadata, Docker resource usage, and captured logs.</p>
      <div class=\"badge-row\">
        <span class=\"badge\">Run ID: {html.escape(metadata.get('run_id', artifacts_dir.name))}</span>
        <span class=\"badge\">Rate: {html.escape(metadata.get('rate', 'n/a'))} msg/s</span>
        <span class=\"badge\">Duration: {html.escape(metadata.get('duration_sec', 'n/a'))} s</span>
        <span class=\"badge\">Web Port: {html.escape(metadata.get('web_port', 'n/a'))}</span>
        <span class=\"badge\">Benchmark Mode Changed: {html.escape(metadata.get('benchmark_mode_changed', 'n/a'))}</span>
      </div>
      <div class=\"grid summary\">{summary_tiles}</div>
    </section>

    <div class=\"grid cards\">
      {card('Run metadata', table_from_mapping(metadata), 'blue')}
      {card('Health snapshot', table_from_mapping({k: str(v) for k, v in health.items()} or {'status': 'missing'}), 'green')}
      {card('Final statistics', table_from_mapping({k: str(v) for k, v in stats.items()} or {'statistics': 'missing'}), 'amber')}
      {card('Artifacts', table_from_mapping(artifact_listing), 'purple')}
    </div>

    <div class=\"subgrid\">
      {card('Docker resource snapshot', pre_block(docker_stats), 'green')}
      {card('Configured container limits', pre_block(resource_limits), 'blue')}
      {card('docker compose ps', pre_block(compose_ps), 'blue')}
      {card('Simulator log tail', pre_block(simulator_log), 'amber')}
      {card('Demo client log tail', pre_block(client_log), 'amber')}
      {card('Simulator docker logs', pre_block(simulator_docker_log), 'red')}
      {card('Demo client docker logs', pre_block(client_docker_log), 'red')}
    </div>
  </div>
</body>
</html>
"""

    output_html.write_text(html_doc, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

