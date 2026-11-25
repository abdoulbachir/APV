#!/bin/bash
# Environment B: no dev or test profile, so every production rule applies. Its own unmarked database,
# test-only keys, the synthetic production-size list. Arguments are passed on: "operator <command>".
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"; . "$HERE/env/secrets.env"
cd "$REPO/backend"
exec env -i PATH="$PATH" HOME="$HOME" APV_DATABASE_URL=jdbc:postgresql://127.0.0.1:55433/apv APV_DATABASE_USER=apv APV_DATABASE_PASSWORD="$PROD_DB_PASSWORD" \
  APV_PORT=8081 APV_MFA_ENCRYPTION_KEY="$APV_MFA_ENCRYPTION_KEY" APV_HASH_KEY="$APV_HASH_KEY" \
  APV_PASSWORD_BLOCKLIST_PATH="$HERE/env/blocklist/synthetic.bin" APV_ALLOWED_ORIGINS=https://localhost:8443 \
  java -jar target/apv-backend-0.1.0-SNAPSHOT.jar "$@"
