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
  -e "s@__K8S_ADMIN_PASSWORD__@${K8S_ADMIN_PASSWORD:-changeme}@g" \
  "$REALM_SRC" > "$REALM_DST"

echo "[keycloak-init] Realm gerado em $REALM_DST"
echo "[keycloak-init] Google Client ID: ${GOOGLE_CLIENT_ID:0:20}..."

start_keycloak() {
  /opt/keycloak/bin/kc.sh start-dev --import-realm --verbose &
  KC_PID="$!"
}

wait_for_admin_cli() {
  admin_user="${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}"
  admin_pass="${KC_BOOTSTRAP_ADMIN_PASSWORD:-admin}"
  attempt=0

  while [ "$attempt" -lt 60 ]; do
    if /opt/keycloak/bin/kcadm.sh config credentials \
      --server http://localhost:8080/keycloak \
      --realm master \
      --user "$admin_user" \
      --password "$admin_pass" >/tmp/kcadm-bootstrap.log 2>&1; then
      return 0
    fi

    attempt=$((attempt + 1))
    sleep 5
  done

  cat /tmp/kcadm-bootstrap.log >&2 || true
  return 1
}

apply_realm_event_settings() {
  /opt/keycloak/bin/kcadm.sh update realms/agentk \
    -s eventsEnabled=true \
    -s adminEventsEnabled=true \
    -s adminEventsDetailsEnabled=true \
    -s eventsExpiration=604800 \
    -s 'eventsListeners=["jboss-logging","http-sender"]' \
    -s 'enabledEventTypes=["LOGIN","LOGIN_ERROR","LOGOUT","LOGOUT_ERROR","REGISTER","REGISTER_ERROR","CODE_TO_TOKEN","CODE_TO_TOKEN_ERROR","CLIENT_LOGIN","CLIENT_LOGIN_ERROR","TOKEN_EXCHANGE","TOKEN_EXCHANGE_ERROR","IDENTITY_PROVIDER_LOGIN","IDENTITY_PROVIDER_LOGIN_ERROR","IDENTITY_PROVIDER_FIRST_LOGIN","IDENTITY_PROVIDER_FIRST_LOGIN_ERROR"]'
}

trap 'kill "$KC_PID" 2>/dev/null || true' INT TERM

start_keycloak
wait_for_admin_cli
apply_realm_event_settings

wait "$KC_PID"
