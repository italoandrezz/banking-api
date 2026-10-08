# 🏦 Banking API



A RESTful Banking API built with **Java 17** and **Spring Boot** to simulate banking operations while applying modern backend development best practices.

This project is part of my backend learning journey, where I apply clean architecture principles, REST API design, Spring ecosystem technologies, and PostgreSQL to build a real-world banking application.

---

## 📌 Current Status

This project is under active development following a feature-branch workflow.

Implemented features:

- Customer CRUD
- Banking Accounts
- Money Transfers
- Transaction History
- Swagger/OpenAPI Documentation

---

## 🚀 Technologies

- Java 17
- Spring Boot
- Spring Web
- Spring Data JPA
- Spring Security
- Bean Validation
- PostgreSQL
- Flyway
- Springdoc OpenAPI (Swagger)
- Lombok
- Maven
- Postman

---

## 🏛️ Architecture

The project follows a layered architecture:

```text
           HTTP Request
                 │
                 ▼
         REST Controller
                 │
                 ▼
          Service Layer
                 │
                 ▼
        Repository Layer
                 │
                 ▼
            PostgreSQL
```

---

## 📁 Project Structure

```text
src
├── controller
├── service
├── repository
├── entity
├── dto
├── exception
│   └── handler
├── config
└── resources
```

---

## ✅ Current Features

### Customers

- Customer registration
- Customer CRUD
- Request validation using Bean Validation
- Global exception handling
- Duplicate CPF validation
- Duplicate email validation

### Accounts

- Create bank account
- List accounts
- Find account by ID
- Deposit
- Withdraw
- Transfer between accounts

### Transactions

- Automatic transaction history
- Deposit history
- Withdraw history
- Transfer history

### API Documentation

- Swagger UI
- OpenAPI 3 documentation

---

## ▶️ Running the Project

### Clone the repository

```bash
git clone https://github.com/italoandrezz/banking-api.git
```

### Navigate to the project

```bash
cd banking-api
```

### Run the application

```bash
./mvnw spring-boot:run
```

The API will be available at:

```text
http://localhost:8080
```
---

## 📖 API Documentation

Once the application is running, access:

```text
http://localhost:8080/swagger-ui/index.html
```

---

## Running tests

The Spring context and database integration tests use the `test` profile and a
dedicated PostgreSQL database. Create it once using a PostgreSQL user with
permission to create databases:

```sql
CREATE DATABASE banking_api_test;
```

By default, tests connect to `localhost:5432/banking_api_test` with user
`postgres` and password `admin`. Override these values with
`TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD`.
Use a dedicated test database, never a production database.

Flyway applies the existing migrations to the `banking_api_tests` schema.
Integration fixtures are disposable and may be cleared between tests in that
schema. The test profile supplies its own test-only JWT key, so running tests
does not require `JWT_SECRET`.

```bash
./mvnw clean test
./mvnw clean verify
```

On Windows, use `.\mvnw.cmd`. GitHub Actions provisions a separate PostgreSQL
test database and uses the same configuration.

### Financial atomicity

Deposits, withdrawals and transfers execute balance changes and transaction
history in a single database transaction. A persistence failure rolls back
the entire operation.

`AccountServiceIntegrationTest` uses real services, repositories and PostgreSQL.
Tests are not wrapped in a test-managed transaction: they read persisted balances
and history through JDBC after the service call finishes. Test-only deferred
triggers simulate failures when committing history or crediting the destination.
The suite checks successful operations, complete rollback and preservation of
previously committed transactions. Fixtures and failure triggers are removed
between tests; run these database tests sequentially.

Balance-changing operations acquire JPA pessimistic write locks until commit or
rollback. Transfers lock both accounts in UUID order, including crossed transfers,
to avoid opposite lock acquisition orders. Read-only account queries do not lock.

`AccountConcurrencyIntegrationTest` checks simultaneous withdrawals, deposits,
same-direction transfers and crossed transfers against PostgreSQL. Each worker
has its own authentication context and service transaction. A start barrier and
database lock contention ensure the operations overlap; bounded waits detect hangs.
Run database tests sequentially against the dedicated test schema.

### Monetary limits

