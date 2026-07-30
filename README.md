# 🏦 Banking API

A RESTful Banking API built with **Java 17** and **Spring Boot** to simulate banking operations while applying modern backend development best practices.

This project is part of my backend learning journey, where I apply clean architecture principles, REST API design, Spring ecosystem technologies, and PostgreSQL to build a real-world banking application.

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
         CustomerController
                 │
                 ▼
          CustomerService
                 │
                 ▼
        CustomerRepository
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

### Customer Module

- Customer registration
- Request validation using Bean Validation
- Global exception handling
- Duplicate CPF validation
- Duplicate email validation

---

## 📡 Available Endpoints

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

## ▶️ Running the Project

### Clone the repository

```bash
git clone https://github.com/your-username/banking-api.git
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

## 🗺️ Roadmap

- [x] Customer registration
- [x] Bean Validation
- [x] Global Exception Handler
- [x] Customer CRUD
- [ ] JWT Authentication
- [ ] Password Encryption (BCrypt)
- [ ] Swagger / OpenAPI
- [ ] Unit Tests
- [ ] Docker
- [ ] Docker Compose
- [x] Banking Accounts
- [ ] Transactions

---

## 👨‍💻 Author

Developed by **Ítalo André**

Backend Developer | Java | Spring Boot | PostgreSQL
