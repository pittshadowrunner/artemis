# Daedalus WMS — Master Data API (M2)

All endpoints live under `/api/v1`. Interactive docs at `/api-docs`.
Every call is capability-checked and tenant-scoped; row-level security applies
underneath, so a query physically cannot cross corporations.

## Bulk loads are partial-success

A 5,000-row location file with three bad rows loads 4,997 and returns exactly
which three failed and why. All-or-nothing rejection makes large uploads
unusable, because the operator has no way to find the offending row.

```json
{
  "createdCount": 4997,
  "skipped": 0,
  "errorCount": 3,
  "completeSuccess": false,
  "errors": [
    { "row": 412, "identifier": "C-04-02-A", "message": "Location already exists at this site." },
    { "row": 1188, "identifier": "F-99-01-A", "message": "Unknown temperature zone 'FREEZR'." },
    { "row": 3301, "identifier": "A-12-03-B", "message": "'abc' isn't a whole number." }
  ]
}
```

## Organization

| Method | Path | Capability |
|---|---|---|
| POST | `/org` | `ORG_MANAGE` |
| GET | `/org/{id}/tree` | authenticated |

Level nesting is enforced: a Site Location can only be created under a District
Region, an Area only under a Site. A Corporation must have no parent and becomes
its own `corporation_id`, which is what every tenant-scoped row keys off.

## Locations — three loading paths

**1. Scripted push** — `POST /locations/bulk`

```json
{
  "siteId": "…",
  "skipExisting": true,
  "locations": [
    { "code": "C-04-02-A", "locType": "PICK_FACE", "tempZone": "REFRIGERATED",
      "aisle": "C", "bay": "04", "tier": "02", "slot": "A", "pickSequence": 1200,
      "rackType": "CARTON_FLOW", "goldenZone": true,
      "replenSku": "CHKN-40", "replenMinQty": 4, "replenMaxQty": 24, "replenTriggerQty": 8 }
  ]
}
```

Note `replenSku` — you can reference the item by SKU instead of looking up its
UUID first, which is what makes scripted loads practical.

**2. Range generation** — `POST /locations/generate`

Describe the rack, we enumerate it:

```json
{
  "siteId": "…",
  "pattern": "{aisle}-{bay}-{tier}-{slot}",
  "locType": "STORAGE",
  "aisles": ["A","B","C"],
  "bays": ["01","02","03","04"],
  "tiers": ["01","02","03"],
  "slots": ["A","B"],
  "pickSequenceStart": 1000,
  "pickSequenceStep": 10,
  "tempZone": "AMBIENT",
  "rackType": "SELECTIVE"
}
```

That's 72 locations from one call. Pick sequence is assigned **serpentine** — up
one aisle, down the next — because that's how a picker actually walks, and
`pick_sequence` is what every proximity optimization downstream sorts on. Get
this wrong at load time and every wave travels badly forever.

**3. File upload** — `POST /locations/upload?siteId=…` (multipart, `.csv` or `.xlsx`)

Template: `docs/templates/locations-template.csv`

## Items

| Method | Path | Capability |
|---|---|---|
| POST | `/items/bulk` | `ITEM_MANAGE` |
| POST | `/items/upload` | `ITEM_MANAGE` |

Items load in **two passes**: all rows insert first, then `base_item_sku` links
resolve. A catalog export that lists a variant before its base item would
otherwise fail on a forward reference, which is the normal ordering in exported
files.

Validation that catches real problems at load time rather than at allocation
time:

- An expiry-tracked item with no `shelf_life_days` and no `date_label` is
  rejected — FEFO would have nothing to sort on.
- A catch-weight item with no `nominal_weight_kg` is rejected — there's no
  baseline to compare captured weights against.

Template: `docs/templates/items-template.csv`

## Inventory (opening balances)

| Method | Path | Capability |
|---|---|---|
| POST | `/inventory/upload` | `INVENTORY_ADJUST` |
| POST | `/inventory/upload-file` | `INVENTORY_ADJUST` |

This is the one path where stock enters without going through Receiving, so it
validates harder than Receiving does:

- Item and location must exist.
- **Temperature compatibility is enforced** — frozen product cannot land in an
  ambient slot.
- Lot-tracked item with no lot number → rejected. Expiry-tracked with no
  expiration date → rejected. Recall traceability and FEFO both depend on it.
- Serial-tracked items must supply exactly as many serials as the quantity.
- Already-expired dates are rejected.

Every loaded record also writes an `OPENING_BALANCE` row to
`inventory_movement`, so the audit trail shows where the stock came from rather
than having it appear from nowhere.

`replaceExisting=true` clears existing AVAILABLE inventory at the site first.
It logs a warning and does not touch allocated or picked stock.

Template: `docs/templates/inventory-template.csv`

## File handling notes

Both CSV and XLSX go through one `RowSource` abstraction, so importers don't
care which arrived. Two things that matter in practice:

- **Header normalization.** `"SKU "`, `"Sku"`, and `"sku"` all resolve to `sku`.
  Real sites send all three, sometimes in the same week.
- **Excel numeric coercion.** Excel stores everything numeric as a double, which
  turns SKU `00123` into `123.0` and long lot numbers into scientific notation.
  The XLSX reader routes through `BigDecimal.toPlainString()` so identifiers
  survive intact. CSV uploads are also BOM-tolerant, since Excel exports carry
  one.

Legacy `.xls` is rejected with a message telling the operator to re-save as
`.xlsx` — that's more useful than a generic parse failure.

## Beta sequence

```
1. POST /org                    (corporation → region → site → areas)
2. POST /items/upload           (item master)
3. POST /locations/generate     (rack structure)
   POST /locations/upload       (pick faces with replen triggers)
4. POST /inventory/upload-file  (opening balances)
```

That's the master-data half of the beta scenario. M3 picks up at Receiving.
