# Registro de Alterações (Changelog)

## [2026-04-27] - Consolidação da Camada de Orquestração e Segurança Dinâmica

### Arquivos Modificados:
- `setup.sh`: Transformado no orquestrador principal (Regente) do ecossistema Guardrail.
- `nginx/nginx.conf`: Atualizado para refletir o novo fluxo de configuração e reforçar instruções de segurança.
- `docker-compose.yaml`: Habilitado `KC_HEALTH_ENABLED=true` e corrigido healthcheck do Keycloak para usar `/dev/tcp` (independente de `curl`).
- `start.sh`: [REMOVIDO] Funções absorvidas pelo `setup.sh`.

### Descrição Técnica:
Implementação de uma arquitetura de orquestração unificada no arquivo `setup.sh`, integrando a detecção dinâmica de infraestrutura de rede (IP) com o ciclo de vida dos containers Docker Compose. A modificação introduziu um sistema de implantação em quatro fases (Infraestrutura, Identidade, Configuração Interativa e Segurança), garantindo que dependências críticas como o `OAUTH2_PROXY_CLIENT_SECRET` sejam validadas e coletadas de forma síncrona antes da ativação do Proxy Reverso (Nginx).

### Justificativa:
A fragmentação da lógica de inicialização entre `setup.sh` e `start.sh` gerava inconsistências na detecção de IPs dinâmicos e na validade dos certificados SSL. Ao centralizar a lógica, assegura-se que:
1. O certificado SSL sempre contenha o IP atual da máquina virtual nos campos SAN (Subject Alternative Names).
2. O fluxo de provisionamento do Keycloak seja bloqueante, impedindo que o acesso à aplicação seja exposto sem a devida camada de autenticação OAuth2 configurada.
3. A experiência do desenvolvedor seja simplificada para um único ponto de entrada (`bash setup.sh`).

---

## 26 de Abril de 2026 - Detecção Automática de IP e Sincronização do `.env` no `setup.sh`

### Arquivos Modificados

- **`setup.sh`** — Adicionadas funções `upsert_env` e `sync_env_ip`; removida variável `AGENTK_HOST_IP` do bloco estático de configuração; `resolve_agentk_host_ip` simplificada (sem fallback manual); `main()` invoca `sync_env_ip` como primeira etapa.

### Descrição

A cada execução do `setup.sh`, o IP atual da máquina é detectado automaticamente e gravado no `.env` sem intervenção manual. Isso resolve o problema de ambientes com IP dinâmico (DHCP, reinicialização de VM, troca de rede).

### Comportamento

1. **Detecção**: `ip route get 1.1.1.1` (primeiro) → `hostname -I` (fallback) → `127.0.0.1` (último recurso).
2. **Gravação no `.env`** via `upsert_env` (cria o arquivo se não existir; atualiza se a chave já existir):
   - `AGENTK_HOST_IP=<ip detectado>`
   - `KC_HOSTNAME_ADMIN_URL=https://<ip detectado>/keycloak`
3. **Invalidação automática do certificado**: se o IP mudou desde a última execução (comparado com `AGENTK_HOST_IP` anterior no `.env`), o certificado SSL é removido e regerado com o novo IP nos `subjectAltNames`. Sem isso, o browser rejeitaria o certificado por mismatch de IP.

---

## 26 de Abril de 2026 - nginx independente do oauth2-proxy + profile 'secure'

### Arquivos Modificados

- **`docker-compose.yaml`** — Adicionado `profiles: [secure]` ao `oauth2-proxy`; removida dependência do `nginx` no `oauth2-proxy`.
- **`nginx/nginx.conf`** — Adicionada página de erro customizada `@auth_unavailable` (503) exibida quando o `oauth2-proxy` não está ativo, com link direto para `/keycloak/admin/`.

### Causa-Raiz

Dois problemas combinados impediam o acesso ao painel admin do Keycloak:

1. **nginx não subia porque dependia do oauth2-proxy**: Com `depends_on oauth2-proxy: service_started`, o nginx aguardava o oauth2-proxy iniciar. Como o oauth2-proxy tem `profiles: [secure]` (adicionado nesta entrega), ele não subia no `docker compose up` padrão — logo, o nginx também não subia, bloqueando `/keycloak/admin/`.
2. **`docker compose up` subia o oauth2-proxy antes do setup interativo**: Sem o `profiles: [secure]`, qualquer `docker compose up` iniciava o oauth2-proxy imediatamente, antes que o `start.sh` tivesse a chance de coletar o Client Secret. O oauth2-proxy falhava ao tentar validar o JWKS com secret incorreto.

### Solução Aplicada

- **`profiles: [secure]`** no oauth2-proxy: exclui o serviço do `docker compose up` padrão. Só é iniciado quando explicitamente solicitado (`docker compose up -d oauth2-proxy`) ou via `start.sh` fase 4. O Docker Compose permite iniciar serviços com profile por nome explícito, então `start.sh` continua funcionando sem alteração.
- **nginx sem `depends_on oauth2-proxy`**: nginx sobe sempre, independentemente do oauth2-proxy. O resultado é:
  - `/keycloak/` → keycloak direto (**sempre acessível**)
  - `/` → oauth2-proxy → se down, nginx retorna página 503 customizada
- **Página `@auth_unavailable`**: exibida no `location /` quando oauth2-proxy retorna 502/503/504. Informa o usuário que o setup está pendente, mostra o link para `/keycloak/admin/` e auto-atualiza a cada 10s.

---

## 26 de Abril de 2026 - Startup em 4 Fases com Pausa Interativa (`start.sh`)

### Arquivos Modificados

- **`start.sh`** — Reescrito com orquestração em 4 fases e pausa interativa entre as fases 3 e 4.
- **`env.example`** — Atualizado com todas as variáveis necessárias para a stack completa.

### Descrição

O Docker Compose é declarativo e não suporta pausas interativas entre serviços. Para garantir que a camada de autenticação só seja ativada após o Keycloak estar configurável e com ao menos um usuário criado, o `start.sh` foi reescrito para encapsular o compose em fases sequenciais.

### Fluxo de Startup

```
FASE 1: docker compose up -d agentk-gateway agentk-server agentk-client ollama
   |
FASE 2: docker compose up -d keycloak
        wait_for_healthy(keycloak)
   |
FASE 3: [PAUSA INTERATIVA]
        - Mostra URL: http://localhost:8082/keycloak/admin/
        - Instrui o usuario a: criar usuario no realm 'agentk'
        - Solicita o Client Secret (ENTER = usa padrao 'oauth2-proxy-secret')
        - Grava OAUTH2_PROXY_CLIENT_SECRET no .env
        - Aguarda ENTER para continuar
   |
FASE 4: docker compose up -d oauth2-proxy nginx
        (a partir daqui, TODO acesso exige login Keycloak)
```

### Por que nao usar Docker Compose puro

O Docker Compose não oferece mecanismo nativo para pausar a execução e aguardar input do usuário. `healthcheck` + `depends_on` garantem ordem de inicialização entre containers, mas não permitem interação humana intermediária. O `start.sh` é a camada correta para esta lógica.

---

## 26 de Abril de 2026 - Correção de Startup do oauth2-proxy: Realm Auto-Provisionado e Healthcheck do Keycloak

