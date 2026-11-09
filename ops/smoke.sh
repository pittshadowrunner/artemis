#!/usr/bin/env bash
# End-to-end smoke test against a running, EMPTY Artemis (fresh volume,
# sysadmin bootstrapped). Applies both datasets, runs the inbound loop as an
# operator, and checks the tenant boundary. Exits non-zero on the first
# assertion that fails. Requires curl + jq.
#   ARTEMIS_URL=http://localhost:8085 SYSADMIN_PASS=... ops/smoke.sh
set -euo pipefail
cd "$(dirname "$0")"
export ARTEMIS_URL="${ARTEMIS_URL:-http://localhost:8085}"
SYS_PASS="${SYSADMIN_PASS:?set SYSADMIN_PASS}"
pass=0; fail=0
check() { # label expected actual
  if [ "$2" = "$3" ]; then echo "  ok   $1"; pass=$((pass+1)); else echo "  FAIL $1: expected [$2] got [$3]"; fail=$((fail+1)); fi; }
as() { export ARTEMIS_USER="$1" ARTEMIS_PASS="$2"; }

echo "== hierarchy (sysadmin)"; as sysadmin@artemis.local "$SYS_PASS"
r=$(./artemis import data/hierarchy.example.yaml)
check "corporation created" "SQE" "$(jq -r .corporation.code <<<"$r")"
check "5 org nodes"          "5"   "$(jq -r .hierarchy.created <<<"$r")"
check "4 users"              "4"   "$(jq -r .users.created <<<"$r")"

echo "== demo data (tenant admin)"; as tom@squiresenterprisesllc.com change-me
r=$(./artemis import data/demo-pit1.yaml)
check "10 items"             "10"  "$(jq -r .items.created <<<"$r")"
check "120 rack slots (4 faces pre-declared)" "116/4" "$(jq -r '."site:PIT1".racks | "\(.created)/\(.updated)"' <<<"$r")"
check "16 pallets, 1 rejected" "16/PLT-0999" "$(jq -r '."site:PIT1".inventory | "\(.created)/\(.errors[0].identifier)"' <<<"$r")"
check "re-apply is idempotent" "0/10" "$(./artemis import data/demo-pit1.yaml | jq -r '.items | "\(.created)/\(.updated)"')"

echo "== inbound loop (operator)"; as j.okafor@pit1.local change-me
SITE=$(./artemis sites | jq -r '.[0].site_id'); MAN=$(./artemis get "sites/$SITE/receiving" | jq -r '.[0].manifest_id')
check "manifest arrives" "ARRIVED" "$(./artemis post receiving/$MAN/arrive | jq -r .status)"
check "short-dated milk rejected" "409" "$(./artemis post receiving/$MAN/pallets '{"lineNumber":2,"lpn":"PLT-1101","qty":48,"lot":"L1","expirationDate":"today+8"}' | jq -r .status)"
check "catch-weight needs scale"  "400" "$(./artemis post receiving/$MAN/pallets '{"lineNumber":3,"lpn":"PLT-1201","qty":48,"lot":"C1","expirationDate":"today+250"}' | jq -r .status)"
p=$(./artemis post receiving/$MAN/pallets '{"lineNumber":1,"lpn":"PLT-1001","qty":50}')
check "dry pallet consolidates with same item" "D-A-2-01-A" "$(jq -r .putaway.destination <<<"$p")"
./artemis post receiving/$MAN/pallets '{"lineNumber":2,"lpn":"PLT-1101","qty":48,"lot":"L1","expirationDate":"today+56"}' >/dev/null
./artemis post receiving/$MAN/pallets '{"lineNumber":3,"lpn":"PLT-1201","qty":48,"lot":"C1","expirationDate":"today+250","actualWeightKg":869.4}' >/dev/null
tasks=$(./artemis get "sites/$SITE/putaway/tasks")
check "frozen work first" "FROZEN,REFRIGERATED,AMBIENT" "$(jq -r '[.[].temp_zone]|join(",")' <<<"$tasks")"
T1=$(jq -r '.[] | select(.lpn=="PLT-1001") | .task_id' <<<"$tasks"); D1=$(jq -r '.[] | select(.lpn=="PLT-1001") | .check_digits' <<<"$tasks")
T2=$(jq -r '.[] | select(.lpn=="PLT-1101") | .task_id' <<<"$tasks")
check "wrong digits hard-stop"   "409" "$(./artemis post putaway/tasks/$T1/complete '{"checkDigits":"00"}' | jq -r .status)"
check "right digits complete"    "false" "$(./artemis post putaway/tasks/$T1/complete "{\"checkDigits\":\"$D1\"}" | jq -r .override)"
check "override needs a reason"  "400" "$(./artemis post putaway/tasks/$T2/complete '{"overrideLocation":"C-A-1-02-A"}' | jq -r .status)"
check "override into wrong zone blocked" "409" "$(./artemis post putaway/tasks/$T2/complete '{"overrideLocation":"F-A-1-02-A","overrideReason":"x"}' | jq -r .status)"
check "override journaled"       "PUTAWAY_OVERRIDE" "$(./artemis post putaway/tasks/$T2/complete '{"overrideLocation":"C-A-1-02-A","overrideReason":"spill"}' >/dev/null; ./artemis get lpn/PLT-1101 | jq -r '.history[-1].movement_type')"
check "manifest closes short"    "CLOSED" "$(./artemis post receiving/$MAN/close | jq -r .status)"

