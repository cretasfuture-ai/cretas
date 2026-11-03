package com.cretas.aims.service.lineage;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.RawMaterialType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 验厂证据包（食品 DAG 缺口 02 第 3 件）：齐全时 READY，缺哪一段就逐项报哪一段。
 *
 * <p>链：原料 RAW-001 → 生产批 PB-100 → 成品 FG-001 → 发货 DN-001。默认证据全齐（随货合格证 / CCP 记录 /
 * 放行记录都有）⇒ READY —— 它是其余每条「缺一段 ⇒ GAPS」的阴性对照。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("验厂证据包 — 齐全 / 缺 CCP / 缺合格证 / 缺放行 / 断链")
class TraceEvidencePackContractTest {

    private static final String FACTORY = "F006";

    @Mock private LineageGraphAssembler graphAssembler;
    @Mock private BatchLineageEdgeRepository edgeRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private SupplierQualificationRepository qualificationRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private SalesDeliveryRecordRepository deliveryRecordRepository;
    @Mock private QcReleaseRecordRepository qcReleaseRecordRepository;
    @Mock private HaccpMonitoringRecordRepository ccpRecordRepository;
    @Mock private HaccpCheckpointRepository checkpointRepository;

    private TraceEvidencePackService service;
    private MaterialBatch raw;
    private final List<HaccpMonitoringRecord> ccpRecords = new ArrayList<>();
    private final List<QcReleaseRecord> releases = new ArrayList<>();
    private TraceabilityDTO.LineageCompleteness completeness;

