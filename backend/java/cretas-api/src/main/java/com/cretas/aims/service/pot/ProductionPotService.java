package com.cretas.aims.service.pot;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.pot.ProductionPot;
import com.cretas.aims.entity.pot.ProductionPotCorrection;
import com.cretas.aims.entity.pot.ProductionPotInput;
import com.cretas.aims.entity.pot.ProductionPotOutput;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.pot.ProductionPotCorrectionRepository;
import com.cretas.aims.repository.pot.ProductionPotInputRepository;
import com.cretas.aims.repository.pot.ProductionPotOutputRepository;
import com.cretas.aims.repository.pot.ProductionPotRepository;
import com.cretas.aims.service.unit.UnitDisplayNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S3-16 锅次: 录入 / 更正 / 确认 / 作废 / 按生产批次查看。
 *
 * <p>判据 (验收场景 S3-16):
 * <ul>
 *   <li>锅次时间与人工工时分开: 这里只有下锅/出锅时间, 人工工时仍在报工 laborSegments;</li>
 *   <li>更正保留: 每次改动写 {@link ProductionPotCorrection} (原值 / 新值 / 原因 / 人 / 时间);</li>
 *   <li>摄像候选时间不能自动成为精确时间: {@code CAMERA_CANDIDATE} 恒未确认, 不算时长;</li>
 *   <li>锅批关联不明时保留「待确认」, 不当作精确事实。</li>
 * </ul>
 * 锅的投入 / 产出只能落在这个生产批次真实的血缘节点上, 同节点各锅之和不超过批次量。
 */
@Service
@RequiredArgsConstructor
public class ProductionPotService {

    public static final String CODE_NOT_A_BATCH_NODE = "POT_LINK_NOT_A_BATCH_NODE";
    public static final String CODE_OVER_ALLOCATED = "POT_LINK_OVER_ALLOCATED";
    public static final String CODE_DUPLICATE_NUMBER = "POT_NUMBER_DUPLICATE";
    public static final String CODE_REASON_REQUIRED = "POT_REASON_REQUIRED";
    public static final String CODE_VOIDED = "POT_VOIDED";
    public static final String CODE_INVALID = "POT_INVALID";

    private final ProductionPotRepository potRepository;
    private final ProductionPotInputRepository inputRepository;
    private final ProductionPotOutputRepository outputRepository;
    private final ProductionPotCorrectionRepository correctionRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final PotNodeIndex nodeIndex;
    private final EntityManager entityManager;
    private final PotProcessSecretRedactor secretRedactor;
    private final com.cretas.aims.repository.ProcessSheetRowRepository processSheetRowRepository;

    /** 工艺保密时一并去掉的更正记录字段 (原值 / 新值本身就是工艺)。 */
    static final Set<String> SECRET_FIELDS = Set.of("processParams", "remark");

    // ==================== 命令 / 视图 ====================

    /** 投入 / 产出分摊: nodeType = MATERIAL_BATCH / FINISHED_BATCH, nodeId = 批次主键。 */
    public record PotLink(String nodeType, String nodeId, BigDecimal quantity, String unit) {}

    public record PotCommand(
            Long productionBatchId,
            String productionPlanId,
            Long processSheetRowId,
            String processCode,
            String processName,
            Integer potNumber,
            String equipmentLabel,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String timeSource,
            String bindingStatus,
            Long operatorId,
            String operatorName,
            BigDecimal outputQuantity,
            String outputUnit,
            Map<String, Object> processParams,
            List<PotLink> inputs,
            List<PotLink> outputs,
            String remark) {}

    /** 更正: 为 null 的字段保持不变; inputs / outputs 非 null 时整组替换。reason 必填。 */
    public record CorrectionCommand(
            Integer potNumber,
            String equipmentLabel,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String timeSource,
            Long operatorId,
            String operatorName,
            BigDecimal outputQuantity,
            String outputUnit,
            Map<String, Object> processParams,
            List<PotLink> inputs,
            List<PotLink> outputs,
            String remark,
            String reason) {}

    /** 主管确认: 关联不明 → 确认; 摄像候选时间 → 改为人工确认的时间 (可同时改时间)。 */
    public record ConfirmCommand(LocalDateTime startedAt, LocalDateTime endedAt, String reason) {}

    public record LinkView(String nodeType, String nodeId, String batchNumber, BigDecimal quantity, String unit) {}

    public record CorrectionView(String action, String fieldName, String oldValue, String newValue, String reason,
                                 Long correctedBy, String correctedByName, LocalDateTime correctedAt) {}

