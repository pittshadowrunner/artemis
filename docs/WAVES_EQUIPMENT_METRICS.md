# Waves, Equipment & Metrics — Design Notes (V3)

## Wave types

Eight types shipped in the `wave_type` enum. Rush pulls expedited orders to the front of everything. Proximity builds waves by clustering orders whose allocated pick locations sit in a tight `pick_sequence` span — the `order_pick_span()` function scores each order's location spread, and the wave planner greedily groups orders with overlapping spans to cut travel. Ship urgency sorts by earliest requested ship date. Carrier cutoff is the sharper version of that: everything that must make a specific trailer departure, with the cutoff timestamp on the wave itself. Route waves group one delivery route and load in reverse stop sequence (last stop loaded first). Zone waves confine work to one Area, which is also how you'd run zone picking with handoffs later. Single-customer waves handle full-trailer builds. Replen-aware waves check `v_replen_pressure` first and hold release until the faces they'll hit are topped off — nothing kills pick rates like sending ten pickers to an empty face.

Waves are planned → released → picking → complete. Releasing a wave is what generates selection assignments, which is where batching happens.

## Batch cart picking

The chain: a cart is `equipment` with `container_positions` (how many totes/containers fit). Releasing orders to a cart-based assignment takes up to that many orders, chosen for overlapping pick spans. Each order maps to a cart position via `assignment_container`.

Two put modes, both fully system-driven and voice-ready:

**Pick to tote.** Each position holds a tote (`container` with barcode + check digits). The task tells the picker: go to slot (verify slot check digits), pick qty, put to position N (verify the position's or tote's check digits). `assignment_task` carries both `check_digits` (slot side) and `put_check_digits` (put side), so the voice dialog is symmetrical — verify where you took it from *and* where you put it.

**Pick direct to shipping container.** First pick for an order prompts LPN induction: picker scans/speaks a new shipping-container LPN, it's stamped on `assignment_container.inducted_lpn`, and every subsequent pick for that order — this trip or a later wave — directs the put to that same LPN. At drop/ship time the inducted LPN becomes the shipped handling unit on the packing list.

Pallet jacks and riders get the same treatment at pallet scale: the jack's carried pallet LPN lives on `equipment.lpn`, and its `check_digits` validate put positioning for full-case/pallet picks.

## Dashboard catalog

Built on the seven views, all snapshot-on-load (no auto-refresh, per your call):

**Pick-face velocity** (`v_pick_face_velocity`) — lines, visits, and cases per face per day. Visits ≠ lines: a face with high visits but low cases per visit is a re-slotting candidate (too popular for its position), and the velocity view against `velocity_zone` shows A-items sitting in C real estate.

**Day progress — receiving** (`v_receiving_progress`) — expected vs received per manifest, percent complete, so the dock knows where the day stands by lunch.

**Day progress — shipping** (`v_shipping_progress` + `v_wave_progress`) — order pipeline by status plus per-wave task completion, with carrier cutoffs as the deadline markers.

**Replenishment pressure** (`v_replen_pressure`) — faces at/below trigger, the queue the replen workstream eats from.

**Labor productivity** (`v_labor_productivity`) — tasks and cases by user, day, and assignment type. Useful and sensitive: surface it to supervisors, not as a public leaderboard, at least until you decide on incentive policy.

**Expiry risk** (`v_expiry_risk`) — lots by days remaining, the FEFO early-warning tile.

Worth adding post-beta: dock door utilization, order cycle time (order create → ship), fill rate (allocated vs ordered), inventory accuracy from cycle counts, and space utilization by temp zone (already sketched in the ops mock).
