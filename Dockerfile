FROM eclipse-temurin:21-jre-jammy

# Caminho do artefato JAR já pronto dentro do contexto de build Docker.
ARG GATEWAY_JAR=build/libs/gateway-sensor-1.0.0-obf.jar

# Metadados
LABEL org.opencontainers.image.title="AgentK Security Gateway"
LABEL org.opencontainers.image.description="Proxy de interceptacao TLS headless para analise de trafego de IA"
LABEL org.opencontainers.image.version="1.0.0"

# Instalar ferramentas necessárias:
#   - iptables: regras NAT para redirecionamento de tráfego
#   - openssl / ca-certificates: geração e confiança da CA
#   - keytool já vem no JRE (via eclipse-temurin)
#   - libpcap: pcap4j precisa da biblioteca nativa
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        iptables \
        openssl \
        ca-certificates \
        libpcap0.8 \
        curl \
    && rm -rf /var/lib/apt/lists/* \
    && update-alternatives --set iptables /usr/sbin/iptables-legacy \
    && update-alternatives --set ip6tables /usr/sbin/ip6tables-legacy

# Usuário de serviço (sem shell interativo = headless)
RUN groupadd -r gateway && useradd -r -g gateway -s /sbin/nologin gateway

WORKDIR /app

# Copiar JAR pré-compilado (sem executar Gradle durante o build da imagem)
COPY ${GATEWAY_JAR} app.jar

# Copiar configurações estáticas
COPY config/ config/
COPY inspection-terms.xml .
COPY monitored-sites.xml .

# Script de entrypoint: configura iptables → inicia gateway
COPY docker-entrypoint.sh .
RUN chmod +x docker-entrypoint.sh

# Diretórios para volumes (certs e keystore persistidos fora do container)
RUN mkdir -p /app/certs /app/keys && \
    chown -R gateway:gateway /app

# A CA e keystore serão montados via volume, não embarcados na imagem
VOLUME ["/app/certs", "/app/keys"]

# Porta do Gateway TLS interceptador
EXPOSE 8443
# Porta do Webhook de validação de prompts
EXPOSE 8080

# Não precisa de stdin/tty — headless
# PID 1 fica com o entrypoint para capturar sinais corretamente
ENTRYPOINT ["/app/docker-entrypoint.sh"]
