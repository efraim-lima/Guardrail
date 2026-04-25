import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

// NOTA: Esta classe não está sendo instanciada em Main.java. O fluxo ativo usa apenas
// PromptValidationWebhookServer. Para ativar a interceptação TLS transparente, instanciar
// TransparentTLSInterceptor em Main.start() com CertAuthority + threadPool e iniciar a thread.

/**
 * TransparentTLSInterceptor - Interceptação transparente com SNI sniffing.
 *
 * Fluxo:
 * 1) Accept em ServerSocket comum (:8443)
 * 2) Sniff do ClientHello para extrair SNI sem consumir fluxo
 * 3) Promoção do socket bruto para SSLSocket de servidor
 * 4) Leitura de HTTP em texto plano após handshake
 * 5) Pausa ativa: consulta Ollama SAFE/UNSAFE
 * 6) SAFE => relay para host SNI, UNSAFE => 403
 */
public class TransparentTLSInterceptor implements Runnable {
    private static final String LOG_PREFIX = "[TLSInterceptor]";

    private static final int MAX_SNI_SNIFF_BYTES = 32768;
    private static final int HEADER_LIMIT_BYTES = 65536;
    // NOTA: OLLAMA_URL e OLLAMA_MODEL estão hardcoded aqui, bypassando SecurityClassifier
    // (que lê OLLAMA_ENDPOINT e OLLAMA_MODEL das variáveis de ambiente e usa modelo "qwen2.5:1.5b").
    // Considerar delegar classifyPrompt() para SecurityClassifier para unificar configuração e modelo.
    private static final String OLLAMA_URL = "http://127.0.0.1:11434/api/generate";
    private static final String OLLAMA_MODEL = "llama3.2";

    private static final SSLSocketFactory INSECURE_SSL_SOCKET_FACTORY = buildInsecureSslSocketFactory();

    private final int port;
    private final CertAuthority certAuthority;
    private final ExecutorService executorService;
    private volatile boolean running = true;
    private ServerSocket serverSocket;

