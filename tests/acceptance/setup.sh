#!/bin/bash
# Creates everything the acceptance run needs, all of it new and disposable:
#   two PostgreSQL 18.7 containers with their own names, ports and volumes
#     apv-m1b-accept-dev   127.0.0.1:55432  marked disposable at creation (fixtures, dev accounts)
#     apv-m1b-accept-prod  127.0.0.1:55433  never marked (production rules, operator commands)
#   test-only database passwords and keys, a self-signed certificate for https://localhost:8443,
#   a synthetic breached-password list of production size (2 GB, not breach data),
#   the backend jar, the real-mode frontend build, the two backends, the preview server and the HTTPS front.
# It never touches a database it did not create: it refuses to run when either container or volume exists.
# Needs: Docker, Java 21, Node 24, openssl. Run from anywhere; teardown.sh removes all of it.
set -Eeuo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
for port in 8080 8081 4173 8443 55432 55433; do
  if ss -ltn 2>/dev/null | grep -q -E ":$port\s"; then echo "Refused: port $port is in use. Stop what is listening there, or run teardown.sh if it is from an earlier run." >&2; exit 1; fi
done
for name in apv-m1b-accept-dev apv-m1b-accept-prod; do
  if docker ps -a --format '{{.Names}}' | grep -qx "$name" || docker volume ls -q | grep -qx "$name"; then
    echo "Refused: a container or volume named $name already exists. Run teardown.sh first if it is from an earlier run." >&2; exit 1
  fi
done
mkdir -p "$HERE/env/blocklist" "$HERE/env/evidence-prod" "$HERE/env/evidence-dev" "$HERE/evidence"; ( umask 077; cat > "$HERE/env/secrets.env" <<SECRETS
DEV_DB_PASSWORD=$(openssl rand -hex 16)
PROD_DB_PASSWORD=$(openssl rand -hex 16)
APV_MFA_ENCRYPTION_KEY=$(openssl rand -base64 32)
APV_HASH_KEY=$(openssl rand -base64 32)
SECRETS
); . "$HERE/env/secrets.env"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$HERE/env/tls.key" -out "$HERE/env/tls.crt" -days 2 -subj /CN=localhost -addext subjectAltName=DNS:localhost 2>/dev/null

docker run -d --name apv-m1b-accept-dev -e POSTGRES_DB=apv -e POSTGRES_USER=apv -e POSTGRES_PASSWORD="$DEV_DB_PASSWORD" -p 127.0.0.1:55432:5432 -v apv-m1b-accept-dev:/var/lib/postgresql postgres:18.7 >/dev/null
docker run -d --name apv-m1b-accept-prod -e POSTGRES_DB=apv -e POSTGRES_USER=apv -e POSTGRES_PASSWORD="$PROD_DB_PASSWORD" -p 127.0.0.1:55433:5432 -v apv-m1b-accept-prod:/var/lib/postgresql postgres:18.7 >/dev/null
for name in apv-m1b-accept-dev apv-m1b-accept-prod; do
  for attempt in $(seq 1 60); do sleep 2; docker exec "$name" psql -U apv apv -Atc 'select 1' >/dev/null 2>&1 && break; done
done; sleep 2
# The marker is written here, at creation, into the database this script has just created and found empty.
[ "$(docker exec apv-m1b-accept-dev psql -U apv apv -Atc "select count(*) from information_schema.tables where table_schema='public'")" = 0 ]
docker exec -i apv-m1b-accept-dev psql -U apv apv -q < "$REPO/backend/dev/initdb/01-disposable-database.sql"

( cd "$REPO" && npm ci --no-audit --no-fund >/dev/null && npm run -s fixtures:generate >/dev/null && npm run -s build >/dev/null )
( cd "$REPO/backend" && ./mvnw --batch-mode --no-transfer-progress -q -DskipTests package )
( cd "$HERE" && npm ci --no-audit --no-fund >/dev/null )
# The project's own test generator: one hundred million evenly spread values that are the hash of nothing,
# plus the hashes of four well-known passwords and one fictional passphrase.
printf 'org.apvgakasinay.support.TestBlocklist.write(java.nio.file.Path.of("%s"), 100_000_000L, java.util.List.of("password","123456","qwerty","iloveyou","breached-example-passphrase-2026"), "apv-sha1-sorted-v1");\n/exit\n' "$HERE/env/blocklist/synthetic.bin" > "$HERE/env/generate-list.jsh"
( cd "$REPO/backend" && jshell --class-path target/test-classes "$HERE/env/generate-list.jsh" >/dev/null && java -jar target/apv-backend-0.1.0-SNAPSHOT.jar blocklist verify "$HERE/env/blocklist/synthetic.bin" )

nohup "$HERE/start-dev.sh"  > "$HERE/evidence/backend-dev.log"  2>&1 &
nohup "$HERE/start-prod.sh" > "$HERE/evidence/backend-prod.log" 2>&1 &
( cd "$REPO" && nohup node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port 4173 --strictPort > "$HERE/evidence/preview.log" 2>&1 & )
( cd "$HERE" && nohup node tls-proxy.mjs "$REPO/dist" "$REPO/security/content-policy.mjs" "$HERE/env/tls.key" "$HERE/env/tls.crt" 8443 8081 > "$HERE/evidence/tls-proxy.log" 2>&1 & )
for attempt in $(seq 1 90); do
  sleep 2
  [ "$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:4173/api/site)" = 200 ] && [ "$(curl -sk --max-time 3 -o /dev/null -w '%{http_code}' https://localhost:8443/api/site)" = 200 ] && { echo "Ready: http://127.0.0.1:4173 (A) and https://localhost:8443 (B)"; exit 0; }
done
echo "Not ready after three minutes; see $HERE/evidence/*.log" >&2; exit 1
