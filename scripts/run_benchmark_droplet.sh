#!/usr/bin/env bash
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCHMARK_INVOCATION="./scripts/run_benchmark_droplet.sh"
# shellcheck source=./benchmark_common.sh
source "${SCRIPTS_DIR}/benchmark_common.sh"

parse_benchmark_args "$@"
run_benchmark_flow "Run Benchmark (Droplet)"