    public record PotView(
            Long id,
            Long productionBatchId,
            String productionPlanId,
            Long processSheetRowId,
            String processCode,
            String processName,
            Integer potNumber,
            String equipmentLabel,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String timeSource,
            boolean timeConfirmed,
            /** 只有时间齐全且已确认才给; 否则 null (未知不硬算)。 */
            Long durationMinutes,
            String bindingStatus,
            Long operatorId,
            String operatorName,
            BigDecimal outputQuantity,
            String outputUnit,
            Map<String, Object> processParams,
            String source,
            String status,
            String voidReason,
            String remark,
            List<LinkView> inputs,
            List<LinkView> outputs,
            List<CorrectionView> corrections,
            /** true = 该产品工艺保密且当前账号无完整查看权: 工序名 / 代码 / 工艺参数 / 备注已去掉。 */
            boolean processRedacted) {}

    /** 批次级节点 vs 已分到锅上的量; complete = 已全部分完 (召回才能按锅收窄)。 */
    public record AllocationLine(String nodeType, String nodeId, String batchNumber, BigDecimal batchQuantity,
                                 BigDecimal allocatedQuantity, String unit, boolean complete) {}

    public record BatchPotsView(Long productionBatchId, String batchNumber, int potCount, int pendingCount,
                                List<PotView> pots, List<AllocationLine> inputAllocation,
                                List<AllocationLine> outputAllocation) {}

    // ==================== 录入 ====================

    @Transactional
    public PotView record(String factoryId, PotCommand cmd, Long userId) {
        if (cmd == null || cmd.productionBatchId() == null) {
            throw invalid("必须指定生产批次");
        }
        // 锁生产批次行: 同一批次的并发录入 / 更正串行, 「各锅之和不超过批次量」不会被两个事务各算一半绕过
        ProductionBatch batch = requireBatchForUpdate(factoryId, cmd.productionBatchId());
        assertRowAndPlanBelongToBatch(factoryId, batch, cmd.processSheetRowId(), cmd.productionPlanId());
        ProductionPot pot = ProductionPot.builder()
                .factoryId(factoryId)
                .productionBatchId(batch.getId())
                .productionPlanId(batch.getProductionPlanId() != null ? batch.getProductionPlanId() : cmd.productionPlanId())
                .processSheetRowId(cmd.processSheetRowId())
                .processCode(blankToNull(cmd.processCode()))
                .processName(blankToNull(cmd.processName()))
                .potNumber(cmd.potNumber())
                .equipmentLabel(blankToNull(cmd.equipmentLabel()))
                .startedAt(cmd.startedAt())
                .endedAt(cmd.endedAt())
                .timeSource(normalizeTimeSource(cmd.timeSource()))
                .bindingStatus(normalizeBinding(cmd.bindingStatus()))
                .operatorId(cmd.operatorId() != null ? cmd.operatorId() : userId)
                .operatorName(blankToNull(cmd.operatorName()))
                .outputQuantity(cmd.outputQuantity())
                .outputUnit(blankToNull(cmd.outputUnit()))
                .processParams(cmd.processParams() == null ? new HashMap<>() : new HashMap<>(cmd.processParams()))
                .source(cmd.processSheetRowId() != null ? ProductionPot.SOURCE_PROCESS_SHEET : ProductionPot.SOURCE_POT_API)
                .remark(blankToNull(cmd.remark()))
                .createdBy(userId)
                .build();
        applyTimeRules(pot);
        validateScalars(pot);
        assertNumberFree(factoryId, pot, null);

        List<PotLink> inputs = cmd.inputs() == null ? List.of() : cmd.inputs();
        List<PotLink> outputs = cmd.outputs() == null ? List.of() : cmd.outputs();
        validateLinks(factoryId, batch.getId(), null, inputs, true);
        validateLinks(factoryId, batch.getId(), null, outputs, false);

        ProductionPot saved = potRepository.saveAndFlush(pot);
        saveLinks(factoryId, saved.getId(), inputs, outputs, userId);
        return toViews(factoryId, List.of(saved)).get(0);
    }

    // ==================== 更正 / 确认 / 作废 ====================

