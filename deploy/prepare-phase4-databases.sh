#!/bin/sh
set -eu

DEPLOY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENV_FILE="$DEPLOY_DIR/.env"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi

ensure_value() {
  name=$1
  default_value=$2
  if ! grep -q "^${name}=" "$ENV_FILE"; then
    printf '%s=%s\n' "$name" "$default_value" >> "$ENV_FILE"
  fi
}

ensure_value OFFICE_DB_USERNAME redblack_office
ensure_value OFFICE_DB_PASSWORD "$(openssl rand -hex 24)"
ensure_value AUDIT_DB_USERNAME redblack_audit
ensure_value AUDIT_DB_PASSWORD "$(openssl rand -hex 24)"
chmod 600 "$ENV_FILE"

set -a
. "$ENV_FILE"
set +a

case "$OFFICE_DB_USERNAME:$AUDIT_DB_USERNAME" in
  *[!A-Za-z0-9_:]*|'') echo "Database usernames contain unsupported characters" >&2; exit 1 ;;
esac

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T mysql \
  mysql --protocol=socket -uroot --password="$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS redblack_office CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS redblack_audit CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${OFFICE_DB_USERNAME}'@'%' IDENTIFIED BY '${OFFICE_DB_PASSWORD}';
ALTER USER '${OFFICE_DB_USERNAME}'@'%' IDENTIFIED BY '${OFFICE_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON redblack_office.* TO '${OFFICE_DB_USERNAME}'@'%';
CREATE USER IF NOT EXISTS '${AUDIT_DB_USERNAME}'@'%' IDENTIFIED BY '${AUDIT_DB_PASSWORD}';
ALTER USER '${AUDIT_DB_USERNAME}'@'%' IDENTIFIED BY '${AUDIT_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON redblack_audit.* TO '${AUDIT_DB_USERNAME}'@'%';
FLUSH PRIVILEGES;
SQL

echo "Office and audit databases and service accounts are ready"
