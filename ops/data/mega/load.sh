#!/usr/bin/env bash
# Load the mega dataset into an EMPTY tenant, in order. Run from anywhere.
#
#   SYSADMIN_USER=admin@artemis.local SYSADMIN_PASS=... ops/data/mega/load.sh
#
#   ARTEMIS_URL    default http://localhost:8085
#   SYSADMIN_USER  platform sysadmin login   (default sysadmin@artemis.local) — creates the tenant
#   SYSADMIN_PASS  its password              (prompted if unset)
#   TENANT_USER    tenant admin from the hierarchy file (default tom@squiresenterprisesllc.com)
#   TENANT_PASS    its password              (default change-me)
#
# Expect several minutes: ~25,000 slots and ~23,000 pallets go in one validated row at a time.
# Starting over?  ops/artemis wipe <corporation id> all   (as the sysadmin), then run this again.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; cli="$here/../../artemis"
export ARTEMIS_URL="${ARTEMIS_URL:-http://localhost:8085}"
SYSADMIN_USER="${SYSADMIN_USER:-sysadmin@artemis.local}"
if [ -z "${SYSADMIN_PASS:-}" ]; then read -rsp "Password for $SYSADMIN_USER: " SYSADMIN_PASS; echo; fi
TENANT_USER="${TENANT_USER:-tom@squiresenterprisesllc.com}"; TENANT_PASS="${TENANT_PASS:-change-me}"

step() { # file user pass
  local f="$1" t0=$SECONDS
  printf '%-26s ' "$(basename "$f")"
  ARTEMIS_USER="$2" ARTEMIS_PASS="$3" "$cli" import "$f" > "$here/.last-$(basename "$f" .yaml).json" || { echo "FAILED — see $here/.last-$(basename "$f" .yaml).json"; exit 1; }
  if command -v jq >/dev/null 2>&1; then
    jq -r '[paths(type == "object" and has("created")) as $p | "\($p | map(tostring) | join(".")): +\(getpath($p).created) ~\(getpath($p).updated // 0) !\(getpath($p).errors | length)"] | join("   ")' "$here/.last-$(basename "$f" .yaml).json" | tr -d '\n'
  fi
  echo "   ($((SECONDS - t0))s)"
}

echo "Loading the mega dataset into $ARTEMIS_URL   (+created ~updated !errors)"
step "$here/mega-0-hierarchy.yaml" "$SYSADMIN_USER" "$SYSADMIN_PASS"
step "$here/mega-1-master.yaml"    "$TENANT_USER"   "$TENANT_PASS"
step "$here/mega-2-locations.yaml" "$TENANT_USER"   "$TENANT_PASS"
step "$here/mega-3-stock.yaml"     "$TENANT_USER"   "$TENANT_PASS"
step "$here/mega-4-activity.yaml"  "$TENANT_USER"   "$TENANT_PASS"
echo "Done. Sign in as $TENANT_USER. Full reports: $here/.last-*.json"
