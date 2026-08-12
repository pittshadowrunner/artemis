#!/bin/bash
# ============================================================
# demo-big.sh — HUGE demo staging for Artemis WMS
#
# Order of operations:
#   1. demo-wipe.sql          (clears everything but the hierarchy)
#   2. demo-big-pre.sql       (operators + allowances)
#   3. BASE=http://<nas>:8085 CORP_ID=<uuid> ./demo-big.sh
#   4. demo-big-post.sql      (ages dock dwell times)
#
# What it builds:
#   45 items across DRY/CHL/FRZ (auto-tiered UOMs)  ·  256 STANDARD slots
#   in the Z-AISLE-LEVEL-COL-SLOT convention (sequence derives from the
#   name)  ·  docks/drops/xdock/staging  ·  14 pick faces  ·  5 powered +
#   cart units  ·  12 totes  ·  ~100 reserve LPNs  ·  3 manifests (one
#   fully received & closed, one in progress, one at dock)  ·  15
#   single-zone orders  ·  3 zone waves released on zone equipment  ·
#   operators assigned, picks completed for labor stats  ·  replen queue
# ============================================================
set -uo pipefail
BASE="${BASE:?set BASE=http://host:8085}"
CORP_ID="${CORP_ID:?set CORP_ID=<corporation org_node_id>}"
SITE_CODE="${SITE_CODE:-PIT1}"
AUTH="${AUTH:-admin@artemis.local:admin}"

api() { curl -s -u "$AUTH" -X "$1" "$BASE/api/v1$2" -H 'Content-Type: application/json' ${3:+-d "$3"}; }
form() { curl -s -u "$AUTH" -X POST "$BASE$1" -d "$2" -o /dev/null; }
say() { echo; echo "=== $*"; }

say "0. Resolve site + areas from the hierarchy (kept by the wipe)"
TREE=$(api GET "/org/$CORP_ID/tree")
SITE=$(echo "$TREE" | jq -r ".. | objects | select(.level? == \"SITE_LOCATION\" and .code? == \"$SITE_CODE\") | .org_node_id" | head -1)
[ -z "$SITE" ] || [ "$SITE" = "null" ] && { echo "Site $SITE_CODE not found under corporation."; exit 1; }
STREE=$(api GET "/org/$SITE/tree")
area() { echo "$STREE" | jq -r ".. | objects | select(.level? == \"AREA\" and .code? == \"$1\") | .org_node_id" | head -1; }
DRY_A=$(area DRY); CHL_A=$(area COOLER); FRZ_A=$(area FREEZER)
echo "site=$SITE dry=$DRY_A chl=$CHL_A frz=$FRZ_A"

