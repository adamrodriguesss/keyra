# Keyra

Keyra is a self-hosted identity and API key service for companies that run more than one product. It gives a company one account system across all of its products, and issues API keys that are scoped to a single product.

A company deploys its own Keyra instance with Terraform, on AWS or GCP, backed by its own Postgres database. No data is shared between companies.

> **Status:** early development. Milestones 1 (manager and user login), 2 (products and managers), 3 (API keys), 4 (key lifecycle), 5 (Terraform on GCP), 6 (user-issued keys) and 7 (Terraform on AWS) are done. This document is the specification the implementation follows.

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
| API key | A credential for programmatic access to one product, with scopes, expiry, and a rate limit. Manager-issued, or owned by a user (a user key). |

### Actors and roles

- **Users** register and log in. They never see products or manager endpoints. On a product that enables user keys, they can mint and manage their own keys.
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
- A product can enable user keys (`user_keys_enabled`, off by default) with default scopes for them and a cap on active keys per user (`max_keys_per_user`, default 10). Admins and assigned managers set this with `PUT /api/manager/products/{id}/user-keys`.

### 4. API keys

- Created by an admin or an assigned manager, for one product.
- Each key has:
  - a human-readable name
  - a list of scopes (free-form strings such as `orders:read`)
  - an expiry: default 90 days, maximum 365 days, or none if an admin explicitly allows it
  - a rate limit in requests per minute (default 60)
  - a status: `ACTIVE`, `REVOKED`, or `EXPIRED` (`EXPIRED` is derived from `expires_at`, not stored)