echo "== outbound (supervisor)"; as supervisor@pit1.local change-me
check "4 orders, zones derived" "4/FROZEN,REFRIGERATED,AMBIENT" "$(./artemis get "sites/$SITE/orders" | jq -r '[length, (.[] | select(.order_number=="ORD-1001") | .zones)] | join("/")')"
W_RUSH=$(./artemis post "sites/$SITE/waves" '{"type":"RUSH","tempZone":"FROZEN"}')
check "RUSH frozen wave takes only the rush order" "ORD-1003" "$(jq -r '[.zoneOrders[].order_number]|join(",")' <<<"$W_RUSH")"
check "frozen zone order can't join a chilled wave (schema)" "0" "$(./artemis post "sites/$SITE/waves" '{"type":"SHIP_URGENCY","tempZone":"REFRIGERATED","orderNumbers":["ORD-1003"]}' | jq -r '.zoneOrders|length')"
W_CHL=$(./artemis post "sites/$SITE/waves" '{"type":"SHIP_URGENCY","tempZone":"REFRIGERATED"}')
check "chilled wave: ship-date order" "ORD-1001,ORD-1002" "$(jq -r '[.zoneOrders[].order_number]|join(",")' <<<"$W_CHL")"
REL=$(./artemis post "waves/$(jq -r .wave_id <<<"$W_CHL")/release" '{"equipment":"CART-01"}')
check "release: 1 cart, 1 shelf-life bypass" "1/1" "$(jq -r '"\(.release.assignmentsCut)/\(.release.bypasses)"' <<<"$REL")"
check "bypass skipped the 8-day face stock for PLT-0101" "PLT-0101/true" "$(./artemis get "sites/$SITE/orders/ORD-1001" | jq -r '.lines[] | select(.sku=="CHL-2001") | .allocations[0] | "\(.lpn)/\(.bypass)"')"
check "ROTATION_BYPASS alert raised" "ROTATION_BYPASS" "$(./artemis get "sites/$SITE/alerts" | jq -r '.[0].alert_type')"
A_CHL=$(jq -r '.assignments[0].assignment_id' <<<"$REL"); ASG=$(./artemis get assignments/$A_CHL)
check "tasks walk the pick path" "true" "$(jq -r '[.tasks[].from_location] as $l | ($l == ($l|sort))' <<<"$ASG")"
T=$(jq -r '.tasks[0]' <<<"$ASG"); TID=$(jq -r .task_id <<<"$T"); CD=$(jq -r .check_digits <<<"$T"); PD=$(jq -r .put_check_digits <<<"$T")
check "wrong put digits → hard stop" "409" "$(./artemis post selection/tasks/$TID/pick "{\"checkDigits\":\"$CD\",\"putCheckDigits\":\"00\"}" | jq -r .status)"
check "pick verified both sides"    "false" "$(./artemis post selection/tasks/$TID/pick "{\"checkDigits\":\"$CD\",\"putCheckDigits\":\"$PD\"}" | jq -r .shorted)"
for row in $(jq -r '.tasks[1:][] | "\(.task_id)|\(.check_digits)|\(.put_check_digits)"' <<<"$ASG"); do IFS='|' read -r tid cd pd <<<"$row"; ./artemis post selection/tasks/$tid/pick "{\"checkDigits\":\"$cd\",\"putCheckDigits\":\"$pd\"}" >/dev/null; done
check "assignment rolls up COMPLETE" "COMPLETE" "$(./artemis get assignments/$A_CHL | jq -r .status)"
check "drop refuses a rack slot"    "400" "$(./artemis post assignments/$A_CHL/drop '{"dropLocation":"D-A-1-01-A"}' | jq -r .status)"
check "drop at DROP-01"             "2"   "$(./artemis post assignments/$A_CHL/drop '{"dropLocation":"DROP-01"}' | jq -r .dropped)"
# remaining zones: ambient (3 orders) and the two frozen waves
W_DRY=$(./artemis post "sites/$SITE/waves" '{"type":"PROXIMITY","tempZone":"AMBIENT"}')
check "ambient proximity wave" "3" "$(jq -r '.zoneOrders|length' <<<"$W_DRY")"
W_FRZ=$(./artemis post "sites/$SITE/waves" '{"type":"SHIP_URGENCY","tempZone":"FROZEN"}')
pickall() { # wave json
  local rel; rel=$(./artemis post "waves/$(jq -r .wave_id <<<"$1")/release" '{"equipment":"CART-02"}')
  for aid in $(jq -r '.assignments[].assignment_id' <<<"$rel"); do
    for row in $(./artemis get assignments/$aid | jq -r '.tasks[] | "\(.task_id)|\(.check_digits)|\(.put_check_digits)"'); do
      IFS='|' read -r tid cd pd <<<"$row"; ./artemis post selection/tasks/$tid/pick "{\"checkDigits\":\"$cd\",\"putCheckDigits\":\"$pd\"}" >/dev/null; done
    ./artemis post assignments/$aid/drop '{"dropLocation":"DROP-02"}' >/dev/null
  done; }
