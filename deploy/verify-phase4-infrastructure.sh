#!/bin/sh
set -eu

DEPLOY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$DEPLOY_DIR"

services="mysql redis kafka identity-service approval-service office-service audit-service gateway-service nginx"
for attempt in $(seq 1 40); do
  unhealthy=""
  for service in $services; do
    container_id=$(docker compose ps -q "$service")
    if [ -z "$container_id" ]; then
      unhealthy="$unhealthy $service(missing)"
      continue
    fi
    state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")
    if [ "$state" != "healthy" ]; then
      unhealthy="$unhealthy $service($state)"
    fi
  done
  [ -z "$unhealthy" ] && break
  [ "$attempt" -eq 40 ] && { echo "Unhealthy containers:$unhealthy" >&2; exit 1; }
  sleep 3
done

set -a
. "$DEPLOY_DIR/.env"
set +a

identity_query() {
  printf '%s\n' "$1" | docker compose exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -u"$MYSQL_USER" redblack_identity 2>/dev/null'
}
approval_query() {
  printf '%s\n' "$1" | docker compose exec -T mysql sh -c \
    'MYSQL_PWD="$APPROVAL_DB_PASSWORD" mysql -N -u"$APPROVAL_DB_USERNAME" redblack_approval 2>/dev/null'
}
office_query() {
  printf '%s\n' "$1" | docker compose exec -T mysql sh -c \
    'MYSQL_PWD="$OFFICE_DB_PASSWORD" mysql -N -u"$OFFICE_DB_USERNAME" redblack_office 2>/dev/null'
}
audit_query() {
  printf '%s\n' "$1" | docker compose exec -T mysql sh -c \
    'MYSQL_PWD="$AUDIT_DB_PASSWORD" mysql -N -u"$AUDIT_DB_USERNAME" redblack_audit 2>/dev/null'
}

office_tables=$(office_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='redblack_office' AND table_name IN ('office_notice','office_notice_department','office_notice_read','office_notification','office_file','office_workbench_application','office_workbench_task','office_inbox_event','office_outbox_event','office_idempotency_record');")
audit_tables=$(audit_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='redblack_audit' AND table_name IN ('audit_operation_log','audit_inbox_event');")
office_migrations=$(office_query "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;")
audit_migrations=$(audit_query "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;")
file_storage_columns=$(office_query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='redblack_office' AND ((table_name='office_file' AND column_name IN ('storage_provider','bucket','etag','storage_status','cleanup_attempts','cleanup_next_attempt_at','cleanup_last_error')) OR (table_name='office_idempotency_record' AND column_name='file_id'));")
file_storage_constraints=$(office_query "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='redblack_office' AND constraint_name IN ('ck_office_file_storage_provider','ck_office_file_storage_status','fk_office_idempotency_file');")

[ "$office_tables" = "10" ] || { echo "Expected 10 office tables, got $office_tables" >&2; exit 1; }
[ "$audit_tables" = "2" ] || { echo "Expected 2 audit tables, got $audit_tables" >&2; exit 1; }
[ "$office_migrations" = "3" ] || { echo "Expected 3 office migrations, got $office_migrations" >&2; exit 1; }
[ "$audit_migrations" = "1" ] || { echo "Expected 1 audit migration, got $audit_migrations" >&2; exit 1; }
[ "$file_storage_columns" = "8" ] || { echo "Expected 8 file storage columns, got $file_storage_columns" >&2; exit 1; }
[ "$file_storage_constraints" = "3" ] || { echo "Expected 3 file storage constraints, got $file_storage_constraints" >&2; exit 1; }

redis_ping=$(docker compose exec -T redis redis-cli ping)
[ "$redis_ping" = "PONG" ] || { echo "Redis ping failed: $redis_ping" >&2; exit 1; }
docker compose exec -T kafka sh -c 'nc -z -w 3 127.0.0.1 9092'

if docker compose logs --since=1m office-service 2>&1 | grep -Eq 'BadSqlGrammarException|SQLSyntaxErrorException'; then
  echo "Office service still reports SQL grammar errors" >&2
  exit 1
fi

pending_total=""
inbox_unresolved=""
for attempt in $(seq 1 20); do
  identity_pending=$(identity_query "SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','SENDING');")
  approval_pending=$(approval_query "SELECT COUNT(*) FROM outbox_event WHERE status='PENDING';")
  office_pending=$(office_query "SELECT COUNT(*) FROM office_outbox_event WHERE status='PENDING';")
  office_unresolved=$(office_query "SELECT COUNT(*) FROM office_inbox_event WHERE status IN ('PENDING','DEAD');")
  audit_unresolved=$(audit_query "SELECT COUNT(*) FROM audit_inbox_event WHERE status IN ('PENDING','DEAD');")
  pending_total=$((identity_pending + approval_pending + office_pending))
  inbox_unresolved=$((office_unresolved + audit_unresolved))
  [ "$pending_total" -eq 0 ] && [ "$inbox_unresolved" -eq 0 ] && break
  [ "$attempt" -eq 20 ] && break
  sleep 2
done
[ "$pending_total" -eq 0 ] || { echo "Outbox pending events remain: $pending_total" >&2; exit 1; }
[ "$inbox_unresolved" -eq 0 ] || { echo "Inbox pending/dead events remain: $inbox_unresolved" >&2; exit 1; }

echo "PHASE4_INFRA_OK containers=9 office_tables=$office_tables audit_tables=$audit_tables file_storage_columns=$file_storage_columns redis=$redis_ping kafka=reachable outbox_pending=0 inbox_unresolved=0"