    @Transactional
    public PotView correct(String factoryId, Long potId, CorrectionCommand cmd, Long userId, String userName) {
        if (cmd == null) {
            throw invalid("更正内容不能为空");
        }
        String reason = requireReason(cmd.reason());
        ProductionPot pot = requireActivePotForUpdate(factoryId, potId);
        if ((cmd.processParams() != null || cmd.remark() != null)
                && secretRedactor.restrictedBatches(factoryId, List.of(pot.getProductionBatchId()))
                        .contains(pot.getProductionBatchId())) {
            // 看不到原值就不许改: 否则一个空值会把看不见的工艺参数覆盖掉
            throw new BusinessException(403, "该产品工艺已设为保密，无权修改这一锅的工艺参数和备注")
                    .withHint("其它字段 (时间、出锅量、用料、去向) 可以照常更正; 工艺参数请找保密负责人");
        }
        List<ProductionPotCorrection> log = new ArrayList<>();

        if (cmd.potNumber() != null && !cmd.potNumber().equals(pot.getPotNumber())) {
            log.add(change(pot, "potNumber", pot.getPotNumber(), cmd.potNumber(), reason, userId, userName));
            pot.setPotNumber(cmd.potNumber());
        }
        if (cmd.equipmentLabel() != null && !Objects.equals(blankToNull(cmd.equipmentLabel()), pot.getEquipmentLabel())) {
            log.add(change(pot, "equipmentLabel", pot.getEquipmentLabel(), blankToNull(cmd.equipmentLabel()), reason, userId, userName));
            pot.setEquipmentLabel(blankToNull(cmd.equipmentLabel()));
        }
        boolean timeChanged = false;
        if (cmd.startedAt() != null && !cmd.startedAt().equals(pot.getStartedAt())) {
            log.add(change(pot, "startedAt", pot.getStartedAt(), cmd.startedAt(), reason, userId, userName));
            pot.setStartedAt(cmd.startedAt());
            timeChanged = true;
        }
        if (cmd.endedAt() != null && !cmd.endedAt().equals(pot.getEndedAt())) {
            log.add(change(pot, "endedAt", pot.getEndedAt(), cmd.endedAt(), reason, userId, userName));
            pot.setEndedAt(cmd.endedAt());
            timeChanged = true;
        }
        if (cmd.timeSource() != null) {
            String ts = normalizeTimeSource(cmd.timeSource());
            if (!ts.equals(pot.getTimeSource())) {
                log.add(change(pot, "timeSource", pot.getTimeSource(), ts, reason, userId, userName));
                pot.setTimeSource(ts);
                timeChanged = true;
            }
        }
        if (timeChanged) {
            boolean before = Boolean.TRUE.equals(pot.getTimeConfirmed());
            applyTimeRules(pot);
            if (before != Boolean.TRUE.equals(pot.getTimeConfirmed())) {
                log.add(change(pot, "timeConfirmed", before, pot.getTimeConfirmed(), reason, userId, userName));
            }
        }
        if (cmd.operatorId() != null && !cmd.operatorId().equals(pot.getOperatorId())) {
            log.add(change(pot, "operatorId", pot.getOperatorId(), cmd.operatorId(), reason, userId, userName));
            pot.setOperatorId(cmd.operatorId());
        }
        if (cmd.operatorName() != null && !Objects.equals(blankToNull(cmd.operatorName()), pot.getOperatorName())) {
            log.add(change(pot, "operatorName", pot.getOperatorName(), blankToNull(cmd.operatorName()), reason, userId, userName));
            pot.setOperatorName(blankToNull(cmd.operatorName()));
        }
        if (cmd.outputQuantity() != null && !sameNumber(cmd.outputQuantity(), pot.getOutputQuantity())) {
            log.add(change(pot, "outputQuantity", plain(pot.getOutputQuantity()), plain(cmd.outputQuantity()), reason, userId, userName));
            pot.setOutputQuantity(cmd.outputQuantity());
        }
        if (cmd.outputUnit() != null && unitChanged(factoryId, pot.getOutputUnit(), blankToNull(cmd.outputUnit()))) {
            log.add(change(pot, "outputUnit", pot.getOutputUnit(), blankToNull(cmd.outputUnit()), reason, userId, userName));
            pot.setOutputUnit(blankToNull(cmd.outputUnit()));
        }
        if (cmd.processParams() != null && !cmd.processParams().equals(pot.getProcessParams())) {
            log.add(change(pot, "processParams", pot.getProcessParams(), cmd.processParams(), reason, userId, userName));
            pot.setProcessParams(new HashMap<>(cmd.processParams()));
        }
        if (cmd.remark() != null && !Objects.equals(blankToNull(cmd.remark()), pot.getRemark())) {
            log.add(change(pot, "remark", pot.getRemark(), blankToNull(cmd.remark()), reason, userId, userName));
            pot.setRemark(blankToNull(cmd.remark()));
        }
        validateScalars(pot);
        assertNumberFree(factoryId, pot, pot.getId());

        if (cmd.inputs() != null || cmd.outputs() != null || cmd.potNumber() != null) {
            requireBatchForUpdate(factoryId, pot.getProductionBatchId());   // 与 record 同一把锁
        }
        List<ProductionPotInput> oldInputs = inputRepository.findByFactoryIdAndPotIdIn(factoryId, List.of(potId));
        List<ProductionPotOutput> oldOutputs = outputRepository.findByFactoryIdAndPotIdIn(factoryId, List.of(potId));
        boolean replaceInputs = cmd.inputs() != null && !describeInputs(oldInputs).equals(describeLinks(cmd.inputs()));
        boolean replaceOutputs = cmd.outputs() != null && !describeOutputs(oldOutputs).equals(describeLinks(cmd.outputs()));
        if (replaceInputs) {
            validateLinks(factoryId, pot.getProductionBatchId(), potId, cmd.inputs(), true);
            log.add(change(pot, "inputs", describeInputs(oldInputs), describeLinks(cmd.inputs()), reason, userId, userName));
        }
        if (replaceOutputs) {
            validateLinks(factoryId, pot.getProductionBatchId(), potId, cmd.outputs(), false);
            log.add(change(pot, "outputs", describeOutputs(oldOutputs), describeLinks(cmd.outputs()), reason, userId, userName));
        }
        if (log.isEmpty()) {
            throw invalid("没有任何字段发生变化").withHint("请修改要更正的内容后再提交");
        }
        // 旧分摊先软删并 flush: 部分唯一索引按 deleted_at IS NULL 判, Hibernate 默认先插后删会撞索引
        if (replaceInputs) {
            inputRepository.deleteAll(oldInputs);
        }
        if (replaceOutputs) {
            outputRepository.deleteAll(oldOutputs);
        }
        entityManager.flush();
        saveLinks(factoryId, potId, replaceInputs ? cmd.inputs() : List.of(),
                replaceOutputs ? cmd.outputs() : List.of(), userId);
        potRepository.saveAndFlush(pot);
        correctionRepository.saveAll(log);
        return toViews(factoryId, List.of(pot)).get(0);
    }

