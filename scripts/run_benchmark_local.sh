#!/usr/bin/env bash
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCHMARK_INVOCATION="./scripts/run_benchmark_local.sh"
# shellcheck source=./benchmark_common.sh
source "${SCRIPTS_DIR}/benchmark_common.sh"

BUILD_FIRST=false
PASSTHROUGH_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --build)
            BUILD_FIRST=true
            shift
            ;;
        *)
            PASSTHROUGH_ARGS+=("$1")
            shift
            ;;
    esac
done

if [[ ${#PASSTHROUGH_ARGS[@]} -gt 0 ]]; then
    parse_benchmark_args "${PASSTHROUGH_ARGS[@]}"
else
    parse_benchmark_args
fi

if [[ "${BUILD_FIRST}" == true ]]; then
    info "Building the latest local Docker image before benchmarking..."
    "${SCRIPTS_DIR}/local_llexsim.sh" build
fi

run_benchmark_flow "Run Benchmark (Local)"

