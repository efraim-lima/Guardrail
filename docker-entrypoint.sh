#!/bin/bash
# =============================================================================
# docker-entrypoint.sh
#
# Responsabilidades:
#   1. Gera CA e keystore se não existirem (primeiro boot)
#   2. Instala CA no trust store interno do container
#   3. Configura regras iptables (NAT 443 → GATEWAY_MITM_PORT)
#   4. Inicia o Gateway Java em modo headless (exec → PID 1)
#
# Este script precisa rodar como root (cap_add: NET_ADMIN no compose).
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Variáveis (todas com fallback seguro)
# ---------------------------------------------------------------------------
GATEWAY_MITM_PORT="${GATEWAY_MITM_PORT:-8443}"
TARGET_PORT="${TARGET_PORT:-443}"
WEBHOOK_PORT="${WEBHOOK_PORT:-8080}"
WEBHOOK_PATH="${WEBHOOK_PATH:-/validar}"

CA_CERT_PATH="${CA_CERT_PATH:-/app/certs/gateway-ca.crt}"
CA_KEY_PATH="${CA_KEY_PATH:-/app/keys/gateway-ca.key}"

KEYSTORE_FILE="${KEYSTORE_FILE:-/app/keys/gateway-keystore.p12}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-gateway-secret}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-agentk-gateway}"
KEYSTORE_DNAME="CN=api.openai.com, O=AgentK Security, L=Sao Paulo, C=BR"

log()  { echo "[Entrypoint] $*"; }
warn() { echo "[Entrypoint][WARN] $*" >&2; }
err()  { echo "[Entrypoint][ERROR] $*" >&2; }

# ---------------------------------------------------------------------------
# ETAPA 1 — Gerar CA se não existir (volume persistente)
# ---------------------------------------------------------------------------
setup_ca() {
    if [[ -f "$CA_CERT_PATH" && -f "$CA_KEY_PATH" ]]; then
        log "CA já existe — pulando geração."
        return 0
    fi

    log "Gerando CA (RSA 4096, 10 anos)..."
    mkdir -p "$(dirname "$CA_CERT_PATH")" "$(dirname "$CA_KEY_PATH")"

    openssl genrsa -out "$CA_KEY_PATH" 4096 2>/dev/null
    openssl req -x509 -new -nodes \
        -key "$CA_KEY_PATH" \
        -sha256 -days 3650 \
        -out "$CA_CERT_PATH" \
        -subj "/CN=AgentK Security Gateway CA" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

    chmod 600 "$CA_KEY_PATH"
    chmod 644 "$CA_CERT_PATH"
    log "CA gerada: $CA_CERT_PATH"
}

# ---------------------------------------------------------------------------
# ETAPA 2 — Gerar keystore PKCS12 se não existir
# ---------------------------------------------------------------------------
setup_keystore() {
    if [[ -f "$KEYSTORE_FILE" ]]; then
        log "Keystore já existe — pulando geração."
        return 0
    fi

    log "Gerando keystore PKCS12 (RSA 2048)..."
    mkdir -p "$(dirname "$KEYSTORE_FILE")"

    keytool -genkeypair \
        -alias "$KEYSTORE_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -storetype PKCS12 \
        -keystore "$KEYSTORE_FILE" \
        -validity 3650 \
        -storepass "$KEYSTORE_PASSWORD" \
        -dname "$KEYSTORE_DNAME" \
        -noprompt 2>/dev/null

    chmod 600 "$KEYSTORE_FILE"
    log "Keystore gerado: $KEYSTORE_FILE"
}

# ---------------------------------------------------------------------------
# ETAPA 3 — Adicionar CA ao trust store interno do container
# ---------------------------------------------------------------------------
trust_ca() {
    if [[ ! -f "$CA_CERT_PATH" ]]; then
        warn "CA não encontrada; trust store não atualizado."
        return 0
    fi

    local dest="/usr/local/share/ca-certificates/gateway-ca.crt"
    if [[ -f "$dest" ]]; then
        log "CA já confiada no container."
        return 0
    fi

    mkdir -p /usr/local/share/ca-certificates
    cp "$CA_CERT_PATH" "$dest"
    update-ca-certificates --fresh > /dev/null 2>&1 || true
    log "CA adicionada ao trust store do container."
}

# ---------------------------------------------------------------------------
# ETAPA 4 — Configurar iptables (NAT: porta 443 → GATEWAY_MITM_PORT)
# ---------------------------------------------------------------------------
setup_iptables() {
    # Verifica se iptables está disponível (modo network_mode: host exige NET_ADMIN)
    if ! command -v iptables &>/dev/null; then
        warn "iptables não encontrado. Redirecionamento de rede não configurado."
        return 0
    fi

    # Verifica se as regras já existem antes de adicionar (idempotente)
    if iptables -t nat -C PREROUTING -p tcp --dport "$TARGET_PORT" \
            -j REDIRECT --to-port "$GATEWAY_MITM_PORT" 2>/dev/null; then
        log "Regra PREROUTING já existe ($TARGET_PORT → $GATEWAY_MITM_PORT)."
    else
        iptables -t nat -A PREROUTING -p tcp --dport "$TARGET_PORT" \
            -j REDIRECT --to-port "$GATEWAY_MITM_PORT"
        log "Regra PREROUTING criada: :$TARGET_PORT → :$GATEWAY_MITM_PORT"
    fi

    if iptables -t nat -C OUTPUT -p tcp --dport "$TARGET_PORT" \
            -j REDIRECT --to-port "$GATEWAY_MITM_PORT" 2>/dev/null; then
        log "Regra OUTPUT já existe."
    else
        iptables -t nat -A OUTPUT -p tcp --dport "$TARGET_PORT" \
            -j REDIRECT --to-port "$GATEWAY_MITM_PORT" 2>/dev/null || true
        log "Regra OUTPUT criada."
    fi
}

# ---------------------------------------------------------------------------
# ETAPA 5 — Iniciar Gateway Java (headless, exec → torna PID 1)
# ---------------------------------------------------------------------------
start_gateway() {
    log "==========================================="
    log "Iniciando AgentK Security Gateway"
    log "  MITM port : $GATEWAY_MITM_PORT"
    log "  Webhook   : $WEBHOOK_PORT$WEBHOOK_PATH"
    log "  CA cert   : $CA_CERT_PATH"
    log "  Keystore  : $KEYSTORE_FILE"
    log "==========================================="

    # exec substitui o shell → Java recebe sinais SIGTERM/SIGINT corretamente
    exec java \
        -Djava.security.egd=file:/dev/./urandom \
        -Dfile.encoding=UTF-8 \
        -Dgateway.ca.cert="$CA_CERT_PATH" \
        -Dgateway.ca.key="$CA_KEY_PATH" \
        -Dgateway.keystore.file="$KEYSTORE_FILE" \
        -Dgateway.keystore.password="$KEYSTORE_PASSWORD" \
        -Dgateway.keystore.alias="$KEYSTORE_ALIAS" \
        -Dgateway.mitm.port="$GATEWAY_MITM_PORT" \
        -Dwebhook.port="$WEBHOOK_PORT" \
        -Dwebhook.path="$WEBHOOK_PATH" \
        -jar /app/app.jar
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
log "=== AgentK Security Gateway — Entrypoint iniciando ==="

setup_ca
setup_keystore
trust_ca
setup_iptables
start_gateway
