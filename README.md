# Keyra

Keyra is a self-hosted identity and API key service for companies that run more than one product. It gives a company one account system across all of its products, and issues API keys that are scoped to a single product.

A company deploys its own Keyra instance with Terraform, on AWS or GCP, backed by its own Postgres database. No data is shared between companies.

> **Status:** early development. Milestone 1 (manager and user login) is done. This document is the specification the implementation follows.

---

## Product

### Goals

1. **One account, every product.** A user registers once. Any product in the company accepts that account with no per-product signup or enrollment.
2. **Product-scoped API keys.** Every key belongs to exactly one product, carries explicit scopes and an expiry, and can be rotated or revoked without touching other products.
3. **Simple integration.** A product needs only a REST call (or a JWKS fetch) to verify a user or a key. No SDK is required.
4. **Isolated, repeatable deployment.** One Terraform module stands up a full instance. Each company owns its infrastructure and its data.
5. **Production-minded defaults.** Hashed credentials, short-lived tokens, auditable key lifecycle, and secrets kept out of code and state.

### Non-goals (v1)

- Social login, SAML, SCIM, and MFA.
- Email verification and password reset by email.
- A hosted multi-tenant SaaS version of Keyra.
- Enforcing rate limits on behalf of products. Keyra stores and returns the limit; the product enforces it.
- A front-end or server-rendered pages. Keyra is a REST/JSON API only.

### Concepts

| Term | Meaning |
|---|---|
| Instance | One deployment of Keyra for one company, with its own database. |
| User | An end user of the company's products. One user account works across all products. |
| Manager | A person who administers Keyra itself. A separate account type from users. |
| Admin | A manager with instance-wide rights. |
| Product | A company product registered in Keyra. Owns a set of API keys. |
| API key | A credential for programmatic access to one product, with scopes, expiry, and a rate limit. |

### Actors and roles

- **Users** register and log in. They never see products, keys, or manager endpoints.
- **Managers** have one of two roles:
  - `ADMIN`: creates products, adds and removes managers, assigns managers to products, and manages keys for every product.
  - `MANAGER`: manages keys and settings only for the products they are assigned to.
- Manager accounts cannot be self-registered. The first admin is created at startup from `KEYRA_BOOTSTRAP_ADMIN_*` environment variables. After that, admins create further managers.

---

## Features

### 1. User accounts

- Register with email and password. Email is unique per instance and stored lowercased.
- Password length: 12 to 128 characters. Hashed as BCrypt(base64(SHA-256(password))), cost 12. The SHA-256 pre-hash gets around BCrypt's 72-byte input limit, so every character of a long password counts.
- Login returns an access token and a refresh token.
- Refresh exchanges a valid refresh token for a new access token and rotates the refresh token.
- Logout revokes the presented refresh token.
- A manager can disable a user. Disabled users cannot log in or refresh.

### 2. Manager accounts

- Login with email and password, same hashing rules as users.
- Admins can create, disable, and delete managers, and assign a manager to one or more products.
- Manager tokens are rejected by user endpoints, and user tokens are rejected by manager endpoints.

### 3. Products

- An admin creates a product with a name, a unique slug, and an optional description.
- Products can be updated and deactivated. Deactivating a product makes all of its keys fail validation.
- A product has zero or more assigned managers.

### 4. API keys

- Created by an admin or an assigned manager, for one product.
- Each key has:
  - a human-readable name
  - a list of scopes (free-form strings such as `orders:read`)
  - an expiry: default 90 days, maximum 365 days, or none if an admin explicitly allows it
  - a rate limit in requests per minute (default 60)
  - a status: `ACTIVE`, `REVOKED`, or `EXPIRED`
- Format: `kyr_<prefix>_<secret>`. The prefix is 8 characters; the secret is 32 random bytes, base62-encoded.
- The full key is shown exactly once, at creation or rotation. Keyra stores only the prefix and a SHA-256 hash.
- **Rotation:** issues a new secret; the old secret stays valid for a grace period (`KEYRA_KEY_ROTATION_GRACE_HOURS`, default 24).
- **Revocation:** immediate and irreversible.
- Every lifecycle event (create, rotate, revoke, expire) is written to `audit_events` with the acting manager and a timestamp.

### 5. Key validation

A product backend validates a key by calling Keyra:

```http
POST /api/v1/keys/validate
{ "product": "orders", "key": "kyr_ab12cd34_..." }
```

