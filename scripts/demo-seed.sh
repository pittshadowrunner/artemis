#!/usr/bin/env bash
# ============================================================
# Artemis WMS — Proof of Concept demo seed
#
# Fills a running instance with a realistic Pittsburgh DC:
# org hierarchy, item master, racking, customers, opening
# balances, inbound in three states, allocation (including a
# deliberate rotation bypass), waves, picking, a completed
# shipment, and replenishment pressure.
#
# Usage:
#   BASE=http://192.168.1.5:8085 CORP_ID=<uuid> ./demo-seed.sh
#
# Find CORP_ID on the Docker host:
#   docker exec -it <db-container> psql -U wms -d wms \
#     -c "SELECT org_node_id, code FROM org_node WHERE level='CORPORATION';"
#
# Requires: bash, curl, jq. Run once against a fresh stack.
# ============================================================
set -u

BASE="${BASE:-http://localhost:8080}"
WMS_USER="${WMS_USER:-admin@artemis.local}"
WMS_PASS="${WMS_PASS:-admin}"
CORP_ID="${CORP_ID:-}"
API="$BASE/api/v1"

command -v jq >/dev/null || { echo "jq is required"; exit 1; }

if [ -z "$CORP_ID" ]; then
  echo "CORP_ID is not set. On the Docker host run:"
  echo "  docker exec -it \$(docker ps --format '{{.Names}}' | grep db) \\"
  echo "    psql -U wms -d wms -c \"SELECT org_node_id, code FROM org_node WHERE level='CORPORATION';\""
  exit 1
fi

api() {  # api METHOD PATH [JSON]
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -u "$WMS_USER:$WMS_PASS" -X "$method" "$API$path" \
         -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -u "$WMS_USER:$WMS_PASS" -X "$method" "$API$path"
  fi
}
say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
# portable relative date (GNU then BSD)
d() { date -d "+$1 days" +%F 2>/dev/null || date -v+"$1"d +%F; }

# ---------------------------------------------------------------
say "Connectivity"
if ! api GET /notifications/count | grep -q unread; then
  echo "Cannot reach $API as $WMS_USER — check the URL and credentials."; exit 1
fi
note "$BASE reachable, authenticated as $WMS_USER"

# ---------------------------------------------------------------
say "1. Organization — Corporation > Region > Site > Areas"
REGION=$(api POST /org "{\"level\":\"DISTRICT_REGION\",\"parentId\":\"$CORP_ID\",\"code\":\"NE\",\"name\":\"Northeast Region\"}" | jq -r .orgNodeId)
SITE=$(api POST /org "{\"level\":\"SITE_LOCATION\",\"parentId\":\"$REGION\",\"code\":\"PIT1\",\"name\":\"Pittsburgh DC\",\"addressLine1\":\"1200 Terminal Way\",\"city\":\"Pittsburgh\",\"stateProvince\":\"PA\",\"postalCode\":\"15219\",\"country\":\"US\"}" | jq -r .orgNodeId)
COOLER=$(api POST /org "{\"level\":\"AREA\",\"parentId\":\"$SITE\",\"code\":\"COOLER\",\"name\":\"Cooler\"}"  | jq -r .orgNodeId)
FREEZER=$(api POST /org "{\"level\":\"AREA\",\"parentId\":\"$SITE\",\"code\":\"FREEZER\",\"name\":\"Freezer\"}" | jq -r .orgNodeId)
DRY=$(api POST /org "{\"level\":\"AREA\",\"parentId\":\"$SITE\",\"code\":\"DRY\",\"name\":\"Dry Goods\"}" | jq -r .orgNodeId)
note "site=$SITE  cooler=$COOLER  freezer=$FREEZER  dry=$DRY"
[ "$SITE" = "null" ] && { echo "Org creation failed — is CORP_ID correct?"; exit 1; }

