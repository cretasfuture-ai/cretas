package com.cretas.aims.service.tenantbackup;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.factorydata.FactoryDataCatalog;
import com.cretas.aims.service.factorydata.FactoryDataService;
import com.cretas.aims.service.factorydata.FactoryDataService.AttachmentMode;
import com.cretas.aims.service.factorydata.FactoryDataService.Format;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.FactoryOverview;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Frequency;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.KeyHolder;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Plan;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Run;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.RunStatus;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Source;
import com.cretas.aims.service.tenantbackup.TenantBackupStore.StoredBackup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按工厂备份套餐 (手册 docs/security/tenant-backup-plans-runbook.md)。
 *
 * <p>一次备份 = 整厂导出 ({@link FactoryDataService}, JSON + manifest, 严格按 factory_id) -> age 加密 ->
 * OSS {@code tenants/<工厂编号>/<工厂编号>_<yyyyMMdd_HHmmss>.zip.age} -> 回读大小核对 -> 执行记录。
 * 清理按套餐保留期只删本厂目录下的过期件, 且永远留下最新一份 (备份连续失败时不至于删光)。
 *
 * <p>身份 (Steve 2026-10-26, 口径 Q7): 定时任务以配置项指定的平台管理员身份在进程内调用导出, 不发长期 token、
 * 不开 HTTP 入口; 导出回执记 trigger_source = BACKUP_SCHEDULER, 本表也记; 只对已开通套餐的工厂执行,
 * 未开通的记一条 SKIPPED。
 */
@Slf4j
@Service
public class TenantBackupService {

    public static final String SCHEDULER_TRIGGER = "BACKUP_SCHEDULER";
    public static final String MANUAL_TRIGGER = "BACKUP_MANUAL";
    public static final String PREFIX = "tenants/";
    /** 日志里带这个标记的行就是要人看的备份告警。 */
    public static final String ALERT_MARKER = "TENANT_BACKUP_ALERT";

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern OBJECT_STAMP = Pattern.compile("_(\\d{8}_\\d{6})\\.zip\\.age$");
    /** 调度每天跑 3 次 (01:20 / 04:20 / 05:20); 到期判断留出 6 小时余量, 当天成功后不会再跑。 */
    static final int DUE_SLACK_HOURS = 6;
    /** RUNNING 超过这么久视为中断。 */
    static final int STALE_RUN_HOURS = 6;

    public record PlanRequest(Boolean enabled, Frequency frequency, Integer retentionDays, Boolean includeAttachments,
                              KeyHolder keyHolder, String customerRecipient, String reason) {}

    public enum Health { NOT_ENABLED, WAITING_FIRST_RUN, OK, OVERDUE, LAST_FAILED }

    public record FactoryRow(String factoryId, String factoryName, Plan plan, Run lastRun, Run lastSuccess,
                             Health health) {}

    public record StoredView(String key, long size, LocalDateTime takenAt) {}

    public record FactoryDetail(String factoryId, String factoryName, Plan plan, List<Run> runs,
                                List<TenantBackupRepository.PlanChange> changes, List<StoredView> stored,
                                String storedError) {}

    public record PurgeResult(int factoriesScanned, int deleted, int deletedOfRemovedFactories, List<String> errors) {}

    public record TickResult(int defaultPlansAdded, int staleRunsFailed, int ran, int succeeded, int failed,
                             PurgeResult purge) {}

    private final TenantBackupRepository repo;
    private final FactoryDataService factoryData;
    private final TenantBackupStore store;
    private final BackupEncryptor encryptor;
    private final Clock clock;
    private final String schedulerAdminId;
    private final Path platformRecipientFile;
    private final Path tmpDir;
    private final LocalDateTime autoEnableCreatedAfter;
    private final ExecutorService manualRuns = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tenant-backup-manual");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Autowired
    public TenantBackupService(TenantBackupRepository repo, FactoryDataService factoryData, TenantBackupStore store,
                               BackupEncryptor encryptor,
                               @Value("${cretas.tenant-backup.scheduler-admin-id:}") String schedulerAdminId,
                               @Value("${cretas.tenant-backup.platform-recipient-file:/etc/cretas-backup/age-recipient.txt}") String platformRecipientFile,
                               @Value("${cretas.tenant-backup.tmp-dir:${java.io.tmpdir}/tenant-backup}") String tmpDir,
                               @Value("${cretas.tenant-backup.auto-enable-created-after:2026-10-27T00:00:00}") String autoEnableCreatedAfter) {
        this(repo, factoryData, store, encryptor, Clock.systemDefaultZone(), schedulerAdminId,
                Path.of(platformRecipientFile), Path.of(tmpDir), LocalDateTime.parse(autoEnableCreatedAfter));
    }

