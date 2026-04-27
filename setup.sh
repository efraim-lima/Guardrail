#!/bin/bash

#############################################################################
# setup.sh
# 
# Script para:
#   1. Detectar IP atual da maquina e gravar no .env automaticamente
#   2. Gerar certificado SSL auto-assinado para o Nginx (com SAN de IP)
#   3. Registrar entrada DNS local em /etc/hosts
#
# O IP e detectado a cada execucao. Se mudar, o certificado e regenerado
# automaticamente com o novo IP nos SANs.
#############################################################################

set -e

# Cores para output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

# Configuracoes
CERTS_DIR="./nginx/certs"
CERT_FILE="${CERTS_DIR}/agentk.crt"
KEY_FILE="${CERTS_DIR}/agentk.key"
CERT_DAYS=365
CERT_CN="agentk.local"
SKIP_HOSTS_ENTRY="${SKIP_HOSTS_ENTRY:-0}"
HOSTS_FILE="/etc/hosts"
ENV_FILE=".env"

# ---------------------------------------------------------------------------
# Detecta o IP da maquina pela rota padrao
# ---------------------------------------------------------------------------
resolve_agentk_host_ip() {
    local detected_ip=""

    if command -v ip >/dev/null 2>&1; then
        detected_ip="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") {print $(i+1); exit}}')"
    fi

    if [[ -z "$detected_ip" ]] && command -v hostname >/dev/null 2>&1; then
        detected_ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi

    echo "${detected_ip:-127.0.0.1}"
}

# ---------------------------------------------------------------------------
# Upsert de chave=valor no .env (cria o arquivo se nao existir)
# ---------------------------------------------------------------------------
upsert_env() {
    local key="$1" value="$2"
    if [[ ! -f "$ENV_FILE" ]]; then
        [[ -f env.example ]] && cp env.example "$ENV_FILE" || touch "$ENV_FILE"
    fi
    if grep -qE "^${key}=" "$ENV_FILE" 2>/dev/null; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
    else
        echo "${key}=${value}" >> "$ENV_FILE"
    fi
}

# ---------------------------------------------------------------------------
# Deteccao de IP e sincronizacao com .env
# Invalida o certificado se o IP mudou desde a ultima geracao.
# ---------------------------------------------------------------------------
sync_env_ip() {
    local detected_ip
    detected_ip="$(resolve_agentk_host_ip)"

    # Le o IP anterior gravado no .env (se existir)
    local previous_ip=""
    if [[ -f "$ENV_FILE" ]]; then
        previous_ip="$(grep -E '^AGENTK_HOST_IP=' "$ENV_FILE" 2>/dev/null | cut -d= -f2 | tr -d '"' || true)"
    fi

    # Grava o IP detectado no .env
    upsert_env "AGENTK_HOST_IP" "$detected_ip"
    upsert_env "KC_HOSTNAME_ADMIN_URL" "https://${detected_ip}/keycloak"

    log_info "IP detectado automaticamente: ${detected_ip}"
    log_success "AGENTK_HOST_IP=${detected_ip} gravado em ${ENV_FILE}"
    log_success "KC_HOSTNAME_ADMIN_URL=https://${detected_ip}/keycloak gravado em ${ENV_FILE}"

    # Se o IP mudou e o certificado ja existe, remove para forcar regeracao
    # (o cert antigo nao teria o novo IP nos SANs)
    if [[ -n "$previous_ip" && "$previous_ip" != "$detected_ip" && -f "$CERT_FILE" ]]; then
        log_warn "IP mudou (${previous_ip} -> ${detected_ip}): removendo certificado antigo para regeracao..."
        rm -f "$CERT_FILE" "$KEY_FILE"
        log_warn "Certificado removido. Sera regerado com o novo IP nos SANs."
    fi

    # Exporta para uso no restante do script
    export AGENTK_HOST_IP="$detected_ip"
}

