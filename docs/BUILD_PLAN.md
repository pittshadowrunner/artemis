# WMS Build Plan v0.1

## Stack (locked)

Java 21 LTS + Spring Boot 3.3 packaged as WAR on Tomcat 10.1 (or embedded Tomcat — see Decision 1), PostgreSQL 16, Caddy 2 for automatic HTTPS, Docker Compose managed through Portainer, GitHub + GitHub Actions for CI (builds the app image, pushes to GHCR, Watchtower pulls updates daily). Flyway for schema migrations so the database evolves through versioned SQL in the repo. Mailgun for transactional email (verification, password reset).

## Architecture principles

**API-first.** Every capability is a REST endpoint under `/api/v1/` with OpenAPI (springdoc) documentation auto-generated. The web UI is just another API client. Voice clients (VoiceLink or otherwise) are another. Beta upload scripts hit the same endpoints — no side doors.

**Multi-tenancy: hard silo.** Every tenant-scoped table carries `corporation_id`, enforced two ways: application-level tenant filter on every query, and Postgres Row Level Security as a backstop (`SET app.current_corp` per connection). Even a bug in the app layer can't leak cross-tenant data.

**Org hierarchy.** Single `org_node` table: Corporation → District Region → Site Location → Area, parent-linked. Address attributes at every level.

**RBAC with highest-wins.** Grants = (user, org node, role). Effective access at any node resolves by walking ancestors and taking the highest-ranked role (`effective_role()` function in the schema). Roles are rows, not enums — the future security portal adds custom roles + capability flags with zero migrations. Ship with ADMIN (rank 100) and READ_ONLY (rank 10).

**Assignments as the universal work unit.** Every workstream (receiving, putaway, replenishment, selection, loading) produces an `assignment` with typed attribute and sequenced `assignment_task` rows. Task sequencing uses `location.pick_sequence` (travel-path order) for proximity optimization; base-item grouping is applied at allocation/wave time so variants of a base item ship together.

**Voice-native.** Task rows carry `check_digits` and `spoken_prompt` fields from day one. VoiceLink 5.3 integration lands as an adapter that maps assignments/tasks to VoiceLink's import format — the domain model doesn't change.

## Phases

**M0 — Foundation (this drop).** Schema, compose stack, Caddy, repo layout.

**M1 — Identity & tenancy.** Spring Boot skeleton, Flyway, auth (JWT), registration + Mailgun email verification, org CRUD, grants, effective-role enforcement middleware.

**M2 — Master data APIs.** Customers, locations (bulk create via JSON push + CSV upload), items, inventory upload (API + spreadsheet).

**M3 — Inbound.** Receiving manifests, receive against manifest (capture lot/expiry/serials/arrival), directed putaway to open slots (capacity + type aware).

**M4 — Outbound.** Orders, allocation (FEFO-aware where expiry-tracked, base-item grouping), selection assignments sequenced by pick path, drop to drop location, shipment + packing list generation (PDF).

**M5 — Replenishment.** Min/max on pick faces, slot-to-slot replen assignments.

**M6 — Beta hardening.** End-to-end demo script matching the beta scenario: script-push locations → upload inventory → receive shipment → putaway → order → select → drop → ship + packing list.

## Open decisions (need your call)

1. **WAR-on-standalone-Tomcat vs Spring Boot's embedded Tomcat.** Embedded is the cloud-native norm — one container, simpler updates, still Tomcat under the hood. Standalone Tomcat only wins if you want multiple WARs per server. Recommend embedded.
2. **UI for beta.** API + a thin server-rendered admin (Thymeleaf) is fastest; a React SPA is nicer long-term. Recommend Thymeleaf for beta, React later.
3. **Allocation strategy default.** FEFO when expiry-tracked, else FIFO by arrival_date, proximity as tiebreaker — confirm.

## Repo layout

```
wms/
├── db/            # Flyway migrations (schema.sql becomes V1__init.sql)
├── deploy/        # docker-compose.yml, Caddyfile, .env.example
├── app/           # Spring Boot service (M1)
├── scripts/       # beta API push scripts (locations, inventory)
└── docs/
```
