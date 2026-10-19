package com.cretas.aims.service.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.cretas.aims.dto.production.ProductionSettlementRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves verified server side cost ratios for reported multi output batches. */
@Service
public class ReportedOutputCostAllocationService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal EPSILON = new BigDecimal("0.000001");
    private static final MathContext MC = MathContext.DECIMAL128;

    private final ProcessSheetRowRepository rowRepository;
    private final ProductionBatchRepository batchRepository;
    private final ObjectMapper objectMapper;

    public ReportedOutputCostAllocationService(ProcessSheetRowRepository rowRepository,
                                                ProductionBatchRepository batchRepository,
                                                ObjectMapper objectMapper) {
        this.rowRepository = rowRepository;
        this.batchRepository = batchRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, BigDecimal> resolve(
            String factoryId,
            String planId,
            List<ProductionSettlementRequest.OutputLine> outputs) {
        if (factoryId == null || planId == null || outputs == null || outputs.isEmpty()) {
            throw unavailable("缺少可核验的多产出结单行");
        }
        List<ProcessSheetRow> rows = rowRepository.findByFactoryIdAndPlanId(factoryId, planId);
        if (rows == null || rows.isEmpty()) {
            throw unavailable("计划没有可核验的报工产出行");
        }

        Set<Long> usedRows = new HashSet<>();
        Set<Long> usedBatches = new HashSet<>();
        Map<String, BigDecimal> costBySku = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ProductionSettlementRequest.OutputLine output : outputs) {
            validateOutput(output);
            ProcessSheetRow row = findUniqueRow(factoryId, planId, output, rows, usedRows, usedBatches);
            JsonNode payload = payload(row);
            BigDecimal allocatedCost = decimal(payload, "allocatedCost");
            BigDecimal ratio = decimal(payload, "costAllocationRatio");
            String basis = text(payload, "costAllocationBasis");
            if (allocatedCost == null || allocatedCost.signum() < 0
                    || ratio == null || ratio.signum() <= 0
                    || !Set.of("MASS", "QUANTITY", "EXPLICIT_RATIO").contains(basis)
                    || !matchesBatchCost(factoryId, planId, row, allocatedCost)) {
                throw unavailable("报工产出成本分摊凭证无效");
            }
            total = total.add(allocatedCost, MC);
            costBySku.merge(output.getProductTypeId(), allocatedCost, (a, b) -> a.add(b, MC));
        }
        if (total.signum() <= 0) {
            throw unavailable("报工产出成本总额为零，不能生成分摊比例");
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        String lastSku = null;
        BigDecimal assigned = BigDecimal.ZERO;
        for (String sku : costBySku.keySet()) {
            lastSku = sku;
        }
        for (Map.Entry<String, BigDecimal> entry : costBySku.entrySet()) {
            BigDecimal percentage;
            if (entry.getKey().equals(lastSku)) {
                percentage = HUNDRED.subtract(assigned, MC);
            } else {
                percentage = entry.getValue().divide(total, MC).multiply(HUNDRED, MC)
                        .setScale(6, java.math.RoundingMode.HALF_UP);
                assigned = assigned.add(percentage, MC);
            }
            if (percentage.signum() <= 0) {
                throw unavailable("报工产出成本分摊比例必须大于零");
            }
            result.put(entry.getKey(), percentage);
        }
        return result;
    }

    private ProcessSheetRow findUniqueRow(String factoryId, String planId,
                                           ProductionSettlementRequest.OutputLine output,
                                           List<ProcessSheetRow> rows, Set<Long> usedRows,
                                           Set<Long> usedBatches) {
        List<ProcessSheetRow> matches = new ArrayList<>();
        for (ProcessSheetRow row : rows) {
            if (!factoryId.equals(row.getFactoryId()) || !planId.equals(row.getPlanId())
                    || !ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus())
                    || row.getBatchId() == null || usedRows.contains(row.getId())
                    || usedBatches.contains(row.getBatchId())
                    || !same(row.getBatchNumber(), output.getBatchNumber())) {
                continue;
            }
            JsonNode payload = payload(row);
            if (!payload.path("multiOutputMember").asBoolean(false)
                    || !payload.path("finished").asBoolean(false)
                    || text(payload, "multiOutputBaseRowId") == null
                    && text(payload, "baseGroup") == null
                    || !same(text(payload, "productTypeId"), output.getProductTypeId())
                    || !sameOptional(text(payload, "batchNumber"), output.getBatchNumber())
                    || !sameUnit(text(payload, "outputUnit", "unit"), output.getUnit())
                    || !sameDecimal(decimal(payload, "outputQuantity"), output.getQuantity())
                    || !same(text(payload, "materialNodeId"), output.getMaterialNodeId())
                    || !same(text(payload, "workflowPortId"), output.getWorkflowPortId())) {
                continue;
            }
            ProductionBatch batch = batchRepository.findByIdAndFactoryId(row.getBatchId(), factoryId)
                    .orElseThrow(() -> unavailable("报工批次不存在或不属于当前工厂"));
            if (batch.getStatus() == com.cretas.aims.entity.enums.ProductionBatchStatus.CANCELLED
                    || !factoryId.equals(batch.getFactoryId())
                    || !planId.equals(batch.getProductionPlanId())
                    || !same(batch.getProductTypeId(), output.getProductTypeId())
                    || !same(batch.getBatchNumber(), output.getBatchNumber())) {
                continue;
            }
            matches.add(row);
        }
        if (matches.size() != 1) {
            throw unavailable(matches.isEmpty() ? "找不到唯一的已提交多产出报工行" : "多产出报工行重复，不能确定成本凭证");
        }
        usedRows.add(matches.getFirst().getId());
        usedBatches.add(matches.getFirst().getBatchId());
        return matches.getFirst();
    }

    private boolean matchesBatchCost(String factoryId, String planId, ProcessSheetRow row, BigDecimal allocatedCost) {
        ProductionBatch batch = batchRepository.findByIdAndFactoryId(row.getBatchId(), factoryId)
                .orElseThrow(() -> unavailable("报工批次不存在或不属于当前工厂"));
        return planId.equals(batch.getProductionPlanId()) && batch.getTotalCost() != null
                && batch.getTotalCost().subtract(allocatedCost).abs().compareTo(EPSILON) <= 0;
    }

    private JsonNode payload(ProcessSheetRow row) {
        try {
            if (row.getRowPayload() == null || row.getRowPayload().isBlank()) {
                throw unavailable("报工行 JSON 为空");
            }
            JsonNode parsed = objectMapper.readTree(row.getRowPayload());
            if (parsed == null || !parsed.isObject()) {
                throw unavailable("报工行 JSON 必须是对象");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw unavailable("报工行 JSON 无法解析");
        }
    }

    private static void validateOutput(ProductionSettlementRequest.OutputLine output) {
        if (output == null || blank(output.getProductTypeId()) || blank(output.getBatchNumber())
                || blank(output.getUnit()) || output.getQuantity() == null
                || output.getQuantity().signum() <= 0 || blank(output.getMaterialNodeId())
                || blank(output.getWorkflowPortId())) {
            throw unavailable("结单产出身份或数量不完整");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isNumber() && !value.isTextual()
                ? null : parseDecimal(value.asText());
    }

    private static BigDecimal parseDecimal(String value) {
        try { return new BigDecimal(value); } catch (RuntimeException e) { return null; }
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean sameOptional(String persisted, String expected) {
        return persisted == null || same(persisted, expected);
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean sameUnit(String left, String right) {
        if (same(left, right)) return true;
        Optional<CanonicalUnit> a = UnitContractServiceImpl.describeBuiltIn(left);
        Optional<CanonicalUnit> b = UnitContractServiceImpl.describeBuiltIn(right);
        return a.isPresent() && b.isPresent() && a.get().code().equals(b.get().code());
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(409, message)
                .withCode("REPORTED_OUTPUT_COST_ALLOCATION_UNAVAILABLE")
                .withHint("请由管理员核对该计划的报工组、全部产出和成本分摊凭据，确认记录完整且相互对应后再结单；不要把未知成本填写为零");
    }
}
