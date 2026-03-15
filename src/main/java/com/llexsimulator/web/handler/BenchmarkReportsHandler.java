package com.llexsimulator.web.handler;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Serves generated benchmark reports from the benchmark artifacts directory.
 */
public final class BenchmarkReportsHandler {

    private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final Path reportsRoot;

    public BenchmarkReportsHandler() {
        this(resolveReportsRoot());
    }

    BenchmarkReportsHandler(Path reportsRoot) {
        this.reportsRoot = reportsRoot;
    }

    public Handler<RoutingContext> index() {
        return ctx -> {
            try {
                List<BenchmarkRun> runs = listRuns();
                String html = renderIndex(runs);
                ctx.response()
                        .putHeader("Content-Type", "text/html; charset=utf-8")
                        .end(html);
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    public Handler<RoutingContext> show() {
        return ctx -> {
            try {
                String runId = ctx.pathParam("runId");
                if (!isSafeRunId(runId)) {
                    ctx.response().setStatusCode(400).end("Invalid report id");
                    return;
                }

                Path reportFile = reportsRoot.resolve(runId).resolve("report.html").normalize();
                if (!reportFile.startsWith(reportsRoot) || !Files.isRegularFile(reportFile)) {
                    ctx.response().setStatusCode(404).end("Report not found");
                    return;
                }

                ctx.response()
                        .putHeader("Content-Type", "text/html; charset=utf-8")
                        .sendFile(reportFile.toString());
            } catch (Exception e) {
                ctx.fail(500, e);
            }
        };
    }

    private List<BenchmarkRun> listRuns() throws IOException {
        if (!Files.isDirectory(reportsRoot)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(reportsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(this::toRun)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(BenchmarkRun::runId).reversed())
                    .toList();
        }
    }

    private BenchmarkRun toRun(Path runDir) {
        Path reportFile = runDir.resolve("report.html");
        if (!Files.isRegularFile(reportFile)) {
            return null;
        }

        Map<String, String> metadata = readKeyValueFile(runDir.resolve("metadata.txt"));
        Map<String, String> stats = readJsonAsStrings(runDir.resolve("statistics.json"));
        return new BenchmarkRun(
                runDir.getFileName().toString(),
                metadata.getOrDefault("label", "Benchmark Run"),
                metadata.getOrDefault("rate", "n/a"),
                metadata.getOrDefault("duration_sec", "n/a"),
                metadata.getOrDefault("started_at_utc", "n/a"),
                stats.getOrDefault("throughputPerSec", "n/a"),
                stats.getOrDefault("p80LatencyUs", "n/a"),
                stats.getOrDefault("p90LatencyUs", "n/a"),
                stats.getOrDefault("p99LatencyUs", "n/a"),
                stats.getOrDefault("ordersReceived", "n/a"),
                stats.getOrDefault("fillRatePct", "n/a")
        );
    }

    private static Map<String, String> readKeyValueFile(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return values;
        }

        try {
            for (String raw : Files.readAllLines(path)) {
                int idx = raw.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                values.put(raw.substring(0, idx).trim(), raw.substring(idx + 1).trim());
            }
        } catch (IOException ignored) {
            // Best-effort listing page.
        }
        return values;
    }

    private static Map<String, String> readJsonAsStrings(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            return values;
        }

        try {
            String json = Files.readString(path);
            String trimmed = json.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                String body = trimmed.substring(1, trimmed.length() - 1).trim();
                if (!body.isEmpty()) {
                    for (String entry : splitTopLevel(body)) {
                        int idx = entry.indexOf(':');
                        if (idx <= 0) {
                            continue;
                        }
                        String key = stripQuotes(entry.substring(0, idx).trim());
                        String value = stripQuotes(entry.substring(idx + 1).trim());
                        values.put(key, value);
                    }
                }
            }
        } catch (IOException ignored) {
            // Best-effort listing page.
        }
        return values;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' && (i == 0 || body.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
            }
            if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String renderIndex(List<BenchmarkRun> runs) {
        StringBuilder cards = new StringBuilder();
        if (runs.isEmpty()) {
            cards.append("""
                    <div class="empty-state">
                      <h2>No benchmark reports yet</h2>
                      <p>Run <code>./scripts/run_benchmark_local.sh 500 30</code> or <code>./scripts/run_benchmark_droplet.sh 500 30</code> to generate your first report.</p>
                    </div>
                    """);
        } else {
            for (BenchmarkRun run : runs) {
                cards.append("""
                        <a class="report-card" href="/reports/%s">
                          <div class="report-card-header">
                            <div>
                              <h2>%s</h2>
                              <p>%s</p>
                            </div>
                            <span class="report-link">Open report →</span>
                          </div>
                          <div class="report-meta-row">
                            <span class="chip">Rate %s msg/s</span>
                            <span class="chip">Duration %s s</span>
                            <span class="chip">Orders %s</span>
                            <span class="chip">Fill Rate %s%%</span>
                          </div>
                          <div class="metric-grid">
                            %s
                            %s
                            %s
                            %s
                          </div>
                        </a>
                        """.formatted(
                        escape(run.runId()),
                        escape(run.label()),
                        escape(formatUtc(run.startedAtUtc())),
                        escape(run.rate()),
                        escape(run.durationSec()),
                        escape(run.ordersReceived()),
                        escape(run.fillRatePct()),
                        metricTile("Throughput", run.throughputPerSec(), "green"),
                        metricTile("p80", run.p80LatencyUs() + " µs", "amber"),
                        metricTile("p90", run.p90LatencyUs() + " µs", "amber"),
                        metricTile("p99", run.p99LatencyUs() + " µs", "red")
                ));
            }
        }

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>LLExSimulator Reports</title>
                  <style>
                    :root {
                      --bg: #0b1220;
                      --panel: #111a2b;
                      --panel-2: #16233a;
                      --text: #e7eefc;
                      --muted: #9fb2d1;
                      --green: #31d0aa;
                      --amber: #f5b942;
                      --red: #ff6b81;
                      --blue: #4da3ff;
                      --border: rgba(255,255,255,0.08);
                      --shadow: 0 18px 40px rgba(0,0,0,0.25);
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
                      background: linear-gradient(180deg, #09101c, #0f1728 30%, #0b1220 100%);
                      color: var(--text);
                    }
                    .wrap { max-width: 1320px; margin: 0 auto; padding: 32px 20px 48px; }
                    .hero {
                      background: linear-gradient(135deg, rgba(77,163,255,0.18), rgba(49,208,170,0.12));
                      border: 1px solid var(--border);
                      border-radius: 24px;
                      padding: 28px;
                      box-shadow: var(--shadow);
                    }
                    .hero h1 { margin: 0 0 8px; font-size: 34px; }
                    .hero p { margin: 0; color: var(--muted); }
                    .hero-actions { margin-top: 18px; display: flex; flex-wrap: wrap; gap: 12px; }
                    .hero-actions a, .hero-actions span {
                      display: inline-flex;
                      align-items: center;
                      gap: 8px;
                      padding: 10px 14px;
                      border-radius: 999px;
                      border: 1px solid var(--border);
                      background: rgba(255,255,255,0.04);
                      color: var(--text);
                      text-decoration: none;
                      font-size: 14px;
                    }
                    .reports-grid {
                      display: grid;
                      grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
                      gap: 18px;
                      margin-top: 24px;
                    }
                    .report-card {
                      display: block;
                      text-decoration: none;
                      color: inherit;
                      background: var(--panel);
                      border: 1px solid var(--border);
                      border-radius: 22px;
                      padding: 20px;
                      box-shadow: var(--shadow);
                      transition: transform 0.16s ease, border-color 0.16s ease;
                    }
                    .report-card:hover {
                      transform: translateY(-2px);
                      border-color: rgba(77,163,255,0.35);
                    }
                    .report-card-header {
                      display: flex;
                      align-items: flex-start;
                      justify-content: space-between;
                      gap: 12px;
                    }
                    .report-card-header h2 { margin: 0 0 6px; font-size: 20px; }
                    .report-card-header p { margin: 0; color: var(--muted); font-size: 13px; }
                    .report-link { color: var(--blue); font-weight: 600; white-space: nowrap; }
                    .report-meta-row {
                      display: flex;
                      flex-wrap: wrap;
                      gap: 10px;
                      margin: 18px 0 16px;
                    }
                    .chip {
                      padding: 7px 11px;
                      border-radius: 999px;
                      background: rgba(255,255,255,0.05);
                      border: 1px solid var(--border);
                      color: var(--muted);
                      font-size: 12px;
                    }
                    .metric-grid {
                      display: grid;
                      grid-template-columns: repeat(2, minmax(0, 1fr));
                      gap: 12px;
                    }
                    .metric {
                      padding: 14px;
                      border-radius: 16px;
                      background: var(--panel-2);
                      border: 1px solid var(--border);
                    }
                    .metric-label {
                      color: var(--muted);
                      text-transform: uppercase;
                      letter-spacing: 0.08em;
                      font-size: 11px;
                    }
                    .metric-value {
                      margin-top: 8px;
                      font-size: 24px;
                      font-weight: 700;
                    }
                    .tone-green { box-shadow: inset 0 0 0 1px rgba(49,208,170,0.18); }
                    .tone-amber { box-shadow: inset 0 0 0 1px rgba(245,185,66,0.18); }
                    .tone-red { box-shadow: inset 0 0 0 1px rgba(255,107,129,0.18); }
                    .empty-state {
                      margin-top: 24px;
                      background: var(--panel);
                      border: 1px solid var(--border);
                      border-radius: 22px;
                      padding: 28px;
                      box-shadow: var(--shadow);
                    }
                    .empty-state h2 { margin: 0 0 10px; }
                    .empty-state p { margin: 0; color: var(--muted); }
                    code {
                      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                      background: rgba(255,255,255,0.08);
                      border-radius: 8px;
                      padding: 2px 6px;
                    }
                  </style>
                </head>
                <body>
                  <div class="wrap">
                    <section class="hero">
                      <h1>Benchmark Reports</h1>
                      <p>Browse generated benchmark runs, compare headline latency metrics, and open each full HTML report.</p>
                      <div class="hero-actions">
                        <a href="/">← Back to Simulator</a>
                        <span>Reports root: __REPORTS_ROOT__</span>
                      </div>
                    </section>
                    <section class="reports-grid">__CARDS__</section>
                  </div>
                </body>
                </html>
                """
                .replace("__REPORTS_ROOT__", escape(reportsRoot.toString()))
                .replace("__CARDS__", cards.toString());
    }

    private static String metricTile(String label, String value, String tone) {
        return """
                <div class="metric tone-%s">
                  <div class="metric-label">%s</div>
                  <div class="metric-value">%s</div>
                </div>
                """.formatted(tone, escape(label), escape(value));
    }

    private static String formatUtc(String raw) {
        try {
            return UTC_FORMAT.format(Instant.parse(raw));
        } catch (Exception e) {
            return raw;
        }
    }

    private static boolean isSafeRunId(String runId) {
        return runId != null && runId.matches("[A-Za-z0-9._-]+");
    }

    private static Path resolveReportsRoot() {
        Path containerPath = Path.of("/app/logs/benchmark-reports");
        if (Files.isDirectory(containerPath)) {
            return containerPath;
        }
        return Path.of("logs/benchmark-reports");
    }

    private static String escape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private record BenchmarkRun(
            String runId,
            String label,
            String rate,
            String durationSec,
            String startedAtUtc,
            String throughputPerSec,
            String p80LatencyUs,
            String p90LatencyUs,
            String p99LatencyUs,
            String ordersReceived,
            String fillRatePct
    ) {
    }
}