# ---------------------------------------------------------------
say "2. Item master — 12 SKUs across three temperature zones"
api POST /items/bulk '{"items":[
 {"sku":"CHKN-40","description":"Chicken Breast 40#","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":90,"minShelfLifeReceiptDays":21,"minShelfLifeShipDays":10,"dateLabel":"USE_BY","tempZone":"REFRIGERATED","velocityClass":"A","casePackQty":4,"palletTi":8,"palletHi":5,"countryOfOrigin":"US"},
 {"sku":"BEEF-80","description":"Ground Beef 80/20 10#","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":45,"dateLabel":"USE_BY","tempZone":"REFRIGERATED","velocityClass":"A","catchWeight":true,"nominalWeightKg":4.54},
 {"sku":"CHED-BLK","description":"Cheddar Block CW","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":120,"dateLabel":"SELL_BY","tempZone":"REFRIGERATED","velocityClass":"B","catchWeight":true,"nominalWeightKg":9.07,"allergens":"MILK","certifications":"KOSHER"},
 {"sku":"MILK-2GAL","description":"Milk 2% Gallon","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":18,"dateLabel":"SELL_BY","tempZone":"REFRIGERATED","velocityClass":"A","allergens":"MILK"},
 {"sku":"MIX-SPRING","description":"Spring Mix 4#","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":14,"dateLabel":"USE_BY","tempZone":"REFRIGERATED","velocityClass":"B"},
 {"sku":"YOG-24","description":"Yogurt Cups 24ct","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":45,"dateLabel":"BEST_BY","tempZone":"REFRIGERATED","velocityClass":"C","allergens":"MILK"},
 {"sku":"FRIES-65","description":"Fries 6/5#","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":365,"dateLabel":"BEST_BY","tempZone":"FROZEN","velocityClass":"A","casePackQty":6},
 {"sku":"SHRMP-2","description":"Shrimp 21-25ct 2#","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":540,"minShelfLifeReceiptDays":21,"dateLabel":"BEST_BY","tempZone":"FROZEN","velocityClass":"B","allergens":"SHELLFISH"},
 {"sku":"ICE-VAN","description":"Vanilla Ice Cream 3gal","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":270,"dateLabel":"BEST_BY","tempZone":"FROZEN","velocityClass":"C","allergens":"MILK"},
 {"sku":"OIL-35","description":"Fry Oil 35#","uom":"CS","tempZone":"AMBIENT","velocityClass":"A","casePackQty":1},
 {"sku":"NAP-12","description":"Napkins 12ct","uom":"CS","tempZone":"AMBIENT","velocityClass":"C"},
 {"sku":"FLOUR-50","description":"AP Flour 50#","uom":"CS","lotTracked":true,"tempZone":"AMBIENT","velocityClass":"B","allergens":"GLUTEN"}]}' \
 | jq -c '{createdCount,errorCount,errors}'

# ---------------------------------------------------------------
say "3. Racking — serpentine pick sequence per zone"
api POST /locations/generate "{\"siteId\":\"$SITE\",\"areaId\":\"$COOLER\",\"locType\":\"STORAGE\",\"aisles\":[\"C\",\"D\"],\"bays\":[\"01\",\"02\",\"03\",\"04\"],\"tiers\":[\"01\",\"02\"],\"slots\":[\"A\",\"B\"],\"tempZone\":\"REFRIGERATED\",\"rackType\":\"SELECTIVE\",\"pickSequenceStart\":1000,\"pickSequenceStep\":10}" | jq -c '{cooler_storage:.createdCount,errorCount}'
api POST /locations/generate "{\"siteId\":\"$SITE\",\"areaId\":\"$FREEZER\",\"locType\":\"STORAGE\",\"aisles\":[\"F\"],\"bays\":[\"01\",\"02\",\"03\"],\"tiers\":[\"01\",\"02\"],\"slots\":[\"A\",\"B\"],\"tempZone\":\"FROZEN\",\"rackType\":\"DRIVE_IN\",\"pickSequenceStart\":2000,\"pickSequenceStep\":10}" | jq -c '{freezer_storage:.createdCount,errorCount}'
api POST /locations/generate "{\"siteId\":\"$SITE\",\"areaId\":\"$DRY\",\"locType\":\"STORAGE\",\"aisles\":[\"A\",\"B\"],\"bays\":[\"01\",\"02\",\"03\"],\"tiers\":[\"01\"],\"slots\":[\"A\"],\"tempZone\":\"AMBIENT\",\"rackType\":\"SELECTIVE\",\"pickSequenceStart\":3000,\"pickSequenceStep\":10}" | jq -c '{dry_storage:.createdCount,errorCount}'

