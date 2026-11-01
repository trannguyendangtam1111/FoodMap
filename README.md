# FoodMap

FoodMap is a food and restaurant discovery app for Ho Chi Minh City, built
with Java 17, Spring Boot, PostgreSQL/PostGIS, and React. The interface is in
Vietnamese.

The main experience is **food-first**:

```text
Cà mèn / Roll -> food suggestion -> Food Page -> restaurant detail
```

Users can also search directly, browse available foods, or explore the map.

**Repository:** TODO: add the actual GitHub repository URL.

## Features

- **Vietnamese-aware search:** restaurant name, menu, and address search using
  PostgreSQL full-text/trigram candidates and server-side relevance ranking.
  Catalog food suggestions open a Food Page; restaurant suggestions open detail.
- **Food discovery ("Thèm gì?"):** server-provided foods with restaurant supply
  counts for a selected area, the user's location, or the city centre.
  Counts describe known supply, not popularity or quality.
- **Cà mèn / Roll ("Ăn gì hôm nay?"):** the backend draws one available food.
  "Món khác" requests another with previously shown food keys excluded;
  "Tìm quán" opens the Food Page in the same scope.
- **Food Page (`/mon/:foodKey`):** a restaurant suggestion and a paginated,
  server-ranked restaurant list for the chosen food, with name/menu evidence.
  Users can request another restaurant without changing the food.
- **Restaurant Detail:** address, contact information, opening hours, menu,
  ratings, and reviews where data is available.
- **Food-aware Map (`/map`):** restaurant and food modes, viewport-based loading,
  food selection (`?food=key`), and matching restaurants using server-provided
  food keys. Food counts refer to the loaded map frame, not the whole city.
- **Authentication and reviews:** registration/login, JWT access tokens, hashed
  rotating refresh tokens with reuse detection, authenticated review submission,
  ownership checks for deletion, and server-maintained rating aggregates.

The decision flow includes empty, error, timeout, and rate-limit states,
keyboard controls, live announcements, and reduced-motion support. Location
is optional; food page URLs carry area keys or `near=1` rather than raw
coordinates. Distances in that flow use the user's real location.

## Backend Engineering

| Area | Implementation |
| --- | --- |
| API | Spring Web REST controllers, validation, DTOs, centralized exception handling, OpenAPI/Swagger |
| Security | Spring Security, BCrypt, signed JWT access tokens, hashed opaque refresh tokens, rotation and reuse detection |
| Persistence | Spring Data JPA/Hibernate, transactional services, lazy relationships, database constraints, optimistic locking for refresh tokens |
| Database | PostgreSQL 16, PostGIS spatial filtering, `pg_trgm` and `unaccent` search, Flyway migrations |
| Search | Bounded database candidates, batched menu loading, Vietnamese-aware relevance tiers, pagination |
| Decisions | Server-owned food selection, food-specific restaurant ranking and weighted selection, exclusions, per-IP rate limiting |
| Ingestion | Explicit Goong snapshot runs, dry-run mode, request budgets, deduplication, validation, run locking, JSON reports |

Frontend: React 18, Vite 5, React Router 6, Tailwind CSS 3, Axios, and Goong JS
for maps. Tests use JUnit 5, Spring Boot Test, Mockito, H2, Vitest, and React
Testing Library.

## Architecture

```text
React SPA -> Spring Boot REST API -> PostgreSQL / PostGIS

Explicit snapshot ingestion -> Goong Places -> validation/deduplication
                           -> PostgreSQL + JSON run report
```

Backend packages are organized by feature: `auth`, `catalog`, `decision`,
`restaurant`, `review`, `ingestion`, and supporting `security`, `integration`,
`common`, and `config` packages. Controllers delegate to services and repositories.

With default settings, discovery and decision requests read the local database
without triggering ingestion or Goong Places calls. Ingestion triggers for startup,
viewport browsing, and scheduled recrawling are disabled by default. The `dev`
profile separately seeds sample application data when no restaurants exist.
Authentication and reviews write to the database.

The Goong map SDK is lazy-loaded on `/map`; map tiles still need a map key and
network access.

### Food-First API Flow

All paths are relative to `/api/v1`.

| Endpoint | Purpose |
| --- | --- |
| `GET /catalog` | Public food vocabulary and available discovery areas |
| `GET /catalog/foods/match?q=...` | Food suggestions and whole-query food intent |
| `GET /restaurants/search?query=...` | Read-only restaurant search |
| `GET /decisions/foods/available` | Foods with known restaurant evidence in scope |
| `GET /decisions/food` | Cà mèn food decision; default kind is `MEAL` |
| `GET /decisions/foods/{key}/restaurants` | Ranked, paginated restaurants for one food |
| `GET /decisions/today?food={key}` | Restaurant suggestion for the chosen food |

Food decisions draw uniformly among available foods after exclusions. Restaurant
suggestions use the existing ranking formula and a weighted top-N draw. The food
is never substituted if no restaurant is available. Scopes support `lat` + `lng`,
an `area` key, or the default city centre. Radius widening is reported and does
not apply to fixed area scopes. Rate limits return `429` with `Retry-After`.

## Local Setup

### Prerequisites