```json
{
  "valid": true,
  "keyId": "...",
  "product": "orders",
  "scopes": ["orders:read"],
  "rateLimitPerMinute": 60,
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

- A key that is unknown, expired, revoked, on a deactivated product, or belongs to a different product returns `{"valid": false}` with no further detail.
- The endpoint is rate limited per source IP to resist guessing.
- Products should cache positive results for 30 to 60 seconds.

### 6. User token verification

Products verify user access tokens locally with Keyra's public keys:

- `GET /.well-known/jwks.json` returns the current signing keys.
- Tokens are signed with RS256.
- Required claims: `iss` (instance URL), `sub` (user id), `aud` (`keyra-user`), `exp`, `iat`.
- Manager tokens use `aud` = `keyra-manager` and must never be accepted by products.

### Token policy

| Token | Lifetime | Storage |
|---|---|---|
| User access token (JWT) | 15 minutes | Client only |
| User refresh token | 7 days, rotated on use | SHA-256 hash in Postgres |
| Manager access token (JWT) | 15 minutes | Client only |
| Manager refresh token | 24 hours, rotated on use | SHA-256 hash in Postgres |

### API overview (draft)

| Area | Endpoints |
|---|---|
| User auth | `POST /api/auth/register`, `/login`, `/refresh`, `/logout`; `GET /api/auth/me` |
| Manager auth | `POST /api/manager/auth/login`, `/refresh`, `/logout`; `GET /api/manager/me` |
| Managers | `POST/GET/PATCH/DELETE /api/manager/managers` (admin only) |
| Products | `POST/GET/PATCH /api/manager/products`, `PUT /api/manager/products/{id}/managers` |
| Keys | `POST/GET /api/manager/products/{id}/keys`, `POST .../keys/{keyId}/rotate`, `POST .../keys/{keyId}/revoke` |
| Validation | `POST /api/v1/keys/validate` |
| Discovery | `GET /.well-known/jwks.json`, `GET /actuator/health` |

All bodies are JSON. Errors use RFC 7807 problem details.

### Data model (initial)

- `users(id, email, password_hash, status, created_at)`
- `managers(id, email, password_hash, role, status, created_at)`
- `refresh_tokens(id, subject_id, subject_type, token_hash, expires_at, revoked_at)`
- `products(id, name, slug, description, active, created_at)`
- `product_managers(product_id, manager_id)`
- `api_keys(id, product_id, name, prefix, key_hash, previous_key_hash, previous_valid_until, scopes, rate_limit_per_minute, expires_at, status, created_by, created_at, revoked_at)`
- `audit_events(id, actor_id, actor_type, action, target_type, target_id, created_at)`

### Non-functional requirements

- **Security:** no plaintext credentials stored or logged. HTTPS only in deployed environments. The signing key and database credentials come from the cloud secret manager, never from the repository.
- **Performance:** key validation p95 under 50 ms at 200 requests per second on a single small container.
- **Availability:** stateless service, scales horizontally; two or more replicas in deployed instances.
- **Observability:** structured JSON logs, health endpoint, and metrics via Spring Actuator and Micrometer.

### Milestones

1. **Manager and user login.** Both auth realms, JWT issuing, refresh tokens, bootstrap admin, JWKS endpoint.
2. **Products and managers.** Product CRUD, manager management, manager-to-product assignment.
3. **API keys.** Creation, hashing, listing, and the validation endpoint.
4. **Key lifecycle.** Rotation with grace period, revocation, expiry handling, audit events, rate limit fields.
5. **Terraform on AWS.** Module, secrets wiring, and a working end-to-end deployment.
6. **Terraform on GCP.** Same module interface on Cloud Run and Cloud SQL.

### Open questions

- Should refresh tokens be bound to a device or IP in a later version?
- Should Keyra offer an optional rate-limit counter so products don't need their own?
- Is a per-product scope catalog needed, or are free-form scope strings enough?

---

## Architecture

```
 Users ----> +--------------------------+
             |  Keyra                   |
 Managers -> |  Spring Boot, REST/JSON  | ---> Postgres
             |  (stateless)             |
 Products -> +--------------------------+
