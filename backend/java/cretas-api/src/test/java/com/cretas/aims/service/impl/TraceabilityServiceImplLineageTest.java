package com.cretas.aims.service.impl;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProcessingStageRecordRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.EncodingRuleService;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 血缘在<b>产品真实入口</b>上的断言（形态 B 判据 1：至少一条断言跑在真入口上）。
 *
 * <p>{@code LineageGraphAssembler} 自己的单测只证明「组装函数写得对」；
 * 这一组证明「溯源页/公开页/召回端点确实调了它，而且用对了开关」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TraceabilityServiceImpl — 血缘接线 / 公开脱敏 / 召回不返回空壳")
class TraceabilityServiceImplLineageTest {

    private static final String FACTORY = "F006";
    private static final String BATCH_NUMBER = "PB-20260914-001";

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private QualityInspectionRepository qualityInspectionRepository;
    @Mock private ShipmentRecordRepository shipmentRecordRepository;
    @Mock private ProcessingStageRecordRepository processingStageRecordRepository;
    @Mock private UserRepository userRepository;
    @Mock private FactoryRepository factoryRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private ProcessSheetRowRepository processSheetRowRepository;
    @Mock private EncodingRuleService encodingRuleService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private LineageGraphAssembler lineageGraphAssembler;
    @Mock private com.cretas.aims.service.unit.UnitContractService unitContractService;

    @InjectMocks private TraceabilityServiceImpl service;

    @BeforeEach
    void wireUnitContract() {
        org.mockito.Mockito.lenient().when(unitContractService.storageUnit(anyString(), anyString())).thenAnswer(invocation ->
                com.cretas.aims.service.unit.impl.UnitContractServiceImpl
                        .crossLanguageCode(invocation.getArgument(1)));
    }

