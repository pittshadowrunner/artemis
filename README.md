# Artemis WMS

Multi-tenant warehouse management. API-first: the web UI and voice are just clients.
Java 21 · Spring Boot 4.1 · PostgreSQL 16 · Thymeleaf · deployed as a Portainer Git stack.

**v2.0 (September 2026)** is a ground-up rebuild of the V1–V17 codebase. Same decisions,
none of the residue: three domain migrations instead of seventeen chronological ones,
package-by-feature instead of `service/` and `web/` buckets, and one declarative import
instead of the psql/curl/`docker cp` staging scripts.

## Repository layout

```
docker-compose.yml     the stack: db + app (+ caddy behind the "edge" profile)
.env.example           every variable the stack reads
app/                   Spring Boot application (own Dockerfile, built on the host)
  src/main/resources/db/migration/
    V1__foundation.sql       org hierarchy, identity, RBAC, policy cascade, alerts, RLS
    V2__master_data.sql      customers, items + UOM tiers, locations, equipment, containers
    V3__inventory_inbound.sql pallets, movement journal, receiving, assignments, directed putaway
    V4__outbound.sql         orders, zone orders, waves, allocation, selection, shipments, packing lists
    V5__replenishment.sql    replen pressure, rotation-correct sourcing, pallet split lineage
  src/main/java/com/artemis/wms/
    platform/    ApiException, error handler, BulkResult, Values, RowSource (CSV/XLSX)
    tenancy/     TenantContext, TenantAwareDataSource (binds RLS per connection), TenantFilter
    identity/    SecurityConfig, AccessService (capability checks), users & grants, bootstrap
    org/         hierarchy, policy cascade
    dataimport/  the declarative import, admin wipe
    masterdata/  items, locations (+ rack generation), customers, equipment, containers
    inventory/   opening balances, LPN lookup with journal
    inbound/     receiving, directed putaway
    outbound/    orders, waves (plan/release), allocation, selection (pick/induct/drop), shipping
    replen/      pressure, run, split-on-complete
    work/        DispatchService — role capability + trained-workflow allowance gate
    ui/          Thymeleaf pages (UiController reads, UiActionController posts); templates under resources/templates
    alerts/      alerts, bells, Mailgun outbox drain
caddy/                 Caddyfile baked into the image (no single-file bind mounts on the NAS)
db/                    postgres:16 with the non-superuser app role baked in
ops/
  artemis              operator CLI (curl + jq over the API)
  smoke.sh             end-to-end test against an empty stack (80 checks: API flows + every UI page)
  data/hierarchy.example.yaml   corporation, regions, sites, areas, policies, users, grants
  data/demo-pit1.yaml           customers, items, racking + pick faces, equipment, balances, a manifest, orders (dates are relative: today+N)
  data/templates/*.csv          upload templates
.github/workflows/ci.yml   build + test only; the NAS builds its own images
```

## Deploying (Portainer Git stack)

Same pattern as the other Squires Enterprises stacks: Portainer clones this repo and builds
the images on the host. No registry, no Watchtower, no unattended restarts.

1. Create the stack from this repo, `docker-compose.yml` at the root.
2. Set the variables from `.env.example`. `DB_PASSWORD` is the **app** role; the Postgres
   superuser is `POSTGRES_ADMIN_*` and the app never uses it — that's what makes Row Level
   Security actually enforce (superusers bypass RLS).
3. `BOOTSTRAP_SYSADMIN_EMAIL/PASSWORD` create the platform sysadmin on the **first boot with an
   empty user table only**. There is no hard-coded admin account.
4. `edge_net` must already exist (the shared edge Caddy owns 80/443). The app also publishes
   `APP_PORT` (default 8085) for LAN access. Enable the `edge` profile only if you want this
   stack's own Caddy in the mix.
5. On a rebuild, dump the `artemis_pgdata` volume — migrations are clean-slate and Flyway will
   refuse to run over a schema from an older jar.

Rotating the DB password later needs `ALTER ROLE artemis_app PASSWORD '...'` *and* the env var.

## First data: the declarative import

Everything the old `demo-seed.sh` / `demo-big.sh` / `demo-big-pre.sql` did is now one document
applied through the API. Sections run in dependency order and every section **upserts by
code**, so you can edit and re-apply freely. Each row commits alone: a bad pallet never rolls
back a good rack, and the response tells you exactly which rows failed and why.

