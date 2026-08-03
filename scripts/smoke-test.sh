#!/bin/bash
# Artemis WMS — end-to-end smoke test (beta scenario, M1→M5)
set -u
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export DB_URL="jdbc:postgresql://localhost:5432/wms_app"
export PGPASSWORD=wms
B="http://localhost:8080/api/v1"
AUTH="-u admin@artemis.local:admin"
PSQL="psql -h localhost -U wms -d wms_app -tAc"

service postgresql start >/dev/null 2>&1; sleep 2
# fresh app database every run
su postgres -c "dropdb --if-exists wms_app && createdb -O wms wms_app" >/dev/null 2>&1

java -jar /home/claude/artemis/app/target/artemis-wms-0.5.0.jar --spring.profiles.active=local-auth > /tmp/app.log 2>&1 &
APP=$!
for i in $(seq 1 60); do
  curl -s -o /dev/null $AUTH $B/notifications/count && break
  sleep 2
done
echo "== app up (pid $APP)"

step() { echo; echo "== $1"; }

step "M1: org hierarchy (corp bootstrapped by local-auth profile)"
CORP=$($PSQL "SELECT org_node_id FROM org_node WHERE level='CORPORATION' AND code='DEV'")
REGION=$(curl -s $AUTH -X POST $B/org -H 'Content-Type: application/json' -d "{\"level\":\"DISTRICT_REGION\",\"parentId\":\"$CORP\",\"code\":\"NE\",\"name\":\"Northeast\"}" | jq -r .orgNodeId)
SITE=$(curl -s $AUTH -X POST $B/org -H 'Content-Type: application/json' -d "{\"level\":\"SITE_LOCATION\",\"parentId\":\"$REGION\",\"code\":\"PIT1\",\"name\":\"Pittsburgh DC\",\"city\":\"Pittsburgh\",\"stateProvince\":\"PA\"}" | jq -r .orgNodeId)
COOLER=$(curl -s $AUTH -X POST $B/org -H 'Content-Type: application/json' -d "{\"level\":\"AREA\",\"parentId\":\"$SITE\",\"code\":\"COOLER\",\"name\":\"Cooler\"}" | jq -r .orgNodeId)
echo "corp=$CORP region=$REGION site=$SITE cooler=$COOLER"
echo "level-nesting guard (Area under Region, should fail):"
curl -s $AUTH -X POST $B/org -H 'Content-Type: application/json' -d "{\"level\":\"AREA\",\"parentId\":\"$REGION\",\"code\":\"BAD\",\"name\":\"Bad\"}" | jq -c .

step "M2: items (incl. a bad row to prove partial success)"
curl -s $AUTH -X POST $B/items/bulk -H 'Content-Type: application/json' -d '{"items":[
 {"sku":"CHKN-40","description":"Chicken Breast 40#","uom":"CS","lotTracked":true,"expiryTracked":true,"shelfLifeDays":90,"tempZone":"REFRIGERATED","velocityClass":"A"},
 {"sku":"NAP-12","description":"Napkins 12ct","uom":"CS","tempZone":"AMBIENT","velocityClass":"C"},
 {"sku":"BAD-1","description":"Expiry with no shelf life","expiryTracked":true}]}' | jq -c '{createdCount,errorCount,errors}'