say "3b. Docks, drops, staging, and pick faces with replen triggers"
api POST /locations/bulk "{\"siteId\":\"$SITE\",\"skipExisting\":true,\"locations\":[
 {\"code\":\"RCV-01\",\"locType\":\"RECEIVING_DOCK\",\"pickSequence\":10},
 {\"code\":\"RCV-02\",\"locType\":\"RECEIVING_DOCK\",\"pickSequence\":20},
 {\"code\":\"SHP-01\",\"locType\":\"SHIPPING_DOCK\",\"pickSequence\":30},
 {\"code\":\"DROP-07\",\"locType\":\"DROP\",\"pickSequence\":40},
 {\"code\":\"DROP-08\",\"locType\":\"DROP\",\"pickSequence\":50},
 {\"code\":\"STG-01\",\"locType\":\"STAGING\",\"pickSequence\":60},
 {\"code\":\"C-PF-01\",\"locType\":\"PICK_FACE\",\"tempZone\":\"REFRIGERATED\",\"areaId\":\"$COOLER\",\"pickSequence\":900,\"velocityZone\":\"A\",\"goldenZone\":true,\"rackType\":\"CARTON_FLOW\",\"replenSku\":\"CHKN-40\",\"replenMinQty\":4,\"replenMaxQty\":24,\"replenTriggerQty\":8},
 {\"code\":\"C-PF-02\",\"locType\":\"PICK_FACE\",\"tempZone\":\"REFRIGERATED\",\"areaId\":\"$COOLER\",\"pickSequence\":910,\"velocityZone\":\"A\",\"goldenZone\":true,\"rackType\":\"CARTON_FLOW\",\"replenSku\":\"MILK-2GAL\",\"replenMinQty\":6,\"replenMaxQty\":36,\"replenTriggerQty\":12},
 {\"code\":\"C-PF-03\",\"locType\":\"PICK_FACE\",\"tempZone\":\"REFRIGERATED\",\"areaId\":\"$COOLER\",\"pickSequence\":920,\"velocityZone\":\"B\",\"replenSku\":\"MIX-SPRING\",\"replenMinQty\":4,\"replenMaxQty\":20,\"replenTriggerQty\":8},
 {\"code\":\"F-PF-01\",\"locType\":\"PICK_FACE\",\"tempZone\":\"FROZEN\",\"areaId\":\"$FREEZER\",\"pickSequence\":1900,\"velocityZone\":\"A\",\"goldenZone\":true,\"replenSku\":\"FRIES-65\",\"replenMinQty\":6,\"replenMaxQty\":30,\"replenTriggerQty\":10},
 {\"code\":\"A-PF-01\",\"locType\":\"PICK_FACE\",\"tempZone\":\"AMBIENT\",\"areaId\":\"$DRY\",\"pickSequence\":2900,\"velocityZone\":\"A\",\"replenSku\":\"OIL-35\",\"replenMinQty\":4,\"replenMaxQty\":24,\"replenTriggerQty\":8},
 {\"code\":\"A-PF-02\",\"locType\":\"PICK_FACE\",\"tempZone\":\"AMBIENT\",\"areaId\":\"$DRY\",\"pickSequence\":2910,\"velocityZone\":\"C\",\"replenSku\":\"NAP-12\",\"replenMinQty\":2,\"replenMaxQty\":12,\"replenTriggerQty\":4}]}" \
 | jq -c '{createdCount,errorCount,errors}'

# ---------------------------------------------------------------
say "4. Customers — routes, stop sequences, freshness rules"
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"GROC1\",\"name\":\"Keystone Grocery\",\"city\":\"Pittsburgh\",\"stateProvince\":\"PA\",\"routeCode\":\"RT7\",\"stopSequence\":1,\"minShelfLifeDays\":30,\"palletBuildPref\":\"STORE_FRIENDLY\",\"preferredCarrier\":\"Estes\"}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"DINER2\",\"name\":\"Steel City Diners\",\"city\":\"Bethel Park\",\"stateProvince\":\"PA\",\"routeCode\":\"RT7\",\"stopSequence\":2,\"minShelfLifeDays\":7}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"SCHOOL3\",\"name\":\"Allegheny Schools\",\"city\":\"Monroeville\",\"stateProvince\":\"PA\",\"routeCode\":\"RT7\",\"stopSequence\":3,\"minShelfLifeDays\":14,\"requiresAsn\":true}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"CAFE4\",\"name\":\"Riverfront Cafes\",\"city\":\"Pittsburgh\",\"stateProvince\":\"PA\",\"routeCode\":\"RT9\",\"stopSequence\":1,\"minShelfLifeDays\":10}" >/dev/null
note "GROC1 (30-day rule), DINER2, SCHOOL3, CAFE4 — RT7 stops 1-3, RT9 stop 1"

# ---------------------------------------------------------------
say "5. Opening balances — staggered lots to drive FEFO and expiry risk"
D3=$(d 3); D5=$(d 5); D9=$(d 9); D11=$(d 11); D21=$(d 21); D40=$(d 40)
D60=$(d 60); D95=$(d 95); D200=$(d 200); D330=$(d 330)
api POST /inventory/upload "{\"siteId\":\"$SITE\",\"records\":[
 {\"lpn\":\"00042301\",\"sku\":\"CHKN-40\",\"location\":\"C-01-01-A\",\"qty\":24,\"lotNumber\":\"26114\",\"expirationDate\":\"$D9\"},
 {\"lpn\":\"00042302\",\"sku\":\"CHKN-40\",\"location\":\"C-01-01-B\",\"qty\":40,\"lotNumber\":\"26120\",\"expirationDate\":\"$D60\"},
 {\"lpn\":\"00042303\",\"sku\":\"CHKN-40\",\"location\":\"C-01-02-A\",\"qty\":40,\"lotNumber\":\"26131\",\"expirationDate\":\"$D95\"},
 {\"lpn\":\"00042304\",\"sku\":\"CHKN-40\",\"location\":\"C-PF-01\",\"qty\":3,\"lotNumber\":\"26120\",\"expirationDate\":\"$D60\"},
 {\"lpn\":\"00042310\",\"sku\":\"BEEF-80\",\"location\":\"C-02-01-A\",\"qty\":30,\"lotNumber\":\"26118\",\"expirationDate\":\"$D40\",\"actualWeightKg\":136.2},
 {\"lpn\":\"00042311\",\"sku\":\"CHED-BLK\",\"location\":\"C-02-01-B\",\"qty\":18,\"lotNumber\":\"26098\",\"expirationDate\":\"$D95\",\"actualWeightKg\":163.3},
 {\"lpn\":\"00042320\",\"sku\":\"MILK-2GAL\",\"location\":\"C-03-01-A\",\"qty\":48,\"lotNumber\":\"26121\",\"expirationDate\":\"$D11\"},
 {\"lpn\":\"00042321\",\"sku\":\"MILK-2GAL\",\"location\":\"C-PF-02\",\"qty\":9,\"lotNumber\":\"26121\",\"expirationDate\":\"$D11\"},
 {\"lpn\":\"00042330\",\"sku\":\"MIX-SPRING\",\"location\":\"C-03-02-A\",\"qty\":20,\"lotNumber\":\"26119\",\"expirationDate\":\"$D5\"},
 {\"lpn\":\"00042331\",\"sku\":\"MIX-SPRING\",\"location\":\"C-PF-03\",\"qty\":6,\"lotNumber\":\"26119\",\"expirationDate\":\"$D5\"},
 {\"lpn\":\"00042340\",\"sku\":\"YOG-24\",\"location\":\"C-04-01-A\",\"qty\":36,\"lotNumber\":\"26102\",\"expirationDate\":\"$D21\"},
 {\"lpn\":\"00042350\",\"sku\":\"FRIES-65\",\"location\":\"F-01-01-A\",\"qty\":60,\"lotNumber\":\"25330\",\"expirationDate\":\"$D330\"},
 {\"lpn\":\"00042351\",\"sku\":\"FRIES-65\",\"location\":\"F-PF-01\",\"qty\":4,\"lotNumber\":\"25330\",\"expirationDate\":\"$D330\"},
 {\"lpn\":\"00042352\",\"sku\":\"SHRMP-2\",\"location\":\"F-01-02-A\",\"qty\":24,\"lotNumber\":\"25288\",\"expirationDate\":\"$D330\"},
 {\"lpn\":\"00042353\",\"sku\":\"ICE-VAN\",\"location\":\"F-02-01-A\",\"qty\":18,\"lotNumber\":\"26011\",\"expirationDate\":\"$D200\"},
 {\"lpn\":\"00042360\",\"sku\":\"OIL-35\",\"location\":\"A-01-01-A\",\"qty\":50},
 {\"lpn\":\"00042361\",\"sku\":\"OIL-35\",\"location\":\"A-PF-01\",\"qty\":5},
 {\"lpn\":\"00042362\",\"sku\":\"NAP-12\",\"location\":\"A-02-01-A\",\"qty\":80},
 {\"lpn\":\"00042363\",\"sku\":\"NAP-12\",\"location\":\"A-PF-02\",\"qty\":1},
 {\"lpn\":\"00042364\",\"sku\":\"FLOUR-50\",\"location\":\"A-03-01-A\",\"qty\":40,\"lotNumber\":\"26055\"},
 {\"lpn\":\"REJECT-ME\",\"sku\":\"FRIES-65\",\"location\":\"A-01-01-A\",\"qty\":5,\"lotNumber\":\"X\",\"expirationDate\":\"$D330\"}]}" \
 | jq -c '{createdCount,errorCount,errors}'
note "last row is a deliberate frozen-into-ambient rejection — proves temp enforcement"

# ---------------------------------------------------------------
say "6. Inbound — three manifests in three states"
note "MAN-4471: received and put away"
M1=$(api POST /receiving/manifests "{\"siteId\":\"$SITE\",\"manifestNumber\":\"MAN-4471\",\"carrier\":\"Sysco\",\"trailerNumber\":\"TRL-8842\",\"expectedDate\":\"$(d 0)\",\"lines\":[{\"sku\":\"CHKN-40\",\"expectedQty\":40},{\"sku\":\"FRIES-65\",\"expectedQty\":30}]}" | jq -r .manifestId)
api POST "/receiving/manifests/$M1/arrive" >/dev/null
for spec in "1|00042401|40|26140|$D95" "2|00042402|30|25341|$D330"; do
  IFS='|' read -r LN LPN QTY LOT EXP <<< "$spec"
  R=$(api POST "/receiving/manifests/$M1/receipts" "{\"lineNumber\":$LN,\"lpn\":\"$LPN\",\"qty\":$QTY,\"lot\":\"$LOT\",\"expirationDate\":\"$EXP\"}")
  TID=$(echo "$R" | jq -r '.putawayTask.taskId'); CD=$(echo "$R" | jq -r '.putawayTask.checkDigits')
  echo "$R" | jq -c '{lpn, slot:.putawayTask.destination, prompt:.putawayTask.spokenPrompt}'
  api POST "/putaway/tasks/$TID/complete" "{\"checkDigits\":\"$CD\"}" | jq -c .
done
api POST "/receiving/manifests/$M1/close" >/dev/null

note "MAN-4472: partially received, still open (drives receiving-progress %)"
M2=$(api POST /receiving/manifests "{\"siteId\":\"$SITE\",\"manifestNumber\":\"MAN-4472\",\"carrier\":\"US Foods\",\"trailerNumber\":\"TRL-2210\",\"expectedDate\":\"$(d 0)\",\"lines\":[{\"sku\":\"MILK-2GAL\",\"expectedQty\":60},{\"sku\":\"YOG-24\",\"expectedQty\":40}]}" | jq -r .manifestId)
api POST "/receiving/manifests/$M2/arrive" >/dev/null
R=$(api POST "/receiving/manifests/$M2/receipts" "{\"lineNumber\":1,\"lpn\":\"00042410\",\"qty\":36,\"lot\":\"26133\",\"expirationDate\":\"$D11\"}")
TID=$(echo "$R" | jq -r '.putawayTask.taskId'); CD=$(echo "$R" | jq -r '.putawayTask.checkDigits')
api POST "/putaway/tasks/$TID/complete" "{\"checkDigits\":\"$CD\"}" >/dev/null
api POST "/receiving/manifests/$M2/rejections" '{"lineNumber":2,"qty":6,"reason":"Temperature abuse — trailer reefer failure"}' | jq -c .

note "MAN-4473: at the dock, nothing received yet"
M3=$(api POST /receiving/manifests "{\"siteId\":\"$SITE\",\"manifestNumber\":\"MAN-4473\",\"carrier\":\"PFG\",\"trailerNumber\":\"TRL-5567\",\"expectedDate\":\"$(d 1)\",\"lines\":[{\"sku\":\"SHRMP-2\",\"expectedQty\":24},{\"sku\":\"OIL-35\",\"expectedQty\":50}]}" | jq -r .manifestId)
api POST "/receiving/manifests/$M3/arrive" >/dev/null

note "short-shelf-life guard: 21-day minimum vs a 9-day case"
api POST "/receiving/manifests/$M3/receipts" "{\"lineNumber\":1,\"lpn\":\"REJECT-2\",\"qty\":1,\"lot\":\"BAD\",\"expirationDate\":\"$D9\"}" | jq -c .

# ---------------------------------------------------------------
say "7. Orders + allocation"
mkorder() { # code number dropLoc lines
  api POST /orders "{\"siteId\":\"$SITE\",\"orderNumber\":\"$2\",\"customerCode\":\"$1\",\"requestedShipDate\":\"$(d 1)\",\"dropLocation\":\"$3\",\"lines\":$4}" | jq -r .orderId
}
O1=$(mkorder GROC1   ORD-2001 DROP-07 '[{"sku":"CHKN-40","qty":8},{"sku":"FRIES-65","qty":6},{"sku":"NAP-12","qty":4}]')
O2=$(mkorder DINER2  ORD-2002 DROP-07 '[{"sku":"BEEF-80","qty":5},{"sku":"OIL-35","qty":6}]')
O3=$(mkorder SCHOOL3 ORD-2003 DROP-08 '[{"sku":"MILK-2GAL","qty":12},{"sku":"YOG-24","qty":10}]')
O4=$(mkorder CAFE4   ORD-2004 DROP-08 '[{"sku":"CHED-BLK","qty":4},{"sku":"MIX-SPRING","qty":4}]')
O5=$(mkorder GROC1   ORD-2005 DROP-07 '[{"sku":"SHRMP-2","qty":6},{"sku":"ICE-VAN","qty":4}]')
O6=$(mkorder DINER2  ORD-2006 DROP-07 '[{"sku":"FLOUR-50","qty":10},{"sku":"NAP-12","qty":200}]')
note "ORD-2001 GROC1 has a 30-day rule against a 9-day lot — expect ROTATION_BYPASS"
for O in $O1 $O2 $O3 $O4 $O5 $O6; do
  api POST "/orders/$O/allocate" | jq -c '{status, messages}'
done
note "ORD-2006 orders 200 napkins against 81 on hand — expect a short, order stays NEW"

# ---------------------------------------------------------------
say "8. Waves"
W1=$(api POST /waves "{\"siteId\":\"$SITE\",\"waveType\":\"SHIP_URGENCY\",\"waveNumber\":\"W-0803-A\",\"maxOrders\":3}" | jq -r .waveId)
echo "  ship-urgency wave: $(api GET /orders/$O1 | jq -r .status) → released next"
REL=$(api POST "/waves/$W1/release" '{"putMode":"TOTE"}')
echo "$REL" | jq -c .
ASG=""
for a in $(echo "$REL" | jq -r '.assignments[]'); do
  if api GET "/selection/assignments/$a/tasks" | jq -e --arg o "$O1" 'any(.[]; .order_id == $o)' >/dev/null; then ASG="$a"; break; fi
done
[ -z "$ASG" ] && ASG=$(echo "$REL" | jq -r '.assignments[0]')
note "picking assignment $ASG (ORD-2001); the other assignments stay open as queue depth"

W2=$(api POST /waves "{\"siteId\":\"$SITE\",\"waveType\":\"ROUTE\",\"waveNumber\":\"W-0803-RT7\",\"routeCode\":\"RT7\",\"maxOrders\":5}" | jq -r .waveId)
note "route wave W-0803-RT7 left PLANNED (loads in reverse stop sequence when released)"

say "9. Selection — every task on the released assignment, both sides verified"
TASKS=$(api GET "/selection/assignments/$ASG/tasks")
echo "$TASKS" | jq -c '.[] | {seq, sku, from:.from_code, qty, slot_digits:.check_digits, put_digits:.put_check_digits}'
echo "$TASKS" | jq -c '.[] | {task_id, check_digits, put_check_digits}' | while read -r t; do
  TID=$(echo "$t" | jq -r .task_id); CD=$(echo "$t" | jq -r .check_digits); PD=$(echo "$t" | jq -r .put_check_digits)
  api POST "/selection/tasks/$TID/pick" "{\"checkDigits\":\"$CD\",\"putConfirmation\":\"$PD\"}" | jq -c .
done

say "10. Drop and ship the first order"
api POST /selection/drops "{\"orderId\":\"$O1\"}" | jq -c .
SHP=$(api POST /shipments "{\"siteId\":\"$SITE\",\"orderIds\":[\"$O1\"],\"carrier\":\"Estes\",\"trailerNumber\":\"TRL-9001\"}" | jq -r .shipmentId)
api POST "/shipments/$SHP/packing-list" | jq -c .
api POST "/shipments/$SHP/ship" | jq -c .
api GET "/orders/$O1" | jq -c '{order_number, status, lines:[.lines[]|{sku,ordered_qty,picked_qty}]}'

# ---------------------------------------------------------------
say "11. Replenishment scan"
api POST "/replenishment/scan?siteId=$SITE" | jq -c .
api GET "/replenishment/tasks?siteId=$SITE" | jq -c '.[] | {sku, from:.from_code, to:.to_code, qty, pull_digits:.check_digits, face_digits:.put_check_digits}'
note "one replen left OPEN on purpose so the queue isn't empty in the UI"

# ---------------------------------------------------------------
say "12. What the dashboards now show"
echo "-- alerts:";            api GET "/alerts?siteId=$SITE"                  | jq -c '.[] | {alert_type, severity, message}'
echo "-- bell:";              api GET /notifications/count                    | jq -c .
echo "-- receiving progress:";api GET "/metrics/receiving-progress?siteId=$SITE" | jq -c '.[] | {manifest_number, carrier, expected_qty, received_qty, pct_complete}'
echo "-- shipping pipeline:"; api GET "/metrics/shipping-progress?siteId=$SITE"  | jq -c '.[] | {status, orders}'
echo "-- wave progress:";     api GET "/metrics/wave-progress?siteId=$SITE"      | jq -c '.[] | {wave_number, wave_type, status, total_tasks, done_tasks}'
echo "-- replen pressure:";   api GET "/metrics/replen-pressure?siteId=$SITE"    | jq -c '.[] | {location_code, sku, on_hand, replen_trigger_qty}'
echo "-- pick-face velocity:";api GET "/metrics/pick-face-velocity?siteId=$SITE" | jq -c '.[] | {location_code, sku, lines, visits, cases}'
echo "-- expiry risk (top 6):"; api GET "/metrics/expiry-risk?siteId=$SITE"      | jq -c '.[] | {sku, lot_number, days_remaining, qty}' | head -6

cat <<SUMMARY

============================================================
 DEMO SEED COMPLETE
============================================================
 Site ID (most endpoints need it):
   $SITE
 Areas: cooler=$COOLER freezer=$FREEZER dry=$DRY

 Swagger:  $BASE/api-docs/ui
 Login:    $WMS_USER / $WMS_PASS

 Live state:
   - 3 manifests: one closed, one partial with a rejection, one at the dock
   - 6 orders: allocated, one bypassed rotation, one short on napkins
   - 1 wave released (6 of 11 tasks picked), 1 route wave planned
   - ORD-2001 shipped with packing list PL-10001 (SHIPPED orders are
     excluded from the shipping-progress view by design)
   - replenishment queue with a CRITICAL alert on the bell
============================================================
SUMMARY
