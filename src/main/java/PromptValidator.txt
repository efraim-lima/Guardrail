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
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * PromptValidator
 *
 * Endpoint HTTP para receber prompts do cliente MCP e retornar veredito.
 *
 * URL padrão:
 *   POST http://0.0.0.0:8080/validar
 *
 * Corpo aceito:
 *   1) JSON: {"prompt":"..."}
 *   2) Texto puro: "..."
 */
public class PromptValidator implements Runnable {
    private static final String LOG_PREFIX = "[PromptWebhook]";

    private final int port;
    private final String path;
    private final SecurityClassifier classifier;
    private final ExecutorService executorService;

    private volatile boolean running = true;
    private HttpServer server;

    public PromptValidator(int port,
                                         String path,
                                         SecurityClassifier classifier,
                                         ExecutorService executorService) {
        this.port = port;
        this.path = (path == null || path.isBlank()) ? "/validar" : path;
        this.classifier = classifier;
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
                try (FileInputStream fis = new FileInputStream(keystorePath)) {
                    ks.load(fis, password);
                }
                
                KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
                kmf.init(ks, password);
                
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
                tmf.init(ks);
                
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
                
                HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress("0.0.0.0", port), 128);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                    public void configure(HttpsParameters params) {
                        try {
                            SSLContext c = getSSLContext();
                            params.setSSLParameters(c.getDefaultSSLParameters());
                        } catch (Exception ex) {
                            System.out.println("Erro ao configurar HTTPS");
                        }
                    }
                });
                this.server = httpsServer;
                log("Webhook de validação iniciado com HTTPS em https://0.0.0.0:" + port + path);
            } else {
                this.server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 128);
                log("Webhook de validação iniciado com HTTP em http://0.0.0.0:" + port + path);
            }
            
            this.server.createContext(path, new ValidateHandler());
            this.server.createContext("/health", new HealthHandler());
            this.server.setExecutor(executorService);
            this.server.start();

            log("Healthcheck em http(s)://0.0.0.0:" + port + "/health");

            while (running) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            logError("Falha no webhook: " + e.getMessage());
        } finally {
            stop();
        }
    }

    public void stop() {
        running = false;
        if (server != null) {
            server.stop(0);
            log("Webhook encerrado");
        }
    }

    private class ValidateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                if (!"POST".equalsIgnoreCase(method)) {
                    writeJson(exchange, 405, "{\"error\":\"Method not allowed\",\"allowed\":\"POST\"}");
                    return;
                }

                byte[] bodyBytes = readAll(exchange.getRequestBody());
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                String prompt = extractPrompt(body, contentType);

                if (prompt == null || prompt.isBlank()) {
                    writeJson(exchange, 400, "{\"error\":\"prompt ausente\"}");
                    return;
                }

                log("Prompt recebido:\n" + prompt);

                String verdict = classifier.classify(prompt);
                boolean allowed = "SAFE".equals(verdict);

                log("Veredito da IA: " + verdict + " | allowed=" + allowed);

                String response = "{"
                        + "\"prompt\":\"" + escapeJson(prompt) + "\","
                        + "\"veredito\":\"" + escapeJson(verdict) + "\""
                        + "}";

                writeJson(exchange, 200, response);
            } catch (Exception e) {
                logError("Erro no /validar: " + e.getMessage());
                writeJson(exchange, 500, "{\"error\":\"internal_error\"}");
            }
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, "{\"status\":\"ok\"}");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        byte[] buffer = new byte[4096];
        int n;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String extractPrompt(String body, String contentType) {
        if (body == null) {
            return null;
        }

        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("application/json") || body.trim().startsWith("{")) {
            String fromPrompt = extractJsonStringField(body, "prompt");
            if (fromPrompt != null && !fromPrompt.isBlank()) {
                return fromPrompt;
            }
            String fromInput = extractJsonStringField(body, "input");
            if (fromInput != null && !fromInput.isBlank()) {
                return fromInput;
            }
            String fromMessage = extractJsonStringField(body, "message");
            if (fromMessage != null && !fromMessage.isBlank()) {
                return fromMessage;
            }
            return null;
        }

        return body.trim();
    }

    private static String extractJsonStringField(String json, String field) {
        String token = "\"" + field + "\"";
        int idx = json.indexOf(token);
        if (idx < 0) {
            return null;
        }

        int colon = json.indexOf(':', idx + token.length());
        if (colon < 0) {
            return null;
        }

        int firstQuote = -1;
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                firstQuote = i;
                break;
            }
            if (!Character.isWhitespace(c)) {
                return null;
            }
        }

        if (firstQuote < 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    default:
                        sb.append(c);
                        break;
                }
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }

        return null;
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void log(String msg) {
        System.out.println(LOG_PREFIX + " " + msg);
    }

    private static void logError(String msg) {
        System.err.println(LOG_PREFIX + " [ERROR] " + msg);
    }
}
