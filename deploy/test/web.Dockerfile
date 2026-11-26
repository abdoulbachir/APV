# The frontend of the test site: the real production build, served by Caddy with TLS.
# Build context: the repository root.
FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# The default build is the real one: it talks to the backend and contains no sample data.
RUN npm run build && npm run test:production-build
RUN node deploy/test/write-policy-headers.mjs /tmp/policy-headers.caddy

FROM caddy:2.10-alpine
COPY --from=build /app/dist /srv
COPY --from=build /tmp/policy-headers.caddy /etc/caddy/policy-headers.caddy
COPY deploy/test/Caddyfile /etc/caddy/Caddyfile
