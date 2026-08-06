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

## 📡 Available Endpoints

### Customers

| Method | Endpoint |
|---------|----------|
| POST | /customers |
| GET | /customers |
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
- [ ] Password Encryption (BCrypt)
- [ ] JWT Authentication
- [ ] Unit Tests
- [ ] Docker
- [ ] Docker Compose

---

## 👨‍💻 Author

Developed by **Ítalo André**

Backend Developer | Java | Spring Boot | PostgreSQL
