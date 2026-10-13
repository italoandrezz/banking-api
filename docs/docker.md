# Docker Compose

Requires Docker Engine/Desktop with Linux containers and Docker Compose v2 or later.
Run commands from the directory containing `compose.yaml` and `pom.xml`.

## Start

Copy `.env.example` to `.env` (PowerShell: `Copy-Item .env.example .env`) and set
`DB_PASSWORD` and `JWT_SECRET` to separate random secrets. The JWT secret must have
at least 32 ASCII characters. Compose rejects empty values for either variable.
Keep an existing `.env` when restarting; it is ignored by Git and excluded from
the Docker build context.

```powershell
docker compose up -d --build --wait
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health
```

The API is published on loopback at `http://localhost:8080`, Swagger at
`/swagger-ui/index.html`. `API_PORT` changes the host port if 8080 is occupied;
the container always listens on 8080. PostgreSQL is reachable only within the
Compose network as `db:5432`; it does not conflict with a local PostgreSQL server.
The first build downloads Java/Maven dependencies and can take several minutes.

| Compose variable | Meaning | Default |
|---|---|---|
| `DB_NAME` | Database created on first initialization | `banking_db` |
| `DB_USERNAME` | PostgreSQL initialization user | `banking_app` |
| `DB_PASSWORD` | Database password | Required |
| `JWT_SECRET` | JWT signing secret | Required |
| `JWT_EXPIRATION` | Token lifetime in milliseconds | `86400000` |
| `API_PORT` | Published local port | `8080` |

The image is built in two stages using Java 17 and the repository's Maven Wrapper.
Only the executable JAR is copied into the JRE runtime, which runs as UID 10001.
Dependencies use a BuildKit cache. Tests require their own database and are not
executed during the image build; run `./mvnw test` separately (Windows:
`.\mvnw.cmd test`). The existing GitHub Actions workflow also runs `clean verify`.

The database healthcheck uses `pg_isready`; API startup waits for it. Flyway applies
the migrations before Hibernate validates the schema. The API healthcheck uses
`GET /actuator/health`, including database availability. This public endpoint shows
only `status`; other actuator endpoints are not exposed. Container restart policy
restarts exited processes, not a running process that is merely unhealthy.

## Validate and inspect

```powershell
.\scripts\Test-Compose.ps1
# With a custom API_PORT:
.\scripts\Test-Compose.ps1 -BaseUrl http://localhost:18080
docker compose logs --tail=100 api
docker compose logs --tail=100 db
docker compose config --quiet
```

The smoke script makes real HTTP requests and checks registration, login, default
role, account creation, deposits, withdrawals, transfers, resulting balances and
paginated statements. It creates sample data that remains in the selected local
database. It complements the full automated test suite; it is not a load test.
Use `config --quiet` when checking configuration to avoid printing resolved secrets.

## Persistence and shutdown

```powershell
docker compose down
docker compose up -d --wait
```

The named `postgres_data` volume retains data across container recreation. Database
initialization variables only apply to an empty volume: changing `DB_PASSWORD` in
`.env` does not change an existing database user's password. Keep the original
settings or change the user's password explicitly. Changing `JWT_SECRET` invalidates
existing tokens. `docker compose down --volumes` deletes the project's database
volume; use it only when intentionally discarding that environment's data.

## Running without Docker

`application.properties` now accepts `DATABASE_URL` (default
`jdbc:postgresql://localhost:5432/banking_db`), `DATABASE_USERNAME` (default
`postgres`), mandatory `DATABASE_PASSWORD`, mandatory `JWT_SECRET`, optional
`JWT_EXPIRATION`, `SERVER_PORT` and `SHOW_SQL` (default `false`). Compose maps its
`DB_*` settings to these application variables. `.env` is read by Compose; Spring
Boot does not load that file automatically when started with Maven.

```powershell
$env:DATABASE_PASSWORD = 'your-local-database-password'
$env:JWT_SECRET = 'your-random-signing-secret-at-least-32-characters'
.\mvnw.cmd spring-boot:run
```

The `test` profile retains its isolated database settings and test-only signing
key. It does not require these application secrets and is not used by Compose.

## Validation performed

Validated locally on 2026-10-12 using Docker Desktop/Linux containers:

- Compose configuration accepted valid secrets and rejected empty required secrets.
- The multistage image built successfully; API and PostgreSQL reached healthy state.
- The API ran as UID 10001, and Flyway applied all five migrations to PostgreSQL 17.
- `Test-Compose.ps1` passed 15 real HTTP requests against the containerized API.
  SQL confirmed two accounts, a combined balance of 90.00 and three transactions.
- After `down` followed by `up`, those balances and history remained in the volume.
- The full Maven suite passed 232 tests, including 318 real HTTP requests against
  its isolated test server. Those are separate from the 15 Compose smoke requests.

This validation used a separate Compose project and port 18080. It does not
validate a public deployment, TLS or load behavior.