    @BeforeEach
    void wire() {
        service = new TraceEvidencePackService(graphAssembler, edgeRepository, materialBatchRepository,
                supplierRepository, qualificationRepository, productionBatchRepository, finishedGoodsBatchRepository,
                deliveryRecordRepository, qcReleaseRecordRepository, ccpRecordRepository, checkpointRepository);

        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId("fg-1");
        fg.setBatchNumber("FG-001");
        fg.setProductName("酱牛肉");
        fg.setProductionPlanId("plan-1");
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, "FG-001")).thenReturn(Optional.of(fg));
        when(finishedGoodsBatchRepository.findAllById(any())).thenReturn(List.of(fg));

        ProductionBatch pb = new ProductionBatch();
        pb.setId(100L);
        pb.setBatchNumber("PB-100");
        pb.setProductionPlanId("plan-1");
        when(productionBatchRepository.findAllById(any())).thenReturn(List.of(pb));

        raw = new MaterialBatch();
        raw.setId("mb-raw");
        raw.setBatchNumber("RAW-001");
        raw.setSupplierId("sup-1");
        raw.setReceiptDate(LocalDate.of(2026, 10, 1));
        raw.setQualityCertificate("CERT-2026-001");
        RawMaterialType type = new RawMaterialType();
        type.setName("牛腱");
        raw.setMaterialType(type);
        when(materialBatchRepository.findAllById(any())).thenReturn(List.of(raw));

        Supplier sup = new Supplier();
        sup.setId("sup-1");
        sup.setName("胜龙牛业");
        when(supplierRepository.findAllById(any())).thenReturn(List.of(sup));
        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(eq(FACTORY), eq("sup-1"), any()))
                .thenReturn(List.of());

        SalesDeliveryRecord dr = new SalesDeliveryRecord();
        dr.setId("dr-1");
        dr.setDeliveryNumber("DN-001");
        dr.setCustomerName("叮咚");
        when(deliveryRecordRepository.findAllById(any())).thenReturn(List.of(dr));

        when(edgeRepository.findByFactoryIdAndTargetIdIn(eq(FACTORY), any())).thenReturn(List.of(
                edge("RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-raw", "PRODUCTION_BATCH", "100"),
                edge("PRODUCTION_TO_FINISHED", "PRODUCTION_BATCH", "100", "FINISHED_BATCH", "fg-1"),
                edge("FINISHED_TO_SHIPMENT", "FINISHED_BATCH", "fg-1", "SHIPMENT_RECORD", "dr-1")));

        HaccpCheckpoint cp = new HaccpCheckpoint();
        cp.setId(7L);
        cp.setCheckpointCode("CCP-1");
        cp.setName("杀菌温度");
        when(checkpointRepository.findAllById(any())).thenReturn(List.of(cp));
        when(checkpointRepository.findByFactoryIdAndActiveTrue(FACTORY)).thenReturn(List.of(cp));
        ccpRecords.add(ccp(false, null));
        when(ccpRecordRepository.findForBatchView(eq(FACTORY), any(), any())).thenAnswer(inv -> ccpRecords);

        releases.add(release(QcReleaseRecord.Decision.RELEASED));
        when(qcReleaseRecordRepository.findByTarget(FACTORY, QcReleaseRecord.TargetType.FINISHED_GOODS, "fg-1"))
                .thenAnswer(inv -> releases);

        completeness = TraceabilityDTO.LineageCompleteness.builder()
                .status(TraceabilityDTO.LineageCompleteness.COMPLETE).summary("完整").gaps(List.of()).build();
        when(graphAssembler.assemble(eq(FACTORY), anyString(), anyString(), anyInt(), eq(true), eq(false)))
                .thenAnswer(inv -> section());
    }

    private TraceEvidencePackService.Pack pack() {
        return service.buildByBatchNumber(FACTORY, "FG-001");
    }

    @Test
    @DisplayName("证据全齐 ⇒ READY, 各段都有内容 (阴性对照)")
    void completeEvidenceIsReady() {
        TraceEvidencePackService.Pack p = pack();

        assertThat(p.status()).isEqualTo(TraceEvidencePackService.READY);
        assertThat(p.missingEvidence()).isEmpty();
        assertThat(p.rawMaterials()).singleElement().satisfies(r -> {
            assertThat(r.batchNumber()).isEqualTo("RAW-001");
            assertThat(r.supplierName()).isEqualTo("胜龙牛业");
            assertThat(r.certificateEvidence()).isTrue();
        });
        assertThat(p.feedings()).extracting(TraceEvidencePackService.Feeding::toBatchNumber)
                .containsExactly("PB-100", "FG-001");
        assertThat(p.ccpRecords()).singleElement().extracting(TraceEvidencePackService.CcpRecord::checkpointCode)
                .isEqualTo("CCP-1");
        assertThat(p.qcReleases()).hasSize(1);
        assertThat(p.shipments()).singleElement().satisfies(s -> {
            assertThat(s.deliveryNumber()).isEqualTo("DN-001");
            assertThat(s.customerName()).isEqualTo("叮咚");
            assertThat(s.fromBatchNumber()).isEqualTo("FG-001");
        });
    }

    @Test
    @DisplayName("🔴 本厂有在用 CCP, 链上生产批没有任何 CCP 记录 ⇒ GAPS: CCP_RECORDS_MISSING")
    void missingCcpRecordsAreReported() {
        ccpRecords.clear();

        TraceEvidencePackService.Pack p = pack();

        assertThat(p.status()).isEqualTo(TraceEvidencePackService.GAPS);
        assertThat(p.missingEvidence()).extracting(TraceEvidencePackService.MissingEvidence::kind)
                .containsExactly(TraceEvidencePackService.CCP_RECORDS_MISSING);
    }

    @Test
    @DisplayName("CCP 偏离未处理 ⇒ CCP_DEVIATION_OPEN; 已处理的偏离不算缺口")
    void openDeviationIsReportedHandledIsNot() {
        ccpRecords.add(ccp(true, null));
        assertThat(pack().missingEvidence()).extracting(TraceEvidencePackService.MissingEvidence::kind)
                .containsExactly(TraceEvidencePackService.CCP_DEVIATION_OPEN);

        ccpRecords.set(1, ccp(true, LocalDateTime.of(2026, 10, 2, 9, 0)));
        assertThat(pack().status()).isEqualTo(TraceEvidencePackService.READY);
    }

    @Test
    @DisplayName("原料没有随货合格证, 供应商资质在收货日已过期 ⇒ RAW_CERTIFICATE_MISSING; 收货日有效 ⇒ 不缺")
    void rawCertificateNeedsCertOrQualificationValidAtReceipt() {
        raw.setQualityCertificate(null);
        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(eq(FACTORY), eq("sup-1"), any()))
                .thenReturn(List.of(qualification(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 30))));
        assertThat(pack().missingEvidence()).extracting(TraceEvidencePackService.MissingEvidence::kind)
                .containsExactly(TraceEvidencePackService.RAW_CERTIFICATE_MISSING);

        when(qualificationRepository.findByFactoryIdAndSupplierIdAndStatusIn(eq(FACTORY), eq("sup-1"), any()))
                .thenReturn(List.of(qualification(LocalDate.of(2025, 1, 1), LocalDate.of(2027, 1, 1))));
        TraceEvidencePackService.Pack p = pack();
        assertThat(p.status()).isEqualTo(TraceEvidencePackService.READY);
        assertThat(p.rawMaterials().get(0).supplierQualifications()).singleElement()
                .extracting(TraceEvidencePackService.Qualification::validAtReceipt).isEqualTo(true);
    }

    @Test
    @DisplayName("成品批没有放行记录 ⇒ QC_RELEASE_MISSING; 最近一次是拒绝 ⇒ QC_RELEASE_REJECTED")
    void qcReleaseMissingOrRejected() {
        releases.clear();
        assertThat(pack().missingEvidence()).extracting(TraceEvidencePackService.MissingEvidence::kind)
                .containsExactly(TraceEvidencePackService.QC_RELEASE_MISSING);

        releases.add(release(QcReleaseRecord.Decision.REJECTED));
        assertThat(pack().missingEvidence()).extracting(TraceEvidencePackService.MissingEvidence::kind)
                .containsExactly(TraceEvidencePackService.QC_RELEASE_REJECTED);
    }

    @Test
    @DisplayName("血缘不完整 ⇒ TRACE_INCOMPLETE, 带上完整性结论原文")
    void incompleteLineageIsAGap() {
        completeness = TraceabilityDTO.LineageCompleteness.builder()
                .status(TraceabilityDTO.LineageCompleteness.INCOMPLETE).summary("不完整：缺上游 1 处").gaps(List.of()).build();

        TraceEvidencePackService.Pack p = pack();

        assertThat(p.missingEvidence()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(TraceEvidencePackService.TRACE_INCOMPLETE);
            assertThat(m.detail()).isEqualTo("不完整：缺上游 1 处");
        });
    }

    @Test
    @DisplayName("ZIP 含六段 CSV, 带 BOM; 以 = 开头的文本被转义 (防 Excel 公式注入)")
    void zipHasSixCsvsAndEscapesFormulas() throws Exception {
        raw.setOriginPlace("=HYPERLINK(\"x\")");

        Map<String, String> files = unzip(service.toZip(pack()));

        assertThat(files.keySet()).containsExactly("00-概要.csv", "01-原料来源与合格证明.csv", "02-投料与产出.csv",
                "03-质检放行.csv", "04-CCP监控.csv", "05-发货.csv");
        assertThat(files.get("05-发货.csv")).startsWith("﻿").contains("DN-001");
        assertThat(files.get("01-原料来源与合格证明.csv")).contains("\"'=HYPERLINK(\"\"x\"\")\"");
    }

    @Test
    @DisplayName("🔴 原料批没有供应商 (客供料) ⇒ 不炸, 无证就报缺 (prod 11-03 F006 酱牛肉 500 的回归)")
    void rawWithoutSupplierDoesNotCrash() {
        raw.setSupplierId(null);
        raw.setQualityCertificate(null);

        TraceEvidencePackService.Pack p = pack();

        assertThat(p.rawMaterials()).singleElement().satisfies(r -> {
            assertThat(r.supplierName()).isNull();
            assertThat(r.supplierQualifications()).isEmpty();
        });
        assertThat(p.missingEvidence()).extracting(TraceEvidencePackService.MissingEvidence::kind)
                .containsExactly(TraceEvidencePackService.RAW_CERTIFICATE_MISSING);
    }

    @Test
    @DisplayName("CCP 记录上没有 checkpointId ⇒ 不炸, 那一行 CCP 编码留空")
    void ccpRecordWithoutCheckpointDoesNotCrash() {
        ccpRecords.clear();
        HaccpMonitoringRecord orphan = ccp(false, null);
        orphan.setCheckpointId(null);
        ccpRecords.add(orphan);

        TraceEvidencePackService.Pack p = pack();

        assertThat(p.ccpRecords()).singleElement()
                .extracting(TraceEvidencePackService.CcpRecord::checkpointCode).isNull();
    }

    @Test
    @DisplayName("批号不存在 ⇒ 404, 不给空包")
    void unknownBatchIs404() {
        assertThatThrownBy(() -> service.buildByBatchNumber(FACTORY, "NOPE"))
                .isInstanceOf(BusinessException.class);
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private TraceabilityDTO.LineageSection section() {
        return TraceabilityDTO.LineageSection.builder()
                .rootType("FINISHED_BATCH").rootId("fg-1")
                .ancestors(List.of(group(1, node("PRODUCTION_BATCH", "100")), group(2, node("MATERIAL_BATCH", "mb-raw"))))
                .descendants(List.of(group(1, node("SHIPMENT_RECORD", "dr-1"))))
                .completeness(completeness)
                .build();
    }

    private static TraceabilityDTO.LineageDepthGroup group(int depth, TraceabilityDTO.LineageNode node) {
        return TraceabilityDTO.LineageDepthGroup.builder().depth(depth).nodes(List.of(node)).build();
    }

    private static TraceabilityDTO.LineageNode node(String type, String id) {
        return TraceabilityDTO.LineageNode.builder().nodeType(type).nodeId(id).resolved(true).build();
    }

    private static BatchLineageEdge edge(String edgeType, String sourceType, String sourceId,
                                         String targetType, String targetId) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(FACTORY);
        e.setEdgeType(edgeType);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setQuantityUsed(BigDecimal.TEN);
        e.setUnit("kg");
        e.setEventTime(LocalDateTime.of(2026, 10, 1, 8, 0).plusHours(edgeType.length()));
        return e;
    }

    private static HaccpMonitoringRecord ccp(boolean deviation, LocalDateTime handledAt) {
        HaccpMonitoringRecord r = new HaccpMonitoringRecord();
        r.setFactoryId(FACTORY);
        r.setCheckpointId(7L);
        r.setBatchNumber("PB-100");
        r.setMeasuredValue(new BigDecimal(deviation ? "60" : "85"));
        r.setMonitoringTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        r.setDeviation(deviation);
        r.setDeviationHandledAt(handledAt);
        return r;
    }

    private static QcReleaseRecord release(String decision) {
        QcReleaseRecord r = new QcReleaseRecord();
        r.setFactoryId(FACTORY);
        r.setTargetType(QcReleaseRecord.TargetType.FINISHED_GOODS);
        r.setTargetId("fg-1");
        r.setBatchNumber("FG-001");
        r.setDecision(decision);
        r.setConclusion("感官、温度合格");
        r.setReleasedAt(LocalDateTime.of(2026, 10, 1, 12, 0));
        return r;
    }

    private static SupplierQualification qualification(LocalDate issue, LocalDate expiry) {
        SupplierQualification q = new SupplierQualification();
        q.setFactoryId(FACTORY);
        q.setSupplierId("sup-1");
        q.setQualificationType("FOOD_PRODUCTION_LICENSE");
        q.setCertificateNumber("SC-001");
        q.setIssueDate(issue);
        q.setExpiryDate(expiry);
        q.setStatus("VALID");
        return q;
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                out.put(e.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