step "M2: locations — serpentine generation + dock/drop/pick-face with replen triggers"
curl -s $AUTH -X POST $B/locations/generate -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"locType\":\"STORAGE\",\"aisles\":[\"C\"],\"bays\":[\"01\",\"02\"],\"tiers\":[\"01\"],\"slots\":[\"A\",\"B\"],\"tempZone\":\"REFRIGERATED\",\"rackType\":\"SELECTIVE\",\"pickSequenceStart\":1000,\"areaId\":\"$COOLER\"}" | jq -c '{createdCount,errorCount}'
curl -s $AUTH -X POST $B/locations/generate -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"locType\":\"STORAGE\",\"aisles\":[\"A\"],\"bays\":[\"01\"],\"tiers\":[\"01\"],\"slots\":[\"A\"],\"tempZone\":\"AMBIENT\",\"rackType\":\"SELECTIVE\",\"pickSequenceStart\":2000}" | jq -c '{createdCount,errorCount}'
curl -s $AUTH -X POST $B/locations/bulk -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"locations\":[
 {\"code\":\"RCV-01\",\"locType\":\"RECEIVING_DOCK\"},
 {\"code\":\"DROP-07\",\"locType\":\"DROP\"},
 {\"code\":\"C-PF-01\",\"locType\":\"PICK_FACE\",\"tempZone\":\"REFRIGERATED\",\"pickSequence\":900,\"areaId\":\"$COOLER\",\"replenSku\":\"CHKN-40\",\"replenMinQty\":4,\"replenMaxQty\":24,\"replenTriggerQty\":8}]}" | jq -c '{createdCount,errorCount,errors}'

step "M2: opening balances (two CHKN lots: 10-day and 60-day; low pick-face stock; temp-mismatch row must fail)"
TEN=$(date -d "+10 days" +%F); SIXTY=$(date -d "+60 days" +%F)
curl -s $AUTH -X POST $B/inventory/upload -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"records\":[
 {\"lpn\":\"LPN-0001\",\"sku\":\"CHKN-40\",\"location\":\"C-01-01-A\",\"qty\":20,\"lotNumber\":\"LOT-OLD\",\"expirationDate\":\"$TEN\"},
 {\"lpn\":\"LPN-0002\",\"sku\":\"CHKN-40\",\"location\":\"C-01-01-B\",\"qty\":20,\"lotNumber\":\"LOT-NEW\",\"expirationDate\":\"$SIXTY\"},
 {\"lpn\":\"LPN-0003\",\"sku\":\"CHKN-40\",\"location\":\"C-PF-01\",\"qty\":3,\"lotNumber\":\"LOT-NEW\",\"expirationDate\":\"$SIXTY\"},
 {\"lpn\":\"LPN-0004\",\"sku\":\"NAP-12\",\"location\":\"A-01-01-A\",\"qty\":50},
 {\"lpn\":\"LPN-BAD\",\"sku\":\"CHKN-40\",\"location\":\"A-01-01-A\",\"qty\":5,\"lotNumber\":\"X\",\"expirationDate\":\"$SIXTY\"}]}" | jq -c '{createdCount,errorCount,errors}'

step "M3: manifest -> arrive -> receipt (directed putaway with check digits) -> complete"
MAN=$(curl -s $AUTH -X POST $B/receiving/manifests -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"manifestNumber\":\"MAN-4471\",\"carrier\":\"Sysco\",\"lines\":[{\"sku\":\"CHKN-40\",\"expectedQty\":10}]}" | jq -r .manifestId)
curl -s $AUTH -X POST $B/receiving/manifests/$MAN/arrive | jq -c .
REC=$(curl -s $AUTH -X POST $B/receiving/manifests/$MAN/receipts -H 'Content-Type: application/json' -d "{\"lineNumber\":1,\"lpn\":\"LPN-1001\",\"qty\":10,\"lot\":\"LOT-RCV\",\"expirationDate\":\"$SIXTY\"}")
echo "$REC" | jq -c .
TASK=$(echo "$REC" | jq -r .putawayTask.taskId); DIGITS=$(echo "$REC" | jq -r .putawayTask.checkDigits)
echo "wrong digits (hard stop expected):"
curl -s $AUTH -X POST $B/putaway/tasks/$TASK/complete -H 'Content-Type: application/json' -d '{"checkDigits":"99"}' | jq -c .
echo "right digits ($DIGITS):"
curl -s $AUTH -X POST $B/putaway/tasks/$TASK/complete -H 'Content-Type: application/json' -d "{\"checkDigits\":\"$DIGITS\"}" | jq -c .
echo "short-shelf-life receipt (21d min via item override — testing rejection at the door):"
$PSQL "UPDATE item SET min_shelf_life_receipt_days=21 WHERE sku='CHKN-40'" >/dev/null
FOURTEEN=$(date -d "+14 days" +%F)
curl -s $AUTH -X POST $B/receiving/manifests/$MAN/receipts -H 'Content-Type: application/json' -d "{\"lineNumber\":1,\"lpn\":\"LPN-1002\",\"qty\":1,\"lot\":\"LOT-SHORT\",\"expirationDate\":\"$FOURTEEN\"}" | jq -c .
curl -s $AUTH -X POST $B/receiving/manifests/$MAN/close | jq -c .

step "M4: customer (30-day freshness rule) -> order -> allocate: expect ROTATION_BYPASS of the 10-day lot"
CUST=$(curl -s $AUTH -X POST $B/customers -H 'Content-Type: application/json' -d "{\"ownerOrgId\":\"$SITE\",\"code\":\"GROC1\",\"name\":\"Grocery Chain\",\"minShelfLifeDays\":30}" | jq -r .customerId)
ORD=$(curl -s $AUTH -X POST $B/orders -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"orderNumber\":\"ORD-1001\",\"customerCode\":\"GROC1\",\"dropLocation\":\"DROP-07\",\"lines\":[{\"sku\":\"CHKN-40\",\"qty\":5}]}" | jq -r .orderId)
curl -s $AUTH -X POST $B/orders/$ORD/allocate | jq -c .
echo "bypass alert on file:"
curl -s $AUTH "$B/alerts?siteId=$SITE" | jq -c '.[] | {alert_type, severity}'
echo "bell after bypass:"
curl -s $AUTH $B/notifications/count | jq -c .

step "M4: wave -> release -> verified pick -> drop"
WAVE=$(curl -s $AUTH -X POST $B/waves -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"waveType\":\"SHIP_URGENCY\"}" | jq -r .waveId)
REL=$(curl -s $AUTH -X POST $B/waves/$WAVE/release -H 'Content-Type: application/json' -d '{"putMode":"TOTE"}')
echo "$REL" | jq -c .
ASG=$(echo "$REL" | jq -r '.assignments[0]')
TASKS=$(curl -s $AUTH $B/selection/assignments/$ASG/tasks)
echo "$TASKS" | jq -c '.[] | {seq, sku, from_code, qty, check_digits, put_check_digits}'
T1=$(echo "$TASKS" | jq -r '.[0].task_id'); CD1=$(echo "$TASKS" | jq -r '.[0].check_digits')
echo "bad put confirmation (refused expected):"
curl -s $AUTH -X POST $B/selection/tasks/$T1/pick -H 'Content-Type: application/json' -d "{\"checkDigits\":\"$CD1\",\"putConfirmation\":\"00\"}" | jq -c .
echo "verified picks — every task, slot digits + put digits (task 2 is 2 of a 20-case LPN: splits it):"
echo "$TASKS" | jq -c '.[] | {task_id, check_digits, put_check_digits}' | while read t; do
  TID=$(echo "$t" | jq -r .task_id); CD=$(echo "$t" | jq -r .check_digits); PD=$(echo "$t" | jq -r .put_check_digits)
  curl -s $AUTH -X POST $B/selection/tasks/$TID/pick -H 'Content-Type: application/json' -d "{\"checkDigits\":\"$CD\",\"putConfirmation\":\"$PD\"}" | jq -c .
done
echo "inventory after split:"
$PSQL "SELECT lpn, qty, status FROM inventory WHERE lpn LIKE 'LPN-0002%' ORDER BY lpn"
curl -s $AUTH -X POST $B/selection/drops -H 'Content-Type: application/json' -d "{\"orderId\":\"$ORD\"}" | jq -c .

step "M4: shipment -> packing list from picked reality -> ship"
SHP=$(curl -s $AUTH -X POST $B/shipments -H 'Content-Type: application/json' -d "{\"siteId\":\"$SITE\",\"orderIds\":[\"$ORD\"]}" | jq -r .shipmentId)
echo "ship without packing list (refused expected):"
curl -s $AUTH -X POST $B/shipments/$SHP/ship | jq -c .
curl -s $AUTH -X POST $B/shipments/$SHP/packing-list | jq -c .
echo "packing list lines (lot + expiry captured):"
$PSQL "SELECT line_number, qty, lot_number, expiration_date FROM packing_list_line"
curl -s $AUTH -X POST $B/shipments/$SHP/ship | jq -c .
curl -s $AUTH $B/orders/$ORD | jq -c '{status, lines: [.lines[] | {sku, ordered_qty, picked_qty}]}'

step "M5: replenishment scan — face at 3 vs trigger 8 (critical at <=4) -> assignment + CRITICAL alert -> bell + email outbox"
SCAN=$(curl -s $AUTH -X POST "$B/replenishment/scan?siteId=$SITE")
echo "$SCAN" | jq -c .
curl -s $AUTH "$B/replenishment/tasks?siteId=$SITE" | jq -c '.[] | {sku, from_code, to_code, qty, check_digits, put_check_digits}'
echo "alerts now:"
curl -s $AUTH "$B/alerts?siteId=$SITE" | jq -c '.[] | {alert_type, severity}'
echo "bell:"
curl -s $AUTH $B/notifications/count | jq -c .
curl -s $AUTH "$B/notifications?unreadOnly=true" | jq -c '.[] | {title}'
echo "email outbox (CRITICAL escalation, drained by Mailgun job when a key is configured):"
$PSQL "SELECT recipient, subject, sent_at IS NOT NULL AS sent FROM email_outbox"
RT=$(curl -s $AUTH "$B/replenishment/tasks?siteId=$SITE" | jq -r '.[0]')
RTID=$(echo "$RT" | jq -r .task_id); RCD=$(echo "$RT" | jq -r .check_digits); RPD=$(echo "$RT" | jq -r .put_check_digits)
echo "complete the replen (both sides verified):"
curl -s $AUTH -X POST $B/replenishment/tasks/$RTID/complete -H 'Content-Type: application/json' -d "{\"checkDigits\":\"$RCD\",\"putCheckDigits\":\"$RPD\"}" | jq -c .
echo "pick face after replenishment:"
$PSQL "SELECT l.code, COALESCE(sum(i.qty),0) on_hand FROM location l LEFT JOIN inventory i ON i.location_id=l.location_id AND i.status='AVAILABLE' WHERE l.code='C-PF-01' GROUP BY l.code"

step "M4/M5: metrics snapshots (admin-only DASHBOARD_VIEW)"
curl -s $AUTH "$B/metrics/labor?siteId=$SITE" | jq -c '.[] | {display_name, assignment_type, tasks, cases}'
curl -s $AUTH "$B/metrics/expiry-risk?siteId=$SITE" | jq -c '.[] | {sku, lot_number, days_remaining, qty}'

echo; echo "== SMOKE TEST COMPLETE =="
kill $APP 2>/dev/null
