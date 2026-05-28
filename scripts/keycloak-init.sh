#!/bin/sh
# keycloak-init.sh — Injeta credenciais no realm antes de importar e inicia o Keycloak.
# Executado como entrypoint do container keycloak (user: root).

set -e

REALM_SRC="/tmp/realm-agentk.json"
REALM_DST="/opt/keycloak/data/import/realm-agentk.json"

mkdir -p /opt/keycloak/data/import

# Substitui os marcadores pelas credenciais reais vindas das variáveis de ambiente.
# Usa @ como delimitador para evitar conflito com / nos valores das credenciais.
sed \
  -e "s@__GOOGLE_CLIENT_ID__@${GOOGLE_CLIENT_ID}@g" \
  -e "s@__GOOGLE_CLIENT_SECRET__@${GOOGLE_CLIENT_SECRET}@g" \
  "$REALM_SRC" > "$REALM_DST"

echo "[keycloak-init] Realm gerado em $REALM_DST"
echo "[keycloak-init] Google Client ID: ${GOOGLE_CLIENT_ID:0:20}..."

exec /opt/keycloak/bin/kc.sh start-dev --import-realm --verbose
