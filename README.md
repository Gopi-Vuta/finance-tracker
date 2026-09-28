# PennyFolio — connected app v0.2.2

This is the actual React + Spring Boot + PostgreSQL application, continued from the approved MVP. The original responsive UI is retained. Google accounts replace the demo user switcher; financial records are stored on the server rather than in browser localStorage.

## Start locally

Prerequisites: Docker Engine with Compose (Docker Desktop or Colima), plus a Google OAuth **Web application** client. Docker builds the Java and Node dependencies; no local Java installation is required for this path.

```sh
cp .env.example .env
# Edit .env with your Google OAuth client ID and secret.
./scripts/start-local.sh
```

The startup script detects either `docker compose` or `docker-compose`.

Open **http://localhost:8080**. Use `localhost` consistently, not `127.0.0.1`, for OAuth and session cookies. Open **http://localhost:8025** for the Mailpit test inbox. All published ports bind to loopback only. The first build downloads dependencies and may take several minutes.

PostgreSQL is intentionally private to Docker and has no host port mapping. This avoids clashes with any existing local PostgreSQL service or SSH tunnel on port `5432`; the application reaches it internally as `db:5432`.

On this Mac, Compose is installed as `docker-compose`; use that spelling in place of `docker compose` in the commands above and below.

If Colima is your Docker engine, start it with `colima start` before running Compose. With Docker Desktop, open the desktop application and wait until its engine is running.

### Google sign-in setup

