# HTTP endpoint validation

The repeatable suite `HttpEndpointValidationTest` starts the actual Spring Boot
application with embedded Tomcat bound to loopback on a random TCP port. Java's
`HttpClient` sends real HTTP requests. Controllers, JWT security, services,
repositories and PostgreSQL are real; no MockMvc or mocked dependencies are used.

Validated on 2026-10-08: **212 HTTP requests**, **17 business endpoints**, **10 HTTP
test scenarios**, all passing. The full Maven suite finished with **207 tests,
zero failures, zero errors and zero skipped tests**. The request count includes
fixture creation/login and expected error responses, including one induced 500.

## Run

```powershell
.\mvnw.cmd test '-Dtest=HttpEndpointValidationTest'
```

The suite uses the `test` profile and its `TEST_DATABASE_URL`,
`TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD` settings (local default
database: `banking_api_test`). It creates/migrates the dedicated schema
`banking_api_http_tests` and truncates only that schema's application tables before
and after each scenario. The schema must be reserved for this suite. Do not run
multiple instances of this suite against the same schema simultaneously.
The server is stopped when the test context closes. No production database is used
by the default configuration.

`target/http-validation.md` records each request's scenario, expected status and
actual status, without tokens, passwords or response bodies. Surefire reports in
`target/surefire-reports` also cover response payloads, committed database state and
concurrency assertions. A passing status table alone is not the complete test result.

## Endpoint coverage

| Endpoint | Scenarios exercised |
|---|---|
| `POST /customers` | Registration, validation, duplicate identity, attempted ADMIN assignment |
| `POST /auth/login` | Successful login, changed email, wrong password, invalid body |
| `GET /auth/me` | Identity, role, anonymous/invalid token, deleted identity, role promotion/revocation |
| `GET /customers` | Authenticated listing denied by design; anonymous/invalid token denied |
| `GET /customers/{id}` | Own profile, ownership, missing/invalid ID, authentication |
| `PUT /customers/{id}` | Update, role assignment ignored, invalid body, ownership, authentication |
| `DELETE /customers/{id}` | Delete without accounts, token invalidation, dependent-account conflict, ownership, authentication |
| `POST /accounts` | Account creation, invalid body, ownership, authentication |
| `GET /accounts` | Only owned accounts returned; authentication |
| `GET /accounts/{id}` | Balance confirmed through HTTP and SQL, ownership, missing/invalid ID, authentication |
| `PATCH /accounts/{id}/block` | Active account blocked, ownership, authentication |
| `PATCH /accounts/{id}/unblock` | Unblock, invalid transition, closed account rejected, ownership, authentication |
| `PATCH /accounts/{id}/close` | Zero-balance closure, nonzero/blocked rejection, ownership, authentication |
| `POST /accounts/{id}/deposit` | Credit, invalid amount, missing account, blocked/closed account, idempotent retry, ownership, authentication |
| `POST /accounts/{id}/withdraw` | Debit, insufficient funds, invalid amount, missing account, idempotent retry and retry after failure, ownership, authentication |
| `POST /accounts/transfer` | Transfer, insufficient funds, same-account rejection, missing destination, concurrent retries, key conflict, commit rollback, ownership, authentication |
| `GET /accounts/{id}/transactions` | Pagination, totals, received transfer, combined date/type filters, invalid filters, closed account history, ownership, authentication |

The suite compares the endpoints exercised against `/v3/api-docs` and asserts that
all 17 documented business routes were reached. It also checks `/admin/**` access
boundaries using a nonexistent route: CUSTOMER gets 403, ADMIN reaches routing and
gets 404. There is no administrative business endpoint in this version.

## Problems reproduced and corrected

1. Tomcat's internal error dispatch was reauthenticated, masking original errors
   with 401. Security now permits only the internal ERROR dispatcher, preserving
   the original 404/500. Direct unauthenticated requests to `/error` still get 401.
2. Deleting a customer with linked accounts raised an unhandled foreign-key
   violation. The delete service now translates the integrity conflict into 409;
   the customer and financial records remain intact.

## Scope and limits

This validates the local application and database over HTTP. It does not test a
deployed environment, TLS, proxies, browser CORS, sustained load or every possible
input combination. The existing unit and integration tests remain complementary.
The deliberately induced commit failure should produce an HTTP 500 and server
error logs; the test verifies that balances, history and idempotency records roll
back and that retrying the same key succeeds after the injected failure is removed.