    TenantBackupService(TenantBackupRepository repo, FactoryDataService factoryData, TenantBackupStore store,
                        BackupEncryptor encryptor, Clock clock, String schedulerAdminId, Path platformRecipientFile,
                        Path tmpDir, LocalDateTime autoEnableCreatedAfter) {
        this.repo = repo;
        this.factoryData = factoryData;
        this.store = store;
        this.encryptor = encryptor;
        this.clock = clock;
        this.schedulerAdminId = schedulerAdminId == null ? "" : schedulerAdminId.trim();
        this.platformRecipientFile = platformRecipientFile;
        this.tmpDir = tmpDir;
        this.autoEnableCreatedAfter = autoEnableCreatedAfter;
    }

    @PreDestroy
    void shutdown() {
        manualRuns.shutdownNow();
    }

    // ----------------------------------------------------------------- 一次备份

    /**
     * 对一个工厂做一次备份。⛔ 只对已开通套餐的工厂执行; 其余情况记一条 SKIPPED 并返回。
     * 失败不抛出: 记 FAILED、打告警日志、返回那条记录 (调度要接着做下一个厂)。
     */
    public Run backup(String factoryId, AuthenticatedActor actor, String trigger) {
        String actorType = actor == null ? null : actor.type().name();
        Long actorId = actor == null ? null : actor.id();
        Plan plan = repo.findPlan(factoryId).orElse(null);
        String skip = null;
        if (plan == null || !plan.enabled()) skip = "该厂未开通备份套餐, 不执行";
        else if (repo.factoryName(factoryId).isEmpty()) skip = "工厂不存在 (可能已彻底删除), 不执行";
        else if (repo.hasRunning(factoryId)) skip = "该厂已有一次备份在进行, 本次不重复执行";
        if (skip != null) {
            long id = repo.startRun(factoryId, trigger, actorType, actorId, RunStatus.SKIPPED, plan, null, skip);
            return repo.findRun(id).orElseThrow();
        }
        AttachmentMode mode = plan.includeAttachments() ? AttachmentMode.INCLUDE : AttachmentMode.INDEX_ONLY;
        long runId = repo.startRun(factoryId, trigger, actorType, actorId, RunStatus.RUNNING, plan, mode.name(), null);
        Path tmp = null;
        Long exportId = null;
        try {
            String recipient = recipientFor(plan);
            LocalDateTime now = LocalDateTime.now(clock);
            String key = PREFIX + factoryId + "/" + factoryId + "_" + STAMP.format(now) + ".zip.age";
            Files.createDirectories(tmpDir);
            tmp = tmpDir.resolve(factoryId + "_" + STAMP.format(now) + "_" + runId + ".zip.age");
            exportId = factoryData.startExport(actor, factoryId, trigger);
            long requestId = exportId;
            long[] plainBytes = {0};
            encryptor.encrypt(recipient, out -> {
                OutputStream counted = new FilterOutputStream(out) {
                    @Override public void write(int b) throws IOException { out.write(b); plainBytes[0]++; }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                        plainBytes[0] += len;
                    }
                    @Override public void close() throws IOException { flush(); }  // 由加密器关
                };
                factoryData.writeExport(requestId, actor, factoryId, mode, EnumSet.of(Format.JSON), counted);
                counted.flush();
            }, tmp);
            long localSize = Files.size(tmp);
            String sha = sha256(tmp);
            long remoteSize = store.put(tmp, key);
            if (remoteSize != localSize) {
                throw new IOException("上传后大小不一致: 本地 " + localSize + " 字节, OSS " + remoteSize + " 字节");
            }
            repo.finishRun(runId, RunStatus.SUCCEEDED, exportId, key, plainBytes[0], localSize, sha, null);
            log.info("tenant-backup OK factoryId={} trigger={} key={} plain={} encrypted={}", factoryId, trigger, key,
                    plainBytes[0], localSize);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            repo.finishRun(runId, RunStatus.FAILED, exportId, null, null, null, null, msg);
            log.error("{} factoryId={} trigger={} runId={} 备份失败: {}", ALERT_MARKER, factoryId, trigger, runId, msg, e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { /* 加密件, 不含明文 */ }
            }
        }
        return repo.findRun(runId).orElseThrow();
    }

    private String recipientFor(Plan plan) throws IOException {
        if (plan.keyHolder() == KeyHolder.CUSTOMER) {
            if (!BackupEncryptor.validRecipient(plan.customerRecipient())) {
                throw new IOException("客户自持钥匙的公钥格式不对, 拒绝上传明文");
            }
            return plan.customerRecipient();
        }
        String recipient = Files.exists(platformRecipientFile)
                ? Files.readAllLines(platformRecipientFile, StandardCharsets.UTF_8).stream().map(String::trim)
                        .filter(BackupEncryptor::validRecipient).findFirst().orElse(null)
                : null;
        if (recipient == null) {
            throw new IOException("平台公钥文件 " + platformRecipientFile + " 不存在或没有 age1 公钥, 拒绝上传明文");
        }
        return recipient;
    }

    // --------------------------------------------------------------------- 调度

    /** 定时任务的一次执行: 补新厂默认套餐 -> 收尸 -> 逐个到期工厂备份 -> 清理过期件。 */
    public TickResult tick() {
        AuthenticatedActor actor = schedulerActor();   // 配置不对时抛出, 调度器打告警
        int stale = repo.failStaleRuns(LocalDateTime.now(clock).minusHours(STALE_RUN_HOURS));
        List<String> added = repo.enableDefaultPlanForNewFactories(autoEnableCreatedAfter,
                toJson(planJson(new Plan(null, true, Frequency.DAILY, 7, false, KeyHolder.PLATFORM, null,
                        Source.AUTO_NEW_FACTORY, null, null, null))));
        if (!added.isEmpty()) log.info("tenant-backup: 新建工厂默认开启「每天 × 7 天」: {}", added);
        int ran = 0, ok = 0, failed = 0;
        for (Plan plan : repo.listPlans()) {
            if (!plan.enabled()) continue;
            Run last = repo.lastSuccess(plan.factoryId()).orElse(null);
            if (!isDue(plan, last, LocalDateTime.now(clock))) continue;
            ran++;
            Run r = backup(plan.factoryId(), actor, SCHEDULER_TRIGGER);
            if (r.status() == RunStatus.SUCCEEDED) ok++;
            else if (r.status() == RunStatus.FAILED) failed++;
        }
        PurgeResult purge = purgeExpired();
        TickResult result = new TickResult(added.size(), stale, ran, ok, failed, purge);
        log.info("tenant-backup tick: {}", result);
        return result;
    }

    static boolean isDue(Plan plan, Run lastSuccess, LocalDateTime now) {
        if (lastSuccess == null) return true;
        return !lastSuccess.startedAt().isAfter(now.minusDays(plan.frequency().days()).plusHours(DUE_SLACK_HOURS));
    }

    /** 配置项指定的平台管理员; 必须存在、active、super_admin。 */
    AuthenticatedActor schedulerActor() {
        long id;
        try {
            id = Long.parseLong(schedulerAdminId);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("未配置 cretas.tenant-backup.scheduler-admin-id (TENANT_BACKUP_SCHEDULER_ADMIN_ID)");
        }
        String role = repo.activePlatformAdminRole(id)
                .orElseThrow(() -> new IllegalStateException("调度用的平台管理员 " + id + " 不存在或未启用"));
        AuthenticatedActor actor = new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, id, null, role);
        factoryData.requireOperator(actor);   // 不在导出白名单里就在这里失败, 而不是逐厂失败
        return actor;
    }

    // --------------------------------------------------------------------- 清理

    /**
     * 按保留期清理。只看 {@code tenants/<工厂编号>/}:
     * 工厂已不存在 (被彻底删除) -> 该目录全部删除; 有套餐 -> 删超过保留期的, 但永远留最新一份;
     * 没有套餐 -> 不动。文件名里解析不出时间的对象不动。
     */
    public PurgeResult purgeExpired() {
        LocalDateTime now = LocalDateTime.now(clock);
        int scanned = 0, deleted = 0, removed = 0;
        List<String> errors = new ArrayList<>();
        for (String factoryId : store.listChildren(PREFIX)) {
            if (!FactoryDataCatalog.validFactoryId(factoryId)) continue;
            scanned++;
            try {
                List<StoredBackup> objects = store.list(PREFIX + factoryId + "/");
                if (repo.factoryName(factoryId).isEmpty()) {
                    List<String> keys = objects.stream().map(StoredBackup::key).toList();
                    if (!keys.isEmpty()) {
                        store.delete(keys);
                        removed += keys.size();
                        log.info("tenant-backup purge: 工厂 {} 已不存在, 删除其全部 {} 份备份", factoryId, keys.size());
                    }
                    continue;
                }
                Optional<Plan> plan = repo.findPlan(factoryId);
                if (plan.isEmpty()) continue;
                LocalDateTime cutoff = now.minusDays(plan.get().retentionDays());
                List<Map.Entry<StoredBackup, LocalDateTime>> dated = new ArrayList<>();
                for (StoredBackup o : objects) {
                    LocalDateTime t = takenAt(o.key());
                    if (t != null) dated.add(Map.entry(o, t));
                }
                dated.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
                List<String> expired = new ArrayList<>();
                for (int i = 1; i < dated.size(); i++) {   // i = 0 是最新一份, 永远保留
                    if (dated.get(i).getValue().isBefore(cutoff)) expired.add(dated.get(i).getKey().key());
                }
                if (!expired.isEmpty()) {
                    store.delete(expired);
                    deleted += expired.size();
                }
            } catch (RuntimeException e) {
                errors.add(factoryId + ": " + e.getMessage());
                log.error("{} factoryId={} 清理过期备份失败: {}", ALERT_MARKER, factoryId, e.getMessage(), e);
            }
        }
        return new PurgeResult(scanned, deleted, removed, errors);
    }

    static LocalDateTime takenAt(String key) {
        Matcher m = OBJECT_STAMP.matcher(key);
        if (!m.find()) return null;
        try {
            return LocalDateTime.parse(m.group(1), STAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ----------------------------------------------------------------- 平台管理

    public List<FactoryRow> overview() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<FactoryRow> out = new ArrayList<>();
        for (FactoryOverview o : repo.overview()) {
            out.add(new FactoryRow(o.factoryId(), o.factoryName(), o.plan(), o.lastRun(), o.lastSuccess(),
                    health(o.plan(), o.lastRun(), o.lastSuccess(), now)));
        }
        return out;
    }

    static Health health(Plan plan, Run lastRun, Run lastSuccess, LocalDateTime now) {
        if (plan == null || !plan.enabled()) return Health.NOT_ENABLED;
        if (lastRun != null && lastRun.status() == RunStatus.FAILED) return Health.LAST_FAILED;
        LocalDateTime since = lastSuccess != null ? lastSuccess.startedAt() : plan.updatedAt();
        boolean late = since != null && since.isBefore(now.minusDays(plan.frequency().days() + 1L));
        if (late) return Health.OVERDUE;
        return lastSuccess == null ? Health.WAITING_FIRST_RUN : Health.OK;
    }

    public FactoryDetail detail(String factoryId) {
        String name = repo.factoryName(factoryId).orElseThrow(() -> new BusinessException(404, "工厂不存在: " + factoryId));
        List<StoredView> stored = new ArrayList<>();
        String storedError = null;
        try {
            for (StoredBackup o : store.list(PREFIX + factoryId + "/")) {
                stored.add(new StoredView(o.key(), o.size(), takenAt(o.key())));
            }
            stored.sort(Comparator.comparing(StoredView::key).reversed());
        } catch (RuntimeException e) {
            storedError = "读取备份存储失败: " + e.getMessage();
        }
        return new FactoryDetail(factoryId, name, repo.findPlan(factoryId).orElse(null), repo.listRuns(factoryId, 50),
                repo.listChanges(factoryId, 50), stored, storedError);
    }

    /** 改套餐 (只有平台; 口径 Q6), 同一事务里留痕。 */
    public Plan updatePlan(AuthenticatedActor actor, String factoryId, PlanRequest req) {
        if (actor == null || !actor.isPlatformAdmin()) throw new BusinessException(403, "只有平台可以修改备份套餐");
        if (repo.factoryName(factoryId).isEmpty()) throw new BusinessException(404, "工厂不存在: " + factoryId);
        if (req == null || req.enabled() == null || req.frequency() == null || req.retentionDays() == null
                || req.keyHolder() == null) {
            throw new BusinessException(400, "enabled / frequency / retentionDays / keyHolder 都必须填写");
        }
        if (!TenantBackupRepository.RETENTION_DAYS.contains(req.retentionDays())) {
            throw new BusinessException(400, "保留期只能是 " + TenantBackupRepository.RETENTION_DAYS + " 天之一");
        }
        String recipient = null;
        if (req.keyHolder() == KeyHolder.CUSTOMER) {
            recipient = req.customerRecipient() == null ? null : req.customerRecipient().trim();
            if (!BackupEncryptor.validRecipient(recipient)) {
                throw new BusinessException(400, "客户自持钥匙需要填写客户的 age 公钥 (age1 开头, 共 62 位)");
            }
        }
        if (req.reason() == null || req.reason().isBlank()) {
            throw new BusinessException(400, "请填写修改原因 (如合同编号), 会记进改动记录");
        }
        Plan before = repo.findPlan(factoryId).orElse(null);
        Plan after = new Plan(factoryId, req.enabled(), req.frequency(), req.retentionDays(),
                Boolean.TRUE.equals(req.includeAttachments()), req.keyHolder(), recipient, Source.PLATFORM, actor.id(),
                before == null ? null : before.createdAt(), null);
        repo.savePlan(after, before == null ? null : toJson(planJson(before)), toJson(planJson(after)),
                actor.type().name(), actor.id(), actor.role(), req.reason().trim());
        return repo.findPlan(factoryId).orElseThrow();
    }

    /**
     * 平台手动触发一次 (用发起人的身份, trigger_source = BACKUP_MANUAL), 后台执行, 立即返回。
     * 发起人必须在整厂导出的白名单里 —— 在这里先判, 不让请求变成一条必然失败的记录。
     */
    public void runNow(AuthenticatedActor actor, String factoryId) {
        factoryData.requireOperator(actor);
        Plan plan = repo.findPlan(factoryId).orElse(null);
        if (plan == null || !plan.enabled()) {
            throw new BusinessException(409, "该厂未开通备份套餐, 先开通再执行")
                    .withHint("在本页点「开通」, 选好频率与保留期并填写原因后再执行");
        }
        if (repo.hasRunning(factoryId)) {
            throw new BusinessException(409, "该厂已有一次备份在进行")
                    .withHint("几分钟后点「记录」查看这次的结果, 不需要重复执行");
        }
        manualRuns.submit(() -> backup(factoryId, actor, MANUAL_TRIGGER));
    }

    // ----------------------------------------------------------------- 工厂侧

    public record FactoryView(Plan plan, List<Run> runs) {}

    /** 工厂只读: 套餐 (隐去客户公钥以外的内部字段无关紧要, 原样给) 与最近的执行记录。 */
    public FactoryView factoryView(String factoryId) {
        return new FactoryView(repo.findPlan(factoryId).orElse(null), repo.listRuns(factoryId, 100));
    }

    /**
     * 工厂下载自己的备份件。只有「客户自持钥匙」的件可以下载 —— 平台钥匙加密的件工厂自己解不开,
     * 需要时由平台解密后交付 (口径 Q4 的 A 级恢复)。
     */
    public Run downloadable(String factoryId, long runId) {
        Run run = repo.findRun(runId).filter(r -> r.factoryId().equals(factoryId))
                .orElseThrow(() -> new BusinessException(404, "备份记录不存在"));
        if (run.status() != RunStatus.SUCCEEDED || run.objectKey() == null) {
            throw new BusinessException(409, "这次备份没有成功, 没有可下载的文件")
                    .withHint("请选择一条结果为「成功」的备份下载");
        }
        if (!KeyHolder.CUSTOMER.name().equals(run.keyHolder())) {
            throw new BusinessException(409, "这份备份用平台钥匙加密, 工厂无法自行解开; 需要时请联系平台解密后交付")
                    .withHint("联系平台, 说明要取回哪一天的备份");
        }
        boolean present = store.list(run.objectKey()).stream().anyMatch(o -> o.key().equals(run.objectKey()));
        if (!present) throw new BusinessException(410, "这份备份已超过保留期被清理");
        return run;
    }

    public void copyTo(String objectKey, OutputStream out) throws IOException {
        store.copyTo(objectKey, out);
    }

    // ------------------------------------------------------------------- 杂项

    private static Map<String, Object> planJson(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", p.enabled());
        m.put("frequency", p.frequency().name());
        m.put("retentionDays", p.retentionDays());
        m.put("includeAttachments", p.includeAttachments());
        m.put("keyHolder", p.keyHolder().name());
        m.put("customerRecipient", p.customerRecipient());
        return m;
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
