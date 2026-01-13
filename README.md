# Payment Service

## Overview

This project implements a **Payment Service** exposing a REST API that allows users to create payments.
The main goal is to demonstrate a **robust, transactional, concurrent and fault-tolerant architecture**.

The service is built with **Spring Boot**, **PostgreSQL**, and **Kafka**, and relies on the **Outbox Pattern** to safely publish payment notifications asynchronously.

---

## Focus

- High Availability
- Transactional Processing
- Error Handling

---

## Tech Stack

- Java / Spring Boot (Maven)
- PostgreSQL
- Apache Kafka
- Docker & Docker Compose
- Flyway (database migrations)
- JPA / Hibernate
- Swagger / OpenAPI

---

## How to Run the Application

### Prerequisites
- Java 21+
- Docker
- Maven

### Start the service

A `docker-compose.yml` file is available at the project root.
With Spring Boot 4, Docker services (PostgreSQL and Kafka) are automatically started when using the local profile.

Run from IntelliJ or CLI:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

---

### REST API Documentation (Swagger)

The API is fully documented using Swagger / OpenAPI.

### Swagger UI

http://localhost:8080/swagger-ui/index.html


### Main endpoint

```yaml
POST /api/payments
```

---


### How to Test End-to-End

1. Seeded data

A test account is automatically created using Flyway migrations.

2. Call the API using Swagger

Required header

X-User-Id: 22222222-2222-2222-2222-222222222222


Request body

```json
{
"accountId": "11111111-1111-1111-1111-111111111111",
"amount": 100,
"currency": "CHF",
"paymentId": "3fa85f64-5717-4562-b3fc-2c963f66afa9"
}
```

3. Observe Kafka notification

Consume messages directly from the Kafka container:

```bash
docker exec -it kafka \
kafka-console-consumer \
--bootstrap-server localhost:9092 \
--topic paymentservice-alpian-dailybanking-dev \
--from-beginning
```


You should see the payment notification published asynchronously.

---

### Architecture (High Level)

- Single transactional flow for payment creation

- Pessimistic locking (SELECT FOR UPDATE) on account to prevent double spending

- Database unique constraint on (account_id, payment_id) for idempotency

- Outbox Pattern to guarantee reliable Kafka publishing

- Asynchronous notification via Kafka

- Fault-tolerant outbox worker with retry and recovery logic

- Centralized error handling using @ControllerAdvice