upsert_hosts_entry() {
    local entry="$1"

    if [[ $EUID -ne 0 ]]; then
        log_info "Permissao de root necessaria para editar $HOSTS_FILE"
        log_info "Executando atualizacao via sudo"
        sudo sed -i '/[[:space:]]agentk\.local\([[:space:]]\|$\)/d' "$HOSTS_FILE"
        echo "$entry" | sudo tee -a "$HOSTS_FILE" > /dev/null
    else
        sed -i '/[[:space:]]agentk\.local\([[:space:]]\|$\)/d' "$HOSTS_FILE"
        echo "$entry" >> "$HOSTS_FILE"
    fi
}

setup_hosts_entry() {
    if [[ "$SKIP_HOSTS_ENTRY" == "1" ]]; then
        log_info "SKIP_HOSTS_ENTRY=1 detectado: etapa de /etc/hosts foi ignorada."
        return 0
    fi

    local hosts_entry="${AGENTK_HOST_IP} agentk.local"

    log_info "Configurando entrada DNS local em $HOSTS_FILE..."

    if grep -qF "$hosts_entry" "$HOSTS_FILE" 2>/dev/null; then
        log_success "Entrada ja existe: $hosts_entry"
        return 0
    fi

    upsert_hosts_entry "$hosts_entry"
    log_success "Entrada adicionada: $hosts_entry"
}

main() {
    echo ""
    log_info "╔═════════════════════════════════════════════╗"
    log_info "║  Gerador de Certificado SSL Auto-assinado  ║"
    log_info "║  para Nginx                                 ║"
    log_info "╚═════════════════════════════════════════════╝"
    echo ""

    # ETAPA 0: Detectar IP e sincronizar .env (sempre, a cada execucao)
    sync_env_ip
    echo ""

    # Criar diretorio para certificados
    if [[ ! -d "$CERTS_DIR" ]]; then
        log_info "Criando diretório de certificados: $CERTS_DIR"
        mkdir -p "$CERTS_DIR"
        log_success "Diretório criado"
    else
        log_success "Diretório $CERTS_DIR já existe"
    fi
    
    # Verificar se certificado ja existe
    if [[ -f "$CERT_FILE" && -f "$KEY_FILE" ]]; then
        log_success "Certificado ja existe:"
        log_success "  Certificado: $CERT_FILE"
        log_success "  Chave privada: $KEY_FILE"
        setup_hosts_entry
        echo ""
        return 0
    fi
    
    # Gerar certificado auto-assinado com SAN (Subject Alternative Names).
    # Browsers modernos ignoram o CN e exigem SAN -- sem SAN o acesso via IP
    # gera erro NET::ERR_CERT_COMMON_NAME_INVALID mesmo com CN correto.
    log_info "Gerando certificado SSL auto-assinado..."
    log_info "  CN (Common Name): $CERT_CN"
    log_info "  IP detectado    : $AGENTK_HOST_IP"
    log_info "  Validade: $CERT_DAYS dias"
    log_info "  Algoritmo: RSA 2048 bits"
    echo ""

    local host_ip="$AGENTK_HOST_IP"

    # Arquivo de extensoes SAN temporario
    local san_cfg
    san_cfg="$(mktemp)"
    cat > "$san_cfg" <<EOF
[req]
distinguished_name = req_distinguished_name
x509_extensions    = v3_req
prompt             = no

[req_distinguished_name]
CN = ${CERT_CN}

[v3_req]
subjectAltName = @alt_names
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[alt_names]
DNS.1 = ${CERT_CN}
DNS.2 = localhost
IP.1  = ${host_ip}
IP.2  = 127.0.0.1
EOF

    openssl req -x509 -nodes -days "$CERT_DAYS" -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -config "$san_cfg" \
        -extensions v3_req 2>/dev/null

    rm -f "$san_cfg"
    
    log_success "Certificado gerado com sucesso"
    log_success "  Certificado: $CERT_FILE"
    log_success "  Chave privada: $KEY_FILE"
    echo ""
    
    # Exibir informações do certificado
    log_info "Informações do certificado:"
    openssl x509 -in "$CERT_FILE" -text -noout | grep -E "Subject:|Issuer:|Not Before|Not After|Public-Key:"
    echo ""
    
    # Registrar entrada DNS local no /etc/hosts
    setup_hosts_entry
    echo ""

    log_success "Setup concluído!"
    echo ""
}

main "$@"
exit $?
