# M0B joint integration acceptance

Use the temporary `integration/m0b-public-read` branch. It combines reviewed frontend PR #4 and backend PR #5. Do not merge into main before joint acceptance and owner approval.

The integration workflow starts a fresh CI-only PostgreSQL database and the packaged Spring Boot app with the full fictional development dataset. It uses the actual real-mode frontend adapter, loaded through Vite in Node, to compare responses over HTTP through the same-origin development proxy. It does not mock the network or launch a browser. Media tokens differ between the two implementations, so response comparisons mask only those tokens and separately verify actual image bytes and headers.

## Local startup

Requires Node 24, Java 21 and a running Docker engine. Use only the isolated local development database: the dev profile replaces its data. Preserve any existing .env; do not overwrite real credentials or connect the development profile to a real database.

From the repository root:

```bash
npm ci
npm run fixtures:generate
test -e .env || cp .env.example .env
docker compose up -d --wait
```

In a backend terminal, from the repository root:

```bash
cd backend
set -a; . ../.env; set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

In another terminal, from the repository root:

```bash
node --experimental-strip-types tests/live-api.mjs
VITE_API_MODE=real VITE_SAMPLE_DATA=true npm run dev
```

Open the loopback address printed by Vite. The React app uses /api and /media/public through the proxy to Spring Boot at 127.0.0.1:8080.

Browser acceptance remains separate: check all three approved pages in EN/FR, light/dark, mobile/desktop, real requests and public images, funding and history, loading/errors, keyboard focus, and that backend unavailability never falls back to the mock. The fixture clock is pinned and data is fictional. No authentication, payment or write endpoints are in scope.

Stop the frontend and backend with Ctrl+C; then `docker compose down` keeps the development database volume. Do not use -v unless intentionally deleting the isolated fixture database.