### Arquivos Modificados

- **`config/keycloak/realm-agentk.json`** *(novo)* — Import do realm `agentk` com o client `oauth2-proxy` pré-configurado.
- **`docker-compose.yaml`** — Adicionado `--import-realm` ao Keycloak; healthcheck ao Keycloak via `/keycloak/health/ready`; `oauth2-proxy` atualizado para aguardar `keycloak: service_healthy`; healthcheck do oauth2-proxy removido; nginx atualizado para `service_started`.

### Descrição

O `oauth2-proxy` falhava com "unhealthy" por três problemas encadeados que impediam o serviço de inicializar.

### Causa-Raiz

1. **`wget` inexistente na imagem distroless**: A imagem `quay.io/oauth2-proxy/oauth2-proxy:latest` é baseada em `gcr.io/distroless/static:nonroot`, que não contém shell, `wget`, `curl` ou qualquer utilitário. O healthcheck `CMD wget ...` falhava imediatamente, marcando o container como `unhealthy`.
2. **Realm `agentk` não existia no Keycloak**: O `oauth2-proxy` tenta buscar o JWKS URL durante a inicialização. Como o realm não havia sido criado, recebia 404 e crashava.
3. **`depends_on` sem `service_healthy`**: O `oauth2-proxy` iniciava antes do Keycloak estar pronto para processar requisições OIDC.

### Solução Aplicada

- **`config/keycloak/realm-agentk.json`**: Realm `agentk` e client `oauth2-proxy` provisionados automaticamente no primeiro boot via `--import-realm`. Secret padrão: `oauth2-proxy-secret`.
- **Keycloak `healthcheck`**: Verifica `/keycloak/health/ready` com `curl`. Container só fica `healthy` após o Keycloak estar pronto e o realm importado.
- **`oauth2-proxy depends_on keycloak: service_healthy`**: Garante order correta de inicialização.
- **Healthcheck do oauth2-proxy removido**: Inviável em imagem distroless. Resiliência via `restart: on-failure`.

### Credenciais Padrão

Secret padrão: `oauth2-proxy-secret`. Para alterar em produção, edite `config/keycloak/realm-agentk.json` **antes** do primeiro `docker compose up` e defina `OAUTH2_PROXY_CLIENT_SECRET` no `.env`.

---

## 26 de Abril de 2026 - Correção de Acesso ao Keycloak via nginx (KC_HTTP_RELATIVE_PATH)

### Arquivos Modificados

- **`nginx/nginx.conf`** — Removida a `/` final do `proxy_pass` no `location /keycloak/`.
- **`docker-compose.yaml`** — Adicionado `KC_HTTP_RELATIVE_PATH=/keycloak`; substituído `KC_PROXY=edge` (deprecado) por `KC_PROXY_HEADERS=xforwarded` + `KC_HTTP_ENABLED=true`; URLs internas do `oauth2-proxy` atualizadas com o prefixo `/keycloak/`.

### Descrição

O Keycloak ficava inacessível via `https://agentk.local/keycloak/` por três problemas combinados que resultavam em 404 ou redirect loop.

### Causa-Raiz

1. **`proxy_pass http://keycloak:8080/` com `/` final**: O nginx removia o prefixo `/keycloak/` antes de repassar a requisição. A requisição `/keycloak/admin/` chegava ao container como `/admin/`. Sem `KC_HTTP_RELATIVE_PATH` configurado, o Keycloak não tinha endpoints registrados sob `/keycloak/` e retornava 404 para a maioria dos recursos. Recursos estáticos gerados pelo Keycloak ainda referenciavam caminhos raiz, quebrando a navegação.
2. **`KC_HTTP_RELATIVE_PATH` ausente**: O Keycloak precisa desta variável para registrar todos os seus servlets e gerar links internos (CSS, JS, redirects OIDC) usando o prefixo `/keycloak/`. Sem ela, o contexto HTTP do Keycloak ficava na raiz `/`, incompatível com o roteamento do nginx.
3. **`KC_PROXY=edge` deprecado no Keycloak 24**: Este modo foi removido/depreciado na versão 24. A configuração equivalente e suportada é `KC_PROXY_HEADERS=xforwarded` (confia nos headers `X-Forwarded-*`) + `KC_HTTP_ENABLED=true` (permite receber HTTP do nginx, uma vez que TLS é terminado pelo nginx). A ausência desta configuração correta impedia que o Keycloak construísse URLs com `https://` a partir das requisições HTTP internas do nginx.

### Solução Aplicada

- **`nginx/nginx.conf`**: Removida a `/` final do `proxy_pass` em `location /keycloak/`. Sem a barra final, o nginx preserva o path completo — `/keycloak/admin/` chega ao container como `/keycloak/admin/`, consistente com `KC_HTTP_RELATIVE_PATH=/keycloak`.
- **`docker-compose.yaml` (Keycloak)**: Adicionado `KC_HTTP_RELATIVE_PATH=/keycloak`; `KC_PROXY=edge` substituído por `KC_PROXY_HEADERS=xforwarded` + `KC_HTTP_ENABLED=true`.
- **`docker-compose.yaml` (oauth2-proxy)**: URLs internas `--redeem-url`, `--oidc-jwks-url` e `--backend-logout-url` atualizadas para incluir o prefixo `/keycloak/`, pois agora o Keycloak escuta sob esse caminho internamente também.

---

## 26 de Abril de 2026 - Correção de Dependência nginx → oauth2-proxy e Healthcheck

### Arquivos Modificados

- **`docker-compose.yaml`** — Adicionado `healthcheck` ao serviço `oauth2-proxy` (endpoint `/ping`); serviço `nginx` atualizado para depender de `oauth2-proxy` com condição `service_healthy`.

### Descrição

O nginx iniciava sem garantia de que o oauth2-proxy estava operacional, tornando o comportamento do proxy indefinido durante o boot. Sem um healthcheck, o Docker Compose não tinha como saber se o oauth2-proxy estava pronto para receber conexões antes de direcionar tráfego a ele via nginx.

### Causa-Raiz

A ausência de `depends_on` entre `nginx` e `oauth2-proxy` permitia que o nginx subisse antes do upstream de autenticação estar disponível. Container antigos com porta `8502` exposta do `agentk-client` também permaneciam ativos após o `up -d` sem `--force-recreate`, mantendo o bypass de autenticação.

### Solução Aplicada

- **Healthcheck no oauth2-proxy**: usa o endpoint nativo `/ping` do oauth2-proxy (retorna `200 OK` quando o processo está pronto para receber conexões).
- **`nginx` depende de `oauth2-proxy: service_healthy`**: garante que o nginx só inicia após o oauth2-proxy estar respondendo, eliminando a janela de tempo em que o upstream de autenticação estaria ausente.

---

## 26 de Abril de 2026 - Correção de Bypass de Autenticação (CWE-284: Broken Access Control)

### Arquivos Modificados

- **`docker-compose.yaml`** — Removida a diretiva `ports` do serviço `agentk-client`; porta do `oauth2-proxy` restringida a `127.0.0.1:4180`.

### Descrição

O serviço `agentk-client` era acessível diretamente via `http://<host>:8502`, contornando completamente o nginx e o oauth2-proxy. Qualquer usuário com acesso de rede à máquina conseguia usar a interface do AgentK sem autenticação — tornando o Keycloak e o oauth2-proxy ineficazes.

