package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.shortage.ProcessParamDeviationCaseDTO;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.shortage.ProcessParamDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.entity.shortage.ShortageCaseEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.shortage.ProcessParamDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.PermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Source-specific case writer for ordinary LOW/HIGH process-parameter facts.
 *
 * <p>The default is deliberately disabled for the compatibility rollout.  The
 * report transaction still calls {@link #recordReport(ProductionReport)}, but
 * no case row is created until the explicit feature property is enabled.</p>
 */
@Service
public class ProcessParamDeviationCaseService {

    public static final String FEATURE_PROPERTY = "cretas.process-param-deviation.case.write-enabled";
    public static final String OWNER_ROLE = "quality_manager";
    private static final String QUALITY_CASE_WRITE = "quality:read_write";
    private static final String PRODUCTION_CASE_WRITE = "production:read_write";
    public static final List<ShortageCase.SourceType> SOURCES =
            List.of(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION);

    public enum Applied { DISABLED, IGNORED, CREATED, REFRESHED, UNCHANGED, ALREADY_RESOLVED }

    /** A tenant-scoped, active factory user that may be selected as the case owner. */
    public record Assignee(Long id, String name, String role) { }

    public record Fact(String factoryId, ProcessParamDeviationCaseDetail.SourceKind sourceKind, String sourceKey,
                       Long sourceReportId, Long productionBatchId, Long workProcessTaskId,
                       String productionPlanId, String processEntryStepKey, String processName, String batchNumber,
                       String productTypeId, String paramKey, String paramLabel,
                       String unit, BigDecimal measuredValue, BigDecimal standardValue, BigDecimal rangeMin,
                       BigDecimal rangeMax, String status, LocalDateTime observedAt) {
        /** Compatibility constructor for callers that do not have display metadata. */
        public Fact(String factoryId, ProcessParamDeviationCaseDetail.SourceKind sourceKind, String sourceKey,
                    Long sourceReportId, Long productionBatchId, Long workProcessTaskId,
                    String productionPlanId, String processEntryStepKey, String productTypeId, String paramKey,
                    String paramLabel, String unit, BigDecimal measuredValue, BigDecimal standardValue,
                    BigDecimal rangeMin, BigDecimal rangeMax, String status, LocalDateTime observedAt) {
            this(factoryId, sourceKind, sourceKey, sourceReportId, productionBatchId, workProcessTaskId,
                    productionPlanId, processEntryStepKey, null, null, productTypeId, paramKey, paramLabel,
                    unit, measuredValue, standardValue, rangeMin, rangeMax, status, observedAt);
        }

        public Fact {
            Objects.requireNonNull(factoryId, "factoryId");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(sourceKey, "sourceKey");
            Objects.requireNonNull(paramKey, "paramKey");
            Objects.requireNonNull(status, "status");
            if (observedAt == null) observedAt = LocalDateTime.now();
        }

        String fingerprint() {
            return sha256(String.join("|", nullToEmpty(sourceKey), nullToEmpty(status),
                    decimal(measuredValue), decimal(standardValue), decimal(rangeMin), decimal(rangeMax)));
        }
    }

    private final ShortageCaseRepository caseRepository;
    private final ProcessParamDeviationCaseDetailRepository detailRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final UserRepository userRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProcessSecretAggregateExclusions aggregateExclusions;
    private final PermissionService permissionService;
    private final boolean writeEnabled;

    public ProcessParamDeviationCaseService(ShortageCaseRepository caseRepository,
                                            ProcessParamDeviationCaseDetailRepository detailRepository,
                                            ShortageCaseEventRepository eventRepository,
                                            UserRepository userRepository,
                                            ProductionBatchRepository batchRepository,
                                            ProcessSecretAggregateExclusions aggregateExclusions,
                                            PermissionService permissionService,
                                            @Value("${" + FEATURE_PROPERTY + ":false}") boolean writeEnabled) {
        this.caseRepository = caseRepository;
        this.detailRepository = detailRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.batchRepository = batchRepository;
        this.aggregateExclusions = aggregateExclusions;
        this.permissionService = permissionService;
        this.writeEnabled = writeEnabled;
    }

    /** Report writers call this once per persisted YIELD row. */
    @Transactional
    public List<Applied> recordReport(ProductionReport report) {
        return recordReport(report, null);
    }

    /**
     * App yield submission supplies the factory-resolved work-process name.  It
     * wins over any client-shaped custom field; the no-argument entry point
     * retains the Clerk custom-field precedence for backwards compatibility.
     */
    @Transactional
    public List<Applied> recordReport(ProductionReport report, String resolvedProcessName) {
        if (!writeEnabled || report == null || report.getCustomFields() == null) {
            return List.of(writeEnabled ? Applied.IGNORED : Applied.DISABLED);
        }
        Object raw = report.getCustomFields().get("paramChecks");
        if (!(raw instanceof Collection<?> checks) || checks.isEmpty()) return List.of(Applied.IGNORED);
        List<Applied> applied = new ArrayList<>();
        for (Object value : checks) {
            if (!(value instanceof Map<?, ?> check)) continue;
            String key = text(check.get("key"));
            if (key == null) continue;
            String status = text(check.get("status"));
            if (status == null) continue;
            String stepKey = text(report.getCustomFields().get("processEntryStepKey"));
            ProcessParamDeviationCaseDetail.SourceKind kind = stepKey == null
                    ? ProcessParamDeviationCaseDetail.SourceKind.YIELD_REPORT
                    : ProcessParamDeviationCaseDetail.SourceKind.PROCESS_SHEET_ROW;
            String sourceKey = sourceKey(kind, report.getId(), report.getBatchId(), stepKey, key);
            var batch = report.getBatchId() == null || batchRepository == null ? null
                    : batchRepository.findByIdAndFactoryId(report.getBatchId(), report.getFactoryId()).orElse(null);
            String productionPlanId = batch == null ? null : batch.getProductionPlanId();
            String batchNumber = batch == null ? null : batch.getBatchNumber();
            String processName = text(resolvedProcessName);
            if (processName == null) processName = text(report.getCustomFields().get("processEntryProcessName"));
            if (processName == null) processName = text(report.getCustomFields().get("processName"));
            if (processName == null) processName = text(report.getProcessCategory());
            Fact fact = new Fact(report.getFactoryId(), kind, sourceKey, report.getId(), report.getBatchId(),
                    report.getWorkProcessTaskId(), productionPlanId, stepKey, processName, batchNumber,
                    report.getProductTypeId(), key,
                    text(check.get("label")), text(check.get("unit")), decimalValue(check.get("value")),
                    decimalValue(check.get("standardValue")), decimalValue(check.get("min")),
                    decimalValue(check.get("max")), status, report.getUpdatedAt() == null
                    ? report.getCreatedAt() : report.getUpdatedAt());
            applied.add(apply(fact, null));
        }
        return applied;
    }

    @Transactional
    public Applied apply(Fact fact, ActorRef actor) {
        if (!writeEnabled) return Applied.DISABLED;
        if (!"LOW".equals(fact.status()) && !"HIGH".equals(fact.status()) && !"OK".equals(fact.status())) {
            return Applied.IGNORED;
        }
        ShortageCase c = caseRepository.findByFactoryIdAndSourceTypeAndSourceIdForUpdate(
                fact.factoryId(), ShortageCase.SourceType.PROCESS_PARAM_DEVIATION, fact.sourceKey()).orElse(null);
        ProcessParamDeviationCaseDetail detail = detailRepository
                .findByFactoryIdAndSourceKey(fact.factoryId(), fact.sourceKey()).orElse(null);
        if (detail != null) {
            if (c == null || !c.getId().equals(detail.getCaseId())) {
                throw new BusinessException(409, "异常处理实例与读数不一致, 请稍后重试")
                        .withHint("刷新异常详情后重试");
            }
            if (fact.fingerprint().equals(detail.getFactFingerprint())) {
                return c.getClosedAt() == null ? Applied.UNCHANGED : Applied.ALREADY_RESOLVED;
            }
            refresh(c, detail, fact, actor);
            return c.getClosedAt() == null ? Applied.REFRESHED : Applied.ALREADY_RESOLVED;
        }
        if (c != null) {
            throw new BusinessException(409, "异常读数不存在, 请稍后重试")
                    .withHint("刷新异常列表后重试");
        }
        if (!"LOW".equals(fact.status()) && !"HIGH".equals(fact.status())) return Applied.IGNORED;
        c = new ShortageCase();
        c.setFactoryId(fact.factoryId());
        c.setSourceType(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION);
        c.setSourceId(fact.sourceKey());
        c.setStage(ShortageCase.Stage.DETECTED);
        c.setOwnerRole(OWNER_ROLE);
        c.setGapLineCount(1);
        c.setInputFingerprint(fact.fingerprint());
        c.setLastRefreshedAt(fact.observedAt());
        c.setLastRefreshReason("PROCESS_PARAM_DEVIATION");
        c = caseRepository.saveAndFlush(c);

        detail = new ProcessParamDeviationCaseDetail();
        detail.setCaseId(c.getId());
        detail.setFactoryId(fact.factoryId());
        copy(detail, fact);
        detailRepository.saveAndFlush(detail);
        event(c, ShortageCaseEvent.EventType.CREATED, null, c.getStage(), "PROCESS_PARAM_DEVIATION", actor);
        return Applied.CREATED;
    }

    @Transactional(readOnly = true)
    public List<ProcessParamDeviationCaseDTO.CaseView> list(String factoryId, boolean openOnly, int page, int size) {
        var rows = caseRepository.findForFactoryBySources(factoryId, openOnly, SOURCES,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100))).getContent();
        var details = detailRepository.findByCaseIdIn(rows.stream().map(ShortageCase::getId).toList());
        List<ProcessParamDeviationCaseDTO.CaseView> out = new ArrayList<>();
        for (ShortageCase c : rows) {
            details.stream().filter(d -> d.getCaseId().equals(c.getId())).findFirst()
                    .filter(d -> readable(factoryId, d)).map(d -> ProcessParamDeviationCaseDTO.view(c, d, ownerName(factoryId, c.getOwnerUserId())))
                    .ifPresent(out::add);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ProcessParamDeviationCaseDTO.DetailView detail(String factoryId, String caseId) {
        ShortageCase c = own(factoryId, caseId);
        ProcessParamDeviationCaseDetail d = detailRepository.findByCaseIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "工艺参数异常读数不存在"));
        assertReadable(factoryId, d);
        return ProcessParamDeviationCaseDTO.detail(c, d, ownerName(factoryId, c.getOwnerUserId()));
    }

    @Transactional(readOnly = true)
    public List<Assignee> assignees(String factoryId) {
        List<com.cretas.aims.entity.User> users = userRepository.findByFactoryIdAndIsActive(factoryId, true);
        if (users == null) return List.of();
        return users.stream()
                .filter(Objects::nonNull)
                .filter(u -> permissionService != null && permissionService.hasAnyPermission(u,
                        QUALITY_CASE_WRITE, PRODUCTION_CASE_WRITE))
                .map(u -> new Assignee(u.getId(), displayName(u), u.getRoleCode()))
                .toList();
    }

    @Transactional
    public void assign(String factoryId, String caseId, Long ownerUserId, long expectedVersion, ActorRef actor) {
        ShortageCase c = lockOpen(factoryId, caseId);
        assertReadable(factoryId, currentDetailForUpdate(factoryId, caseId));
        assertVersion(c, expectedVersion);
        if (ownerUserId == null) throw new BusinessException(400, "请选择负责人");
        var user = userRepository.findByIdAndFactoryId(ownerUserId, factoryId)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .orElseThrow(() -> new BusinessException(400, "负责人不是本厂有效用户"));
        if (permissionService == null || !permissionService.hasAnyPermission(user,
                QUALITY_CASE_WRITE, PRODUCTION_CASE_WRITE)) {
            throw new BusinessException(403, "负责人没有工艺参数异常处理权限")
                    .withHint("请选择具备质量或生产处理读写权限的本厂用户");
        }
        c.setOwnerUserId(user.getId());
        caseRepository.saveAndFlush(c);
        event(c, ShortageCaseEvent.EventType.NOTE, c.getStage(), c.getStage(), "OWNER_ASSIGNED", actor);
    }

    @Transactional
    public void startVerify(String factoryId, String caseId, long expectedVersion, ActorRef actor) {
        ShortageCase c = lockOpen(factoryId, caseId);
        assertReadable(factoryId, currentDetailForUpdate(factoryId, caseId));
        assertVersion(c, expectedVersion);
        if (c.getOwnerUserId() == null) throw new BusinessException(409, "请先指定负责人, 再开始核查")
                .withHint("先选择具备质量或生产处理权限的负责人");
        if (c.getStage() != ShortageCase.Stage.DETECTED) throw new BusinessException(409, "这条异常已在核查中")
                .withHint("刷新处理实例后继续");
        ShortageCase.Stage from = c.getStage();
        c.setStage(ShortageCase.Stage.EXECUTING);
        caseRepository.saveAndFlush(c);
        event(c, ShortageCaseEvent.EventType.STAGE_CHANGED, from, c.getStage(), "VERIFY_STARTED", actor);
    }

    @Transactional
    public void resolve(String factoryId, String caseId, ProcessParamDeviationCaseDetail.Resolution resolution,
                        String note, long expectedVersion, ActorRef actor) {
        ShortageCase c = lockCase(factoryId, caseId);
        if (c.getClosedAt() != null) return; // safe replay: never re-open a resolved fact
        ProcessParamDeviationCaseDetail d = currentDetailForUpdate(factoryId, caseId);
        assertReadable(factoryId, d);
        if (c.getVersion() != expectedVersion) throw new BusinessException(409, "异常已被别人更新, 请刷新后重试")
                .withHint("刷新异常详情后重试");
        if (c.getStage() != ShortageCase.Stage.EXECUTING) throw new BusinessException(409, "请先开始核查, 再填写处理结果")
                .withHint("先开始核查后再填写处理结果");
        if (resolution == null || note == null || note.isBlank()) throw new BusinessException(400, "处理结果和说明都要填写");
        LocalDateTime now = LocalDateTime.now();
        ShortageCase.Stage from = c.getStage();
        c.setStage(ShortageCase.Stage.CLOSED);
        c.setClosedAt(now);
        d.setResolutionCode(resolution);
        d.setResolutionNote(note.trim());
        d.setResolvedBy(actor == null ? null : actor.factoryUserId());
        d.setResolvedAt(now);
        caseRepository.saveAndFlush(c);
        detailRepository.saveAndFlush(d);
        event(c, ShortageCaseEvent.EventType.STAGE_CHANGED, from, c.getStage(), "RESOLVED", actor);
    }

    private void refresh(ShortageCase c, ProcessParamDeviationCaseDetail d, Fact fact, ActorRef actor) {
        d.setFactFingerprint(fact.fingerprint());
        copy(d, fact);
        c.setInputFingerprint(fact.fingerprint());
        c.setLastRefreshedAt(fact.observedAt());
        c.setLastRefreshReason("PROCESS_PARAM_DEVIATION_REFRESH");
        caseRepository.saveAndFlush(c);
        detailRepository.saveAndFlush(d);
        event(c, ShortageCaseEvent.EventType.REFRESHED, c.getStage(), c.getStage(),
                "PROCESS_PARAM_DEVIATION_REFRESH", actor);
    }

    private ShortageCase own(String factoryId, String caseId) {
        return caseRepository.findByIdAndFactoryIdAndSourceTypeIn(caseId, factoryId, SOURCES)
                .orElseThrow(() -> new BusinessException(404, "工艺参数异常不存在或不属于本厂"));
    }

    private ShortageCase lockOpen(String factoryId, String caseId) {
        ShortageCase current = lockCase(factoryId, caseId);
        if (current.getClosedAt() != null) throw new BusinessException(409, "这条异常已结束")
                .withHint("该异常已关闭，无需重复处理");
        return current;
    }

    private ShortageCase lockCase(String factoryId, String caseId) {
        return caseRepository.findForUpdateByIdAndFactoryIdAndSourceTypeIn(caseId, factoryId, SOURCES)
                .orElseThrow(() -> new BusinessException(404, "工艺参数异常不存在或不属于本厂"));
    }

    private void assertVersion(ShortageCase c, long expectedVersion) {
        if (expectedVersion < 0 || c.getVersion() != expectedVersion) {
            throw new BusinessException(409, "异常已被别人更新, 请刷新后重试")
                    .withHint("刷新异常详情后重试");
        }
    }

    private boolean readable(String factoryId, ProcessParamDeviationCaseDetail d) {
        var hidden = aggregateExclusions.forCurrentCaller(factoryId, "process param deviation case");
        if (hidden == null) return false;
        if (hidden.productTypeIds().isEmpty() && hidden.planIds().isEmpty()) return true;
        String product = d.getProductTypeId();
        String plan = d.getProductionPlanId();
        if (product == null || hidden.productTypeIds().contains(product)) return false;
        return plan == null || !hidden.planIds().contains(plan);
    }

    private ProcessParamDeviationCaseDetail currentDetail(String factoryId, String caseId) {
        return detailRepository.findByCaseIdAndFactoryId(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "工艺参数异常读数不存在"));
    }

    private ProcessParamDeviationCaseDetail currentDetailForUpdate(String factoryId, String caseId) {
        return detailRepository.findByCaseIdAndFactoryIdForUpdate(caseId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "工艺参数异常读数不存在"));
    }

    private void assertReadable(String factoryId, ProcessParamDeviationCaseDetail d) {
        if (!readable(factoryId, d)) throw new BusinessException(403, "该产品的工艺参数异常受保密策略保护");
    }

    private static void copy(ProcessParamDeviationCaseDetail d, Fact f) {
        d.setSourceKey(f.sourceKey());
        d.setSourceKind(f.sourceKind());
        d.setSourceReportId(f.sourceReportId());
        d.setProductionBatchId(f.productionBatchId());
        d.setProductionPlanId(f.productionPlanId());
        d.setWorkProcessTaskId(f.workProcessTaskId());
        d.setProcessEntryStepKey(f.processEntryStepKey());
        d.setProcessName(f.processName());
        d.setBatchNumber(f.batchNumber());
        d.setProductTypeId(f.productTypeId());
        d.setParamKey(f.paramKey());
        d.setParamLabel(f.paramLabel());
        d.setUnit(f.unit());
        d.setMeasuredValue(f.measuredValue());
        d.setStandardValue(f.standardValue());
        d.setRangeMin(f.rangeMin());
        d.setRangeMax(f.rangeMax());
        d.setStatus(f.status());
        d.setFactFingerprint(f.fingerprint());
        d.setObservedAt(f.observedAt());
    }

    private String ownerName(String factoryId, Long ownerUserId) {
        if (ownerUserId == null) return null;
        return userRepository.findByIdAndFactoryId(ownerUserId, factoryId)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .map(ProcessParamDeviationCaseService::displayName)
                .orElse(null);
    }

    private static String displayName(com.cretas.aims.entity.User user) {
        String fullName = text(user.getFullName());
        return fullName == null ? text(user.getUsername()) : fullName;
    }

    private void event(ShortageCase c, ShortageCaseEvent.EventType type, ShortageCase.Stage from,
                       ShortageCase.Stage to, String reason, ActorRef actor) {
        var e = new com.cretas.aims.entity.shortage.ShortageCaseEvent();
        e.setCaseId(c.getId());
        e.setFactoryId(c.getFactoryId());
        e.setEventType(type);
        e.setFromStage(from);
        e.setToStage(to);
        e.setTriggerReason(reason);
        e.setDetail("工艺参数异常处理状态已记录");
        e.setActorType(actor == null ? "SYSTEM" : actor.type().name());
        e.setActorId(actor == null ? null : actor.id());
        eventRepository.save(e);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal decimalValue(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        try { return new BigDecimal(value.toString().trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String decimal(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    /** Keep the ShortageCase.source_id (191 chars) safe without changing identity semantics. */
    private static String sourceKey(ProcessParamDeviationCaseDetail.SourceKind kind, Long reportId,
                                    Long batchId, String stepKey, String paramKey) {
        String candidate = kind == ProcessParamDeviationCaseDetail.SourceKind.YIELD_REPORT
                ? "yield-report:" + reportId + ":" + paramKey
                : "process-sheet:" + batchId + ":" + stepKey + ":" + paramKey;
        if (candidate.length() <= 191) return candidate;
        String prefix = kind == ProcessParamDeviationCaseDetail.SourceKind.YIELD_REPORT
                ? "yield-report:" + reportId + ":" : "process-sheet:" + batchId + ":";
        return prefix + sha256(nullToEmpty(stepKey) + "|" + paramKey).substring(0, 64);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
