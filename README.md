# 🏥 Patient Management System — Microservices Architecture

> A production-grade microservices project built to demonstrate real-world system design patterns: inter-service communication, event-driven architecture, centralized authentication, and cloud-native infrastructure provisioning.

---

## 📌 Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
    - [Logical Architecture](#logical-architecture)
    - [AWS Cloud Architecture](#aws-cloud-architecture)
- [Services](#services)
- [Tech Stack](#tech-stack)
- [Communication Patterns](#communication-patterns)
- [Authentication & Authorization](#authentication--authorization)
- [Infrastructure as Code](#infrastructure-as-code)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Project Structure](#project-structure)

---

## Overview

This project is a **microservices-based Patient Management System** designed with a heavy focus on **distributed systems architecture** and **cloud-native patterns** rather than business logic. It serves as a real-world reference implementation covering:

- **API Gateway** pattern with JWT-based route protection
- **Synchronous gRPC** communication between services
- **Asynchronous event-driven** communication via Apache Kafka with Protobuf serialization
- **OAuth 2.0 / OpenID Connect** via Keycloak
- **Infrastructure as Code** using AWS CDK (Java) targeting AWS ECS Fargate + RDS + MSK
- **LocalStack** for full cloud emulation locally

---

## Architecture

### Logical Architecture

![Logical Architecture Diagram](https://raw.githubusercontent.com/RaidThabet/patient-management-system/main/docs/patient-management.png)

> **Key Design Decisions:**
> - The **API Gateway** is the single entry point — clients never call services directly.
> - **gRPC** is used for synchronous, low-latency internal calls (Patient → Billing) with Protobuf contracts defined in `.proto` files shared across services.
> - **Kafka** is used for asynchronous fire-and-forget events (Patient → Analytics), fully decoupling the producer from any downstream consumers.
> - **Protobuf** serialization is used for both gRPC messages and Kafka payloads, providing compact binary encoding and a strongly-typed contract.

---

### AWS Cloud Architecture

![AWS Cloud Architecture Diagram](https://raw.githubusercontent.com/RaidThabet/patient-management-system/main/docs/patient-management-aws.png)

> **Infrastructure is defined 100% as code** using the **AWS CDK (Java)** and can be deployed to a real AWS account or emulated locally via **LocalStack**.

---

## Services

| Service | Port(s) | Role | Key Tech |
|---|---|---|---|
| **api-gateway** | `4004` | Single entry point, JWT validation, request routing | Spring Cloud Gateway, OAuth2 Resource Server |
| **patient-service** | `4000` | Core CRUD for patient records, orchestrates downstream calls | Spring Boot, JPA, PostgreSQL, Kafka Producer, gRPC Client |
| **billing-service** | `4001` (HTTP) `9001` (gRPC) | Creates billing accounts triggered by patient registration | Spring Boot, gRPC Server, Protobuf |
| **analytics-service** | `4002` | Consumes patient events for analytics processing | Spring Boot, Kafka Consumer, Protobuf |
| **keycloak** | `8080` | Identity provider, issues JWT tokens | Keycloak 26.3.3, PostgreSQL backend |
| **infrastructure** | — | AWS CDK stack definition (IaC) | AWS CDK Java, LocalStack |

---

## Tech Stack

### Core
| Category | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 4.0.2 |
| Build Tool | Apache Maven |

### Communication
| Pattern | Technology |
|---|---|
| REST (external) | Spring MVC |
| API Gateway | Spring Cloud Gateway (WebFlux) |
| Synchronous RPC | gRPC 1.69 + Protobuf 4.29 |
| Async Messaging | Apache Kafka + Protobuf binary serialization |

### Data
| Layer | Technology |
|---|---|
| ORM | Spring Data JPA / Hibernate |
| Database | PostgreSQL (RDS on AWS, local volume for dev) |
| Schema management | Hibernate DDL auto (`update`) |

### Security
| Component | Technology |
|---|---|
| Identity Provider | Keycloak 26.3.3 |
| Protocol | OAuth 2.0 / OpenID Connect |
| Token format | JWT (RS256) |
| Validation | Spring Security OAuth2 Resource Server |

### Infrastructure & DevOps
| Tool | Purpose |
|---|---|
| Docker | Containerization (multi-stage builds) |
| AWS CDK (Java) | Infrastructure as Code |
| AWS ECS Fargate | Serverless container hosting |
| AWS RDS (PostgreSQL) | Managed database |
| AWS MSK | Managed Kafka |
| AWS ALB | Application Load Balancer |
| AWS CloudMap | Service discovery (`*.patient-management.local`) |
| AWS CloudWatch | Centralized logging |
| AWS Secrets Manager | Database credential management |
| LocalStack | Full AWS cloud emulation locally |

---

## Communication Patterns

### 1. REST (Client → API Gateway → Patient Service)
All external traffic enters through the API Gateway on port `4004`. The gateway validates the JWT Bearer token issued by Keycloak and proxies the request to the target service after stripping the `/api` prefix.

```
Client  →  [JWT]  →  API Gateway :4004  →  /patients/**  →  Patient Service :4000
```

### 2. gRPC (Patient Service → Billing Service)
When a new patient is registered, Patient Service makes a **synchronous blocking gRPC call** to Billing Service to create a corresponding billing account. The contract is defined in `billing_service.proto`:

```protobuf
service BillingService {
  rpc CreateBillingAccount (BillingRequest) returns (BillingResponse);
}
```

This ensures the billing account is provisioned as part of the same request lifecycle.

### 3. Kafka Event (Patient Service → Analytics Service)
After patient creation, an asynchronous **Kafka event** is published to the `patient` topic. The payload is a **Protobuf-serialized** `PatientEvent` message:

```protobuf
message PatientEvent {
  string patientId = 1;
  string name      = 2;
  string email     = 3;
  string event_type = 4;  // e.g. "PATIENT_CREATED"
}
```

Analytics Service is a Kafka consumer that deserializes this binary payload and processes it independently — completely decoupled from the producer.

---

## Authentication & Authorization

Authentication is handled by a dedicated **Keycloak** instance backed by PostgreSQL.

**Flow:**
1. Client requests an access token from Keycloak using **client credentials** grant.
2. Keycloak issues a signed **JWT** token for the configured realm (`patient-management`) and client (`ms-api`).
3. Every request to the API Gateway must include `Authorization: Bearer <token>`.
4. The gateway validates the JWT signature against Keycloak's JWKS endpoint — no request reaches a downstream service without a valid token.

```
POST /realms/patient-management/protocol/openid-connect/token
  grant_type=client_credentials
  client_id=ms-api
  client_secret=<secret>
  → { "access_token": "eyJ..." }
```

---

## Infrastructure as Code

The entire AWS infrastructure is defined in a single **AWS CDK Java stack** (`infrastructure/src/main/java/com/pm/stack/LocalStack.java`) and can be deployed to AWS or emulated locally via **LocalStack**.

**Resources provisioned:**
- **VPC** with 2 availability zones
- **ECS Fargate Cluster** with AWS CloudMap service discovery
- **5 Fargate Services** (api-gateway, patient-service, billing-service, analytics-service, keycloak)
- **2 RDS PostgreSQL instances** (keycloak-db, patient-service-db) with auto-generated Secrets Manager credentials
- **Amazon MSK cluster** (Kafka 3.5.1, 2 × m5.xlarge brokers)
- **Application Load Balancer** fronting the API Gateway
- **CloudWatch Log Groups** per service (1-day retention)
- **Route53 Health Checks** for database readiness before service startup

### Deploy to LocalStack

```bash
# 1. Synthesize the CDK template
cd infrastructure
mvn compile exec:java

# 2. Deploy to LocalStack
chmod +x localstack-deploy.sh
./localstack-deploy.sh

# Output: Load Balancer DNS  →  lb-<id>.elb.localhost.localstack.cloud
```

---

## Getting Started

### Prerequisites
- Java 17+
- Maven 3.9+
- Docker & Docker Compose
- AWS CLI (configured for LocalStack)
- LocalStack (for cloud emulation)

### Running Locally with Docker

```bash
# Build all service images
docker build -t patient-service    ./patient-service
docker build -t billing-service    ./billing-service
docker build -t analytics-service  ./analytics-service
docker build -t api-gateway        ./api-gateway
docker build -t keycloak-service   ./keycloak

# Deploy stack to LocalStack
cd infrastructure
./localstack-deploy.sh
```

### Running Individual Services (Dev Mode)

Each service is a standard Spring Boot application:

```bash
cd patient-service
./mvnw spring-boot:run
```

---

## API Reference

All requests go through the API Gateway. A valid JWT token is required.

### Get Access Token

```http
POST http://<ALB_DNS>:8080/realms/patient-management/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=ms-api&client_secret=<secret>
```

### Patient Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/patients` | Retrieve all patients |
| `POST` | `/api/patients` | Create a new patient |
| `PUT` | `/api/patients/{id}` | Update an existing patient |
| `DELETE` | `/api/patients/{id}` | Delete a patient |

#### Create Patient — Request Body

```json
{
  "name": "John Doe",
  "email": "john.doe@example.com",
  "address": "123 Main Street",
  "dateOfBirth": "1990-01-15",
  "registeredDate": "2024-11-28"
}
```

**On creation, the system automatically:**
1. Persists the patient to PostgreSQL
2. Calls Billing Service via **gRPC** to create a billing account
3. Publishes a `PATIENT_CREATED` event to the **Kafka** `patient` topic

### gRPC — Billing Service (Direct)

```http
GRPC localhost:9001/BillingService/CreateBillingAccount

{
  "patientId": "uuid",
  "name": "John Doe",
  "email": "john.doe@example.com"
}
```

---

## Key Architectural Concepts Demonstrated

| Concept | Implementation |
|---|---|
| **API Gateway Pattern** | Spring Cloud Gateway as single entry point with route predicates and filters |
| **Database per Service** | Patient Service and Keycloak each have isolated RDS instances |
| **Synchronous Inter-Service Communication** | gRPC with Protobuf contracts (Patient → Billing) |
| **Asynchronous Event-Driven Communication** | Kafka with Protobuf serialization (Patient → Analytics) |
| **Centralized Identity Management** | Keycloak with OAuth2/OIDC, all routes protected by JWT |
| **Service Discovery** | AWS CloudMap with private DNS (`*.patient-management.local`) |
| **Infrastructure as Code** | AWS CDK (Java) defining entire stack declaratively |
| **Cloud Emulation** | LocalStack for full AWS-compatible local development |
| **Multi-Stage Docker Builds** | Layered images: Maven build → minimal JRE runtime |
| **Secrets Management** | AWS Secrets Manager for auto-generated RDS credentials |
| **Centralized Logging** | CloudWatch Log Groups per ECS service |
| **Health-Checked Deployments** | Route53 TCP health checks on RDS before service startup |