### Causa-Raiz

A diretiva `ports: ["0.0.0.0:8502:8501"]` no serviço `agentk-client` publicava a porta do Streamlit diretamente no host. Embora o fluxo oficial de autenticação passe por `nginx → oauth2-proxy → agentk-client`, a exposição direta da porta criava um caminho alternativo sem nenhuma verificação de identidade (OWASP A01:2021 — Broken Access Control).

O mesmo problema existia no `oauth2-proxy`: exposto em `0.0.0.0:4180` sem TLS, era acessível diretamente sem a camada de criptografia do nginx.

### Solução Aplicada

- **`agentk-client`**: Bloco `ports` removido integralmente. O container permanece alcançável apenas dentro da rede `agentk-network`, exclusivamente pelo `oauth2-proxy`. O único ponto de entrada externo é o nginx na porta 443.
- **`oauth2-proxy`**: Porta restringida a `127.0.0.1:4180` (loopback apenas), mantendo a possibilidade de debug local sem expor o serviço externamente sem TLS.

---

## 26 de Abril de 2026 - Correção do Fluxo de Autenticação: nginx → oauth2-proxy → Keycloak

### Arquivos Modificados

- **`nginx/nginx.conf`** — Adicionado `location /keycloak/` roteando diretamente ao container Keycloak; `location /` alterado de `agentk-client:8501` para `oauth2-proxy:4180`.
- **`docker-compose.yaml`** — Configuradas variáveis `KC_HOSTNAME_URL`, `KC_HOSTNAME_ADMIN_URL`, `KC_PROXY` e `KC_HOSTNAME_STRICT` no serviço Keycloak. Serviço `oauth2-proxy` reconfigurado com `--provider=oidc`, `--skip-oidc-discovery=true` e separação entre URLs públicas (browser) e internas (container-to-container).

### Descrição

O redirecionamento para a tela de login do Keycloak não ocorria porque o nginx estava roteando todas as requisições diretamente para o `agentk-client:8501`, ignorando completamente o `oauth2-proxy`. Adicionalmente, o `oauth2-proxy` estava configurado com a URL interna do Keycloak (`http://keycloak:8080/...`) como `--oidc-issuer-url`, o que faria com que o browser do usuário recebesse redirects para um hostname Docker inacessível externamente.

### Causa-Raiz (dois problemas combinados)

1. **nginx não consultava o oauth2-proxy**: O `location /` apontava para `agentk-client:8501`. Todo o stack de autenticação (oauth2-proxy + Keycloak) existia no compose, mas nunca era invocado no caminho de uma requisição normal do browser.
2. **Hostname circular no OIDC flow**: O `--oidc-issuer-url=http://keycloak:8080/realms/agentk` fazia com que o campo `issuer` dos tokens JWT e os `authorization_endpoint` retornados pelo Keycloak contivessem URLs acessíveis apenas dentro da rede Docker. O browser do usuário não consegue resolver `keycloak:8080`.

### Solução Aplicada

**Fluxo corrigido:**
```
Browser → Nginx:443 → oauth2-proxy:4180 → agentk-client:8501   (app autenticada)
Browser → Nginx:443/keycloak/ → keycloak:8080                   (admin + OIDC login)
oauth2-proxy ↔ keycloak:8080 (direto, container-to-container para token exchange e JWKS)
```

**nginx/nginx.conf:** Adicionado `location /keycloak/` com `proxy_pass http://keycloak:8080/` antes do `location /`. O bloco `/keycloak/` não passa pelo oauth2-proxy, resolvendo o problema do "chicken-and-egg" (é necessário acessar o Keycloak para configurá-lo antes que qualquer autenticação exista). O `location /` passou a apontar para `oauth2-proxy:4180`.

**docker-compose.yaml (Keycloak):** Adicionadas `KC_HOSTNAME_URL=https://agentk.local/keycloak` e `KC_HOSTNAME_ADMIN_URL` para que o Keycloak emita tokens JWT com o campo `iss` contendo a URL pública correta. `KC_PROXY=edge` instrui o Keycloak a confiar nos headers `X-Forwarded-*` enviados pelo nginx. A porta `8082` foi mantida apenas em `127.0.0.1` como fallback de debug local.

**docker-compose.yaml (oauth2-proxy):** Migrado de `--provider=keycloak-oidc` para `--provider=oidc` com `--skip-oidc-discovery=true`, possibilitando o controle explícito de cada URL. A `--login-url` usa a URL pública (`https://agentk.local/keycloak/...`) para que o browser consiga alcançar a tela de login. As `--redeem-url` e `--oidc-jwks-url` usam o hostname interno Docker (`http://keycloak:8080/...`) para a troca de token e validação de assinatura, sem trânsito desnecessário pelo nginx.

### Procedimento de configuração do Keycloak após o deploy

1. Acessar `https://agentk.local/keycloak/admin/` com as credenciais `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD`.
2. Criar o realm **`agentk`**.
3. Dentro do realm, criar o client **`oauth2-proxy`** (tipo `confidential`, `Standard Flow Enabled`).
4. Adicionar o **Valid Redirect URI**: `https://agentk.local/oauth2/callback`.
5. Copiar o **Client Secret** e definir a variável `OAUTH2_PROXY_CLIENT_SECRET` no `.env`.
6. Reiniciar o serviço: `docker compose restart oauth2-proxy`.

---

## 26 de Abril de 2026 - Correção de `SecurityException` no Fat JAR (assinaturas BouncyCastle)

### Arquivos Modificados

- **`build.gradle`** — Adicionadas cláusulas `exclude` para arquivos `META-INF/*.SF`, `META-INF/*.RSA`, `META-INF/*.DSA` e `META-INF/SIG-*` na task `fatJar`.

### Descrição

O gateway falhava na inicialização com `java.lang.SecurityException: Invalid signature file digest for Manifest main attributes` imediatamente após o entrypoint do Docker configurar o ambiente com sucesso.

### Causa-Raiz

O BouncyCastle distribui seus JARs com assinatura criptográfica (arquivos `META-INF/BC*.SF` e `BC*.DSA`) para garantir a integridade da biblioteca. A task `fatJar` descompactava todas as dependências e as re-empacotava em um único JAR, incluindo esses arquivos de assinatura. O ProGuard então processava o fat JAR renomeando e reorganizando classes, invalidando os hashes registrados nas assinaturas. O JVM detectava a divergência no carregamento e lançava `SecurityException` antes mesmo de executar `main()`.

### Solução Aplicada

Adicionadas cláusulas `exclude` na task `fatJar` do `build.gradle` para remover todos os arquivos de assinatura (`*.SF`, `*.RSA`, `*.DSA`, `SIG-*`) durante a criação do fat JAR, antes da etapa de ofuscação pelo ProGuard.

---

## 26 de Abril de 2026 - Correção de Falha Crítica de Inicialização do `agentk-gateway` (iptables / NET_ADMIN)

### Arquivos Modificados

- **`docker-compose.yaml`** — Adicionada a diretiva `cap_add: [NET_ADMIN]` ao serviço `agentk-gateway`.
- **`Dockerfile`** — Adicionada instrução `update-alternatives` para substituir o backend padrão `iptables-nft` pelo `iptables-legacy` após a instalação do pacote `iptables`.