- Format: `kyr_<prefix>_<secret>`. The prefix is 8 characters; the secret is 32 random bytes, base62-encoded.
- The full key is shown exactly once, at creation or rotation. Keyra stores only the prefix and a SHA-256 hash.
- **Rotation:** issues a new secret under the same key id and prefix; the old secret stays valid for a grace period (`KEYRA_KEY_ROTATION_GRACE_HOURS`, default 24). Only one old secret is kept: rotating again inside the grace period ends the older one immediately.
- **Revocation:** immediate and irreversible. Both the current and any old secret stop validating.
- Only an `ACTIVE` key can be rotated or revoked; a revoked or expired key returns `409`. A key on a product the manager can't reach returns `404`.
- **User keys:** on a product with user keys enabled, a user creates, lists, rotates and revokes their own keys at `/api/products/{slug}/keys` with a user access token. Omitted scopes default to the product's `default_user_scopes`; the user may choose others, and never-expiring keys are not allowed. Keyra stays tier-agnostic: the company decides who may mint what. A missing, inactive or non-enabled product, or another user's key, returns `404`; reaching `max_keys_per_user` active keys returns `409`.
- **Changing limits:** a manager changes a key's scopes or rate limit with `PATCH .../keys/{keyId}` (active keys only, `409` otherwise), for upgrades and downgrades. `GET .../keys?ownerUserId=` filters by owner. `POST .../products/{id}/users/{userId}/keys/revoke` revokes all of a user's active keys on the product (cancellation) and returns them.
- Every lifecycle event (create, rotate, revoke, update, expire) is written to `audit_events` with a timestamp. Create, rotate, revoke and update record the actor: the manager, or the user (actor type `USER`) for user keys. Expiry is derived, so it is recorded with actor type `SYSTEM` (no actor id) the first time any request finds the key expired (validation, listing, or a rotate/revoke attempt), dated at the key's `expires_at`. It is recorded exactly once per key; a revoked key never gets an expire event. A manager reads them back with `GET /api/manager/products/{id}/audit-events` (newest first, actor email and key name resolved). Listing does not itself record expiry, so an expire event shows up once a key read has noticed it.

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
  "expiresAt": "2027-01-01T00:00:00Z",
  "userId": null
}
```

- `userId` is the owning user for user keys, `null` for manager-issued ones. Use it to meter and bill per user. A key whose owner is disabled returns `{"valid": false}`; re-enabling the user restores it.
- A key that is unknown, expired, revoked, on a deactivated product, or belongs to a different product returns `{"valid": false}` with no further detail.
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
| Users | `GET /api/manager/users?q=&page=&size=` (search by email, newest first, size capped at 100), `PATCH /api/manager/users/{id}` (any manager; enable or disable a user) |
| Products | `POST/GET/PATCH /api/manager/products`, `PUT /api/manager/products/{id}/managers`, `PUT /api/manager/products/{id}/user-keys` (admin or assigned manager) |
| Keys | `POST/GET /api/manager/products/{id}/keys`, `POST .../keys/{keyId}/rotate`, `POST .../keys/{keyId}/revoke`, `PATCH .../keys/{keyId}`, `POST /api/manager/products/{id}/users/{userId}/keys/revoke` |
| Audit | `GET /api/manager/products/{id}/audit-events?keyId=&page=&size=` (admin or assigned manager; newest first, size capped at 100) |
| User keys | `POST/GET /api/products/{slug}/keys`, `POST .../keys/{keyId}/rotate`, `POST .../keys/{keyId}/revoke` (user token) |
| Validation | `POST /api/v1/keys/validate` |
| Discovery | `GET /.well-known/jwks.json`, `GET /actuator/health` |

All bodies are JSON. Errors use RFC 7807 problem details.

Disabling or deleting an account revokes its refresh tokens immediately, but an access token already issued stays valid until it expires (15 minutes at most). The same applies to a manager whose role is changed: the old role is honored until their access token expires.

### Data model (initial)

- `users(id, email, password_hash, status, created_at)`
- `managers(id, email, password_hash, role, status, created_at)`
- `refresh_tokens(id, subject_id, subject_type, token_hash, expires_at, revoked_at)`
- `products(id, name, slug, description, active, user_keys_enabled, default_user_scopes, max_keys_per_user, created_at)`
- `product_managers(product_id, manager_id)`
- `api_keys(id, product_id, name, prefix, key_hash, previous_key_hash, previous_valid_until, scopes, rate_limit_per_minute, expires_at, status, created_by, owner_user_id, created_at, revoked_at, expiry_audited)`
- `audit_events(id, actor_id, actor_type, action, target_type, target_id, created_at)`

### SaaS deployment

For a company whose end users mint their own keys, keep Keyra private so that only the company backend reaches it, because users choose their own scopes: the company decides who may mint what, and the backend enforces it (for example by checking scopes against the user's subscription tier on validation). Use a `MANAGER` account assigned to the product as the backend's service account for `PATCH` limits and bulk revoke.

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
5. **Terraform on GCP.** Module on Cloud Run with Cloud SQL or your own Postgres, secrets wiring, and a working end-to-end deployment.
6. **User-issued keys.** Users mint their own keys on enabled products; `userId` in validation; PATCH limits and bulk revoke for managers.
7. **Terraform on AWS.** Same module interface on ECS Fargate and RDS.

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
Dockerfile                          Container image (configured only by KEYRA_* env vars)
infra/modules/keyra/gcp/            Terraform module for GCP (tests/ runs offline with terraform test)
infra/modules/keyra/aws/            Terraform module for AWS (same; app_db/ is the app-database function)
infra/examples/gcp/, infra/examples/aws/   Root modules that deploy one instance
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
| Container image | `docker build -t keyra .` |
| Terraform format / module tests | `terraform fmt -recursive infra`, `terraform -chdir=infra/modules/keyra/gcp test` |
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

Secrets never reach Terraform state: the JWT signing key and database password are generated as ephemeral values and written to the secret manager through write-only attributes, and the bootstrap admin password is generated the same way (or passed as an ephemeral input). This needs Terraform 1.11 or later.

### Deploy to GCP

The module (`infra/modules/keyra/gcp`) runs Keyra on Cloud Run with a public `https://<name>-<project-number>.<region>.run.app` URL, which is also the token issuer. By default it runs the published image from GitHub Container Registry, proxied through an Artifact Registry remote repository. You don't clone this repository or install Docker.

