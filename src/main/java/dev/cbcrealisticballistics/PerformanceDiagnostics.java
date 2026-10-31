package dev.cbcrealisticballistics;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead slow-tick profiler which is always available in release jars.
 * Stack sampling starts only after a server tick has already exceeded 40 ms,
 * so healthy 20 TPS operation pays only two nanoTime calls per tick.
 */
public final class PerformanceDiagnostics {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SAMPLE_THRESHOLD_NS = 40_000_000L;
    private static final long LOG_THRESHOLD_NS = 50_000_000L;
    private static final long ACTIVITY_TAIL_NS = TimeUnit.SECONDS.toNanos(2);
    private static final Path LOG_PATH = FMLPaths.GAMEDIR.get()
            .resolve("logs")
            .resolve("cbc-realistic-ballistics-performance.log");
    private static volatile ScheduledExecutorService worker;
    private static final AtomicLong TICK_SEQUENCE = new AtomicLong();
    private static final AtomicLong LAST_NOTICE_NS = new AtomicLong();
    private static volatile long lastProjectileActivityNs = Long.MIN_VALUE;
    private static volatile TickFrame activeTick;

    private PerformanceDiagnostics() {
    }

    private static synchronized void initializeIfNeeded() {
        if (worker != null) return;
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(
                new DiagnosticThreadFactory());
        worker = created;
        created.execute(() -> {
            try {
                Files.createDirectories(LOG_PATH.getParent());
                Files.writeString(
                        LOG_PATH,
                        "CBC Realistic Ballistics performance diagnostics\n"
                                + "Session: " + Instant.now() + "\n"
                                + "Only projectile-related ticks >= 50 ms are listed. "
                                + "Stack samples begin at 40 ms.\n\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } catch (IOException exception) {
                LOGGER.error("Could not initialize CBC ballistics performance log", exception);
            }
        });
        created.scheduleAtFixedRate(
                PerformanceDiagnostics::sampleSlowTick,
                20L,
                20L,
                TimeUnit.MILLISECONDS
        );
    }

    public static void onServerTickPre(ServerTickEvent.Pre event) {
        if (!BallisticsConfig.PERFORMANCE_DIAGNOSTICS.get()) {
            activeTick = null;
            return;
        }
        initializeIfNeeded();
        activeTick = new TickFrame(
                TICK_SEQUENCE.incrementAndGet(),
                System.nanoTime(),
                Thread.currentThread()
        );
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        TickFrame frame = activeTick;
        if (frame == null) return;
        frame.endNs = System.nanoTime();
        frame.finished = true;
        activeTick = null;
        long duration = frame.endNs - frame.startNs;
        if (duration < LOG_THRESHOLD_NS || !isProjectileActivityRecent(frame.endNs)) return;

        String report = format(frame, duration);
        long now = System.nanoTime();
        long previousNotice = LAST_NOTICE_NS.get();
        if (now - previousNotice >= TimeUnit.SECONDS.toNanos(5)
                && LAST_NOTICE_NS.compareAndSet(previousNotice, now)) {
            LOGGER.warn(
                    "CBC ballistics diagnostics is capturing slow server ticks; report: {}",
                    LOG_PATH
            );
        }
        ScheduledExecutorService currentWorker = worker;
        if (currentWorker != null) currentWorker.execute(() -> append(report));
    }

    /** Returns zero while diagnostics are dormant, avoiding nanoTime entirely. */
    public static long startTiming() {
        return activeTick == null ? 0L : System.nanoTime();
    }

    public static long elapsedSince(long startedNs) {
        return startedNs == 0L ? 0L : Math.max(0L, System.nanoTime() - startedNs);
    }

    public static void recordProjectileTick(long elapsedNs) {
        TickFrame frame = activeTick;
        if (frame == null || elapsedNs == 0L) return;
        frame.metrics.projectileTicks.incrementAndGet();
        frame.metrics.projectileNs.addAndGet(Math.max(0L, elapsedNs));
    }

    public static void markProjectileActivity() {
        if (activeTick == null) return;
        lastProjectileActivityNs = System.nanoTime();
    }

    public static void recordCollisionPass(long elapsedNs, boolean fullCbcPass) {
        TickFrame frame = activeTick;
        if (frame == null || elapsedNs == 0L) return;
        frame.metrics.collisionChecks.incrementAndGet();
        if (fullCbcPass) frame.metrics.fullCollisionPasses.incrementAndGet();
        frame.metrics.collisionNs.addAndGet(Math.max(0L, elapsedNs));
    }

    public static void recordForceCalculation(long elapsedNs) {
        TickFrame frame = activeTick;
        if (frame == null || elapsedNs == 0L) return;
        frame.metrics.forceCalls.incrementAndGet();
        frame.metrics.forceNs.addAndGet(Math.max(0L, elapsedNs));
    }

    public static void recordTransit(long elapsedNs, int tracked, int suppliedTicks) {
        TickFrame frame = activeTick;
        if (frame == null || elapsedNs == 0L) return;
        frame.metrics.transitNs.addAndGet(Math.max(0L, elapsedNs));
        frame.metrics.transitTracked.addAndGet(Math.max(0, tracked));
        frame.metrics.suppliedTicks.addAndGet(Math.max(0, suppliedTicks));
    }

    public static void recordPlanner(
            long elapsedNs,
            int tracked,
            int predictionSteps,
            int newTickets
    ) {
        TickFrame frame = activeTick;
        if (frame == null || elapsedNs == 0L) return;
        frame.metrics.plannerNs.addAndGet(Math.max(0L, elapsedNs));
        frame.metrics.plansTracked.addAndGet(Math.max(0, tracked));
        frame.metrics.predictionSteps.addAndGet(Math.max(0, predictionSteps));
        frame.metrics.newTickets.addAndGet(Math.max(0, newTickets));
    }

    public static void recordTicketOperation(long elapsedNs, boolean add) {
        TickFrame frame = activeTick;
        if (frame == null || elapsedNs == 0L) return;
        frame.metrics.ticketNs.addAndGet(Math.max(0L, elapsedNs));
        if (add) frame.metrics.ticketAdds.incrementAndGet();
        else frame.metrics.ticketRemoves.incrementAndGet();
    }

    public static Path logPath() {
        return LOG_PATH;
    }

    private static void sampleSlowTick() {
        TickFrame frame = activeTick;
        if (frame == null || frame.finished) return;
        long now = System.nanoTime();
        if (!isProjectileActivityRecent(now)) return;
        long elapsed = now - frame.startNs;
        if (elapsed < SAMPLE_THRESHOLD_NS) return;
        StackTraceElement[] trace = frame.serverThread.getStackTrace();
        if (trace.length == 0) return;
        String signature = stackSignature(trace);
        frame.stackSamples.computeIfAbsent(signature, ignored -> new AtomicInteger())
                .incrementAndGet();
    }

    private static boolean isProjectileActivityRecent(long nowNs) {
        long activityNs = lastProjectileActivityNs;
        return activityNs != Long.MIN_VALUE && nowNs - activityNs <= ACTIVITY_TAIL_NS;
    }

    private static String stackSignature(StackTraceElement[] trace) {
        StringBuilder builder = new StringBuilder(512);
        int accepted = 0;
        for (StackTraceElement element : trace) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName())
                    || className.equals(PerformanceDiagnostics.class.getName())) continue;
            if (accepted++ > 0) builder.append(" <- ");
            builder.append(className)
                    .append('.')
                    .append(element.getMethodName())
                    .append(':')
                    .append(element.getLineNumber());
            if (accepted >= 16) break;
        }
        return builder.length() == 0 ? "<no useful server stack>" : builder.toString();
    }

    private static String format(TickFrame frame, long durationNs) {
        TickMetrics m = frame.metrics;
        StringBuilder builder = new StringBuilder(4096);
        builder.append('[').append(Instant.now()).append("] slow server tick #")
                .append(frame.sequence)
                .append("\n  duration_ms=").append(ms(durationNs))
                .append("\n  projectile_ticks=").append(m.projectileTicks.get())
                .append(" projectile_ms=").append(ms(m.projectileNs.get()))
                .append("\n  collision_checks=").append(m.collisionChecks.get())
                .append(" full_cbc_collision_passes=").append(m.fullCollisionPasses.get())
                .append(" collision_ms=").append(ms(m.collisionNs.get()))
                .append("\n  force_calls=").append(m.forceCalls.get())
                .append(" force_ms=").append(ms(m.forceNs.get()))
                .append("\n  transit_tracked=").append(m.transitTracked.get())
                .append(" supplied_ticks=").append(m.suppliedTicks.get())
                .append(" transit_ms=").append(ms(m.transitNs.get()))
                .append("\n  plans_tracked=").append(m.plansTracked.get())
                .append(" prediction_steps=").append(m.predictionSteps.get())
                .append(" new_tickets=").append(m.newTickets.get())
                .append(" planner_ms=").append(ms(m.plannerNs.get()))
                .append("\n  ticket_adds=").append(m.ticketAdds.get())
                .append(" ticket_removes=").append(m.ticketRemoves.get())
                .append(" ticket_api_ms=").append(ms(m.ticketNs.get()))
                .append("\n  sampled_hot_stacks:\n");

        List<Map.Entry<String, AtomicInteger>> samples = new ArrayList<>(
                frame.stackSamples.entrySet());
        samples.sort(Comparator.comparingInt(
                (Map.Entry<String, AtomicInteger> entry) -> entry.getValue().get()
        ).reversed());
        int total = samples.stream().mapToInt(entry -> entry.getValue().get()).sum();
        for (int index = 0; index < Math.min(12, samples.size()); index++) {
            Map.Entry<String, AtomicInteger> sample = samples.get(index);
            double percent = total == 0 ? 0.0 : sample.getValue().get() * 100.0 / total;
            builder.append("    ")
                    .append(sample.getValue().get())
                    .append(" samples (")
                    .append(String.format(java.util.Locale.ROOT, "%.1f", percent))
                    .append("%): ")
                    .append(sample.getKey())
                    .append('\n');
        }
        builder.append('\n');
        return builder.toString();
    }

    private static String ms(long nanoseconds) {
        return String.format(java.util.Locale.ROOT, "%.3f", nanoseconds / 1_000_000.0);
    }

    private static void append(String report) {
        try {
            Files.writeString(
                    LOG_PATH,
                    report,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            LOGGER.error("Could not append CBC ballistics performance report", exception);
        }
    }

    private static final class TickFrame {
        private final long sequence;
        private final long startNs;
        private final Thread serverThread;
        private final TickMetrics metrics = new TickMetrics();
        private final ConcurrentHashMap<String, AtomicInteger> stackSamples =
                new ConcurrentHashMap<>();
        private volatile long endNs;
        private volatile boolean finished;

        private TickFrame(long sequence, long startNs, Thread serverThread) {
            this.sequence = sequence;
            this.startNs = startNs;
            this.serverThread = serverThread;
        }
    }

    private static final class TickMetrics {
        private final AtomicInteger projectileTicks = new AtomicInteger();
        private final AtomicLong projectileNs = new AtomicLong();
        private final AtomicInteger collisionChecks = new AtomicInteger();
        private final AtomicInteger fullCollisionPasses = new AtomicInteger();
        private final AtomicLong collisionNs = new AtomicLong();
        private final AtomicInteger forceCalls = new AtomicInteger();
        private final AtomicLong forceNs = new AtomicLong();
        private final AtomicInteger transitTracked = new AtomicInteger();
        private final AtomicInteger suppliedTicks = new AtomicInteger();
        private final AtomicLong transitNs = new AtomicLong();
        private final AtomicInteger plansTracked = new AtomicInteger();
        private final AtomicInteger predictionSteps = new AtomicInteger();
        private final AtomicInteger newTickets = new AtomicInteger();
        private final AtomicLong plannerNs = new AtomicLong();
        private final AtomicInteger ticketAdds = new AtomicInteger();
        private final AtomicInteger ticketRemoves = new AtomicInteger();
        private final AtomicLong ticketNs = new AtomicLong();
    }

    private static final class DiagnosticThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "CBC Ballistics Slow Tick Sampler");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    }
}