### Descrição

O container `agentk-gateway` falhava sistematicamente na inicialização com o erro `iptables v1.8.7 (nf_tables): unknown option "--dport"`, impedindo que o processo Java do gateway fosse iniciado. Como o script `docker-entrypoint.sh` opera com `set -euo pipefail`, qualquer falha no bloco `setup_iptables()` encerrava o processo imediatamente — fazendo com que o healthcheck marcasse o container como `unhealthy` e o compose descartasse o serviço.

### Causa-Raiz

A falha apresentava duas origens combinadas:

1. **Ausência da capability `NET_ADMIN`**: Sem ela, o kernel bloqueia operações na tabela `nat` do iptables, impedindo a criação de regras PREROUTING/OUTPUT mesmo para processos rodando como root no container.
2. **Backend `nf_tables` incompatível em containers**: O Ubuntu Jammy utiliza por padrão o `iptables-nft` (backend nf_tables). Em ambientes containerizados sem os módulos nft do kernel disponíveis, este backend apresenta comportamento errático — incluindo a rejeição de opções padrão como `--dport` — ao contrário do `iptables-legacy` que acessa diretamente as syscalls `ip_tables`.

### Solução Aplicada

- **`cap_add: NET_ADMIN`** concede ao container a capability Linux necessária para modificar regras de firewall e NAT do kernel.
- **`update-alternatives --set iptables /usr/sbin/iptables-legacy`** garante que todas as invocações de `iptables` dentro do container utilizem o backend legado, compatível com a camada de kernel exposta pelo Docker, eliminando os erros de "unknown option".

---

## 26 de Abril de 2026 - Correção de Conectividade e Resolução de Nomes do Gateway

- **Unificação da Orquestração**: Os serviços `agentk-server` e `agentk-client` foram reincorporados ao `docker-compose.yaml` da raiz do projeto. Esta mudança elimina problemas de resolução de nome (DNS) entre containers de diferentes projetos e simplifica o fluxo de inicialização.
- **Correção de Endpoint MCP**: Ajustada a variável `MCP_SERVER_URL` para incluir o sufixo `/sse` nos arquivos `docker-compose.yaml` e `docker-compose.init.yaml`. A alteração resolve o erro 404 encontrado na inicialização do cliente MCP, direcionando-o para o endpoint correto do servidor FastMCP.
- **Estabilização da Rede Docker**: A rede `agentk-network` foi alterada de `external: true` para `driver: bridge` no manifesto unificado. O Docker Compose agora gerencia a criação da rede automaticamente, garantindo conectividade imediata entre o Gateway e o Cliente AgentK.
- **Ajuste em `Agentk-Sugest/client/app/services/chat_service.py`**: Introduzida lógica de sanitização de URL para forçar o uso do protocolo HTTPS na comunicação com o Gateway, prevenindo erros de protocolo caso a variável de ambiente seja configurada incorretamente.
- **Correção em `nginx/nginx.conf`**: Implementado suporte a WebSockets (Upgrade/Connection) no bloco de proxy para o `agentk-client`. A alteração resolve o travamento na tela de "Loading" do Streamlit ao ser acessado via HTTPS.
- **Resiliência com Healthchecks**: Implementados mecanismos de verificação de saúde para o `agentk-gateway` (via `pgrep`) e `agentk-server` (via socket). O serviço `agentk-client` agora aguarda a estabilização completa do backend via `service_healthy`, reduzindo erros de inicialização.
- **Robustez do Gateway**: Removido o `entrypoint` fixo do Java no `docker-compose.yaml` para permitir que o script `docker-entrypoint.sh` original configure permissões e certificados corretamente, resolvendo falhas imediatas de boot.

### Refinamento de Scripts e Logs
- **Ajuste em `start.sh`**: Corrigida a instrução de acesso ao Gateway para refletir o uso de HTTPS (`https://localhost:8081`). A alteração alinha os logs operacionais com a implementação real de segurança do sensor.

## 26 de Abril de 2026 - Mapeamento Dinâmico de `agentk.local` e Execução Garantida do Setup no Fluxo Init

### Evolução do `setup.sh` para IP Dinâmico e Upsert Idempotente em `/etc/hosts`
- **Atualização de `setup.sh`**: O parâmetro `AGENTK_HOST_IP` passou a suportar resolução automática (`auto`) do IP da VM por rota padrão (`ip route get`) com fallback para `hostname -I` e, em último caso, `127.0.0.1`. Foi implementado `upsert` idempotente da entrada `agentk.local`, removendo mapeamentos antigos e inserindo o novo valor de forma determinística.
- **Suporte a execução em container init**: Introduzida a flag `SKIP_HOSTS_ENTRY` para permitir geração de certificados em ambiente containerizado sem tentativa indevida de alteração do `/etc/hosts` do host.

### Garantia de Execução de Setup no Docker durante Instalação
- **Atualização de `docker-entrypoint-init.sh`**: O setup passou a ser invocado com `SKIP_HOSTS_ENTRY=1 AGENTK_HOST_IP=auto`, tornando o comportamento explícito para execução sob container.
- **Atualização de `docker-compose.init.yaml`**: O serviço `init` foi reforçado para instalar dependências necessárias (`bash`, `openssl`, `iproute2`) antes da execução do setup e os serviços dependentes foram convertidos para `depends_on` com `condition: service_completed_successfully`, garantindo ordenação correta e execução efetiva do setup no fluxo de instalação via Docker.

## 26 de Abril de 2026 - Mitigação de Colisão de Porta do AgentK Client com Alteração Mínima

### Parametrização da Porta Externa no Compose da Raiz (Preservando o Compose Original do AgentK)
- **Ajuste em `docker-compose.yaml` e `docker-compose.init.yaml`**: A publicação de porta do serviço `agentk-client` foi alterada de mapeamento fixo (`8501:8501`) para mapeamento parametrizado (`${AGENTK_CLIENT_HOST_PORT:-8502}:8501`, com `HOST_BIND_IP`), mantendo a porta interna do container em `8501` e deslocando por padrão apenas a porta externa do host para `8502`.
- **Preservação do sistema AgentK**: O arquivo original `Agentk-Sugest/docker-compose.yml` foi mantido inalterado para reduzir impacto no fluxo nativo do projeto AgentK e cumprir a diretriz de mínima intervenção.
- **Atualização operacional em `start.sh`**: O endpoint exibido para acesso direto ao client foi atualizado para refletir a nova variável `${AGENTK_CLIENT_HOST_PORT:-8502}`.

## 26 de Abril de 2026 - Mitigação de Colisão de Porta do MCP Server no Host

### Parametrização da Porta Externa do Serviço `agentk-server`
- **Ajuste em `docker-compose.yaml`, `docker-compose.init.yaml` e `Agentk-Sugest/docker-compose.yml`**: A publicação de porta do serviço MCP foi alterada de mapeamento fixo (`3333:3333`) para mapeamento parametrizado (`${AGENTK_MCP_HOST_PORT:-3334}:3333`, respeitando também `HOST_BIND_IP` nos manifests da raiz). A mudança preserva a porta interna `3333` na rede Docker para comunicação entre containers e desloca, por padrão, a porta exposta no host para `3334`, eliminando o erro de bind quando `3333` já está em uso por processo preexistente.

