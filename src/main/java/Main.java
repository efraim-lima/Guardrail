import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final String LOG_PREFIX = "[Gateway]";

    private static final int WEBHOOK_PORT = Integer.parseInt(envOr("WEBHOOK_PORT", "8080"));
    private static final String WEBHOOK_PATH = envOr("WEBHOOK_PATH", "/validar");

    private ExecutorService threadPool;
    private OllamaJobQueue jobQueue;
    private PromptValidator webhookServer;
    private volatile boolean running = true;

    public Main() {
        AuditLogger.log("Gateway-System", "STARTUP", "Application", "INITIALIZING", "127.0.0.1", "Versão 1.0.0");
    }

    // NOTA: O modo de interceptação TLS (TransparentTLSInterceptor + CertAuthority) ainda não está
    // conectado a este método de inicialização. Apenas o modo webhook está ativo.
    // Para ativar a interceptação MITM, instancie TransparentTLSInterceptor aqui com uma CertAuthority
    // carregada do keystore (CA_CERT_PATH / CA_KEY_PATH) e submeta ao threadPool.
    public void start() {
        try {
            installShutdownHook();
            startWebhookMode();
            waitForShutdown();
        } catch (Exception e) {
            logError("Falha ao iniciar Gateway: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void startWebhookMode() {
        log("========================================");
        log("GATEWAY - MODO WEBHOOK DE VALIDAÇÃO");
        log("========================================");
        log("Endpoint: http://host.docker.internal:" + WEBHOOK_PORT + WEBHOOK_PATH);

        // Virtual threads para handlers HTTP: cada requisição recebe uma thread leve
        // que bloqueia em awaitResult() sem consumir OS threads do pool.
        this.threadPool = Executors.newVirtualThreadPerTaskExecutor();
        SecurityClassifier classifier = new SecurityClassifier();
        this.jobQueue = new OllamaJobQueue(classifier);

        this.webhookServer = new PromptValidator(
                WEBHOOK_PORT,
                WEBHOOK_PATH,
                jobQueue,
                threadPool
        );

        Thread webhookThread = new Thread(webhookServer, "Prompt-Validation-Webhook");
        webhookThread.setDaemon(false);
        webhookThread.start();

        log("✓ Webhook iniciado na porta " + WEBHOOK_PORT);
        AuditLogger.log("Gateway-System", "STARTUP", "WebhookServer", "SUCCESS", "0.0.0.0", "Port=" + WEBHOOK_PORT);
    }

    private void waitForShutdown() {
        try {
            while (running) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Recebido sinal de shutdown...");
            this.shutdown();
        }, "Shutdown-Hook"));
    }

    private synchronized void shutdown() {
        if (!running) return;
        running = false;

        if (webhookServer != null) {
            webhookServer.stop();
        }

        if (jobQueue != null) {
            jobQueue.shutdown();
        }

        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log("✓ Gateway encerrado");
        AuditLogger.log("Gateway-System", "SHUTDOWN", "Application", "SUCCESS", "127.0.0.1", "Graceful shutdown completed");
    }

    private static void log(String message) {
        System.out.println(LOG_PREFIX + " " + message);
    }

    private static void logError(String message) {
        System.err.println(LOG_PREFIX + " [ERROR] " + message);
    }

    private static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    public static void main(String[] args) {
        new Main().start();
    }
}