    @Transactional
    public PotView confirm(String factoryId, Long potId, ConfirmCommand cmd, Long userId, String userName) {
        String reason = requireReason(cmd == null ? null : cmd.reason());
        ProductionPot pot = requireActivePotForUpdate(factoryId, potId);
        List<ProductionPotCorrection> log = new ArrayList<>();
        if (cmd.startedAt() != null && !cmd.startedAt().equals(pot.getStartedAt())) {
            log.add(change(pot, "startedAt", pot.getStartedAt(), cmd.startedAt(), reason, userId, userName, ProductionPotCorrection.ACTION_CONFIRM));
            pot.setStartedAt(cmd.startedAt());
        }
        if (cmd.endedAt() != null && !cmd.endedAt().equals(pot.getEndedAt())) {
            log.add(change(pot, "endedAt", pot.getEndedAt(), cmd.endedAt(), reason, userId, userName, ProductionPotCorrection.ACTION_CONFIRM));
            pot.setEndedAt(cmd.endedAt());
        }
        if (ProductionPot.BINDING_PENDING.equals(pot.getBindingStatus())) {
            log.add(change(pot, "bindingStatus", pot.getBindingStatus(), ProductionPot.BINDING_CONFIRMED, reason, userId, userName, ProductionPotCorrection.ACTION_CONFIRM));
            pot.setBindingStatus(ProductionPot.BINDING_CONFIRMED);
        }
        boolean hasTimes = pot.getStartedAt() != null && pot.getEndedAt() != null;
        if (!Boolean.TRUE.equals(pot.getTimeConfirmed()) && hasTimes) {
            // 候选 / 未知时间经主管确认 ⇒ 成为人工确认的时间; 原来源留在更正记录里
            if (!ProductionPot.TIME_MANUAL.equals(pot.getTimeSource()) && !ProductionPot.TIME_DEVICE.equals(pot.getTimeSource())) {
                log.add(change(pot, "timeSource", pot.getTimeSource(), ProductionPot.TIME_MANUAL, reason, userId, userName, ProductionPotCorrection.ACTION_CONFIRM));
                pot.setTimeSource(ProductionPot.TIME_MANUAL);
            }
            log.add(change(pot, "timeConfirmed", false, true, reason, userId, userName, ProductionPotCorrection.ACTION_CONFIRM));
            pot.setTimeConfirmed(true);
        }
        if (log.isEmpty()) {
            throw invalid(hasTimes ? "这一锅已经是确认状态" : "下锅/出锅时间不全, 不能确认时间")
                    .withHint(hasTimes ? null : "请同时填写下锅时间和出锅时间");
        }
        validateScalars(pot);
        pot.setConfirmedBy(userId);
        pot.setConfirmedAt(LocalDateTime.now());
        potRepository.saveAndFlush(pot);
        correctionRepository.saveAll(log);
        return toViews(factoryId, List.of(pot)).get(0);
    }

