import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OllamaJobQueue.java — Fila de processamento assíncrono para chamadas ao Ollama.
 *
 * Implementa o padrão equivalente ao Redis + Celery (Python), inteiramente em Java nativo:
 *   - submit(prompt)              → devolve jobId imediatamente   (≅ task.delay())
 *   - awaitResult(jobId, timeout) → bloqueia até o veredito chegar (≅ task.get(timeout))
 *
 * Cada job é executado por um pool de workers dedicado (OLLAMA_WORKERS), totalmente
 * desacoplado das threads HTTP. A capacidade máxima da fila é controlada por um
 * Semaphore justo (OLLAMA_MAX_QUEUE). Jobs expiram após JOB_TTL_SECONDS segundos.
 */
public class OllamaJobQueue {

    private static final String LOG_PREFIX      = "[OllamaJobQueue]";
    private static final int    DEFAULT_MAX_QUEUE = 200;
    private static final long   JOB_TTL_SECONDS   = 600; // 10 minutos

    private final ExecutorService          workers;
    private final ScheduledExecutorService cleaner;
    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();
    private final Semaphore                semaphore;
    private final SecurityClassifier       classifier;

    // -------------------------------------------------------------------------
    // Estrutura interna de um job
    // -------------------------------------------------------------------------

    static class JobEntry {
        final String                   prompt;
        final CompletableFuture<String> future;
        final long                     createdAtMs;

        JobEntry(String prompt, CompletableFuture<String> future) {
            this.prompt      = prompt;
            this.future      = future;
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    // -------------------------------------------------------------------------
    // Tipos de retorno de awaitResult
    // -------------------------------------------------------------------------

    public enum AwaitStatus { DONE, PROCESSING, NOT_FOUND }

    public static class AwaitResult {
        public final AwaitStatus status;
        public final String      verdict;
        public final String      prompt;

        AwaitResult(AwaitStatus status, String verdict, String prompt) {
            this.status  = status;
            this.verdict = verdict;
            this.prompt  = prompt;
        }
    }

    // -------------------------------------------------------------------------
    // Construtor
    // -------------------------------------------------------------------------

    public OllamaJobQueue(SecurityClassifier classifier) {
        this.classifier = classifier;

        int maxQueue = Integer.parseInt(envOr("OLLAMA_MAX_QUEUE", String.valueOf(DEFAULT_MAX_QUEUE)));

        // Semaphore justo: limita a quantidade de classificações simultâneas (rate-limit)
        // Virtual threads são baratas, mas o Qwen/Ollama é um gargalo real de CPU/RAM
        this.semaphore = new Semaphore(maxQueue, true);

        // Uma virtual thread por tarefa: cada job recebe thread dedicada que termina
        // imediatamente ao produzir o veredito — sem pool fixo, sem fila de espera interna.
        this.workers = Executors.newVirtualThreadPerTaskExecutor();

        // Agendador de limpeza periódica de jobs expirados (daemon)
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JobQueue-Cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanExpiredJobs, 5, 5, TimeUnit.MINUTES);

        log("Iniciado com virtual-thread-per-task, capacidade máxima de fila=" + maxQueue);
    }

    // -------------------------------------------------------------------------
    // Submissão assíncrona
    // -------------------------------------------------------------------------

    /**
     * Submete um prompt para classificação assíncrona.
     *
     * @param prompt  texto a classificar
     * @return        jobId único (UUID) para consulta posterior via awaitResult()
     * @throws IllegalStateException quando a fila atingiu capacidade máxima
     */
    public String submit(String prompt) {
        if (!semaphore.tryAcquire()) {
            throw new IllegalStateException("Fila saturada — tente novamente em instantes.");
        }

        String jobId = UUID.randomUUID().toString();

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            log("Job iniciado: " + jobId + " | prompt=" + (prompt.length() > 60 ? prompt.substring(0, 60) + "..." : prompt));

            // Heartbeat: loga a cada 10s para confirmar que a inferência está em progresso
            ScheduledFuture<?> heartbeat = cleaner.scheduleAtFixedRate(() ->
                log("Job em progresso: " + jobId
                        + " | elapsed=" + ((System.currentTimeMillis() - startMs) / 1000) + "s"),
                10, 10, TimeUnit.SECONDS);

            try {
                return classifier.classify(prompt);
            } finally {
                heartbeat.cancel(false);
                long elapsed = System.currentTimeMillis() - startMs;
                log("Job concluído: " + jobId + " | elapsed=" + elapsed + "ms");
                // Libera o slot de taxa assim que a virtual thread termina a classificação
                semaphore.release();
            }
        }, workers);

        jobs.put(jobId, new JobEntry(prompt, future));

