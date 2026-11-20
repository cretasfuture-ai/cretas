package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.SourceIndividualDetailDTO;
import com.cretas.aims.dto.source.SourceIndividualDetailDTO.PreviouslyLinkedBatch;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.lineage.LineageGraphAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 源头个体详情 —— 本体 + 系谱(父母/后代/同父兄弟) + 去向(原料批次 → 生产 → 成品 → 出货)。
 *
 * <h3>查询次数是有界的, 不随后代数/批次数增长</h3>
 * <pre>
 *   本体           1  (findByIdAndFactoryId)
 *   父本 / 母本    ≤2 (各一次, 未登记则不发)
 *   后代           1  (findOffspring)
 *   同父兄弟       ≤1 (父本未登记则不发)
 *   原料批次       1  (findByFactoryIdAndSourceIndividualId…)
 *   解除日志       1  (本厂、当前个体)
 *   下游           每个原料批次一次 impact —— impact 内部已按类型批量解析节点
 * </pre>
 * 最后一项与批次数成正比, 但一头个体挂的原料批次是个位数(整牛入库一次就是一个批次),
 * ⛔ 这里<b>不</b>再对 impact 返回的节点逐条查实体 —— 那才是 N+1。
 *
 * <h3>「排除自身」在这里, 不在仓储里</h3>
 * {@code findByFactoryIdAndSireIndividualId…} 如实返回同父的全部个体(含自己);
 * 「同父兄弟不含自身」由本类过滤。变异对照: 去掉那个过滤, 测试必须红。
 *
 * @since 2026-09-15 (FEAT-RN-SOURCE-INDIVIDUAL-DETAIL-20260915)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceIndividualDetailService {

    private final SourceIndividualRepository repository;
    private final MaterialBatchRepository materialBatchRepository;
    private final LineageGraphAssembler lineageGraphAssembler;
    private final OperationLogRepository operationLogRepository;

    @Transactional(readOnly = true)
    public SourceIndividualDetailDTO getDetail(String factoryId, String id) {
        SourceIndividual self = repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "源头个体档案不存在: " + id));

        SourceIndividualDetailDTO.Summary sire = parentSummary(factoryId, self.getSireIndividualId());
        SourceIndividualDetailDTO.Summary dam = parentSummary(factoryId, self.getDamIndividualId());

        List<SourceIndividualDetailDTO.Summary> offspring = repository.findOffspring(factoryId, id).stream()
                .map(SourceIndividualDetailService::toSummary)
                .toList();

        List<SourceIndividualDetailDTO.Summary> siblingsBySire = self.getSireIndividualId() == null
                ? List.of()
                : repository.findByFactoryIdAndSireIndividualIdOrderByIndividualCodeAsc(
                                factoryId, self.getSireIndividualId()).stream()
                        // 「其它后代」—— 自己不算自己的兄弟。
                        .filter(si -> !id.equals(si.getId()))
                        .map(SourceIndividualDetailService::toSummary)
                        .toList();

        List<MaterialBatch> batches =
                materialBatchRepository.findByFactoryIdAndSourceIndividualIdOrderByReceiptDateDesc(factoryId, id);
        List<String> batchIds = batches.stream().map(MaterialBatch::getId).toList();
        List<PreviouslyLinkedBatch> previouslyLinked = findPreviouslyLinkedBatches(factoryId, id, Set.copyOf(batchIds));

        return SourceIndividualDetailDTO.builder()
                .individual(SourceIndividualService.toDTO(self))
                .sire(sire)
                .dam(dam)
                .offspring(offspring)
                .siblingsBySire(siblingsBySire)
                .batches(batches.stream().map(SourceIndividualDetailService::toBatchSummary).toList())
                .downstream(mergeDownstream(batchIds,
                        batchId -> lineageGraphAssembler.assembleImpact(factoryId,
                                LineageEdgeRecorder.NODE_MATERIAL_BATCH, batchId, LineageGraphAssembler.DEFAULT_MAX_DEPTH)))
                .previouslyLinkedBatches(previouslyLinked)
                .previouslyLinkedDownstream(mergeDownstream(
                        previouslyLinked.stream().map(PreviouslyLinkedBatch::batchId).toList(),
                        batchId -> lineageGraphAssembler.assembleAuditedMaterialBatchImpact(factoryId, batchId)))
                .build();
    }

    private List<PreviouslyLinkedBatch> findPreviouslyLinkedBatches(
            String factoryId, String individualId, Set<String> currentBatchIds) {
        Map<String, PreviouslyLinkedBatch> byBatchId = new LinkedHashMap<>();
        for (OperationLog row : operationLogRepository.findMaterialBatchUnlinksByIndividual(factoryId, individualId)) {
            if (!currentBatchIds.contains(row.getEntityId())) {
                byBatchId.computeIfAbsent(row.getEntityId(), ignored -> toPreviouslyLinkedBatch(row, individualId));
            }
        }
        return List.copyOf(byBatchId.values());
    }

    private static PreviouslyLinkedBatch toPreviouslyLinkedBatch(OperationLog row, String individualId) {
        String batchNumber = logString(row.getOldValue(), "batchNumber");
        String reason = logString(row.getNewValue(), "unlinkReason");
        // #1968 的旧日志只把批次号和原因写在摘要里，末尾的角色分隔符在原因之后。
        String summary = row.getSummary();
        String prefix = "解除批次 ";
        String separator = " 的来源个体 " + individualId + "; 原因: ";
        if ((batchNumber == null || reason == null) && summary != null && summary.startsWith(prefix)) {
            int separatorIndex = summary.indexOf(separator, prefix.length());
            int roleIndex = summary.lastIndexOf("; 角色: ");
            if (separatorIndex >= 0 && roleIndex >= separatorIndex + separator.length()) {
                if (batchNumber == null) {
                    batchNumber = summary.substring(prefix.length(), separatorIndex);
                }
                if (reason == null) {
                    reason = summary.substring(separatorIndex + separator.length(), roleIndex);
                }
            }
        }
        return new PreviouslyLinkedBatch(row.getEntityId(), batchNumber, row.getCreatedAt(), reason, row.getUserId());
    }

    private static String logString(Map<String, Object> values, String key) {
        return values != null && values.get(key) instanceof String value ? value : null;
    }

    /**
     * 父/母本摘要。未登记 ⇒ null; 登记了但查不到(软删/别厂) ⇒ 也是 null ——
     * 两者由调用方拿 {@code individual.sireIndividualId} 区分, 这里不编一个空壳。
     */
    private SourceIndividualDetailDTO.Summary parentSummary(String factoryId, String parentId) {
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        return repository.findByIdAndFactoryId(parentId, factoryId)
                .map(SourceIndividualDetailService::toSummary)
                .orElseGet(() -> {
                    log.warn("源头个体系谱指向一份查不到的档案: factoryId={}, parentId={}", factoryId, parentId);
                    return null;
                });
    }

    /**
     * 把每个原料批次的召回影响面合并成一份, 按 {@code nodeType#nodeId} 去重。
     *
     * <p>同一头牛分割成两个原料批次、又投进同一个生产批次时, 那个生产批次会在两份 impact 里
     * 各出现一次 —— 去重后只留一条，保留传入的批次顺序。
     * 没有批次时三个列表都是空列表, ⛔ 不是 null。
     */
    private SourceIndividualDetailDTO.Downstream mergeDownstream(
            List<String> batchIds, Function<String, TraceabilityDTO.ImpactResponse> loadImpact) {
        Map<String, TraceabilityDTO.LineageNode> production = new LinkedHashMap<>();
        Map<String, TraceabilityDTO.LineageNode> finished = new LinkedHashMap<>();
        Map<String, TraceabilityDTO.LineageNode> shipments = new LinkedHashMap<>();

        for (String batchId : batchIds) {
            TraceabilityDTO.ImpactResponse impact = loadImpact.apply(batchId);
            if (impact == null) {
                // 引擎给了 null 就是引擎坏了 —— ⛔ 不静默当成「没有下游」。
                throw new BusinessException(503, "血缘引擎未就绪，无法计算个体去向")
                        .withHint("请稍后重试；若持续出现请联系管理员检查血缘服务");
            }
            putAll(production, impact.getProductionBatches());
            putAll(finished, impact.getFinishedBatches());
            putAll(shipments, impact.getShipments());
        }

        return SourceIndividualDetailDTO.Downstream.builder()
                .productionBatches(new ArrayList<>(production.values()))
                .finishedBatches(new ArrayList<>(finished.values()))
                .shipments(new ArrayList<>(shipments.values()))
                .build();
    }

    private static void putAll(Map<String, TraceabilityDTO.LineageNode> into,
                               List<TraceabilityDTO.LineageNode> nodes) {
        if (nodes == null) {
            return;
        }
        for (TraceabilityDTO.LineageNode node : nodes) {
            // 内部接口 nodeId 非 null; 万一没有(脏数据), 退到 batchNumber 当键 —— 只影响去重, 不渲染。
            String key = node.getNodeType() + "#"
                    + (node.getNodeId() != null ? node.getNodeId() : "bn:" + node.getBatchNumber());
            into.putIfAbsent(key, node);
        }
    }

    static SourceIndividualDetailDTO.Summary toSummary(SourceIndividual si) {
        return SourceIndividualDetailDTO.Summary.builder()
                .id(si.getId())
                .individualCode(si.getIndividualCode())
                .tagNumber(si.getTagNumber())
                .species(si.getSpecies())
                .breed(si.getBreed())
                .sex(si.getSex())
                .dnaVerificationStatus(si.getDnaVerificationStatus())
                .build();
    }

    static SourceIndividualDetailDTO.BatchSummary toBatchSummary(MaterialBatch mb) {
        return SourceIndividualDetailDTO.BatchSummary.builder()
                .id(mb.getId())
                .batchNumber(mb.getBatchNumber())
                // 与 LineageGraphAssembler 同口径: 物料类型查不到就是 null, 不编名字。
                .materialName(mb.getMaterialType() != null ? mb.getMaterialType().getName() : null)
                .quantity(mb.getReceiptQuantity())
                .unit(mb.getQuantityUnit())
                .receiptDate(mb.getReceiptDate())
                .build();
    }
}
