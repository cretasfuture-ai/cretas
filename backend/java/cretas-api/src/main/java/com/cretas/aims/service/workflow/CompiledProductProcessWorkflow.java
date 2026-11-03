package com.cretas.aims.service.workflow;

import java.math.BigDecimal;
import java.util.List;

public record CompiledProductProcessWorkflow(
        String nodesJson,
        String edgesJson,
        List<CompiledTask> processTasks,
        List<CompiledPort> ports) {

    public CompiledProductProcessWorkflow {
        processTasks = List.copyOf(processTasks);
        ports = List.copyOf(ports);
    }

    public List<CompiledTask> allProcessTasks() {
        return processTasks;
    }

    public List<CompiledTask> reportableTasks() {
        return processTasks.stream()
                .filter(CompiledTask::reportingRequired)
                .toList();
    }

    public List<CompiledPort> portsFor(String workflowNodeId) {
        return ports.stream()
                .filter(port -> port.workflowNodeId().equals(workflowNodeId))
                .toList();
    }

    public record CompiledTask(
            String workflowNodeId,
            String workProcessId,
            int processOrder,
            String plannedUnit,
            Integer estimatedMinutes,
            boolean reportingRequired) {
    }

    public record CompiledPort(
            String workflowNodeId,
            String workflowPortId,
            String direction,
            int ordinal,
            String materialNodeId,
            String materialKind,
            String skuId,
            String unit,
            String materialPrimaryUnitCode,
            String conversionRefId,
            Long conversionVersion,
            BigDecimal conversionFactorSnapshot,
            boolean required,
            String conversionMode,
            String conversionExpression,
            BigDecimal standardQuantity,
            String quantityMode,
            String selectionGroupId,
            String selectionGroupLabel,
            String selectionGroupMode,
            Integer selectionGroupMinSelections,
            Integer selectionGroupMaxSelections,
            /** 主原料份额(%), 仅 INPUT 端口有意义; null = 不参与推算。 */
            BigDecimal inputShareRatio,
            /** 固定比例成本分摊份额(%), 仅 OUTPUT 端口且工序选「固定比例」时非 null (食品 DAG 缺口 10)。 */
            BigDecimal fixedCostShare) {

        /** 2026-11-03 之前的规范构造器(23 参): 固定比例份额记 null (= 按重量)。 */
        public CompiledPort(
                String workflowNodeId, String workflowPortId, String direction, int ordinal,
                String materialNodeId, String materialKind, String skuId, String unit,
                String materialPrimaryUnitCode, String conversionRefId, Long conversionVersion,
                BigDecimal conversionFactorSnapshot, boolean required, String conversionMode,
                String conversionExpression, BigDecimal standardQuantity, String quantityMode,
                String selectionGroupId, String selectionGroupLabel, String selectionGroupMode,
                Integer selectionGroupMinSelections, Integer selectionGroupMaxSelections,
                BigDecimal inputShareRatio) {
            this(workflowNodeId, workflowPortId, direction, ordinal, materialNodeId, materialKind,
                    skuId, unit, materialPrimaryUnitCode, conversionRefId, conversionVersion,
                    conversionFactorSnapshot, required, conversionMode, conversionExpression,
                    standardQuantity, quantityMode, selectionGroupId, selectionGroupLabel,
                    selectionGroupMode, selectionGroupMinSelections, selectionGroupMaxSelections,
                    inputShareRatio, null);
        }

        /** 2026-09-02 之前的规范构造器(22 参): 既有测试与老调用点用它, 份额记 null。 */
        public CompiledPort(
                String workflowNodeId, String workflowPortId, String direction, int ordinal,
                String materialNodeId, String materialKind, String skuId, String unit,
                String materialPrimaryUnitCode, String conversionRefId, Long conversionVersion,
                BigDecimal conversionFactorSnapshot, boolean required, String conversionMode,
                String conversionExpression, BigDecimal standardQuantity, String quantityMode,
                String selectionGroupId, String selectionGroupLabel, String selectionGroupMode,
                Integer selectionGroupMinSelections, Integer selectionGroupMaxSelections) {
            this(workflowNodeId, workflowPortId, direction, ordinal, materialNodeId, materialKind,
                    skuId, unit, materialPrimaryUnitCode, conversionRefId, conversionVersion,
                    conversionFactorSnapshot, required, conversionMode, conversionExpression,
                    standardQuantity, quantityMode, selectionGroupId, selectionGroupLabel,
                    selectionGroupMode, selectionGroupMinSelections, selectionGroupMaxSelections, null);
        }

        public CompiledPort(
                String workflowNodeId,
                String workflowPortId,
                String direction,
                int ordinal,
                String materialNodeId,
                String materialKind,
                String skuId,
                String unit,
                String materialPrimaryUnitCode,
                String conversionRefId,
                Long conversionVersion,
                BigDecimal conversionFactorSnapshot,
                boolean required,
                String conversionMode,
                String conversionExpression,
                BigDecimal standardQuantity,
                String quantityMode) {
            this(workflowNodeId, workflowPortId, direction, ordinal, materialNodeId, materialKind,
                    skuId, unit, materialPrimaryUnitCode, conversionRefId, conversionVersion,
                    conversionFactorSnapshot, required, conversionMode, conversionExpression,
                    standardQuantity, quantityMode, null, null, null, null, null, null);
        }

        public CompiledPort(
                String workflowNodeId,
                String workflowPortId,
                String direction,
                int ordinal,
                String materialNodeId,
                String materialKind,
                String skuId,
                String unit,
                String materialPrimaryUnitCode,
                String conversionRefId,
                Long conversionVersion,
                BigDecimal conversionFactorSnapshot,
                boolean required,
                String conversionMode,
                String conversionExpression) {
            this(workflowNodeId, workflowPortId, direction, ordinal, materialNodeId, materialKind,
                    skuId, unit, materialPrimaryUnitCode, conversionRefId, conversionVersion,
                    conversionFactorSnapshot, required, conversionMode, conversionExpression,
                    null, null, null, null, null, null, null, null);
        }

        public CompiledPort(
                String workflowNodeId,
                String workflowPortId,
                String direction,
                int ordinal,
                String materialNodeId,
                String materialKind,
                String skuId,
                String unit,
                boolean required,
                String conversionMode,
                String conversionExpression) {
            this(workflowNodeId, workflowPortId, direction, ordinal, materialNodeId, materialKind,
                    skuId, unit, unit, null, null, null, required, conversionMode,
                    conversionExpression, null, null, null, null, null, null, null, null);
        }
    }
}