    @Transactional
    public PotView voidPot(String factoryId, Long potId, String reasonRaw, Long userId, String userName) {
        String reason = requireReason(reasonRaw);
        ProductionPot pot = requireActivePotForUpdate(factoryId, potId);
        ProductionPotCorrection c = change(pot, "status", pot.getStatus(), ProductionPot.STATUS_VOIDED, reason,
                userId, userName, ProductionPotCorrection.ACTION_VOID);
        pot.setStatus(ProductionPot.STATUS_VOIDED);
        pot.setVoidReason(reason);
        potRepository.saveAndFlush(pot);
        correctionRepository.save(c);
        return toViews(factoryId, List.of(pot)).get(0);
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public BatchPotsView listForBatch(String factoryId, Long productionBatchId) {
        ProductionBatch batch = requireBatch(factoryId, productionBatchId);
        List<ProductionPot> pots = potRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessCodeAscPotNumberAscIdAsc(factoryId, batch.getId());
        List<PotView> views = toViews(factoryId, pots);
        List<Long> activeIds = pots.stream().filter(p -> !p.isVoided()).map(ProductionPot::getId).toList();
        Map<String, BigDecimal> inAlloc = sumInputs(inputRepository.findByFactoryIdAndPotIdIn(factoryId, activeIds));
        Map<String, BigDecimal> outAlloc = sumOutputs(outputRepository.findByFactoryIdAndPotIdIn(factoryId, activeIds));
        Map<String, PotNodeIndex.BatchNode> inNodes = nodeIndex.inputsOf(factoryId, batch.getId());
        Map<String, PotNodeIndex.BatchNode> outNodes = nodeIndex.outputsOf(factoryId, batch.getId());
        Set<String> keys = new HashSet<>(inNodes.keySet());
        keys.addAll(outNodes.keySet());
        Map<String, String> labels = nodeIndex.labels(factoryId, keys);
        int pending = (int) pots.stream().filter(p -> !p.isVoided() && isPending(p)).count();
        return new BatchPotsView(batch.getId(), batch.getBatchNumber(), activeIds.size(), pending, views,
                allocationLines(inNodes, inAlloc, labels), allocationLines(outNodes, outAlloc, labels));
    }

    @Transactional(readOnly = true)
    public List<PotView> listForBatches(String factoryId, Collection<Long> productionBatchIds) {
        if (productionBatchIds == null || productionBatchIds.isEmpty()) {
            return List.of();
        }
        List<ProductionPot> pots = new ArrayList<>(potRepository.findByFactoryIdAndProductionBatchIdIn(factoryId, productionBatchIds));
        pots.sort(Comparator.comparing(ProductionPot::getProductionBatchId)
                .thenComparing(p -> Objects.toString(p.getProcessCode(), ""))
                .thenComparing(ProductionPot::getPotNumber));
        return toViews(factoryId, pots);
    }

    @Transactional(readOnly = true)
    public PotView get(String factoryId, Long potId) {
        ProductionPot pot = potRepository.findByIdAndFactoryId(potId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "锅次不存在或不属于本工厂: " + potId));
        return toViews(factoryId, List.of(pot)).get(0);
    }

    /** 时间不全 / 未确认 / 关联不明 都算待确认。 */
    public static boolean isPending(ProductionPot p) {
        return ProductionPot.BINDING_PENDING.equals(p.getBindingStatus()) || !Boolean.TRUE.equals(p.getTimeConfirmed());
    }

    // ==================== 内部 ====================

    List<PotView> toViews(String factoryId, List<ProductionPot> pots) {
        if (pots.isEmpty()) {
            return List.of();
        }
        List<Long> ids = pots.stream().map(ProductionPot::getId).toList();
        Map<Long, List<ProductionPotInput>> inputs = inputRepository.findByFactoryIdAndPotIdIn(factoryId, ids).stream()
                .collect(Collectors.groupingBy(ProductionPotInput::getPotId));
        Map<Long, List<ProductionPotOutput>> outputs = outputRepository.findByFactoryIdAndPotIdIn(factoryId, ids).stream()
                .collect(Collectors.groupingBy(ProductionPotOutput::getPotId));
        Map<Long, List<ProductionPotCorrection>> corrections = correctionRepository
                .findByFactoryIdAndPotIdInOrderByCorrectedAtAscIdAsc(factoryId, ids).stream()
                .collect(Collectors.groupingBy(ProductionPotCorrection::getPotId, LinkedHashMap::new, Collectors.toList()));
        Set<String> keys = new HashSet<>();
        inputs.values().forEach(l -> l.forEach(i -> keys.add(PotNodeIndex.key(i.getSourceType(), i.getSourceId()))));
        outputs.values().forEach(l -> l.forEach(o -> keys.add(PotNodeIndex.key(o.getTargetType(), o.getTargetId()))));
        Map<String, String> labels = nodeIndex.labels(factoryId, keys);
        Set<Long> restricted = secretRedactor.restrictedBatches(factoryId,
                pots.stream().map(ProductionPot::getProductionBatchId).collect(Collectors.toSet()));

        List<PotView> out = new ArrayList<>();
        for (ProductionPot p : pots) {
            boolean redact = restricted.contains(p.getProductionBatchId());
            List<LinkView> in = inputs.getOrDefault(p.getId(), List.of()).stream()
                    .map(i -> new LinkView(i.getSourceType(), i.getSourceId(),
                            labels.get(PotNodeIndex.key(i.getSourceType(), i.getSourceId())), i.getQuantity(), i.getUnit()))
                    .toList();
            List<LinkView> o = outputs.getOrDefault(p.getId(), List.of()).stream()
                    .map(x -> new LinkView(x.getTargetType(), x.getTargetId(),
                            labels.get(PotNodeIndex.key(x.getTargetType(), x.getTargetId())), x.getQuantity(), x.getUnit()))
                    .toList();
            List<CorrectionView> cs = corrections.getOrDefault(p.getId(), List.of()).stream()
                    .map(c -> {
                        boolean hide = redact && SECRET_FIELDS.contains(c.getFieldName());
                        return new CorrectionView(c.getAction(), c.getFieldName(), hide ? null : c.getOldValue(),
                                hide ? null : c.getNewValue(), c.getReason(), c.getCorrectedBy(),
                                c.getCorrectedByName(), c.getCorrectedAt());
                    })
                    .toList();
            out.add(new PotView(p.getId(), p.getProductionBatchId(), p.getProductionPlanId(), p.getProcessSheetRowId(),
                    redact ? null : p.getProcessCode(), redact ? null : p.getProcessName(), p.getPotNumber(),
                    p.getEquipmentLabel(),
                    p.getStartedAt(), p.getEndedAt(), p.getTimeSource(), Boolean.TRUE.equals(p.getTimeConfirmed()),
                    durationMinutes(p), p.getBindingStatus(), p.getOperatorId(), p.getOperatorName(),
                    p.getOutputQuantity(), p.getOutputUnit(), redact ? Map.of() : p.getProcessParams(),
                    p.getSource(), p.getStatus(), p.getVoidReason(), redact ? null : p.getRemark(), in, o, cs, redact));
        }
        return out;
    }