```bash
export ARTEMIS_URL=http://nas:8085
ARTEMIS_USER=sysadmin@artemis.local ARTEMIS_PASS=... ops/artemis import ops/data/hierarchy.example.yaml
ARTEMIS_USER=tom@squiresenterprisesllc.com ARTEMIS_PASS=... ops/artemis import ops/data/demo-pit1.yaml
```

Only a sysadmin can create a `corporation:`; a tenant ADMIN (capability `DATA_IMPORT`) applies
everything else inside their own tenant. A sysadmin acting inside a tenant sets
`ARTEMIS_CORP=<corporation id>` (the `X-Artemis-Corporation` header).

```yaml
corporation: { code: SQE, name: Squires Enterprises LLC }
regions:
  - code: NE
    sites:
      - code: PIT1
        name: Pittsburgh DC
        policy: { rotation: FIFO, minShelfLifeReceiptDays: 30, allowItemMixing: false }
        areas:
          - { code: FREEZER, policy: { rotation: FEFO } }     # area overrides site, field by field
users:
  - { email: tom@…, displayName: Tom, password: …, grants: [{ node: SQE, role: ADMIN }] }
  - { email: j.okafor@…, displayName: J. Okafor, workflows: [RECEIVING, PUTAWAY], grants: [{ node: PIT1, role: OPERATOR }] }
customers: [ … ]
items:     [ { sku, description, tempZone, casePackQty, palletTi, palletHi, lotTracked, expiryTracked, shelfLifeDays, catchWeight, nominalWeightKg, … } ]
sites:
  - site: PIT1
    locations: [ { code: DOCK-R1, locType: RECEIVING_DOCK }, … ]
    racks:     [ { zone: F, aisles: 1-1, levels: 1-2, columns: 1-6, slots: [A, B], tempZone: FROZEN, area: FREEZER } ]
    equipment: [ { code: CART-01, type: CART, positions: 6 } ]
    containers:[ { barcode: TOTE-0001, type: TOTE } ]
    inventory: [ { lpn: PLT-0201, sku: '023700016584', location: F-01-2-01-A, qty: 48, lot: CB-2210, expirationDate: 2027-05-01, actualWeightKg: 872.5 } ]
    manifests: [ { manifestNumber: MAN-260917-01, carrier: Estes, lines: [{ sku: '023700016584', expectedQty: 48 }] } ]
    orders:    [ { orderNumber: ORD-1001, customer: GIANT-EAGLE-012, rush: false, requestedShipDate: 2026-09-21, lines: [{ sku: '070732001217', qty: 10 }] } ]
```

Other CLI commands: `me`, `sites`, `corporations`, `get <path>`, `post <path> <json>`,
`wipe <corpId> [operations|all]` (sysadmin; confirms with the corporation code).
CSV/XLSX uploads still exist for items, locations and opening balances (`/upload` endpoints,
templates under `ops/data/templates/`).

## The mega dataset

`ops/data/mega/` is a fully stocked building to tinker in: 10,192 items, 100 Pittsburgh-area
customers, 25,480 rack slots (five levels; 60 dry aisles, 24 chill, 20 freezer), 23,610
pallets, 80 pieces of equipment, 45 people, 240 open orders and 12 inbound manifests.

```
ops/artemis wipe <corporation id> all          # only if the tenant already exists (sysadmin)
SYSADMIN_USER=admin@artemis.local SYSADMIN_PASS=... ops/data/mega/load.sh
```

It loads five files in order and prints what each created; allow several minutes (every row is
validated like any other write). Items are identified by a 12-digit **UPC-A** — in YAML a UPC must
be quoted (`sku: '013000004664'`) or it is read as a number and loses its leading zero. Brands and
UPCs are invented; customers are real places with illustrative addresses and fictional phone
numbers. Every password is `change-me`. `generate.py` rebuilds the files if you want a different
shape.

## The web UI

Server-rendered Thymeleaf over the same services the API uses — a page can never do something the
API can't, and a domain rejection shows as a plain sentence in a flash bar. Sign in at `/login`.
The bar is the same on every page: one **search box** and a fixed set of entries that keep pointing
at the site you're working in, whatever you clicked last.

**Everything printed is a link.** A slot code, pallet LPN, SKU, lot, order, wave, assignment,
manifest, shipment, customer, operator or equipment unit opens that thing's page, from any screen.

- **Search** — one box for pallets, slots, items, lots, orders, customers, waves, assignments,
  manifests, shipments, operators, equipment, serials and containers. An exact match (scan an LPN,
  type a slot) goes straight there; anything else lists hits grouped by kind. `/` focuses it.
