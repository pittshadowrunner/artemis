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
check "an empty manifest can't be closed" "409" "$(./artemis post receiving/$MAN/close | jq -r .status)"
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
p=$(./artemis post receiving/$MAN/pallets '{"lineNumber":1,"qty":10}')
check "no LPN given: the system assigns one" "true" "$(jq -r '.lpn | test("^PLT-1[0-9]{6}$")' <<<"$p")"
check "…on the dock, with a putaway task to a slot" "DOCK-R1/true" "$(jq -r '"\(.location)/\(.putaway.destination != null)"' <<<"$p")"
check "every received pallet has an LPN and a place" "4/0" "$(./artemis get receiving/$MAN | jq -r '[(.pallets|length), ([.pallets[] | select(.lpn == null or .location == null)] | length)] | join("/")')"
check "a duplicate LPN is refused" "409" "$(./artemis post receiving/$MAN/pallets '{"lineNumber":1,"lpn":"PLT-1001","qty":1}' | jq -r .status)"
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
./artemis post "sites/$SITE/replen/run" >/dev/null   # the scheduled scan may already have cut these; either way they exist now
check "replen tasks cut" "true" "$(./artemis get "sites/$SITE/assignments?type=REPLENISHMENT" | jq -r 'length >= 1')"
check "a second scan cuts nothing twice" "0" "$(./artemis post "sites/$SITE/replen/run" | jq -r .created)"
check "REPLEN_CRITICAL alert for the empty face" "1" "$(./artemis get "sites/$SITE/alerts" | jq -r '[.[] | select(.alert_type=="REPLEN_CRITICAL")] | length')"
RT=$(./artemis get "sites/$SITE/assignments?type=REPLENISHMENT" | jq -r '.[] | select(.priority==9) | .assignment_id' | head -1)
RTASK=$(./artemis get assignments/$RT | jq -r '.tasks[0]'); RTID=$(jq -r .task_id <<<"$RTASK"); RCD=$(jq -r .check_digits <<<"$RTASK")
check "replen check digits" "409" "$(./artemis post replen/tasks/$RTID/complete '{"checkDigits":"00"}' | jq -r .status)"
check "pallet split: child LPN lands on the face" "PLT-0001.1" "$(./artemis post replen/tasks/$RTID/complete "{\"checkDigits\":\"$RCD\"}" | jq -r .lpnAtFace)"
check "parent keeps lineage-correct remainder" "true" "$(./artemis get lpn/PLT-0001 | jq -r '.qty|tonumber < 50')"
check "face no longer under pressure" "0" "$(./artemis get "sites/$SITE/replen/pressure" | jq -r '[.[] | select(.location_code=="D-A-1-01-A")] | length')"

