#!/bin/bash
# Environment A: dev profile, the disposable marked database of this harness, fictional fixtures. HTTP only.
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"; . "$HERE/env/secrets.env"
cd "$REPO/backend"
exec env -i PATH="$PATH" HOME="$HOME" APV_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/apv APV_DATABASE_USER=apv APV_DATABASE_PASSWORD="$DEV_DB_PASSWORD" \
  APV_PORT=8080 APV_FIXTURES_PATH=../contract/fixtures/dataset.json APV_FIXTURES_MEDIA_ROOT=../contract/fixtures \
  java -jar target/apv-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev
