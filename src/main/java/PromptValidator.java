import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * PromptValidator - Endpoint de validação de segurança.
 */
public class PromptValidator implements Runnable {
    private static final String LOG_PREFIX = "[PromptWebhook]";
    private static final long RESULT_POLL_TIMEOUT = Long.parseLong(envOr("OLLAMA_RESULT_TIMEOUT_SECONDS", "150"));

    private final int port;
    private final String path;
    private final OllamaJobQueue jobQueue;
    private final ExecutorService executorService;

    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 100;
        }
    });
    
    private static final Pattern BLOCKED_PATTERN = Pattern.compile("(?i).*(rm -rf|drop table|bypass|ignore todas as instru|ignorar todas as instru|esqueça todas).*");

    private volatile boolean running = true;
    private HttpServer server;

    public PromptValidator(int port, String path, OllamaJobQueue jobQueue, ExecutorService executorService) {
        this.port = port;
        this.path = (path == null || path.isBlank()) ? "/validar" : path;
        this.jobQueue = jobQueue;
        this.executorService = executorService;
    }

    @Override
    public void run() {
        try {
            String keystorePath = System.getenv("KEYSTORE_PATH");
            if (keystorePath != null && !keystorePath.isBlank()) {
                String keystorePass = System.getenv().getOrDefault("KEYSTORE_PASSWORD", "gateway-secret");
                char[] password = keystorePass.toCharArray();
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(keystorePath)) { ks.load(fis, password); }
                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, password);
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
                tmf.init(ks);
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
                
                HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("0.0.0.0", port), 128);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    public void configure(HttpsParameters params) {
                        try { params.setSSLParameters(getSSLContext().getDefaultSSLParameters()); } catch (Exception ex) {}
                    }
                });
                this.server = httpsServer;
                log("Webhook iniciado com HTTPS em https://0.0.0.0:" + port + path);
            } else {
                this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 128);
                log("Webhook iniciado com HTTP em http://0.0.0.0:" + port + path);
            }
            
            this.server.createContext(path, new ValidateHandler());
            this.server.createContext("/resultado", new ResultHandler());
            this.server.createContext("/health", new HealthHandler());
            this.server.setExecutor(executorService);
            this.server.start();
            
            log("Healthcheck em http(s)://0.0.0.0:" + port + "/health");
            while (running) { Thread.sleep(1000); }
        } catch (Exception e) {
            logError("Falha no webhook: " + e.getMessage());
        } finally { stop(); }
    }

    public void stop() {
        running = false;
        if (server != null) { server.stop(0); log("Webhook encerrado"); }
    }

    private class ValidateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }
                
                String body = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
                String prompt = extractPrompt(body, exchange.getRequestHeaders().getFirst("Content-Type"));
                
                if (prompt == null || prompt.isBlank()) {
                    writeJson(exchange, 400, "{\"error\":\"prompt ausente\"}");
                    return;
                }
                
                String sanitized = sanitizeAndTruncate(prompt);
                String sourceIp = exchange.getRemoteAddress().getAddress().getHostAddress();
                boolean isTestFlow = "true".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Test-Flow"));

                if (isTestFlow) { 
                    AuditLogger.logTestVerdict(prompt, "RECEIVED", sourceIp); 
                } else { 
                    AuditLogger.log("anonymous", "PROMPT_VALIDATION", "prompt", "RECEIVED", sourceIp, 
                        "snippet=" + (sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized)); 
                }

                // 1. Bloqueio Heurístico
                if (BLOCKED_PATTERN.matcher(sanitized).matches()) {
                    log("Bloqueado por heurística: " + sanitized);
                    String jobId = jobQueue.submitResolved(sanitized, "Reprovado");
                    writeJson(exchange, 202, "{\"job_id\":\"" + escapeJson(jobId) + "\",\"status\":\"QUEUED\"}");
                    return;
                }
                
                // 2. Cache local
                String cached = cache.get(sanitized);
                if (cached != null) {
                    log("Cache hit: " + cached);
                    String jobId = jobQueue.submitResolved(sanitized, cached);
                    writeJson(exchange, 202, "{\"job_id\":\"" + escapeJson(jobId) + "\",\"status\":\"QUEUED\"}");
                    return;
                }
                
                // 3. Fila assíncrona (Ollama)
                String jobId = jobQueue.submit(sanitized, isTestFlow);
                writeJson(exchange, 202, "{\"job_id\":\"" + escapeJson(jobId) + "\",\"status\":\"QUEUED\"}");
                
            } catch (Exception e) {
                logError("Erro no /validar: " + e.getMessage());
                writeJson(exchange, 500, "{\"error\":\"internal_error\"}");
            }
        }
    }

    private class ResultHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String uriPath = exchange.getRequestURI().getPath();
                String prefix = "/resultado/";
                if (!uriPath.startsWith(prefix) || uriPath.length() <= prefix.length()) {
                    writeJson(exchange, 400, "{\"error\":\"job_id ausente\"}");
                    return;
                }
                
                String jobId = uriPath.substring(prefix.length());
                if (!jobQueue.exists(jobId)) {
                    writeJson(exchange, 404, "{\"error\":\"job_not_found\"}");
                    return;
                }
                
                OllamaJobQueue.AwaitResult result = jobQueue.awaitResult(jobId, RESULT_POLL_TIMEOUT);
                if (result.status == OllamaJobQueue.JobStatus.DONE) {
                    writeJson(exchange, 200, "{\"job_id\":\"" + escapeJson(jobId) + "\",\"veredito\":\"" + escapeJson(result.verdict) + "\",\"status\":\"DONE\"}");
                } else {
                    writeJson(exchange, 202, "{\"job_id\":\"" + escapeJson(jobId) + "\",\"status\":\"PROCESSING\"}");
                }
            } catch (Exception e) {
                writeJson(exchange, 500, "{\"error\":\"internal_error\"}");
            }
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, "{\"status\":\"UP\"}");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); }
        return out.toByteArray();
    }

    private static String extractPrompt(String body, String contentType) {
        if (body == null) return null;
        if (body.trim().startsWith("{")) {
            String p = extractJsonStringField(body, "prompt");
            if (p != null) return p;
            p = extractJsonStringField(body, "input");
            if (p != null) return p;
            return extractJsonStringField(body, "message");
        }
        return body.trim();
    }

    private static String sanitizeAndTruncate(String input) {
        if (input == null) return "";
        String s = input.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        String[] w = s.split("\\s+");
        if (w.length <= 300) return s.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) { sb.append(w[i]).append(" "); }
        return sb.toString().trim();
    }

    private static String extractJsonStringField(String json, String field) {
        String token = "\"" + field + "\"";
        int idx = json.indexOf(token);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + token.length());
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) {
                if (c == 'n') sb.append('\n'); else if (c == 'r') sb.append('\r'); else if (c == 't') sb.append('\t');
                else if (c == '"') sb.append('"'); else if (c == '\\') sb.append('\\');
                else sb.append(c);
                esc = false; continue;
            }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') return sb.toString();
            sb.append(c);
        }
        return null;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private static String escapeJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String envOr(String k, String f) {
        String v = System.getenv(k);
        return (v != null && !v.isBlank()) ? v : f;
    }

    private static void log(String msg) { System.out.println(LOG_PREFIX + " " + msg); }
    private static void logError(String msg) { System.err.println(LOG_PREFIX + " [ERROR] " + msg); }
}