Deposits, withdrawals and transfers require a positive amount with at most 13
integer digits and 2 decimal places (maximum `9999999999999.99`, matching
`NUMERIC(15,2)`). Values are rejected rather than rounded; `1.000` is also rejected.
Invalid amounts return HTTP 400. Deposits or transfers that would exceed the
maximum balance return HTTP 409 without changing balances or transaction history.
The service also validates amounts when called directly, outside HTTP controllers.

### Block and unblock accounts

The authenticated owner can call `PATCH /accounts/{id}/block` to change an ACTIVE
account to BLOCKED, and `PATCH /accounts/{id}/unblock` to restore a BLOCKED account
to ACTIVE. Neither endpoint requires a body; both return HTTP 200 with the account.
Repeated or invalid transitions return 409. CLOSED accounts cannot be reopened.
Missing authentication returns 401, another owner's account returns 403, and a
nonexistent account returns 404.

Only ACTIVE accounts can deposit, withdraw, send or receive transfers. Other
states return 409 without changing balances or history. Blocking is allowed with
a nonzero balance; account lookup and transaction history remain available.
Status changes update `updatedAt` and acquire the same pessimistic lock used by
financial operations. A concurrent movement either commits before the block or
sees the blocked state and fails. Blocking/unblocking does not create a financial
transaction or remove previous history.

```bash
curl -X PATCH http://localhost:8080/accounts/ACCOUNT_UUID/block \
  -H "Authorization: Bearer TOKEN"
curl -X PATCH http://localhost:8080/accounts/ACCOUNT_UUID/unblock \
  -H "Authorization: Bearer TOKEN"
```

### Close an account

`PATCH /accounts/{id}/close` permanently changes an ACTIVE account with zero
balance to CLOSED. Only the authenticated owner can close the account. No request
body is required; success returns HTTP 200 with the updated account.

A nonzero balance, a BLOCKED account or an already CLOSED account returns 409.
Blocked accounts must be unblocked first. Missing authentication returns 401,
another owner's account returns 403, and a nonexistent account returns 404.

Closure updates `updatedAt`, preserves the account and its transaction history,
and does not create a financial transaction. Owners can still consult their
closed accounts and history, but cannot reopen them or perform financial
operations on them, including receiving transfers.

The zero-balance check and status change hold the same pessimistic lock as
financial operations. If incoming money commits first, closure is rejected;
if closure commits first, the incoming movement is rejected.

```bash
curl -X PATCH http://localhost:8080/accounts/ACCOUNT_UUID/close \
  -H "Authorization: Bearer TOKEN"
```

## Authentication and account ownership

Register through `POST /customers`, then log in through `POST /auth/login` with e-mail and password.
Send the returned token as `Authorization: Bearer <token>` on private endpoints.
Configure `JWT_SECRET` with a strong HMAC secret of at least 32 UTF-8 bytes before starting the application.

JWT subjects contain the immutable customer UUID. Tokens issued with e-mail subjects are no longer accepted: log in again.
Changing an e-mail does not change the customer's token identity.

- `GET /customers` returns 403 for authenticated customers; there is no administrator listing.
- `GET/PUT/DELETE /customers/{id}` operate only on the authenticated customer's own ID.
- `POST /accounts` retains `customerId`, which must match the authenticated customer.
- `GET /accounts` lists only the authenticated customer's accounts.
- Account lookup, deposit, withdrawal and transaction history require account ownership.
- Transfers require ownership of the source account; the destination may belong to another customer.
- Missing/invalid authentication returns 401; an existing resource belonging to another customer returns 403; a nonexistent resource returns 404.

Deposits represent the authenticated account holder's operation, not an external payment intake.

---

## 📡 Available Endpoints

### Customers

| Method | Endpoint |
|---------|----------|
| POST | /customers |
| GET | /customers (403; listing unavailable) |
| GET | /customers/{id} |
| PUT | /customers/{id} |
| DELETE | /customers/{id} |

### Accounts

| Method | Endpoint |
|---------|----------|
| POST | /accounts |
| GET | /accounts |
| GET | /accounts/{id} |
| PATCH | /accounts/{id}/block |
| PATCH | /accounts/{id}/unblock |
| PATCH | /accounts/{id}/close |
| POST | /accounts/{id}/deposit |
| POST | /accounts/{id}/withdraw |
| POST | /accounts/transfer |

### Transactions

| Method | Endpoint |
|---------|----------|
| GET | /accounts/{id}/transactions |

### Paginated account statement

