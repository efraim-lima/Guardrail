import javax.net.ssl.SSLSocket;
import java.io.*;
import java.util.*;

// NOTA: Esta classe não está sendo chamada em nenhum ponto do fluxo ativo.
// Main.java usa apenas PromptValidationWebhookServer para classificação.
// TransparentTLSInterceptor possui lógica equivalente implementada internamente
// (extractPromptFromJson, classifyPrompt, relayToUpstream) sem delegar a este módulo.
// Para integrar: TransparentTLSInterceptor.handleDecryptedHttp deve instanciar e chamar analyze().

/**
 * TrafficAnalyzer.java - Extrator e Filtro
 *
 * Responsabilidades:
 * 1. Ler bytes do soquete decifrado (SSLSocket)
 * 2. Interpretar protocolo HTTP
 * 3. Extrair payload JSON
 * 4. Identificar o prompt do usuário no array "messages"
 * 5. Passar para SecurityClassifier para veredito
 *
 * Fluxo:
 *   TransparentTLSInterceptor.analyzeTraffic(SSLSocket)
 *      ↓
 *   TrafficAnalyzer analyzer = new TrafficAnalyzer(sslSocket)
 *      ↓
 *   analyzer.analyze()
 *      ├→ parseHeaders()      [lê HTTP headers]
 *      ├→ extractPayload()    [lê HTTP body com base em Content-Length]
 *      ├→ parseJSON()         [mapeia para objeto JSON]
 *      ├→ extractUserPrompt() [extrai texto do prompt do usuário]
 *      └→ classifyAndRelay()  [passa para SecurityClassifier e UpstreamRelay]
 */
public class TrafficAnalyzer {
    private static final String LOG_PREFIX = "[TrafficAnalyzer]";
    private static final Set<String> DEFAULT_MONITORED_HOSTS =
            new HashSet<>(Arrays.asList("api.openai.com"));
    
    private SSLSocket clientSocket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private Map<String, String> httpHeaders = new HashMap<>();
    private String httpMethod;
    private String httpPath;
    private String httpVersion;
    private byte[] httpBody;
    private String hostHeader;
    
    // ============================================================================
    // CONSTRUCTOR
    // ============================================================================
    
    /**
     * Construtor do TrafficAnalyzer
     *
     * @param clientSocket SSLSocket descriptografado recebido do TransparentTLSInterceptor
     *
     * @throws IllegalArgumentException se clientSocket for null
     */
    public TrafficAnalyzer(SSLSocket clientSocket) {
        if (clientSocket == null) {
            throw new IllegalArgumentException("SSLSocket não pode ser null");
        }
        
        this.clientSocket = clientSocket;
        try {
            this.inputStream = new BufferedInputStream(clientSocket.getInputStream());
            this.outputStream = clientSocket.getOutputStream();
        } catch (IOException e) {
            logError("Erro ao obter streams: " + e.getMessage());
        }
    }
    
    // ============================================================================
    // MAIN ANALYSIS FLOW
    // ============================================================================
    
