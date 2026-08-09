#!/bin/sh
set -eu

DEPLOY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENV_FILE="$DEPLOY_DIR/.env"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"
compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

running=$(compose ps --services --status running | wc -l | tr -d ' ')
[ "$running" -eq 7 ]
[ "$(curl -fsS http://127.0.0.1/health | grep -c '"status":"UP"')" -eq 1 ]
[ "$(compose exec -T redis redis-cli ping | tr -d '\r')" = "PONG" ]

mysql_query() {
  compose exec -T mysql sh -c \
    'MYSQL_PWD="$APPROVAL_DB_PASSWORD" mysql -N -u"$APPROVAL_DB_USERNAME" redblack_approval -e "$1"' \
    sh "$1" | tr -d '\r'
}

tables=$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='redblack_approval' AND table_name IN ('leave_application','leave_application_attachment','approval_task','approval_record','approval_idempotency_record','outbox_event','leave_application_no_sequence');")
[ "$tables" -eq 7 ]
[ "$(mysql_query "SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1;")" -eq 1 ]

applications=$(mysql_query "SELECT COUNT(*) FROM leave_application;")
tasks=$(mysql_query "SELECT COUNT(*) FROM approval_task;")
records=$(mysql_query "SELECT COUNT(*) FROM approval_record;")
[ "$applications" -ge 4 ]
[ "$tasks" -ge 5 ]
[ "$records" -ge 12 ]

attempt=0
while [ "$attempt" -lt 20 ]; do
  pending=$(mysql_query "SELECT COUNT(*) FROM outbox_event WHERE status='PENDING';")
  [ "$pending" -eq 0 ] && break
  attempt=$((attempt + 1))
  sleep 2
done
[ "$pending" -eq 0 ]

approval_events=$(mysql_query "SELECT COUNT(*) FROM outbox_event WHERE topic='redblack.approval.events.v1' AND status='SENT';")
[ "$approval_events" -ge 8 ]
[ "$(compose exec -T redis redis-cli exists redblack:identity:authorization:10003 | tr -d '\r')" -eq 1 ]

kafka_output=/tmp/redblack-phase3-kafka-events.jsonl
rm -f "$kafka_output"
timeout 40 docker run --rm --network redblack-oa_backend edenhill/kcat:1.7.1 \
  -b kafka:9092 -t redblack.approval.events.v1 -C -o beginning -c "$approval_events" -q \
  > "$kafka_output"
for event_type in LEAVE_SUBMITTED LEAVE_APPROVED LEAVE_REJECTED LEAVE_WITHDRAWN TASK_TRANSFERRED; do
  grep -Eq "\"eventType\"[[:space:]]*:[[:space:]]*\"$event_type\"" "$kafka_output"
done
consumed=$(wc -l < "$kafka_output" | tr -d ' ')
rm -f "$kafka_output"

echo "PHASE3_INFRA_OK containers=$running tables=$tables applications=$applications tasks=$tasks records=$records approvalEvents=$approval_events consumed=$consumed"
