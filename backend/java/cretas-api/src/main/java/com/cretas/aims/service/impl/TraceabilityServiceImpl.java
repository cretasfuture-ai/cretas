package com.cretas.aims.service.impl;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.enums.SalesDeliveryStatus;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.ProcessingStageType;
import com.cretas.aims.repository.*;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.sales.SalesDeliveryItemBatchAllocationRepository;
import com.cretas.aims.entity.sales.SalesDeliveryItemBatchAllocation;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.TraceabilityService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 溯源服务实现类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceabilityServiceImpl implements TraceabilityService {

    private final ProductionBatchRepository productionBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final ShipmentRecordRepository shipmentRecordRepository;
    private final ProcessingStageRecordRepository processingStageRecordRepository;
    private final UserRepository userRepository;
    private final FactoryRepository factoryRepository;
    private final SupplierRepository supplierRepository;
    private final CustomerRepository customerRepository;
    private final ProcessSheetRowRepository processSheetRowRepository;
    private final ObjectMapper objectMapper;

    /**
     * 标签仓库 —— 溯源码的<b>唯一</b>来源。
     *
     * <p>🔴 2026-09-15 真机彩排: 详情页顶上显示 `TR-CATTLE_3701_001-20260915-8125`, 而标签上印的是
     * `TRACE-SL-DEMO-BATCH-V1-SEED01`。原实现每次查询都 {@code generateTraceCode()} 现造一个:
     * 工厂配了编码规则就 {@code incrementSequence()}(<b>看一眼溯源就烧掉一个序号</b>),
     * 没配就 `TR-…-{毫秒%10000}`; 两条路都不落库、不与批次绑定、扫不回来。
     * 现在只返回 {@code labels.trace_code} 里<b>持久化过</b>的码, 没打过标签就是 {@code null},
     * ⛔ 不再现造 —— 一个扫不回来的「溯源码」不是溯源码, 是装饰。
     *
     * <p>⚠️ 允许为 null(单测 {@code @InjectMocks} 不 mock 它时), 为 null 时同样返回 null。
     */
    private final com.cretas.aims.repository.LabelRepository labelRepository;

    /**
     * 多跳血缘组装器。
     *
     * <p>⚠️ 允许为 null：本类在单测里用 {@code @InjectMocks} 构造，不 mock 它时会被塞 null。
     * 为 null 时 {@code lineage} 字段留 null（表示「本次没拿到血缘」），
     * ⛔ 不返回一个空的 LineageSection —— 那会让「引擎没接上」长成「确实没有上游」。
     */
    private final com.cretas.aims.service.lineage.LineageGraphAssembler lineageGraphAssembler;

    /**
     * New sales-delivery tables are optional for the legacy traceability test
     * constructor.  They are read only and deliberately field-injected so the
     * existing constructor contract remains unchanged.
     */
    @Autowired(required = false)
    private FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Autowired(required = false)
    private SalesDeliveryItemBatchAllocationRepository salesDeliveryItemBatchAllocationRepository;

    @Autowired(required = false)
    private SalesDeliveryItemRepository salesDeliveryItemRepository;

    @Autowired(required = false)
    private SalesDeliveryRecordRepository salesDeliveryRecordRepository;

    @Autowired
    private BatchLineageEdgeRepository batchLineageEdgeRepository;

    @Autowired
    private com.cretas.aims.service.unit.UnitContractService unitContractService;


    /** 血缘查询允许的根节点类型（与 BatchLineageEdge.sourceType 取值集一致）。 */
    private static final Set<String> LINEAGE_ROOT_TYPES = Set.of(
            "MATERIAL_BATCH", "PRODUCTION_BATCH", "FINISHED_BATCH", "SHIPMENT_RECORD");

    @Override
    @Transactional(readOnly = true)
    public TraceabilityDTO.BatchTraceResponse getBatchTrace(String factoryId, String batchNumber) {
        log.info("获取批次基础溯源信息: factoryId={}, batchNumber={}", factoryId, batchNumber);

        // 1. 查找生产批次
        Optional<ProductionBatch> batchOpt = productionBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);

        if (batchOpt.isEmpty()) {
            log.warn("未找到生产批次: {}", batchNumber);
            return null;
        }

        ProductionBatch batch = batchOpt.get();

        // 2. 构建生产信息
        TraceabilityDTO.ProductionInfo productionInfo = buildProductionInfo(batch, factoryId);

        // 3. 统计关联数据数量
        List<MaterialConsumption> consumptions = materialConsumptionRepository
                .findByProductionBatchId(batch.getId());
        List<QualityInspection> inspections = qualityInspectionRepository
                .findByProductionBatchId(batch.getId());
        List<ShipmentRecord> shipments = shipmentRecordRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);

        // 4. 构建响应
        return TraceabilityDTO.BatchTraceResponse.builder()
                .production(productionInfo)
                .materialCount(consumptions.size())
                .inspectionCount(inspections.size())
                .shipmentCount(shipments.size())
                .qualityStatus(batch.getQualityStatus() != null ? batch.getQualityStatus().name() : "PENDING")
                .lastUpdateTime(batch.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)  // 溯源码改为只读标签表, 本方法不再有任何写操作
    public TraceabilityDTO.FullTraceResponse getFullTrace(String factoryId, String batchNumber) {
        log.info("获取完整溯源链路: factoryId={}, batchNumber={}", factoryId, batchNumber);

        // 1. 查找生产批次
        Optional<ProductionBatch> batchOpt = productionBatchRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);

        if (batchOpt.isEmpty()) {
            log.warn("未找到生产批次: {}", batchNumber);
            return null;
        }

        ProductionBatch batch = batchOpt.get();

        // 2. 构建生产信息
        TraceabilityDTO.ProductionInfo productionInfo = buildProductionInfo(batch, factoryId);

        // 3. 获取原材料消耗记录并构建材料信息
        List<ProcessSheetRow> processSheetRows = loadTraceProcessSheetRows(factoryId, batch);
        TraceConsumptionResolution traceConsumptions = resolveTraceConsumptions(factoryId, batch, processSheetRows);
        List<TraceabilityDTO.MaterialInfo> materials = buildMaterialInfoList(traceConsumptions.consumptions());

        // 4. 获取加工环节记录
        List<ProcessingStageRecord> stageRecords = processingStageRecordRepository
                .findByProductionBatchIdOrderByStageOrderAsc(batch.getId());
        List<TraceabilityDTO.ProcessingStageInfo> processingStages = buildProcessingStageInfoList(stageRecords);
        List<TraceabilityDTO.ProcessingStageInfo> sheetStages = buildProcessSheetStageInfoList(
                factoryId, batch, processSheetRows);
        if (!sheetStages.isEmpty()) {
            processingStages = new ArrayList<>(processingStages);
            processingStages.addAll(sheetStages);
            processingStages.sort(Comparator.comparing(
                    TraceabilityDTO.ProcessingStageInfo::getStageOrder,
                    Comparator.nullsLast(Integer::compareTo)));
        }

        // 5. 获取质检记录
        List<QualityInspection> inspections = qualityInspectionRepository
                .findByProductionBatchId(batch.getId());
        List<TraceabilityDTO.QualityInfo> qualityInfos = buildQualityInfoList(inspections);

        // 6. 获取出货记录
        List<ShipmentRecord> shipmentRecords = shipmentRecordRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);
        List<TraceabilityDTO.ShipmentInfo> shipments = buildShipmentInfoList(shipmentRecords);
        appendAllocatedSalesDeliveryShipments(factoryId, batch, shipments);

        // 7. 溯源码 = 这个批次已打印标签上的码(持久化); 没打过标签就是 null, ⛔ 不现造
        String traceCode = persistedTraceCode(batch.getId());

        // 8. 多跳血缘（加法式新增，既有字段一个不动）
        TraceabilityDTO.LineageSection lineage = buildLineage(
                factoryId, "PRODUCTION_BATCH", String.valueOf(batch.getId()), true, false);

        // 9. 构建完整响应
        return TraceabilityDTO.FullTraceResponse.builder()
                .production(productionInfo)
                .materials(materials)
                .sharedInputSourceBatchNumber(traceConsumptions.sharedOwnerBatchNumber())
                .processingStages(processingStages)
                .qualityInspections(qualityInfos)
                .shipments(shipments)
                .traceCode(traceCode)
                .queryTime(LocalDateTime.now())
                .lineage(lineage)
                .build();
    }

    @Override
    @Transactional(readOnly = true)  // 同 getFullTrace: 溯源码只读标签表
    public TraceabilityDTO.PublicTraceResponse getPublicTrace(String batchNumber) {
        log.info("公开溯源查询: batchNumber={}", batchNumber);

        // 1. 跨工厂查找生产批次（公开查询不限制工厂）
        // N+1 修复：使用直接查询替代 findAll().stream().filter()
        Optional<ProductionBatch> batchOpt = productionBatchRepository.findByBatchNumber(batchNumber);

        if (batchOpt.isEmpty()) {
            log.warn("公开查询未找到批次: {}", batchNumber);
            return TraceabilityDTO.PublicTraceResponse.builder()
                    .batchNumber(batchNumber)
                    .isValid(false)
                    .message("未找到该批次信息")
                    .queryTime(LocalDateTime.now())
                    .build();
        }

        ProductionBatch batch = batchOpt.get();
        String factoryId = batch.getFactoryId();

        // 2. 获取工厂信息（脱敏）
        //    ⛔ 找不到工厂时原来回落成写死的「认证工厂」—— 一个没有任何认证来源的说法。
        //    找不到就是 null, 前端不渲染这一行。
        String factoryName = factoryRepository.findById(factoryId)
                .map(Factory::getName)
                .orElse(null);

        // 3. 多跳来源链 —— Steve 2026-10-25 裁定: 公开溯源只显示原料来源, 不显示中间工序。
        //    往上查整条链, 但只拿回原料批 (中间生产批 / 在制库存批一个都不出), 见 assemblePublic。
        LineageGraphAssembler.PublicLineage publicLineage =
                buildPublicLineage(factoryId, String.valueOf(batch.getId()));
        TraceabilityDTO.LineageSection lineage = publicLineage == null ? null : publicLineage.section();

        // 4. 原材料来源卡（脱敏）: 本批直接投料里的原料 ∪ 链上的原料, 同一个「是不是原料」判据。
        //    ⛔ 多道工序的产品, 最后一道直接投的是上一道的在制库存批 —— 原来会把它当「原材料」露出来。
        List<MaterialConsumption> consumptions = materialConsumptionRepository
                .findByProductionBatchId(batch.getId());
        List<TraceabilityDTO.PublicMaterialInfo> publicMaterials = buildPublicMaterialInfoList(
                consumptions, publicLineage == null ? List.of() : publicLineage.rawMaterials());

        // 5. 获取质检信息（脱敏）
        List<QualityInspection> inspections = qualityInspectionRepository
                .findByProductionBatchId(batch.getId());
        TraceabilityDTO.PublicQualityInfo publicQuality = null;
        if (!inspections.isEmpty()) {
            QualityInspection latestInspection = inspections.get(inspections.size() - 1);
            publicQuality = TraceabilityDTO.PublicQualityInfo.builder()
                    .inspectionDate(latestInspection.getInspectionDate().atStartOfDay())
                    .result(latestInspection.getResult())
                    .passRate(latestInspection.getPassRate() != null ?
                            latestInspection.getPassRate().doubleValue() : null)
                    .build();
        }

        // 6. 构建公开响应
        return TraceabilityDTO.PublicTraceResponse.builder()
                .productName(batch.getProductName())
                .batchNumber(batchNumber)
                .productionDate(batch.getStartTime() != null ? batch.getStartTime().toLocalDate() : null)
                .factoryName(factoryName)
                .qualityStatus(batch.getQualityStatus() != null ? batch.getQualityStatus().name() : "待检验")
                // ⛔ 原来对所有批次无条件写死「食品安全认证」—— 消费者扫码看到的是一张编出来的证。
                //    现在没有产品 / 工厂级认证的落库来源: Factory 实体没有认证字段;
                //    MaterialBatch.qualityCertificate 是<b>原料批次</b>的字段(还被当 qualityGrade 用),
                //    不是本成品的认证; 供应商资质(SupplierQualification)属于供应商, 公开页已按脱敏不带供应商。
                //    ⇒ 一律 null, 前端为 null 时整块不渲染。有了真实来源再接, ⛔ 不要再写一个常量顶上。
                .certificationInfo(null)
                .materials(publicMaterials)
                .qualityInspection(publicQuality)
                .traceCode(persistedTraceCode(batch.getId()))
                .queryTime(LocalDateTime.now())
                .isValid(true)
                .message("溯源信息查询成功")
                .lineage(lineage)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TraceabilityDTO.ImpactResponse getImpact(String factoryId, String batchType, String batchId,
                                                    int maxDepth) {
        String normalizedType = batchType == null ? null : batchType.trim().toUpperCase(Locale.ROOT);
        if (!LINEAGE_ROOT_TYPES.contains(normalizedType)) {
            throw new BusinessException(400, "不支持的批次类型: " + batchType)
                    .withHint("批次类型只能是 MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD")
                    .withHintTarget("batchType");
        }
        if (batchId == null || batchId.isBlank()) {
            throw new BusinessException(400, "批次 ID 不能为空").withHintTarget("batchId");
        }
        if (lineageGraphAssembler == null) {
            // ⛔ 禁止降级: 召回场景下「查不成」绝不能返回一个全 0 的空壳,
            //    那会被读成「这批货没发出去过」。
            throw new BusinessException(503, "血缘引擎未就绪，无法计算召回影响面")
                    .withHint("请稍后重试；若持续出现请联系管理员检查血缘服务");
        }
        return lineageGraphAssembler.assembleImpact(factoryId, normalizedType, batchId, maxDepth);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 组装多跳血缘。
     *
     * <p>血缘是溯源页的<b>附加</b>信息，组装失败不应该让整张溯源页 500 —— 返回 null 表示
     * 「本次没拿到血缘」，与「确实没有上游」（返回空 ancestors 的 section）是两个不同的读数，
     * 前端据此分别显示「暂时取不到」和「无上游血缘记录」。
     */
    private TraceabilityDTO.LineageSection buildLineage(String factoryId, String rootType, String rootId,
                                                        boolean includeDescendants, boolean desensitize) {
        if (lineageGraphAssembler == null) {
            return null;
        }
        try {
            // 内部完整溯源取整条链（上限）, 中间工序全给。公开侧走 buildPublicLineage, 只给原料。
            return lineageGraphAssembler.assemble(factoryId, rootType, rootId,
                    com.cretas.aims.service.lineage.LineageGraphAssembler.MAX_DEPTH_CAP,
                    includeDescendants, desensitize);
        } catch (Exception e) {
            log.warn("[lineage] 溯源页血缘组装失败, 本次不带 lineage 字段: factory={} root={}:{}",
                    factoryId, rootType, rootId, e);
            return null;
        }
    }

    /**
     * 公开溯源的血缘（只有原料，没有中间工序）。失败语义同 {@link #buildLineage}：null = 本次没拿到。
     */
    private LineageGraphAssembler.PublicLineage buildPublicLineage(String factoryId, String productionBatchId) {
        if (lineageGraphAssembler == null) {
            return null;
        }
        try {
            return lineageGraphAssembler.assemblePublic(
                    factoryId, LineageEdgeRecorder.NODE_PRODUCTION_BATCH, productionBatchId);
        } catch (Exception e) {
            log.warn("[lineage] 公开溯源血缘组装失败, 本次不带 lineage 字段: factory={} pb={}",
                    factoryId, productionBatchId, e);
            return null;
        }
    }

    /**
     * 构建生产信息
     */
    private TraceabilityDTO.ProductionInfo buildProductionInfo(ProductionBatch batch, String factoryId) {
        // 获取工厂名称
        String factoryName = factoryId;
        Optional<Factory> factoryOpt = factoryRepository.findById(factoryId);
        if (factoryOpt.isPresent()) {
            factoryName = factoryOpt.get().getName();
        }

        return TraceabilityDTO.ProductionInfo.builder()
                .batchId(batch.getId())
                .batchNumber(batch.getBatchNumber())
                .productName(batch.getProductName())
                .productType(batch.getProductTypeId())
                .productionDate(batch.getStartTime() != null ? batch.getStartTime().toLocalDate() : null)
                .completionTime(batch.getEndTime())
                .supervisorName(batch.getSupervisorName())
                .equipmentName(batch.getEquipmentName())
                .quantity(batch.getActualQuantity() != null ? batch.getActualQuantity().doubleValue() : null)
                .goodQuantity(batch.getGoodQuantity() != null ? batch.getGoodQuantity().doubleValue() : null)
                .defectQuantity(batch.getDefectQuantity() != null ? batch.getDefectQuantity().doubleValue() : null)
                .unit(batch.getUnit())
                .qualityStatus(batch.getQualityStatus() != null ? batch.getQualityStatus().name() : "PENDING")
                .factoryName(factoryName)
                .factoryId(factoryId)
                .build();
    }

    /**
     * 构建原材料信息列表
     * N+1 修复：使用批量查询 + Map 替代循环中的单独查询
     */
    private List<TraceabilityDTO.MaterialInfo> buildMaterialInfoList(List<MaterialConsumption> consumptions) {
        if (consumptions.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 批量查询所有 MaterialBatch
        Set<String> batchIds = consumptions.stream()
                .map(MaterialConsumption::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, MaterialBatch> batchMap = materialBatchRepository.findAllById(batchIds)
                .stream().collect(Collectors.toMap(MaterialBatch::getId, Function.identity()));

        // 2. 批量查询所有 Supplier
        Set<String> supplierIds = batchMap.values().stream()
                .map(MaterialBatch::getSupplierId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Supplier> supplierMap = supplierIds.isEmpty() ? Collections.emptyMap() :
                supplierRepository.findAllById(supplierIds)
                        .stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));

        // 3. 使用 Map 关联数据
        List<TraceabilityDTO.MaterialInfo> materials = new ArrayList<>();
        for (MaterialConsumption consumption : consumptions) {
            MaterialBatch materialBatch = batchMap.get(consumption.getBatchId());
            if (materialBatch == null) {
                continue;
            }

            // 从 Map 获取供应商信息
            String supplierName = "未知供应商";
            String supplierCode = "";
            if (materialBatch.getSupplierId() != null) {
                Supplier supplier = supplierMap.get(materialBatch.getSupplierId());
                if (supplier != null) {
                    supplierName = supplier.getName();
                    supplierCode = supplier.getSupplierCode();
                }
            }

            // 获取材料类型名称
            String materialName = materialBatch.getMaterialTypeId();
            String materialType = "原材料";
            if (materialBatch.getMaterialType() != null) {
                materialName = materialBatch.getMaterialType().getName();
                materialType = materialBatch.getMaterialType().getCategory();
            }

            materials.add(TraceabilityDTO.MaterialInfo.builder()
                    .batchNumber(materialBatch.getBatchNumber())
                    .materialName(materialName)
                    .materialType(materialType)
                    .supplierName(supplierName)
                    .supplierCode(supplierCode)
                    .receiptDate(materialBatch.getReceiptDate())
                    .expireDate(materialBatch.getExpireDate())
                    .quantity(consumption.getQuantity() != null ? consumption.getQuantity().doubleValue() : null)
                    .automatic(com.cretas.aims.service.processsecret.ProcessSecretRecipeDerived.isAutomaticConsumption(consumption.getSourceType()))
                    .unit(materialBatch.getQuantityUnit())
                    .storageLocation(materialBatch.getStorageLocation())
                    .status(materialBatch.getStatus() != null ? materialBatch.getStatus().name() : "UNKNOWN")
                    .build());
        }

        return materials;
    }

    /**
     * 构建加工环节信息列表
     */
    private List<TraceabilityDTO.ProcessingStageInfo> buildProcessingStageInfoList(List<ProcessingStageRecord> records) {
        if (records.isEmpty()) {
            return new ArrayList<>();
        }

        return records.stream().map(record -> TraceabilityDTO.ProcessingStageInfo.builder()
                .stageType(record.getStageType() != null ? record.getStageType().name() : null)
                .stageName(record.getStageName())
                .stageOrder(record.getStageOrder())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .durationMinutes(record.getDurationMinutes())
                .inputWeight(record.getInputWeight() != null ? record.getInputWeight().doubleValue() : null)
                .inputUnit("kg")
                .outputWeight(record.getOutputWeight() != null ? record.getOutputWeight().doubleValue() : null)
                .outputUnit("kg")
                .lossRate(record.getLossRate() != null ? record.getLossRate().doubleValue() : null)
                .passRate(record.getPassRate() != null ? record.getPassRate().doubleValue() : null)
                .temperature(record.getProductTemperature() != null ? record.getProductTemperature().doubleValue() : null)
                .equipmentName(record.getEquipmentName())
                .operatorName(record.getOperatorName())
                .build()).collect(Collectors.toList());
    }

    /**
     * Process-sheet rows are the source of truth for clerk cross-batch entry.
     * Legacy ProcessingStageRecord is often empty for those rows, so full trace
     * must expose the row lineage here or mixed-batch traceability looks blank.
     */
    private List<TraceabilityDTO.ProcessingStageInfo> buildProcessSheetStageInfoList(
            String factoryId, ProductionBatch batch, List<ProcessSheetRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> exactGroupBatchIds = resolveExactMultiOutputGroupBatchIds(factoryId, batch, rows);
        String exactGroupKey = resolveTargetMultiOutputGroupKey(factoryId, batch, rows);
        boolean malformedMultiOutputAssociation = hasMalformedMultiOutputAssociation(factoryId, batch, rows);
        return rows.stream()
                .map(this::toProcessSheetStage)
                .filter(Objects::nonNull)
                .filter(s -> exactGroupBatchIds.isEmpty()
                        ? !malformedMultiOutputAssociation
                            && (isTargetProductStage(s.payload, batch)
                                || Objects.equals(s.row.getBatchNumber(), batch.getBatchNumber()))
                        : Objects.equals(batch.getId(), s.row.getBatchId())
                            && Objects.equals(factoryId, s.row.getFactoryId())
                            && Objects.equals(batch.getProductionPlanId(), s.row.getPlanId())
                            && Objects.equals(batch.getBatchNumber(), s.row.getBatchNumber())
                            && Objects.equals(exactGroupKey, multiOutputGroupKey(s.row, s.payload)))
                .sorted(Comparator.comparing(
                        s -> s.row.getProcessOrder(),
                        Comparator.nullsLast(Integer::compareTo)))
                .map(s -> TraceabilityDTO.ProcessingStageInfo.builder()
                        .stageType("PROCESS_SHEET")
                        .stageName(stringValue(s.payload.get("processName"),
                                s.row.getProcessCode() == null ? "process-sheet" : s.row.getProcessCode()))
                        .stageOrder(s.row.getProcessOrder())
                        .startTime(parseProcessDate(s.payload.get("processDate")))
                        .inputWeight(doubleValue(s.payload.get("inputQuantity")))
                        .outputWeight(doubleValue(s.payload.get("outputQuantity")))
                        .inputUnit(processSheetInputUnit(s.payload))
                        .outputUnit(processSheetOutputUnit(s.payload))
                        .lossRate(calcLossRate(factoryId, s.payload.get("inputQuantity"), s.payload.get("outputQuantity"),
                                processSheetInputUnit(s.payload), processSheetOutputUnit(s.payload)))
                        .passRate(null)
                        // Process-sheet payloads do not carry a verified worker identity.
                        // Source allocations are provenance, never an operator name.
                        .operatorName(null)
                        .build())
                .collect(Collectors.toList());
    }

    private List<ProcessSheetRow> loadTraceProcessSheetRows(String factoryId, ProductionBatch batch) {
        if (processSheetRowRepository == null || batch.getProductionPlanId() == null
                || batch.getProductionPlanId().isBlank()) {
            return List.of();
        }
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(
                factoryId, batch.getProductionPlanId());
        return rows == null ? List.of() : rows;
    }

    private TraceConsumptionResolution resolveTraceConsumptions(
            String factoryId, ProductionBatch batch, List<ProcessSheetRow> rows) {
        List<MaterialConsumption> direct = Optional.ofNullable(
                materialConsumptionRepository.findByProductionBatchId(batch.getId())).orElseGet(List::of);
        if (!direct.isEmpty() || rows.isEmpty()) {
            return new TraceConsumptionResolution(direct, null);
        }
        Set<Long> groupBatchIds = resolveExactMultiOutputGroupBatchIds(factoryId, batch, rows);
        if (groupBatchIds.isEmpty()) {
            return new TraceConsumptionResolution(direct, null);
        }
        List<TraceConsumptionOwner> owners = groupBatchIds.stream()
                .filter(id -> !Objects.equals(id, batch.getId()))
                .map(id -> resolveTraceOwner(factoryId, batch, rows, id))
                .filter(Objects::nonNull)
                .filter(owner -> !owner.consumptions().isEmpty())
                .toList();
        // One and only one group owner is authoritative for shared input.  Multiple
        // owners are ambiguous and must not be merged into a fabricated total.
        if (owners.size() != 1) {
            return new TraceConsumptionResolution(direct, null);
        }
        return new TraceConsumptionResolution(owners.getFirst().consumptions(),
                owners.getFirst().batch().getBatchNumber());
    }

    private TraceConsumptionOwner resolveTraceOwner(
            String factoryId, ProductionBatch target, List<ProcessSheetRow> rows, Long batchId) {
        Optional<ProductionBatch> owner = verifiedGroupBatch(factoryId, target, rows, batchId);
        if (owner.isEmpty()) {
            return null;
        }
        List<MaterialConsumption> consumptions = Optional.ofNullable(
                        materialConsumptionRepository.findByProductionBatchId(batchId))
                .orElseGet(List::of).stream()
                .filter(c -> Objects.equals(owner.get().getId(), c.getProductionBatchId())
                        && c.getDeletedAt() == null
                        && Objects.equals(factoryId, c.getFactoryId())
                        && Objects.equals(target.getProductionPlanId(), c.getProductionPlanId()))
                .toList();
        return new TraceConsumptionOwner(owner.get(), consumptions);
    }

    private Set<Long> resolveExactMultiOutputGroupBatchIds(
            String factoryId, ProductionBatch batch, List<ProcessSheetRow> rows) {
        Set<String> targetKeys = rows.stream()
                .map(this::toProcessSheetStage)
                .filter(Objects::nonNull)
                .filter(s -> Objects.equals(batch.getId(), s.row.getBatchId())
                        && Objects.equals(factoryId, s.row.getFactoryId())
                        && Objects.equals(batch.getProductionPlanId(), s.row.getPlanId())
                        && Objects.equals(batch.getBatchNumber(), s.row.getBatchNumber()))
                .filter(s -> isValidMultiOutputMember(s.payload))
                .map(s -> multiOutputGroupKey(s.row, s.payload))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (targetKeys.size() != 1) {
            return Set.of();
        }
        String key = targetKeys.iterator().next();
        return rows.stream()
                .map(this::toProcessSheetStage)
                .filter(Objects::nonNull)
                .filter(s -> Objects.equals(factoryId, s.row.getFactoryId())
                        && Objects.equals(batch.getProductionPlanId(), s.row.getPlanId())
                        && isValidMultiOutputMember(s.payload)
                        && Objects.equals(key, multiOutputGroupKey(s.row, s.payload))
                        && s.row.getBatchId() != null)
                .map(s -> s.row.getBatchId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String resolveTargetMultiOutputGroupKey(
            String factoryId, ProductionBatch batch, List<ProcessSheetRow> rows) {
        Set<String> keys = rows.stream().map(this::toProcessSheetStage).filter(Objects::nonNull)
                .filter(s -> Objects.equals(batch.getId(), s.row.getBatchId())
                        && Objects.equals(factoryId, s.row.getFactoryId())
                        && Objects.equals(batch.getProductionPlanId(), s.row.getPlanId())
                        && Objects.equals(batch.getBatchNumber(), s.row.getBatchNumber())
                        && isValidMultiOutputMember(s.payload))
                .map(s -> multiOutputGroupKey(s.row, s.payload))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return keys.size() == 1 ? keys.iterator().next() : null;
    }

    private boolean hasMalformedMultiOutputAssociation(
            String factoryId, ProductionBatch batch, List<ProcessSheetRow> rows) {
        boolean malformed = rows.stream().map(this::toProcessSheetStage).filter(Objects::nonNull)
                .anyMatch(s -> Objects.equals(factoryId, s.row.getFactoryId())
                        && Objects.equals(batch.getProductionPlanId(), s.row.getPlanId())
                        && Objects.equals(batch.getBatchNumber(), s.row.getBatchNumber())
                        && Boolean.TRUE.equals(s.payload.get("multiOutputMember"))
                        && (!Objects.equals(batch.getId(), s.row.getBatchId())
                            || !isValidMultiOutputMember(s.payload)));
        long distinctTargetKeys = rows.stream().map(this::toProcessSheetStage).filter(Objects::nonNull)
                .filter(s -> Objects.equals(batch.getId(), s.row.getBatchId())
                        && Objects.equals(factoryId, s.row.getFactoryId())
                        && Objects.equals(batch.getProductionPlanId(), s.row.getPlanId())
                        && Objects.equals(batch.getBatchNumber(), s.row.getBatchNumber())
                        && isValidMultiOutputMember(s.payload))
                .map(s -> multiOutputGroupKey(s.row, s.payload))
                .distinct().count();
        return malformed || distinctTargetKeys > 1;
    }

    private Optional<ProductionBatch> verifiedGroupBatch(
            String factoryId, ProductionBatch target, List<ProcessSheetRow> rows, Long batchId) {
        String groupKey = resolveTargetMultiOutputGroupKey(factoryId, target, rows);
        if (groupKey == null) {
            return Optional.empty();
        }
        Set<String> batchNumbers = rows.stream()
                .map(this::toProcessSheetStage)
                .filter(Objects::nonNull)
                .filter(stage -> Objects.equals(batchId, stage.row.getBatchId())
                        && Objects.equals(factoryId, stage.row.getFactoryId())
                        && Objects.equals(target.getProductionPlanId(), stage.row.getPlanId())
                        && isValidMultiOutputMember(stage.payload)
                        && Objects.equals(groupKey, multiOutputGroupKey(stage.row, stage.payload)))
                .map(stage -> stage.row.getBatchNumber())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (batchNumbers.size() != 1 || batchNumbers.stream().anyMatch(number -> number == null || number.isBlank())) {
            return Optional.empty();
        }
        String batchNumber = batchNumbers.iterator().next();
        return productionBatchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .filter(batch -> Objects.equals(batchId, batch.getId())
                        && Objects.equals(factoryId, batch.getFactoryId())
                        && Objects.equals(target.getProductionPlanId(), batch.getProductionPlanId())
                        && Objects.equals(batchNumber, batch.getBatchNumber())
                        && batch.getDeletedAt() == null);
    }

    private boolean isValidMultiOutputMember(Map<String, Object> payload) {
        return Boolean.TRUE.equals(payload.get("multiOutputMember"))
                && !isBlank(stringValue(payload.get("multiOutputBaseRowId"), null));
    }

    private String multiOutputGroupKey(ProcessSheetRow row, Map<String, Object> payload) {
        return row.getProcessCode() + "|" + row.getProcessOrder() + "|"
                + stringValue(payload.get("multiOutputBaseRowId"), null);
    }

    private record TraceConsumptionOwner(ProductionBatch batch, List<MaterialConsumption> consumptions) {}

    private record TraceConsumptionResolution(List<MaterialConsumption> consumptions,
                                               String sharedOwnerBatchNumber) {}

    private ProcessSheetStage toProcessSheetStage(ProcessSheetRow row) {
        if (row.getRowPayload() == null || row.getRowPayload().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    row.getRowPayload(),
                    new TypeReference<Map<String, Object>>() {});
            return new ProcessSheetStage(row, payload);
        } catch (Exception e) {
            log.warn("Failed to parse process sheet row payload for traceability: rowId={}", row.getId(), e);
            return null;
        }
    }

    private boolean isTargetProductStage(Map<String, Object> payload, ProductionBatch batch) {
        Object productTypeId = payload.get("productTypeId");
        return productTypeId != null && Objects.equals(String.valueOf(productTypeId), batch.getProductTypeId());
    }

    private LocalDateTime parseProcessDate(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value)).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Double calcLossRate(String factoryId, Object inputValue, Object outputValue,
                                String inputUnit, String outputUnit) {
        if (inputUnit == null || outputUnit == null || !sameUnit(factoryId, inputUnit, outputUnit)) {
            return null;
        }
        Double input = doubleValue(inputValue);
        Double output = doubleValue(outputValue);
        if (input == null || input == 0 || output == null) {
            return null;
        }
        return (input - output) / input;
    }

    private boolean sameUnit(String factoryId, String left, String right) {
        if (left == null || right == null) return false;
        if (unitContractService == null) {
            throw new IllegalStateException("UnitContractService is required for unit identity checks");
        }
        String canonicalLeft = unitContractService.storageUnit(factoryId, left);
        String canonicalRight = unitContractService.storageUnit(factoryId, right);
        return canonicalLeft != null && !canonicalLeft.isBlank()
                && Objects.equals(canonicalLeft, canonicalRight);
    }

    private String processSheetInputUnit(Map<String, Object> payload) {
        String explicit = firstNonBlank(stringValue(payload.get("inputUnit"), null),
                stringValue(payload.get("inputQuantityUnit"), null));
        if (explicit != null) {
            return explicit;
        }
        Object upstream = payload.get("upstreamSources");
        if (upstream instanceof List<?> list && !list.isEmpty()) {
            return inferSourceUnit(list, true);
        }
        Object raws = payload.get("rawMaterialInputs");
        if (raws instanceof List<?> list && !list.isEmpty()) {
            return inferSourceUnit(list, false);
        }
        return null;
    }

    private String inferSourceUnit(List<?> sourceEntries, boolean feedQuantityKgIsLegacyUnit) {
        Set<String> units = new LinkedHashSet<>();
        for (Object entry : sourceEntries) {
            if (!(entry instanceof Map<?, ?> map)) {
                return null;
            }
            String unit = firstNonBlank(stringValue(map.get("unit"), null),
                    stringValue(map.get("quantityUnit"), null));
            if (unit == null && feedQuantityKgIsLegacyUnit && map.get("feedQuantityKg") != null) {
                unit = "kg";
            }
            if (unit == null) {
                return null;
            }
            units.add(unit);
        }
        return units.size() == 1 ? units.iterator().next() : null;
    }

    private String processSheetOutputUnit(Map<String, Object> payload) {
        return firstNonBlank(stringValue(payload.get("outputUnit"), null),
                stringValue(payload.get("unit"), null),
                stringValue(payload.get("outputQuantityUnit"), null));
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value);
        return s.isBlank() ? fallback : s;
    }

    private record ProcessSheetStage(ProcessSheetRow row, Map<String, Object> payload) {}

    /**
     * 构建质检信息列表
     * N+1 修复：使用批量查询 + Map 替代循环中的单独查询
     */
    private List<TraceabilityDTO.QualityInfo> buildQualityInfoList(List<QualityInspection> inspections) {
        if (inspections.isEmpty()) {
            return new ArrayList<>();
        }

        // 批量查询所有检验员
        Set<Long> inspectorIds = inspections.stream()
                .map(QualityInspection::getInspectorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> inspectorMap = inspectorIds.isEmpty() ? Collections.emptyMap() :
                userRepository.findAllById(inspectorIds)
                        .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        // 使用 Map 关联数据
        List<TraceabilityDTO.QualityInfo> qualityInfos = new ArrayList<>();
        for (QualityInspection inspection : inspections) {
            // 从 Map 获取检验员名称
            String inspectorName = null;
            if (inspection.getInspectorId() != null) {
                User inspector = inspectorMap.get(inspection.getInspectorId());
                if (inspector != null) {
                    inspectorName = inspector.getFullName();
                }
            }

            qualityInfos.add(TraceabilityDTO.QualityInfo.builder()
                    .inspectionId(inspection.getId())
                    .inspectorId(inspection.getInspectorId())
                    .inspectionDate(inspection.getInspectionDate() != null ? inspection.getInspectionDate().atStartOfDay() : null)
                    .inspectorName(inspectorName)
                    .result(inspection.getResult())
                    .passRate(inspection.getPassRate() != null ? inspection.getPassRate().doubleValue() : null)
                    .sampleSize(inspection.getSampleSize() != null ? inspection.getSampleSize().doubleValue() : null)
                    .passCount(inspection.getPassCount() != null ? inspection.getPassCount().doubleValue() : null)
                    .failCount(inspection.getFailCount() != null ? inspection.getFailCount().doubleValue() : null)
                    .conclusion(inspection.getResult())
                    .remarks(inspection.getNotes())
                    .build());
        }

        return qualityInfos;
    }

    /**
     * 构建出货信息列表
     * N+1 修复：使用批量查询 + Map 替代循环中的单独查询
     */
    private List<TraceabilityDTO.ShipmentInfo> buildShipmentInfoList(List<ShipmentRecord> shipmentRecords) {
        if (shipmentRecords.isEmpty()) {
            return new ArrayList<>();
        }

        // 批量查询所有客户
        Set<String> customerIds = shipmentRecords.stream()
                .map(ShipmentRecord::getCustomerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Customer> customerMap = customerIds.isEmpty() ? Collections.emptyMap() :
                customerRepository.findAllById(customerIds)
                        .stream().collect(Collectors.toMap(Customer::getId, Function.identity()));

        // 使用 Map 关联数据
        List<TraceabilityDTO.ShipmentInfo> shipments = new ArrayList<>();
        for (ShipmentRecord record : shipmentRecords) {
            // 从 Map 获取客户名称
            String customerName = "客户";
            if (record.getCustomerId() != null) {
                Customer customer = customerMap.get(record.getCustomerId());
                if (customer != null) {
                    customerName = customer.getName();
                }
            }

            shipments.add(TraceabilityDTO.ShipmentInfo.builder()
                    .shipmentNumber(record.getShipmentNumber())
                    .shipmentDate(record.getShipmentDate())
                    .shipmentTimeSource(record.getShipmentDate() == null ? "UNRECORDED" : "LEGACY_DATE_ONLY")
                    .customerName(customerName)
                    .logisticsCompany(record.getLogisticsCompany())
                    .trackingNumber(record.getTrackingNumber())
                    .status(record.getStatus())
                    .quantity(record.getQuantity() != null ? record.getQuantity().doubleValue() : null)
                    .unit(record.getUnit())
                    .build());
        }

        return shipments;
    }

    /**
     * Append shipments recorded by the current sales-delivery flow.  The old
     * ShipmentRecord table remains the first source for backwards compatibility;
     * allocations are then projected one by one so split batches and units are
     * not collapsed into a delivery-level total.
     */
    private void appendAllocatedSalesDeliveryShipments(String factoryId, ProductionBatch batch,
                                                        List<TraceabilityDTO.ShipmentInfo> shipments) {
        if (finishedGoodsBatchRepository == null
                || salesDeliveryItemBatchAllocationRepository == null
                || salesDeliveryItemRepository == null
                || salesDeliveryRecordRepository == null
                || batch.getProductionPlanId() == null
                || batch.getProductionPlanId().isBlank()) {
            return;
        }

        List<FinishedGoodsBatch> finishedGoodsBatches = Optional.ofNullable(
                finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                        factoryId, batch.getProductionPlanId())).orElseGet(List::of);
        if (finishedGoodsBatches.isEmpty()) {
            return;
        }

        Set<String> legacyKeys = shipments.stream()
                .map(this::shipmentKey)
                .collect(Collectors.toSet());
        Set<String> allocationKeys = new HashSet<>();
        Map<String, Customer> customerMap = new HashMap<>();
        Map<String, List<BatchLineageEdge>> shipmentEvents = new HashMap<>();

        for (FinishedGoodsBatch finishedGoodsBatch : finishedGoodsBatches) {
            if (finishedGoodsBatch == null || isBlank(finishedGoodsBatch.getId())
                    || !Objects.equals(factoryId, finishedGoodsBatch.getFactoryId())
                    || !Objects.equals(batch.getBatchNumber(), finishedGoodsBatch.getBatchNumber())
                    || !Objects.equals(batch.getProductTypeId(), finishedGoodsBatch.getProductTypeId())) {
                continue;
            }
            List<SalesDeliveryItemBatchAllocation> allocations = Optional.ofNullable(
                    salesDeliveryItemBatchAllocationRepository.findByFactoryIdAndFinishedGoodsBatchId(
                            factoryId, finishedGoodsBatch.getId())).orElseGet(List::of);
            for (SalesDeliveryItemBatchAllocation allocation : allocations) {
                if (allocation == null
                        || !Objects.equals(factoryId, allocation.getFactoryId())
                        || !Objects.equals(finishedGoodsBatch.getId(), allocation.getFinishedGoodsBatchId())
                        || allocation.getAllocatedQty() == null
                        || allocation.getAllocatedQty().signum() <= 0) {
                    continue;
                }
                Long deliveryItemId;
                try {
                    deliveryItemId = Long.valueOf(allocation.getDeliveryItemId());
                } catch (Exception e) {
                    continue;
                }
                SalesDeliveryItem item = salesDeliveryItemRepository.findById(deliveryItemId).orElse(null);
                // The legacy item field stores only the first deducted batch.
                // Exact per-batch allocations are authoritative for split shipments.
                if (item == null || isBlank(item.getDeliveryRecordId())
                        || !Objects.equals(item.getProductTypeId(), finishedGoodsBatch.getProductTypeId())) {
                    continue;
                }
                SalesDeliveryRecord delivery = salesDeliveryRecordRepository.findById(item.getDeliveryRecordId())
                        .orElse(null);
                if (delivery == null || !Objects.equals(factoryId, delivery.getFactoryId())
                        || (delivery.getStatus() != SalesDeliveryStatus.SHIPPED
                        && delivery.getStatus() != SalesDeliveryStatus.DELIVERED)) {
                    continue;
                }

                String unit = firstNonBlank(allocation.getUnit(), item.getUnit(), finishedGoodsBatch.getUnit());
                LocalDateTime shippedAt = actualShipmentTime(factoryId, delivery.getId(),
                        finishedGoodsBatch.getId(), shipmentEvents);
                TraceabilityDTO.ShipmentInfo shipment = TraceabilityDTO.ShipmentInfo.builder()
                        .shipmentNumber(delivery.getDeliveryNumber())
                        .shipmentDate(shippedAt == null ? null : shippedAt.toLocalDate())
                        .plannedDeliveryDate(delivery.getDeliveryDate())
                        .shippedAt(shippedAt)
                        .shipmentTimeSource(shippedAt == null ? "UNRECORDED" : "LINEAGE_EVENT")
                        .customerName(resolveSalesDeliveryCustomerName(factoryId, delivery, customerMap))
                        .logisticsCompany(delivery.getLogisticsCompany())
                        .trackingNumber(delivery.getTrackingNumber())
                        .status(delivery.getStatus().name())
                        // SHIPPED/DELIVERED can only follow shipDelivery's allocation
                        // deduction.  Warehouse short-ship confirmation updates the
                        // item quantity and requires allocations to be reworked before
                        // shipping, so allocatedQty is the per-source actual here;
                        // item.deliveredQuantity is not a per-batch source quantity.
                        .quantity(allocation.getAllocatedQty().doubleValue())
                        .unit(unit)
                        .build();

                // A legacy row may represent this same delivery allocation.  Keep the
                // legacy row and skip only that exact projection; independent split
                // allocations retain their item/batch identity and remain visible.
                if (legacyKeys.contains(shipmentKey(shipment))) {
                    continue;
                }
                String allocationKey = shipmentKey(shipment) + "|item=" + deliveryItemId
                        + "|fg=" + finishedGoodsBatch.getId() + "|allocation=" + allocation.getId();
                if (allocationKeys.add(allocationKey)) {
                    shipments.add(shipment);
                }
            }
        }
    }

    private LocalDateTime actualShipmentTime(String factoryId, String deliveryId, String finishedBatchId,
                                              Map<String, List<BatchLineageEdge>> eventsByDelivery) {
        if (batchLineageEdgeRepository == null) {
            throw new IllegalStateException("出货时间来源未接通");
        }
        List<BatchLineageEdge> events = eventsByDelivery.computeIfAbsent(deliveryId,
                id -> batchLineageEdgeRepository.findByFactoryIdAndTargetIdAndTargetType(factoryId, id, "SHIPMENT_RECORD"));
        return events.stream()
                .filter(Objects::nonNull)
                .filter(edge -> Objects.equals(factoryId, edge.getFactoryId())
                        && "FINISHED_TO_SHIPMENT".equals(edge.getEdgeType())
                        && "FINISHED_BATCH".equals(edge.getSourceType())
                        && Objects.equals(finishedBatchId, edge.getSourceId())
                        && "SHIPMENT_RECORD".equals(edge.getTargetType())
                        && Objects.equals(deliveryId, edge.getTargetId())
                        && edge.getMeta() != null
                        && "SALES_DELIVERY_RECORD".equals(edge.getMeta().get("shipmentCarrier")))
                .map(BatchLineageEdge::getEventTime)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo).orElse(null);
    }

    private String resolveSalesDeliveryCustomerName(String factoryId, SalesDeliveryRecord delivery,
                                                      Map<String, Customer> customerMap) {
        if (!isBlank(delivery.getCustomerName())) {
            return delivery.getCustomerName();
        }
        if (!isBlank(delivery.getCustomerId())) {
            Customer customer = customerMap.get(delivery.getCustomerId());
            if (customer == null) {
                customer = customerRepository.findByIdAndFactoryId(delivery.getCustomerId(), factoryId).orElse(null);
                if (customer != null) {
                    customerMap.put(delivery.getCustomerId(), customer);
                }
            }
            if (customer != null && !isBlank(customer.getName())) {
                return customer.getName();
            }
        }
        return null;
    }

    private String shipmentKey(TraceabilityDTO.ShipmentInfo shipment) {
        return String.join("|", String.valueOf(shipment.getShipmentNumber()),
                String.valueOf(shipment.getShipmentDate()),
                String.valueOf(shipment.getQuantity()), String.valueOf(shipment.getUnit()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 构建公开原材料信息列表（脱敏）
     * N+1 修复：使用批量查询 + Map 替代循环中的单独查询
     *
     * <p>Steve 2026-10-25：公开侧只显示原料来源。本批直接投料里的在制库存批 / 副产
     * （{@link LineageGraphAssembler#isRawMaterialForPublic} 判否）不算原材料、不出；
     * 再并上链上的原料（{@code lineageRaws}），按批次去重，按品类 + 入库日排序 ——
     * ⛔ 不按投料顺序（那是工序先后），也不再区分「最后一道直接投的」和「更早投的」。
     */
    private List<TraceabilityDTO.PublicMaterialInfo> buildPublicMaterialInfoList(List<MaterialConsumption> consumptions,
                                                                                 List<MaterialBatch> lineageRaws) {
        // 1. 批量查询本批直接投料的 MaterialBatch, 只留原料, 再并上链上的原料
        Set<String> batchIds = consumptions.stream()
                .map(MaterialConsumption::getBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, MaterialBatch> batchMap = new LinkedHashMap<>();
        if (!batchIds.isEmpty()) {
            for (MaterialBatch mb : materialBatchRepository.findAllById(batchIds)) {
                if (LineageGraphAssembler.isRawMaterialForPublic(mb)) {
                    batchMap.putIfAbsent(mb.getId(), mb);
                }
            }
        }
        for (MaterialBatch mb : lineageRaws == null ? List.<MaterialBatch>of() : lineageRaws) {
            if (LineageGraphAssembler.isRawMaterialForPublic(mb)) {
                batchMap.putIfAbsent(mb.getId(), mb);
            }
        }
        if (batchMap.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 批量查询所有 Supplier
        Set<String> supplierIds = batchMap.values().stream()
                .map(MaterialBatch::getSupplierId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Supplier> supplierMap = supplierIds.isEmpty() ? Collections.emptyMap() :
                supplierRepository.findAllById(supplierIds)
                        .stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));

        // 3. 使用 Map 关联数据
        List<TraceabilityDTO.PublicMaterialInfo> materials = new ArrayList<>();
        for (MaterialBatch materialBatch : batchMap.values()) {
            String materialType = "原材料";
            if (materialBatch.getMaterialType() != null) {
                materialType = materialBatch.getMaterialType().getName();
            }

            // 从 Map 获取产地信息（简化）
            String origin = "国内";
            if (materialBatch.getSupplierId() != null) {
                Supplier supplier = supplierMap.get(materialBatch.getSupplierId());
                if (supplier != null && supplier.getAddress() != null) {
                    // 只显示省/市级别
                    String address = supplier.getAddress();
                    if (address.contains("省")) {
                        origin = address.split("省")[0] + "省";
                    } else if (address.contains("市")) {
                        origin = address.split("市")[0] + "市";
                    }
                }
            }

            materials.add(TraceabilityDTO.PublicMaterialInfo.builder()
                    .materialType(materialType)
                    .origin(origin)
                    .receiptDate(materialBatch.getReceiptDate())
                    .build());
        }
        materials.sort(Comparator
                .comparing(TraceabilityDTO.PublicMaterialInfo::getMaterialType,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(TraceabilityDTO.PublicMaterialInfo::getReceiptDate,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return materials;
    }

    /**
     * 该生产批次已打印标签上的溯源码; 多张标签取最新一张; 没有标签或没注入仓库 → {@code null}。
     *
     * <p>判据是「扫这个码能不能回到这个批次」: {@code labels.trace_code} 正是
     * {@code TraceabilityController#resolveBatchNumberFromTraceCode} 反查的那一列, 所以这里出的码
     * 一定能扫回来; 反过来任何现造的码都不能。
     */
    private String persistedTraceCode(Long productionBatchId) {
        if (labelRepository == null || productionBatchId == null) {
            return null;
        }
        return labelRepository.findByProductionBatchIdAndDeletedAtIsNull(productionBatchId).stream()
                .filter(l -> l.getTraceCode() != null && !l.getTraceCode().isBlank())
                .sorted(java.util.Comparator.comparing(
                        (com.cretas.aims.entity.Label l) -> l.getCreatedAt(),
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed())
                .map(com.cretas.aims.entity.Label::getTraceCode)
                .findFirst()
                .orElse(null);
    }
}