    /**
     * Inicia análise do tráfego
     *
     * Fluxo:
     * 1. Parse headers
     * 2. Verificar host monitorado
     * 3. Extrair payload
     * 4. Parse JSON
     * 5. Classificar e repassar
     */
    public void analyze() {
        try {
            log("Iniciando análise de tráfego");
            
            // Etapa 1: Parse HTTP headers
            parseHeaders();
            
            // Etapa 2: Verificar se é um host que queremos monitorar
            if (hostHeader == null) {
                logError("Host header não encontrado, encerrando");
                writeErrorResponse(400, "Bad Request");
                return;
            }
            
            log("Host de destino: " + hostHeader);

            // Bypass transparente para hosts não monitorados.
            if (!isMonitoredHost(hostHeader)) {
                log("Host não monitorado, fazendo bypass transparente");
                extractPayload();
                UpstreamRelay relay = new UpstreamRelay();
                relay.handleSafeRequest(httpMethod, httpPath, httpVersion, httpHeaders, httpBody, inputStream, outputStream);
                return;
            }

            // Mesmo em host monitorado, só classifica endpoints de IA.
            if (!isPromptEndpoint()) {
                log("Endpoint não relacionado a prompt, fazendo bypass transparente");
                extractPayload();
                UpstreamRelay relay = new UpstreamRelay();
                relay.handleSafeRequest(httpMethod, httpPath, httpVersion, httpHeaders, httpBody, inputStream, outputStream);
                return;
            }
            
            // Etapa 3: Extrair payload
            extractPayload();
            
            // Etapa 4: Parse JSON
            String userPrompt = parseJSON();
            
            if (userPrompt == null || userPrompt.isEmpty()) {
                log("Nenhum prompt isolável encontrado; fazendo fail-open para upstream");
                bypassToUpstream();
                return;
            }
            
            log("Prompt extraído: " + truncate(userPrompt, 100));
            
            // Etapa 5: Classificar e repassar
            classifyAndRelay(userPrompt);
            
        } catch (IOException e) {
            logError("Erro durante análise: " + e.getMessage());
            try {
                if (canBypassAfterError()) {
                    log("Erro durante análise; aplicando fail-open para upstream");
                    bypassToUpstream();
                    return;
                }
            } catch (IOException bypassError) {
                logError("Falha no fail-open: " + bypassError.getMessage());
            }
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }
    
    // ============================================================================
    // ETAPA 1: PARSE HTTP HEADERS
    // ============================================================================
    
    /**
     * Parse HTTP headers
     *
     * Lê linha por linha até encontrar "\r\n\r\n" (fim dos headers)
     * Extrai: método, path, version, headers individuais
     */
    private void parseHeaders() throws IOException {
        log("Parseando HTTP headers...");

        String headerBlock = readHeaderBlock();
        String[] lines = headerBlock.split("\\r?\\n");
        if (lines.length == 0) {
            throw new IOException("Bloco de headers vazio");
        }

        // Primeira linha: REQUEST LINE
        String requestLine = lines[0];
        if (requestLine == null || requestLine.isEmpty()) {
            throw new IOException("Request line vazia");
        }
        
        log("Request line: " + requestLine);
        
        // Parse: "METHOD PATH HTTP/VERSION"
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 3) {
            throw new IOException("Request line inválida: " + requestLine);
        }
        
        this.httpMethod = parts[0];  // GET, POST, etc
        this.httpPath = parts[1];     // /v1/chat/completions
        this.httpVersion = parts[2];  // HTTP/1.1
        
        log("  Método: " + httpMethod);
        log("  Path: " + httpPath);
        log("  Version: " + httpVersion);
        
        // Parse dos headers (até linha vazia)
        for (int index = 1; index < lines.length; index++) {
            String headerLine = lines[index];
            if (headerLine == null || headerLine.isEmpty()) {
                continue;
            }
            int colonIndex = headerLine.indexOf(":");
            if (colonIndex > 0) {
                String key = headerLine.substring(0, colonIndex).trim().toLowerCase();
                String value = headerLine.substring(colonIndex + 1).trim();
                httpHeaders.put(key, value);
                
                if (key.equals("host")) {
                    this.hostHeader = value;
                    log("  Host: " + hostHeader);
                }
            }
        }
        
        log("✓ Headers parseados");
    }

    private String readHeaderBlock() throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;

        while (true) {
            int current = inputStream.read();
            if (current == -1) {
                throw new IOException("Fim do stream antes do fim dos headers");
            }

            headerBytes.write(current);

            if ((matched == 0 || matched == 2) && current == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && current == '\n') {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = 0;
            }

            if (headerBytes.size() > 65536) {
                throw new IOException("Headers HTTP excederam 64KB");
            }
        }