## 26 de Abril de 2026 - Correção Sistêmica de Conectividade entre Nginx, AgentK Client e MCP Server

### Reativação de Serviços e Correção de Roteamento de Borda
- **Atualização de `docker-compose.yaml`**: Reativados os serviços `agentk-server` e `agentk-client` no manifesto principal, com exposição explícita de portas (`3333` e `8501`), dependências de inicialização e `healthcheck` para o servidor MCP. O serviço `nginx` foi reconfigurado para depender diretamente de `agentk-client` e os binds de portas sensíveis ao acesso externo em VM foram parametrizados por `HOST_BIND_IP` (padrão `0.0.0.0`) para viabilizar acesso via IP da máquina virtual.
- **Atualização de `docker-compose.init.yaml`**: Aplicado o mesmo alinhamento estrutural do compose principal na variante com serviço `init`, garantindo equivalência funcional entre os dois fluxos de inicialização.
- **Refatoração de `nginx/nginx.conf`**: O upstream HTTPS foi alterado de `oauth2-proxy:4180` para `agentk-client:8501`, eliminando acoplamento ao caminho de autenticação intermediário durante o fluxo base de acesso ao app via `agentk.local`.

### Correção de Endereçamento Interno do Cliente
- **Atualização de `Agentk-Sugest/client/app/services/chat_service.py`**: O endpoint de validação do Guardrail deixou de ser hardcoded em `host.docker.internal:8080` e passou a ser parametrizável por `GATEWAY_VALIDATE_URL`, com fallback para `https://agentk-gateway:8080/validar`. A alteração assegura comunicação nativa entre containers na rede Docker e evita falhas quando o host expõe porta distinta.

### Ajustes Operacionais de Script para Ambiente VM
- **Atualização de `setup.sh`**: A entrada de hosts passou a usar `AGENTK_HOST_IP` (padrão `127.0.0.1`) na composição de `agentk.local`, tornando o mapeamento adaptável para acesso via IP da VM. Foi corrigido também o fluxo para sempre aplicar/verificar a entrada no `/etc/hosts`, mesmo quando o certificado já existe.
- **Atualização de `start.sh`**: Revisadas as instruções de acesso pós-subida para refletir endpoints reais do ambiente virtualizado (`agentk.local`, `<IP_DA_VM>:8501`, `<IP_DA_VM>:3333`, `<IP_DA_VM>:8082` e porta parametrizada do Ollama).

## 26 de Abril de 2026 - Mitigação de Colisão de Porta do Ollama no Host

### Parametrização da Porta Externa no Docker Compose
- **Ajuste em `docker-compose.yaml` e `docker-compose.init.yaml`**: A exposição de porta do serviço `ollama` foi alterada de mapeamento fixo (`11434:11434`) para mapeamento parametrizado (`${OLLAMA_HOST_PORT:-11435}:11434`). A mudança preserva a porta interna do contêiner (`11434`) para comunicação entre serviços na rede Docker e desloca, por padrão, a porta do host para `11435`, mitigando conflitos de bind quando a porta `11434` já está ocupada por instância local preexistente.

## 26 de Abril de 2026 - Automação de Setup em Fluxo Docker

### Criação de Scripts de Inicialização Automatizada
- **Novo arquivo `start.sh`**: Script wrapper que executa automaticamente `setup.sh` antes de iniciar os containers Docker via `docker compose up -d --build`. Ideal para fluxo local de desenvolvimento onde o usuário deseja uma única linha de comando para provisionar toda a infraestrutura.
- **Novo arquivo `docker-entrypoint-init.sh`**: Script de entrypoint que executa setup.sh dentro de um contexto pré-Docker, viabilizando o uso de um serviço `init` no Docker Compose que executa antes dos demais containers.
- **Novo arquivo `docker-compose.init.yaml`**: Versão alternativa do Docker Compose que inclui um serviço de inicialização (`init`) que executa o setup.sh automaticamente. Todos os demais serviços (`gateway`, `keycloak`, `ollama`, `nginx`, `oauth2-proxy`) declaram dependência (`depends_on`) neste serviço, garantindo a execução sequencial. Uso: `docker compose -f docker-compose.init.yaml up -d --build`.

## 26 de Abril de 2026 - Simplificação do Script de Setup para Geração de Certificado Nginx

### Redução de Escopo do `setup.sh` para Foco Exclusivo em Certificado SSL
- **Refatoração do `setup.sh`**: O script foi completamente reestruturado para remover toda infraestrutura complexa de iptables, CA de autoridade certificadora, keystore PKCS12 e adição de certificados ao trust store do sistema. A nova versão executa apenas a tarefa elementar de gerar um certificado SSL auto-assinado (RSA 2048, validade 365 dias) para o serviço Nginx local, criando o diretório `./certs` se necessário e exportando os artefatos (`agentk.crt` e `agentk.key`) para consumo pela orquestração Docker Compose. A alteração alinha o propósito do script com o ambiente de desenvolvimento mais enxuto.
- **Adição de entrada DNS local (`setup_hosts_entry`)**: Incluída a função `setup_hosts_entry` que insere idempotentemente a entrada `127.0.0.1 agentk.local` em `/etc/hosts` do sistema anfitrião. A função verifica prévia existência da entrada antes de qualquer escrita (prevenindo duplicatas), e adapta a estratégia de elevação de privilégio conforme o contexto de execução: injeção direta quando executada como root ou via `sudo tee -a` quando executada como usuário comum. Esta operação foi deliberadamente mantida no script do host porque o Docker Compose não possui permissão de modificar definições de resolução DNS do sistema anfitrião.

## 26 de Abril de 2026 - Eliminação de Dependência de Gradle no Build da Imagem Gateway

### Reestruturação do `Dockerfile` para Fluxo Exclusivamente Baseado em Docker
- **Refatoração do `Dockerfile` do Gateway**: Foi removido o estágio `builder` baseado em `gradle:8.7-jdk21` e, consequentemente, suprimida a execução de `gradle obfuscatedJar --no-daemon` no processo de build da imagem. Em substituição, a imagem passou a consumir diretamente o artefato pré-compilado via `ARG GATEWAY_JAR` (padrão `build/libs/gateway-sensor-1.0.0-obf.jar`) e `COPY ${GATEWAY_JAR} app.jar`. A alteração neutraliza a falha de compatibilidade de script Gradle (`classifier` inválido) durante o build em contêiner e consolida um pipeline de execução centrado apenas em Docker para etapa de orquestração.

## 26 de Abril de 2026 - Adequação de Portabilidade do Serviço Gateway no Docker Compose

### Substituição de Bind-Mount de Artefato por Build Nativo de Imagem
- **Refatoração do serviço `gateway` em `docker-compose.yaml`**: A definição foi alterada de `image` com bind-mount explícito do artefato `./build/libs/gateway-sensor-1.0.0-obf.jar:/app/app.jar:ro` para estratégia de `build` local (`context: .`, `dockerfile: Dockerfile`), preservando o `entrypoint` direto em `java -jar /app/app.jar`. A mudança elimina a dependência de caminhos absolutos do hospedeiro no momento de execução (`not a directory` em ambiente com diretório de trabalho divergente), assegurando comportamento reprodutível em ambientes distintos sem exigir pré-posicionamento manual do JAR no host invocador.