```

- One stateless Spring Boot service, run as one or more identical containers behind a load balancer. Postgres is the only stateful dependency.
- Separate Spring Security filter chains per caller type:
  - `/api/auth/**`: users
  - `/api/manager/**`: managers
  - `/api/v1/keys/validate`: products validating a key

### Technology

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1: Web MVC, Security, Data JPA, Validation, OAuth2 Resource Server (to be added) |
| Build | Maven, via the wrapper (`./mvnw`) |
| Database | PostgreSQL |
| Migrations | Flyway |
| Tests | JUnit 5, Testcontainers (real Postgres) |
| Local database | Docker Compose (`compose.example.yaml` → `compose.yaml`, Postgres 18) |
| Formatting | Spotless + palantir-java-format |
| Static analysis | Error Prone |
| Infrastructure | Terraform |

### Code layout

The code is organized by layer under `com.rodrigues.keyra`:

```
com.rodrigues.keyra
├── controller/   REST controllers. Thin: validate input, call a service, map to DTOs.
├── service/      Business logic and transactions.
├── repository/   Spring Data JPA repositories.
├── entity/       JPA entities, mapped 1:1 to Flyway tables.
├── dto/          Request/response records. Entities are never returned from controllers.
├── security/     Filter chains, JWT issuing/verification, password and key hashing.
├── config/       Spring configuration and @ConfigurationProperties.
└── exception/    Exceptions and the RFC 7807 handler.

src/main/resources/db/migration/   Flyway migrations, V<n>__<description>.sql
```

---

## Development

### Prerequisites

- JDK 21
- Docker with the Compose plugin, running (Compose provides the local database; Testcontainers provides one for tests)
- Local copies of two gitignored files:
  ```bash
  cp compose.example.yaml compose.yaml
  cp config/application.example.properties config/application.properties
  ```

### Commands

| Task | Command |
|---|---|
| Run locally (persistent Postgres via Compose) | `./mvnw spring-boot:run` |
| Run locally (throwaway Postgres via Testcontainers) | `./mvnw spring-boot:test-run` |
| All tests | `./mvnw test` |
| Single test | `./mvnw test -Dtest=ClassName#method` |
| Full build + tests | `./mvnw verify` |
| Format | `./mvnw spotless:apply` |
| Check formatting | `./mvnw spotless:check` |

Running the app (`spring-boot:run` or `KeyraApplication` from the IDE) starts the Postgres in `compose.yaml` if it isn't running, and stops it on exit only if it started it. A container you started yourself with `docker compose up -d` is reused and left running. Data is kept in the `postgres-data` volume; `docker compose down -v` wipes it. The database listens on `127.0.0.1:5432` (database, user and password are all `keyra`).

Local settings (issuer URL, JWT signing key, bootstrap admin `admin@keyra.local` / `local-admin-password`) are in `config/application.properties`, copied from `config/application.example.properties`. Spring Boot reads that file from the working directory, so it applies to every local launch, but it is not in the jar. Never copy it into a deployed image. The example uses a throwaway signing key, so tokens stop working whenever the app restarts, including DevTools restarts on save. Its comments show how to set a fixed local key instead.

`spring-boot:test-run` and the integration tests use the `local` profile (`src/test/resources/application-local.properties`), which swaps Compose for Testcontainers and supplies its own settings. Tests don't need `config/application.properties`, and ignore it if present. Without `config/application.properties`, Keyra refuses to start unless `KEYRA_ISSUER_URL`, `KEYRA_JWT_PRIVATE_KEY` and `KEYRA_JWT_KEY_ID` are set.

### Rules

- **Formatting:** palantir-java-format (4-space indent), enforced by Spotless. Run `spotless:apply` before committing.
- **Static analysis:** Error Prone runs on every compile and fails the build on errors. Fix the code instead of suppressing the check. Always build through `./mvnw`, because Error Prone needs the JVM flags in `.mvn/jvm.config`.
- **Schema:** change it only through a new Flyway migration. Never edit a migration that has already been applied. Hibernate must not create or alter tables.
- **Tests:** unit tests for services, plus integration tests against real Postgres via `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`.
- **Secrets:** never commit credentials or keys. Configuration comes from `KEYRA_*` environment variables.

---

## Deployment

Keyra ships a reusable Terraform module, `modules/keyra`, with the same input variables on each cloud and a cloud-specific implementation underneath.

| | AWS | GCP |
|---|---|---|
| Compute | ECS Fargate | Cloud Run |
| Database | RDS for PostgreSQL | Cloud SQL for PostgreSQL |
| Ingress | Application Load Balancer | Cloud Run managed HTTPS |
| Secrets | Secrets Manager | Secret Manager |

Core inputs: instance name, region, container image, replica count, database size, and the bootstrap admin email and password. Applying the module outputs the public URL of the new instance.

### Configuration

Environment variables, populated by Terraform in deployed instances:

| Variable | Purpose |
|---|---|
| `KEYRA_DB_URL`, `KEYRA_DB_USER`, `KEYRA_DB_PASSWORD` | Postgres connection |
| `KEYRA_JWT_PRIVATE_KEY`, `KEYRA_JWT_KEY_ID` | RS256 signing key (PKCS#8 PEM) and its `kid` |
| `KEYRA_ISSUER_URL` | `iss` claim / public URL |
| `KEYRA_BOOTSTRAP_ADMIN_EMAIL`, `KEYRA_BOOTSTRAP_ADMIN_PASSWORD` | First admin, created at startup |
| `KEYRA_KEY_ROTATION_GRACE_HOURS` | Old-secret grace period after rotation (default 24) |
