# Daedalus WMS — Outbound API (M4)

The full chain: order → allocate → wave → release → pick → drop → ship.

## Orders & Allocation

```
POST /api/v1/orders                 customer by code, lines by SKU
POST /api/v1/orders/{id}/allocate
```

Allocation walks candidates in the rotation order from `effective_rotation`
(policy cascade first, else FEFO for expiry-tracked / FIFO otherwise, proximity
as tiebreaker). The freshness cutoff is the **strictest** of the customer's
minimum-remaining-life, the item's ship minimum, and the site/area policy ship
minimum.

**The bypass, per the agreed policy:** when the lot strict rotation would take
fails the freshness cutoff, the engine skips it and fills from the next closest
date that qualifies — and writes a `ROTATION_BYPASS` alert (WARNING) scoped to
the bypassed lot's Area (falling back to Site) so the team knows the system
made that call and aging stock needs disposition. The allocation response also
carries the messages inline. Validated live: 30-day customer rule, 10-day lot
skipped, order filled from the 60-day lot, alert written.

Shorts leave the order in NEW with allocated quantities recorded, so a
follow-up allocation after receiving tops it off.

## Waves

```
POST /api/v1/waves                  explicit orderIds, or auto-select
POST /api/v1/waves/{id}/release     { equipmentCode, putMode: TOTE | SHIPPING_CONTAINER }
```

Auto-select is type-appropriate: SHIP_URGENCY/CARRIER_CUTOFF take the earliest
ship dates, PROXIMITY takes the tightest pick spans, ROUTE takes a route's
orders in reverse stop sequence (last stop loads first).

**Release is where batching happens.** With a cart, batch size =
`container_positions`: orders sort by the start of their pick path and chunk N
at a time, so each trip covers one stretch of the building. Each order maps to
a cart position; tasks merge across the chunk's orders and sort by
`pick_sequence` — the walk order. Put check digits come from
`equipment_position` (or derive per-order if the cart has none registered).

## Selection

```
GET  /api/v1/selection/assignments/{id}/tasks
POST /api/v1/selection/assignments/{id}/induct     first pick of an order (SHIPPING_CONTAINER mode)
POST /api/v1/selection/tasks/{id}/pick
POST /api/v1/selection/drops
```

Fully system-driven, both sides verified:

- **Pick side** — slot check digits must match.
- **Put side** — the confirmation must match the cart position's digits, the
  tote barcode, or the order's inducted shipping-container LPN. On a
  shipping-container flow with nothing inducted yet, the pick is refused with
  the induction instruction — exactly the "induct the LPN on the first pick,
  track it for every later pick on that order" behavior specified.

Partial picks **split the LPN**: the picked quantity moves to a child record
(status PICKED), the remainder stays AVAILABLE in the slot. Shorts require a
reason and are logged, never silently rounded. Order status advances
RELEASED → PICKING on first pick → PICKED when every line is complete.

Drop moves all of an order's picked inventory to a drop location (honoring a
preset drop on the order), writes DROP movements, and advances the order to
DROPPED.

## Shipping

```
POST /api/v1/shipments                       from DROPPED orders, one customer
POST /api/v1/shipments/{id}/packing-list
POST /api/v1/shipments/{id}/ship             packing list required first
```

The packing list is generated from **picked reality** — lots, expiration
dates, captured catch weights, serials — not from what was ordered, because
the document on the truck must describe the physical freight. Shipping flips
inventory to SHIPPED, writes SHIP movements, and closes the orders.

## Alerts

```
GET  /api/v1/alerts?siteId=…&areaId=…
POST /api/v1/alerts/{id}/acknowledge
```

Open alerts for the site or a specific area, newest first; acknowledgment is
recorded with user and timestamp.
