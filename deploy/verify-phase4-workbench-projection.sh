#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <application-id>" >&2
  exit 2
fi

application_id=$1
case "$application_id" in
  ''|*[!0-9]*)
    echo "application-id must be a decimal number" >&2
    exit 2
    ;;
esac

DEPLOY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$DEPLOY_DIR"

set -a
. "$DEPLOY_DIR/.env"
set +a

root_query() {
  printf '%s\n' "$1" | docker compose exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -uroot 2>/dev/null'
}

for attempt in $(seq 1 20); do
  row=$(root_query "SELECT CONCAT(a.id, '|', a.version, '|', w.application_version, '|', a.status, '|', w.status) FROM redblack_approval.leave_application a JOIN redblack_office.office_workbench_application w ON w.application_id=a.id WHERE a.id=$application_id AND a.version=w.application_version AND a.status=w.status;")
  [ -n "$row" ] && break
  [ "$attempt" -eq 20 ] && {
    echo "Workbench projection did not converge for application $application_id" >&2
    exit 1
  }
  sleep 1
done

IFS='|' read -r actual_id approval_version workbench_version approval_status workbench_status <<EOF
$row
EOF

echo "PHASE4_WORKBENCH_OK application=$actual_id approval_version=$approval_version workbench_version=$workbench_version approval_status=$approval_status workbench_status=$workbench_status"
