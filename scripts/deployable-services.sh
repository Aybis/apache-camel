#!/usr/bin/env bash
# Print the services that may be deployed beyond a developer machine or test environment:
# every registry entry except those marked `local-only: true`. Production manifests, image
# publishing and CI release jobs must take their service list from here, never from services/.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
awk '
  function emit() { if (name != "" && localonly != "true") print name }
  /^  - name:/ { emit(); name=$3; localonly="false" }
  /^    local-only:/ { localonly=$2 }
  END { emit() }
' "$ROOT/config/services.yml"
