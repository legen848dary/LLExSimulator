# LLExSimulator — High-Performance FIX Exchange Simulator

[![Java 21](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net)
[![QuickFIX/J](https://img.shields.io/badge/FIX-4.2%20%7C%204.4%20%7C%205.0%20%7C%205.0SP2-green)](https://www.quickfixj.org)
[![LMAX Disruptor](https://img.shields.io/badge/Disruptor-4.0.0-orange)](https://lmax-exchange.github.io/disruptor)
[![Aeron](https://img.shields.io/badge/Aeron-1.44.0-purple)](https://aeron.io)

A **zero-GC, ultra-low-latency** FIX Exchange Simulator designed for **performance and capacity testing**. It accepts real FIX connections and responds with fully configurable fill behaviors — all without pausing the JVM for garbage collection.

---

## Table of Contents

- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Fill Behaviors](#fill-behaviors)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Building](#building)
- [Running Locally (JVM)](#running-locally-jvm)
- [Docker Deployment](#docker-deployment)
  - [Management Script Reference](#management-script-reference)
  - [Deploying to a Fresh Ubuntu Droplet](#deploying-to-a-fresh-ubuntu-droplet)
- [Configuration](#configuration)
- [REST API Reference](#rest-api-reference)
- [WebSocket Events](#websocket-events)
- [Web UI](#web-ui)
- [FIX Session Configuration](#fix-session-configuration)
- [Performance Tuning](#performance-tuning)
- [Project Structure](#project-structure)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           FIX Clients (any FIX version)                     │
│                    FIX 4.2 │ FIX 4.4 │ FIX 5.0 │ FIX 5.0SP2                │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ TCP (port 9880)
                               ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                         QuickFIX/J SocketAcceptor                            │
│                    [dedicated platform thread • busy-poll]                   │
│                                                                              │
│  ① Capture arrivalTimeNs = System.nanoTime()    ← latency clock starts here  │
│  ② fromApp(Message, SessionID)                                               │
│  ③ Publish to Disruptor RingBuffer (publishEvent)  ← returns immediately    │
└────────────────────────────────┬─────────────────────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│               LMAX Disruptor RingBuffer  (131 072 pre-allocated slots)       │
│         Each slot holds off-heap SBE-encoded buffers — zero heap allocation  │
│                                                                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  ┌──────┐│
│  │ Stage 1          │→ │ Stage 2          │→ │ Stage 3          │→ │ St.4 ││
│  │ ValidationHandler│  │ FillStrategy     │  │ ExecutionReport  │  │Metric││
│  │                  │  │ Handler          │  │ Handler          │  │Publis││
│  │ • Symbol check   │  │ • 1 volatile read│  │ • Build FIX 35=8 │  │ ││
│  │ • Qty > 0        │  │   for active cfg │  │ • sendToTarget() │  │ ││
│  │ • Price for LIMIT│  │ • Apply strategy │  │ • Virtual thread │  │ ││
│  │ • Sets isValid   │  │ • Claim OrderState│  │   for delayed    │  │ ││
│  └──────────────────┘  └──────────────────┘  └──────────────────┘  └──────┘│
│                [single dedicated platform thread • BusySpinWaitStrategy]    │
└────────────────────────────────────────────────────────────────────────────┬─┘
                                                                             │
                     FIX ExecutionReport (35=8)                              │ Aeron IPC
                     sent via QuickFIX/J Session                             │ (aeron:ipc
                             │                                               │ stream 1002)
                             ▼                                               ▼
                    FIX Client receives                          ┌───────────────────────┐
                    ExecutionReport                              │ MetricsSubscriber     │
                                                                │ [virtual thread]      │
                                                                │ Decodes MetricsSnap.  │
                                                                │ Builds JSON (no alloc)│
                                                                └──────────┬────────────┘
                                                                           │ vertx.runOnContext()
                                                                           ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                          Vert.x HTTP Server  (port 8080)                     │
│                     [native transport: epoll / kqueue]                       │
│                                                                              │
│   REST API                     WebSocket                  Static SPA         │
│   /api/fill-profiles     ←→    /ws  →  Vue 3 UI          /  (index.html)    │
│   /api/statistics               real-time metrics                            │
│   /api/sessions                 order flow events                            │
│   /api/health                                                                │
└──────────────────────────────────────────────────────────────────────────────┘
                                       ▲
                                       │  Browser WebSocket
                                       │
┌──────────────────────────────────────────────────────────────────────────────┐
│                    Vue 3 SPA  (Tailwind CSS + Chart.js)                      │
│                                                                              │
│  ┌────────────────┐  ┌─────────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ Fill Profile   │  │ Stats Dashboard │  │  p99 Latency │  │  FIX       │ │
│  │ Config Panel   │  │ throughput/fills│  │  Chart (60pt)│  │  Sessions  │ │
│  │ 9 behaviors    │  │ reject rate     │  │  live update │  │  + disconnect│ │
│  └────────────────┘  └─────────────────┘  └──────────────┘  └────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Thread Inventory

| Thread Name | Type | Role | Idle Strategy |
|---|---|---|---|
| `artio-poll` / QFJ threads | Platform (daemon) | QuickFIX/J network I/O | Netty NIO |
| `disruptor-handler-0` | Platform (daemon, MAX priority) | All 4 pipeline stages | `BusySpinWaitStrategy` |
| `aeron-sender` | Platform (Aeron-managed) | Aeron media driver sender | NoOp (busy-poll) |
| `aeron-receiver` | Platform (Aeron-managed) | Aeron media driver receiver | NoOp (busy-poll) |
| `aeron-conductor` | Platform (Aeron-managed) | Aeron media driver conductor | BusySpin |
| `metrics-subscriber` | **Virtual** | Aeron subscription → WebSocket | `Thread.onSpinWait()` |
| `vert.x-eventloop-*` | Platform | HTTP / REST / WebSocket | Netty epoll/kqueue |
| `delayed-fill-<id>` | **Virtual** | `LockSupport.parkNanos()` + send | N/A |
| `throughput-tracker` | **Virtual** | 1-second window TPS counter | `Thread.sleep(1000)` |

---

## Technology Stack

| Concern | Library | Version | Why |
|---|---|---|---|
| **FIX Engine** | QuickFIX/J | 2.3.1 | Production-grade; supports FIX 4.2, 4.4, 5.0, 5.0SP2, FIXT 1.1 out-of-the-box |
| **Order Pipeline** | LMAX Disruptor | 4.0.0 | Lock-free ring buffer; single-producer single-consumer; cache-line padded |
| **Off-Heap State** | Agrona `UnsafeBuffer` | 1.22.0 | Direct `ByteBuffer`-backed; no GC pressure; `Long2ObjectHashMap` for registry |
| **Internal Protocol** | SBE (Simple Binary Encoding) | 1.30.0 | Fixed-size flyweight codecs; zero-copy encode/decode; generated at build time |
| **Metrics IPC** | Aeron | 1.44.0 | Zero-copy `aeron:ipc` transport between Disruptor thread and Vert.x event loop |
| **Latency Histograms** | HdrHistogram | 2.2.2 | Lock-free; pre-allocated (no resize at runtime); p50/p99/p999 |
| **Web Server** | Vert.x | 4.5.10 | Non-blocking, native epoll/kqueue transport; async WebSocket |
| **Frontend** | Vue 3 + Tailwind CSS + Chart.js | CDN | No build step required; real-time SPA |
| **JVM** | Java 21 + ZGC Generational | 21 | Sub-millisecond GC pauses; virtual threads for non-critical paths |

---

## Fill Behaviors

Nine configurable fill strategies can be switched live without restarting the simulator:

| Behavior | Description | Key Parameters |
|---|---|---|
| `IMMEDIATE_FULL_FILL` | 100% fill at limit price, zero delay | — |
| `PARTIAL_FILL` | Partial fill in N legs | `fillPctBps` (0–10000), `numPartialFills` |
| `DELAYED_FILL` | Full fill after a configurable delay | `delayMs` |
| `REJECT` | Reject with a configurable reason code | `rejectReason` |
| `PARTIAL_THEN_CANCEL` | Partial fill, then cancel remaining | `fillPctBps` |
| `PRICE_IMPROVEMENT` | Fill at a price better than limit | `priceImprovementBps` |
| `FILL_AT_ARRIVAL_PRICE` | Fill at the price seen on order arrival | — |
| `RANDOM_FILL` | Random qty (within range) + random delay | `randomMinQtyPct`, `randomMaxQtyPct`, `randomMinDelayMs`, `randomMaxDelayMs` |
| `NO_FILL_IOC_CANCEL` | Immediate cancel, zero fill (IOC/FOK simulation) | — |

> **Switching live:** `PUT /api/fill-profiles/:name/activate` or use the Web UI. The change takes effect on the *very next order* — no restart required.

---

## Prerequisites

| Tool | Minimum Version | Install |
|---|---|---|
| Java JDK | 21 | [Adoptium](https://adoptium.net) or `brew install --cask temurin@21` |
| Docker Desktop | 4.x | [docker.com](https://www.docker.com/products/docker-desktop) |
| Docker Compose | v2 (built into Docker Desktop) | Included with Docker Desktop |
| `curl` | Any | Pre-installed on macOS/Linux |

> **Local JVM run only:** Java 21 is required. Docker handles everything else.

---

## Quick Start

```bash
# 1. Clone (if not already)
git clone git@github.com:legen848dary/LLExSimulator.git
cd LLExSimulator

# 2. Build and start with Docker (recommended)
./scripts/local_llexsim.sh build
./scripts/local_llexsim.sh start

# 3. Open the Web UI
open http://localhost:8080

# 4. Check health
curl http://localhost:8080/api/health
```

---

## Building

### Gradle (fat JAR)

```bash
# Generate SBE codecs + compile + package
./gradlew shadowJar

# Output: build/libs/LLExSimulator-1.0-SNAPSHOT.jar
```

### Docker image only

```bash
./scripts/local_llexsim.sh build
# or directly:
docker compose build
```

The Dockerfile uses a **multi-stage build**:
- **Stage 1** (`gradle:8.7-jdk21`): builds the fat JAR
- **Stage 2** (`eclipse-temurin:21-jre-jammy`): lean runtime image (~280 MB)

---

## Running Locally (JVM)

For development or when Docker is not available:

```bash
# Build the fat JAR
./gradlew shadowJar

# Run with recommended JVM flags (ZGC, fixed heap, Aeron config)
java \
  -XX:+UseZGC -XX:+ZGenerational \
  -Xms512m -Xmx512m \
  -XX:+AlwaysPreTouch \
  -XX:+DisableExplicitGC \
  -Daeron.dir=/tmp/aeron-llexsim \
  -Daeron.ipc.term.buffer.length=8388608 \
  -Daeron.threading.mode=DEDICATED \
  -Daeron.sender.idle.strategy=noop \
  -Daeron.receiver.idle.strategy=noop \
  -Dagrona.disable.bounds.checks=true \
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  -jar build/libs/LLExSimulator-1.0-SNAPSHOT.jar
```

> **Shared-memory note:** the simulator prefers `/dev/shm` on Linux for lower-latency Aeron IPC, but now automatically falls back to `/tmp/aeron-llexsim` if `/dev/shm` is missing or too small for startup.

---

## Docker Deployment

All Docker lifecycle operations are handled by a single script:

```bash
./scripts/local_llexsim.sh <command>
```

### Management Script Reference

| Command | Description |
|---|---|
| `build` | Build the Docker image from source (runs Gradle + `docker compose build`) |
| `start` | Start the simulator; auto-builds image if missing |
| `stop` | Gracefully stop the running container (`SIGTERM`, 15 s timeout) |
| `restart` | Stop then start (rolling restart, no data loss) |
| `status` | Show container health, port bindings, CPU/memory usage, and live API health |
| `logs` | Tail container logs in real-time (`Ctrl+C` to exit) |
| `clean` | Stop + remove containers and Docker volumes; **preserves** the built image |
| `purge` | Full wipe: containers, volumes, image, and `build/` directory |
| `fix-connect` | Test FIX TCP port connectivity using `nc` |
| `help` | Show usage reference |

### Examples

```bash
# ── First-time setup ─────────────────────────────────────────────────────────
./scripts/local_llexsim.sh build          # Build image (~3–5 min first time)
./scripts/local_llexsim.sh start          # Start and wait for healthy

# ── Daily operations ─────────────────────────────────────────────────────────
./scripts/local_llexsim.sh status         # Check health + resource usage
./scripts/local_llexsim.sh logs           # Follow live logs
./scripts/local_llexsim.sh restart        # Apply config change without full rebuild
./scripts/local_stop_all.sh               # Stop both the demo client and simulator
./scripts/local_clean_ledgers.sh          # Remove FIX/Aeron ledger state without deleting normal logs

# ── Testing FIX connectivity ─────────────────────────────────────────────────
./scripts/local_llexsim.sh fix-connect    # Verify port 9880 is open

# ── Cleanup ──────────────────────────────────────────────────────────────────
./scripts/local_llexsim.sh clean          # Remove containers + volumes (keep image)
./scripts/local_llexsim.sh purge          # Full wipe; next start will rebuild image

# ── Override ports ───────────────────────────────────────────────────────────
WEB_PORT=9090 FIX_PORT=9999 ./scripts/local_llexsim.sh start

# ── Show more log lines ───────────────────────────────────────────────────────
LOG_LINES=500 ./scripts/local_llexsim.sh logs
```

### Ports

| Port | Protocol | Description |
|---|---|---|
| `8080` | HTTP/WS | Web UI, REST API, WebSocket (`/ws`) |
| `9880` | TCP | FIX acceptor (all FIX versions) |

### Deploying to a Fresh Ubuntu Droplet

This repository includes three helper scripts for a brand-new Ubuntu droplet:

- [`scripts/remote_setup_droplet_for_docker.sh`](scripts/remote_setup_droplet_for_docker.sh) — install Docker and baseline deployment tooling
- [`scripts/remote_release_to_droplet.sh`](scripts/remote_release_to_droplet.sh) — rebuild locally and deploy the Docker image remotely
- [`scripts/remote_setup_https_for_hostname.sh`](scripts/remote_setup_https_for_hostname.sh) — configure Nginx + Certbot for a public hostname

All three scripts use the same SSH argument style:

```bash
./scripts/<script-name> <host-or-ip> <ssh-key-path> <ssh-user> [options]
```

#### Step 1 — Provision the droplet for Docker

Run this once on a fresh Ubuntu droplet:

```bash
./scripts/remote_setup_droplet_for_docker.sh 203.0.113.10 ~/.ssh/<your-private-key> root
```

What it does:

- installs Docker Engine from Docker's official Ubuntu repository
- installs deployment helpers such as `git`, `rsync`, `curl`, `jq`, `python3`, `ufw`, and `fail2ban`
- enables `docker` and `containerd`
- grants Docker access to the SSH user used for bootstrap (for example `root`, `ubuntu`, or `deploy`)
- creates `/opt/llexsimulator/{config,logs,releases,scripts}` and assigns ownership to that SSH user

The remote setup, release, and HTTPS scripts support either:

- direct `root` SSH access, or
- a passwordless `sudo` user such as `ubuntu` or `deploy`

Security defaults:

- UFW opens **SSH only** by default
- the web port and FIX port remain closed unless you explicitly opt in

If you intentionally want a public firewall rule later, you can opt in during provisioning:

```bash
./scripts/remote_setup_droplet_for_docker.sh 203.0.113.10 ~/.ssh/<your-private-key> root --open-web-port
./scripts/remote_setup_droplet_for_docker.sh 203.0.113.10 ~/.ssh/<your-private-key> root --open-fix-port
```

#### Step 2 — Build locally and deploy the simulator

From your local machine, rebuild and deploy to the droplet:

```bash
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root
```

What this script does:

1. builds the fat JAR locally with `./gradlew shadowJar`
2. builds a droplet-targeted Docker image locally with `docker buildx --platform linux/amd64`
3. syncs `config/` to `/opt/llexsimulator/config/` on the droplet
4. syncs droplet helper scripts to `/opt/llexsimulator/scripts/`
5. streams the Docker image to the droplet using `docker save | ssh ... docker load`
6. verifies remote Docker/Compose access and write permissions before deployment
7. writes `/opt/llexsimulator/docker-compose.yml`
8. starts or recreates the container remotely and waits for health

#### Zero-to-working checklist for a brand-new droplet

From your local machine, this is the shortest repeatable path from an empty droplet to a working simulator:

```bash
# 1. Bootstrap the droplet for Docker (root or a passwordless sudo user)
./scripts/remote_setup_droplet_for_docker.sh 203.0.113.10 ~/.ssh/<your-private-key> root

# 2. Build locally and deploy the simulator to the droplet
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root

# 3. Verify the app is healthy on the droplet
ssh -i ~/.ssh/<your-private-key> root@203.0.113.10 'curl -fsS http://127.0.0.1:8080/api/health | cat'

# 4. Optional: expose the web UI through HTTPS on your hostname
./scripts/remote_setup_https_for_hostname.sh \
  203.0.113.10 \
  ~/.ssh/<your-private-key> \
  root \
  --fqdn sim.example.com \
  --email ops@example.com
```

After that, rerunning only the release script is the normal rebuild/redeploy path:

```bash
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root
```

If a deployment fails specifically during the Docker image upload step and you already have the correct local image, you can retry from the image-transfer phase onward without rebuilding:

```bash
./scripts/remote_retry_image_transfer_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root
```

If your network is flaky and you want resumable uploads instead of restarting from byte 0 on every interruption, use the rsync-based resumable variant instead:

```bash
./scripts/remote_resume_image_transfer_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root
```

Use the full release script when you need a fresh JAR/image build or a config sync. Use the streaming retry script for a quick one-shot resend. Use the resumable script when the build is already done and the upload path needs true resume support.

Secure deployment defaults:

- the web/API port binds to `127.0.0.1:8080` on the droplet
- the FIX port binds to `127.0.0.1:9880` on the droplet
- the simulator auto-pins to one fewer vCPU than the host has, leaving headroom for SSH, Nginx, Docker, and the optional demo client
- neither service is internet-facing by default

That means the intended public web entrypoint is an HTTPS reverse proxy such as Nginx, and the intended FIX access path is either local-on-droplet use or SSH tunneling from your workstation.

On a `2 vCPU / 2 GB` droplet, the default `CPUSET_MODE=auto` now pins the simulator to a single core instead of both cores.
If you need to override that explicitly, use for example:

```bash
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root --cpuset 0
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root --cpuset none
```

If you explicitly want public Docker port publishing later, the release script supports opt-in flags:

```bash
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root --public-web-port
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root --public-fix-port
```

#### Step 3 — Point your hostname at the droplet

Before requesting an HTTPS certificate, create a DNS `A` record such as:

```text
sim.example.com -> 203.0.113.10
```

Important routing model:

- DNS maps the hostname to the droplet IP only
- Nginx on the droplet maps that hostname to `http://127.0.0.1:8080`
- DNS does **not** know or care where `/opt/llexsimulator` lives on disk

#### Step 4 — Configure HTTPS for the hostname

After the app is deployed and the DNS `A` record is live, configure Nginx + Certbot:

```bash
./scripts/remote_setup_https_for_hostname.sh \
  203.0.113.10 \
  ~/.ssh/<your-private-key> \
  root \
  --fqdn sim.example.com \
  --email ops@example.com
```

This script:

- installs `nginx`, `certbot`, and `python3-certbot-nginx`
- creates an Nginx reverse-proxy config for your hostname
- proxies public HTTP/HTTPS traffic to `http://127.0.0.1:8080`
- obtains and installs a Let's Encrypt certificate
- enables redirect from HTTP to HTTPS

After it completes, the expected public entrypoint is:

```text
https://sim.example.com
```

If you want the script to remove an old firewall rule that exposed port `8080`, use:

```bash
./scripts/remote_setup_https_for_hostname.sh \
  203.0.113.10 \
  ~/.ssh/<your-private-key> \
  root \
  --fqdn sim.example.com \
  --email ops@example.com \
  --close-direct-web-port
```

#### Step 5 — Access FIX locally via SSH tunnel

By default, the FIX acceptor is local-only on the droplet at `127.0.0.1:9880`.

If you want to inspect the FIX port locally from your laptop with another tool, open an SSH tunnel first:

```bash
ssh -i ~/.ssh/<your-private-key> -N -L 9880:127.0.0.1:9880 root@203.0.113.10
```

You can then point any local FIX tool at `localhost:9880`.

#### Step 6 — Run the demo FIX client on the droplet only when needed

The droplet deployment now includes an on-demand Docker Compose service named `fix-demo-client`.
It is not started by default.

SSH into the droplet:

```bash
ssh -i ~/.ssh/<your-private-key> root@203.0.113.10
cd /opt/llexsimulator
```

Start the demo client in the background at `100 msg/s`:

```bash
./scripts/fix_demo_client_start.sh 100
```

Start it at a higher rate:

```bash
./scripts/fix_demo_client_start.sh 500
```

View logs:

```bash
docker compose logs -f fix-demo-client
```

Check status:

```bash
docker compose ps fix-demo-client
```

Stop and remove it cleanly:

```bash
./scripts/fix_demo_client_stop.sh
```

Useful environment overrides for the droplet demo client:

```bash
FIX_CLIENT_BEGIN_STRING=FIX.4.4 \
FIX_CLIENT_SENDER_COMP_ID=CLIENT1 \
FIX_CLIENT_TARGET_COMP_ID=LLEXSIM \
FIX_CLIENT_SYMBOL=MSFT \
FIX_CLIENT_ORDER_QTY=250 \
FIX_CLIENT_PRICE=412.15 \
./scripts/fix_demo_client_start.sh 250
```

Because the demo client runs in the same Compose network as the simulator, it connects to the simulator privately inside Docker using the internal hostname `llexsimulator` and does not require the FIX port to be publicly exposed.

#### Step 7 — Useful dry runs

All droplet scripts support a dry-run mode so you can inspect the remote commands before executing them:

```bash
./scripts/remote_setup_droplet_for_docker.sh 203.0.113.10 ~/.ssh/<your-private-key> root --dry-run
./scripts/remote_release_to_droplet.sh 203.0.113.10 ~/.ssh/<your-private-key> root --dry-run
./scripts/remote_setup_https_for_hostname.sh 203.0.113.10 ~/.ssh/<your-private-key> root --fqdn sim.example.com --dry-run
```

During a real `./scripts/remote_release_to_droplet.sh ...` run, the Docker image transfer step now shows a live progress bar when `pv` is installed locally:

```bash
brew install pv
```

Without `pv`, the release still works; it just streams the image without a progress bar.

---

## Demo FIX Client

A lightweight demo FIX initiator is included for smoke testing and load generation.
It connects to the simulator and continuously sends `NewOrderSingle` messages at a
fixed rate until stopped.

```bash
./scripts/fix_demo_client_start.sh 100          # start at 100 NewOrderSingles / second
./scripts/fix_demo_client_start.sh 500          # restart at 500 msg/s
docker compose logs -f fix-demo-client          # tail client progress
./scripts/fix_demo_client_stop.sh               # stop the background client
./scripts/local_stop_all.sh                     # stop both the background client and Docker simulator
./scripts/local_clean_ledgers.sh                # clear client/session ledgers and runtime state
```

The same start/stop scripts work both:

- in your local repo checkout on macOS/Linux, and
- on the droplet under `/opt/llexsimulator/scripts/` after a release.

No localhost or droplet hostname argument is needed in the normal Docker workflow because the client connects to the simulator using the internal Compose service name `llexsimulator`.

Local helpers also honor `FIX_DEMO_RATE` when no explicit positional rate is passed:

```bash
FIX_DEMO_RATE=500 ./scripts/fix_demo_client_start.sh
FIX_DEMO_RATE=500 ./scripts/local_clean_and_run.sh
FIX_DEMO_RATE=500 ./scripts/local_rebuild_and_run.sh
```

Run it in the foreground if you want to see connection and progress messages live:

```bash
./scripts/local_fix_demo_client.sh run 250
```

Optional environment overrides:

```bash
FIX_CLIENT_BEGIN_STRING=FIX.4.4 \
FIX_CLIENT_SENDER_COMP_ID=CLIENT1 \
FIX_CLIENT_TARGET_COMP_ID=LLEXSIM \
FIX_CLIENT_SYMBOL=MSFT \
FIX_CLIENT_ORDER_QTY=250 \
FIX_CLIENT_PRICE=412.15 \
./scripts/fix_demo_client_start.sh 1000
```

The demo client uses the same Docker image as the simulator, so the required JVM/module flags are already baked into the container runtime workflow.

The demo client writes logs under `logs/fix-demo-client/` inside the shared Docker volume and keeps its QuickFIX/J session logs under `logs/fix-demo-client/quickfixj/`.

---

## Disconnect Troubleshooting

When a disconnect happens, capture the simulator and client evidence immediately:

```bash
./scripts/local_capture_disconnect_evidence.sh
```

This writes a timestamped bundle under `logs/disconnect-evidence/` containing:

- `recent-disconnects.json` — sticky simulator-side disconnect archive from `/api/sessions/recent-disconnects`
- `sessions.json` — currently active session diagnostics from `/api/sessions`
- `health.json` — health snapshot from `/api/health`
- `simulator-log-tail.txt` — last 200 lines of `logs/llexsimulator.log`
- `client-console-log-tail.txt` — last 200 lines of `logs/fix-demo-client/console.log`
- `client-main-log-tail.txt` — last 200 lines of `logs/fix-demo-client/fix-demo-client.log`
- `client-log-files.txt` — inventory of client-side QuickFIX/J/session log files

Useful overrides:

```bash
EVIDENCE_WEB_PORT=9090 EVIDENCE_LIMIT=20 EVIDENCE_LOG_LINES=400 ./scripts/local_capture_disconnect_evidence.sh
```

If you want a custom output directory:

```bash
./scripts/local_capture_disconnect_evidence.sh /tmp/llex-disconnect-$(date +%Y%m%d-%H%M%S)
```

The first files to share for diagnosis are:

- `recent-disconnects.json`
- `sessions.json`
- `health.json`
- `simulator-log-tail.txt`
- `client-console-log-tail.txt`

---

## Configuration

Configuration is loaded from `src/main/resources/simulator.properties` (or override by mounting `./config/simulator.properties` into the Docker container):

```properties
# FIX Engine
fix.host=0.0.0.0
fix.port=9880
fix.log.dir=logs/quickfixj
fix.raw.message.logging.enabled=false # enable only for debugging raw FIX I/O

# Disruptor
ring.buffer.size=131072          # Must be power of 2
wait.strategy=BUSY_SPIN          # BUSY_SPIN (lowest latency) or SLEEPING (lower CPU)

# Order Repository (pre-allocated pool)
order.pool.size=131072

# Web Server
web.port=8080

# Benchmark Mode
benchmark.mode.enabled=false     # true = keep REST/health, disable live Aeron/WebSocket metrics fan-out

# Aeron IPC
aeron.dir=/dev/shm/aeron-llexsim              # Falls back to /tmp/aeron-llexsim when /dev/shm is unavailable/too small
artio.library.aeron.channel=aeron:ipc?term-length=8388608
metrics.aeron.channel=aeron:ipc?term-length=65536

# Metrics
metrics.publish.interval=500     # Publish WebSocket snapshot every N orders
```

### Override at Docker runtime

Mount a custom config without rebuilding:

```bash
# Edit ./config/simulator.properties, then:
./scripts/local_llexsim.sh restart
```

The `docker-compose.yml` mounts `./config/` as a read-only volume inside the container.

For lower-noise latency runs, set `benchmark.mode.enabled=true` in `./config/simulator.properties` and restart the simulator. This keeps the REST/health endpoints available but disables the live Aeron/WebSocket metrics fan-out used by the GUI.

### Benchmark Runs

Use the benchmark runners instead of toggling `benchmark.mode.enabled` manually. They enable benchmark mode temporarily, run the demo client at the requested rate, capture logs/statistics, generate a colorful HTML report, and then restore the original config/state.

#### Local benchmark run

```bash
./scripts/run_benchmark_local.sh
./scripts/run_benchmark_local.sh 500 30
./scripts/run_benchmark_local.sh --build --rate 1000 --duration 60
./scripts/run_benchmark_local.sh --rate 2000 --duration 120
```

Defaults:

```text
rate     = 500 msg/s
duration = 30 s
```

#### Droplet benchmark run

SSH into the droplet first and run the benchmark from the deployed app directory:

```bash
ssh -i ~/.ssh/<your-private-key> root@203.0.113.10
cd /opt/llexsimulator

./scripts/run_benchmark_droplet.sh
./scripts/run_benchmark_droplet.sh 500 30
./scripts/run_benchmark_droplet.sh --rate 1000 --duration 60
```

#### What gets captured

Each benchmark run saves a timestamped artifact bundle under:

```text
logs/benchmark-reports/<timestamp>/
```

The bundle includes:

- `health.json`
- `statistics.json`
- `docker-stats.txt`
- `docker-compose-ps.txt`
- simulator/client log tails
- simulator/client Docker logs
- `metadata.txt`
- `report.html` — a colorful self-contained HTML summary report

Examples:

```text
Local:   /Users/<you>/.../LLExSimulator/logs/benchmark-reports/<timestamp>/report.html
Droplet: /opt/llexsimulator/logs/benchmark-reports/<timestamp>/report.html
```

#### Viewing reports in the browser

When the simulator web app is running, you can browse generated reports at:

```text
http://localhost:8080/reports
```

For a droplet deployment:

- if the web app is exposed publicly, open your droplet hostname/IP plus `/reports`
- if the web app is local-only on the droplet, SSH in and use `curl http://127.0.0.1:8080/reports`, or expose it through your existing HTTPS/Nginx setup

Example:

```text
https://sim.example.com/reports
```

---

## REST API Reference

Base URL: `http://localhost:8080`

### Health

```
GET /api/health
```
```json
{ "status": "UP", "fixSessions": 2, "disruptorRemainingCapacity": 130048 }
```

### Statistics

```
GET /api/statistics
```
```json
{
  "ordersReceived": 125000,
  "execReportsSent": 124850,
  "fillsSent": 120000,
  "rejectsSent": 4850,
  "p50LatencyUs": 12,
  "p99LatencyUs": 45,
  "p999LatencyUs": 180,
  "maxLatencyUs": 2100,
  "throughputPerSec": 38400,
  "fillRatePct": 96.1,
  "activeProfile": "immediate-full-fill"
}
```

### Fill Profiles

```
GET    /api/fill-profiles                  → List all profiles
POST   /api/fill-profiles                  → Create or update a profile
PUT    /api/fill-profiles/:name/activate   → Set active profile (live switch)
DELETE /api/fill-profiles/:name            → Delete a profile
```

**Create / update profile body:**
```json
{
  "name": "slow-partial",
  "description": "50% fill after 5 ms delay",
  "behaviorType": "DELAYED_FILL",
  "fillPctBps": 5000,
  "numPartialFills": 1,
  "delayMs": 5,
  "rejectReason": null,
  "randomMinQtyPct": 0,
  "randomMaxQtyPct": 100,
  "randomMinDelayMs": 0,
  "randomMaxDelayMs": 0,
  "priceImprovementBps": 0
}
```

### Sessions

```
GET    /api/sessions          → List active FIX sessions
DELETE /api/sessions/:id      → Force-disconnect a session
```

### Usage Examples (curl)

```bash
# Check health
curl http://localhost:8080/api/health

# Get current statistics
curl http://localhost:8080/api/statistics | python3 -m json.tool

# List fill profiles
curl http://localhost:8080/api/fill-profiles | python3 -m json.tool

# Create a "reject-all" profile
curl -X POST http://localhost:8080/api/fill-profiles \
  -H "Content-Type: application/json" \
  -d '{"name":"reject-all","description":"Reject everything","behaviorType":"REJECT",
       "fillPctBps":0,"numPartialFills":0,"delayMs":0,"rejectReason":"SIMULATOR_REJECT",
       "randomMinQtyPct":0,"randomMaxQtyPct":100,"randomMinDelayMs":0,"randomMaxDelayMs":0,
       "priceImprovementBps":0}'

# Activate the profile (takes effect immediately — next order onwards)
curl -X PUT http://localhost:8080/api/fill-profiles/reject-all/activate

# Switch back to immediate fills
curl -X PUT http://localhost:8080/api/fill-profiles/immediate-full-fill/activate

# List FIX sessions
curl http://localhost:8080/api/sessions | python3 -m json.tool
```

---

## WebSocket Events

Connect to `ws://localhost:8080/ws` to receive real-time JSON messages.

### Metrics Snapshot (published every N orders)

```json
{
  "type": "metrics",
  "snapshotTimeNs": 1741776000000000000,
  "ordersReceived": 50000,
  "execReportsSent": 49900,
  "fills": 48000,
  "rejects": 1900,
  "p50Us": 11,
  "p99Us": 42,
  "p999Us": 175,
  "maxUs": 1800,
  "throughputPerSec": 42000
}
```

### WebSocket client example (JavaScript)

```javascript
const ws = new WebSocket('ws://localhost:8080/ws');
ws.onmessage = ({ data }) => {
  const msg = JSON.parse(data);
  if (msg.type === 'metrics') {
    console.log(`p99: ${msg.p99Us} µs | tps: ${msg.throughputPerSec}`);
  }
};
```

---

## Web UI

Open **http://localhost:8080** in your browser.

| Panel | Description |
|---|---|
| **Stats Cards** | Live throughput/sec, p99 latency, fill rate %, reject rate % |
| **p99 Latency Chart** | Rolling 60-point line chart, with selectable dashboard refresh cadence: 1 / 5 / 10 / 30 / 60 seconds |
| **Reset Metrics** | One-click reset of counters and latency history before a fresh test/demo-client run |
| **Fill Profile Panel** | Select, create, update, and activate fill-behavior profiles |
| **Order Flow Table** | Last 100 orders with flash-on-arrival highlighting (green=fill, red=reject) |
| **FIX Sessions** | Active sessions with disconnect button |

---

## FIX Session Configuration

QuickFIX/J session settings are in `src/main/resources/quickfixj.cfg`.

### Default Sessions

| BeginString | SenderCompID | TargetCompID | Version |
|---|---|---|---|
| `FIX.4.2` | `LLEXSIM` | `CLIENT1` | FIX 4.2 |
| `FIX.4.4` | `LLEXSIM` | `CLIENT1` | FIX 4.4 |
| `FIXT.1.1` | `LLEXSIM` | `CLIENT1` | FIX 5.0 |
| `FIXT.1.1` | `LLEXSIM` | `CLIENT2` | FIX 5.0 SP2 |

### Adding a New Session

Add a `[SESSION]` block to `quickfixj.cfg`:

```ini
[SESSION]
BeginString=FIX.4.4
SenderCompID=LLEXSIM
TargetCompID=MY_TEST_CLIENT
DataDictionary=FIX44.xml
```

Then `./scripts/local_llexsim.sh restart`.

### Supported FIX Message Types

| Tag 35 | Name | Direction |
|---|---|---|
| `D` | NewOrderSingle | Client → Simulator |
| `F` | OrderCancelRequest | Client → Simulator |
| `8` | ExecutionReport | Simulator → Client |

---

## Performance Tuning

### JVM Flags (applied in `docker-compose.yml` / `Dockerfile`)

| Flag | Purpose |
|---|---|
| `-XX:+UseZGC -XX:+ZGenerational` | Java 21 Generational ZGC — sub-millisecond GC pauses |
| `-Xms512m -Xmx512m` | Fixed heap; eliminates heap resize safepoints |
| `-XX:+AlwaysPreTouch` | Pre-fault heap pages at startup (avoids latency spikes) |
| `-XX:+DisableExplicitGC` | Prevents `System.gc()` calls from libraries |
| `-XX:+PerfDisableSharedMem` | Disables JMX perf shared memory overhead |
| `-Daeron.threading.mode=DEDICATED` | Dedicated sender/receiver/conductor threads |
| `-Daeron.sender.idle.strategy=noop` | Busy-poll sender (lowest possible latency) |
| `-Dagrona.disable.bounds.checks=true` | Skip `UnsafeBuffer` bounds checks in production |

### CPU Affinity (bare metal)

In `docker-compose.yml`, the `cpuset: "0-3"` pins the container to the first 4 cores. Adjust for your NUMA topology.

For bare-metal JVM runs:
```bash
# Pin to cores 0-3, NUMA node 0
numactl --cpunodebind=0 --membind=0 java $JAVA_OPTS -jar build/libs/LLExSimulator-1.0-SNAPSHOT.jar
```

### Wait Strategy

```properties
# simulator.properties
wait.strategy=BUSY_SPIN    # Lowest latency, uses 100% of one CPU core
# wait.strategy=SLEEPING   # Lower CPU; adds ~100 µs worst-case latency
```

### Ring Buffer Size

Must be a power of 2. Increase if you see `Order pool exhausted` in logs:
```properties
ring.buffer.size=262144   # 256k slots
order.pool.size=32768     # must match or exceed expected concurrent in-flight orders
```

---

## Project Structure

```
LLExSimulator/
├── build.gradle.kts                    # Gradle build (Java 21 toolchain, Shadow plugin, SBE codegen)
├── docker-compose.yml                  # Docker Compose with ZGC JVM tuning + CPU pinning
├── Dockerfile                          # Multi-stage: Gradle build → lean JRE runtime
├── scripts/
│   ├── local_llexsim.sh                # Local Docker lifecycle manager (start/stop/restart/clean/purge...)
│   ├── local_fix_demo_client.sh        # Local demo FIX client lifecycle helper
│   ├── local_stop_all.sh               # Convenience wrapper: stop demo client + simulator together
│   ├── local_clean_ledgers.sh          # Removes FIX/Aeron ledger/state directories only
│   ├── remote_release_to_droplet.sh    # Build locally and deploy remotely to a droplet
│   ├── remote_render_release_remote_script.py # Generates the remote deploy shell script
│   ├── remote_setup_droplet_for_docker.sh # Bootstrap a droplet with Docker
│   └── remote_setup_https_for_hostname.sh # Configure Nginx + Certbot on the droplet
├── config/
│   └── simulator.properties            # Override config (mounted read-only into container)
└── src/main/
    ├── java/com/llexsimulator/
    │   ├── Main.java                   # Entry point; sets Aeron/Agrona system properties
    │   ├── SimulatorBootstrap.java     # Orchestrates startup/shutdown in correct order
    │   ├── engine/
    │   │   ├── FixEngineManager.java   # QuickFIX/J SocketAcceptor lifecycle
    │   │   ├── FixSessionApplication.java  # Application callbacks; publishes to Disruptor
    │   │   └── OrderSessionRegistry.java   # Maps numeric connId → QuickFIX/J SessionID
    │   ├── order/
    │   │   ├── OrderState.java         # Off-heap order state (Agrona UnsafeBuffer, 256 bytes)
    │   │   ├── OrderRepository.java    # Pre-allocated pool + Long2ObjectHashMap
    │   │   ├── OrderIdGenerator.java   # Zero-GC ID generation (no String allocation)
    │   │   └── ExecIdGenerator.java    # Same as above for ExecID
    │   ├── disruptor/
    │   │   ├── OrderEvent.java         # Pre-allocated ring-buffer slot (off-heap SBE buffers)
    │   │   ├── OrderEventFactory.java  # Allocates all slots once at startup
    │   │   ├── OrderEventTranslator.java  # Translates QFJ Message → SBE-encoded OrderEvent
    │   │   ├── DisruptorPipeline.java  # Wires up the 4-stage handler chain
    │   │   └── handler/
    │   │       ├── ValidationHandler.java      # Stage 1: field validation
    │   │       ├── FillStrategyHandler.java    # Stage 2: strategy selection + OrderState claim
    │   │       ├── ExecutionReportHandler.java # Stage 3: build + send FIX 35=8
    │   │       └── MetricsPublishHandler.java  # Stage 4: latency recording + Aeron publish
    │   ├── fill/
    │   │   ├── FillStrategy.java            # Interface: apply(event, config)
    │   │   ├── FillBehaviorConfig.java      # Volatile-field config snapshot (1 volatile read on hot path)
    │   │   ├── FillStrategyFactory.java     # Pre-created strategy instances (EnumMap)
    │   │   ├── FillProfileManager.java      # Named profiles; REST-writable
    │   │   └── strategy/                    # 9 stateless strategy implementations
    │   │       ├── ImmediateFillStrategy.java
    │   │       ├── PartialFillStrategy.java
    │   │       ├── DelayedFillStrategy.java
    │   │       ├── RejectStrategy.java
    │   │       ├── PartialFillThenCancelStrategy.java
    │   │       ├── PriceImprovementFillStrategy.java
    │   │       ├── FillAtArrivalPriceStrategy.java
    │   │       ├── RandomFillStrategy.java
    │   │       └── IocCancelStrategy.java
    │   ├── aeron/
    │   │   ├── AeronContext.java        # Embedded MediaDriver (DEDICATED threading)
    │   │   ├── MetricsPublisher.java    # Aeron Publication on aeron:ipc stream 1002
    │   │   └── MetricsSubscriber.java  # Aeron Subscription → WebSocket broadcast
    │   ├── metrics/
    │   │   ├── MetricsRegistry.java     # HdrHistogram + LongAdder counters
    │   │   └── ThroughputTracker.java   # 1-second sliding window TPS
    │   ├── web/
    │   │   ├── WebServer.java           # Vert.x HTTP server (native transport)
    │   │   ├── RestApiRouter.java       # All REST routes
    │   │   ├── WebSocketBroadcaster.java # CopyOnWriteArraySet of connected clients
    │   │   ├── handler/                 # REST endpoint handlers
    │   │   └── dto/                     # JSON DTOs (FillProfileDto, StatisticsDto)
    │   └── config/
    │       ├── SimulatorConfig.java     # Immutable config record
    │       ├── ConfigLoader.java        # Loads simulator.properties from classpath
    │       └── FillBehaviorProfile.java # Named profile record
    └── resources/
        ├── sbe/fix-messages.xml        # SBE schema (5 messages; codecs generated at build time)
        ├── quickfixj.cfg               # QuickFIX/J session settings
        ├── simulator.properties        # Runtime configuration
        ├── logback.xml                 # Async logging (neverBlock=true, immediateFlush=false)
        └── web/
            ├── index.html              # SPA shell (loads Vue 3 + Tailwind + Chart.js from CDN)
            └── app.js                  # Vue 3 Composition API application
```

---

## License

MIT — use freely for performance testing, development, and research.

