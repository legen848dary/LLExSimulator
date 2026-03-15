#!/usr/bin/env bash
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./fix_demo_client_common.sh
source "${SCRIPTS_DIR}/fix_demo_client_common.sh"

RATE="${1:-${FIX_DEMO_RATE:-100}}"

if [[ $# -gt 1 ]]; then
    error "Usage: ./scripts/fix_demo_client_start.sh [rate-per-second]"
    exit 1
fi

start_fix_demo_client "${RATE}"

