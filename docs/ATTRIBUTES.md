# Attribute Catalog — Industry Deep Dive (V2)

What got added beyond the original spec, and why. Everything below is live in `V2__policies_and_attributes.sql` and validated.

## Inventory control policies (the smallest-unit-wins cascade)

Policies attach to any Site Location or Area. Every field is nullable, and NULL means "inherit." Resolution walks from the Area upward and takes the nearest non-null value *per field* — so a cooler Area can flip rotation to FEFO while still inheriting the Site's 30-day receipt shelf-life rule. If nothing is set anywhere, `effective_rotation()` falls back to the industry default: FEFO for expiry-tracked items, FIFO by arrival date otherwise.

Policy fields: rotation strategy (FEFO/FIFO/LIFO/NONE), min remaining shelf life to accept at receipt, min remaining shelf life to allocate for shipping, expiry alert horizon, lot mixing allowed per location, item mixing allowed per location, catch-weight tolerance %, and whether lot/expiry capture is mandatory at receiving.

## Item attributes

**Cold storage.** `temp_zone` (deep frozen → heated) with optional min/max °C. Putaway and allocation check `temp_compatible(item, location)` so frozen product never gets directed to an ambient slot.

**Food service.** `catch_weight` + `nominal_weight_kg` — variable-weight product (proteins, cheese wheels) where you bill by actual captured weight, not units; actual weight is captured on the inventory record at receiving and flows to the packing list. `date_label` distinguishes use-by vs sell-by vs best-by vs pack date, which changes how FEFO math and customer shelf-life guarantees work. `allergens[]` and `certifications[]` (organic/kosher/halal/non-GMO) support segregation rules and label compliance. `country_of_origin` for COOL labeling. Item-level shelf-life overrides trump the org policy for sensitive SKUs.

**GS1 / packaging hierarchy.** GTINs at each and case level, inner pack, case pack, and Ti×Hi (cases per layer × layers per pallet). Ti-Hi drives pallet build math in outbound and receiving appointment estimates.

**Handling.** Hazmat class + UN number (gates hazmat-approved locations), fragile, this-side-up, stack limit, crushable, conveyable (non-conveyables route around any future sortation).

**Retail.** Style/color/size/season codes for apparel-style variant matrices (these hang off your base-item designation), plus unit cost and retail price for inventory valuation and retailer compliance docs.

**Slotting.** `velocity_class` (A/B/C) pairs with location `velocity_zone` so fast movers live on the best real estate.

## Location attributes

Temp zone and humidity control, hazmat approval, rack type (selective, drive-in, push-back, pallet flow, carton flow, shelving, floor stack, mezzanine — matters for rotation feasibility: drive-in racking is physically LIFO), equipment class restriction (reach truck vs order picker vs walkie), golden-zone flag for ergonomic pick heights, and velocity zone.

The big one: **pick-face replenishment triggers** — a dedicated item plus min/max/trigger quantities per pick face. This is what actually generates your Replenishment workstream: when on-hand at the face drops to trigger, the system creates a slot-to-slot replen assignment from reserve storage.

## Customer attributes

Route code and stop sequence — in food distribution the load sequence is the reverse of the delivery stop sequence, so this drives outbound staging order. Delivery windows. Minimum remaining shelf life the customer will accept (a grocery chain demanding 75% remaining life is a hard allocation constraint, layered on top of your own ship policy). GS1 label and ASN (EDI 856) compliance flags. Pallet build preference (store-friendly / single-SKU / mixed).

## Deliberately deferred

Cycle counting programs, UOM conversion tables beyond each/inner/case, kitting/BOM, yard management, labor standards, and billing/3PL invoicing. All are natural post-beta modules; nothing in the schema blocks them.