1. Prerequisites: Terraform 1.11+ and `gcloud` logged in with rights to create the resources.
2. Open the [configurator](https://adamrodriguesss.github.io/keyra/configurator.html#gcp), fill it in, and save the generated `main.tf` in an empty folder.
3. In that folder, run the three commands the page shows:
   ```bash
   gcloud storage buckets create gs://<project>-tfstate --location=<region> --uniform-bucket-level-access --project=<project>   # once per project
   gcloud storage buckets update gs://<project>-tfstate --versioning
   terraform init && terraform apply
   gcloud secrets versions access latest --secret=<name>-bootstrap-admin-password --project=<project>   # the generated admin password
   ```

`terraform apply` ends with a health check on `/actuator/health`; a warning there means the instance isn't up yet, so read the Cloud Run logs.

#### Build your own image

Set `image` in the module block (the configurator's "Advanced: custom image") and push it first:

```bash
gcloud artifacts repositories create keyra --repository-format=docker --location=<region>
gcloud auth configure-docker <region>-docker.pkg.dev
docker build -t <region>-docker.pkg.dev/<project>/keyra/keyra:<version> .
docker push <region>-docker.pkg.dev/<project>/keyra/keyra:<version>
```

#### Releasing

Bump `local.keyra_version` in `infra/modules/keyra/gcp/main.tf` and `infra/modules/keyra/aws/main.tf`, and `KEYRA_VERSION` in `docs/configurator.html`, then push the tag `v<version>`. The release workflow checks the tag against both modules' `keyra_version` and publishes `ghcr.io/adamrodriguesss/keyra:<version>` and `:latest`. After the first release, make the `keyra` GHCR package public so deployments can pull it anonymously.

Cloud SQL and the Cloud Run service have deletion protection on by default (`deletion_protection = false` to tear an instance down). Bump `jwt_key_version` or `db_password_version` to rotate those secrets.

`terraform destroy` can stop at the subnet with `resourceInUseByAnotherResource`: Cloud Run's VPC egress holds internal addresses in it that Google releases an hour or two after the service is deleted. Everything billable is already gone at that point; run `terraform destroy` again later to remove the network.

#### Database and network modes

| Mode | Inputs | What the module creates |
|---|---|---|
| Cloud SQL (default) | none | VPC, private-IP-only Cloud SQL with TLS required, backups and point-in-time recovery |
| Your own Postgres, public | `external_database` | Nothing for the database; Cloud Run connects over the internet |
| Your own Postgres, IP allowlist | `external_database`, `egress = { static_ip = true }` | VPC and Cloud NAT with a reserved IP (`egress_ip` output) that all egress uses |
| Your own Postgres, private network | `external_database`, `egress = { network, subnetwork }` | Direct VPC egress into your network; you own routing, peering/VPN and firewall rules |

With the IP allowlist mode, reserve the address and allowlist it before the full apply, because Keyra can't start until your database accepts it:

```bash
terraform apply -target=module.keyra.google_compute_address.egress
terraform output egress_ip   # add to your database's allowlist, then run terraform apply
```

`external_database` takes a `jdbc_url` (must set `sslmode=require`, `verify-ca` or `verify-full`), a `username`, and `password_secret_id`: an existing Secret Manager secret holding the password. The module only grants Cloud Run read access to it and never handles the value.

Changing the database mode of an existing instance (for example Cloud SQL to your own Postgres) switches Keyra to a new, empty database, so migrate your data yourself (`pg_dump`/`pg_restore`) before applying. Cloud Run moves first and the old Cloud SQL instance and network are removed afterwards, though the subnet may still need a second `terraform destroy`/apply later because of the documented Cloud Run hold. A change that replaces the module's VPC, subnet or Cloud SQL instance (such as a new `subnet_cidr` or `region`) needs a new instance `name`, because the replacement is created before the old one is removed and the names would clash.

#### Using your own Postgres

Any provider or self-hosted Postgres works if it meets these requirements:

- PostgreSQL 15 or later (CI tests against 18).
- The Keyra user owns its database or schema: Flyway creates and alters tables at startup.
- TLS is required (enforced by the `jdbc_url` validation).
- Session-mode connections only. Transaction-mode poolers (PgBouncer in transaction mode, such as Supabase's port 6543) break Flyway's migration lock and prepared statements; use a direct connection or a session-mode pooler.
- `max_replicas × KEYRA_DB_POOL_SIZE` (default 4 × 10) must fit within the database's `max_connections`; lower `db_pool_size` for small plans.
- Backups, high availability and upgrades are your responsibility.

The [configurator](https://adamrodriguesss.github.io/keyra/configurator.html) has step-by-step setup guides for a self-managed server, Supabase and Neon.

#### Sharing the database server with your own apps

- One server, one database and one user per app. Keyra's database is Keyra's alone: never point another app at it or grant another app's user access, and read Keyra data only through its API.
- Cloud SQL mode: set `app_databases`. Connect from your app with Direct VPC egress into the `vpc_network` / `vpc_subnetwork` outputs, using the private IP in `jdbc_url` and the password from `password_secret_id` (grant your app's service account `secretAccessor` on it).
- Your own Postgres: create the extra database and user yourself, and point `jdbc_url` at Keyra's database.
- Adding later: add a key and `terraform apply` again; only the new database, user and secret are created. Removing a key keeps the data (`ABANDON`). Databases created outside Terraform also work and Terraform leaves them alone; never use Keyra's `keyra` user to create them.
- Keyra's V7 migration takes ownership of its database and schema and revokes PUBLIC access, so other users on the instance can't connect to, drop or alter it. Do the same for your app's database: `alter database <db> owner to <user>; revoke all on database <db> from public;`.
- Connection budget: `max_replicas × db_pool_size` plus every other app's pool must fit `max_connections`; resize `db_tier` if needed.
- Shared fate: a restore or point-in-time recovery of the instance rolls back every database on it.

#### Metrics

Set `metrics_enabled = true` to export metrics to Google Cloud Managed Service for Prometheus. Keyra pushes OTLP every 60 seconds to an OpenTelemetry Collector sidecar, which forwards it. It is off by default because pushing between requests needs CPU always allocated, and the sidecar adds 1 vCPU per instance. Both are billed for as long as each instance runs.

What to watch (OTLP names; Prometheus spells them with underscores and unit or `_total` suffixes, e.g. `http_server_requests_milliseconds_bucket` and `keyra_key_validations_total`; durations are in milliseconds, so the SLO bucket is `le="50"`):

- `http.server.requests`: request rate, latency (p95 and a 50 ms SLO bucket) and status per endpoint. Validation latency is `uri="/api/v1/keys/validate"`, and failed logins are `status="401"` on the login URIs.
- `keyra.key.validations{outcome="valid"|"invalid"}`: validation results, since that endpoint always answers 200. There is no reason tag, by design.
- `hikaricp.connections.*`: the database pool, the first thing to saturate under validation load.
- `jvm.*` and `process.*`: memory, garbage collection, threads and CPU.

Outside Terraform, set `KEYRA_METRICS_ENABLED=true` and point `KEYRA_METRICS_OTLP_URL` at any OTLP/HTTP collector.

### Deploy to AWS

The module (`infra/modules/keyra/aws`) runs Keyra on ECS Fargate behind an Application Load Balancer. By default it pulls the published image straight from GitHub Container Registry. The public URL, which is also the token issuer, comes one of two ways:

- **Your domain** (`domain_name` + `route53_zone_id`, recommended): `https://<domain_name>`. The module issues an ACM certificate, validates it in your Route 53 zone and points the domain at a public load balancer. Plain HTTP redirects to HTTPS.
- **No domain** (leave both unset): CloudFront serves `https://<id>.cloudfront.net` (the `url` output) and reaches an internal load balancer in the private subnets through a VPC origin, over HTTP inside the VPC. The load balancer has no public address and admits only CloudFront's VPC-origin security group. Nothing is cached: the cache policy keys on `Authorization` (CloudFront otherwise drops it from GET requests) with a default TTL of 0, and Keyra sends `no-store`. CloudFront's default certificate also accepts TLS 1.0 and 1.1 from viewers (AWS doesn't let you raise the minimum on `*.cloudfront.net`); the domain mode enforces TLS 1.2+. Expect a few extra milliseconds per request for the CloudFront hop, and 5-15 minutes on the first apply while the distribution deploys. Switching between the two modes changes the issuer URL, so every token already issued stops verifying and users must sign in again: pick one up front.

1. Prerequisites: Terraform 1.11+, AWS credentials with rights to create the resources (`aws configure`, `aws login` or `aws sso login`), and, for the domain mode, a Route 53 hosted zone for the domain.
2. Open the [configurator](https://adamrodriguesss.github.io/keyra/configurator.html#aws), fill it in, and save the generated `main.tf` in an empty folder.
3. In that folder, run the commands the page shows:
   ```bash
   aws s3api create-bucket --bucket <account>-keyra-tfstate --region <region> --create-bucket-configuration LocationConstraint=<region>   # once per account; omit the last flag in us-east-1
   aws s3api put-bucket-versioning --bucket <account>-keyra-tfstate --region <region> --versioning-configuration Status=Enabled
   terraform init && terraform apply
   aws secretsmanager get-secret-value --secret-id <name>-bootstrap-admin-password --region <region> --query SecretString --output text   # the generated admin password
   ```

Prefer variables to a generated file? `infra/examples/aws` is the same root driven by `terraform.tfvars` (copy `terraform.tfvars.example`): `terraform init -backend-config="bucket=<account>-keyra-tfstate" -backend-config="region=<region>"`, then `terraform apply`.

`terraform apply` waits until the new tasks are healthy behind the load balancer (a failing rollout is rolled back), then checks `/actuator/health`. If it hangs or fails, read the ECS service events and the `/keyra/<name>` log group.

The RDS instance and the load balancer have deletion protection on by default (`deletion_protection = false` to tear an instance down; it also skips the final RDS snapshot and deletes secrets without the 30-day recovery window, so the same `name` can be reused at once). Bump `jwt_key_version` or `db_password_version` to rotate those secrets.

#### Database and network modes on AWS

| Mode | Inputs | What the module creates |
|---|---|---|
| RDS (default) | none | VPC over two AZs; tasks in public subnets with public IPs that only the load balancer can reach (no NAT gateway); RDS in private subnets with TLS required, encrypted storage, 7 days of backups and point-in-time recovery (`db_backup_retention_days`; AWS free-plan accounts only allow 1) |
| Your own Postgres, public | `external_database` | The same VPC, without RDS; tasks connect over the internet |
| Your own Postgres, IP allowlist | `external_database`, `egress = { static_ip = true }` | Tasks move to private subnets behind one NAT gateway with an Elastic IP (`egress_ip` output) |
| Your own Postgres, your VPC | `external_database`, `egress = { vpc_id, task_subnet_ids, alb_subnet_ids }` | No networking: tasks run in your subnets (which need their own route out, e.g. a NAT, to pull the image and reach AWS APIs) and the load balancer in your `alb_subnet_ids`: public subnets with a domain, private ones (in a VPC with an internet gateway) without. Allow the `task_security_group_id` output on your database |

With the IP allowlist mode, create the address first and allowlist it, because Keyra can't start until your database accepts it:

```bash
terraform apply -target=module.keyra.aws_eip.egress
terraform output egress_ip   # add to your database's allowlist, then run terraform apply
```

On AWS, `external_database.password_secret_id` must be the secret's full ARN. If the secret uses your own KMS key, grant the execution role (`<name>-execution`) `kms:Decrypt` on it.

RDS has no API for creating roles, so Keyra connects as the instance's master user `keyra`, which owns the `keyra` database. The [Using your own Postgres](#using-your-own-postgres) requirements apply to an external database as on GCP.

`app_databases` works on RDS too. Because RDS has no API for databases or roles, a small function in the private subnets (`<name>-app-db`, Python, no dependencies) connects as the master user over verified TLS and creates each database and its owner role. It reads the passwords from Secrets Manager itself, so they never appear in Terraform state, and sends Postgres a SCRAM verifier, never the plaintext. It runs again only when you bump `password_version`, and removing an entry keeps the data. Each app role owns its database and PUBLIC access is revoked, so other apps can't connect. Keyra's own user ends with no access to app data: it can't read the tables or `SET ROLE` to the app role, and keeps only the ADMIN grant Postgres gives a role's creator, which the function needs to reset the password. That isolation has one limit RDS can't remove: Keyra's user is the master user, a member of `rds_superuser`, so stolen Keyra database credentials could grant themselves access. If an app's data must not depend on Keyra's credentials, give it its own server. Without a NAT gateway it reaches Secrets Manager through a VPC endpoint the module adds (about $15/month while `app_databases` is set). Your apps reach the database from the module VPC (`vpc_id` output); give their security group access on the RDS security group (`<name>-db`).

#### Metrics on AWS

Set `metrics_enabled = true` to export metrics to CloudWatch, namespace `Keyra`. An AWS Distro for OpenTelemetry collector sidecar receives Keyra's OTLP push and writes CloudWatch embedded-metric logs to `/keyra/<name>/metrics`. The sidecar shares the task's existing CPU and memory, so the task costs the same; CloudWatch bills per custom metric, so only these are kept:

- `keyra.key.validations` by `outcome` (`valid` / `invalid`).
- `http.server.requests` by `uri` and `status`: count, sum, min and max in milliseconds. CloudWatch receives no histogram buckets, so use the load balancer's built-in `TargetResponseTime` (namespace `AWS/ApplicationELB`, p95 statistic) for latency percentiles.
- `hikaricp.connections`, `.active`, `.pending`, `.max` by `pool`.
- `jvm.memory.used` by `area`.

Find them in CloudWatch → Metrics → All metrics → `Keyra`, or with `aws cloudwatch list-metrics --namespace Keyra`.

### Configuration

Environment variables, populated by Terraform in deployed instances:

| Variable | Purpose |
|---|---|
| `KEYRA_DB_URL`, `KEYRA_DB_USER`, `KEYRA_DB_PASSWORD` | Postgres connection |
| `KEYRA_JWT_PRIVATE_KEY`, `KEYRA_JWT_KEY_ID` | RS256 signing key (PKCS#8 PEM) and its `kid` |
| `KEYRA_ISSUER_URL` | `iss` claim / public URL |
| `KEYRA_BOOTSTRAP_ADMIN_EMAIL`, `KEYRA_BOOTSTRAP_ADMIN_PASSWORD` | First admin, created at startup |
| `KEYRA_KEY_ROTATION_GRACE_HOURS` | Old-secret grace period after rotation (default 24) |
| `KEYRA_DB_POOL_SIZE` | Database connections per instance (default 10) |
| `PORT` | HTTP port (default 8080; set by Cloud Run) |
| `KEYRA_METRICS_ENABLED`, `KEYRA_METRICS_OTLP_URL` | Push metrics over OTLP (default off; URL default `http://localhost:4318/v1/metrics`) |

`GET /actuator/health` (with `/liveness` and `/readiness` probes) is public; every other actuator endpoint is denied. Metrics are pushed, never served (see [Metrics](#metrics)).