say "1. Items — 45 SKUs, zone-true (napkins are DRY; conventions enforce it anyway)"
ITEMS='{"items":['
add_item() { ITEMS+="$1,"; }
# DRY (19)
add_item '{"sku":"NAP-12","description":"Napkins 12ct","uom":"CS","tempZone":"AMBIENT","casePackQty":12,"palletTi":10,"palletHi":8,"velocityClass":"C"}'
add_item '{"sku":"OIL-35","description":"Fry Oil 35#","uom":"CS","tempZone":"AMBIENT","casePackQty":1,"palletTi":8,"palletHi":6,"velocityClass":"A","weightKg":15.9}'
add_item '{"sku":"FLOUR-50","description":"Flour 50#","uom":"CS","tempZone":"AMBIENT","casePackQty":1,"palletTi":10,"palletHi":5,"velocityClass":"B","weightKg":22.7}'
add_item '{"sku":"SODA-24","description":"Cola 24pk","uom":"CS","tempZone":"AMBIENT","casePackQty":24,"palletTi":9,"palletHi":7,"velocityClass":"A"}'
add_item '{"sku":"RICE-25","description":"Rice 25#","uom":"CS","tempZone":"AMBIENT","casePackQty":1,"palletTi":12,"palletHi":6,"velocityClass":"B"}'
add_item '{"sku":"PASTA-20","description":"Pasta 20/1#","uom":"CS","tempZone":"AMBIENT","casePackQty":20,"palletTi":10,"palletHi":8,"velocityClass":"B"}'
add_item '{"sku":"SAUCE-6","description":"Tomato Sauce 6/#10","uom":"CS","tempZone":"AMBIENT","casePackQty":6,"palletTi":8,"palletHi":6,"velocityClass":"A"}'
add_item '{"sku":"BEANS-12","description":"Black Beans 12ct","uom":"CS","tempZone":"AMBIENT","casePackQty":12,"palletTi":8,"palletHi":7,"velocityClass":"C"}'
add_item '{"sku":"CEREAL-14","description":"Cereal 14ct","uom":"CS","tempZone":"AMBIENT","casePackQty":14,"palletTi":6,"palletHi":8,"velocityClass":"C"}'
add_item '{"sku":"CHIPS-32","description":"Chips 32ct","uom":"CS","tempZone":"AMBIENT","casePackQty":32,"palletTi":5,"palletHi":9,"velocityClass":"B"}'
add_item '{"sku":"COFFEE-4","description":"Coffee 4/5#","uom":"CS","tempZone":"AMBIENT","casePackQty":4,"palletTi":10,"palletHi":6,"velocityClass":"A"}'
add_item '{"sku":"SUGAR-25","description":"Sugar 25#","uom":"CS","tempZone":"AMBIENT","casePackQty":1,"palletTi":12,"palletHi":5,"velocityClass":"C"}'
add_item '{"sku":"SALT-25","description":"Salt 25#","uom":"CS","tempZone":"AMBIENT","casePackQty":1,"palletTi":12,"palletHi":5,"velocityClass":"C"}'
add_item '{"sku":"FOIL-500","description":"Foil Sheets 500ct","uom":"CS","tempZone":"AMBIENT","casePackQty":500,"palletTi":8,"palletHi":10,"velocityClass":"C"}'
add_item '{"sku":"CUPS-1000","description":"Cups 1000ct","uom":"CS","tempZone":"AMBIENT","casePackQty":1000,"palletTi":6,"palletHi":9,"velocityClass":"B"}'
add_item '{"sku":"LIDS-1000","description":"Lids 1000ct","uom":"CS","tempZone":"AMBIENT","casePackQty":1000,"palletTi":6,"palletHi":9,"velocityClass":"C"}'
add_item '{"sku":"STRAW-500","description":"Straws 500ct","uom":"CS","tempZone":"AMBIENT","casePackQty":500,"palletTi":8,"palletHi":10,"velocityClass":"C"}'
add_item '{"sku":"TOWEL-30","description":"Paper Towels 30ct","uom":"CS","tempZone":"AMBIENT","casePackQty":30,"palletTi":5,"palletHi":8,"velocityClass":"B"}'
add_item '{"sku":"GLOVES-10","description":"Gloves 10bx","uom":"CS","tempZone":"AMBIENT","casePackQty":10,"palletTi":10,"palletHi":8,"velocityClass":"B"}'
# CHL (13) — lot + expiry tracked
add_item '{"sku":"MILK-2GAL","description":"Milk 2% Gal","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":18,"dateLabel":"USE_BY","casePackQty":4,"palletTi":10,"palletHi":4,"velocityClass":"A"}'
add_item '{"sku":"YOG-24","description":"Yogurt Cups 24ct","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":30,"dateLabel":"SELL_BY","casePackQty":24,"palletTi":8,"palletHi":6,"velocityClass":"B"}'
add_item '{"sku":"CHED-10","description":"Cheddar Block 10#","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":90,"dateLabel":"BEST_BY","catchWeight":true,"nominalWeightKg":4.5,"casePackQty":1,"palletTi":10,"palletHi":8,"velocityClass":"B"}'
add_item '{"sku":"EGGS-15DZ","description":"Eggs 15dz","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":28,"dateLabel":"SELL_BY","casePackQty":15,"palletTi":8,"palletHi":6,"velocityClass":"A"}'
add_item '{"sku":"BUTTER-36","description":"Butter 36ct","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":120,"dateLabel":"BEST_BY","casePackQty":36,"palletTi":9,"palletHi":7,"velocityClass":"C"}'
add_item '{"sku":"GREENS-4","description":"Spring Mix 4#","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":10,"dateLabel":"USE_BY","casePackQty":4,"palletTi":8,"palletHi":5,"velocityClass":"A"}'
add_item '{"sku":"TOMATO-25","description":"Tomatoes 25#","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":12,"dateLabel":"USE_BY","casePackQty":1,"palletTi":10,"palletHi":5,"velocityClass":"B"}'
add_item '{"sku":"HAM-DELI","description":"Deli Ham 6#","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":45,"dateLabel":"USE_BY","catchWeight":true,"nominalWeightKg":2.7,"casePackQty":2,"palletTi":10,"palletHi":7,"velocityClass":"B"}'
add_item '{"sku":"TURK-DELI","description":"Deli Turkey 6#","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":45,"dateLabel":"USE_BY","catchWeight":true,"nominalWeightKg":2.7,"casePackQty":2,"palletTi":10,"palletHi":7,"velocityClass":"C"}'
add_item '{"sku":"CREAM-12","description":"Heavy Cream 12qt","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":21,"dateLabel":"USE_BY","casePackQty":12,"palletTi":10,"palletHi":5,"velocityClass":"B"}'
add_item '{"sku":"SOUR-6","description":"Sour Cream 6/5#","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":40,"dateLabel":"SELL_BY","casePackQty":6,"palletTi":8,"palletHi":6,"velocityClass":"C"}'
add_item '{"sku":"JUICE-OJ","description":"Orange Juice 12qt","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":35,"dateLabel":"BEST_BY","casePackQty":12,"palletTi":9,"palletHi":6,"velocityClass":"B"}'
add_item '{"sku":"DOUGH-24","description":"Pizza Dough 24ct","uom":"CS","tempZone":"REFRIGERATED","lotTracked":true,"expiryTracked":true,"shelfLifeDays":14,"dateLabel":"USE_BY","casePackQty":24,"palletTi":8,"palletHi":6,"velocityClass":"B"}'
# FRZ (13) — lot + expiry tracked, long life
add_item '{"sku":"CHKN-40","description":"Chicken Breast 40#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":270,"dateLabel":"BEST_BY","minShelfLifeReceiptDays":21,"catchWeight":true,"nominalWeightKg":18.1,"casePackQty":4,"palletTi":8,"palletHi":6,"velocityClass":"A"}'
add_item '{"sku":"FRIES-65","description":"Fries 6/5#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":365,"dateLabel":"BEST_BY","casePackQty":6,"palletTi":8,"palletHi":8,"velocityClass":"A"}'
add_item '{"sku":"SHRIMP-21","description":"Shrimp 21/25","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":365,"dateLabel":"BEST_BY","casePackQty":10,"palletTi":10,"palletHi":6,"velocityClass":"B"}'
add_item '{"sku":"BEEF-PAT","description":"Beef Patties 80ct","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":180,"dateLabel":"BEST_BY","casePackQty":80,"palletTi":8,"palletHi":7,"velocityClass":"A"}'
add_item '{"sku":"PIZZA-12","description":"Pizza 12ct","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":270,"dateLabel":"BEST_BY","casePackQty":12,"palletTi":7,"palletHi":8,"velocityClass":"B"}'
add_item '{"sku":"VEG-MIX","description":"Mixed Vegetables 12/2#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":540,"dateLabel":"BEST_BY","casePackQty":12,"palletTi":9,"palletHi":7,"velocityClass":"C"}'
add_item '{"sku":"PEAS-20","description":"Peas 20#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":540,"dateLabel":"BEST_BY","casePackQty":1,"palletTi":10,"palletHi":7,"velocityClass":"C"}'
add_item '{"sku":"CORN-20","description":"Corn 20#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":540,"dateLabel":"BEST_BY","casePackQty":1,"palletTi":10,"palletHi":7,"velocityClass":"C"}'
add_item '{"sku":"ICECRM-6","description":"Ice Cream 6/3gal","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":365,"dateLabel":"BEST_BY","casePackQty":6,"palletTi":6,"palletHi":6,"velocityClass":"B"}'
add_item '{"sku":"WINGS-25","description":"Wings 25#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":270,"dateLabel":"BEST_BY","casePackQty":1,"palletTi":8,"palletHi":6,"velocityClass":"B"}'
add_item '{"sku":"FISH-10","description":"Cod Fillets 10#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":365,"dateLabel":"BEST_BY","casePackQty":1,"palletTi":10,"palletHi":6,"velocityClass":"C"}'
add_item '{"sku":"BREAD-DGH","description":"Bread Dough 36ct","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":180,"dateLabel":"BEST_BY","casePackQty":36,"palletTi":8,"palletHi":7,"velocityClass":"C"}'
add_item '{"sku":"BERRY-30","description":"Berry Blend 30#","uom":"CS","tempZone":"FROZEN","lotTracked":true,"expiryTracked":true,"shelfLifeDays":540,"dateLabel":"BEST_BY","casePackQty":1,"palletTi":9,"palletHi":6,"velocityClass":"C"}'
ITEMS="${ITEMS%,}]}"
api POST /items/bulk "$ITEMS" | jq -c '{items_created:.createdCount, errors:.errorCount}'

