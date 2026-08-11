# Artemis WMS

Cloud-native warehouse management system. Java 21 · Spring Boot 3.3 (embedded
Tomcat) · PostgreSQL 16 · Flyway · Caddy 2 · Docker Compose · GitHub Actions.

**This build compiles and runs** — validated end-to-end against live
PostgreSQL 16: the full beta scenario (org → items → locations → opening
balances → receive → putaway → order → allocate → wave → pick → drop → ship
→ replenish) passes via `scripts/smoke-test.sh`.

## Architecture

- **API-first.** Everything under `/api/v1/`; OpenAPI docs at `/api-docs`.
  The web UI is just another client; voice is another. No side doors.
- **Multi-tenant hard silo.** `corporation_id` on every tenant row +
  Postgres RLS backstop (`SET app.current_corp` per connection via
  `TenantAwareDataSource`). Run the app as a non-superuser DB role in
  production so RLS is enforced.
- **RBAC, highest-wins.** Grants = (user, org node, role); resolution walks
  ancestors via `effective_role()` / `effective_capabilities()` in SQL.
- **OIDC-first.** "IdP authenticates, app authorizes." Per-tenant IdP registry
  (V4). Local password login exists only behind the `local-auth` Spring
  profile — dev/test convenience, absent in production.
- **Assignments as the universal work unit**, tasks sequenced by
  `pick_sequence`, voice-native (`check_digits` + `spoken_prompt` both sides).

## Repo layout

```
db/        Flyway migrations V1–V8 (also packaged into the app)
app/       Spring Boot service (pom.xml, Dockerfile)
deploy/    docker-compose.yml, Caddyfile, .env.example
scripts/   smoke-test.sh — full e2e beta scenario against a live instance
docs/      API contracts, design notes, UI mockups, upload templates
```

## Run locally

```bash
createdb wms
cd app
DB_URL=jdbc:postgresql://localhost:5432/wms mvn spring-boot:run \
  -Dspring-boot.run.profiles=local-auth
# Flyway migrates V1–V8; local-auth seeds admin@artemis.local / admin
curl -u admin@artemis.local:admin localhost:8080/api/v1/notifications/count
```

## Deploy

`deploy/` is a Portainer-ready compose stack: Postgres 16, the app image from
GHCR, Caddy (automatic HTTPS), Watchtower (daily image pulls). Copy
`.env.example` → `.env`, set the domain and secrets, `docker compose up -d`.
CI (`.github/workflows/ci.yml`) builds, tests against a Postgres service
container, and pushes `ghcr.io/<owner>/artemis-wms`.

## Milestones

| | |
|---|---|
| M1 | Identity & tenancy — org CRUD, grants, capability enforcement |
| M2 | Master data — customers, items (two-pass base links), locations (bulk / serpentine generate / CSV+XLSX upload), hard-validated opening balances |
| M3 | Inbound — manifests, dock-enforced capture, min-shelf-life rejection at receipt, directed putaway (`directed_putaway_slot`), check-digit verified completion |
| M4 | Outbound — allocation (rotation cascade + freshness bypass with `ROTATION_BYPASS` alert), 8 wave types, cart batching, both-side verified selection, LPN split, packing list from picked reality |
| M5 | Replenishment — trigger scan over `v_replen_pressure`, slot-to-slot assignments, `REPLEN_CRITICAL` → bell + email outbox escalation |
| UI | Server-rendered operator UI (Thymeleaf): `/` operations board, `/metrics` dashboards — mockup-faithful, snapshot-on-load, DASHBOARD_VIEW-gated |
| M6 (partial) | Equipment registry (`POST /equipment` — enables batch-cart release), labor dispatch (`POST /assignments/{id}/assign` feeds `v_labor_productivity`), platform console at `/admin` with sysadmin REST (`/api/v1/admin/*`) |
| Asset screens | Linkable asset registry: hub → Items / Zones / Slots / Carts & Powered / Totes / Waves, list → detail everywhere, Wave → Assignment → pick-line graph; V10 adds assignment numbers, datetime wave numbers, slot dimensions, equipment capabilities, calculated ABC item velocity (`v_item_velocity`, trailing 30d) |
| Mutations & lifecycle | Create/edit forms on every asset screen (items, slots, equipment, containers) + action buttons (plan/release wave, assign/reassign, attach tote, replen scan); V11 assignment lifecycle: PENDING → ASSIGNED → REASSIGNED → IN PROGRESS → COMPLETE with reassignment audit (count + previous assignee), 4s live-status polling on the assignment screen; pick lines resolve the full put address (equipment type + code + position + tote with authoritative digits) |
| V12 | Slot conventions + tiered UOMs: one item per slot and item-temp-zone authority enforced by DB trigger on STORAGE/PICK_FACE (docks/drops/staging exempt; directed putaway defaults to no mixing); AMB placards standardize to DRY; slots list links its 1:1 item; check-digit labels cleaned up ("say" phrasing removed); `item_uom` tiered hierarchy (1 CS = 4 EA, 1 PL = 40 CS, add tiers from the item screen) with each-equivalents, backfilled from case pack and Ti×Hi |
| Ops navigation | Clickable workstream lanes filter the board: Put Away / Replen / Selection filter the task table, Receiving swaps in open manifests, Shipping swaps in orders awaiting load; task table renamed "Open Tasks" (one row per task) with an explicit Assignment column so multi-item selection assignments aren't misread; scoped filter boxes on every list panel (ops, assets, waves, wave assignments, pick lines) |
| V9 | Platform SYSADMIN tier — corporation creation and platform admin locked to `app_user.sysadmin`; tenant admin roles propagate downward via grants as before |

## Auth tiers

- **SYSADMIN** (platform): flag on the user, not a grant. Creates corporations,
  future home of IdP/platform administration. Never reachable from tenant roles.
  local-auth seeds `sysadmin@artemis.local / sysadmin` (fresh installs) and
  grandfathers `admin@artemis.local` on existing dev databases (V9).
- **Tenant admins** (corporation → district → site → area): grants at org nodes,
  highest-wins resolution walks ancestors — a corporation ADMIN grant covers
  every site beneath it.
