---
name: verify
description: Verify the project builds, is formatted, and all tests pass. Use before declaring a change done.
---

1. Check Docker is up: `docker info --format '{{.ServerVersion}}'`. If it fails, stop and tell the user Docker must be running for Testcontainers.
2. Run `./mvnw -q spotless:check`. If it fails, run `./mvnw -q spotless:apply` and note which files changed.
3. Run `./mvnw verify`.
4. Report: formatting status, test counts, and for each failure the test name, the assertion/exception, and the likely cause. Don't paste full Maven logs.