## 26 de Abril de 2026 - Correção de Integridade do Projeto Docker Compose Após Desacoplamento do AgentK

### Eliminação de Referência a Serviço Indefinido
- **Ajuste no `docker-compose.yaml`**: Foi removida, no serviço `oauth2-proxy`, a entrada `agentk-client` da diretiva `depends_on`, uma vez que o respectivo serviço havia sido previamente comentado para viabilizar a execução segregada dos componentes do ecossistema AgentK. A correção restaura a validade estrutural do manifesto Docker Compose, eliminando o erro de carregamento `depends on undefined service` e preservando a inicialização dos serviços remanescentes de forma consistente.

## 26 de Abril de 2026 - Desacoplamento Operacional do Compose Principal em Relação ao Compose do AgentK

### Isolamento de Ferramentas para Execução Independente
- **Comentário seletivo no `docker-compose.yaml`**: Foi executado o comentário integral dos blocos de serviço `agentk-server` e `agentk-client`, bem como das declarações correlatas `minikube` (rede externa) e `agentk-logs` (volume local), preservando-se inalteradas as demais definições de infraestrutura não pertencentes ao manifesto específico do AgentK. A intervenção teve como fundamento a separação de responsabilidades entre orquestrações, permitindo que os componentes nativos do `Agentk-Sugest/docker-compose.yml` passem a ser inicializados por meio de fluxo autônomo e dedicado, sem acoplamento direto ao compose consolidado da raiz do projeto.

## 25 de Abril de 2026 - Evolução do Guardrail: Controle de Acesso Baseado em Risco

### Autenticação em Escopo de Risco (Keycloak)
- **Implementação do Fluxo de Autorização para Vereditos RISKY**: Foi introduzido um mecanismo de interrupção e autorização no serviço de chat (`Agentk-Sugest/client/app/services/chat_service.py`). A partir desta atualização, prompts classificados pela Inteligência Artificial do Gateway como `RISKY` (arriscados) são interceptados e não enviados de imediato ao modelo LLM primário. Em vez de sofrerem um bloqueio permanente como ações `UNSAFE`, estes requisitam credenciais de administrador via interface gráfica baseada em *modals* (implementada através do decorador `@st.dialog` do Streamlit).
- **Integração com Servidor de Identidade**: A validação das credenciais fornecidas durante o cenário de risco é processada diretamente contra o Identity Provider Keycloak (`http://keycloak:8080/realms/agentk/protocol/openid-connect/token`), garantindo conformidade com a arquitetura de acesso do ecossistema. O processo utiliza o tipo de concessão `password` e, em caso de êxito na validação do *token*, a execução da aplicação é reinvocada para prosseguir com o fluxo original da requisição mitigada.
- **Adaptação de Ciclo de Vida da UI (`Agentk-Sugest/client/app/main.py`)**: Para suportar as características assíncronas do Streamlit durante a abertura do painel de autorização, a lógica de evento principal foi modificada para registrar a sessão autorizada transacionalmente e recarregar a requisição validada sem que o *prompt* do usuário seja perdido ou duplicado.## 25 de Abril de 2026 - Evolução de Empacotamento, Orquestração e Documentação

### Documentação Arquitetural
- **Criação do arquivo `ARCHITECTURE.md`**: Elaboração de documentação analítica descrevendo a topologia estrutural do Gateway. O texto abrange o modelo operacional, detalhando os módulos de entrada (`Main.java`), interfaces HTTP (`PromptValidator.java`) e a mecânica de classificação através de Inteligência Artificial (`SecurityClassifier.java`), bem como as metodologias aplicadas na tomada de decisão sobre a categorização de *prompts*.

### Otimização da Engenharia de Construção (Build)
- **Modificação do `Dockerfile`**: Implementação da abordagem de *Multi-stage Build*. O processo de compilação agora utiliza o contêiner `gradle:8.7-jdk21` no estágio inicial (builder) para gerar e ofuscar o código binário nativamente no ambiente Docker. O artefato produzido é então injetado no ambiente de execução (`eclipse-temurin:21-jre-jammy`), mitigando definitivamente o acoplamento sistêmico e suprimindo a necessidade de dependências de compilação (Gradle, JDK) no *host* do usuário.

### Evolução na Orquestração de Contêineres
- **Atualização do `docker-compose.yaml` e `docker-compose.merged.yaml`**: 
  - Integração nativa do serviço **Ollama** (`ollama/ollama:latest`) com persistência em disco assegurada via volumes montados.
  - Modificação do *entrypoint* do serviço do motor de IA visando promover a automação total do provisionamento do modelo adotado. A configuração introduz o comando `ollama serve & sleep 5 && ollama pull qwen2.5:1.5b && wait`, garantindo o trânsito assíncrono para a obtenção do modelo sem intervenção humana.
  - Supressão de comentários e instruções obsoletas que demandavam compilação local manual, alinhando a documentação *inline* à nova estrutura hermética de containers.

### Inclusão de Proxy Reverso (Nginx)
- **Atualização do `docker-compose.merged.yaml`**: Adicionado o serviço do **Nginx** (`nginx:alpine`) atuando como proxy reverso com suporte a HTTPS. A configuração estabelece a montagem de volumes em modo leitura (*read-only*) para o arquivo de configuração e certificados digitais, expõe as portas 80 e 443 estritamente para o laço local (`127.0.0.1`) e define dependência explícita (`depends_on`) em relação ao serviço `oauth2-proxy`, integrando o proxy de borda à rede `agentk-network`.

### Otimização e Segurança na Inicialização do Gateway
- **Limpeza Estrutural do `docker-compose.merged.yaml`**: Reestruturação do serviço `gateway` para iniciar estritamente o que é essencial. Foi definido o `entrypoint: ["java", "-jar", "/app/app.jar"]`, contornando lógicas desnecessárias do script original (como regras de `iptables`). Com isso, foram removidos os privilégios elevados de rede (`cap_add: NET_ADMIN`, `network_mode: "host"`), isolando-o de maneira segura na rede `agentk-network`.
- **Remoção de Dependências Obsoletas**: Como o módulo de interceptação TLS (MITM) não está ativo na arquitetura atual, eliminou-se o mapeamento de volumes referentes à geração de Certificados e Keystores (`gateway-certs` e `gateway-keys`), bem como as variáveis de ambiente que forçavam os demais serviços (`agentk-server` e `agentk-client`) a confiar nessa Autoridade Certificadora inativa, resultando em uma topologia mais leve e coerente com as operações restritas ao Webhook e ao modelo de IA local.

### Configuração de Infraestrutura em Produção
- **Criação do `docker-compose.final.yaml`**: Adicionado um arquivo Docker Compose de orquestração definitiva. Diferente das versões anteriores, este manifesto ignora a compilação local (Multi-stage build) da imagem e invoca nativamente a imagem `eclipse-temurin:21-jre-jammy`, mapeando via volume o artefato pré-compilado pelo Gradle (`./build/libs/gateway-sensor-1.0.0-obf.jar`). Essa alteração garante a integridade e separação estrita do ciclo de build e deploy em ambientes provisionados.