echo "== picked stock rides on the unit; work built on the floor"; as supervisor@pit1.local change-me
check "nothing waiting to be built yet" "0" "$(./artemis get "sites/$SITE/selection/unbuilt" | jq -r length)"
r=$(./artemis post "sites/$SITE/orders/bulk" '[{"orderNumber":"ORD-2001","customer":"GIANT-EAGLE-012","lines":[{"sku":"DRY-1002","qty":2}]},{"orderNumber":"ORD-2002","customer":"GIANT-EAGLE-012","lines":[{"sku":"DRY-1003","qty":3}]},{"orderNumber":"ORD-2003","customer":"GIANT-EAGLE-012","lines":[{"sku":"DRY-1004","qty":1}]}]')
check "three more orders" "3" "$(jq -r .created <<<"$r")"
W_MAN=$(./artemis post "sites/$SITE/waves" '{"type":"SHIP_URGENCY","tempZone":"AMBIENT"}')
REL=$(./artemis post "waves/$(jq -r .wave_id <<<"$W_MAN")/release" '{"build":"MANUAL"}')
check "manual release allocates but cuts nothing" "0/3" "$(jq -r '"\(.release.assignmentsCut)/\(.release.leftToBuild)"' <<<"$REL")"
check "three zone orders wait on the board" "3" "$(./artemis get "sites/$SITE/selection/unbuilt" | jq -r 'length')"
check "an order that isn't waiting can't be built" "409" "$(./artemis post "sites/$SITE/selection/build" '{"tempZone":"AMBIENT","orderNumbers":["ORD-1001"]}' | jq -r .status)"
B=$(./artemis post "sites/$SITE/selection/build" '{"tempZone":"AMBIENT","orderNumbers":["ORD-2001"]}')
check "built by hand, unit not chosen yet" "1/null" "$(jq -r '"\(.zoneOrders)/\(.equipment)"' <<<"$B")"
A_MAN=$(jq -r .assignmentId <<<"$B"); T=$(./artemis get assignments/$A_MAN | jq -r '.tasks[0]'); TID=$(jq -r .task_id <<<"$T")
check "no unit → nothing can be picked" "409" "$(./artemis post selection/tasks/$TID/pick "{\"checkDigits\":\"$(jq -r .check_digits <<<"$T")\",\"putCheckDigits\":\"$(jq -r .put_check_digits <<<"$T")\"}" | jq -r .status)"
check "choose the unit" "CART-01/true" "$(./artemis post assignments/$A_MAN/equipment '{"equipment":"CART-01"}' | jq -r '"\(.equipment)/\(.changed)"')"
T=$(./artemis get assignments/$A_MAN | jq -r '.tasks[0]')
check "put digits now come from the unit's position" "$(./artemis get "sites/$SITE/equipment" | jq -r '.[] | select(.code=="CART-01") | .equipment_id' >/dev/null; jq -r .put_check_digits <<<"$T")" "$(./artemis get assignments/$A_MAN | jq -r '.containers[0].put_check_digits')"
P=$(./artemis post selection/tasks/$TID/pick "{\"checkDigits\":\"$(jq -r .check_digits <<<"$T")\",\"putCheckDigits\":\"$(jq -r .put_check_digits <<<"$T")\"}")
PKU=$(jq -r .pickUnit <<<"$P")
check "the pick becomes a unit of stock" "true" "$(jq -r '.pickUnit | test("^PK-[0-9]+$")' <<<"$P")"
check "unit can't change once picks ride on it" "409" "$(./artemis post assignments/$A_MAN/equipment '{"equipment":"CART-02"}' | jq -r .status)"
check "wave stays open while orders are unbuilt" "PICKING" "$(./artemis get "waves/$(jq -r .wave_id <<<"$W_MAN")" | jq -r .status)"

echo "== work on request (operators)"; as m.alvarez@pit1.local change-me
CART2=$(./artemis get "sites/$SITE/equipment" | jq -r '.[] | select(.code=="CART-02") | .equipment_id')
./artemis post equipment/$CART2/checkout >/dev/null
check "not trained → refused by name" "409" "$(./artemis post "sites/$SITE/work/next" '{"type":"PUTAWAY"}' | jq -r .status)"
N=$(./artemis post "sites/$SITE/work/next" '{"type":"SELECTION"}')
check "asks for selection: released orders are built for them, on their cart" "BUILT/2/CART-02" "$(jq -r '"\(.how)/\(.zoneOrders)/\(.equipment)"' <<<"$N")"
check "asks again: finish what you have" "RESUMED/$(jq -r .assignmentNumber <<<"$N")" "$(./artemis post "sites/$SITE/work/next" | jq -r '"\(.how)/\(.assignmentNumber)"')"
A_REQ=$(jq -r .assignmentId <<<"$N")
check "it is theirs" "M. Alvarez" "$(./artemis get assignments/$A_REQ | jq -r .operator)"
for row in $(./artemis get assignments/$A_REQ | jq -r '.tasks[] | "\(.task_id)|\(.check_digits)|\(.put_check_digits)"'); do IFS='|' read -r tid cd pd <<<"$row"; ./artemis post selection/tasks/$tid/pick "{\"checkDigits\":\"$cd\",\"putCheckDigits\":\"$pd\"}" >/dev/null; done
./artemis post assignments/$A_REQ/drop '{"dropLocation":"DROP-02"}' >/dev/null
./artemis post equipment/$CART2/checkin >/dev/null
check "wave completes when the last built assignment does" "COMPLETE" "$(./artemis get "waves/$(jq -r .wave_id <<<"$W_MAN")" | jq -r .status)"
as j.okafor@pit1.local change-me
N=$(./artemis post "sites/$SITE/work/next")
check "a putaway operator gets the most urgent putaway" "ASSIGNED/PUTAWAY" "$(jq -r '"\(.how)/\(.type)"' <<<"$N")"
check "selection isn't offered to them" "409" "$(./artemis post "sites/$SITE/work/next" '{"type":"SELECTION"}' | jq -r .status)"
check "a supervisor can take it back off them" "OPEN" "$(as supervisor@pit1.local change-me; ./artemis post "assignments/$(jq -r .assignmentId <<<"$N")/assign" '{}' >/dev/null; ./artemis get "assignments/$(jq -r .assignmentId <<<"$N")" | jq -r .status)"
as supervisor@pit1.local change-me