        return headerBytes.toString("UTF-8");
    }
    
    // ============================================================================
    // ETAPA 2: EXTRACT PAYLOAD
    // ============================================================================
    
    /**
     * Extrai o body da requisição HTTP
     *
     * Lê baseado em Content-Length header
     * Se Content-Length não existir, tenta ler até EOF
     */
    private void extractPayload() throws IOException {
        log("Extraindo payload HTTP...");
        
        String contentLengthStr = httpHeaders.get("content-length");
        if (contentLengthStr == null) {
            // Para requests sem body (GET/HEAD em geral), não bloquear lendo até EOF.
            this.httpBody = new byte[0];
            log("Content-Length ausente, assumindo body vazio");
        } else {
            int contentLength = Integer.parseInt(contentLengthStr);
            log("  Content-Length: " + contentLength + " bytes");
            
            if (contentLength > 10_000_000) {  // 10MB limit
                throw new IOException("Payload muito grande: " + contentLength + " bytes");
            }
            
            this.httpBody = new byte[contentLength];
            int bytesRead = 0;
            while (bytesRead < contentLength) {
                int n = inputStream.read(httpBody, bytesRead, contentLength - bytesRead);
                if (n == -1) break;
                bytesRead += n;
            }
            
            log("  Bytes lidos: " + bytesRead);
        }
        
        log("✓ Payload extraído (" + (httpBody != null ? httpBody.length : 0) + " bytes)");
    }
    
    // ============================================================================
    // ETAPA 3: PARSE JSON & EXTRACT USER PROMPT
    // ============================================================================
    
    /**
     * Parse JSON do body e extrai prompt do usuário
     *
     * Procura por:
     * {
     *   "messages": [
     *     {"role": "user", "content": "PROMPT AQUI"}
     *   ]
     * }
     *
     * @return String do prompt do usuário, ou null se não encontrado
     */
    private String parseJSON() throws IOException {
        if (httpBody == null || httpBody.length == 0) {
            logError("Body vazio");
            return null;
        }
        
        String bodyStr = new String(httpBody, "UTF-8");
        log("Body JSON recebido (" + bodyStr.length() + " chars)");
        log("  Amostra: " + truncate(bodyStr, 200));
        
        // TODO: Quando temos Jackson ou Gson disponível, fazer parse real
        // Por enquanto, parse manual simples
        
        // Procurar por "role":"user" seguido de "content":"..."
        return extractUserPromptManual(bodyStr);
    }
    
    /**
     * Extração manual do prompt (sem dependência externa)
     * Quando temos Jackson/Gson, será substituído por parse real
     */
    private String extractUserPromptManual(String jsonStr) {
        // Procurar por: "role":"user"
        // NOTA: variável 'userRolePattern' declarada mas nunca utilizada — a verificação abaixo
        // usa jsonStr.contains("user") em vez de aplicar o regex.
        String userRolePattern = "\"role\"\\s*:\\s*\"user\"";
        
        if (!jsonStr.contains("user")) {
            log("⚠ Nenhum 'user' role encontrado no JSON");
            return null;
        }
        
        // Procurar por: "content":"..." após um user role
        // Pattern simplificado: procurar pela palavra "content" e extrair até a próxima aspas
        int contentIndex = jsonStr.indexOf("\"content\"");
        if (contentIndex == -1) {
            contentIndex = jsonStr.indexOf("\"content\"");
        }
        
        if (contentIndex == -1) {
            log("⚠ Campo 'content' não encontrado");
            return null;
        }
        
        // Procurar a primeira ocorrência de : após "content"
        int colonIndex = jsonStr.indexOf(":", contentIndex);
        if (colonIndex == -1) return null;
        
        // Skipear espaços e achar a primeira aspas
        int startQuote = jsonStr.indexOf("\"", colonIndex);
        if (startQuote == -1) return null;
        
        // Procurar a próxima aspas (fim do conteúdo)
        int endQuote = startQuote + 1;
        while (endQuote < jsonStr.length()) {
            if (jsonStr.charAt(endQuote) == '"' && jsonStr.charAt(endQuote - 1) != '\\') {
                break;
            }
            endQuote++;
        }
        
        if (endQuote >= jsonStr.length()) return null;
        
        String prompt = jsonStr.substring(startQuote + 1, endQuote);
        
        // Unescapepr caracteres JSON
        prompt = unescapeJSON(prompt);
        
        log("✓ Prompt extraído com sucesso");
        return prompt;
    }
    
    /**
     * Unescape de caracteres JSON
     */
    private String unescapeJSON(String str) {
        return str
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    private boolean isMonitoredHost(String hostHeaderValue) {
        String host = normalizeHost(hostHeaderValue);
        Set<String> monitored = getMonitoredHosts();
        return monitored.contains(host);
    }

    private boolean isPromptEndpoint() {
        if (httpPath == null || httpMethod == null) {
            return false;
        }

        if (!"POST".equalsIgnoreCase(httpMethod)) {
            return false;
        }

        String path = httpPath.toLowerCase(Locale.ROOT);
        return path.contains("/v1/chat/completions") || path.contains("/v1/responses");
    }

    private Set<String> getMonitoredHosts() {
        String env = System.getenv("MONITORED_HOSTS");
        if (env == null || env.trim().isEmpty()) {
            return DEFAULT_MONITORED_HOSTS;
        }

        Set<String> hosts = new HashSet<>();
        for (String token : env.split(",")) {
            String h = token.trim().toLowerCase(Locale.ROOT);
            if (!h.isEmpty()) {
                hosts.add(h);
            }
        }
        return hosts.isEmpty() ? DEFAULT_MONITORED_HOSTS : hosts;
    }

    private String normalizeHost(String hostHeaderValue) {
        String raw = hostHeaderValue.trim().toLowerCase(Locale.ROOT);
        int colon = raw.lastIndexOf(':');
        if (colon > 0) {
            return raw.substring(0, colon);
        }
        return raw;
    }
    
    // ============================================================================
    // ETAPA 4: CLASSIFY & RELAY
    // ============================================================================
    
    /**
     * Classifica o prompt via SecurityClassifier e relaia para upstream
     *
     * Integração com UpstreamRelay permanece para o módulo 5.
     *
     * @param userPrompt Prompt extraído do usuário
     */
    private void classifyAndRelay(String userPrompt) {
        log("Classificando prompt...");
        
        try {
            SecurityClassifier classifier = new SecurityClassifier();
            String verdict = classifier.classify(userPrompt);
            UpstreamRelay relay = new UpstreamRelay();
            
            log("Veredito: " + verdict);
            
            if ("SAFE".equals(verdict)) {
                log("✓ Prompt aprovado, relaiando para upstream");
                relay.handleSafeRequest(
                        httpMethod,
                        httpPath,
                        httpVersion,
                        httpHeaders,
                        httpBody,
                    inputStream,
                        outputStream
                );
            } else {
                log("✗ Prompt bloqueado: " + verdict);
                relay.writeBlockedResponse(outputStream);
            }
            
        } catch (Exception e) {
            logError("Erro na classificação: " + e.getMessage());
            try {
                log("Classificação falhou; aplicando fail-open para upstream");
                bypassToUpstream();
            } catch (IOException ignored) {}
        }
    }

    private void bypassToUpstream() throws IOException {
        UpstreamRelay relay = new UpstreamRelay();
        relay.handleSafeRequest(
                httpMethod,
                httpPath,
                httpVersion,
                httpHeaders,
                httpBody,
                inputStream,
                outputStream
        );
    }

    private boolean canBypassAfterError() {
        return httpMethod != null && httpPath != null && httpVersion != null && hostHeader != null;
    }
    
    // ============================================================================
    // HTTP RESPONSE HELPERS
    // ============================================================================
    
    /**
     * Escreve resposta de erro HTTP
     */
    private void writeErrorResponse(int statusCode, String message) throws IOException {
        String statusLine;
        switch (statusCode) {
            case 400: statusLine = "400 Bad Request"; break;
            case 403: statusLine = "403 Forbidden"; break;
            case 500: statusLine = "500 Internal Server Error"; break;
            default: statusLine = statusCode + " Error"; break;
        }
        
        String jsonError = "{\"error\": \"" + message + "\"}";
        
        String response = "HTTP/1.1 " + statusLine + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + jsonError.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                jsonError;
        
        outputStream.write(response.getBytes("UTF-8"));
        outputStream.flush();
        
        log("Resposta de erro enviada: " + statusLine);
    }
    
    // ============================================================================
    // UTILITY METHODS
    // ============================================================================
    
    /**
     * Ler todos os bytes do InputStream
     *
     * NOTA: método nunca chamado internamente nesta classe. A leitura do body é feita
     * via extractPayload() com leitura baseada em Content-Length. Pode ser removido
     * ou utilizado como substituto de extractPayload() para body sem Content-Length.
     */
    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[8192];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }
    
    /**
     * Truncar string para logging
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "null";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    /**
     * Log info message
     */
    private static void log(String message) {
        System.out.println(LOG_PREFIX + " " + message);
    }
    
    /**
     * Log error message
     */
    private static void logError(String message) {
        System.err.println(LOG_PREFIX + " [ERROR] " + message);
    }
}