    static Long durationMinutes(ProductionPot p) {
        if (!Boolean.TRUE.equals(p.getTimeConfirmed()) || p.getStartedAt() == null || p.getEndedAt() == null) {
            return null;
        }
        return Duration.between(p.getStartedAt(), p.getEndedAt()).toMinutes();
    }

    /**
     * 时间规则: 人工 / 设备时间齐全即视为确认 (那是录入人自己的记录);
     * 摄像候选恒未确认 (只是候选); 未知不许带时间。
     */
    private static void applyTimeRules(ProductionPot pot) {
        String ts = pot.getTimeSource();
        if (ProductionPot.TIME_UNKNOWN.equals(ts) && (pot.getStartedAt() != null || pot.getEndedAt() != null)) {
            throw invalid("时间来源为「未知」时不能填写时间").withHint("有时间就选人工录入或设备; 只是摄像候选请选摄像候选");
        }
        boolean complete = pot.getStartedAt() != null && pot.getEndedAt() != null;
        boolean trusted = ProductionPot.TIME_MANUAL.equals(ts) || ProductionPot.TIME_DEVICE.equals(ts);
        pot.setTimeConfirmed(complete && trusted);
    }

    private static void validateScalars(ProductionPot pot) {
        if (pot.getPotNumber() == null || pot.getPotNumber() <= 0) {
            throw invalid("锅号必须是正整数").withHintTarget("potNumber");
        }
        if (pot.getStartedAt() != null && pot.getEndedAt() != null && pot.getEndedAt().isBefore(pot.getStartedAt())) {
            throw invalid("出锅时间不能早于下锅时间").withHintTarget("endedAt");
        }
        if (pot.getOutputQuantity() != null && pot.getOutputQuantity().signum() < 0) {
            throw invalid("出锅量不能为负数").withHintTarget("outputQuantity");
        }
    }

    private void assertNumberFree(String factoryId, ProductionPot pot, Long selfId) {
        boolean taken = potRepository
                .findByFactoryIdAndProductionBatchIdOrderByProcessCodeAscPotNumberAscIdAsc(factoryId, pot.getProductionBatchId())
                .stream()
                .filter(p -> !p.isVoided())
                .filter(p -> selfId == null || !selfId.equals(p.getId()))
                .anyMatch(p -> Objects.equals(p.getProcessCode(), pot.getProcessCode())
                        && Objects.equals(p.getPotNumber(), pot.getPotNumber()));
        if (taken) {
            throw new BusinessException(409, "本批次本工序已有第 " + pot.getPotNumber() + " 锅")
                    .withCode(CODE_DUPLICATE_NUMBER)
                    .withHint("请换一个锅号; 录错的锅请先作废再重录")
                    .withHintTarget("potNumber");
        }
    }