pickall "$W_DRY"; pickall "$W_RUSH"; pickall "$W_FRZ"
check "all orders DROPPED" "DROPPED,DROPPED,DROPPED,DROPPED" "$(./artemis get "sites/$SITE/orders" | jq -r '[.[].status]|join(",")')"
check "face PF-0001 picked to zero stays in the journal" "PICKED/true" "$(./artemis get lpn/PF-0001 | jq -r '"\(.status)/\((.qty|tonumber)==0)"')"
SHIP=$(./artemis post "sites/$SITE/shipments" '{"customer":"GIANT-EAGLE-012","orderNumbers":["ORD-1001","ORD-1004"],"carrier":"Estes","dock":"DOCK-S1"}')
check "shipment refuses another customer's order" "400" "$(./artemis post "sites/$SITE/shipments" '{"customer":"GIANT-EAGLE-012","orderNumbers":["ORD-1002"]}' | jq -r .status)"
check "packing list from picked lots" "PL-/L24091" "$(jq -r '"\(.packingList[0].document_number[0:3])/\((.packingList[] | select(.sku=="CHL-2001") | .lot_number))"' <<<"$SHIP")"
check "shipped" "SHIPPED" "$(./artemis post shipments/$(jq -r .shipment_id <<<"$SHIP")/ship | jq -r .status)"

echo "== replenishment (supervisor)"
check "empty face under pressure" "D-A-1-01-A" "$(./artemis get "sites/$SITE/replen/pressure" | jq -r '.[] | select(.on_hand==0) | .location_code' | head -1)"
RUN=$(./artemis post "sites/$SITE/replen/run")
check "replen tasks cut" "true" "$(jq -r '.created >= 1' <<<"$RUN")"
check "REPLEN_CRITICAL alert for the empty face" "1" "$(./artemis get "sites/$SITE/alerts" | jq -r '[.[] | select(.alert_type=="REPLEN_CRITICAL")] | length')"
RT=$(./artemis get "sites/$SITE/assignments?type=REPLENISHMENT" | jq -r '.[] | select(.priority==9) | .assignment_id' | head -1)
RTASK=$(./artemis get assignments/$RT | jq -r '.tasks[0]'); RTID=$(jq -r .task_id <<<"$RTASK"); RCD=$(jq -r .check_digits <<<"$RTASK")
check "replen check digits" "409" "$(./artemis post replen/tasks/$RTID/complete '{"checkDigits":"00"}' | jq -r .status)"
check "pallet split: child LPN lands on the face" "PLT-0001.1" "$(./artemis post replen/tasks/$RTID/complete "{\"checkDigits\":\"$RCD\"}" | jq -r .lpnAtFace)"
check "parent keeps lineage-correct remainder" "true" "$(./artemis get lpn/PLT-0001 | jq -r '.qty|tonumber < 50')"
check "face no longer under pressure" "0" "$(./artemis get "sites/$SITE/replen/pressure" | jq -r '[.[] | select(.location_code=="D-A-1-01-A")] | length')"

echo "== ui (supervisor)"; as supervisor@pit1.local change-me
ui() { curl -s -o /tmp/ui.html -w "%{http_code}" -u "$ARTEMIS_USER:$ARTEMIS_PASS" "$ARTEMIS_URL$1"; }
page() { # label path marker
  local code; code=$(ui "$2"); if [ "$code" = "200" ] && grep -q "$3" /tmp/ui.html; then echo "  ok   page $1"; pass=$((pass+1)); else echo "  FAIL page $1: http $code, marker '$3' $(grep -q "$3" /tmp/ui.html && echo found || echo missing)"; fail=$((fail+1)); fi; }
