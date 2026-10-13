# Continuous integration

`.github/workflows/ci.yml` runs on pull requests targeting `develop` and pushes to
`develop`. It has two independent jobs on separate Ubuntu runners:

| Job | Validation |
|---|---|
| `build` | Java 17, PostgreSQL service and `./mvnw clean verify`, including unit, database integration and real HTTP tests |
| `compose` / Docker Compose validation | Image build, PostgreSQL/API readiness and real HTTP smoke flow against the packaged application |

## Compose job

1. Generate temporary database and JWT secrets with OpenSSL. Mask the values in
   Actions logs and pass them through `GITHUB_ENV`; repository secrets are not required.
2. Validate Compose configuration without printing resolved credentials.
3. Build the API image from the Dockerfile using the Maven Wrapper.
4. Start the `banking-ci` Compose project and wait up to 180 seconds for healthchecks.
5. Run `scripts/Test-Compose.ps1` with PowerShell on Linux against port 18080.
   It checks 15 real HTTP requests for registration, login, roles, accounts,
   deposits, withdrawals, transfers, balances and statements.
6. Collect container status and service logs, then remove containers, network and
   database volume using `always()` steps.
7. On failure, upload the build, startup, smoke, container and cleanup logs as
   `compose-failure-<run-id>-<attempt>`, retained for seven days.

The job has a 25-minute timeout, with separate build, startup, smoke and cleanup
limits. Bash pipelines use the Actions shell's `pipefail` behavior, and PowerShell
rethrows smoke errors after recording them, so log capture does not hide failures.
Only log files are uploaded; no environment files or full container inspection
output are included. Permissions are limited to `contents: read`.

The runner is disposable and the database belongs to that job. `always()` handles
normal step failures and cancellation while the runner is available; forced runner
termination or a job timeout can prevent cleanup steps from completing.

The Docker build skips database tests; the separate `build` job runs the full suite.
No images are published and no deployment or automatic merge is performed.
To enforce both results before merging, configure the repository's branch rules
to require `build` and `Docker Compose validation`; adding the workflow does not
change those rules automatically.

## Local verification

The workflow can be checked with `actionlint`. The Compose build, readiness and
smoke flow can be reproduced using [the Docker guide](docker.md). A local Docker
run is distinct from execution on a GitHub-hosted runner; the remote job result is
available after publishing the branch and opening a PR against `develop`.

Validated locally on 2026-10-13: `actionlint` passed; image build and service
healthchecks succeeded; the 15-request smoke script passed using PowerShell 7.4
inside Linux. An intentionally unreachable HTTP address produced a nonzero exit
code even with `Tee-Object`. Service diagnostics were collected and the temporary
Compose containers and volume were removed. GitHub artifact upload and the hosted
job itself still require a remote workflow run.
