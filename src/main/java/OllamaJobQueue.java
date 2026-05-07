import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final int    DEFAULT_WORKERS   = 10;
    private static final int    DEFAULT_MAX_QUEUE = 200;
    private static final long   DEFAULT_JOB_EXEC_TIMEOUT_SECONDS = 0;
    private static final long   JOB_TTL_SECONDS   = 600; // 10 minutos

    private final ExecutorService          workers;
    private final ScheduledExecutorService cleaner;
    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();
    private final Semaphore                semaphore;
    private final SecurityClassifier       classifier;
    private final int                      maxQueueCapacity;
    private final long                     maxJobExecutionSeconds;
    private final AtomicLong               submittedCount = new AtomicLong(0);
    private final AtomicLong               completedCount = new AtomicLong(0);
    private final AtomicLong               failedCount    = new AtomicLong(0);
    private final AtomicLong               timedOutCount  = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Estrutura interna de um job
    // -------------------------------------------------------------------------

    static class JobEntry {
        final String                   prompt;
        final CompletableFuture<String> future;
        final long                     createdAtMs;
        final boolean                  isTestFlow;
        final AtomicBoolean            permitReleased;
        final Future<?>                workerTask;

        JobEntry(String prompt,
                 CompletableFuture<String> future,
                 boolean isTestFlow,
                 AtomicBoolean permitReleased,
                 Future<?> workerTask) {
            this.prompt      = prompt;
            this.future      = future;
            this.createdAtMs = System.currentTimeMillis();
            this.isTestFlow  = isTestFlow;
            this.permitReleased = permitReleased;
            this.workerTask = workerTask;
        }
    }

    public static class HealthSnapshot {
        public final String status;
        public final int pendingJobs;
        public final int queueCapacity;
        public final int queuePermitsAvailable;
        public final long submittedJobs;
        public final long completedJobs;
        public final long failedJobs;
        public final long timedOutJobs;

        HealthSnapshot(String status,
                       int pendingJobs,
                       int queueCapacity,
                       int queuePermitsAvailable,
                       long submittedJobs,
                       long completedJobs,
                       long failedJobs,
                       long timedOutJobs) {
            this.status = status;
            this.pendingJobs = pendingJobs;
            this.queueCapacity = queueCapacity;
            this.queuePermitsAvailable = queuePermitsAvailable;
            this.submittedJobs = submittedJobs;
            this.completedJobs = completedJobs;
            this.failedJobs = failedJobs;
            this.timedOutJobs = timedOutJobs;
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
        public final boolean     isTestFlow;

        AwaitResult(AwaitStatus status, String verdict, String prompt, boolean isTestFlow) {
            this.status     = status;
            this.verdict    = verdict;
            this.prompt     = prompt;
            this.isTestFlow = isTestFlow;
        }
    }

    // -------------------------------------------------------------------------
    // Construtor
    // -------------------------------------------------------------------------

    public OllamaJobQueue(SecurityClassifier classifier) {
        this.classifier = classifier;

        int workerCount = Integer.parseInt(envOr("OLLAMA_WORKERS",   String.valueOf(DEFAULT_WORKERS)));
        int maxQueue    = Integer.parseInt(envOr("OLLAMA_MAX_QUEUE", String.valueOf(DEFAULT_MAX_QUEUE)));
        this.maxJobExecutionSeconds = Long.parseLong(envOr("OLLAMA_JOB_EXEC_TIMEOUT_SECONDS",
            String.valueOf(DEFAULT_JOB_EXEC_TIMEOUT_SECONDS)));
        if (this.maxJobExecutionSeconds < 0) {
            throw new IllegalArgumentException("OLLAMA_JOB_EXEC_TIMEOUT_SECONDS deve ser >= 0");
        }
        this.maxQueueCapacity = maxQueue;

        // Semaphore justo: respeita a ordem de chegada dos submits quando a fila enche
        this.semaphore = new Semaphore(maxQueue, true);

        // Pool de workers exclusivo para chamadas ao Ollama (isolado das threads HTTP)
        this.workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "Ollama-Worker-" + System.nanoTime());
            t.setDaemon(false);
            return t;
        });

        // Agendador de limpeza periódica de jobs expirados (daemon)
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JobQueue-Cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanExpiredJobs, 5, 5, TimeUnit.MINUTES);

        log("Iniciado com " + workerCount
            + " worker(s), capacidade máxima=" + maxQueue
            + ", timeout por job="
            + (maxJobExecutionSeconds > 0 ? maxJobExecutionSeconds + "s" : "desativado")
            + ".");
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
        return submit(prompt, false);
    }

    public String submit(String prompt, boolean isTestFlow) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("Prompt não pode ser nulo ou vazio.");
        }

        if (!semaphore.tryAcquire()) {
            throw new IllegalStateException("Fila saturada — tente novamente em instantes.");
        }

        String jobId = UUID.randomUUID().toString();
        String normalizedPrompt = prompt.trim();
        AtomicBoolean permitReleased = new AtomicBoolean(false);
        CompletableFuture<String> future = new CompletableFuture<>();

        Future<?> workerTask = workers.submit(() -> {
            String tName = Thread.currentThread().getName();
            try {
                log("Thread " + tName + " iniciada para processar job " + jobId);
                String verdict = classifier.classify(normalizedPrompt, isTestFlow);
                future.complete(verdict);
                completedCount.incrementAndGet();
                log("Thread " + tName + " finalizada para job " + jobId + ". Veredito: " + verdict);
            } catch (Exception ex) {
                failedCount.incrementAndGet();
                future.completeExceptionally(ex);
                logError("Falha na execução do job " + jobId + ": " + ex.getMessage());
            } finally {
                releasePermitOnce(permitReleased);
            }
        });

        jobs.put(jobId, new JobEntry(normalizedPrompt, future, isTestFlow, permitReleased, workerTask));
        submittedCount.incrementAndGet();

        if (maxJobExecutionSeconds > 0) {
            cleaner.schedule(() -> {
            if (future.isDone()) {
                return;
            }

            boolean cancelled = workerTask.cancel(true);
            timedOutCount.incrementAndGet();
            future.completeExceptionally(new TimeoutException(
                "Job excedeu timeout de " + maxJobExecutionSeconds + "s"));
            releasePermitOnce(permitReleased);

            logError("Timeout no job " + jobId + " após " + maxJobExecutionSeconds
                + "s. cancel_requested=" + cancelled);
            AuditLogger.log("Gateway-System", "JOB_TIMEOUT", jobId, "TIMEOUT", "internal",
                "cancel_requested=" + cancelled + ", pending=" + pendingCount());
            }, maxJobExecutionSeconds, TimeUnit.SECONDS);
        }

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
            return new AwaitResult(AwaitStatus.NOT_FOUND, null, null, false);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds deve ser maior que zero.");
        }

        try {
            String verdict = entry.future.get(timeoutSeconds, TimeUnit.SECONDS);
            return new AwaitResult(AwaitStatus.DONE, verdict, entry.prompt, entry.isTestFlow);
        } catch (TimeoutException e) {
            return new AwaitResult(AwaitStatus.PROCESSING, null, entry.prompt, entry.isTestFlow);
        } catch (ExecutionException e) {
            logError("Falha no job " + jobId + ": "
                    + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return new AwaitResult(AwaitStatus.DONE, "UNCERTAIN", entry.prompt, entry.isTestFlow);
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

    public HealthSnapshot healthSnapshot() {
        int pendingJobs = pendingCount();
        String status = "ok";

        if (pendingJobs >= maxQueueCapacity || semaphore.availablePermits() == 0) {
            status = "overloaded";
        } else if (timedOutCount.get() > 0 || failedCount.get() > 0) {
            status = "degraded";
        }

        return new HealthSnapshot(
                status,
                pendingJobs,
                maxQueueCapacity,
                semaphore.availablePermits(),
                submittedCount.get(),
                completedCount.get(),
                failedCount.get(),
                timedOutCount.get()
        );
    }

    // -------------------------------------------------------------------------
    // Encerramento gracioso
    // -------------------------------------------------------------------------

    public void shutdown() {
        cleaner.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) {
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

    private void releasePermitOnce(AtomicBoolean permitReleased) {
        if (permitReleased.compareAndSet(false, true)) {
            semaphore.release();
        }
    }

    private static void log(String msg)      { System.out.println(LOG_PREFIX + " " + msg); }
    private static void logError(String msg) { System.err.println(LOG_PREFIX + " [ERROR] " + msg); }
}
