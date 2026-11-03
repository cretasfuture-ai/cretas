package com.cretas.aims.service.lineage;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.foodsafety.SupplierQualification;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.foodsafety.SupplierQualificationRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 验厂证据包 —— 按一个成品批（或生产批）一次拿齐「原料从哪来、合格证明、投了什么、质检放行 / CCP、发给了谁」
 * （食品 DAG 缺口 02 第 3 件）。
 *
 * <p>链路取自 {@link LineageGraphAssembler#assemble}（内部视图、整条链），所以证据包里的批次集合与溯源页
 * <b>同一份</b>，并直接带上它的 {@code completeness}。⛔ 不另写一份走链逻辑（形态 D）。
 *
 * <p>内部用：供应商名、客户名、证书号都给。不受公开溯源「只给原料」的限制
 * （那条裁定只管消费者扫码页）。
 *
 * <h2>{@link Pack#missingEvidence} —— 「哪一段证据缺了」</h2>
 * <p>审核员最先问的是缺什么，不是有什么。每一项缺口单独列出；全空才是 {@code READY}。
 * 判据刻意保守：只报「按数据能证明缺了」的，⛔ 不因为一个可选字段为空就报缺。
 * <ul>
 *   <li>{@code TRACE_INCOMPLETE}：血缘本身不完整（断链 / 截断 / 节点查不到）。</li>
 *   <li>{@code RAW_CERTIFICATE_MISSING}：原料批既没有随货合格证，供应商在收货日也没有有效资质。</li>
 *   <li>{@code CCP_RECORDS_MISSING}：本厂配置了在用的 CCP，链上生产批却一条 CCP 监控记录都没有。</li>
 *   <li>{@code CCP_DEVIATION_OPEN}：链上有未处理的 CCP 偏离。</li>
 *   <li>{@code QC_RELEASE_MISSING}：成品批没有放行记录；{@code QC_RELEASE_REJECTED}：最近一次是拒绝。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TraceEvidencePackService {

    public static final String READY = "READY";
    public static final String GAPS = "GAPS";

    public static final String TRACE_INCOMPLETE = "TRACE_INCOMPLETE";
    public static final String RAW_CERTIFICATE_MISSING = "RAW_CERTIFICATE_MISSING";
    public static final String CCP_RECORDS_MISSING = "CCP_RECORDS_MISSING";
    public static final String CCP_DEVIATION_OPEN = "CCP_DEVIATION_OPEN";
    public static final String QC_RELEASE_MISSING = "QC_RELEASE_MISSING";
    public static final String QC_RELEASE_REJECTED = "QC_RELEASE_REJECTED";

    /** findForBatchView 的 IN 不能是空集合（PG 上 {@code IN ()} 是语法错），用一个不可能命中的占位。 */
    private static final String NO_MATCH = "#NO-MATCH#";

    private final LineageGraphAssembler graphAssembler;
    private final BatchLineageEdgeRepository edgeRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierQualificationRepository qualificationRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SalesDeliveryRecordRepository deliveryRecordRepository;
    private final QcReleaseRecordRepository qcReleaseRecordRepository;
    private final HaccpMonitoringRecordRepository ccpRecordRepository;
    private final HaccpCheckpointRepository checkpointRepository;

    // ── 输出模型 ──────────────────────────────────────────────────────────────

    public record Pack(String factoryId, String rootType, String rootId, String rootBatchNumber, String productName,
                       LocalDateTime generatedAt, String status, List<MissingEvidence> missingEvidence,
                       TraceabilityDTO.LineageCompleteness completeness,
                       List<RawMaterial> rawMaterials, List<Feeding> feedings, List<ProductionStep> productionBatches,
                       List<QcRelease> qcReleases, List<CcpRecord> ccpRecords, List<Shipment> shipments) {}

    public record MissingEvidence(String kind, String batchNumber, String detail) {}

    public record RawMaterial(String materialBatchId, String batchNumber, String materialName, String supplierName,
                              String supplierBatchNumber, String originPlace, LocalDate receiptDate,
                              LocalDate productionDate, LocalDate expireDate, BigDecimal receiptQuantity,
                              String qualityCertificate, List<Qualification> supplierQualifications,
                              boolean certificateEvidence) {}

    public record Qualification(String qualificationType, String certificateNumber, String issuingAuthority,
                                LocalDate issueDate, LocalDate expiryDate, String status, boolean validAtReceipt) {}

    public record Feeding(String edgeType, String fromType, String fromBatchNumber, String toType,
                          String toBatchNumber, BigDecimal quantity, String unit, LocalDateTime eventTime) {}

    public record ProductionStep(Long productionBatchId, String batchNumber, String productName,
                                 String productionPlanId) {}

    public record QcRelease(String targetType, String batchNumber, String decision, BigDecimal quantity, String unit,
                            String conclusion, String releasedByName, LocalDateTime releasedAt) {}

    public record CcpRecord(String checkpointCode, String checkpointName, String batchNumber, BigDecimal measuredValue,
                            BigDecimal criticalLimitMin, BigDecimal criticalLimitMax, String unit,
                            LocalDateTime monitoringTime, boolean deviation, String deviationAction,
                            LocalDateTime deviationHandledAt, String operatorName) {}

    public record Shipment(String deliveryNumber, String customerName, LocalDate deliveryDate, String status,
                           String fromBatchNumber, BigDecimal quantity, String unit) {}

    // ── 入口 ────────────────────────────────────────────────────────────────

    /** 按批次号取证据包：先找成品批，再找生产批；都没有 ⇒ 404（⛔ 不返回一个空包）。 */
    @Transactional(readOnly = true)
    public Pack buildByBatchNumber(String factoryId, String batchNumber) {
        if (batchNumber == null || batchNumber.isBlank()) {
            throw new BusinessException(400, "请输入批次号").withHintTarget("batchNumber");
        }
        String no = batchNumber.trim();
        var finished = finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(factoryId, no);
        if (finished.isPresent()) {
            FinishedGoodsBatch fg = finished.get();
            return build(factoryId, LineageEdgeRecorder.NODE_FINISHED_BATCH, fg.getId(), fg.getBatchNumber(),
                    fg.getProductName());
        }
        var production = productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, no);
        if (production.isPresent()) {
            ProductionBatch pb = production.get();
            return build(factoryId, LineageEdgeRecorder.NODE_PRODUCTION_BATCH, String.valueOf(pb.getId()),
                    pb.getBatchNumber(), pb.getProductName());
        }
        throw new BusinessException(404, "当前工厂没有这个成品批或生产批")
                .withHint("请核对批次号").withHintTarget("batchNumber");
    }

    Pack build(String factoryId, String rootType, String rootId, String rootBatchNumber, String productName) {
        TraceabilityDTO.LineageSection section = graphAssembler.assemble(factoryId, rootType, rootId,
                LineageGraphAssembler.MAX_DEPTH_CAP, true, false);

        // 链上全部节点（含起点），按类型分桶
        Map<String, Set<String>> idsByType = new LinkedHashMap<>();
        idsByType.computeIfAbsent(rootType, t -> new LinkedHashSet<>()).add(rootId);
        collect(section.getAncestors(), idsByType);
        collect(section.getDescendants(), idsByType);
        Set<String> allIds = idsByType.values().stream().flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, MaterialBatch> materials = byId(materialBatchRepository.findAllById(
                idsByType.getOrDefault(LineageEdgeRecorder.NODE_MATERIAL_BATCH, Set.of())), MaterialBatch::getId);
        Map<String, ProductionBatch> productions = byId(productionBatchRepository.findAllById(
                        longIds(idsByType.getOrDefault(LineageEdgeRecorder.NODE_PRODUCTION_BATCH, Set.of()))),
                b -> String.valueOf(b.getId()));
        Map<String, FinishedGoodsBatch> finished = byId(finishedGoodsBatchRepository.findAllById(
                idsByType.getOrDefault(LineageEdgeRecorder.NODE_FINISHED_BATCH, Set.of())), FinishedGoodsBatch::getId);
        Map<String, SalesDeliveryRecord> deliveries = byId(deliveryRecordRepository.findAllById(
                idsByType.getOrDefault(LineageEdgeRecorder.NODE_SHIPMENT_RECORD, Set.of())), SalesDeliveryRecord::getId);
        Function<String, String> label = key -> labelOf(key, materials, productions, finished, deliveries);

        List<BatchLineageEdge> edges = allIds.isEmpty() ? List.of()
                : Objects.requireNonNullElse(edgeRepository.findByFactoryIdAndTargetIdIn(factoryId, allIds),
                        List.<BatchLineageEdge>of()).stream()
                .filter(e -> allIds.contains(e.getSourceId()))
                .filter(e -> !(Objects.equals(e.getSourceType(), e.getTargetType())
                        && Objects.equals(e.getSourceId(), e.getTargetId())))
                .toList();
        Set<String> producedByProduction = edges.stream()
                .filter(e -> LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(e.getSourceType())
                        && LineageEdgeRecorder.NODE_MATERIAL_BATCH.equals(e.getTargetType()))
                .map(BatchLineageEdge::getTargetId).collect(Collectors.toSet());

        List<MissingEvidence> missing = new ArrayList<>();
        TraceabilityDTO.LineageCompleteness completeness = section.getCompleteness();
        if (completeness != null && !TraceabilityDTO.LineageCompleteness.COMPLETE.equals(completeness.getStatus())) {
            missing.add(new MissingEvidence(TRACE_INCOMPLETE, rootBatchNumber, completeness.getSummary()));
        }

        List<RawMaterial> raws = rawMaterials(factoryId, materials, producedByProduction, missing);
        List<Feeding> feedings = edges.stream()
                .filter(e -> !LineageEdgeRecorder.EDGE_FINISHED_TO_SHIPMENT.equals(e.getEdgeType()))
                .sorted(Comparator.comparing(BatchLineageEdge::getEventTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(e -> new Feeding(e.getEdgeType(), e.getSourceType(), label.apply(e.getSourceType() + "#" + e.getSourceId()),
                        e.getTargetType(), label.apply(e.getTargetType() + "#" + e.getTargetId()),
                        e.getQuantityUsed(), e.getUnit(), e.getEventTime()))
                .toList();
        List<ProductionStep> steps = productions.values().stream()
                .sorted(Comparator.comparing(ProductionBatch::getId))
                .map(pb -> new ProductionStep(pb.getId(), pb.getBatchNumber(), pb.getProductName(), pb.getProductionPlanId()))
                .toList();

        List<QcRelease> releases = qcReleases(factoryId, finished, materials, producedByProduction, missing);
        List<CcpRecord> ccp = ccpRecords(factoryId, productions, finished, missing);
        List<Shipment> shipments = edges.stream()
                .filter(e -> LineageEdgeRecorder.EDGE_FINISHED_TO_SHIPMENT.equals(e.getEdgeType()))
                .map(e -> {
                    SalesDeliveryRecord dr = deliveries.get(e.getTargetId());
                    return new Shipment(dr == null ? e.getTargetId() : dr.getDeliveryNumber(),
                            dr == null ? null : dr.getCustomerName(),
                            dr == null ? null : dr.getDeliveryDate(),
                            dr == null || dr.getStatus() == null ? null : dr.getStatus().name(),
                            label.apply(e.getSourceType() + "#" + e.getSourceId()), e.getQuantityUsed(), e.getUnit());
                })
                .toList();

        return new Pack(factoryId, rootType, rootId, rootBatchNumber, productName, LocalDateTime.now(),
                missing.isEmpty() ? READY : GAPS, missing, completeness,
                raws, feedings, steps, releases, ccp, shipments);
    }

    // ── 各段 ────────────────────────────────────────────────────────────────

    private List<RawMaterial> rawMaterials(String factoryId, Map<String, MaterialBatch> materials,
                                           Set<String> producedByProduction, List<MissingEvidence> missing) {
        List<MaterialBatch> raws = materials.values().stream()
                .filter(LineageGraphAssembler::isRawMaterialForPublic)
                .filter(mb -> !producedByProduction.contains(mb.getId()))
                .sorted(Comparator.comparing(MaterialBatch::getBatchNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Set<String> supplierIds = raws.stream().map(MaterialBatch::getSupplierId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Supplier> suppliers = supplierIds.isEmpty() ? Map.of()
                : byId(supplierRepository.findAllById(supplierIds), Supplier::getId);
        Map<String, List<SupplierQualification>> qualifications = new LinkedHashMap<>();
        for (String supplierId : supplierIds) {
            qualifications.put(supplierId, Objects.requireNonNullElse(
                    qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(factoryId, supplierId,
                            List.of("VALID", "EXPIRING", "EXPIRED", "REVOKED")),
                    List.of()));
        }

        List<RawMaterial> out = new ArrayList<>();
        for (MaterialBatch mb : raws) {
            List<Qualification> quals = qualifications.getOrDefault(mb.getSupplierId(), List.of()).stream()
                    .map(q -> new Qualification(q.getQualificationType(), q.getCertificateNumber(),
                            q.getIssuingAuthority(), q.getIssueDate(), q.getExpiryDate(), q.getStatus(),
                            validAt(q, mb.getReceiptDate())))
                    .toList();
            boolean hasCertificate = mb.getQualityCertificate() != null && !mb.getQualityCertificate().isBlank();
            boolean evidence = hasCertificate || quals.stream().anyMatch(Qualification::validAtReceipt);
            if (!evidence) {
                missing.add(new MissingEvidence(RAW_CERTIFICATE_MISSING, mb.getBatchNumber(),
                        "原料批没有随货合格证, 供应商在收货日也没有有效资质"));
            }
            Supplier s = suppliers.get(mb.getSupplierId());
            out.add(new RawMaterial(mb.getId(), mb.getBatchNumber(),
                    mb.getMaterialType() == null ? null : mb.getMaterialType().getName(),
                    s == null ? null : s.getName(), mb.getSupplierBatchNumber(), mb.getOriginPlace(),
                    mb.getReceiptDate(), mb.getProductionDate(), mb.getExpireDate(), mb.getReceiptQuantity(),
                    mb.getQualityCertificate(), quals, evidence));
        }
        return out;
    }

    /** 资质在收货日有效：已签发、未过期、状态不是吊销 (EXPIRED 只说明「现在」过期, 收货那天是否有效看日期)。收货日未知 ⇒ 不能算有效（⛔ 不拿今天顶替）。 */
    static boolean validAt(SupplierQualification q, LocalDate receiptDate) {
        if (receiptDate == null || q.getExpiryDate() == null) {
            return false;
        }
        if ("REVOKED".equals(q.getStatus())) {
            return false;
        }
        boolean issued = q.getIssueDate() == null || !q.getIssueDate().isAfter(receiptDate);
        return issued && !q.getExpiryDate().isBefore(receiptDate);
    }

    private List<QcRelease> qcReleases(String factoryId, Map<String, FinishedGoodsBatch> finished,
                                       Map<String, MaterialBatch> materials, Set<String> producedByProduction,
                                       List<MissingEvidence> missing) {
        List<QcRelease> out = new ArrayList<>();
        for (FinishedGoodsBatch fg : finished.values()) {
            List<QcReleaseRecord> records = Objects.requireNonNullElse(qcReleaseRecordRepository.findByTarget(
                    factoryId, QcReleaseRecord.TargetType.FINISHED_GOODS, fg.getId()), List.of());
            if (records.isEmpty()) {
                missing.add(new MissingEvidence(QC_RELEASE_MISSING, fg.getBatchNumber(), "成品批没有质检放行记录"));
            } else if (QcReleaseRecord.Decision.REJECTED.equals(records.get(0).getDecision())) {
                missing.add(new MissingEvidence(QC_RELEASE_REJECTED, fg.getBatchNumber(), "成品批最近一次质检结论是拒绝"));
            }
            records.forEach(r -> out.add(toRelease(r)));
        }
        // 在制半成品的放行是链上的中间证据：有就列, 没有不算缺 (不是每道都要求检)
        for (MaterialBatch mb : materials.values()) {
            if (!LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(mb.getSourceDocType())
                    && !producedByProduction.contains(mb.getId())) {
                continue;
            }
            Objects.requireNonNullElse(qcReleaseRecordRepository.findByTarget(
                            factoryId, QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, mb.getId()), List.<QcReleaseRecord>of())
                    .forEach(r -> out.add(toRelease(r)));
        }
        return out;
    }

    private static QcRelease toRelease(QcReleaseRecord r) {
        return new QcRelease(r.getTargetType(), r.getBatchNumber(), r.getDecision(), r.getQuantity(), r.getUnit(),
                r.getConclusion(), r.getReleasedByName(), r.getReleasedAt());
    }

    private List<CcpRecord> ccpRecords(String factoryId, Map<String, ProductionBatch> productions,
                                       Map<String, FinishedGoodsBatch> finished, List<MissingEvidence> missing) {
        Set<String> batchNumbers = new LinkedHashSet<>();
        Set<String> planIds = new LinkedHashSet<>();
        productions.values().forEach(pb -> {
            batchNumbers.add(pb.getBatchNumber());
            planIds.add(pb.getProductionPlanId());
        });
        finished.values().forEach(fg -> {
            batchNumbers.add(fg.getBatchNumber());
            planIds.add(fg.getProductionPlanId());
        });
        batchNumbers.remove(null);
        planIds.remove(null);
        List<HaccpMonitoringRecord> records = productions.isEmpty() && finished.isEmpty() ? List.of()
                : Objects.requireNonNullElse(ccpRecordRepository.findForBatchView(factoryId,
                        batchNumbers.isEmpty() ? Set.of(NO_MATCH) : batchNumbers,
                        planIds.isEmpty() ? Set.of(NO_MATCH) : planIds), List.of());
        Set<Long> checkpointIds = records.stream().map(HaccpMonitoringRecord::getCheckpointId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, HaccpCheckpoint> checkpoints = checkpointIds.isEmpty() ? Map.of()
                : byId(checkpointRepository.findAllById(checkpointIds), HaccpCheckpoint::getId);

        if (records.isEmpty() && !productions.isEmpty()) {
            List<HaccpCheckpoint> active = Objects.requireNonNullElse(
                    checkpointRepository.findByFactoryIdAndActiveTrue(factoryId), List.of());
            if (!active.isEmpty()) {
                missing.add(new MissingEvidence(CCP_RECORDS_MISSING, null,
                        "本厂有 " + active.size() + " 个在用 CCP, 链上 " + productions.size()
                                + " 个生产批没有任何 CCP 监控记录"));
            }
        }
        records.stream()
                .filter(r -> r.isDeviation() && r.getDeviationHandledAt() == null)
                .forEach(r -> missing.add(new MissingEvidence(CCP_DEVIATION_OPEN, r.getBatchNumber(),
                        "CCP 偏离未处理: " + checkpointLabel(checkpoints.get(r.getCheckpointId()))
                                + " 实测 " + r.getMeasuredValue())));

        return records.stream().map(r -> {
            HaccpCheckpoint cp = checkpoints.get(r.getCheckpointId());
            return new CcpRecord(cp == null ? null : cp.getCheckpointCode(), cp == null ? null : cp.getName(),
                    r.getBatchNumber(), r.getMeasuredValue(),
                    r.getCriticalLimitMinSnapshot() != null ? r.getCriticalLimitMinSnapshot()
                            : cp == null ? null : cp.getCriticalLimitMin(),
                    r.getCriticalLimitMaxSnapshot() != null ? r.getCriticalLimitMaxSnapshot()
                            : cp == null ? null : cp.getCriticalLimitMax(),
                    r.getUnitSnapshot() != null ? r.getUnitSnapshot() : cp == null ? null : cp.getUnit(),
                    r.getMonitoringTime(), r.isDeviation(), r.getDeviationAction(), r.getDeviationHandledAt(),
                    r.getOperatorName());
        }).toList();
    }

    private static String checkpointLabel(HaccpCheckpoint cp) {
        return cp == null ? "(CCP 已删除)" : cp.getCheckpointCode() + " " + cp.getName();
    }

    // ── 下载：同一个 Pack 写成 CSV 压缩包（⛔ 不另查一遍数据） ─────────────────

    /** 证据包 ZIP：每段一个 CSV（UTF-8 BOM，Excel 直接打开不乱码）。 */
    public byte[] toZip(Pack p) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            List<List<Object>> summary = new ArrayList<>();
            summary.add(List.of("批次号", nz(p.rootBatchNumber())));
            summary.add(List.of("产品", nz(p.productName())));
            summary.add(List.of("生成时间", p.generatedAt()));
            summary.add(List.of("证据状态", READY.equals(p.status()) ? "齐全" : "有缺口"));
            summary.add(List.of("血缘完整性", p.completeness() == null ? "未取到" : nz(p.completeness().getSummary())));
            p.missingEvidence().forEach(m -> summary.add(List.of("缺口:" + m.kind(), nz(m.batchNumber()) + " " + m.detail())));
            csv(zip, "00-概要.csv", List.of("项目", "内容"), summary);
            csv(zip, "01-原料来源与合格证明.csv",
                    List.of("原料批号", "原料", "供应商", "供应商批号", "产地", "收货日期", "生产日期", "保质期至",
                            "收货数量", "随货合格证", "供应商资质(收货日有效)", "合格证明齐全"),
                    p.rawMaterials().stream().map(r -> List.<Object>of(nz(r.batchNumber()), nz(r.materialName()),
                            nz(r.supplierName()), nz(r.supplierBatchNumber()), nz(r.originPlace()), nz(r.receiptDate()),
                            nz(r.productionDate()), nz(r.expireDate()), nz(r.receiptQuantity()), nz(r.qualityCertificate()),
                            r.supplierQualifications().stream()
                                    .map(q -> q.qualificationType() + ":" + q.certificateNumber() + "(至" + q.expiryDate()
                                            + (q.validAtReceipt() ? ",有效)" : ",无效)"))
                                    .collect(Collectors.joining("; ")),
                            r.certificateEvidence() ? "是" : "否")).toList());
            csv(zip, "02-投料与产出.csv", List.of("关系", "来源类型", "来源批号", "去向类型", "去向批号", "数量", "单位", "时间"),
                    p.feedings().stream().map(f -> List.<Object>of(f.edgeType(), f.fromType(), nz(f.fromBatchNumber()),
                            f.toType(), nz(f.toBatchNumber()), nz(f.quantity()), nz(f.unit()), nz(f.eventTime()))).toList());
            csv(zip, "03-质检放行.csv", List.of("对象", "批号", "结论", "数量", "单位", "说明", "放行人", "放行时间"),
                    p.qcReleases().stream().map(r -> List.<Object>of(r.targetType(), nz(r.batchNumber()), r.decision(),
                            nz(r.quantity()), nz(r.unit()), nz(r.conclusion()), nz(r.releasedByName()),
                            nz(r.releasedAt()))).toList());
            csv(zip, "04-CCP监控.csv", List.of("CCP", "名称", "批号", "实测值", "下限", "上限", "单位", "监控时间",
                            "偏离", "纠偏措施", "偏离处理时间", "记录人"),
                    p.ccpRecords().stream().map(c -> List.<Object>of(nz(c.checkpointCode()), nz(c.checkpointName()),
                            nz(c.batchNumber()), nz(c.measuredValue()), nz(c.criticalLimitMin()), nz(c.criticalLimitMax()),
                            nz(c.unit()), nz(c.monitoringTime()), c.deviation() ? "是" : "否", nz(c.deviationAction()),
                            nz(c.deviationHandledAt()), nz(c.operatorName()))).toList());
            csv(zip, "05-发货.csv", List.of("发货单号", "客户", "发货日期", "状态", "成品批号", "数量", "单位"),
                    p.shipments().stream().map(s -> List.<Object>of(nz(s.deliveryNumber()), nz(s.customerName()),
                            nz(s.deliveryDate()), nz(s.status()), nz(s.fromBatchNumber()), nz(s.quantity()),
                            nz(s.unit()))).toList());
        } catch (IOException e) {
            throw new IllegalStateException("证据包打包失败", e);
        }
        return bytes.toByteArray();
    }

    private static void csv(ZipOutputStream zip, String name, List<String> header, List<List<Object>> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(header.stream().map(TraceEvidencePackService::cell).collect(Collectors.joining(","))).append("\r\n");
        for (List<Object> row : rows) {
            sb.append(row.stream().map(TraceEvidencePackService::cell).collect(Collectors.joining(","))).append("\r\n");
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** CSV 转义；以 = + - @ 开头的文本前加单引号，防 Excel 公式注入。 */
    static String cell(Object v) {
        String s = v == null ? "" : v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(v);
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !(v instanceof BigDecimal)) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static Object nz(Object v) {
        return v == null ? "" : v;
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private static void collect(List<TraceabilityDTO.LineageDepthGroup> groups, Map<String, Set<String>> into) {
        if (groups == null) {
            return;
        }
        for (TraceabilityDTO.LineageDepthGroup g : groups) {
            for (TraceabilityDTO.LineageNode n : g.getNodes() == null ? List.<TraceabilityDTO.LineageNode>of() : g.getNodes()) {
                if (n.getNodeType() != null && n.getNodeId() != null) {
                    into.computeIfAbsent(n.getNodeType(), t -> new LinkedHashSet<>()).add(n.getNodeId());
                }
            }
        }
    }

    private static List<Long> longIds(Set<String> ids) {
        List<Long> out = new ArrayList<>();
        for (String id : ids) {
            try {
                out.add(Long.valueOf(id));
            } catch (NumberFormatException ignored) {
                // 非数字的生产批节点 id 是脏数据, 溯源完整性里已经报成 NODE_UNRESOLVED
            }
        }
        return out;
    }

    private static <K, V> Map<K, V> byId(Iterable<V> items, Function<V, K> key) {
        Map<K, V> out = new LinkedHashMap<>();
        if (items != null) {
            for (V v : items) {
                out.putIfAbsent(key.apply(v), v);
            }
        }
        return out;
    }

    private static String labelOf(String key, Map<String, MaterialBatch> materials,
                                  Map<String, ProductionBatch> productions, Map<String, FinishedGoodsBatch> finished,
                                  Map<String, SalesDeliveryRecord> deliveries) {
        int i = key.indexOf('#');
        String type = key.substring(0, i);
        String id = key.substring(i + 1);
        String batchNumber = switch (type) {
            case LineageEdgeRecorder.NODE_MATERIAL_BATCH -> materials.containsKey(id) ? materials.get(id).getBatchNumber() : null;
            case LineageEdgeRecorder.NODE_PRODUCTION_BATCH -> productions.containsKey(id) ? productions.get(id).getBatchNumber() : null;
            case LineageEdgeRecorder.NODE_FINISHED_BATCH -> finished.containsKey(id) ? finished.get(id).getBatchNumber() : null;
            case LineageEdgeRecorder.NODE_SHIPMENT_RECORD -> deliveries.containsKey(id) ? deliveries.get(id).getDeliveryNumber() : null;
            default -> null;
        };
        // 查不到实体 ⇒ 给原 id, 不编批号 (与溯源页 resolved=false 同一条纪律)
        return batchNumber != null ? batchNumber : id;
    }
}
