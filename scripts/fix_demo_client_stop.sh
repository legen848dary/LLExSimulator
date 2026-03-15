#!/usr/bin/env bash
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./fix_demo_client_common.sh
source "${SCRIPTS_DIR}/fix_demo_client_common.sh"

if [[ $# -ne 0 ]]; then
    error "Usage: ./scripts/fix_demo_client_stop.sh"
    exit 1
fi

stop_fix_demo_client

