package com.cretas.aims.service.integration;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.integration.ExternalSourceSnapshot;
import com.cretas.aims.entity.integration.FactoryExternalIntegration;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.integration.ExternalSourceSnapshotRepository;
import com.cretas.aims.repository.integration.FactoryExternalIntegrationRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.security.ActorRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一原型迁移 P10「外部软件」(MIGRATION-PLAN 4.2 / 4.3, DECISIONS 十八 M6)。
 *
 * <ul>
 *   <li><b>授权</b>: 平台专属表 {@code factory_external_integrations}; 只有平台方法 ({@link #upsert} / {@link #issuePushToken})
 *       写, 工厂端只在 feature-config 里只读地看到 {@link #featureConfigView} (只列 ACTIVE 的系统)。</li>
 *   <li><b>推送</b> ({@link #push}): 本机采集推只读快照。凭证 = 授权行上的专用令牌 (只存 SHA-256), 令牌决定工厂和系统,
 *       请求体里的系统 / 来源组织必须与授权行一致; 授权行不是 ACTIVE 一律拒收; 字段按 {@link ExternalDataset#allowedFields()}
 *       白名单, 多一个键就整批拒收 (人员 / 工资字段进不来)。</li>
 *   <li><b>读</b> ({@link #factoryView} / {@link #reportComparison}): 一律按 factoryId 过滤并先校验授权行; 没授权 = 404;
 *       有授权没数据 = {@code available=false} + 「外部数据未接入（找平台）」, ⛔ 不出 0。</li>
 * </ul>
 * 不回写外部系统: 本服务没有任何对外调用。
 */
@Slf4j
@Service
public class ExternalIntegrationService {

    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    public static final long STALE_HOURS = 36;
    public static final String NOT_CONNECTED = "外部数据未接入（找平台）";
    public static final String NOT_AUTHORIZED = "本厂没有开通这个外部软件的接口授权";
    static final int MAX_ROWS_PER_DATASET = 5000;
    static final int COMPARISON_DAYS = 14;
    static final int MAX_CRETAS_SCAN = 5000;
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(10);
    static final String TOKEN_PREFIX = "cxs_";
    private static final Pattern SOURCE_ORG = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FactoryExternalIntegrationRepository integrations;
    private final ExternalSourceSnapshotRepository snapshots;
    private final ProductionReportRepository reports;
    private final WorkProcessTaskRepository tasks;
    private final WorkProcessRepository processes;
    private final ObjectMapper objectMapper;
    Clock clock = Clock.systemUTC();

    public ExternalIntegrationService(FactoryExternalIntegrationRepository integrations,
                                      ExternalSourceSnapshotRepository snapshots,
                                      ProductionReportRepository reports,
                                      WorkProcessTaskRepository tasks,
                                      WorkProcessRepository processes,
                                      ObjectMapper objectMapper) {
        this.integrations = integrations;
        this.snapshots = snapshots;
        this.reports = reports;
        this.tasks = tasks;
        this.processes = processes;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 工厂端只读

    /**
     * feature-config 只读行的内容: {@code {blacklake: {authorized: true, label, lastCollectedAt, stale}}}。
     * 只列 ACTIVE 的系统; 读失败 = 空 (外部部分不出现, 不是「当成有授权」)。
     */
    public Map<String, Object> featureConfigView(String factoryId) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (FactoryExternalIntegration row : integrations.findByFactoryIdOrderBySystemAsc(factoryId)) {
                if (!row.isActive() || !factoryId.equals(row.getFactoryId())) continue;
                Optional<ExternalSystem> system = ExternalSystem.of(row.getSystem());
                if (system.isEmpty()) continue;
                Optional<LocalDateTime> last = snapshots
                        .findFirstByFactoryIdAndSystemOrderByCollectedAtDesc(factoryId, row.getSystem())
                        .map(ExternalSourceSnapshot::getCollectedAt);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("authorized", true);
                entry.put("label", system.get().label());
                entry.put("lastCollectedAt", last.map(ExternalIntegrationService::beijingMinute).orElse(null));
                entry.put("stale", last.map(this::isStale).orElse(false));
                out.put(row.getSystem(), entry);
            }
        } catch (RuntimeException e) {
            log.warn("外部软件授权读取失败, 按未授权下发: factoryId={}, error={}", factoryId, e.toString());
            return new LinkedHashMap<>();
        }
        return out;
    }

    public record DatasetView(String dataset, String label, String collectedAt, int rowCount) {
    }

    public record IntegrationView(String system, String label, String status, String authorizedAt,
                                  String lastCollectedAt, boolean stale, boolean connected, String reason,
                                  List<DatasetView> datasets) {
    }

    /** 本厂有授权的外部软件 (只列 ACTIVE)。没授权 = 空列表, 不报错 (页面据此不渲染)。 */
    @Transactional(readOnly = true)
    public List<IntegrationView> factoryView(String factoryId) {
        List<IntegrationView> out = new ArrayList<>();
        for (FactoryExternalIntegration row : integrations.findByFactoryIdOrderBySystemAsc(factoryId)) {
            if (!row.isActive() || !factoryId.equals(row.getFactoryId())) continue;
            ExternalSystem system = ExternalSystem.of(row.getSystem()).orElse(null);
            if (system == null) continue;
            out.add(integrationView(row, system));
        }
        return out;
    }

    private IntegrationView integrationView(FactoryExternalIntegration row, ExternalSystem system) {
        List<DatasetView> datasets = new ArrayList<>();
        LocalDateTime last = null;
        for (ExternalDataset dataset : ExternalDataset.values()) {
            Optional<ExternalSourceSnapshot> s = snapshots.findFirstByFactoryIdAndSystemAndDatasetOrderByCollectedAtDesc(
                    row.getFactoryId(), row.getSystem(), dataset.code());
            if (s.isEmpty()) continue;
            datasets.add(new DatasetView(dataset.code(), dataset.label(), beijingMinute(s.get().getCollectedAt()),
                    s.get().getRowCount()));
            if (last == null || s.get().getCollectedAt().isAfter(last)) last = s.get().getCollectedAt();
        }
        boolean connected = last != null;
        return new IntegrationView(row.getSystem(), system.label(), row.getStatus(),
                row.getAuthorizedAt() == null ? null : row.getAuthorizedAt().format(MINUTE),
                connected ? beijingMinute(last) : null, connected && isStale(last), connected,
                connected ? null : NOT_CONNECTED, datasets);
    }

    public enum MatchState { MATCH, DIFF, UNIT_MISMATCH, EXTERNAL_ONLY, CRETAS_ONLY }

    public record ComparisonRow(LocalDate reportDate, String productName, String processName,
                                BigDecimal externalGoodQty, String externalUnit, Integer externalReportCount,
                                BigDecimal cretasGoodQty, String cretasUnit, Integer cretasReportCount,
                                BigDecimal difference, MatchState state) {
    }

    /**
     * @param available false = 有授权但外部数据还没进来 ({@link #reason} 写原因), rows 为空 —— 页面显示原因, 不显示 0
     * @param cretasTruncated Cretas 一侧窗口内超过 {@link #MAX_CRETAS_SCAN} 条, 只比了最近的那些 (页面要写明)
     */
    public record Comparison(String system, String label, boolean available, String reason, String asOf,
                             boolean stale, LocalDate from, LocalDate to, boolean cretasTruncated,
                             List<ComparisonRow> rows) {
    }

    /**
     * 报工对照: 外部系统报工 (快照) 与 Cretas 称重报工 (YIELD) 按「报工日 × 产品名 × 工序名」并排。
     * 只读, 不改任何一边; 窗口 = 快照里最近 {@link #COMPARISON_DAYS} 天 (北京日期)。
     */
    @Transactional(readOnly = true)
    public Comparison reportComparison(String factoryId, String systemCode) {
        FactoryExternalIntegration row = requireActive(factoryId, systemCode);
        ExternalSystem system = ExternalSystem.of(row.getSystem()).orElseThrow();
        Optional<ExternalSourceSnapshot> snapshot = snapshots.findFirstByFactoryIdAndSystemAndDatasetOrderByCollectedAtDesc(
                factoryId, row.getSystem(), ExternalDataset.REPORT_OUTPUT.code());
        if (snapshot.isEmpty() || !factoryId.equals(snapshot.get().getFactoryId())) {
            return new Comparison(row.getSystem(), system.label(), false, NOT_CONNECTED, null, false,
                    null, null, false, List.of());
        }
        ExternalSourceSnapshot s = snapshot.get();
        List<Map<String, Object>> external = readRows(s);
        LocalDate to = external.stream().map(r -> parseDate(r.get("reportDate"))).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        if (to == null) {
            return new Comparison(row.getSystem(), system.label(), true, null, beijingMinute(s.getCollectedAt()),
                    isStale(s.getCollectedAt()), null, null, false, List.of());
        }
        LocalDate from = to.minusDays(COMPARISON_DAYS - 1L);

        Map<String, Side> ext = new TreeMap<>();
        for (Map<String, Object> r : external) {
            LocalDate d = parseDate(r.get("reportDate"));
            if (d == null || d.isBefore(from) || d.isAfter(to)) continue;
            String key = key(d, text(r.get("productName")), text(r.get("processName")));
            ext.computeIfAbsent(key, k -> new Side(d, text(r.get("productName")), text(r.get("processName"))))
                    .add(decimal(r.get("goodQty")), text(r.get("unit")), intValue(r.get("reportCount"), 1));
        }

        Page<ProductionReport> page = reports.findByFactoryIdAndReportTypeAndReportDateBetweenAndDeletedAtIsNull(
                factoryId, "YIELD", from, to,
                PageRequest.of(0, MAX_CRETAS_SCAN, Sort.by(Sort.Order.desc("reportDate"), Sort.Order.desc("id"))));
        List<ProductionReport> cretasReports = page.getContent().stream()
                .filter(r -> factoryId.equals(r.getFactoryId())).toList();
        Map<Long, String> processNameByTask = processNames(factoryId, cretasReports);
        Map<String, Side> cretas = new TreeMap<>();
        for (ProductionReport r : cretasReports) {
            if (r.getReportDate() == null) continue;
            String process = processNameByTask.getOrDefault(r.getWorkProcessTaskId(), "");
            String key = key(r.getReportDate(), text(r.getProductName()), process);
            cretas.computeIfAbsent(key, k -> new Side(r.getReportDate(), text(r.getProductName()), process))
                    .add(r.getOutputQuantity(), text(r.getOutputUnit()), 1);
        }

        List<ComparisonRow> rows = new ArrayList<>();
        Set<String> keys = new java.util.TreeSet<>(Comparator.reverseOrder());
        keys.addAll(ext.keySet());
        keys.addAll(cretas.keySet());
        for (String key : keys) {
            Side e = ext.get(key);
            Side c = cretas.get(key);
            Side any = e != null ? e : c;
            rows.add(new ComparisonRow(any.date, any.product, any.process,
                    e == null ? null : e.qty, e == null ? null : e.unit(), e == null ? null : e.count,
                    c == null ? null : c.qty, c == null ? null : c.unit(), c == null ? null : c.count,
                    e != null && c != null && sameUnit(e, c) ? c.qty.subtract(e.qty) : null, state(e, c)));
        }
        return new Comparison(row.getSystem(), system.label(), true, null, beijingMinute(s.getCollectedAt()),
                isStale(s.getCollectedAt()), from, to, page.getTotalElements() > MAX_CRETAS_SCAN, rows);
    }

    private Map<Long, String> processNames(String factoryId, List<ProductionReport> list) {
        Set<Long> taskIds = list.stream().map(ProductionReport::getWorkProcessTaskId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) return Map.of();
        List<WorkProcessTask> taskRows = tasks.findAllById(taskIds).stream()
                .filter(t -> factoryId.equals(t.getFactoryId())).toList();
        Set<String> processIds = taskRows.stream().map(WorkProcessTask::getWorkProcessId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> names = processes.findAllById(processIds).stream()
                .filter(p -> factoryId.equals(p.getFactoryId()))
                .collect(Collectors.toMap(WorkProcess::getId, p -> text(p.getProcessName()), (a, b) -> a));
        Map<Long, String> out = new LinkedHashMap<>();
        for (WorkProcessTask t : taskRows) {
            String name = names.get(t.getWorkProcessId());
            if (name != null) out.put(t.getId(), name);
        }
        return out;
    }

    private static final class Side {
        final LocalDate date;
        final String product;
        final String process;
        BigDecimal qty = BigDecimal.ZERO;
        final Set<String> units = new java.util.TreeSet<>();
        int count;

        Side(LocalDate date, String product, String process) {
            this.date = date;
            this.product = product;
            this.process = process;
        }

        void add(BigDecimal value, String unit, int n) {
            if (value != null) qty = qty.add(value);
            if (!unit.isEmpty()) units.add(unit.toLowerCase(Locale.ROOT));
            count += n;
        }

        String unit() {
            return units.size() == 1 ? units.iterator().next() : units.isEmpty() ? null : String.join("/", units);
        }
    }

    private static boolean sameUnit(Side e, Side c) {
        return e.units.size() == 1 && e.units.equals(c.units);
    }

    private static MatchState state(Side e, Side c) {
        if (e == null) return MatchState.CRETAS_ONLY;
        if (c == null) return MatchState.EXTERNAL_ONLY;
        if (!sameUnit(e, c)) return MatchState.UNIT_MISMATCH;
        BigDecimal tolerance = e.qty.abs().multiply(new BigDecimal("0.005")).max(new BigDecimal("0.01"));
        return c.qty.subtract(e.qty).abs().compareTo(tolerance) <= 0 ? MatchState.MATCH : MatchState.DIFF;
    }

    private static String key(LocalDate d, String product, String process) {
        return d + "\u0001" + product + "\u0001" + process;
    }

    private List<Map<String, Object>> readRows(ExternalSourceSnapshot s) {
        try {
            return objectMapper.readValue(s.getPayload(), new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            throw new BusinessException(500, "外部数据快照读取失败（快照 " + s.getId() + "）");
        }
    }

    /** 本厂该系统的授权行必须存在且 ACTIVE, 否则 404 (不区分「没有」和「暂停」, 也不透露别厂)。 */
    public FactoryExternalIntegration requireActive(String factoryId, String systemCode) {
        if (ExternalSystem.of(systemCode).isEmpty()) throw new BusinessException(404, NOT_AUTHORIZED);
        return integrations.findByFactoryIdAndSystem(factoryId, systemCode)
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .filter(FactoryExternalIntegration::isActive)
                .orElseThrow(() -> new BusinessException(404, NOT_AUTHORIZED));
    }

    // ------------------------------------------------------------------ 平台写

    public record PlatformRow(String system, String label, String status, String sourceOrgId, String authorizedAt,
                              Long authorizedBy, String note, boolean hasPushToken, String pushTokenIssuedAt,
                              String lastCollectedAt, boolean stale) {
    }

    public record Update(String status, String sourceOrgId, String note) {
    }

    public record IssuedToken(String system, String token, String issuedAt) {
    }

    @Transactional(readOnly = true)
    public List<PlatformRow> platformView(String factoryId) {
        Map<String, FactoryExternalIntegration> byCode = integrations.findByFactoryIdOrderBySystemAsc(factoryId).stream()
                .collect(Collectors.toMap(FactoryExternalIntegration::getSystem, Function.identity(), (a, b) -> a));
        List<PlatformRow> out = new ArrayList<>();
        for (ExternalSystem system : ExternalSystem.values()) {
            FactoryExternalIntegration row = byCode.get(system.code());
            if (row == null) {
                out.add(new PlatformRow(system.code(), system.label(), "UNSET", null, null, null, null,
                        false, null, null, false));
                continue;
            }
            Optional<LocalDateTime> last = snapshots.findFirstByFactoryIdAndSystemOrderByCollectedAtDesc(
                    factoryId, system.code()).map(ExternalSourceSnapshot::getCollectedAt);
            out.add(new PlatformRow(system.code(), system.label(), row.getStatus(), row.getSourceOrgId(),
                    row.getAuthorizedAt() == null ? null : row.getAuthorizedAt().format(MINUTE), row.getAuthorizedBy(),
                    row.getNote(), row.getPushTokenSha256() != null,
                    row.getPushTokenIssuedAt() == null ? null : row.getPushTokenIssuedAt().format(MINUTE),
                    last.map(ExternalIntegrationService::beijingMinute).orElse(null), last.map(this::isStale).orElse(false)));
        }
        return out;
    }

    /** 建 / 改授权行。调用方已过 {@code PlatformFactoryControlAuthority.require(.., write=true)}。 */
    @Transactional
    public List<PlatformRow> upsert(String factoryId, String systemCode, Update body, ActorRef actor) {
        ExternalSystem system = ExternalSystem.of(systemCode)
                .orElseThrow(() -> new BusinessException(400, "不支持的外部软件: " + systemCode));
        if (body == null || body.status() == null
                || !Set.of(FactoryExternalIntegration.ACTIVE, FactoryExternalIntegration.SUSPENDED).contains(body.status())) {
            throw new BusinessException(400, "状态只能是 ACTIVE（授权）或 SUSPENDED（暂停）");
        }
        String sourceOrgId = blankToNull(body.sourceOrgId());
        if (sourceOrgId != null && !SOURCE_ORG.matcher(sourceOrgId).matches()) {
            throw new BusinessException(400, "来源组织 id 只能是字母、数字和 _ . : -，最长 64 位");
        }
        if (FactoryExternalIntegration.ACTIVE.equals(body.status()) && sourceOrgId == null) {
            throw new BusinessException(400, "授权前要填外部系统里的来源组织 id（推送时据此核对）");
        }
        String note = blankToNull(body.note());
        if (note != null && note.length() > 500) throw new BusinessException(400, "备注最长 500 字");
        LocalDateTime now = LocalDateTime.now(clock.withZone(BEIJING));
        FactoryExternalIntegration row = integrations.findByFactoryIdAndSystem(factoryId, system.code()).orElse(null);
        if (row == null) {
            row = FactoryExternalIntegration.builder().factoryId(factoryId).system(system.code())
                    .authorizedAt(now).authorizedBy(actor.id()).build();
        } else if (!row.isActive() && FactoryExternalIntegration.ACTIVE.equals(body.status())) {
            row.setAuthorizedAt(now);
            row.setAuthorizedBy(actor.id());
        }
        row.setStatus(body.status());
        row.setSourceOrgId(sourceOrgId);
        row.setNote(note);
        row.setUpdatedAt(now);
        row.setUpdatedBy(actor.id());
        integrations.save(row);
        log.info("外部软件授权变更: factoryId={}, system={}, status={}, actorId={}", factoryId, system.code(),
                body.status(), actor.id());
        return platformView(factoryId);
    }

    /** 签发 (或轮换) 推送令牌: 明文只在这里返回一次, 库里只留 SHA-256; 旧令牌当场失效。 */
    @Transactional
    public IssuedToken issuePushToken(String factoryId, String systemCode, ActorRef actor) {
        FactoryExternalIntegration row = integrations.findByFactoryIdAndSystem(factoryId, systemCode)
                .orElseThrow(() -> new BusinessException(404, "先建授权行，再签发推送凭证"));
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime now = LocalDateTime.now(clock.withZone(BEIJING));
        row.setPushTokenSha256(sha256(token));
        row.setPushTokenIssuedAt(now);
        row.setUpdatedAt(now);
        row.setUpdatedBy(actor.id());
        integrations.save(row);
        log.info("外部快照推送凭证已签发: factoryId={}, system={}, actorId={}", factoryId, systemCode, actor.id());
        return new IssuedToken(row.getSystem(), token, now.format(MINUTE));
    }

    // ------------------------------------------------------------------ 本机推送

    public record PushRequest(String system, String sourceOrgId, String collectedAt, String collectorRunId,
                              Map<String, List<Map<String, Object>>> datasets) {
    }

    public record PushedDataset(String dataset, int rowCount, boolean duplicate) {
    }

    public record PushResult(String factoryId, String system, String collectedAt, List<PushedDataset> datasets) {
    }

    /**
     * 本机采集推送只读快照。令牌决定工厂和系统; 只写 {@code external_source_snapshots}, 不碰任何业务表。
     * 失败一律整批拒收 (事务回滚), 不会只写进一半。
     */
    @Transactional
    public PushResult push(String token, PushRequest body) {
        if (token == null || token.isBlank() || !token.startsWith(TOKEN_PREFIX)) {
            throw new BusinessException(401, "推送凭证缺失或无效");
        }
        FactoryExternalIntegration row = integrations.findByPushTokenSha256(sha256(token.trim()))
                .orElseThrow(() -> new BusinessException(401, "推送凭证缺失或无效"));
        if (!row.isActive()) throw new BusinessException(403, "该工厂的外部软件授权已暂停，拒收快照");
        if (body == null) throw new BusinessException(400, "请求体为空");
        if (!row.getSystem().equals(body.system())) throw new BusinessException(403, "推送凭证与外部软件不匹配");
        if (row.getSourceOrgId() == null || !row.getSourceOrgId().equals(body.sourceOrgId())) {
            throw new BusinessException(403, "来源组织与授权记录不一致，拒收");
        }
        LocalDateTime collectedAt = parseCollectedAt(body.collectedAt());
        String runId = blankToNull(body.collectorRunId());
        if (runId != null && !RUN_ID.matcher(runId).matches()) throw new BusinessException(400, "collectorRunId 格式不对");
        if (body.datasets() == null || body.datasets().isEmpty()) throw new BusinessException(400, "没有数据集");

        LocalDateTime receivedAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        List<PushedDataset> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : body.datasets().entrySet()) {
            ExternalDataset dataset = ExternalDataset.of(entry.getKey())
                    .orElseThrow(() -> new BusinessException(400, "不接收的数据集: " + entry.getKey()));
            List<Map<String, Object>> rows = entry.getValue() == null ? List.of() : entry.getValue();
            validateRows(dataset, rows);
            String payload = canonical(rows);
            String sha = sha256(payload);
            Optional<ExternalSourceSnapshot> existing = snapshots.findByFactoryIdAndSystemAndDatasetAndCollectedAt(
                    row.getFactoryId(), row.getSystem(), dataset.code(), collectedAt);
            if (existing.isPresent()) {
                if (!existing.get().getPayloadSha256().equals(sha)) {
                    throw new BusinessException(409, "同一采集时点已收过内容不同的快照（" + dataset.code() + "），拒收");
                }
                out.add(new PushedDataset(dataset.code(), rows.size(), true));
                continue;
            }
            snapshots.save(ExternalSourceSnapshot.builder().factoryId(row.getFactoryId()).system(row.getSystem())
                    .dataset(dataset.code()).collectedAt(collectedAt).receivedAt(receivedAt).rowCount(rows.size())
                    .payload(payload).payloadSha256(sha).collectorRunId(runId).build());
            out.add(new PushedDataset(dataset.code(), rows.size(), false));
        }
        log.info("外部快照已收: factoryId={}, system={}, collectedAt={}Z, datasets={}", row.getFactoryId(),
                row.getSystem(), collectedAt, out);
        return new PushResult(row.getFactoryId(), row.getSystem(), beijingMinute(collectedAt), out);
    }

    private void validateRows(ExternalDataset dataset, List<Map<String, Object>> rows) {
        if (rows.size() > MAX_ROWS_PER_DATASET) {
            throw new BusinessException(413, dataset.code() + " 超过 " + MAX_ROWS_PER_DATASET + " 行，拒收");
        }
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (r == null) throw new BusinessException(400, dataset.code() + " 第 " + (i + 1) + " 行为空");
            for (Map.Entry<String, Object> f : r.entrySet()) {
                if (!dataset.allowedFields().contains(f.getKey())) {
                    throw new BusinessException(400, dataset.code() + " 第 " + (i + 1) + " 行有不接收的字段「"
                            + f.getKey() + "」，整批拒收（只收白名单字段，人员 / 工资字段不进 47）");
                }
                Object v = f.getValue();
                if (v != null && !(v instanceof String || v instanceof Number || v instanceof Boolean)) {
                    throw new BusinessException(400, dataset.code() + " 第 " + (i + 1) + " 行字段「" + f.getKey()
                            + "」不是标量，拒收");
                }
                if (v instanceof String s && s.length() > 200) {
                    throw new BusinessException(400, dataset.code() + " 第 " + (i + 1) + " 行字段「" + f.getKey()
                            + "」超过 200 字，拒收");
                }
            }
            if (dataset == ExternalDataset.REPORT_OUTPUT && parseDate(r.get("reportDate")) == null) {
                throw new BusinessException(400, "report_output 第 " + (i + 1) + " 行 reportDate 不是 YYYY-MM-DD");
            }
        }
    }

    private LocalDateTime parseCollectedAt(String text) {
        if (text == null) throw new BusinessException(400, "collectedAt 必须是带时区的 ISO 时刻");
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "collectedAt 必须是带时区的 ISO 时刻");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (at.isAfter(now.plus(MAX_FUTURE_SKEW))) throw new BusinessException(400, "collectedAt 晚于当前时间，拒收");
        return at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private String canonical(List<Map<String, Object>> rows) {
        try {
            List<Map<String, Object>> sorted = rows.stream().map(r -> (Map<String, Object>) new TreeMap<>(r)).toList();
            return objectMapper.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new BusinessException(400, "快照内容无法序列化");
        }
    }

    // ------------------------------------------------------------------ 工具

    boolean isStale(LocalDateTime collectedAtUtc) {
        return Duration.between(collectedAtUtc.toInstant(ZoneOffset.UTC), clock.instant()).toHours() >= STALE_HOURS;
    }

    static String beijingMinute(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(BEIJING).format(MINUTE);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static LocalDate parseDate(Object o) {
        if (!(o instanceof String s)) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) return null;
        try {
            return new BigDecimal(String.valueOf(o).trim()).setScale(3, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intValue(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        return fallback;
    }
}