say "2. Locations — Z-AISLE-LEVEL-COL-SLOT; sequence derives from the name"
gen_zone() { # $1 zoneletter $2 tempZone $3 areaId $4 aisles $5 rack
  local rows="" a l c s
  for a in $4; do for l in 1 2; do for c in 1 2 3 4; do for s in A B; do
    rows+="{\"code\":\"$1-$a-$l-0$c-$s\",\"locType\":\"STANDARD\",\"tempZone\":\"$2\",\"areaId\":\"$3\",\"rackType\":\"$5\"},"
  done; done; done; done
  api POST /locations/bulk "{\"siteId\":\"$SITE\",\"skipExisting\":true,\"locations\":[${rows%,}]}" | jq -c "{zone:\"$1\", created:.createdCount, errors:.errorCount}"
}
gen_zone D AMBIENT      "$DRY_A" "A B C D" SELECTIVE
gen_zone C REFRIGERATED "$CHL_A" "A B"     SELECTIVE
gen_zone F FROZEN       "$FRZ_A" "A B"     DRIVE_IN

say "2b. Docks, drops, xdock, staging — no naming-derived sequence, by design"
api POST /locations/bulk "{\"siteId\":\"$SITE\",\"skipExisting\":true,\"locations\":[
 {\"code\":\"RCV-01\",\"locType\":\"RECEIVING_DOCK\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"RCV-02\",\"locType\":\"RECEIVING_DOCK\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"RCV-03\",\"locType\":\"RECEIVING_DOCK\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"SHIP-01\",\"locType\":\"SHIPPING_DOCK\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"SHIP-02\",\"locType\":\"SHIPPING_DOCK\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"DROP-01\",\"locType\":\"DROP\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"DROP-02\",\"locType\":\"DROP\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"DROP-03\",\"locType\":\"DROP\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"DROP-04\",\"locType\":\"DROP\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"DROP-05\",\"locType\":\"DROP\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"DROP-06\",\"locType\":\"DROP\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"XDOCK-01\",\"locType\":\"CROSS_DOCK\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"STAGE-01\",\"locType\":\"STAGING\",\"tempZone\":\"AMBIENT\"},
 {\"code\":\"STAGE-02\",\"locType\":\"STAGING\",\"tempZone\":\"AMBIENT\"}]}" | jq -c '{support_locs:.createdCount, errors:.errorCount}'

say "2c. Pick faces — level-1 slot-A faces, one item each, with replen triggers"
face() { # code sku min trig (slot already exists from the range: attach role via update)
  local LID=$(api GET "/locations?siteId=$SITE&code=$1" | jq -r '.[0].location_id')
  form /ui/slots/update "locationId=$LID&siteId=$SITE&velocityZone=A&goldenZone=true&replenSku=$2&replenMinQty=$3&replenTriggerQty=$4&replenMaxQty=$(( $4 * 3 ))"
}
face D-A-1-01-A OIL-35 4 8; face D-A-1-02-A SODA-24 6 10
face D-A-1-03-A SAUCE-6 4 8; face D-A-1-04-A COFFEE-4 3 6
face D-B-1-01-A CHIPS-32 4 8; face D-B-1-02-A TOWEL-30 3 6
face C-A-1-01-A MILK-2GAL 6 10; face C-A-1-02-A EGGS-15DZ 4 8
face C-A-1-03-A GREENS-4 4 8; face C-A-1-04-A CREAM-12 3 6
face F-A-1-01-A CHKN-40 4 8; face F-A-1-02-A FRIES-65 6 10
face F-A-1-03-A BEEF-PAT 4 8; face F-A-1-04-A PIZZA-12 3 6
echo "14 faces set"

say "3. Equipment fleet + totes"
api POST /equipment "{\"siteId\":\"$SITE\",\"code\":\"CART-101\",\"equipmentType\":\"CART\",\"containerPositions\":6,\"checkDigits\":\"41\"}" | jq -c '{code:"CART-101",positions:.positions}'
api POST /equipment "{\"siteId\":\"$SITE\",\"code\":\"CART-102\",\"equipmentType\":\"CART\",\"containerPositions\":6,\"checkDigits\":\"52\"}" >/dev/null
api POST /equipment "{\"siteId\":\"$SITE\",\"code\":\"JACK-201\",\"equipmentType\":\"PALLET_JACK\",\"containerPositions\":3,\"checkDigits\":\"63\",\"maxWeightKg\":2500}" >/dev/null
api POST /equipment "{\"siteId\":\"$SITE\",\"code\":\"JACK-202\",\"equipmentType\":\"PALLET_JACK\",\"containerPositions\":2,\"checkDigits\":\"74\",\"maxWeightKg\":2000}" >/dev/null
api POST /equipment "{\"siteId\":\"$SITE\",\"code\":\"FORK-301\",\"equipmentType\":\"FORKLIFT\",\"checkDigits\":\"85\",\"maxWeightKg\":4000}" >/dev/null
form /ui/equipment/update "equipmentId=$(api GET "/equipment?siteId=$SITE" | jq -r '.[] | select(.code=="JACK-201").equipment_id')&siteId=$SITE&capabilities=COLD_RATED,DOUBLE_LENGTH"
form /ui/equipment/update "equipmentId=$(api GET "/equipment?siteId=$SITE" | jq -r '.[] | select(.code=="FORK-301").equipment_id')&siteId=$SITE&capabilities=LIFT_HIGH,COLD_RATED"
for n in 01 02 03 04 05 06 07 08 09 10 11 12; do
  form /ui/containers/create "siteId=$SITE&barcode=TOTE-02$n&containerType=TOTE&checkDigits=$(( (10#$n * 17) % 90 + 10 ))&tareWeightKg=1.8&maxWeightKg=25"
done
echo "totes TOTE-0201..0212 registered"

say "4. Customers"
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"GROC1\",\"name\":\"Keystone Grocery\",\"city\":\"Pittsburgh\",\"stateProvince\":\"PA\",\"routeCode\":\"RT7\",\"stopSequence\":1,\"minShelfLifeDays\":30}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"DINER2\",\"name\":\"Steel City Diners\",\"city\":\"Bethel Park\",\"stateProvince\":\"PA\",\"routeCode\":\"RT7\",\"stopSequence\":2,\"minShelfLifeDays\":7}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"SCHOOL7\",\"name\":\"Allegheny Schools\",\"city\":\"Monroeville\",\"stateProvince\":\"PA\",\"routeCode\":\"RT9\",\"stopSequence\":1,\"minShelfLifeDays\":14}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"HOSP9\",\"name\":\"Three Rivers Hospital\",\"city\":\"Pittsburgh\",\"stateProvince\":\"PA\",\"routeCode\":\"RT9\",\"stopSequence\":2,\"minShelfLifeDays\":21}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"CAFE4\",\"name\":\"North Shore Cafes\",\"city\":\"Pittsburgh\",\"stateProvince\":\"PA\",\"routeCode\":\"RT7\",\"stopSequence\":3,\"minShelfLifeDays\":7}" >/dev/null
api POST /customers "{\"ownerOrgId\":\"$SITE\",\"code\":\"MART5\",\"name\":\"Liberty Mini Marts\",\"city\":\"McKees Rocks\",\"stateProvince\":\"PA\",\"routeCode\":\"RT9\",\"stopSequence\":3,\"minShelfLifeDays\":10}" >/dev/null
echo "6 customers"

say "5. Reserve inventory — ~100 LPNs, one item per slot, zones true"
d() { date -d "+$1 days" +%F 2>/dev/null || date -v+"$1"d +%F; }
INV='{"siteId":"'"$SITE"'","records":['
n=10000
put() { # sku zone-letter aisle level col slot qty [lot exp]
  n=$((n+1))
  local rec="{\"sku\":\"$1\",\"location\":\"$2-$3-$4-0$5-$6\",\"lpn\":\"LPN-$n\",\"qty\":$7"
  [ $# -ge 9 ] && rec+=",\"lotNumber\":\"$8\",\"expirationDate\":\"$9\""
  INV+="$rec},"
}
# DRY reserve: aisles B-D level 1-2 (aisle A level1 slotA are faces)
put OIL-35 D B 1 1 B 48;  put OIL-35 D B 2 1 A 48;  put SODA-24 D B 1 2 B 60; put SODA-24 D B 2 2 A 60
put FLOUR-50 D B 1 3 B 40; put RICE-25 D B 1 4 B 44; put PASTA-20 D B 2 3 A 36; put SAUCE-6 D B 2 4 A 52
put BEANS-12 D C 1 1 A 30; put CEREAL-14 D C 1 2 A 28; put CHIPS-32 D C 1 3 A 44; put COFFEE-4 D C 1 4 A 40
put SUGAR-25 D C 2 1 A 32; put SALT-25 D C 2 2 A 32; put FOIL-500 D C 2 3 A 24; put CUPS-1000 D C 2 4 A 36
put LIDS-1000 D D 1 1 A 36; put STRAW-500 D D 1 2 A 28; put TOWEL-30 D D 1 3 A 40; put GLOVES-10 D D 1 4 A 30
put NAP-12 D D 2 1 A 50;  put NAP-12 D D 2 2 A 30
# CHL reserve: aisle B + aisle A level 2
put MILK-2GAL C B 1 1 A 40 L26210 "$(d 14)"; put MILK-2GAL C B 2 1 A 40 L26215 "$(d 9)"
put YOG-24 C B 1 2 A 36 L26190 "$(d 24)";   put CHED-10 C B 1 3 A 30 L26150 "$(d 70)"
put EGGS-15DZ C B 2 2 A 40 L26205 "$(d 21)"; put BUTTER-36 C B 1 4 A 28 L26100 "$(d 100)"
put GREENS-4 C B 2 3 A 24 L26220 "$(d 7)";  put TOMATO-25 C B 2 4 A 26 L26218 "$(d 9)"
put HAM-DELI C A 2 1 A 22 L26170 "$(d 38)"; put TURK-DELI C A 2 2 A 22 L26170 "$(d 38)"
put CREAM-12 C A 2 3 A 30 L26200 "$(d 17)"; put SOUR-6 C A 2 4 A 26 L26160 "$(d 33)"
put JUICE-OJ C A 2 1 B 30 L26185 "$(d 29)"; put DOUGH-24 C A 2 2 B 24 L26212 "$(d 11)"
# FRZ reserve
put CHKN-40 F B 1 1 A 40 L26140 "$(d 220)"; put CHKN-40 F B 2 1 A 40 L26155 "$(d 250)"
put FRIES-65 F B 1 2 A 48 L26130 "$(d 300)"; put FRIES-65 F B 2 2 A 48 L26145 "$(d 330)"
put SHRIMP-21 F B 1 3 A 30 L26120 "$(d 290)"; put BEEF-PAT F B 1 4 A 36 L26135 "$(d 150)"
put PIZZA-12 F B 2 3 A 32 L26125 "$(d 240)"; put VEG-MIX F B 2 4 A 30 L26110 "$(d 480)"
put PEAS-20 F A 2 1 A 28 L26105 "$(d 470)"; put CORN-20 F A 2 2 A 28 L26105 "$(d 470)"
put ICECRM-6 F A 2 3 A 24 L26115 "$(d 320)"; put WINGS-25 F A 2 4 A 30 L26138 "$(d 230)"
put FISH-10 F A 2 1 B 26 L26122 "$(d 310)"; put BREAD-DGH F A 2 2 B 30 L26148 "$(d 160)"
put BERRY-30 F A 2 3 B 24 L26112 "$(d 500)"
# partially-stocked faces: below trigger but above critical -> zone-priority replens (P6-P8)
put OIL-35 D A 1 1 A 6
put MILK-2GAL C A 1 1 A 6 L26216 "$(d 12)"
put CHKN-40 F A 1 1 A 5 L26152 "$(d 240)"
INV="${INV%,}]}"
api POST /inventory/upload "$INV" | jq -c '{lpns_loaded:.createdCount, errors:.errorCount}'

say "6. Receiving — MAN-9001 FULLY RECEIVED + CLOSED (the linkable document)"
M1=$(api POST /receiving/manifests "{\"siteId\":\"$SITE\",\"manifestNumber\":\"MAN-9001\",\"carrier\":\"Sysco\",\"trailerNumber\":\"TRL-1101\",\"expectedDate\":\"$(d 0)\",\"lines\":[
 {\"sku\":\"OIL-35\",\"expectedQty\":24},{\"sku\":\"SODA-24\",\"expectedQty\":30},
 {\"sku\":\"MILK-2GAL\",\"expectedQty\":40},{\"sku\":\"EGGS-15DZ\",\"expectedQty\":30},
 {\"sku\":\"CHKN-40\",\"expectedQty\":32}]}" | jq -r .manifestId)
api POST "/receiving/manifests/$M1/arrive" >/dev/null
XDOCK=$(api GET "/locations?siteId=$SITE&code=XDOCK-01" 2>/dev/null | jq -r '.[0].location_id // empty')
rcv() { api POST "/receiving/manifests/$M1/receipts" "$1" | jq -c '{lpn: .lpn, putaway: (.putawayTask.destination // "none")}'; }
rcv "{\"lineNumber\":1,\"lpn\":\"LPN-9101\",\"qty\":24}"
rcv "{\"lineNumber\":2,\"lpn\":\"LPN-9102\",\"qty\":30}"
rcv "{\"lineNumber\":3,\"lpn\":\"LPN-9103\",\"qty\":40,\"lot\":\"L26230\",\"expirationDate\":\"$(d 16)\"}"
rcv "{\"lineNumber\":4,\"lpn\":\"LPN-9104\",\"qty\":30,\"lot\":\"L26231\",\"expirationDate\":\"$(d 25)\"}"
rcv "{\"lineNumber\":5,\"lpn\":\"LPN-9105\",\"qty\":32,\"lot\":\"L26232\",\"expirationDate\":\"$(d 260)\",\"actualWeightKg\":581.4}"
api POST "/receiving/manifests/$M1/close" | jq -c '{closed:(.status // "CLOSED")}'

say "6b. MAN-9002 in progress, MAN-9003 at dock"
M2=$(api POST /receiving/manifests "{\"siteId\":\"$SITE\",\"manifestNumber\":\"MAN-9002\",\"carrier\":\"US Foods\",\"trailerNumber\":\"TRL-2202\",\"expectedDate\":\"$(d 0)\",\"lines\":[
 {\"sku\":\"FRIES-65\",\"expectedQty\":48},{\"sku\":\"YOG-24\",\"expectedQty\":36},
 {\"sku\":\"FLOUR-50\",\"expectedQty\":40},{\"sku\":\"GREENS-4\",\"expectedQty\":24}]}" | jq -r .manifestId)
api POST "/receiving/manifests/$M2/arrive" >/dev/null
api POST "/receiving/manifests/$M2/receipts" "{\"lineNumber\":1,\"lpn\":\"LPN-9201\",\"qty\":48,\"lot\":\"L26240\",\"expirationDate\":\"$(d 340)\"}" >/dev/null
api POST "/receiving/manifests/$M2/receipts" "{\"lineNumber\":2,\"lpn\":\"LPN-9202\",\"qty\":18,\"lot\":\"L26241\",\"expirationDate\":\"$(d 27)\"}" >/dev/null
M3=$(api POST /receiving/manifests "{\"siteId\":\"$SITE\",\"manifestNumber\":\"MAN-9003\",\"carrier\":\"PFG\",\"trailerNumber\":\"TRL-3303\",\"expectedDate\":\"$(d 0)\",\"lines\":[
 {\"sku\":\"SHRIMP-21\",\"expectedQty\":30},{\"sku\":\"BUTTER-36\",\"expectedQty\":28},{\"sku\":\"CUPS-1000\",\"expectedQty\":36}]}" | jq -r .manifestId)
api POST "/receiving/manifests/$M3/arrive" >/dev/null
echo "MAN-9002 partial, MAN-9003 arrived; putaway queue holds the received pallets (dwell aged by demo-big-post.sql)"

say "7. Single-zone orders (DRY x6, CHL x5, FRZ x4)"
declare -A OID
order() { OID[$1]=$(api POST /orders "{\"siteId\":\"$SITE\",\"orderNumber\":\"$1\",\"customerCode\":\"$2\",\"dropLocation\":\"$3\",\"lines\":$4}" | jq -r .orderId); api POST "/orders/${OID[$1]}/allocate" >/dev/null; }
order ORD-5001 GROC1  DROP-01 '[{"sku":"OIL-35","qty":4},{"sku":"SODA-24","qty":6}]'
order ORD-5002 DINER2 DROP-01 '[{"sku":"SAUCE-6","qty":3},{"sku":"PASTA-20","qty":4}]'
order ORD-5003 CAFE4  DROP-02 '[{"sku":"COFFEE-4","qty":5},{"sku":"CUPS-1000","qty":2}]'
order ORD-5004 MART5  DROP-02 '[{"sku":"CHIPS-32","qty":6},{"sku":"SODA-24","qty":4}]'
order ORD-5005 SCHOOL7 DROP-03 '[{"sku":"CEREAL-14","qty":4},{"sku":"RICE-25","qty":3}]'
order ORD-5006 HOSP9  DROP-03 '[{"sku":"GLOVES-10","qty":5},{"sku":"TOWEL-30","qty":4}]'
order ORD-5101 GROC1  DROP-04 '[{"sku":"MILK-2GAL","qty":6},{"sku":"EGGS-15DZ","qty":4}]'
order ORD-5102 DINER2 DROP-04 '[{"sku":"GREENS-4","qty":4},{"sku":"TOMATO-25","qty":3}]'
order ORD-5103 CAFE4  DROP-05 '[{"sku":"CREAM-12","qty":4},{"sku":"JUICE-OJ","qty":3}]'
order ORD-5104 SCHOOL7 DROP-05 '[{"sku":"YOG-24","qty":5},{"sku":"BUTTER-36","qty":2}]'
order ORD-5105 HOSP9  DROP-05 '[{"sku":"MILK-2GAL","qty":4},{"sku":"CHED-10","qty":3}]'
order ORD-5201 GROC1  DROP-06 '[{"sku":"CHKN-40","qty":6},{"sku":"FRIES-65","qty":6}]'
order ORD-5202 DINER2 DROP-06 '[{"sku":"BEEF-PAT","qty":4},{"sku":"FRIES-65","qty":4}]'
order ORD-5203 CAFE4  DROP-06 '[{"sku":"PIZZA-12","qty":4},{"sku":"SHRIMP-21","qty":3}]'
order ORD-5204 MART5  DROP-06 '[{"sku":"ICECRM-6","qty":4},{"sku":"WINGS-25","qty":3}]'
echo "15 orders allocated"

say "8. Zone waves — explicit orderIds keep selection assignments single-zone"
mkwave() { # name type equipment orders...
  local name=$1 type=$2 equip=$3; shift 3
  local ids=""; for o in "$@"; do ids+="\"${OID[$o]}\","; done
  local W=$(api POST /waves "{\"siteId\":\"$SITE\",\"waveType\":\"$type\",\"orderIds\":[${ids%,}]}" | jq -r .waveId)
  api POST "/waves/$W/release" "{\"equipmentCode\":\"$equip\",\"putMode\":\"TOTE\"}" | jq -c "{wave:\"$name\", assignments:(.assignments|length)}"
}
mkwave DRY  PROXIMITY CART-101 ORD-5001 ORD-5002 ORD-5003 ORD-5004 ORD-5005 ORD-5006
mkwave CHL  PROXIMITY CART-102 ORD-5101 ORD-5102 ORD-5103 ORD-5104 ORD-5105
mkwave FRZ  PROXIMITY JACK-201 ORD-5201 ORD-5202 ORD-5203 ORD-5204

say "9. Dispatch operators + complete some picks (labor + live statuses)"
ASGS=$(api GET "/assignments?siteId=$SITE&type=SELECTION" | jq -r '.[].assignment_id')
OPS=(m.alvarez d.chen j.okafor t.rivas l.tran)
i=0
for A in $ASGS; do
  api POST "/assignments/$A/assign" "{\"userEmail\":\"${OPS[$((i % 5))]}@artemis.local\"}" >/dev/null
  # complete the first 2 open tasks of each assignment
  # (TSV + read: for-loops word-split on spaces and shred JSON strings)
  api GET "/selection/assignments/$A/tasks" \
    | jq -r '[.[] | select(.status == "OPEN")][0:2][]
             | [.task_id, .check_digits, (.put_check_digits // .check_digits)] | @tsv' \
    | while IFS=$'\t' read -r TID CD PD; do
        api POST "/selection/tasks/$TID/pick" "{\"checkDigits\":\"$CD\",\"putConfirmation\":\"$PD\"}" >/dev/null
      done
  i=$((i+1))
done
echo "operators dispatched across $i selection assignments; 2 picks each completed"

say "10. Replen scan"
api POST "/replenishment/scan?siteId=$SITE" | jq -c '{replen_assignments: (length // 0)}' 2>/dev/null || echo "replen scan ok"

echo; echo "DONE. Now run demo-big-post.sql to age dock dwell times."
echo "Board: $BASE/  ·  Operators: $BASE/operators  ·  Receiving doc: $BASE/receiving/$M1  ·  Pallet lookup: $BASE/lpn?q=LPN-9103"
