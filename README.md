# AgentK Guardrail & Security Architecture

Este repositório consolida o projeto **AgentK Guardrail**, uma arquitetura focada na segurança e validação de intenções (prompts) utilizando Modelos de Linguagem Local (Ollama) e um ecossistema robusto de proteção, monitoramento e controle de acesso.

## 🛡️ Arquitetura de Segurança Proposta

A infraestrutura foi projetada incorporando múltiplas camadas de defesa e mitigação de riscos:

### 1. Gateway Sensorial como Guardrail
No centro da arquitetura está a aplicação Java (Gateway) que atua como um validador de tráfego. Antes de qualquer requisição ser enviada aos modelos externos ou ao ecossistema principal, o Gateway analisa a intenção (prompt) usando um modelo local de IA (qwen2.5:1.5b via Ollama). Esta camada de inteligência mitiga o risco de ataques como *Prompt Injection* ou manipulações maliciosas (`UNSAFE`, `SUSPECT`, `RISKY`), isolando e avaliando os dados em contêiner hermético via blocos de instrução rígidos.

### 2. Autenticação e Autorização (Keycloak)
Para controlar de forma rígida o acesso, o ecossistema incorpora o **Keycloak** atuando em conjunto com o **OAuth2 Proxy**.
- Prompts com classificação de risco moderado (`RISKY`) são interceptados na interface de conversação, forçando uma etapa de autorização adicional via credenciais de administrador validadas ativamente no provedor de identidade Keycloak antes de prosseguir com a requisição.
- O controle de acesso baseia-se em tokens (OIDC) que protegem a malha de comunicação.

### 3. Nginx como Proxy Reverso Edge
O **Nginx** foi provisionado para assumir o papel de Edge Gateway. Atuando de forma frontal, é o responsável por:
- Intermediar e rotear todo o tráfego externo para os serviços pertinentes.
- Prover a terminação unificada garantindo que o ciclo de vida e a resolução de requisições web funcionem por trás de um perímetro seguro e delimitado.

### 4. Criptografia em Trânsito (Certificados SSL/TLS)
A proteção dos dados transmitidos é assegurada através da implementação rigorosa de criptografia na camada de transporte (HTTPS/TLS):
- O tráfego direcionado pelo Nginx faz o uso e montagem de certificados digitais seguros.
- As comunicações internas do Gateway utilizam certificados próprios encapsulados via padrão PKCS12, garantindo proteção contra interceptações de tráfego (Sniffing e Man-in-the-Middle) intra-rede.

### 5. Telemetria e Centralização de Logs (AgentK Logging)
Para auditoria corporativa e resposta a incidentes, um sistema de logging unificado captura eventos críticos:
- Volumes persistentes reúnem os artefatos de telemetria emitidos por todos os microsserviços do ecosistema.
- Utilização de rotacionamento estruturado (`RotatingFileHandler`), parametrizações globais de severidade e formatação nativa que rastreia tentativas falhas de submissão e detecções do Guardrail.

---

## 🚀 Como Executar o Projeto

O projeto é projetado para operar inteiramente via contêineres Docker, simplificando o processo de implementação.

### Pré-requisitos
- **Docker** e **Docker Compose** instalados no ambiente host.
- (Opcional) Ambiente Minikube caso exista integração direta do AgentK com orquestração Kubernetes.

### Inicializando a Infraestrutura

Para levantar a infraestrutura completa, a inicialização se dá pelo orquestrador Docker Compose. 

Execute o seguinte comando na raiz do projeto:

```bash
docker-compose up -d
```

Este comando orquestrará:
- O pull do modelo e inicialização do serviço Ollama.
- O provisionamento da base de dados e do servidor de identidade Keycloak.
- A configuração da rede e do OAuth2 Proxy.
- A inicialização do Gateway Validador (Java) com seu certificado TLS acoplado.
- Os serviços clientes e servidores do AgentK-Sugest.
- O Proxy reverso de borda Nginx.

### Configuração Manual do Keycloak

A instância do Keycloak provisionada é inicializada em modo de desenvolvimento (`start-dev`) e sua persistência atual é baseada em arquivos (`dev-file`). Para preparar o Keycloak pela primeira vez ou ajustar os controles de permissão para aprovar os comandos `RISKY`:

1. **Acesso Administrativo**: 
   - Navegue até `http://127.0.0.1:8080/admin` ou acesse diretamente a porta exposta localmente.
   - O login e a senha padrões estão definidos no arquivo `docker-compose.yaml` (por padrão: `admin` / `admin`).

2. **Criação do Realm**:
   - Crie (se não existir) um novo realm chamado `agentk` para acomodar os serviços.

3. **Criação de Clientes (Clients)**:
   - Cadastre um cliente chamado `oauth2-proxy` compatível com o protocolo OpenID Connect.
   - Configure a URL raiz (Root URL) apontando para o seu ambiente e configure o Client Secret. O Client Secret gerado deve ser atualizado na variável de ambiente `OAUTH2_PROXY_CLIENT_SECRET` do `docker-compose.yaml`.

4. **Gestão de Usuários**:
   - Crie usuários corporativos.
   - Para permitir autorização em requisições de risco, defina senhas seguras (Credentials -> Password) e remova a obrigatoriedade de senha temporária para os administradores encarregados de assinar requisições `RISKY`.

5. **Testes de Integração**:
   - Uma vez que o fluxo OIDC esteja ativo, a interface Streamlit demandará um token válido no Keycloak (com o tipo de concessão password) sempre que o motor do Guardrail (Gateway) sinalizar uma ação como suspeita e exigir aprovação em tempo de execução.