    private ProductionBatch stubBatch() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(8967L);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber(BATCH_NUMBER);
        batch.setProductName("酱牛肉");
        when(productionBatchRepository.findByFactoryIdAndBatchNumber(FACTORY, BATCH_NUMBER))
                .thenReturn(Optional.of(batch));
        when(productionBatchRepository.findByBatchNumber(BATCH_NUMBER)).thenReturn(Optional.of(batch));
        when(materialConsumptionRepository.findByProductionBatchId(8967L)).thenReturn(List.of());
        when(qualityInspectionRepository.findByProductionBatchId(8967L)).thenReturn(List.of());
        when(processingStageRecordRepository.findByProductionBatchIdOrderByStageOrderAsc(8967L))
                .thenReturn(List.of());
        when(shipmentRecordRepository.findByFactoryIdAndBatchNumber(anyString(), anyString()))
                .thenReturn(List.of());
        when(factoryRepository.findById(FACTORY)).thenReturn(Optional.empty());
        return batch;
    }

    private TraceabilityDTO.LineageSection section(int nodeCount) {
        return TraceabilityDTO.LineageSection.builder()
                .rootType("PRODUCTION_BATCH").rootId("8967")
                .ancestors(List.of()).nodeCount(nodeCount).maxDepth(5)
                .build();
    }

    @Test
    @DisplayName("内部完整溯源: 调 assembler 时 includeDescendants=true, desensitize=false, 且 lineage 挂上响应")
    void fullTraceAsksForDescendantsAndNoDesensitization() {
        stubBatch();
        when(lineageGraphAssembler.assemble(anyString(), anyString(), anyString(), anyInt(),
                anyBoolean(), anyBoolean())).thenReturn(section(3));

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, BATCH_NUMBER);

        // 真入口上确实接了线（而不是只有 assembler 自己的单测绿）
        // 跳数 = 上限 50 (字面量): 10 道工序的首道原料在 depth 19, 原来的 5 跳只看得到最后两道。
        verify(lineageGraphAssembler).assemble(eq(FACTORY), eq("PRODUCTION_BATCH"), eq("8967"),
                eq(50), eq(true), eq(false));
        assertThat(response.getLineage()).isNotNull();
        assertThat(response.getLineage().getNodeCount()).isEqualTo(3);
        // 加法式: 既有字段仍在
        assertThat(response.getProduction().getBatchNumber()).isEqualTo(BATCH_NUMBER);
    }

    @Test
    @DisplayName("公开溯源: 走 assemblePublic (只给原料), ⛔ 不走内部的 assemble")
    void publicTraceUsesThePublicRawMaterialView() {
        stubBatch();
        when(lineageGraphAssembler.assemblePublic(anyString(), anyString(), anyString()))
                .thenReturn(new LineageGraphAssembler.PublicLineage(section(2), List.of()));

        TraceabilityDTO.PublicTraceResponse response = service.getPublicTrace(BATCH_NUMBER);

        // 🔴 这是消费者侧唯一的脱敏闸门。走成内部 assemble = 把中间工序 / 同一批还发给了谁露给扫码的人。
        verify(lineageGraphAssembler).assemblePublic(FACTORY, "PRODUCTION_BATCH", "8967");
        org.mockito.Mockito.verify(lineageGraphAssembler, org.mockito.Mockito.never())
                .assemble(anyString(), anyString(), anyString(), anyInt(), anyBoolean(), anyBoolean());
        assertThat(response.getLineage()).isNotNull();
        assertThat(response.getIsValid()).isTrue();
    }

    /**
     * Steve 2026-10-25: 公开溯源只显示原料来源。多道工序的产品, 最后一道直接投料的是上一道的在制库存批 ——
     * 「原材料来源」卡原来会把它当原料露出来 (名字就是工序半成品)。
     */
    @Test
    @DisplayName("公开「原材料来源」: 直接投料里的在制批 / 副产不出, 链上的原料并进来, 按批去重")
    void publicMaterialsShowOnlyRawMaterials() {
        stubBatch();
        com.cretas.aims.entity.MaterialConsumption wipUse = new com.cretas.aims.entity.MaterialConsumption();
        wipUse.setBatchId("wip-9");
        com.cretas.aims.entity.MaterialConsumption rawUse = new com.cretas.aims.entity.MaterialConsumption();
        rawUse.setBatchId("raw-spice");
        com.cretas.aims.entity.MaterialConsumption rawUseAgain = new com.cretas.aims.entity.MaterialConsumption();
        rawUseAgain.setBatchId("raw-spice");
        when(materialConsumptionRepository.findByProductionBatchId(8967L))
                .thenReturn(List.of(wipUse, rawUse, rawUseAgain));
        com.cretas.aims.entity.MaterialBatch wip = materialBatch("wip-9", "第9道在制品", "PRODUCTION_BATCH");
        com.cretas.aims.entity.MaterialBatch spice = materialBatch("raw-spice", "八角", "PURCHASE_RECEIVE");
        com.cretas.aims.entity.MaterialBatch cattle = materialBatch("raw-cmr", "整牛", "PURCHASE_RECEIVE");
        when(materialBatchRepository.findAllById(any())).thenReturn(List.of(wip, spice));
        when(lineageGraphAssembler.assemblePublic(anyString(), anyString(), anyString()))
                .thenReturn(new LineageGraphAssembler.PublicLineage(section(1), List.of(cattle)));

        TraceabilityDTO.PublicTraceResponse response = service.getPublicTrace(BATCH_NUMBER);

        System.out.println("[public-raw-only] materials=" + response.getMaterials());
        assertThat(response.getMaterials()).extracting(TraceabilityDTO.PublicMaterialInfo::getMaterialType)
                .containsExactly("八角", "整牛");
    }

    private static com.cretas.aims.entity.MaterialBatch materialBatch(String id, String typeName, String sourceDocType) {
        com.cretas.aims.entity.MaterialBatch mb = new com.cretas.aims.entity.MaterialBatch();
        mb.setId(id);
        mb.setSourceDocType(sourceDocType);
        com.cretas.aims.entity.RawMaterialType type = new com.cretas.aims.entity.RawMaterialType();
        type.setName(typeName);
        mb.setMaterialType(type);
        return mb;
    }

    @Test
    @DisplayName("assembler 抛异常时溯源页仍然返回, 但 lineage 是 null（本次没拿到 ≠ 确实没有上游）")
    void assemblerFailureDoesNotBreakThePageAndYieldsNullNotEmpty() {
        stubBatch();
        when(lineageGraphAssembler.assemble(anyString(), anyString(), anyString(), anyInt(),
                anyBoolean(), anyBoolean())).thenThrow(new RuntimeException("closure table down"));

        TraceabilityDTO.FullTraceResponse response = service.getFullTrace(FACTORY, BATCH_NUMBER);

        assertThat(response).isNotNull();
        // null = 「本次没拿到」；如果这里返回一个空 section，前端会显示「无上游血缘记录」——
        // 把故障说成了事实。
        assertThat(response.getLineage()).isNull();
    }

    @Test
    @DisplayName("召回影响面: 非法批次类型 400, ⛔ 不返回全 0 空壳")
    void impactRejectsUnknownBatchType() {
        assertThatThrownBy(() -> service.getImpact(FACTORY, "NOT_A_TYPE", "x", 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的批次类型");
    }

    @Test
    @DisplayName("召回影响面: batchId 为空 400")
    void impactRejectsBlankId() {
        assertThatThrownBy(() -> service.getImpact(FACTORY, "MATERIAL_BATCH", "  ", 5))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("召回影响面: 合法入参透传给 assembler（类型大小写归一）")
    void impactDelegatesToAssembler() {
        when(lineageGraphAssembler.assembleImpact(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(TraceabilityDTO.ImpactResponse.builder()
                        .rootType("MATERIAL_BATCH").rootId("mb-1")
                        .productionBatchCount(1).finishedBatchCount(0).shipmentCount(0)
                        .build());

        TraceabilityDTO.ImpactResponse response =
                service.getImpact(FACTORY, "material_batch", "mb-1", 7);

        verify(lineageGraphAssembler).assembleImpact(FACTORY, "MATERIAL_BATCH", "mb-1", 7);
        assertThat(response.getProductionBatchCount()).isEqualTo(1);
    }
}