    /**
     * 分摊只能落在批次真实节点上; 同节点 (本锅 + 其它未作废的锅) 之和不超过批次量;
     * 单位与批次边一致。excludePotId = 正在更正的那一锅 (它的旧分摊不计)。
     */
    private void validateLinks(String factoryId, Long productionBatchId, Long excludePotId,
                               List<PotLink> links, boolean inputSide) {
        if (links == null || links.isEmpty()) {
            return;
        }
        Map<String, PotNodeIndex.BatchNode> nodes = inputSide
                ? nodeIndex.inputsOf(factoryId, productionBatchId)
                : nodeIndex.outputsOf(factoryId, productionBatchId);
        String side = inputSide ? "投入" : "产出";
        Set<String> seen = new HashSet<>();
        for (PotLink l : links) {
            if (l == null || l.nodeType() == null || l.nodeId() == null || l.nodeId().isBlank()) {
                throw invalid("锅次" + side + "缺少批次");
            }
            if (l.quantity() == null || l.quantity().signum() <= 0) {
                throw invalid("锅次" + side + "数量必须大于 0").withHintTarget(inputSide ? "inputs" : "outputs");
            }
            String k = PotNodeIndex.key(l.nodeType(), l.nodeId());
            if (!seen.add(k)) {
                throw invalid("同一锅里同一批次只能填一行" + side);
            }
            PotNodeIndex.BatchNode node = nodes.get(k);
            if (node == null) {
                throw new BusinessException(400, "该批次不是这个生产批次的" + side + ": " + l.nodeId())
                        .withCode(CODE_NOT_A_BATCH_NODE)
                        .withHint("锅次只能分摊生产批次已经记录的" + side + "批次")
                        .withHintTarget(inputSide ? "inputs" : "outputs");
            }
            if (!nodeIndex.sameUnit(factoryId, l.unit(), node.unit())) {
                throw invalid("锅次" + side + "单位「" + UnitDisplayNames.display(l.unit()) + "」与批次单位「"
                        + UnitDisplayNames.display(node.unit()) + "」不一致");
            }
        }
        // 上限: 同节点所有未作废锅之和
        List<Long> otherPots = potRepository.findByFactoryIdAndProductionBatchIdOrderByProcessCodeAscPotNumberAscIdAsc(
                        factoryId, productionBatchId).stream()
                .filter(p -> !p.isVoided())
                .map(ProductionPot::getId)
                .filter(id -> excludePotId == null || !excludePotId.equals(id))
                .toList();
        Map<String, BigDecimal> already = inputSide
                ? sumInputs(inputRepository.findByFactoryIdAndPotIdIn(factoryId, otherPots))
                : sumOutputs(outputRepository.findByFactoryIdAndPotIdIn(factoryId, otherPots));
        for (PotLink l : links) {
            String k = PotNodeIndex.key(l.nodeType(), l.nodeId());
            PotNodeIndex.BatchNode node = nodes.get(k);
            if (node.quantity() == null) {
                continue;   // 批次量未知: 不能校验上限 (召回也不会按锅收窄)
            }
            BigDecimal total = already.getOrDefault(k, BigDecimal.ZERO).add(l.quantity());
            if (total.compareTo(node.quantity()) > 0) {
                throw new BusinessException(400, "各锅" + side + "之和 " + plain(total) + " 超过批次" + side + " "
                        + plain(node.quantity()) + (node.unit() == null ? "" : UnitDisplayNames.display(node.unit())))
                        .withCode(CODE_OVER_ALLOCATED)
                        .withHint("请核对每一锅的量; 批次级数量以报工为准")
                        .withHintTarget(inputSide ? "inputs" : "outputs");
            }
        }
    }

    private void saveLinks(String factoryId, Long potId, List<PotLink> inputs, List<PotLink> outputs, Long userId) {
        for (PotLink l : inputs) {
            inputRepository.save(ProductionPotInput.builder().factoryId(factoryId).potId(potId)
                    .sourceType(l.nodeType()).sourceId(l.nodeId()).quantity(l.quantity()).unit(blankToNull(l.unit()))
                    .createdBy(userId).build());
        }
        for (PotLink l : outputs) {
            outputRepository.save(ProductionPotOutput.builder().factoryId(factoryId).potId(potId)
                    .targetType(l.nodeType()).targetId(l.nodeId()).quantity(l.quantity()).unit(blankToNull(l.unit()))
                    .createdBy(userId).build());
        }
        inputRepository.flush();
        outputRepository.flush();
    }

