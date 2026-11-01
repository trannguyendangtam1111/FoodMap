# PostgreSQL/PostGIS integration tests

Requires Java 17+, Maven, and a running Docker engine. From the repository root:

```sh
mvn -f foodmap-backend/pom.xml -Ppostgres-it verify
```

The opt-in profile runs only `*PostgresIT` through Maven Failsafe and skips
Surefire's fast tests. Normal `mvn test` still runs the existing H2/unit tests
and does not start a container. Two test-scoped Testcontainers modules are pinned
to 1.21.4 for Docker Engine 29 compatibility; production dependencies/configuration
are unchanged.

`RestaurantRepositoryPostgresIT` starts a disposable `postgis/postgis:16-3.4`
container on a random port. The first run may download that image and Maven
dependencies. No database URL or credentials are read from `.env` or application
configuration. No Spring Boot application context, seeder, ingestion, or Goong
client is started. There is no fallback to a local database and no container reuse.

Coverage:

- All Flyway migrations on an empty schema, required extensions, Hibernate schema
  validation, and a second migration run with no pending work.
- The actual availability repository query: centre, 999.9 m inside a 1 km radius,
  1000.1 m outside, and an inactive restaurant. PostGIS generates the coordinates;
  the box contains both boundary points so the distance predicate is exercised.
- The actual PostgreSQL search query: accent-folded name, menu evidence,
  full-text address lookup, inactive/unrelated exclusions, and projection mapping.

Fixtures are synthetic. Cleanup affects only the container database, which
Testcontainers removes after the suite. If Docker is unavailable, report the suite
as NOT RUN; do not point it at the development database. Reports are written to
`foodmap-backend/target/failsafe-reports/`.