echo "== ui (supervisor, signed in through the form)"; as supervisor@pit1.local change-me
JAR=$(mktemp); UI=$(mktemp); trap 'rm -f "$JAR" "$UI"' EXIT
csrf() { grep -o 'name="_csrf" value="[^"]*"' "$UI" | head -1 | sed 's/.*value="//;s/"$//'; }
signin() { # email password
  rm -f "$JAR"; curl -s -c "$JAR" -o "$UI" "$ARTEMIS_URL/login"
  curl -s -o /dev/null -b "$JAR" -c "$JAR" --data-urlencode "username=$1" --data-urlencode "password=$2" --data-urlencode "_csrf=$(csrf)" "$ARTEMIS_URL/login"; }
ui() { curl -s -o "$UI" -w "%{http_code}" -b "$JAR" -c "$JAR" "$ARTEMIS_URL$1"; }
goes() { curl -s -o /dev/null -w "%{redirect_url}" -b "$JAR" "$ARTEMIS_URL$1" | sed "s|^$ARTEMIS_URL||"; }
post() { # path field=value…  (expects a page with a form to be the last one fetched)
  local path="$1"; shift; local args=(--data-urlencode "_csrf=$(csrf)"); for f in "$@"; do args+=(--data-urlencode "$f"); done
  curl -s -o /dev/null -w "%{http_code}" -b "$JAR" -c "$JAR" "${args[@]}" "$ARTEMIS_URL$path"; }
page() { # label path marker
  local code; code=$(ui "$2"); if [ "$code" = "200" ] && grep -q -- "$3" "$UI"; then echo "  ok   page $1"; pass=$((pass+1)); else echo "  FAIL page $1: http $code, marker '$3' $(grep -q -- "$3" "$UI" && echo found || echo missing)"; fail=$((fail+1)); fi; }
