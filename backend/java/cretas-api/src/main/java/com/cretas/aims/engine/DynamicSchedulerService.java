package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.audit.RequestAuditSupport;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicSchedulerService {

    private final FactorySchedulerConfigRepository schedulerRepo;
    private final ToolRegistry toolRegistry;
    private final AutonomousToolRunner runner;

    // Self-reference for proxy-aware @Transactional dispatch (R7 G2 persistExecutionMetadata
    // needs REQUIRES_NEW, which Spring AOP only honors through the proxy). @Lazy breaks
    // the self-circular dependency at wire time. Mirrors TriggerChainExecutor pattern.
    @Autowired @Lazy
    private DynamicSchedulerService self;
    private final ObjectMapper objectMapper;

    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();
    private volatile TaskScheduler taskScheduler;

    private TaskScheduler getOrCreateScheduler() {
        if (taskScheduler == null) {
            synchronized (this) {
                if (taskScheduler == null) {
                    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                    scheduler.setPoolSize(2);
                    scheduler.setThreadNamePrefix("canvas-scheduler-");
                    scheduler.initialize();
                    taskScheduler = scheduler;
                }
            }
        }
        return taskScheduler;
    }

    @PostConstruct
    public void loadSchedules() {
        List<FactorySchedulerConfig> configs = schedulerRepo.findByEnabledTrue();
        log.info("Loading {} dynamic scheduled tasks", configs.size());
        Map<ScheduleScope, List<FactorySchedulerConfig>> byScope = configs.stream()
                .collect(Collectors.groupingBy(config -> new ScheduleScope(
                        config.getFactoryId(), config.getTaskCode())));
        for (List<FactorySchedulerConfig> scopedConfigs : byScope.values()) {
            if (scopedConfigs.size() > 1) {
                String error = safeError("multiple enabled scheduler configs for scope "
                        + scopeDescription(scopedConfigs.get(0)));
                cancelTask(scopedConfigs.get(0).getFactoryId(), scopedConfigs.get(0).getTaskCode());
                for (FactorySchedulerConfig config : scopedConfigs) {
                    markConfigStatus(config, "CONFIG_INVALID", error);
                }
                log.error("Skipped duplicate dynamic scheduler scope: {} configIds={}",
                        scopeDescription(scopedConfigs.get(0)), configIds(scopedConfigs));
                continue;
            }
            scheduleTask(scopedConfigs.get(0));
        }
        log.info("[SchedulerLoad] enabledConfigs={} activeTasks={}", configs.size(), activeTasks.size());
    }

    @PreDestroy
    public void shutdown() {
        activeTasks.values().forEach(f -> f.cancel(false));
        activeTasks.clear();
    }

    public void scheduleTask(FactorySchedulerConfig config) {
        if (config != null && Boolean.FALSE.equals(config.getEnabled())) {
            cancelTask(config.getFactoryId(), config.getTaskCode());
            log.info("Skipped disabled dynamic scheduler config id={} scope={}",
                    config.getId(), scopeDescription(config));
            return;
        }
        String validationError = validateConfig(config);
        if (validationError != null) {
            String status = validationStatus(validationError);
            markConfigStatus(config, status, validationError);
            cancelTask(config == null ? null : config.getFactoryId(),
                    config == null ? null : config.getTaskCode());
            return;
        }

        String key = taskKey(config.getFactoryId(), config.getTaskCode());
        ScheduledFuture<?> existing = activeTasks.get(key);
        if (existing != null) { existing.cancel(false); activeTasks.remove(key); }

        try {
            // The task carries only the config id and the cron it was scheduled with: every fire
            // re-reads the row, so a change made through another instance takes effect here too.
            Long configId = config.getId();
            String scheduledCron = config.getCronExpression();
            PlannedFireTrigger trigger = new PlannedFireTrigger(new CronTrigger(scheduledCron));
            ScheduledFuture<?> future = getOrCreateScheduler().schedule(
                    () -> fire(configId, scheduledCron, key, trigger.planned()), trigger);
            activeTasks.put(key, future);
            log.info("Scheduled task: {} [{}] cron={}", key, config.getToolOrMethod(), config.getCronExpression());
        } catch (IllegalArgumentException e) {
            String error = "invalid cron: " + safeError(e.getMessage());
            log.error("Invalid cron for {}: {}", safeError(key), error);
            markConfigStatus(config, "CONFIG_INVALID", error);
        }
    }

    public void reloadSchedule(String factoryId, String taskCode) {
        List<FactorySchedulerConfig> matching = schedulerRepo.findByFactoryId(factoryId).stream()
                .filter(config -> Objects.equals(config.getTaskCode(), taskCode))
                .toList();
        cancelTask(factoryId, taskCode);

        if (matching.size() > 1) {
            String error = safeError("multiple scheduler configs for scope "
                    + scopeDescription(matching.get(0)));
            matching.forEach(config -> markConfigStatus(config, "CONFIG_INVALID", error));
            log.error("Skipped duplicate dynamic scheduler scope during reload: {} configIds={}",
                    scopeDescription(matching.get(0)), configIds(matching));
        } else if (matching.size() == 1 && Boolean.TRUE.equals(matching.get(0).getEnabled())) {
            scheduleTask(matching.get(0));
        } else {
            cancelTask(factoryId, taskCode);
        }
    }

    public void reloadAll() {
        activeTasks.values().forEach(f -> f.cancel(false));
        activeTasks.clear();
        loadSchedules();
    }

    public void cancelTask(String factoryId, String taskCode) {
        String key = taskKey(factoryId, taskCode);
        ScheduledFuture<?> future = activeTasks.remove(key);
        if (future != null) { future.cancel(false); log.info("Cancelled: {}", key); }
    }

    public int getActiveTaskCount() { return activeTasks.size(); }

    /**
     * Config-time gate for the scheduler PUT: the same rule {@link #validateConfig} applies before
     * every run. An unregistered tool is left to the existing TOOL_NOT_FOUND path.
     */
    public void requireSchedulable(String toolName, Map<String, Object> params) {
        if (toolName == null || toolName.isBlank()) return;
        Optional<ToolExecutor> tool = toolRegistry.getExecutor(toolName);
        if (tool.isEmpty()) return;
        String refusal = AutonomousToolPolicy.identityRejection(params);
        if (refusal != null) throw AutonomousToolPolicy.refusal(refusal);
        AutonomousToolPolicy.requireSourceAllowed(toolName, ToolExecutionSource.SCHEDULER);
    }

    /**
     * A cron trigger that remembers the planned fire time it last handed out. Spring computes the
     * next planned time before scheduling each run and only after the previous run finished, so the
     * run that starts reads exactly its own planned time, however late it actually starts.
     */
    static final class PlannedFireTrigger implements Trigger {
        private final Trigger cron;
        private volatile Instant planned;

        PlannedFireTrigger(Trigger cron) {
            this.cron = cron;
        }

        @Override
        public Instant nextExecution(TriggerContext context) {
            Instant next = cron.nextExecution(context);
            planned = next;
            return next;
        }

        Instant planned() {
            return planned;
        }
    }

    /**
     * The immutable identity of one planned fire: the cron is evaluated in this JVM's default zone
     * (as before), the key is the absolute instant, so it does not depend on when the fire actually
     * runs. Instances firing for the same planned time share it; adjacent planned times never do.
     */
    static String plannedFireKey(Instant plannedFire) {
        return plannedFire == null ? null : "slot:" + plannedFire;
    }

    /**
     * One cron fire: re-read the config, stop if it is gone or disabled, reschedule if its cron
     * changed elsewhere, otherwise run it under its planned fire's run key.
     */
    void fire(Long configId, String scheduledCron, String key, Instant plannedFire) {
        FactorySchedulerConfig fresh = configId == null ? null : schedulerRepo.findById(configId).orElse(null);
        if (fresh == null || !Boolean.TRUE.equals(fresh.getEnabled())) {
            ScheduledFuture<?> future = activeTasks.remove(key);
            if (future != null) future.cancel(false);
            log.info("Scheduled config id={} is gone or disabled at fire time; stopped on this instance", configId);
            return;
        }
        if (!Objects.equals(scheduledCron, fresh.getCronExpression())) {
            log.info("Scheduled config id={} cron changed elsewhere; rescheduling on this instance", configId);
            scheduleTask(fresh);
            return;
        }
        executeTask(fresh, plannedFireKey(plannedFire));
    }

    /** A run with no planned fire identity: reads run, delegated writes are refused by the runner. */
    private void executeTask(FactorySchedulerConfig config) {
        executeTask(config, null);
    }

    private void executeTask(FactorySchedulerConfig config, String runKey) {
        String validationError = validateConfig(config);
        if (validationError != null) {
            String status = validationStatus(validationError);
            LocalDateTime startedAt = LocalDateTime.now();
            log.error("Skipped invalid scheduled task id={} scope={} status={} error={}",
                    config == null ? null : config.getId(), scopeDescription(config), status, safeError(validationError));
            persistExecutionMetadataThroughProxy(config == null ? null : config.getId(),
                    startedAt, status, safeError(validationError));
            return;
        }

        String toolOrMethod = config.getToolOrMethod();

        // R7 G2: track execution outcome for observability (E2E J2-7d + ops diagnostics).
        // Final state gets persisted via self.persistExecutionMetadata (REQUIRES_NEW tx)
        // so the write commits even if the tool throws.
        final String[] statusHolder = { "SUCCESS" };
        final String[] errorHolder = { null };
        final java.time.LocalDateTime startedAt = java.time.LocalDateTime.now();

        Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolOrMethod);
        if (executor.isEmpty()) {
            String safeToolName = safeError(toolOrMethod);
            log.warn("Scheduled tool not found: {}", safeToolName);
            statusHolder[0] = "TOOL_NOT_FOUND";
            errorHolder[0] = safeError("tool not registered: " + toolOrMethod);
        } else {
            try {
                // Never executed directly: the gateway runs it as the run-as user and audits it.
                AutonomousToolRunner.Outcome outcome = runner.run(config.getFactoryId(), config.getRunAsUserId(),
                        toolOrMethod, config.getParams(), ToolExecutionSource.SCHEDULER,
                        "sched-" + config.getId() + "-" + config.getTaskCode(),
                        SchedulerGrantBinding.of(config, executor.get().getVersion(), objectMapper),
                        runKey);
                if (outcome.replay()) {
                    // Another fire of the same slot (second instance) consumed it: that fire records the
                    // outcome. Writing ours would overwrite its SUCCESS with this instance's view.
                    log.info("Scheduled task id={} slot already run elsewhere: {} {}", config.getId(),
                            outcome.status(), safeError(outcome.error()));
                    return;
                }
                String businessError = outcome.succeeded() && outcome.payload() != null
                        ? businessFailure(outcome.payload().toString()) : null;
                if (!outcome.succeeded()) {
                    statusHolder[0] = outcome.status();
                    errorHolder[0] = safeError(outcome.error());
                    log.warn("Scheduled task id={} not completed: status={} error={}",
                            config.getId(), statusHolder[0], errorHolder[0]);
                } else if (businessError != null) {
                    statusHolder[0] = "FAILED";
                    errorHolder[0] = safeError(businessError);
                    log.warn("Scheduled task id={} returned business failure: {}",
                            config.getId(), errorHolder[0]);
                }
            } catch (Exception e) {
                String safeException = safeError(e.getMessage());
                log.error("Scheduled task id={} failed type={} error={}",
                        config.getId(), e.getClass().getSimpleName(), safeException);
                statusHolder[0] = "FAILED";
                errorHolder[0] = e.getClass().getSimpleName() + ": " + safeException;
            }
        }

        // Persist through self proxy so @Transactional(REQUIRES_NEW) takes effect.
        persistExecutionMetadataThroughProxy(config.getId(), startedAt, statusHolder[0], errorHolder[0]);
    }

    /**
     * R7 G2: best-effort write of execution metadata. Refetch the config row so we
     * don't race against concurrent config updates (Quartz holds the original object
     * from scheduleTask time; the row may have been re-saved in between).
     * Failure to persist metadata must not crash the scheduler thread.
     *
     * REQUIRES_NEW: scheduler threads have no ambient transaction, but being explicit
     * keeps parity with TriggerChainExecutor where event-dispatch threads may carry
     * the publisher's transaction. Consistent pattern across both executors.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistExecutionMetadata(Long configId,
                                         java.time.LocalDateTime startedAt,
                                         String status,
                                         String errorMessage) {
        try {
            if (configId == null) {
                log.error("Cannot persist scheduler execution metadata without config id: status={}", status);
                return;
            }
            schedulerRepo.findById(configId)
                .ifPresent(fresh -> {
                    fresh.setLastExecutedAt(startedAt);
                    fresh.setLastExecutionStatus(status);
                    fresh.setLastExecutionError(safeError(errorMessage));
                    fresh.setExecutionCount((fresh.getExecutionCount() == null ? 0L : fresh.getExecutionCount()) + 1);
                    schedulerRepo.save(fresh);
                });
        } catch (Exception persistErr) {
            log.warn("Failed to persist scheduler execution metadata for config id={} type={} error={}",
                    configId, persistErr.getClass().getSimpleName(), safeError(persistErr.getMessage()));
        }
    }

    private void persistExecutionMetadataThroughProxy(Long configId,
                                                       LocalDateTime startedAt,
                                                       String status,
                                                       String errorMessage) {
        // The fallback keeps direct unit invocation safe; Spring uses the proxy in production.
        DynamicSchedulerService target = self == null ? this : self;
        target.persistExecutionMetadata(configId, startedAt, status, errorMessage);
    }

    private String validateConfig(FactorySchedulerConfig config) {
        if (config == null) return "scheduler config is null";
        if (config.getId() == null) return "scheduler config id is missing";
        if (config.getFactoryId() == null || config.getFactoryId().isBlank()) {
            String error = "factoryId is required; a global null factory scope is not schedulable";
            if (config.getToolOrMethod() != null && !config.getToolOrMethod().isBlank()
                    && toolRegistry.getExecutor(config.getToolOrMethod()).isEmpty()) {
                error += "; tool not registered: " + config.getToolOrMethod();
            }
            return error;
        }
        if (config.getTaskCode() == null || config.getTaskCode().isBlank()) return "taskCode is required";
        if (config.getCronExpression() == null || config.getCronExpression().isBlank()) {
            return "cronExpression is required";
        }
        if (config.getToolOrMethod() == null || config.getToolOrMethod().isBlank()) {
            return "toolOrMethod is required";
        }
        Optional<ToolExecutor> tool = toolRegistry.getExecutor(config.getToolOrMethod());
        if (tool.isEmpty()) {
            return "tool not registered: " + config.getToolOrMethod();
        }
        // Identity parameters are refused at schedule time and before every run. A write tool may be
        // scheduled; each of its runs needs a delegated grant, checked by the runner.
        return AutonomousToolPolicy.identityRejection(config.getParams());
    }

    private void markConfigStatus(FactorySchedulerConfig config, String status, String error) {
        if (config == null) {
            log.error("Cannot record invalid null scheduler config: status={} error={}", status, safeError(error));
            return;
        }
        try {
            if (config.getId() == null) {
                log.error("Cannot record scheduler config status without config id: status={} error={}",
                        status, safeError(error));
                return;
            }
            schedulerRepo.findById(config.getId()).ifPresent(fresh -> {
                fresh.setLastExecutionStatus(status);
                fresh.setLastExecutionError(safeError(error));
                schedulerRepo.save(fresh);
            });
        } catch (Exception persistErr) {
            log.error("Failed to record scheduler config status id={} status={} type={} error={}",
                    config.getId(), status, persistErr.getClass().getSimpleName(), safeError(persistErr.getMessage()));
        }
        log.error("Dynamic scheduler config invalid id={} scope={} status={} error={}",
                config.getId(), scopeDescription(config), status, safeError(error));
    }

    private String businessFailure(String result) {
        if (result == null || result.isBlank()) return null;
        try {
            var node = objectMapper.readTree(result);
            if (node != null && node.isObject() && node.has("success") && node.get("success").isBoolean()
                    && !node.get("success").asBoolean()) {
                if (node.hasNonNull("error")) return node.get("error").asText();
                if (node.hasNonNull("message")) return node.get("message").asText();
                return "tool returned success=false";
            }
        } catch (Exception ignored) {
            // Existing tools may return non-JSON text; preserve their successful behavior.
        }
        return null;
    }

    private String validationStatus(String validationError) {
        if (AutonomousToolPolicy.isWriteDenial(validationError)) return AutonomousToolPolicy.WRITE_DENIED;
        return validationError.startsWith("tool not registered:")
                ? "TOOL_NOT_FOUND" : "CONFIG_INVALID";
    }

    private String safeError(String value) {
        return RequestAuditSupport.sanitizeText(value, 1000);
    }

    private String taskKey(String factoryId, String taskCode) {
        return String.valueOf(factoryId) + ":" + String.valueOf(taskCode);
    }

    private String scopeDescription(FactorySchedulerConfig config) {
        if (config == null) return "<null config>";
        return "factoryId=" + RequestAuditSupport.safeToken(config.getFactoryId(), 100)
                + ", taskCode=" + RequestAuditSupport.safeToken(config.getTaskCode(), 100);
    }

    private List<Long> configIds(List<FactorySchedulerConfig> configs) {
        return configs.stream().map(FactorySchedulerConfig::getId).toList();
    }

    private record ScheduleScope(String factoryId, String taskCode) { }
}
