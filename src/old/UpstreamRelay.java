import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;

// NOTA: Esta classe é chamada apenas por TrafficAnalyzer (classifyAndRelay e bypassToUpstream),
// mas TrafficAnalyzer não está no fluxo de execução ativo (não é instanciado por Main nem por
// TransparentTLSInterceptor). TransparentTLSInterceptor possui relay próprio interno (relayToUpstream /
// writeRequestToUpstream) que duplica a responsabilidade deste módulo.
// Para unificar: TransparentTLSInterceptor deveria delegar para UpstreamRelay em vez de reimplementar.

/**
 * UpstreamRelay.java - A Ponte Final
 *
 * Responsabilidades:
 * 1. Caminho SAFE: abrir conexão TLS com upstream real e reenviar requisição
 * 2. Caminho UNSAFE: retornar HTTP 403 formatado ao cliente
 */
public class UpstreamRelay {
    private static final String LOG_PREFIX = "[UpstreamRelay]";
    private static final SSLSocketFactory INSECURE_SSL_SOCKET_FACTORY = buildInsecureSslSocketFactory();

    /**
     * Caminho SAFE: envia a requisição original para o host upstream e retransmite
     * a resposta ao cliente conectado no gateway.
     */
    public void handleSafeRequest(String method,
                                  String path,
                                  String version,
                                  Map<String, String> headers,
                                  byte[] body,
                                  InputStream clientInput,
                                  OutputStream clientOutput) throws IOException {

        String hostHeader = headers.get("host");
        if (hostHeader == null || hostHeader.trim().isEmpty()) {
            throw new IOException("Header Host ausente para relay upstream");
        }

        HostPort target = parseHostPort(hostHeader.trim());
        log("Conectando upstream em " + target.host + ":" + target.port);
        boolean tunnelMode = shouldTunnelBidirectionally(path, headers);

        try (SSLSocket upstreamSocket = createUpstreamSocketWithFallback(target.host, target.port)) {
            upstreamSocket.setSoTimeout(30000);

            OutputStream upstreamOut = new BufferedOutputStream(upstreamSocket.getOutputStream());
            InputStream upstreamIn = upstreamSocket.getInputStream();

            writeRequestToUpstream(method, path, version, headers, body, upstreamOut, tunnelMode);

            if (tunnelMode) {
                log("Ativando túnel bidirecional para conexão de stream/upgrade");
                relayBidirectionally(clientInput, clientOutput, upstreamIn, upstreamOut);
            } else {
                relayUpstreamToClient(upstreamIn, clientOutput);
            }

            clientOutput.flush();
            log("Relay upstream concluído");
        }
    }

    /**
     * Caminho UNSAFE: retorna 403 para o cliente.
     */
    public void writeBlockedResponse(OutputStream clientOutput) throws IOException {
        String jsonError = "{\"error\": \"Blocked by AgentK Security Policy\"}";
        String response = "HTTP/1.1 403 Forbidden\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + jsonError.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + jsonError;

        clientOutput.write(response.getBytes(StandardCharsets.UTF_8));
        clientOutput.flush();
        log("Resposta 403 enviada ao cliente");
    }

    private void writeRequestToUpstream(String method,
                                        String path,
                                        String version,
                                        Map<String, String> headers,
                                        byte[] body,
                                        OutputStream upstreamOut,
                                        boolean tunnelMode) throws IOException {

        String safeMethod = (method == null || method.isEmpty()) ? "GET" : method;
        String safePath = (path == null || path.isEmpty()) ? "/" : path;
        String safeVersion = (version == null || version.isEmpty()) ? "HTTP/1.1" : version;

        StringBuilder requestBuilder = new StringBuilder();
        requestBuilder.append(safeMethod).append(' ').append(safePath).append(' ').append(safeVersion).append("\r\n");

        boolean hasContentLength = false;
        boolean hasTransferEncoding = false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null) {
                continue;
            }

