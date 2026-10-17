#!/usr/bin/env bash
set -euo pipefail
export TRAVELGO_CLOUD_EVALUATION_INITIALIZER=1
exec /app/render-entrypoint.sh "$@"