        // Sinal de conclusão emitido imediatamente quando a virtual thread produz o veredito.
        // whenComplete é invocado pela própria thread que completou o future — sem delay.
        future.whenComplete((verdict, ex) -> {
            String result = (ex != null || verdict == null) ? "UNCERTAIN" : verdict;
            log("Sinal de conclusão: job=" + jobId + " veredito=" + result
                    + " thread=" + Thread.currentThread().getName());
            AuditLogger.log("Gateway-System", "JOB_COMPLETED", jobId, result, "internal",
                    "pending_after=" + pendingCount());
        });

        // Agendamento de remoção automática ao expirar o TTL (evita vazamento de memória)
        cleaner.schedule(() -> jobs.remove(jobId), JOB_TTL_SECONDS, TimeUnit.SECONDS);

        AuditLogger.log("Gateway-System", "JOB_SUBMITTED", jobId, "QUEUED", "internal",
                "pending=" + pendingCount() + ", semaphore_available=" + semaphore.availablePermits());
        return jobId;
    }

    // -------------------------------------------------------------------------
    // Long-poll de resultado
    // -------------------------------------------------------------------------

    /**
     * Aguarda o resultado de um job (operação de long-poll bloqueante).
     *
     * O chamador bloqueia até o veredito estar disponível ou o timeout expirar.
     * Equivalente ao task.get(timeout) do Celery.
     *
     * @param jobId          ID retornado por submit()
     * @param timeoutSeconds segundos máximos de espera
     * @return               AwaitResult com status DONE, PROCESSING ou NOT_FOUND
     */
    public AwaitResult awaitResult(String jobId, long timeoutSeconds) throws InterruptedException {
        JobEntry entry = jobs.get(jobId);
        if (entry == null) {
            return new AwaitResult(AwaitStatus.NOT_FOUND, null, null);
        }

        long pollStartMs = System.currentTimeMillis();
        log("Long-poll aguardando: job=" + jobId + " timeout=" + timeoutSeconds + "s"
                + " already_done=" + entry.future.isDone());
        try {
            String verdict = entry.future.get(timeoutSeconds, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - pollStartMs;
            log("Long-poll resolvido: job=" + jobId + " elapsed=" + elapsed + "ms status=DONE");
            return new AwaitResult(AwaitStatus.DONE, verdict, entry.prompt);
        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - pollStartMs;
            log("Long-poll expirou: job=" + jobId + " elapsed=" + elapsed + "ms timeout=" + timeoutSeconds + "s");
            // Job ainda em processamento: cliente deve retentar o long-poll
            return new AwaitResult(AwaitStatus.PROCESSING, null, null);
        } catch (ExecutionException e) {
            long elapsed = System.currentTimeMillis() - pollStartMs;
            logError("Long-poll falhou: job=" + jobId + " elapsed=" + elapsed + "ms reason="
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return new AwaitResult(AwaitStatus.DONE, "UNCERTAIN", entry.prompt);
        }
    }

    // -------------------------------------------------------------------------
    // Consultas não-bloqueantes
    // -------------------------------------------------------------------------

    public boolean isDone(String jobId) {
        JobEntry entry = jobs.get(jobId);
        return entry != null && entry.future.isDone();
    }

    public boolean exists(String jobId) {
        return jobs.containsKey(jobId);
    }

    public int pendingCount() {
        return (int) jobs.values().stream().filter(e -> !e.future.isDone()).count();
    }

    // -------------------------------------------------------------------------
    // Encerramento gracioso
    // -------------------------------------------------------------------------

    public void shutdown() {
        cleaner.shutdownNow();
        // Virtual thread executor: shutdown() sinaliza para não aceitar novos jobs;
        // as virtual threads já em execução terminam naturalmente quando sua tarefa acaba.
        workers.shutdown();
        try {
            if (!workers.awaitTermination(120, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log("Encerrado.");
    }

    // -------------------------------------------------------------------------
    // Limpeza periódica de jobs expirados
    // -------------------------------------------------------------------------

    private void cleanExpiredJobs() {
        long cutoff = System.currentTimeMillis() - JOB_TTL_SECONDS * 1000L;
        int removed = 0;
        for (Map.Entry<String, JobEntry> e : jobs.entrySet()) {
            JobEntry entry = e.getValue();
            if (entry.future.isDone() && entry.createdAtMs < cutoff) {
                jobs.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log("Cleanup: " + removed + " job(s) expirado(s) removidos. Pendentes=" + pendingCount());
        }
    }

    // -------------------------------------------------------------------------
    // Utilitários
    // -------------------------------------------------------------------------

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    private static void log(String msg)      { System.out.println(LOG_PREFIX + " " + msg); }
    private static void logError(String msg) { System.err.println(LOG_PREFIX + " [ERROR] " + msg); }
}