See also the financial retry contract below before retrying deposits, withdrawals or transfers.

`GET /accounts/{id}/transactions?page=0&size=20&startDate=2026-10-01&endDate=2026-10-31&type=TRANSFER`

Requires the account owner's bearer token. Includes outgoing and received transfers;
history remains available for blocked and closed accounts.

| Parameter | Default | Rules |
|-----------|---------|-------|
| `page` | `0` | Zero-based, nonnegative; `page * size` must fit a signed 32-bit integer |
| `size` | `20` | Between 1 and 100 |
| `startDate` | None | Inclusive date in `yyyy-MM-dd` format |
| `endDate` | None | Inclusive date in `yyyy-MM-dd` format; cannot precede `startDate` |
| `type` | All | `DEPOSIT`, `WITHDRAW` or `TRANSFER` (case-sensitive) |

Filters are optional and can be combined. Dates use the calendar of the stored
transaction timestamp (without timezone conversion). The end date includes the
whole day, excluding midnight of the following day. Invalid parameters return `400`.

**Breaking response change:** this endpoint now returns a page object instead of a
bare array. Clients must read transactions from `content` and request additional pages.
Each item keeps the existing transaction fields.

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

Results are ordered by `createdAt DESC, id DESC`, including a UUID tie-breaker for
equal timestamps. Totals reflect the account and selected filters. Pages beyond
the last page return empty `content` with the matching totals. Separate page
requests do not share a snapshot: newly inserted transactions can shift offsets.
Flyway migration V2 adds indexes for origin and destination statement queries.

### Financial operation retries

The deposit, withdrawal and transfer endpoints accept an optional `Idempotency-Key`
header. Generate a new key (for example, a UUID) for each intended operation and
reuse it for retries of that same operation:

```http
POST /accounts/ACCOUNT_UUID/deposit
Authorization: Bearer YOUR_TOKEN
Idempotency-Key: 4e28d1c9-2017-4148-9031-cabf326b5f21
Content-Type: application/json

{"amount": 25.00}
```

- Without the header, every successful request executes independently, preserving
  the previous behavior.
- Keys are case-sensitive, scoped to the authenticated customer across all three
  endpoints, and contain 1–128 ASCII letters, digits, `.`, `_`, `:` or `-`.
  Invalid keys return `400`.
- A successful retry returns `200` with the original `AccountResponse` snapshot,
  even if subsequent operations changed the balance or account status. Read the
  account endpoint to obtain its current state.
- The operation, source account, destination account and amount must match.
  Amounts such as `25`, `25.0` and `25.00` are equivalent. Reusing a key with
  different data returns `409` without moving money again.
- Authentication and source account ownership are checked before replay. A key
  never grants access to another customer's response.
- Concurrent requests for the same customer/key wait for the initial database
  transaction. After success they replay its result; after rollback one may retry
  the operation. Failed operations are not cached.
- Migration V3 stores the response and request identity in PostgreSQL. The key,
  balances and transaction history commit or roll back together. Records survive
  application restarts; there is currently no expiration or automatic cleanup.
  Records have no foreign keys to mutable customer/account records so retention
  is independent; deleting replay records would allow their keys to execute again.

### Create Customer

**POST** `/customers`

### Example Request

```json
{
  "fullName": "John Doe",
  "cpf": "12345678901",
  "email": "john.doe@email.com",
  "password": "12345678",
  "phone": "81999999999",
  "birthDate": "2000-01-01"
}
```

### Example Response

```json
{
  "id": "UUID",
  "fullName": "John Doe",
  "cpf": "12345678901",
  "email": "john.doe@email.com",
  "phone": "81999999999",
  "birthDate": "2000-01-01",
  "createdAt": "2026-07-15T12:24:43"
}
```

---

## 🗺️ Roadmap

- [x] Customer registration
- [x] Bean Validation
- [x] Global Exception Handler
- [x] Customer CRUD
- [x] Banking Accounts
- [x] Transactions
- [x] Swagger / OpenAPI
- [x] Password Encryption (BCrypt)
- [x] JWT Authentication with customer UUID and ownership checks
- [x] Unit Tests and HTTP ownership/security tests
- [ ] Docker
- [ ] Docker Compose

---

## 👨‍💻 Author

Developed by **Ítalo André**

Backend Developer | Java | Spring Boot | PostgreSQL
