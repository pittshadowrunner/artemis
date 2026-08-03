# Daedalus WMS — Receiving & Put Away API (M3)

## Receiving flow

```
POST /api/v1/receiving/manifests            create expected manifest (lines by SKU)
POST /api/v1/receiving/manifests/{id}/arrive       truck at the door
POST /api/v1/receiving/manifests/{id}/receipts     one call per LPN
POST /api/v1/receiving/manifests/{id}/rejections   damage / temp abuse / short life
POST /api/v1/receiving/manifests/{id}/close        short shipments allowed, deliberately
GET  /api/v1/receiving/manifests/{id}              line-level expected/received/rejected
```

A receipt is where the system first learns about physical stock, so capture is
enforced at the dock: lot-tracked needs a lot, expiry-tracked needs a date,
serial-tracked needs exactly qty serials, catch-weight needs the scale reading.
The **minimum-shelf-life-at-receipt** policy (item override first, then the
site/area cascade) rejects product that should go back on the truck — a 21-day
minimum against a 14-days-remaining case of chicken fails at the door, not at
allocation. Over-receipt beyond 10% of expected requires correcting the
manifest line first.

Each receipt response includes the **directed putaway task**: destination slot,
its check digits, and the voice prompt ("Put away to R 01 01 A, check 42").
Cross-dock receipts pass `skipPutawayTask: true`.

## Directed slotting

One SQL function (`directed_putaway_slot`) is the single source of truth, so
the API, the voice dialog, and any future slotting simulator agree on what
"the right slot" means:

1. **Hard filters** — active STORAGE type, temperature zone matches the item,
   hazmat-approved if the item carries a hazmat class, and the site/area
   **mixing policy** (defaults: items may share a slot, lots may not — the
   policy cascade can flip either).
2. **Ranking** — same-item consolidation first, then velocity-zone match
   (A items toward A real estate), then shortest travel by pick sequence.

No qualifying slot is a hard error with a reason, not a silent dump to
overflow.

## Execution

```
GET  /api/v1/putaway/tasks?siteId=…          open queue, priority order
POST /api/v1/putaway/tasks/{taskId}/complete
```

Completion is **check-digit verified**: the operator speaks or scans the
destination digits; a mismatch is a hard stop. Supervisor overrides to a
different slot are allowed but require a reason and are audit-logged — putting
a pallet in the wrong slot *silently* is how inventory accuracy dies.

Every completion moves the LPN, writes a PUTAWAY movement with the operator
and assignment attached, and closes the assignment when its last task
finishes.

## Validated end-to-end

The full chain ran against live Postgres: manifest → receipt at the dock →
directed slot (refrigerated item correctly matched to refrigerated storage) →
check-digit task → completion → LPN in final slot, assignment COMPLETE.