### Correção de Topologia em Orquestração Local
- **Ajuste de Contextos no `docker-compose.final.yaml`**: Corrigidos os caminhos para as declarações de compilação (*build context*) dos serviços `agentk-server` e `agentk-client`, que antes apontavam equivocadamente para a raiz (`./server` e `./client`) e agora endereçam precisamente as subpastas em `./Agentk-Sugest/`.
- **Refinamento na Mapeamento do Nginx**: O bloco do serviço de roteamento de borda (Nginx) teve sua diretiva de volumes corrigida. A tentativa de carregar uma configuração inexistente em `./nginx/nginx.conf` foi suprimida e o mapeamento de certificados TLS foi direcionado à pasta correta preexistente na raiz da infraestrutura (`./certs:/etc/nginx/certs:ro`), assegurando a correta funcionalidade sintática na partida do orquestrador.

## 24 de Abril de 2026 - Centralização e Padronização de Logs no Ambiente AgentK

### Configuração Global de Telemetria
- **Criação do módulo `logs/logging_config.py`**: Estabelecimento de uma infraestrutura centralizada para emissão de logs em todo o ecossistema. A solução implementa a captura de variáveis de ambiente (`AGENTK_LOG_LEVEL`, `AGENTK_LOG_DIR`, `AGENTK_LOG_MAX_MB`, `AGENTK_LOG_BACKUPS`) para parametrizar a rotação, o diretório e a severidade dos registros. Inclui um mecanismo de resolução de caminhos com *fallback* automático (priorizando o diretório do sistema `/var/log/agentk` em relação ao diretório local `logs/`) e introduz o manipulador `RotatingFileHandler` para mitigar o consumo de disco frente a altos volumes de dados em paralelo à emissão contínua em *stdout*.

### Wrappers de Telemetria nos Microsserviços
- **Refatoração no Servidor (`server/app/utils/logger.py`)**: Integração com a configuração global de logs. Foi suprimida a criação estática e isolada do arquivo local em favor da nova política, emitindo artefatos no arquivo definido por `AGENTK_SERVER_LOG_FILE` (padrão: `agentk-server.log`) sob o *namespace* específico `agentk.server`.
- **Refatoração no Cliente (`client/app/utils/logger.py`)**: Adequação da interface de log do cliente aos padrões do projeto, assegurando a exportação das trilhas de auditoria para o arquivo `AGENTK_CLIENT_LOG_FILE` (padrão: `agentk-client.log`) sob o *namespace* `agentk.client`.

### Refatoração de Instrumentação no Código Base
- **Adaptação no Ponto de Entrada do Servidor (`server/app/main.py`)**: Remoção de chamadas legadas à API `logging.basicConfig` e da instância dedicada de `FileHandler`, migrando o controle de severidade e formatação de saída de forma exclusiva para as definições orquestradas externamente via variáveis de ambiente.
- **Substituição de Mecanismos de Debug no Cliente (`client/app/classes/mcp_client.py`)**: Eliminação de rotinas manuais de depuração (ex: `_debug_log`) e de *flags* puramente locais de controle (`_debug`). A classe `MCPClient` passou a adotar o novo padrão estruturado para instrumentar adequadamente as operações críticas de ciclo de vida (inicialização `stdio` e `http`), processos de listagem e requisição de recursos (`get_tools`, `get_resources`, `get_prompts`, `get_resource`, `invoke_prompt`), execução das ferramentas disponíveis (`call_tool`) e encerramentos assíncronos (`cleanup`), capturando anomalias em níveis semânticos apropriados (como `logger.error`).

### Orquestração de Volumes e Persistência de Logs
- **Atualização do Manifesto Docker (`docker-compose.yml`)**: Incorporação de definições de rastreabilidade unificada para os serviços `agentk-server` e `agentk-client`. Foram injetadas as novas variáveis de ambiente limitadoras e definidoras da arquitetura de telemetria. Introduziu-se o mapeamento de volume compartilhado `agentk-logs:/var/log/agentk`, assegurando que ambos os componentes preservem e persistam seus registros de eventos diagnósticos na mesma partição do contêiner e no *host*.

## 17 de Abril de 2026 - Implementação de Validação de Segurança (Guardrail) no Fluxo de Chat

### Interceptação e Inspeção de Prompts
- **Refatoração do Serviço de Chat (`Agentk-Sugest/client/app/services/chat_service.py`)**: Alteração substancial no método `process_llm_request()` para instituir um ponto de controle (middleware) obrigatório. A submissão de requisições ao modelo principal (ex: ChatGPT) foi condicionada a uma pré-validação de segurança. O sistema agora extrai a última instrução (prompt) emitida pelo usuário na interface e a submete via HTTP POST ao Gateway local (`http://host.docker.internal:8080/validar`).

### Mecanismo de Bloqueio Baseado em Inteligência Artificial Local
- **Integração de Lógica de Veredito Semântico**: O fluxo de execução foi enriquecido com a etapa de análise e verificação de integridade do pacote JSON retornado pelo Gateway. A estrutura valida se o modelo de IA primário local compreendeu o mesmo texto submetido e toma decisões baseadas no estado de `veredito`. Quaisquer classificações diferentes de `SAFE` (como `SUSPECT`, `UNCERTAIN`, `RISKY` ou `UNSAFE`) desencadeiam o bloqueio automático da requisição, apresentando mensagens descritivas de *warning* ou *error* diretamente na interface gráfica, abortando a chamada externa e preservando a segurança do ambiente.

### Reforço na Segurança e Criptografia (Guardrail)
- **Mitigação de Prompt Injection (`SecurityClassifier.java`)**: Implementada blindagem na formatação de contexto submetida ao modelo de IA local (Ollama). A entrada do usuário (`userPrompt`) foi rigorosamente isolada através de sintaxe de marcação (`<USER_PROMPT>...</USER_PROMPT>`). Em conjunto, foi introduzida uma instrução forte forçando o modelo a tratar exclusivamente esse fragmento como dados inertes a serem classificados, prevenindo ataques onde um payload malicioso poderia sobrescrever o comportamento (role) e as instruções base do Guardrail.
- **Implementação de Transporte Seguro TLS/HTTPS (`PromptValidator.java` & Orquestração)**: O `Gateway` foi refatorado para habilitar suporte nativo ao tráfego HTTPS quando o certificado for mapeado via variável de ambiente (`KEYSTORE_PATH`). Para esta transição:
  - O código Java migrou condicionalmente da classe base `HttpServer` para `HttpsServer`, incorporando classes do pacote de segurança do Java para leitura do certificado PKCS12 (gerado anteriormente).
  - No `docker-compose.final.yaml`, mapeou-se o volume para injetar o arquivo `gateway-keystore.p12` no serviço `gateway`.
  - No módulo cliente (`chat_service.py`), a `gateway_url` foi migrada de `http://` para `https://` (com inibição das validações estritas de certificados locais com `verify=False`), assegurando que a transferência do pacote (incluindo o prompt original) não sofra interceptação ou "sniffing" não autorizado através da rede Docker interna.

## 25 de Abril de 2026 - Elaboração de Documentação de Segurança e Implantação

