# PennyFolio: Vercel + Render + Supabase

This deployment starts with an empty hosted database. No local records are migrated.

## 1. Supabase
Create a project, preferably near your Render region. This app uses PostgreSQL via Spring Boot, not Supabase Auth or browser database APIs. Disable the Supabase Data API for this project before deployment (or keep application tables in a schema excluded from the Data API). Application authorization lives in Spring Boot; do not expose these tables through public Supabase APIs.

Copy the **Session pooler** connection details from Connect. Set on Render:

- `DB_URL=jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=require`
- `DB_USER`: exact session-pooler username, typically `postgres.<project-ref>`
- `DB_PASSWORD`: database password, only in Render's secret environment settings.

Do not put credentials in the JDBC URL, Git, Vercel, or chat. Flyway creates the app tables on startup. Supabase keys are not required.

## 2. Render
Connect the GitHub repo and use `render.yaml` as a Blueprint, or create a Docker web service with `Dockerfile.backend` and repository root as build context. Choose the plan explicitly in the dashboard. Use one instance: sessions are currently in memory and reminder processing is not coordinated across instances. Restarts require users to sign in again.

Set `SPRING_PROFILES_ACTIVE=production`, the database variables, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, and `APP_BASE_URL=https://<your-vercel-production-host>` (no trailing slash). `APP_BASE_URL` is the frontend origin, not Render's URL. Render supplies `PORT`. Health path: `/api/health`. This is a process-readiness endpoint; database migrations must complete for startup, but it does not continuously test database connectivity.

For dependable scheduled reminders use an always-running service and configure the existing SMTP variables. Leave reminders disabled until SMTP is configured.

## 3. Vercel
Import the same GitHub repository. Set Root Directory to `frontend`, Framework to Vite, Build Command to `npm run build`, Output Directory to `dist`.

Once Render provides its URL, run from the repository root:

```sh
node scripts/configure-vercel.mjs https://YOUR-SERVICE.onrender.com
```

Commit the generated `frontend/vercel.json` and push. It proxies `/api/*`, `/oauth2/*`, and `/login/*` to Render. The browser uses relative URLs and a host-only HttpOnly cookie through the frontend origin. Keep personalized API/auth responses uncached. Do not add a browser Supabase client or expose backend secrets in VITE variables.

## 4. Google OAuth
Keep the existing Web application OAuth client or create a separate production client. Add the exact authorized redirect URI:

`https://<your-vercel-production-host>/login/oauth2/code/google`

Use the same hostname as APP_BASE_URL and open that hostname when signing in. Keep localhost callbacks if local development is still needed. While Google consent is in Testing, add intended Google accounts as test users. Configure branding as PennyFolio. Preview deployment URLs are not configured for sign-in; test auth on the stable production domain. A custom domain can be added later, updating both callback and APP_BASE_URL.

## 5. Live acceptance checks — required before calling deployment complete
- `/api/health` through the Vercel hostname returns 200; `/api/state` signed out returns 401 JSON.
- Sign in with Google: callback and final page stay on the Vercel/custom hostname, without a redirect to Render or localhost.
- Inspect the session cookie: Secure, HttpOnly, Path=/, SameSite=Lax. Confirm auth/API responses are not cached.
- Add a small identifiable test entry; reload and confirm it persists. Edit and delete it through the UI.
- Import a non-sensitive test workbook into an empty test account; verify the preview/profile and monthly totals. Do not use real history unless intended.
- Sign out and confirm `/api/state` returns 401 again. Check that a second user sees only their own personal records.
- Verify writes without the session CSRF token fail. Exercise a normal save to confirm valid CSRF/session cookies survive the proxy.

Live Google login and persistence tests require deployed URLs, provider credentials configured by the owner, and a test user session. A local frontend build does not validate those integrations.