    static Map<String, BigDecimal> sumInputs(List<ProductionPotInput> rows) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (ProductionPotInput r : rows) {
            m.merge(PotNodeIndex.key(r.getSourceType(), r.getSourceId()), r.getQuantity(), BigDecimal::add);
        }
        return m;
    }

    static Map<String, BigDecimal> sumOutputs(List<ProductionPotOutput> rows) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (ProductionPotOutput r : rows) {
            m.merge(PotNodeIndex.key(r.getTargetType(), r.getTargetId()), r.getQuantity(), BigDecimal::add);
        }
        return m;
    }

    private static List<AllocationLine> allocationLines(Map<String, PotNodeIndex.BatchNode> nodes,
                                                        Map<String, BigDecimal> allocated, Map<String, String> labels) {
        List<AllocationLine> out = new ArrayList<>();
        for (PotNodeIndex.BatchNode n : nodes.values()) {
            BigDecimal a = allocated.getOrDefault(n.key(), BigDecimal.ZERO);
            boolean complete = n.quantity() != null && a.compareTo(n.quantity()) >= 0;
            out.add(new AllocationLine(n.type(), n.id(), labels.get(n.key()), n.quantity(), a, n.unit(), complete));
        }
        return out;
    }

    private ProductionBatch requireBatchForUpdate(String factoryId, Long productionBatchId) {
        return productionBatchRepository.findByIdAndFactoryIdForUpdate(productionBatchId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产批次不存在或不属于本工厂: " + productionBatchId)
                        .withHintTarget("productionBatchId"));
    }

    /** 报工行 / 生产计划必须是这个批次的 (不许把锅挂到别的计划、别的报工行上)。 */
    private void assertRowAndPlanBelongToBatch(String factoryId, ProductionBatch batch, Long rowId, String planId) {
        if (planId != null && !planId.isBlank() && batch.getProductionPlanId() != null
                && !planId.equals(batch.getProductionPlanId())) {
            throw invalid("生产计划与生产批次不一致").withHintTarget("productionPlanId");
        }
        if (rowId == null) {
            return;
        }
        ProcessSheetRow row = processSheetRowRepository.findById(rowId)
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .orElseThrow(() -> invalid("报工行不存在或不属于本工厂: " + rowId).withHintTarget("processSheetRowId"));
        boolean planMismatch = batch.getProductionPlanId() != null && !batch.getProductionPlanId().equals(row.getPlanId());
        // 报工行在物化时回写 batch_id; 已回写就必须是这个批次
        boolean batchMismatch = row.getBatchId() != null && !row.getBatchId().equals(batch.getId());
        if (planMismatch || batchMismatch) {
            throw invalid("报工行不属于这个生产批次").withHintTarget("processSheetRowId");
        }
    }

    private ProductionBatch requireBatch(String factoryId, Long productionBatchId) {
        return productionBatchRepository.findByIdAndFactoryId(productionBatchId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产批次不存在或不属于本工厂: " + productionBatchId)
                        .withHintTarget("productionBatchId"));
    }

    private ProductionPot requireActivePotForUpdate(String factoryId, Long potId) {
        ProductionPot pot = potRepository.findByIdAndFactoryIdForUpdate(potId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "锅次不存在或不属于本工厂: " + potId));
        if (pot.isVoided()) {
            throw new BusinessException(409, "这一锅已作废, 不能再修改").withCode(CODE_VOIDED);
        }
        return pot;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "请填写原因").withCode(CODE_REASON_REQUIRED).withHintTarget("reason");
        }
        String r = reason.trim();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }

    private static ProductionPotCorrection change(ProductionPot pot, String field, Object oldV, Object newV,
                                                  String reason, Long userId, String userName) {
        return change(pot, field, oldV, newV, reason, userId, userName, ProductionPotCorrection.ACTION_CORRECT);
    }

    private static ProductionPotCorrection change(ProductionPot pot, String field, Object oldV, Object newV,
                                                  String reason, Long userId, String userName, String action) {
        return ProductionPotCorrection.builder()
                .factoryId(pot.getFactoryId())
                .potId(pot.getId())
                .action(action)
                .fieldName(field)
                .oldValue(oldV == null ? null : String.valueOf(oldV))
                .newValue(newV == null ? null : String.valueOf(newV))
                .reason(reason)
                .correctedBy(userId)
                .correctedByName(userName)
                .correctedAt(LocalDateTime.now())
                .build();
    }

    private static String describeInputs(List<ProductionPotInput> rows) {
        return rows.stream().map(r -> r.getSourceType() + ":" + r.getSourceId() + "=" + plain(r.getQuantity())
                        + unitText(r.getUnit())).sorted().collect(Collectors.joining(", "));
    }

    private static String describeOutputs(List<ProductionPotOutput> rows) {
        return rows.stream().map(r -> r.getTargetType() + ":" + r.getTargetId() + "=" + plain(r.getQuantity())
                        + unitText(r.getUnit())).sorted().collect(Collectors.joining(", "));
    }

    private static String describeLinks(List<PotLink> links) {
        return links.stream().filter(Objects::nonNull)
                .map(l -> l.nodeType() + ":" + l.nodeId() + "=" + plain(l.quantity()) + unitText(blankToNull(l.unit())))
                .sorted().collect(Collectors.joining(", "));
    }

    /** 单位是否真的改了: 两边都空 = 没改; 一边空 = 改了; 都有 = 问单位契约。 */
    private boolean unitChanged(String factoryId, String before, String after) {
        if (before == null || after == null) {
            return !(before == null && after == null);
        }
        return !nodeIndex.sameUnit(factoryId, before, after);
    }

    private static String unitText(String unit) {
        return unit == null ? "" : UnitDisplayNames.display(unit);
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String normalizeTimeSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProductionPot.TIME_MANUAL;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case ProductionPot.TIME_MANUAL, ProductionPot.TIME_DEVICE, ProductionPot.TIME_CAMERA_CANDIDATE,
                 ProductionPot.TIME_UNKNOWN -> v;
            default -> throw invalid("不认识的时间来源: " + raw).withHintTarget("timeSource");
        };
    }

    private static String normalizeBinding(String raw) {
        if (raw == null || raw.isBlank()) {
            return ProductionPot.BINDING_CONFIRMED;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ProductionPot.BINDING_CONFIRMED.equals(v) && !ProductionPot.BINDING_PENDING.equals(v)) {
            throw invalid("不认识的锅批关联状态: " + raw).withHintTarget("bindingStatus");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BusinessException invalid(String message) {
        return new BusinessException(400, message).withCode(CODE_INVALID);
    }
}