- Java 17 and Maven 3.6.3+
- Node.js 20+ and npm
- Docker with Docker Compose for local PostgreSQL/PostGIS
- A Goong map key for the map; a Goong REST API key only for explicit ingestion

**TODO:** add the actual clone URL. After obtaining the repository, open a
terminal in its root directory. Examples below use PowerShell.

### 1. Environment

```powershell
Copy-Item .env.example .env
Copy-Item foodmap-frontend/.env.example foodmap-frontend/.env
```

Edit the copied files before starting. Keep secrets out of version control.

| Variable | Notes |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` for local setup |
| `DB_URL` | `jdbc:postgresql://localhost:5434/foodmap_dev` |
| `DB_USERNAME`, `DB_PASSWORD` | Must match the database initialized by Compose |
| `SERVER_PORT` | `8082` matches the Vite development proxy |
| `JWT_SECRET` | Replace with a random secret of at least 32 UTF-8 bytes; used as-is, not Base64-decoded |
| `SEED_DEMO_PASSWORD` | Choose an 8-100 character password for dev sample accounts; required when seeding |
| `GOONG_API_KEY`, `GOONG_MAP_KEY` | Backend placeholders can remain for local snapshot browsing; real keys are needed for their respective Goong operations |
| `VITE_GOONG_MAP_KEY` | Real map tiles key for the frontend map |
| `VITE_API_BASE_URL` | Backend origin without `/api/v1`; leave empty for Vite's proxy, or use `http://localhost:8082` |

Set `JPA_DDL_AUTO=validate` in the copied root `.env` to keep schema management
with Flyway. The current example says `update`; the `dev` profile explicitly
sets `validate`. The production profile also uses `validate`.

### 2. Database

From the repository root:

```powershell
docker compose up -d
```

Compose starts PostgreSQL 16/PostGIS on `127.0.0.1:5434`, with database
`foodmap_dev` and persistent volume `foodmap-db-data`. Credentials initialize
a new volume; changing `.env` does not change existing database credentials.
Flyway applies schema migrations when the backend starts.

### 3. Backend

After editing the root `.env`, copy it into the backend working directory:

```powershell
Copy-Item .env foodmap-backend/.env
Set-Location foodmap-backend
mvn spring-boot:run
```

The backend reads `.env` from its working directory. Recopy it after changing
root settings, or supply environment variables through the shell/IDE instead.

- API base: [http://localhost:8082/api/v1](http://localhost:8082/api/v1)
- Swagger UI: [http://localhost:8082/swagger-ui.html](http://localhost:8082/swagger-ui.html)
- Default `dev` startup seeds sample restaurants, dishes, reviews, and demo users
  when no restaurants exist; no Goong ingestion run is needed.

### 4. Frontend

In a separate terminal, starting from the repository root:

```powershell
Set-Location foodmap-frontend
npm ci
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). Vite uses a fixed port and
proxies `/api` to `http://localhost:8082`. If changing backend ports, keep the
frontend API origin/proxy aligned. Production uses environment-provided secrets
and the `prod` profile; these instructions describe local setup.

## Controlled Goong Ingestion

Ingestion is optional for a local demo with seeded data. The separate
`snapshot-ingest` profile runs without a web server and defaults to a dry run.
Execution requires explicit opt-in and a budget. The pipeline discovers places,
skips duplicates/known places, validates coordinates and region, and records
results in a JSON report. Provider requests, including retries, consume the
armed budget; the run holds a PostgreSQL advisory lock.

Advanced food probes, AUTO mode, and the optional scheduler are documented in
[Auto ingestion and scheduler](foodmap-backend/docs/auto-ingestion-scheduler.md).
Leave ingestion disabled for ordinary browsing unless deliberately running it.

## Testing

Commands below run the suites locally. No current passing result or test count
is claimed here.

```powershell
# From the repository root: fast backend tests (H2/unit tests; no Docker)
mvn -f foodmap-backend/pom.xml test

# From the repository root: frontend
npm --prefix foodmap-frontend test
```

The standard backend `test` profile uses H2 with Flyway disabled. Tests cover
authentication/security, decisions, matching/ranking, reviews, and ingestion
guards. PostGIS repositories are mocked in the sampled decision API tests, so
those tests do not validate production spatial SQL or Flyway migrations.
Frontend tests cover API normalization, components, pages, and decision flows.

Optional **Testcontainers-based PostgreSQL/PostGIS integration tests** cover
fresh Flyway migrations, a radius boundary case, and PostgreSQL-specific search.
They require a running Docker engine and use a disposable database, without
accessing the development database or calling Goong.

```powershell
# From the repository root: PostgreSQL/PostGIS suite only
mvn -f foodmap-backend/pom.xml -Ppostgres-it verify
```

See [PostgreSQL/PostGIS integration test documentation](foodmap-backend/docs/postgres-integration-tests.md)
for prerequisites, isolation, and coverage details.

## Screenshots

TODO: add actual screenshots of the current application:

- Home with "Thèm gì?" and Cà mèn food reveal
- Food Page with restaurant suggestion and list
- Restaurant Detail
- Food-aware Map, including its mobile layout

**Optional demo video:** TODO: add a link to a real application walkthrough.
