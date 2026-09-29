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

## Authentication and account ownership

Register through `POST /customers`, then log in through `POST /auth/login` with e-mail and password.
Send the returned token as `Authorization: Bearer <token>` on private endpoints.
Configure `JWT_SECRET` with a strong HMAC secret of at least 32 UTF-8 bytes before starting the application or running context tests.

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
| POST | /accounts/{id}/deposit |
| POST | /accounts/{id}/withdraw |
| POST | /accounts/transfer |

### Transactions

| Method | Endpoint |
|---------|----------|
| GET | /accounts/{id}/transactions |

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
