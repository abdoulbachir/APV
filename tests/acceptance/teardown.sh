#!/bin/bash
# Removes what setup.sh created, by exact name and port, and nothing else: the four processes, the two
# containers and their two volumes, and the generated env/ folder. evidence/ is kept.
HERE="$(cd "$(dirname "$0")" && pwd)"
for port in 8080 8081 4173 8443; do
  pid="$(ss -ltnp 2>/dev/null | grep -E ":$port\s" | sed -nE 's/.*pid=([0-9]+).*/\1/p' | head -1)"
  # Only a process this harness starts: the backend jar, the preview server or the HTTPS front.
  if [ -n "$pid" ] && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q -E 'apv-backend|vite\.js preview|tls-proxy\.mjs'; then kill "$pid" && echo "Stopped the process on port $port."; fi
done
docker rm -f apv-m1b-accept-dev apv-m1b-accept-prod 2>/dev/null
docker volume rm apv-m1b-accept-dev apv-m1b-accept-prod 2>/dev/null
rm -rf "$HERE/env"
echo "Removed. Logs and results remain in $HERE/evidence."
