import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OllamaJobQueue - Fila de processamento assíncrono sincronizada.
 */
public class OllamaJobQueue {
    private static final String LOG_PREFIX = "[OllamaJobQueue]";
    private static final int DEFAULT_WORKERS = 4;
    private static final int DEFAULT_MAX_QUEUE = 200;
    private static final long JOB_TTL_SECONDS = 600;

    private final ExecutorService workers;
    private final ScheduledExecutorService cleaner;
    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();
    private final Semaphore semaphore;
    private final SecurityClassifier classifier;
    private final AtomicLong submittedCount = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);

    public enum JobStatus { QUEUED, PROCESSING, DONE, NOT_FOUND }

    public static class AwaitResult {
        public final String prompt;
        public final String verdict;
        public final JobStatus status;
        public final boolean isTestFlow;
        AwaitResult(String p, String v, JobStatus s, boolean t) { 
            this.prompt = p; this.verdict = v; this.status = s; this.isTestFlow = t; 
        }
    }

    public OllamaJobQueue(SecurityClassifier classifier) {
        this.classifier = classifier;
        int workerCount = Integer.parseInt(envOr("OLLAMA_WORKERS", String.valueOf(DEFAULT_WORKERS)));
        int maxQueue = Integer.parseInt(envOr("OLLAMA_MAX_QUEUE", String.valueOf(DEFAULT_MAX_QUEUE)));
        this.workers = Executors.newFixedThreadPool(workerCount);
        this.cleaner = Executors.newScheduledThreadPool(1);
        this.semaphore = new Semaphore(maxQueue, true);
        log("Iniciado com " + workerCount + " workers e capacidade de " + maxQueue + " jobs.");
    }

    public String submit(String prompt, boolean isTestFlow, String sourceIp) {
        if (!semaphore.tryAcquire()) {
            throw new IllegalStateException("Fila saturada");
        }
        
        String jobId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        AtomicBoolean permitReleased = new AtomicBoolean(false);

        Future<?> task = workers.submit(() -> {
            try {
                // Chama a versão simplificada do classificador (apenas o prompt)
                String verdict = classifier.classify(prompt);
                future.complete(verdict);
                completedCount.incrementAndGet();
                
                // Log do veredito final para fluxos de teste
                if (isTestFlow) {
                    AuditLogger.logTestVerdict(prompt, verdict, sourceIp);
                }
            } catch (Exception e) {
                failedCount.incrementAndGet();
                future.completeExceptionally(e);
                if (isTestFlow) {
                    AuditLogger.logTestVerdict(prompt, "ERRO_CLASSIFICACAO", sourceIp);
                }
            } finally {
                if (permitReleased.compareAndSet(false, true)) {
                    semaphore.release();
                }
            }
        });

        jobs.put(jobId, new JobEntry(prompt, future, isTestFlow, sourceIp, permitReleased, task));
        submittedCount.incrementAndGet();
        
        // Cleanup automático
        cleaner.schedule(() -> jobs.remove(jobId), JOB_TTL_SECONDS, TimeUnit.SECONDS);
        
        return jobId;
    }

    public String submitResolved(String prompt, String verdict, boolean isTestFlow, String sourceIp) {
        String jobId = UUID.randomUUID().toString();
        // Jobs resolvidos por cache/heurística não ocupam workers nem semáforo
        jobs.put(jobId, new JobEntry(prompt, CompletableFuture.completedFuture(verdict), isTestFlow, sourceIp, new AtomicBoolean(true), null));
        
        if (isTestFlow) {
            AuditLogger.logTestVerdict(prompt, verdict, sourceIp);
        }
        
        cleaner.schedule(() -> jobs.remove(jobId), JOB_TTL_SECONDS, TimeUnit.SECONDS);
        return jobId;
    }

    public AwaitResult awaitResult(String jobId, long timeoutSeconds) throws InterruptedException {
        JobEntry entry = jobs.get(jobId);
        if (entry == null) return new AwaitResult(null, null, JobStatus.NOT_FOUND, false);
        
        try {
            String v = entry.future.get(timeoutSeconds, TimeUnit.SECONDS);
            return new AwaitResult(entry.prompt, v, JobStatus.DONE, entry.isTestFlow);
        } catch (TimeoutException e) {
            return new AwaitResult(entry.prompt, null, JobStatus.PROCESSING, entry.isTestFlow);
        } catch (Exception e) {
            return new AwaitResult(entry.prompt, "Reprovado", JobStatus.DONE, entry.isTestFlow);
        }
    }

    public void shutdown() {
        workers.shutdown();
        cleaner.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) cleaner.shutdownNow();
        } catch (InterruptedException e) {
            workers.shutdownNow();
            cleaner.shutdownNow();
        }
        log("Fila de jobs encerrada.");
    }

    public boolean exists(String jobId) {
        return jobs.containsKey(jobId);
    }

    public HealthSnapshot healthSnapshot() {
        return new HealthSnapshot(
            "UP",
            (int) (submittedCount.get() - completedCount.get() - failedCount.get()),
            DEFAULT_MAX_QUEUE,
            semaphore.availablePermits(),
            submittedCount.get(),
            completedCount.get(),
            failedCount.get(),
            0
        );
    }

    private String envOr(String k, String f) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? f : v;
    }

    private void log(String msg) { System.out.println(LOG_PREFIX + " " + msg); }
    private void logError(String msg) { System.err.println(LOG_PREFIX + " [ERROR] " + msg); }

    static class JobEntry {
        final String prompt;
        final CompletableFuture<String> future;
        final boolean isTestFlow;
        final String sourceIp;
        final AtomicBoolean permitReleased;
        final Future<?> workerTask;

        JobEntry(String p, CompletableFuture<String> f, boolean t, String ip, AtomicBoolean pr, Future<?> wt) {
            this.prompt = p; this.future = f; this.isTestFlow = t; this.sourceIp = ip; this.permitReleased = pr; this.workerTask = wt;
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

        HealthSnapshot(String s, int p, int c, int a, long sj, long cj, long fj, long tj) {
            this.status = s; this.pendingJobs = p; this.queueCapacity = c; 
            this.queuePermitsAvailable = a; this.submittedJobs = sj; 
            this.completedJobs = cj; this.failedJobs = fj; this.timedOutJobs = tj;
        }
    }
}