1. In [Google Cloud Console](https://console.cloud.google.com/), choose or create a project.
2. Configure Google Auth Platform branding, audience and data access. While the consent configuration is in Testing, add both users' Google email addresses as test users. Request only `openid`, `profile` and `email`.
3. Create an OAuth client with application type **Web application**.
4. Add this exact authorized redirect URI:

   ```text
   http://localhost:8080/login/oauth2/code/google
   ```

5. Put its client ID and secret into `.env`, replacing the example values. Keep `.env` local; it is excluded from Git and Docker build context. Do not paste the secret into chat or frontend code.
6. Run `docker compose up --build` and sign in. A new Google account starts with an empty personal tracker. Use separate browser profiles to try two real users.

The backend performs the authorization-code/OpenID Connect flow with Spring Security, verifies Google identity, and requires a verified email. Identity is keyed by Google's subject, never by a client-supplied user ID. Tokens are not stored in frontend localStorage. Google client secrets remain on the server.

Official references: [Google web-server OAuth setup](https://developers.google.com/identity/protocols/oauth2/web-server), [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).

### Create and link a family

- Sign in as the first person and create a family on the Family page.
- Choose Invite member and enter the second person's Google email.
- Share the displayed code with that person directly. No invitation email is automatically sent.
- The invited person signs into their own account, chooses Join family, and enters the code. Joining explicitly shares their finance history with the family.
- Invitations are email-bound, single-use, expire after seven days, and are stored hashed. Family membership is checked on the server. Any current member may invite another member.
- Combined is read-only and includes every member. Personal edits always belong to the signed-in user. Leaving preserves the user's records and removes family visibility on subsequent requests.

### Reminders and local email

Compose enables the scheduler, but each user's reminder preference starts **disabled**. Configure and enable schedules in Settings. The local SMTP destination is Mailpit: messages appear in its local inbox and are not delivered to real recipients.

The worker checks once per minute, evaluates the user's timezone, clamps days 29–31 to a month's last day when necessary, honors enabled/incomplete/tallied settings, and records delivery attempts. It catches up due schedules for the current month; older months are not replayed. Retries are spaced 15 minutes apart, up to five attempts. The delivery key is user/schedule/month. Editing a schedule after that month's successful send does not resend it; adding a new schedule does create a new delivery key.

Delivery is at-least-once: an SMTP success followed by a database/process failure can cause a duplicate on retry. It does not claim exactly-once email delivery. Configure authenticated TLS SMTP only when moving beyond local testing, and update the from address and application URL. Never put SMTP credentials in frontend code.

## What is implemented

- Google OIDC sign-in, HttpOnly session cookies, CSRF-protected writes and logout.
- Persistent profiles, recurring defaults, monthly accounts, credit cards, fixed expenses, investment contributions, one-offs, remarks and check-in progress.
- Server validation for amounts, dates, identifiers, recurring ranges, timezones, reminder schedules and reconciliation prerequisites.
- Versioned atomic saves. A stale revision returns HTTP 409 instead of silently overwriting another tab's changes. Failed saves expose retry, draft export and explicit discard/reload controls.
- Family creation, invite creation, email-bound joining, membership listing, leaving and family-scoped reads.
- Configurable scheduled reminders with a local test inbox.
- Responsive phone/tablet/desktop UI from v2.1, connected to the API.
- Flyway migration and a persistent PostgreSQL Compose volume.

## Architecture

```text
Browser: React UI
    │ same-origin fetch + session cookie + CSRF header
    ▼
Spring Boot 4.0.8 / Spring Security / JDBC / Flyway
    ├── Google OIDC (login only)
    ├── PostgreSQL (users, memberships, monthly records, preferences)
    └── Reminder worker → SMTP (Mailpit locally)
```

The production build serves React assets from Spring Boot at port 8080. No CORS allowance or browser API key is needed. Session state is in server memory: restarting the application requires signing in again, but financial records persist in PostgreSQL.

### Source layout

- `frontend/src/main.jsx`: retained screens and connected authentication/family flows.
- `frontend/src/api.js`: session/CSRF requests, server saves, conflict recovery and draft backup.
- `frontend/src/model.js`: monthly initialization and display calculations; its synthetic fixtures are used only by unit tests and are not automatically loaded by the connected app.
- `backend/src/main/java/com/financetracker/SecurityConfig.java`: access rules and Google login.
- `FinanceService.java`: owner-scoped finance storage and family operations.
- `FinanceValidation.java`: server-side validation and decimal reconciliation calculations.
- `ReminderScheduler.java`: timezone-aware scheduling and delivery state.
- `backend/src/main/resources/db/migration/`: versioned schema.
- `backend/src/test/`: API/security/storage/reminder tests.

### Data model

| Table | Purpose |
|---|---|
| `app_users` | Google subject, verified email, editable name/timezone, revision, recurring and reminder documents |
| `monthly_records` | Owner + `YYYY-MM` primary key, validated monthly document |
| `families` | Family identity and name |
| `family_members` | Membership; one family per user, any number of members |
| `family_invites` | SHA-256 token hash, intended email, expiry and consumption time |
| `reminder_deliveries` | Per-user/schedule/month status, retries and last send |

Finance documents are serialized JSON stored in PostgreSQL text columns. The application validates them before writes, including BigDecimal amount checks. This first connected release uses an owner-level revision and atomically replaces that owner's monthly document set on save; it does not pretend to be a transaction-level ledger API. This preserves the flexible MVP schema while making ownership, concurrency and persistence explicit. Later accounting/reporting needs may justify normalized entry tables and finer-grained endpoints.

Recurring defaults initialize a month when the user first opens it. Existing saved months are preserved when defaults change. Opening balances are entered explicitly; missing closing balances remain null rather than becoming zero. Family savings rates use aggregate income/expenses, and offsetting member discrepancies do not make the family tallied. Full check-in review and balanced reconciliation are separate states.

### API

| Method/path | Behavior |
|---|---|
| `GET /api/config` | Whether Google sign-in has been configured (no secrets) |
| `GET /api/csrf` | Session-bound token for subsequent mutations |
| `GET /api/state` | Signed-in user plus currently authorized family finances |
| `PUT /api/finance` | `{revision, user, months}` for the signed-in user only |
| `POST /api/families` | `{name}` creates a family |
| `POST /api/families/invites` | `{email}` returns a one-time invite code |
| `POST /api/families/join` | `{code}` consumes an invite |
| `POST /api/families/leave` | Removes the caller's membership |
| `POST /api/logout` | Invalidates the session; requires CSRF |

Writes are authenticated and CSRF protected. The server ignores editable email input and uses the verified Google identity. It rejects owner-ID mismatches and stale revisions. Other members' reminder settings are not returned.

## Development and tests

For the full build (network access is needed the first time):

```sh
cd frontend
npm ci
npm test
npm run build
cd ../backend
./mvnw test
./mvnw package
```

Docker's app build copies the frontend production assets into the Spring Boot JAR automatically.

For native development, run PostgreSQL and Mailpit via `docker compose up -d db mailpit`, export the variables from your local `.env` into the shell, and run `./mvnw spring-boot:run` from `backend`. Run `npm run dev` from `frontend` for Vite. Its `/api`, `/oauth2` and `/login` routes proxy to the backend. Google login returns to port 8080; build/copy frontend assets into `backend/src/main/resources/static/` before testing that full redirect path, or use Compose for the simplest end-to-end experience.

The Java source targets Java 17+; the container uses Java 21. Frontend requires Node 22.12+. Maven Wrapper downloads Maven into the normal user cache when used outside this development session.

Tests exercise authentication requirements, CSRF, separate identities, owner isolation, stale writes, transaction rollback, dates/amounts, multi-member access, leave revocation, invitation expiry/email/single-use checks, leap-month scheduling, deduplication and stop-when-tallied. Reminder tests use a recording mail transport; they do not send real email. Integration tests use H2 in PostgreSQL compatibility mode; a PostgreSQL smoke run remains necessary before deployment.

## Existing MVP data

The connected app can make a controlled one-time import of a legacy v2 profile. To move **PopCorn** into `gopisaikrishna.vuta@gmail.com`:

1. In the old MVP at `http://127.0.0.1:4173`, select **Settings** and choose **Export backup**. This downloads a JSON file from the browser-only tracker.
2. Start the connected app, sign in with `gopisaikrishna.vuta@gmail.com`, and do not add any new monthly entries first.
3. Open **Settings → Import PopCorn backup**, open the downloaded JSON in a text editor, paste its contents, leave the profile as `PopCorn`, tick the confirmation, and save.

The server accepts only a FinanceTracker v2 JSON backup, imports only the profile named PopCorn, and refuses if the signed-in account already has monthly records. It preserves the Google identity, Google email, display name, reminder settings, and all family memberships. It imports recurring configuration and PopCorn's month-by-month records. It does not import Candy data, demo users, invite codes, or the old browser's family relationship.

Keep the exported backup until you have checked the imported history. There is deliberately no automatic browser-data upload and no overwrite mode.

## Backups and stopping

```sh
# Keep the database volume while stopping containers:
docker compose down

# Database backup (contains private finance data; keep it private):
docker compose exec -T db pg_dump -U financetracker financetracker > finance-backup.sql
```

Do not add `-v` to `docker compose down` unless intentionally deleting the database volume. The UI can also export currently accessible records as JSON; there is no JSON restore UI yet.

## Before deploying

This is a local-first connected release, not a claim of completed production operations. Configure a real domain and HTTPS, set the exact Google redirect URL, enable secure cookies, provide private database/SMTP credentials, persistent backups and monitoring, and validate against PostgreSQL. Decide on session persistence, invitation administration, deletion/retention and data import before wider use. The local Compose configuration intentionally exposes no service beyond localhost.

See [VALIDATION.md](VALIDATION.md) for the exact checks performed and end-to-end checks that still require local setup.

## Dashboard update — v0.2.0

This release adds separate Invested and Uncommitted cards and two trend lines;
missing observations appear as gaps. Explicitly reviewed zero amounts still plot
as zero. Newly generated recurring rows are estimates until edited or their
check-in section is reviewed. Legacy saved rows are treated as recorded because
older versions did not retain an estimate flag. Percentages require positive
income; uncommitted money requires income and all spending sections to have
entries or a completed review. Family series require observations from all members.

The dashboard includes prior-month deltas and one meaningful comparison. An
optional Category field on expense entries enables category comparisons (for
example Dining); existing entries fall back to Fixed expenses, Credit cards and
Variable expenses. Comparisons omit missing or zero-base percentages, and insights
require at least ₹1,000 and 10% change. No statement import is included.

Personal streaks require all eight steps and an exactly tallied reconciliation.
The current month may remain in progress while the prior streak is shown. A
missing or untallied preceding month resets it, with a dismissible explanation.

Budget goals have a dedicated Budget Goals page in the individual view. Presets
are Balanced (50/30/20), Debt payoff (50/20/30), Aggressive saver (35/15/50), and
Custom. Targets are whole percentages totaling 100. Map the four existing expense
and investment groups to Needs, Wants or Savings. The meter previews edits before
saving; Cancel restores saved choices. Savings counts mapped entries, not leftover
cash. A five-percentage-point tolerance is amber; larger deviations are red.
Goals are personal and are not combined into a single family target.

### Persistence and upgrade

`V2__budget_preferences.sql` adds `app_users.budget_json`; Flyway applies it on
startup. Settings are validated on the server and saved with the existing revision
check. Existing monthly records and Google identities are preserved. Monthly JSON
now also supports `entered` for explicitly entered zero income and `estimated` on
recurring rows. No database reset is needed.

Update the source in your existing project directory, preserve your `.env`, and
run `docker compose up --build -d` from that same directory so Compose keeps the
existing database volume. Do not use `docker compose down -v` for this upgrade.

### Validation

Run `npm test` and `npm run build` in frontend, and `./mvnw test` in backend.
Tests cover missing versus zero data, percentage examples, streak gaps, budget
mapping and validation, plus backend budget persistence and rejected invalid saves.
Browser checks use isolated sample data, not a live Google account or production
finance records.

### v0.2.1 navigation and layout

Budget Goals now provides a savings progress ring, target cards, and contextual encouragement for the selected month. Income is required before progress can be calculated; only recorded savings count. Targets and category mapping remain editable and persist to the server.

One-off expenses and remarks are now under Expenses & Investments. Monthly Check-in retains its existing steps. Long names and email addresses wrap inside the desktop account card. No database migration is required for these presentation changes.

To update an existing installation, replace the application source while retaining your local `.env`, then run `docker compose up --build -d`. Keep the existing database volume.

### v0.2.2 — PennyFolio branding

The app is now PennyFolio (with a “y”): sign-in page, sidebar, footer, browser title, favicon, backup filenames, and reminder emails use the new name. Existing backups remain compatible. Internal package names, browser storage keys, database identifiers, and the `finance-tracker-app` directory are retained for continuity.

Update in your existing project directory, retain `.env` and the database volume, and run `docker compose up --build -d`. Google’s consent screen name is managed separately in Google Auth Platform → Branding; set its app name to PennyFolio there. OAuth client credentials and redirect URLs do not need to change for this rebrand.

### v0.3.0 — visual refresh

Pastel metric cards, vector illustrations, navigation and section icons, gradient PennyFolio branding, softer surfaces, and reduced-motion-aware transitions. Charts now label currency or percentage axes. No data schema changes.

The original design is committed and tagged `pennyfolio-before-visual-refresh` (commit `bc6af10`). To view that version without changing the current branch, use `git switch --detach pennyfolio-before-visual-refresh`; return with `git switch -`. Keep local `.env` and database volumes. A Git bundle is supplied alongside the release to preserve both commits outside this workspace.

Validation: 19 frontend tests pass; production build passes. Mock-data browser checks at 390px and 1440px show no page overflow; long sidebar identity fits; Budget Goals navigation works.