check "login page is public" "200" "$(curl -s -o /dev/null -w '%{http_code}' "$ARTEMIS_URL/login")"
check "unauthenticated browser redirects to login" "302" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: text/html' "$ARTEMIS_URL/sites/$SITE/board")"
page board      "/sites/$SITE/board"      "ops board"
page racking    "/sites/$SITE/racking"    "Zone D · Aisle A"
page lpn        "/lpn?q=PLT-0001"         "Journal"
page receiving  "/sites/$SITE/receiving"  "New manifest"
page manifest   "/receiving/$MAN"         "Pallets received"
page orders     "/sites/$SITE/orders"     "ORD-1001"
page order      "/orders/$(./artemis get sites/$SITE/orders/ORD-1001 | jq -r .order_id)" "BYPASS"
page waves      "/sites/$SITE/waves"      "Plan a wave"
page wave       "/waves/$(jq -r .wave_id <<<"$W_CHL")" "Zone orders"
page assignment "/assignments/$A_CHL"     "Cart positions"
page replen-asg "/assignments/$RT"        "Replenish"
page shipments  "/sites/$SITE/shipments"  "Build a shipment"
page shipment   "/shipments/$(jq -r .shipment_id <<<"$SHIP")" "Packing list"
page dashboard  "/sites/$SITE/dashboard"  "Pick-face velocity"
page alerts     "/sites/$SITE/alerts"     "ROTATION_BYPASS"
page notifications "/notifications"       "Notifications"
page items      "/items"                  "CHL-2001"
page item       "/items/CHL-2002"         "UOM tiers"
page customers  "/customers"              "GIANT-EAGLE-012"
page org        "/org"                    "FREEZER"
page policy     "/org/$(./artemis get org/sites | jq -r '.[0].site_id')/policy" "Effective (after cascade)"
page users      "/users"                  "Trained for"
ui "/sites/$SITE/board" >/dev/null; check "forms carry CSRF tokens" "true" "$(grep -c '_csrf' /tmp/ui.html | awk '{print ($1>0)?"true":"false"}')"
check "board offers dispatch with operator load" "true" "$(grep -q 'J. Okafor (' /tmp/ui.html && grep -q 'Dispatch' /tmp/ui.html && echo true)"
echo "== ui dispatch gate (api)"
PA=$(./artemis get "sites/$SITE/assignments?type=PUTAWAY" | jq -r '.[0].assignment_id // empty')
ALV=$(./artemis get users | jq -r '.[] | select(.email=="m.alvarez@pit1.local") | .user_id')
OKA=$(./artemis get users | jq -r '.[] | select(.email=="j.okafor@pit1.local") | .user_id')
check "untrained operator refused for PUTAWAY" "409" "$(./artemis post "assignments/${PA:-$RT}/assign" "{\"userId\":\"$ALV\"}" | jq -r .status)"
check "eligible list for SELECTION excludes Okafor" "false" "$(./artemis get "sites/$SITE/dispatch/eligible?type=SELECTION" | jq -r '[.[].email] | index("j.okafor@pit1.local") != null')"

echo "== tenant boundary"; as sysadmin@artemis.local "$SYS_PASS"
printf 'corporation: {code: ACME, name: Acme Foods}\nregions: [{code: MW, name: Midwest, sites: [{code: CHI1, name: Chicago}]}]\n' > /tmp/acme.yaml
ACME=$(./artemis import /tmp/acme.yaml | jq -r .corporation.orgNodeId)
check "sysadmin sees all tenants"   "ACME,SQE" "$(./artemis corporations | jq -r '[.[].code]|join(",")')"
check "sysadmin scoped inside ACME" "CHI1"     "$(ARTEMIS_CORP=$ACME ./artemis sites | jq -r '[.[].code]|join(",")')"
check "sysadmin write needs tenant" "400"      "$(./artemis post items/bulk '[{"sku":"X","description":"x"}]' | jq -r .status)"
as tom@squiresenterprisesllc.com change-me
check "tenant admin sees own corp only" "SQE"  "$(./artemis corporations | jq -r '[.[].code]|join(",")')"
check "tenant admin can't create corps" "403"  "$(./artemis post org/corporations '{"code":"EVIL","name":"x"}' | jq -r .status)"
check "other tenant's node is invisible" "404" "$(./artemis get org/$ACME | jq -r .status)"
as m.alvarez@pit1.local change-me
check "operator lacks ITEM_MANAGE" "403" "$(./artemis post items/bulk '[{"sku":"X","description":"x"}]' | jq -r .status)"
as sysadmin@artemis.local "$SYS_PASS"
printf 'ACME\n' | ./artemis wipe $ACME all >/dev/null
check "wipe removes the tenant" "SQE" "$(./artemis corporations | jq -r '[.[].code]|join(",")')"

echo; echo "passed=$pass failed=$fail"; [ "$fail" -eq 0 ]
