# Private test site under production rules

Runs the whole application in Docker on one machine, the way production would behave: HTTPS, no development profile, no fictional data, public sign-up closed, staff accounts created with the operator command. It is for testing by hand (`M1B Manual Test Checklist`) before any real deployment.

**It is not the production deployment.** Backups, monitoring, updates, a separate database role and hosting are not addressed here. Wipe it when testing is over.

Three containers: PostgreSQL, the backend, and a web container (Caddy) that serves the built frontend with the project's policy headers, ends TLS and forwards `/api` and `/media/public` to the backend. Only the web container is reachable from outside.

## Before the first start

1. **The breached-password list.** The backend refuses to start without it under production rules. Build it once as `docs/backend-password-blocklist.md` describes (a download of a few tens of gigabytes) and put the `.bin` file and its `.manifest` in one folder on this machine.
2. **The name testers will type.** It must be the same for everyone and must reach this machine: a name in your home DNS, or a public name. The backend accepts sign-in only from that exact origin.
3. **Settings.** From the repository root:

   ```bash
   cp deploy/test/.env.example deploy/test/.env
   chmod 600 deploy/test/.env
   ```

   Fill in every line. `deploy/test/.env` is ignored by git; never commit it.

## Start

```bash
docker compose --env-file deploy/test/.env -f deploy/test/compose.yaml up -d --build
docker compose --env-file deploy/test/.env -f deploy/test/compose.yaml ps
```

The first build takes a few minutes. The backend checks the whole list at every start, which takes some seconds; the web container waits for it.

The commands below shorten the first part to `dc`:

```bash
alias dc='docker compose --env-file deploy/test/.env -f deploy/test/compose.yaml'
```

## Certificates

- **`APV_TLS=internal` (default).** The web container makes its own private certificate authority. Browsers warn until its root certificate is installed on each test device, and some features (saving passwords on iPhone, for one) need it installed. Copy it out once, after the first start:

  ```bash
  dc cp web:/data/caddy/pki/authorities/local/root.crt ./apv-test-root.crt
  ```

  Install it on each device as a trusted root (on iPhone also enable it under Settings, General, About, Certificate Trust Settings). Remove it from the devices when testing is over. The file is not secret, but a device that trusts it trusts anything this test server signs: keep the `apv-test-web` volume private.
- **`APV_TLS=you@example.org`.** For a public name whose ports 80 and 443 reach this machine: a public certificate is obtained automatically and nothing is installed on devices.

## The first administrator

Staff accounts are never created over the web. With the site running:

```bash
dc run --rm backend operator create-super-admin
```

It asks for the email and the display name on the terminal and prints a one-time credential, valid 60 minutes. Open `https://<your name>/auth/set-password`, enter the credential, choose a password, then set up the authenticator. Other commands (`create-admin`, `issue-password-reset`, `recover-staff-access`, `list-staff`...) work the same way; see `backend/README.md`.

The first operator command marks the database as real, for ever.

## What testers will see

- No cases: this database holds no fictional data, and none can be loaded into it. Public pages are empty until case administration exists (a later milestone).
- "Create an account" is closed: the backend cannot verify email addresses yet. Donor accounts cannot be created on this site; test sign-in with staff accounts.
- Sessions end after 30 minutes without activity for staff.

## Known limits of this setup

- **Client addresses.** Rate limits count per client address. On Docker Desktop, and with some Docker network settings, every client appears to the site under one internal address, so testers share one budget: five wrong passwords by one tester can lock out the others for up to 15 minutes. On a Linux server with the default Docker engine the real addresses are normally seen. Check with `dc exec postgres psql -U apv apv -c "select distinct client_address from audit_event"`.
- **Another HTTPS port.** If `APV_HTTPS_PORT` is not 443, put the port in `APV_PUBLIC_ORIGIN` and have testers type the full `https://name:port` address: the redirect from plain HTTP goes to port 443.
- **The database role owns its tables**, as in development. A separate role is a decision for the real deployment.
- No backup. Losing the `apv-test-postgres` volume or `APV_MFA_ENCRYPTION_KEY` loses the accounts.

## Logs

```bash
dc logs -f backend
dc logs -f web
```

## Wipe everything

```bash
dc down -v --rmi local
rm deploy/test/.env
```

This deletes the database, the certificates and the images. The breached-password list is yours to keep or delete. Remove the test root certificate from the devices.