- **Board** — lanes in floor order: Receiving → Putaway → Replenishment → Selection → Shipping,
  with zone filter chips, priority, and dispatch selects that only list operators **trained for
  that workflow**.
- **Locations** — each temperature zone with its fill level; open a zone for its aisles, an aisle
  for its placards (each cell links to its slot and its item). **Inbound** (receiving docks, cross-dock,
  inbound staging) and **Outbound staging** (drops, staging lanes, shipping docks) are their own
  screens. A **slot** page shows what's in it, what's headed to or from it, and
  its move history.
- **Pallets** / **Lots** — original vs on-hand quantity, where each pallet is and where it's
  directed, every stop with dwell, the orders it's promised to.
- **Receiving** → manifest → receive by line. Receiving a pallet always does three things at once:
  assigns an **LPN** (`PLT-1000001`… unless you scan the supplier's), lands it on a **dock**, and
  cuts a **putaway task** to a slot. A manifest can't be closed with nothing received; an untouched
  one can be cancelled.
- **Orders** (filter by customer, date, status) → order page with ship-to and its zone orders;
  **Waves** (pick a zone, tick zone orders or let the wave type choose; release with or without a
  unit); **Assignments**; **Shipping**.
- **Operators** — people, not assets: trained workflows, grants, personal labor, the work they've
  been handed, equipment they have out. **Equipment** — check out / check in to an operator, and
  what's riding in each position right now, back to the order and wave.
- **Dashboard**, **Items** (velocity is calculated from the last 30 days of picks), **Customers**
  (address, phone, order history), **Org**, the alert bell and **Notifications**.

**Picked stock stays stock.** A pick doesn't make cases vanish from the slot: the picked quantity
becomes a pick unit (`PK-n`) riding on the equipment, in its put position, for its zone order. The
equipment page shows what's on each position right now; dropping the load moves those units to the
drop location; shipping takes them out of the building. A selection assignment can exist before
its unit is known — put positions stay blank and nothing can be picked until someone sets the unit.

**Work built on the floor.** Releasing a wave either cuts its assignments at once or leaves the
allocated zone orders on the board ("Released, not built"). From there a supervisor ticks zone
orders and builds an assignment by hand (one zone, one wave, no more orders than the unit has
positions), or an operator presses **Give me my next assignment**: they resume what they hold,
else take the highest-priority work they're trained and permitted to do — and if that is unbuilt
selection, it's built for them on the spot, sized to the unit they have checked out.
API: `sites/{id}/work/next`, `sites/{id}/selection/unbuilt`, `sites/{id}/selection/build`,
`assignments/{id}/equipment`, and `waves/{id}/release {"build":"MANUAL"}`.

Replenishment has no button: a scan runs every minute (`REPLEN_SCAN_MS`, off with
`REPLEN_AUTO=false`) and cuts work for any pick face under its trigger.

**Sign-in.** Pages take the login form only; the API (`/api/**`) takes HTTP Basic only. They are
separate on purpose — a browser that has cached API credentials can't silently override who signed
in. The platform sysadmin belongs to no tenant: after signing in they get a tenant list and choose
one to work in for that browser session (the CLI equivalent is `ARTEMIS_CORP`).

Dispatch is gated twice: the dispatcher's role capability for that workflow, and the operator's
trained-workflow allowance (`users[].workflows` in the import). Untrained operators don't appear in
the select and are refused by the API with a reason.

## Conventions baked into the schema

- **Hierarchy** Corporation → District Region → Site Location → Area. Nesting is a trigger,
  so a wrong parent is a 409, never a corrupt tree.
- **RBAC** roles are data (ADMIN, SUPERVISOR, OPERATOR, READ_ONLY seeded); a grant attaches a
  role at a node; effective role = highest rank across the ancestry. Sysadmin is a flag on the
  user — platform tier, never a grant, invisible to tenants.
- **Tenancy** every tenant table carries `corporation_id`, RLS is `FORCE`d, and the app binds
  the caller's corporation on every connection checkout. Sysadmins without a selected tenant
  get `app.platform_admin = on`; inside a tenant they are scoped like anyone else.
- **Policies** set at Site or Area; nearest non-null value wins per field. Rotation falls back
  to FEFO for expiry-tracked items, FIFO otherwise.
- **Locations** one racking type, `STANDARD`; pick-face is a role (`replen_item_id` set).
  Slot names are `Zone-Aisle-Level-Column-Slot` (`D-07-3-12-B`); the zone is the temperature zone
  and aisles are simply numbered within it. The pick sequence (zone → aisle → column → slot →
  level) and check digits derive from the name by trigger. Docks, drops and staging lanes carry a **flow** instead — inbound or outbound. One item per slot and item-temp-zone-is-authoritative are enforced on
  `inventory` writes; docks, drops and staging are exempt.
- **Items** CS and PL UOM tiers derive from `case_pack_qty` / `pallet_ti × pallet_hi`.
  Expiry-tracked items need `shelf_life_days` or a `date_label`; catch-weight needs a nominal.
- **Assignments** the universal work unit; priority 1–10 with zone-aware defaults
  (frozen > chilled > dry); reassignment is tracked, not overwritten; rollup by trigger.
- **Receiving** a document with a timeline. One receipt = one LPN; lot/expiry/serial/
  catch-weight capture enforced at the dock; minimum-shelf-life-at-receipt sends product back
  on the truck; every pallet spawns a directed putaway task (consolidate → velocity → travel).
- **Putaway** check-digit verified; supervisor override needs a reason and is journaled as
  `PUTAWAY_OVERRIDE`.
- **Zone Orders** (order × temp zone) derive from the lines by trigger and are the unit of
  outbound work. A wave is single-zone by construction: the database refuses a zone order that
  doesn't match the wave's zone, so frozen never waits on dry.
- **Waves** RUSH, SHIP_URGENCY, CARRIER_CUTOFF, ROUTE (reverse stop order), PROXIMITY,
  SINGLE_CUSTOMER, ZONE (area filter). Release allocates, then cuts SELECTION assignments sized
  to the cart's positions; tasks walk the pick path with slot digits and put-position digits.
- **Allocation** pick faces first, then reserve in rotation order. When the customer's, the
  site's or the item's shelf-life term (strictest wins) disqualifies the strict-rotation pallet,
  the next qualifying pallet is taken, the row is marked `bypass`, and a ROTATION_BYPASS alert
  goes to the area so the team knows older stock is sitting.
- **Selection** both sides check-digit verified; short picks release the remainder for
  re-allocation; a pallet picked to zero is PICKED, never deleted. Drop is per assignment to a
  DROP/STAGING/dock location; an order is DROPPED once every zone is down.
- **Shipping** one customer per shipment, DROPPED orders only; the packing list is generated
  from what was picked (LPN, lot, expiry), not from what was ordered.
- **Replenishment** faces at or under trigger get work from the rotation-correct reserve pallet
  (`replen_source`), sized to max. Empty face = priority 9 + REPLEN_CRITICAL alert. Completing
  a partial move splits the pallet: `PLT-0001` → `PLT-0001.1` at the face, lineage kept.
- **Alerts** producers insert one `system_alert` row; a trigger fans out bells to everyone
  whose effective role carries `DASHBOARD_VIEW`, and CRITICAL alerts also to the email outbox,
  drained through the shared Mailgun domain (`artemis@mail.squiresenterprisesllc.com`).

## API

OpenAPI at `/api-docs` (UI at `/api-docs/ui`). Basic auth under the `local-auth` profile;
OIDC per tenant otherwise. Everything is under `/api/v1`:
`org`, `users`, `import`, `items`, `customers`, `sites/{siteId}/locations|equipment|containers|inventory|receiving|putaway/tasks|alerts`,
`sites/{siteId}/orders|waves|assignments|shipments|replen/pressure|replen/run`,
`receiving/{id}`, `putaway/tasks/{id}/complete`, `waves/{id}/release`, `assignments/{id}/assign|induct|drop`,
`selection/tasks/{id}/pick`, `replen/tasks/{id}/complete`, `shipments/{id}/ship`, `lpn/{lpn}`,
`notifications`, `admin/tenants/{id}/data`.

## Roadmap

- ~~Cycle 2~~ outbound + replenishment — done (v2.0.0-cycle2).
- ~~Cycle 3~~ the Thymeleaf UI, operator allowance gating on dispatch — done.
- ~~Cycle 4~~ linked navigation and universal search, system LPNs at receiving, slot / pallet / lot
  / operator / equipment pages, scheduled replenishment, separate page and API sign-in — done.
- ~~Cycle 5~~ picked stock rides on the unit as inventory, selection assignments built by hand,
  work handed out on request — done.
- ~~Cycle 6~~ location subscreens (zone → aisle, inbound, outbound staging), numbered aisles, UPC
  item ids, the mega dataset — done.
- **Next** permanent vs operational asset split.
- **Later** VoiceLink dialog endpoints, OIDC group → grant sync, custom roles portal.