            String lower = name.toLowerCase();
            if ("proxy-connection".equals(lower)) {
                continue;
            }
            if (!tunnelMode && "connection".equals(lower)) {
                continue;
            }
            if ("content-length".equals(lower)) {
                hasContentLength = true;
            }
            if ("transfer-encoding".equals(lower)) {
                hasTransferEncoding = true;
            }

            requestBuilder.append(name).append(": ").append(value).append("\r\n");
        }

        if (body != null && body.length > 0 && !hasContentLength && !hasTransferEncoding) {
            requestBuilder.append("content-length: ").append(body.length).append("\r\n");
        }
        if (!tunnelMode) {
            requestBuilder.append("connection: close\r\n");
        }
        requestBuilder.append("\r\n");

        upstreamOut.write(requestBuilder.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) {
            upstreamOut.write(body);
        }
        upstreamOut.flush();
    }

    private void relayUpstreamToClient(InputStream upstreamIn, OutputStream clientOut) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = upstreamIn.read(buffer)) != -1) {
            clientOut.write(buffer, 0, read);
            clientOut.flush();
        }
    }

    private void relayBidirectionally(InputStream clientIn,
                                      OutputStream clientOut,
                                      InputStream upstreamIn,
                                      OutputStream upstreamOut) throws IOException {
        Thread clientToUpstream = new Thread(() -> pipe(clientIn, upstreamOut, "cliente->upstream"),
                "relay-client-upstream");
        clientToUpstream.setDaemon(true);
        clientToUpstream.start();

        pipe(upstreamIn, clientOut, "upstream->cliente");

        try {
            clientToUpstream.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pipe(InputStream in, OutputStream out, String direction) {
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            log("Canal " + direction + " encerrado: " + e.getMessage());
        }
    }

    private boolean shouldTunnelBidirectionally(String path, Map<String, String> headers) {
        String upgrade = valueOf(headers, "upgrade");
        String connection = valueOf(headers, "connection");
        String accept = valueOf(headers, "accept");
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);

        if (upgrade.contains("websocket")) {
            return true;
        }
        if (connection.contains("upgrade")) {
            return true;
        }
        if (normalizedPath.contains("/_stcore/stream")) {
            return true;
        }
        return accept.contains("text/event-stream");
    }

    private String valueOf(Map<String, String> headers, String key) {
        String value = headers.get(key);
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private SSLSocket createUpstreamSocketWithFallback(String host, int port) throws IOException {
        SSLSocketFactory defaultFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) defaultFactory.createSocket(host, port);

        try {
            socket.startHandshake();
            return socket;
        } catch (SSLHandshakeException handshakeError) {
            closeQuietly(socket);

            log("Aviso: falha de validação TLS no upstream (" + handshakeError.getMessage() + ")");
            log("Aplicando fallback inseguro para manter o bypass transparente");

            SSLSocket insecureSocket = (SSLSocket) INSECURE_SSL_SOCKET_FACTORY.createSocket(host, port);
            SSLParameters params = insecureSocket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm(null);
            insecureSocket.setSSLParameters(params);
            insecureSocket.startHandshake();
            return insecureSocket;
        }
    }

    private static SSLSocketFactory buildInsecureSslSocketFactory() {
        try {
            TrustManager[] trustAllManagers = new TrustManager[]{
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
            ctx.init(null, trustAllManagers, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Falha ao criar SSL factory insegura", e);
        }
    }

    private void closeQuietly(SSLSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private HostPort parseHostPort(String hostHeader) {
        int idx = hostHeader.lastIndexOf(':');
        if (idx > 0 && idx < hostHeader.length() - 1) {
            String h = hostHeader.substring(0, idx);
            String p = hostHeader.substring(idx + 1);
            try {
                return new HostPort(h, Integer.parseInt(p));
            } catch (NumberFormatException ignored) {
                return new HostPort(hostHeader, 443);
            }
        }
        return new HostPort(hostHeader, 443);
    }

    private static class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static void log(String message) {
        System.out.println(LOG_PREFIX + " " + message);
    }
}
