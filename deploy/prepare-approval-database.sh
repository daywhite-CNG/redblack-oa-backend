#!/bin/sh
set -eu

DEPLOY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENV_FILE="$DEPLOY_DIR/.env"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi

if ! grep -q '^APPROVAL_DB_USERNAME=' "$ENV_FILE"; then
  printf '\nAPPROVAL_DB_USERNAME=redblack_approval\n' >> "$ENV_FILE"
fi
if ! grep -q '^APPROVAL_DB_PASSWORD=' "$ENV_FILE"; then
  printf 'APPROVAL_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"

set -a
. "$ENV_FILE"
set +a

case "$APPROVAL_DB_USERNAME" in
  *[!A-Za-z0-9_]*|'')
    echo "APPROVAL_DB_USERNAME contains unsupported characters" >&2
    exit 1
    ;;
esac

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T mysql \
  mysql --protocol=socket -uroot --password="$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS redblack_approval
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${APPROVAL_DB_USERNAME}'@'%'
  IDENTIFIED BY '${APPROVAL_DB_PASSWORD}';
ALTER USER '${APPROVAL_DB_USERNAME}'@'%'
  IDENTIFIED BY '${APPROVAL_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON redblack_approval.* TO '${APPROVAL_DB_USERNAME}'@'%';
FLUSH PRIVILEGES;
SQL

echo "Approval database and service account are ready"