    public TransparentTLSInterceptor(int port, CertAuthority certAuthority, ExecutorService executorService) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Porta inválida: " + port);
        }
        if (certAuthority == null) {
            throw new IllegalArgumentException("CertAuthority não pode ser null");
        }
        if (executorService == null) {
            throw new IllegalArgumentException("ExecutorService não pode ser null");
        }

        this.port = port;
        this.certAuthority = certAuthority;
        this.executorService = executorService;
    }

    @Override
    public void run() {
        try {
            this.serverSocket = new ServerSocket(port, 128, InetAddress.getByName("0.0.0.0"));
            this.serverSocket.setReuseAddress(true);

            log("Escutando em 0.0.0.0:" + port);
            log("Aguardando conexões HTTPS redirecionadas por iptables...");

            while (running) {
                try {
                    Socket rawClient = serverSocket.accept();
                    rawClient.setSoTimeout(30000);

                    String clientAddr = rawClient.getInetAddress().getHostAddress();
                    int clientPort = rawClient.getPort();
                    log("✓ Nova conexão aceita de " + clientAddr + ":" + clientPort);

                    executorService.submit(() -> handleClient(rawClient));
                } catch (SocketTimeoutException e) {
                    if (!running) {
                        break;
                    }
                } catch (IOException e) {
                    if (running) {
                        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                        if (message.contains("broken pipe") || message.contains("connection reset")) {
                            log("Conexão encerrada pelo cliente durante accept: " + e.getMessage());
                        } else {
                            logError("Erro I/O ao aceitar conexão: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logError("Erro fatal no Interceptor: " + e.getMessage());
        } finally {
            closeServerSocketQuietly();
            log("Interceptor encerrado");
        }
    }

    private void handleClient(Socket rawClient) {
        BufferedInputStream bufferedRawIn = null;
        SniffResult sniff = null;
        try {
            bufferedRawIn = new BufferedInputStream(rawClient.getInputStream());

            sniff = sniffSniFromClientHello(bufferedRawIn);
            if (!sniff.isTls) {
                log("Conexão não-TLS recebida em 8443; encerrando sem erro");
                closeQuietly(rawClient);
                return;
            }

            if (sniff.sniHost == null || sniff.sniHost.isBlank()) {
                log("SNI ausente no ClientHello; usando Host do HTTP quando disponível");
            } else {
                log("SNI detectado: " + sniff.sniHost);
            }

            String effectiveSni = (sniff.sniHost != null && !sniff.sniHost.isBlank())
                    ? sniff.sniHost : "_fallback_";
            SSLContext hostCtx = certAuthority.contextForHost(effectiveSni);

            PrebufferedSocket prebufferedSocket = new PrebufferedSocket(rawClient, bufferedRawIn);
            SSLSocket sslClientSocket = (SSLSocket) hostCtx.getSocketFactory().createSocket(
                    prebufferedSocket,
                    rawClient.getInetAddress().getHostAddress(),
                    rawClient.getPort(),
                    true
            );
            sslClientSocket.setUseClientMode(false);
            sslClientSocket.startHandshake();

            handleDecryptedHttp(sslClientSocket, sniff.sniHost);
        } catch (SSLHandshakeException e) {
            String fallbackHost = (sniff != null) ? sniff.sniHost : null;
            if (fallbackHost != null && !fallbackHost.isBlank() && bufferedRawIn != null) {
                log("Handshake MITM falhou; aplicando túnel TLS bruto para " + fallbackHost);
                try {
                    bufferedRawIn.reset();
                    tunnelRawTls(rawClient, bufferedRawIn, fallbackHost);
                    return;
                } catch (Exception tunnelError) {
                    logError("Falha no fallback de túnel TLS: " + tunnelError.getMessage());
                }
            }
            logError("Handshake TLS falhou e sem fallback viável: " + e.getMessage());
            closeQuietly(rawClient);
            return;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("broken pipe") || msg.contains("connection reset")) {
                log("Conexão encerrada pelo cliente: " + e.getMessage());
            } else {
                logError("Falha ao processar cliente: " + e.getMessage());
            }
            closeQuietly(rawClient);
        }
    }

    private void handleDecryptedHttp(SSLSocket clientTlsSocket, String sniHost) throws Exception {
        try (SSLSocket client = clientTlsSocket) {
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            ParsedHttpRequest request = readHttpRequest(in);

            String targetHost = selectTargetHost(sniHost, request.headers.get("host"));
            if (targetHost == null || targetHost.isBlank()) {
                writeSimpleError(out, 400, "Missing target host");
                return;
            }

            request.headers.put("host", targetHost);

            String verdict = "SAFE";
            if (isPromptEndpoint(request.method, request.path)) {
                String prompt = extractPromptFromJson(request.body);
                if (prompt != null && !prompt.isBlank()) {
                    verdict = classifyPrompt(prompt);
                    log("Veredito de segurança: " + verdict);
                } else {
                    log("Prompt não extraído; fail-open para upstream");
                }
            }

            if ("UNSAFE".equals(verdict)) {
                writeSimpleError(out, 403, "Blocked by AgentK Security Policy");
                return;
            }

            if (isOpenAiPromptRequest(targetHost, request)) {
                forwardOpenAiRequestFromScratch(targetHost, request, out);
                return;
            }

            relayToUpstream(targetHost, request, in, out);
        }
    }

    private boolean isOpenAiPromptRequest(String targetHost, ParsedHttpRequest request) {
        if (targetHost == null || request == null) {
            return false;
        }
        String host = targetHost.toLowerCase(Locale.ROOT);
        return (host.equals("api.openai.com") || host.endsWith(".openai.com"))
                && isPromptEndpoint(request.method, request.path);
    }

    private void forwardOpenAiRequestFromScratch(String targetHost,
                                                 ParsedHttpRequest request,
                                                 OutputStream clientOut) throws Exception {
        String uri = "https://" + targetHost + (request.path == null ? "/" : request.path);
        String body = new String(request.body == null ? new byte[0] : request.body, StandardCharsets.UTF_8);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(60));

        copyAllowedHeaders(request.headers, builder);

        if (request.body != null && request.body.length > 0) {
            builder.method(request.method == null ? "POST" : request.method,
                    HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.method(request.method == null ? "GET" : request.method,
                    HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<byte[]> upstreamResp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

        writeHttpResponse(clientOut, upstreamResp.statusCode(), upstreamResp.headers().map(), upstreamResp.body());
        log("Resposta OpenAI (requisição nova) enviada, status=" + upstreamResp.statusCode());
    }

    private void copyAllowedHeaders(Map<String, String> incoming, HttpRequest.Builder builder) {
        if (incoming == null) {
            return;
        }

        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }

            String lower = key.toLowerCase(Locale.ROOT);
            if ("host".equals(lower)
                    || "content-length".equals(lower)
                    || "connection".equals(lower)
                    || "proxy-connection".equals(lower)
                    || "transfer-encoding".equals(lower)) {
                continue;
            }

            builder.header(key, value);
        }
    }

    private void writeHttpResponse(OutputStream out,
                                   int statusCode,
                                   Map<String, List<String>> headers,
                                   byte[] body) throws IOException {
        byte[] safeBody = body == null ? new byte[0] : body;
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append("\r\n");

        if (headers != null) {
            for (Map.Entry<String, List<String>> h : headers.entrySet()) {
                if (h.getKey() == null || h.getValue() == null) {
                    continue;
                }
                String lower = h.getKey().toLowerCase(Locale.ROOT);
                if ("content-length".equals(lower)
                        || "transfer-encoding".equals(lower)
                        || "connection".equals(lower)) {
                    continue;
                }
                for (String value : h.getValue()) {
                    if (value != null) {
                        sb.append(h.getKey()).append(": ").append(value).append("\r\n");
                    }
                }
            }
        }

        sb.append("Content-Length: ").append(safeBody.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(safeBody);
        out.flush();
    }

    private void tunnelRawTls(Socket clientSocket, BufferedInputStream clientIn, String targetHost) throws IOException {
        try (Socket upstream = new Socket(targetHost, 443);
             Socket client = clientSocket) {

            upstream.setSoTimeout(30000);
            InputStream upstreamIn = upstream.getInputStream();
            OutputStream upstreamOut = upstream.getOutputStream();
            OutputStream clientOut = client.getOutputStream();

            Thread clientToUpstream = new Thread(() -> pipe(clientIn, upstreamOut, "cliente->upstream(raw)"),
                    "raw-client-upstream");
            clientToUpstream.setDaemon(true);
            clientToUpstream.start();

            pipe(upstreamIn, clientOut, "upstream->cliente(raw)");
        }
    }

    private SniffResult sniffSniFromClientHello(BufferedInputStream in) throws IOException {
        in.mark(MAX_SNI_SNIFF_BYTES);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];

        while (captured.size() < MAX_SNI_SNIFF_BYTES) {
            int n = in.read(chunk);
            if (n == -1) {
                in.reset();
                return new SniffResult(false, null);
            }

            captured.write(chunk, 0, n);
            byte[] data = captured.toByteArray();

            ParseState state = tryExtractSni(data);
            if (state.status == ParseStatus.COMPLETE) {
                in.reset();
                return new SniffResult(true, state.sniHost);
            }
            if (state.status == ParseStatus.NON_TLS) {
                in.reset();
                return new SniffResult(false, null);
            }
        }

        in.reset();
        return new SniffResult(true, null);
    }

    private ParseState tryExtractSni(byte[] data) {
        if (data.length < 5) {
            return ParseState.needMore();
        }

        if ((data[0] & 0xFF) != 22) {
            return ParseState.nonTls();
        }

        ByteArrayOutputStream handshakeBuf = new ByteArrayOutputStream();
        int pos = 0;
        int handshakeTotal = -1;

        while (pos + 5 <= data.length) {
            int contentType = data[pos] & 0xFF;
            int versionMajor = data[pos + 1] & 0xFF;
            int recordLen = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);

            if (contentType != 22 || versionMajor != 3) {
                return ParseState.nonTls();
            }

            if (pos + 5 + recordLen > data.length) {
                break;
            }

            handshakeBuf.write(data, pos + 5, recordLen);
            pos += 5 + recordLen;

            byte[] hs = handshakeBuf.toByteArray();
            if (hs.length >= 4 && handshakeTotal < 0) {
                int hsType = hs[0] & 0xFF;
                if (hsType != 1) {
                    return ParseState.nonTls();
                }
                handshakeTotal = 4 + ((hs[1] & 0xFF) << 16) + ((hs[2] & 0xFF) << 8) + (hs[3] & 0xFF);
            }

            if (handshakeTotal > 0 && hs.length >= handshakeTotal) {
                String sni = parseSniFromClientHello(Arrays.copyOf(hs, handshakeTotal));
                return ParseState.complete(sni);
            }
        }

        if (data.length >= MAX_SNI_SNIFF_BYTES) {
            return ParseState.complete(null);
        }
        return ParseState.needMore();
    }

    private String parseSniFromClientHello(byte[] clientHelloHandshake) {
        int offset = 4;

        if (clientHelloHandshake.length < offset + 2 + 32 + 1) {
            return null;
        }

        offset += 2;
        offset += 32;

        int sessionIdLen = clientHelloHandshake[offset] & 0xFF;
        offset += 1;
        if (offset + sessionIdLen > clientHelloHandshake.length) {
            return null;
        }
        offset += sessionIdLen;

        if (offset + 2 > clientHelloHandshake.length) {
            return null;
        }
        int cipherLen = u16(clientHelloHandshake, offset);
        offset += 2;
        if (offset + cipherLen > clientHelloHandshake.length) {
            return null;
        }
        offset += cipherLen;

        if (offset + 1 > clientHelloHandshake.length) {
            return null;
        }
        int compLen = clientHelloHandshake[offset] & 0xFF;
        offset += 1;
        if (offset + compLen > clientHelloHandshake.length) {
            return null;
        }
        offset += compLen;

        if (offset == clientHelloHandshake.length) {
            return null;
        }

        if (offset + 2 > clientHelloHandshake.length) {
            return null;
        }
        int extTotalLen = u16(clientHelloHandshake, offset);
        offset += 2;

        int extEnd = offset + extTotalLen;
        if (extEnd > clientHelloHandshake.length) {
            return null;
        }

        while (offset + 4 <= extEnd) {
            int extType = u16(clientHelloHandshake, offset);
            int extLen = u16(clientHelloHandshake, offset + 2);
            offset += 4;

            if (offset + extLen > extEnd) {
                return null;
            }

            if (extType == 0) {
                int p = offset;
                if (p + 2 > offset + extLen) {
                    return null;
                }
                int listLen = u16(clientHelloHandshake, p);
                p += 2;
                int listEnd = Math.min(p + listLen, offset + extLen);

                while (p + 3 <= listEnd) {
                    int nameType = clientHelloHandshake[p] & 0xFF;
                    int nameLen = u16(clientHelloHandshake, p + 1);
                    p += 3;
                    if (p + nameLen > listEnd) {
                        return null;
                    }
                    if (nameType == 0) {
                        return new String(clientHelloHandshake, p, nameLen, StandardCharsets.US_ASCII)
                                .toLowerCase(Locale.ROOT);
                    }
                    p += nameLen;
                }
                return null;
            }

            offset += extLen;
        }

        return null;
    }

    private ParsedHttpRequest readHttpRequest(InputStream in) throws IOException {
        String headerBlock = readHeaderBlock(in);
        String[] lines = headerBlock.split("\\r?\\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            throw new IOException("Request line vazia");
        }

        String[] requestLine = lines[0].split("\\s+");
        if (requestLine.length < 3) {
            throw new IOException("Request line inválida: " + lines[0]);
        }

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            headers.put(key, value);
        }

        byte[] body = readHttpBody(in, headers);

        ParsedHttpRequest req = new ParsedHttpRequest();
        req.method = requestLine[0];
        req.path = requestLine[1];
        req.version = requestLine[2];
        req.headers = headers;
        req.body = body;
        return req;
    }

    private String readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;

        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("EOF antes do fim dos headers");
            }

            bytes.write(b);

            if ((matched == 0 || matched == 2) && b == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && b == '\n') {
                matched++;
                if (matched == 4) {
                    break;
                }
            } else {
                matched = 0;
            }

            if (bytes.size() > HEADER_LIMIT_BYTES) {
                throw new IOException("Headers HTTP excederam 64KB");
            }
        }

        return bytes.toString(StandardCharsets.UTF_8);
    }

    private byte[] readHttpBody(InputStream in, Map<String, String> headers) throws IOException {
        String contentLength = headers.get("content-length");
        if (contentLength == null || contentLength.isBlank()) {
            return new byte[0];
        }

        int len;
        try {
            len = Integer.parseInt(contentLength.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Content-Length inválido: " + contentLength);
        }

        if (len < 0 || len > 10_000_000) {
            throw new IOException("Content-Length fora do limite: " + len);
        }

        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(body, off, len - off);
            if (n == -1) {
                break;
            }
            off += n;
        }

        if (off < len) {
            throw new IOException("Body incompleto: lidos=" + off + " esperado=" + len);
        }

        return body;
    }

    private boolean isPromptEndpoint(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }

        String p = path.toLowerCase(Locale.ROOT);
        return p.contains("/v1/chat/completions") || p.contains("/v1/responses");
    }

    // NOTA: duplica a lógica de TrafficAnalyzer.extractUserPromptManual(). Se TrafficAnalyzer
    // for integrado ao fluxo, este método pode ser removido em favor daquele.
    private String extractPromptFromJson(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        int userIdx = body.indexOf("\"role\"");
        if (userIdx < 0) {
            return null;
        }
        int userVal = body.indexOf("\"user\"", userIdx);
        if (userVal < 0) {
            return null;
        }

        int contentKey = body.indexOf("\"content\"", userVal);
        if (contentKey < 0) {
            return null;
        }
        int colon = body.indexOf(':', contentKey);
        if (colon < 0) {
            return null;
        }
        int start = body.indexOf('"', colon);
        if (start < 0) {
            return null;
        }

        int end = start + 1;
        while (end < body.length()) {
            if (body.charAt(end) == '"' && body.charAt(end - 1) != '\\') {
                break;
            }
            end++;
        }
        if (end >= body.length()) {
            return null;
        }

        return body.substring(start + 1, end)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    // NOTA: duplica a responsabilidade de SecurityClassifier. Usa modelo e URL hardcoded
    // em vez de ler variáveis de ambiente (OLLAMA_ENDPOINT / OLLAMA_MODEL). Considerar
    // substituir pelo: new SecurityClassifier().classify(prompt)
    private String classifyPrompt(String prompt) {
        try {
            String aiPrompt = "Responda apenas com SAFE ou UNSAFE. "
                    + "Avalie risco de abuso, malware, exfiltração, bypass de políticas.\n"
                    + "Prompt:\n" + prompt;

            String payload = "{"
                    + "\"model\":\"" + escapeJson(OLLAMA_MODEL) + "\","
                    + "\"prompt\":\"" + escapeJson(aiPrompt) + "\","
                    + "\"stream\":false"
                    + "}";

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log("Ollama retornou HTTP " + response.statusCode() + ", usando SAFE por fail-open");
                return "SAFE";
            }

            String upper = response.body() == null ? "" : response.body().toUpperCase(Locale.ROOT);
            if (upper.contains("UNSAFE")) {
                return "UNSAFE";
            }
            return "SAFE";
        } catch (Exception e) {
            log("Falha ao consultar Ollama: " + e.getMessage() + "; usando SAFE por fail-open");
            return "SAFE";
        }
    }

    // NOTA: duplica a responsabilidade de UpstreamRelay.handleSafeRequest(). Considerar
    // delegar para UpstreamRelay para eliminar duplicação e centralizar o relay em um único módulo.
    private void relayToUpstream(String targetHost,
                                 ParsedHttpRequest request,
                                 InputStream clientInput,
                                 OutputStream clientOutput) throws IOException {
        try (SSLSocket upstream = createUpstreamSocketWithFallback(targetHost, 443)) {
            upstream.setSoTimeout(30000);

            OutputStream upstreamOut = upstream.getOutputStream();
            InputStream upstreamIn = upstream.getInputStream();

            writeRequestToUpstream(request, targetHost, upstreamOut);

            if (isStreamingRequest(request.path, request.headers)) {
                Thread clientToUpstream = new Thread(() -> pipe(clientInput, upstreamOut, "cliente->upstream"),
                        "relay-client-upstream");
                clientToUpstream.setDaemon(true);
                clientToUpstream.start();

                pipe(upstreamIn, clientOutput, "upstream->cliente");
            } else {
                pipe(upstreamIn, clientOutput, "upstream->cliente");
            }

            clientOutput.flush();
            log("Relay upstream concluído");
        }
    }

    // NOTA: duplica UpstreamRelay.writeRequestToUpstream() — mesma lógica de montagem do request HTTP.
    private void writeRequestToUpstream(ParsedHttpRequest req,
                                        String targetHost,
                                        OutputStream upstreamOut) throws IOException {
        String method = req.method == null ? "GET" : req.method;
        String path = req.path == null ? "/" : req.path;
        String version = req.version == null ? "HTTP/1.1" : req.version;

        boolean tunnelMode = isStreamingRequest(path, req.headers);

        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");

        boolean hasContentLength = false;
        boolean hasTransferEncoding = false;

        for (Map.Entry<String, String> entry : req.headers.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (key == null || val == null) {
                continue;
            }
            String lower = key.toLowerCase(Locale.ROOT);
            if ("proxy-connection".equals(lower)) {
                continue;
            }
            if (!tunnelMode && "connection".equals(lower)) {
                continue;
            }
            if ("host".equals(lower)) {
                sb.append("host: ").append(targetHost).append("\r\n");
                continue;
            }
            if ("content-length".equals(lower)) {
                hasContentLength = true;
            }
            if ("transfer-encoding".equals(lower)) {
                hasTransferEncoding = true;
            }
            sb.append(key).append(": ").append(val).append("\r\n");
        }

        if (req.body != null && req.body.length > 0 && !hasContentLength && !hasTransferEncoding) {
            sb.append("content-length: ").append(req.body.length).append("\r\n");
        }
        if (!tunnelMode) {
            sb.append("connection: close\r\n");
        }

        sb.append("\r\n");
        upstreamOut.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (req.body != null && req.body.length > 0) {
            upstreamOut.write(req.body);
        }
        upstreamOut.flush();
    }

    private boolean isStreamingRequest(String path, Map<String, String> headers) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        String upgrade = valueOf(headers, "upgrade");
        String connection = valueOf(headers, "connection");
        String accept = valueOf(headers, "accept");

        if (p.contains("/_stcore/stream")) {
            return true;
        }
        if (upgrade.contains("websocket")) {
            return true;
        }
        if (connection.contains("upgrade")) {
            return true;
        }
        return accept.contains("text/event-stream");
    }

    private String selectTargetHost(String sniHost, String hostHeader) {
        if (sniHost != null && !sniHost.isBlank()) {
            return sniHost;
        }
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String h = hostHeader.trim();
        int idx = h.lastIndexOf(':');
        if (idx > 0) {
            return h.substring(0, idx);
        }
        return h;
    }

    private SSLSocket createUpstreamSocketWithFallback(String host, int port) throws IOException {
        SSLSocketFactory defaultFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) defaultFactory.createSocket(host, port);

        try {
            socket.startHandshake();
            return socket;
        } catch (SSLHandshakeException e) {
            closeQuietly(socket);
            log("Aviso: falha de validação TLS no upstream (" + e.getMessage() + ")");
            log("Aplicando fallback inseguro para manter o bypass transparente");

            SSLSocket insecure = (SSLSocket) INSECURE_SSL_SOCKET_FACTORY.createSocket(host, port);
            SSLParameters params = insecure.getSSLParameters();
            params.setEndpointIdentificationAlgorithm(null);
            insecure.setSSLParameters(params);
            insecure.startHandshake();
            return insecure;
        }
    }

    private void writeSimpleError(OutputStream out, int statusCode, String message) throws IOException {
        String statusLine = statusCode == 403 ? "403 Forbidden" : "400 Bad Request";
        String json = "{\"error\":\"" + message + "\"}";
        String response = "HTTP/1.1 " + statusLine + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + json;
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void pipe(InputStream in, OutputStream out, String direction) {
        byte[] buffer = new byte[8192];
        int n;
        try {
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            log("Canal " + direction + " encerrado: " + e.getMessage());
        }
    }

    private String valueOf(Map<String, String> headers, String key) {
        String value = headers.get(key);
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static SSLSocketFactory buildInsecureSslSocketFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Falha ao criar SSL factory insegura", e);
        }
    }

    private static int u16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public void stop() {
        this.running = false;
        closeServerSocketQuietly();
    }

    private void closeServerSocketQuietly() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void log(String message) {
        System.out.println(LOG_PREFIX + " " + message);
    }

    private static void logError(String message) {
        System.err.println(LOG_PREFIX + " [ERROR] " + message);
    }

    private enum ParseStatus {
        NEED_MORE,
        COMPLETE,
        NON_TLS
    }

    private static class ParseState {
        final ParseStatus status;
        final String sniHost;

        private ParseState(ParseStatus status, String sniHost) {
            this.status = status;
            this.sniHost = sniHost;
        }

        static ParseState needMore() {
            return new ParseState(ParseStatus.NEED_MORE, null);
        }

        static ParseState complete(String sniHost) {
            return new ParseState(ParseStatus.COMPLETE, sniHost);
        }

        static ParseState nonTls() {
            return new ParseState(ParseStatus.NON_TLS, null);
        }
    }

    private static class SniffResult {
        final boolean isTls;
        final String sniHost;

        SniffResult(boolean isTls, String sniHost) {
            this.isTls = isTls;
            this.sniHost = sniHost;
        }
    }

    private static class ParsedHttpRequest {
        String method;
        String path;
        String version;
        Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];
    }

    /**
     * Socket wrapper para reutilizar o mesmo fluxo já lido no sniff.
     */
    private static class PrebufferedSocket extends Socket {
        private final Socket delegate;
        private final InputStream in;

        PrebufferedSocket(Socket delegate, InputStream in) {
            this.delegate = delegate;
            this.in = in;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return delegate.getOutputStream();
        }

        @Override
        public synchronized void close() throws IOException {
            delegate.close();
        }

        @Override
        public InetAddress getInetAddress() {
            return delegate.getInetAddress();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            delegate.setSoTimeout(timeout);
        }

        @Override
        public int getSoTimeout() throws SocketException {
            return delegate.getSoTimeout();
        }
    }
}
