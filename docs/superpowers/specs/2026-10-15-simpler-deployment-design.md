# Simpler deployment: configurator plus three commands

Date: 2026-10-15
Status: approved design, not yet planned

## Goal

A company deploys Keyra on GCP by filling in the configurator and running at most three commands. It needs no clone of the repository, no Docker and no environment variables. Prerequisites shrink to Terraform 1.11+ and a logged-in `gcloud`.

Today it takes eight manual steps: install Docker, create an Artifact Registry repo, build and push the image, create a state bucket, fill in tfvars, export `TF_VAR_bootstrap_admin_password` before every plan and apply, run init and apply, and smoke-test by hand.

## 1. Release pipeline

- New `.github/workflows/release.yml`, triggered by tags matching `v*`.
- It builds the existing `Dockerfile` and pushes `ghcr.io/adamrodriguesss/keyra:<version>` and `:latest`, where `<version>` is the tag without the `v` (tag `v0.6.0` gives `0.6.0`). It logs in with the built-in `GITHUB_TOKEN` (`packages: write`).
- The module declares `local.keyra_version` (for example `"0.6.0"`) in `infra/modules/keyra/gcp/main.tf`. The workflow fails before pushing if the tag doesn't equal `v${keyra_version}`. One tag therefore pins both the module source and the image it runs.
- Done once by hand: make the GHCR package `keyra` public so anyone can pull it anonymously.

## 2. GCP module changes (`infra/modules/keyra/gcp`)

### Image

- `variable "image"` becomes optional: `default = null`.
- When `image` is null:
  - `artifactregistry.googleapis.com` is added to `google_project_service.apis`.
  - `google_artifact_registry_repository.ghcr` is created with `repository_id = "${var.name}-ghcr"`, `format = "DOCKER"` and `mode = "REMOTE_REPOSITORY"`, with an upstream of `remote_repository_config.docker_repository.custom_repository.uri = "https://ghcr.io"`.
  - The service runs `${var.region}-docker.pkg.dev/${var.project_id}/${var.name}-ghcr/adamrodriguesss/keyra:${local.keyra_version}`.
- When `image` is set, nothing changes from today: no remote repository and no extra API.
- This all goes through one local, `local.image`, in `main.tf`, next to the other mode decisions.

### Bootstrap admin password

- `variable "bootstrap_admin_password"` becomes optional: `default = null`, still `sensitive` and `ephemeral`. Its validation becomes `var.bootstrap_admin_password == null || (length between 12 and 128)`.
- When it's null, `ephemeral "random_password" "bootstrap_admin"` (length 24) supplies the value. `google_secret_manager_secret_version.bootstrap_admin_password` writes `coalesce(var.bootstrap_admin_password, ephemeral.random_password.bootstrap_admin[0].result)` through `secret_data_wo`. `secret_data_wo_version` stays `1`, so later applies never rewrite it.
- New output `bootstrap_admin_password_secret`: the secret ID, `${var.name}-bootstrap-admin-password`.
- The variable description stops telling users to set `TF_VAR_bootstrap_admin_password`.

### Health check

- `versions.tf` adds `hashicorp/http >= 3.4`.
- New `check "health"` block with a scoped `data "http"` on `${local.url}/actuator/health`. Retries cover cold starts. It asserts that the status code is 200 and `jsondecode(body).status == "UP"`. A failure is a warning and doesn't fail the apply.

## 3. Configurator (`docs/configurator.html`)

- Output changes from `terraform.tfvars` to one `main.tf` that holds:
  - `terraform { required_version = ">= 1.11"; backend "gcs" { bucket = "<project>-tfstate", prefix = "keyra" } }`
  - `provider "google" { project, region }`
  - `module "keyra" { source = "git::https://github.com/adamrodriguesss/keyra.git//infra/modules/keyra/gcp?ref=v<version>" ... }`, with the user's values inline. `image` and `bootstrap_admin_password` are left out.
  - `output "url"` and `output "bootstrap_admin_password_secret"`.
- Copy and Download buttons for `main.tf`.
- A constant `KEYRA_VERSION` in the page sets the `ref`. The release process bumps it together with `local.keyra_version`.
- Commands shown for the default mode:
  1. `gcloud storage buckets create gs://<project>-tfstate --location=<region> --uniform-bucket-level-access --project=<project>`, then `gcloud storage buckets update gs://<project>-tfstate --versioning`. Run once per project.
  2. `terraform init && terraform apply`
  3. `gcloud secrets versions access latest --secret=<name>-bootstrap-admin-password --project=<project>`
- Steps that stay for some modes: storing the external DB password (bring your own Postgres), the targeted apply and allowlisting (IP allowlist mode), and the app database grants (`app_databases`).
- Removed: the "Push the image" step, the password `read` and export, and the curl smoke test.
- An "Advanced" disclosure keeps an optional custom `image` field. It's written into the module block only when it's filled in. The field's checks stay as they are today.

## 4. Example, tests and docs

- `infra/examples/gcp`: `image` and `bootstrap_admin_password` default to `null` and pass through, and the new output is exposed. `terraform.tfvars.example` loses the image line and the TF_VAR comment.
- `infra/modules/keyra/gcp/tests/modes.tftest.hcl`:
  - add `mock_provider "http"` with a body of `{"status":"UP"}`;
  - a run with no `image`, asserting that the remote repository exists and the service image points at it;
  - a run with `image` set, asserting there's no remote repository;
  - a run with no `bootstrap_admin_password`, which plans without errors and returns the secret output;
  - existing runs keep passing. `random` and `tls` stay real providers, because mocks don't support ephemeral resources.
- Run `terraform-docs infra/modules/keyra/gcp` after the variable and output changes, and `terraform fmt -recursive infra`.
- `README.md` "Deploy to GCP": rewritten around the configurator and the three commands. A short "Build your own image" subsection keeps the old Docker steps for people who need them, and the release process gets a note.
- BRD (Claude doc "Keyra — Business Requirements Document"): section 4.7 "Deploying" is updated to the new flow. A "Published image (GHCR)" row is added to 4.1.

## Out of scope

- The AWS module. It will use the same GHCR image, which ECS can pull directly.
- CI for tests or `terraform test`.
- Hosting for keyra-admin.
- Automating state bucket creation inside Terraform. That's a chicken-and-egg problem, so the bucket stays one `gcloud` command.

## Risks

- Remote repositories pull from GHCR without credentials. If the GHCR package is ever made private, deployments that don't set `image` will fail to pull.
- An existing instance that sets `image` explicitly keeps working. Switching it to `null` creates the proxy repo and rolls out a new Cloud Run revision on the next apply.
- An instance that drops a previously supplied password keeps the existing secret version, because `secret_data_wo_version` stays `1`.
