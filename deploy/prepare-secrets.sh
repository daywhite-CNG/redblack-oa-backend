#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SECRETS_DIR="$SCRIPT_DIR/secrets"

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this script with sudo so the private key can be restricted to the application user." >&2
  exit 1
fi

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"

if [ ! -s "$SECRETS_DIR/jwt-private.pem" ]; then
  umask 077
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$SECRETS_DIR/jwt-private.pem"
fi

openssl pkey -in "$SECRETS_DIR/jwt-private.pem" -pubout -out "$SECRETS_DIR/jwt-public.pem"
chown root:10001 "$SECRETS_DIR/jwt-private.pem"
chmod 640 "$SECRETS_DIR/jwt-private.pem"
chmod 644 "$SECRETS_DIR/jwt-public.pem"

echo "JWT key pair is ready in $SECRETS_DIR"
