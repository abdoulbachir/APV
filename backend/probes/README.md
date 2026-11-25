# Identity compatibility probes (milestone 1B, phase 1)

Executable answers to the four questions ADR 0002 left "not verified by running". This is a separate, throwaway Maven project. It is **not** the identity implementation, it is never deployed, and nothing in it is on the backend's classpath: the accepted backend builds and passes its 114 tests without any of these dependencies.

It uses the same Spring Boot parent as the backend, so the versions it resolves are the ones the backend will get when identity is implemented.

## Run

```bash
cd backend
./mvnw -f probes/pom.xml test      # no database: probes 1, 3, 4 and the clock-driven half of 2
./mvnw -f probes/pom.xml verify    # adds PostgreSQL 18.7 through Testcontainers (Docker required)
```

`verify` writes the resolved versions and the Argon2 timings to `probes/target/probe-results/`. CI runs it in the "Backend checks" workflow and publishes both as notices on the run.

## What the probe application is

A minimal Spring Boot application with three fictional in-memory accounts (a donor, an enrolled administrator, an administrator with no second factor) and a handful of `/probe/...` endpoints, just enough to drive every session transition of the contract over real HTTP. Its paths are not the contract's. It has no rate limits, no audit, no migrations and no account storage.

| File | Purpose |
|---|---|
| `ProbeSecurityConfig` | The filter chain: session-stored CSRF, deny by default, Spring Security's multi-factor authorization |
| `SessionTransitions` | The one place a session changes privilege: new identifier, new CSRF token, own idle timeout, absolute deadline |
| `SessionDeadlineFilter` | Ends a session past its absolute deadline, before the CSRF check |
| `Totp` | RFC 6238 on the JDK alone |
| `ProbeController` | Sign-in, second factor, enrollment, password change, sign-out |

## Tests

| Class | Runs with | Tests |
|---|---|---|
| `MemoryProbeTest` | The embedded server's own sessions, no database | 22 scenarios |
| `PostgresProbeIT` | Spring Session JDBC on PostgreSQL 18.7 (Testcontainers) | The same 22 scenarios, plus 7 that need the database |
| `Argon2ProbeTest` | No Spring context | 6 |
| `TotpTest` | No Spring context | 2: the RFC 6238 vectors, and the one-step window |

Every scenario uses a real HTTP client against a real port, with the session cookie handled the way a browser handles it.

## Results

Recorded in `docs/adr/0002-identity-and-sessions.md`, section "Compatibility probes", with the commit and CI run they come from.
