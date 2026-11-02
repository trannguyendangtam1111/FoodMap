# Render backend deployment

Deploy only the backend; the Vercel frontend is configured separately.

## Render settings

- Service: Web Service, Docker runtime.
- Root Directory: `foodmap-backend`.
- Dockerfile Path: `./Dockerfile`; Docker build context: `.`.
- Build/start overrides: leave blank; use the Dockerfile and its entrypoint.
- Health Check Path: `/actuator/health/liveness`.
- Choose a region near the Neon database (Singapore for the current database).
- A small paid instance avoids free-instance cold starts. Actual memory usage
  still needs validation after deployment.

The image uses Java 17, runs as a non-root user, and fixes the active profile to
`prod`. It copies only the POM and main sources, excluding `.env`, tests, build
outputs, and backups. Logs go to stdout/stderr. `PORT` is supplied by Render;
`SERVER_PORT` is only a fallback. Do not override the container entrypoint.

## Required environment variable names

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `JWT_SECRET`
- `CORS_ALLOWED_ORIGINS`

Store credentials in Render's secret environment settings, never Docker build
arguments, source control, frontend variables, or logs. `DB_URL` must be the Neon
JDBC URL with TLS required, not a libpq-style URI. Keep credentials separate from
the URL. Do not override SSL with a disabling URL parameter. Set a fresh random
JWT secret of at least 32 UTF-8 bytes; placeholders are not deployment secrets.

Set `CORS_ALLOWED_ORIGINS` to exact HTTPS origins of the deployed Vercel frontend,
comma-separated, without paths or trailing slashes. Add preview origins explicitly
when needed; do not broadly allow arbitrary Vercel deployments. No actual frontend
origin is assumed here.

Optional tuning: `DB_POOL_MIN_IDLE`, `DB_POOL_MAX_SIZE`, `JAVA_TOOL_OPTIONS`,
`JWT_ACCESS_EXPIRATION_MS`, `JWT_REFRESH_EXPIRATION_MS`.

`GOONG_API_KEY` and `GOONG_MAP_KEY` are optional for this snapshot-only backend.
The frontend map key belongs in Vercel and is not configured by this backend image.
Do not enable the `dev` or `snapshot-ingest` profiles in production.

## Database and startup safety

- The production pool defaults to zero idle connections and at most five total.
- Hibernate validates mappings; it does not update the schema.
- Flyway validates the restored migration history/checksums only. Startup does
  not migrate, baseline, repair, clean, or recreate the database. Future schema
  changes require a separate reviewed migration procedure.
- The current Boot-managed Flyway 9.x may warn that PostgreSQL 18 is newer than
  its tested range. No version upgrade or history rewrite is included here.
- Development data seeding is dev-profile-only. Production explicitly disables
  startup ingestion, viewport enqueueing, and scheduled recrawling. The provider
  request budget remains unarmed.
- The public health path exposes only liveness status, not database details.
  Liveness does not poll Neon, allowing idle database suspension and avoiding
  restarts caused solely by database cold starts. Readiness/root health paths
  are not anonymously permitted by the application security chain.

## Verification before enabling traffic

After an explicitly approved deployment, check startup profile, Hibernate and
Flyway validation, the health path, one read-only restaurant request, and browser
CORS from the real Vercel origin. Verify auth and reviews separately before
publishing the demo. This preparation does not deploy or connect to Neon.