check "login page is public" "200" "$(curl -s -o /dev/null -w '%{http_code}' "$ARTEMIS_URL/login")"
check "unauthenticated browser redirects to login" "302" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Accept: text/html' "$ARTEMIS_URL/sites/$SITE/board")"
check "pages don't take Basic credentials (a cached API login can't override who signed in)" "302" "$(curl -s -o /dev/null -w '%{http_code}' -u "$ARTEMIS_USER:$ARTEMIS_PASS" "$ARTEMIS_URL/sites/$SITE/board")"
signin "$ARTEMIS_USER" "$ARTEMIS_PASS"
page board      "/sites/$SITE/board"      "Replenishment"
check "the bar says who signed in" "true" "$(grep -q 'supervisor@pit1.local\|Supervisor' "$UI" && echo true)"
check "no stray markup on the board" "0" "$(grep -c '&lt;title\|&lt;/' "$UI" || true)"
check "no manual replenishment button" "0" "$(grep -ci 'replen/run\|Run scan' "$UI" || true)"
check "forms carry CSRF tokens" "true" "$(grep -c '_csrf' "$UI" | awk '{print ($1>0)?"true":"false"}')"
check "board offers dispatch with operator load" "true" "$(grep -q 'J. Okafor · ' "$UI" && grep -q 'Dispatch' "$UI" && echo true)"
page racking    "/sites/$SITE/racking"    "Zone D · Aisle A"
check "rack cells link to the slot and the item" "true" "$(grep -q 'href="/slots/D-A-1-01-A"' "$UI" && grep -q 'href="/items/DRY-' "$UI" && echo true)"
page slot       "/slots/D-A-1-01-A"       "Pick face"
page pallets    "/sites/$SITE/pallets"    "PLT-0001"
page pallet     "/pallets/PLT-0001"       "Where it"
page "pallet (split child)" "/pallets/PLT-0001.1" "PLT-0001"
page lots       "/sites/$SITE/lots"       "L24091"
page lot        "/lots/CHL-2001?lot=L24091" "PLT-"
page receiving  "/sites/$SITE/receiving"  "New manifest"
page manifest   "/receiving/$MAN"         "Pallets received"
page orders     "/sites/$SITE/orders"     "ORD-1001"
page "orders by customer" "/sites/$SITE/orders?customer=GIANT-EAGLE-012" "ORD-1001"
page order      "/orders/ORD-1001"        "Zone orders"
page waves      "/sites/$SITE/waves"      "Plan a wave"
page wave       "/waves/$(jq -r .wave_id <<<"$W_CHL")" "Zone orders"
page assignment "/assignments/$A_CHL"     "CART-01"
page replen-asg "/assignments/$RT"        "PLT-0001"
page shipments  "/sites/$SITE/shipments"  "Build a shipment"
page shipment   "/shipments/$(jq -r .shipment_id <<<"$SHIP")" "Packing list"
page dashboard  "/sites/$SITE/dashboard"  "Pick-face velocity"
page alerts     "/sites/$SITE/alerts"     "ROTATION BYPASS"
page notifications "/notifications"       "Notifications"
page items      "/items"                  "CHL-2001"
page item       "/items/CHL-2002"         "Units of measure"
page customers  "/customers"              "GIANT-EAGLE-012"
page customer   "/customers/GIANT-EAGLE-012" "ORD-1001"
page operators  "/operators"              "Trained for"
page operator   "/operators/$(./artemis get users | jq -r '.[] | select(.email=="m.alvarez@pit1.local") | .user_id')" "Labor"
page equipment  "/sites/$SITE/equipment"  "CART-01"
page unit       "/equipment/CART-01"      "Operator uses"
page "unit carries the pick"      "/equipment/CART-01" "$PKU"
page "pick unit knows its unit"    "/pallets/$PKU"      "riding on"
check "drop the hand-built load" "1" "$(./artemis post assignments/$A_MAN/drop '{"dropLocation":"DROP-01"}' | jq -r .dropped)"
page "…then sits at the drop"      "/pallets/$PKU"      "DROP-01"
page "drop location lists it"      "/slots/DROP-01"     "$PKU"
page "source pallet lists its picks" "/pallets/$(./artemis get assignments/$A_MAN | jq -r '.tasks[0].lpn')" "Picked out of it"
check "the unit is empty again" "0" "$(ui /equipment/CART-01 >/dev/null; grep -c "$PKU" "$UI" || true)"
SH2=$(./artemis post "sites/$SITE/shipments" '{"customer":"GIANT-EAGLE-012","orderNumbers":["ORD-2001"],"carrier":"Estes","dock":"DOCK-S1"}'); ./artemis post "shipments/$(jq -r .shipment_id <<<"$SH2")/ship" >/dev/null
page "shipped: it left the building" "/pallets/$PKU"    "left the building"
page org        "/org"                    "FREEZER"
page policy     "/org/$SITE/policy"       "Effective (after cascade)"
echo "== universal search"
check "a pallet LPN goes straight to the pallet"  "/pallets/PLT-0001"   "$(goes '/search?q=plt-0001')"
check "a slot code goes straight to the slot"     "/slots/D-A-1-01-A"   "$(goes '/search?q=D-A-1-01-A')"
check "an order number goes straight to the order" "/orders/ORD-1001"   "$(goes '/search?q=ord-1001')"
check "a SKU goes straight to the item"           "/items/CHL-2001"     "$(goes '/search?q=CHL-2001')"
page "search: a person"   "/search?q=okafor&stay=1" "Operator"
page "search: many hits"  "/search?q=ORD-10"  "ORD-1004"
page "search: nothing"    "/search?q=zzzz"    "Nothing"
check "the old LPN lookup address still works" "/search?q=PLT-0001" "$(goes '/lpn?q=PLT-0001')"
check "an unknown pallet is a 404 page, not a stack trace" "404" "$(ui /pallets/NOPE)"
echo "== receiving through the screen"
ui "/sites/$SITE/receiving" >/dev/null
check "create a manifest" "302" "$(post "/ui/sites/$SITE/manifests" manifestNumber=MAN-UI supplier=Smoke "lines=DRY-1001 20")"
M2=$(./artemis get "sites/$SITE/receiving" | jq -r '.[] | select(.manifest_number=="MAN-UI") | .manifest_id')
page "new manifest" "/receiving/$M2" "MAN-UI"
check "arrive" "302" "$(post "/ui/receiving/$M2/arrive")"; ui "/receiving/$M2" >/dev/null
check "close refused while nothing is received" "ARRIVED" "$(post "/ui/receiving/$M2/close" >/dev/null; ./artemis get receiving/$M2 | jq -r .status)"
ui "/receiving/$M2" >/dev/null
check "receive a pallet with no LPN typed" "302" "$(post "/ui/receiving/$M2/pallet" lineNumber=1 qty=20)"
check "it has a system LPN, a dock and a putaway" "true/DOCK-R1/true" "$(./artemis get receiving/$M2 | jq -r '.pallets[0] | "\(.lpn|test("^PLT-1"))/\(.location)/\(.directed_to != null)"')"
check "fully received" "RECEIVED" "$(./artemis get receiving/$M2 | jq -r .status)"
page "manifest shows where it's going" "/receiving/$M2" "Directed to"
M3=$(./artemis post "sites/$SITE/receiving" '{"manifestNumber":"MAN-GONE","supplier":"Smoke","lines":[{"sku":"DRY-1001","expectedQty":5}]}' | jq -r .manifestId)
check "an untouched manifest can be cancelled" "CANCELLED" "$(./artemis post receiving/$M3/cancel | jq -r .status)"
echo "== equipment check-out"
EQ=$(./artemis get "sites/$SITE/equipment" | jq -r '.[] | select(.code=="CART-02") | .equipment_id'); ui "/equipment/CART-02" >/dev/null
check "check out to an operator" "302" "$(post "/ui/equipment/$EQ/checkout" "userId=$(./artemis get users | jq -r '.[] | select(.email=="m.alvarez@pit1.local") | .user_id')" back=/equipment/CART-02)"
page "unit shows who has it" "/equipment/CART-02" "M. Alvarez"
check "check in" "302" "$(post "/ui/equipment/$EQ/checkin" back=/equipment/CART-02)"
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
echo "== sysadmin in the browser"
signin "$ARTEMIS_USER" "$ARTEMIS_PASS"
page "tenant picker instead of an error" "/" "Work in SQE"
check "a tenant page without a tenant goes back to the picker" "/" "$(goes "/sites/$SITE/orders")"
SQE=$(./artemis corporations | jq -r '.[] | select(.code=="SQE") | .org_node_id')
check "enter a tenant" "302" "$(post /ui/platform/enter "corporationId=$SQE")"
page "orders as sysadmin inside SQE" "/sites/$SITE/orders" "ORD-1001"
printf 'ACME\n' | ./artemis wipe $ACME all >/dev/null
check "wipe removes the tenant" "SQE" "$(./artemis corporations | jq -r '[.[].code]|join(",")')"

check "a tenant with a full journal wipes clean (operations only)" "0/10" "$(printf 'SQE\n' | ./artemis wipe $SQE operations >/dev/null; as tom@squiresenterprisesllc.com change-me; echo "$(./artemis get "sites/$SITE/orders" | jq -r length)/$(./artemis get items | jq -r length)")"

echo; echo "passed=$pass failed=$fail"; [ "$fail" -eq 0 ]