### Consolidação do Repositório
- **Criação do arquivo `README.md`**: Elaboração de documentação abrangente descrevendo as propostas de arquitetura de segurança do ecossistema AgentK. O documento detalha o papel fundamental do **Nginx** no roteamento de borda, a adoção de certificados SSL/TLS para criptografia em trânsito, a infraestrutura centralizada de captura de eventos e telemetria (Logs), os mecanismos de controle de acesso gerenciados pelo **Keycloak** em conjunto com fluxos de autorização baseados em risco, e as competências do Gateway Java como validador (Guardrail) contra *Prompt Injection*. Adicionalmente, foi incluído um guia de orquestração via contêineres (`docker-compose`) e instruções de configurações iniciais de *Realms* e *Clients* necessários no provisionamento do provedor de identidade.

## 26 de Abril de 2026 - Correção de Contexto de Build no Agentk-Sugest

### Resolução de Falha de Cálculo de Cache no Docker BuildKit (`/logs: not found`)
- **Correção do `Agentk-Sugest/docker-compose.yml`**: Identificado e resolvido um erro de referência de caminho que impedia a conclusão do *build* dos serviços `agentk-server` e `agentk-client`. O `BuildKit` relatava `failed to compute cache key: "/logs": not found` porque o `context` de ambos os serviços era definido como `./server` e `./client`, restringindo a visibilidade do daemon Docker exclusivamente ao respectivo subdiretório. Contudo, os manifestos `Dockerfile` de ambos os serviços empregavam instruções `COPY` com caminhos prefixados relativos à raiz do projeto (e.g., `COPY server/app/`, `COPY client/app/`, `COPY logs/`), evidenciando a incompatibilidade entre o escopo do contexto e os caminhos referenciados. A solução consistiu em elevar o `context` de ambos os serviços para `.` (raiz do diretório `Agentk-Sugest/`) e ajustar a diretiva `dockerfile` para referenciar explicitamente o caminho completo relativo à nova raiz de contexto (`server/Dockerfile` e `client/Dockerfile`). Essa alteração restabelece a coerência arquitetural entre o orquestrador e os manifestos de construção de imagem, permitindo o acesso irrestrito ao módulo compartilhado de telemetria (`logs/`).

## 26 de Abril de 2026 - Resolução de Conflitos na Orquestração de Contêineres

### Mitigação de Colisão de Portas de Rede
- **Modificação do `docker-compose.yaml`**: Ajuste no mapeamento de portas do serviço `gateway` para o hospedeiro (host). A diretiva de exposição foi alterada de `"8080:8080"` para `"8081:8080"`. Esta intervenção foi necessária para resolver uma colisão direta de alocação de portas (Binding) na interface de rede local (`127.0.0.1`), visto que o serviço `keycloak` já detinha reserva para o porto `8080` de forma nativa. O ajuste mantém a integridade da malha interna (rede do Docker), assegurando que o roteamento interno (proxy) entre a aplicação cliente e o Guardrail continue operacional.

### Correção de Módulos Compartilhados no Build de Contêineres
- **Atualização de Contexto e Mapeamento (`docker-compose.yaml` e `Dockerfile`)**: Resolvido o problema de importação circular ausente (`ModuleNotFoundError: No module named 'logs'`). Anteriormente, os ambientes isolados do cliente e do servidor limitavam-se ao seus subdiretórios de origem durante o *build*. O escopo de contexto (*build context*) do orquestrador Docker Compose foi elevado para a raiz do repositório `./Agentk-Sugest`, permitindo que tanto o cliente (`client/Dockerfile`) quanto o servidor (`server/Dockerfile`) acessem o módulo unificado de rastreamento de eventos em `logs/`. Os manifestos Dockerfile correspondentes foram retificados com a injeção apropriada das pastas compartilhadas (via `COPY logs/ ./app/logs/`), estabelecendo um paradigma arquitetural coerente onde microsserviços consomem bibliotecas locais de maneira centralizada sem duplicação de artefatos. Adicionalmente, foi criado o descritor de inicialização de módulo (`__init__.py`) no diretório `logs/` para formalizar a construção do pacote Python.


## 26 de Abril de 2026 - Correção de Acesso Externo e Inicialização MCP

### Ajuste de Roteamento e Telemetria de Rede
- **Correção no `nginx/nginx.conf`**: O parâmetro `server_name` foi expandido de `agentk.local` para `agentk.local _`. Esta alteração permite que o Nginx aceite e processe requisições direcionadas diretamente ao endereço IP da VM (onde o cabeçalho `Host` não corresponde ao domínio nominal), facilitando o acesso externo em ambientes de teste sem necessidade de configuração prévia de DNS no cliente.
- **Resolução de Erro de Conexão MCP (SSE)**: Identificada falha crítica na inicialização do serviço AgentK (`ExceptionGroup` no Streamlit). O problema residia na omissão do endpoint `/sse` na variável `MCP_SERVER_URL`. O framework `FastMCP`, quando operando em modo HTTP, expõe obrigatoriamente o fluxo de eventos neste caminho específico. A correção foi aplicada tanto no `docker-compose.yaml` da raiz quanto no subdiretório `Agentk-Sugest/`, normalizando a comunicação interna entre o cliente Streamlit e o servidor MCP.

## 26 de Abril de 2026 - Adaptação de Contexto de Build para Orquestração Unificada

### Resolução de Erro de Build ("/logs": not found)
- **Adaptação na Camada do Gateway (Raiz)**: O arquivo `docker-compose.yaml` principal foi configurado para utilizar a raiz do repositório (`context: .`) como base para todos os serviços. Esta "adaptação do lado do Gateway" permite que o Docker BuildKit acesse transversalmente as pastas de código e de logs compartilhadas.
- **Refatoração dos Dockerfiles do AgentK**: Os manifestos de construção em `Agentk-Sugest/server/Dockerfile` e `Agentk-Sugest/client/Dockerfile` foram atualizados para utilizar caminhos relativos à raiz do projeto. Esta mudança garante a integridade do build quando invocado pela stack principal, resolvendo definitivamente a falha de localização do módulo de logs.
- **Preservação da Orquestração Secundária**: O arquivo `Agentk-Sugest/docker-compose.yml` foi mantido rigorosamente intacto conforme solicitado, servindo como referência de configuração local, enquanto a inteligência de integração foi movida para o orquestrador raiz.

## 26 de Abril de 2026 - Autonomia de Serviços e Resolução de Contexto de Build

### Independência de Contexto (AgentK)
- **Sincronização de Dependências**: Realizada a cópia física da pasta `logs/` para dentro de `Agentk-Sugest/server/` e `Agentk-Sugest/client/`. Esta medida de "infraestrutura por redundância" resolve o erro de build provocado pelo uso de contextos restritos em arquivos Compose protegidos contra alteração.
- **Simplificação de Manifestos de Construção**: Os `Dockerfile`s foram ajustados para operar em contextos isolados (`COPY requirements.txt .`), tornando-os compatíveis tanto com a orquestração raiz quanto com o comando `docker compose up` executado diretamente na pasta do subprojeto.
- **Isolamento na Camada Gateway**: O `docker-compose.yaml` raiz foi atualizado para utilizar contextos específicos de serviço (`context: ./Agentk-Sugest/server`), garantindo que o build da stack principal seja robusto e independente.
