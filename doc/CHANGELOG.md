# Changelog — service-cp-crime-probation-results-distribution

All notable changes to this service are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The design lives on the
[Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service)
Confluence page, which is the source of truth. This file does not replay the design's history.

Entries land under `[Unreleased]` as they are merged, and move into a version section at release.
Anything that changes what is **sent** to HMPPS must also be a registered deviation, with its own
assertion in the parity harness.

## [Unreleased]

### Added
- **Initial scaffold.**
  - **Build and quality gates.** A Gradle build on a Java 25 toolchain, with Spring Boot 4.1,
    Checkstyle, PMD (main and test rulesets) and a JaCoCo coverage ratchet at line 0.88 and branch
    0.85.
  - **Dependencies.** The libraries the design needs:
    - Spring Web, JDBC, Flyway and PostgreSQL;
    - Azure Service Bus and Azure Identity, through the Azure SDK BOM;
    - Lettuce;
    - Actuator, OpenTelemetry and Prometheus;
    - JSON Schema validation;
    - the estate starters `cp-auth-rules-filter` and `cp-audit-filter-springboot`;
    - for tests, Testcontainers (PostgreSQL, Azure, Toxiproxy) and WireMock.
  - **Package modules.** These live under `uk.gov.hmcts.cp.probationresultsdistribution`: `inbound`,
    `application`, `pipeline`, `adapter.payload`, `adapter.usersgroups`, `adapter.gateway`,
    `persistence`, `domain`, `config` and `api`. Each has a `package-info.java` that states its
    responsibility and its allowed dependencies.
  - **Configuration.** The property catalogue in `application.yaml`, with environment-variable
    placeholders and no packaged secrets.
  - **Local stack.** Postgres, the Service Bus emulator with the `probation.requests` queue
    definition, Redis and WireMock, plus a container smoke script.
  - **CI.** GitHub Actions workflows for build and publish (draft and release), PMD analysis,
    CodeQL with SBOM and DAST, secrets scanning, Dependabot auto-merge and post-merge catch-up.
    The `main` branch ruleset definition is in `.github/rulesets/`.
  - **Spec Kit.** Spec Kit and the engineering constitution.
  - **Contracts.** [API_CONTRACTS.md](API_CONTRACTS.md) records the inbound queue contract and the
    outbound HMPPS publish. The outbound publish is pending gate G1.
