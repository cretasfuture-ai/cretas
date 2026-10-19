package com.cretas.aims.service.yield.impl;

import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Quantity projection from immutable runtime ports. No current SKU or BOM lookup. */
public final class YieldUnitResolver {
    private static final MathContext CONTEXT = MathContext.DECIMAL128;

    private YieldUnitResolver() {}

    public record Projection(String inputUnit, String outputUnit, BigDecimal inputToOutputFactor, String error) {
        public boolean valid() { return error == null; }
    }

    public static Projection resolve(WorkProcessTask task, WorkProcess legacy, List<WorkflowTaskPort> ports,
                                     String requestedInput, String requestedOutput) {
        List<WorkflowTaskPort> own = ports == null ? List.of() : ports.stream()
                .filter(port -> Objects.equals(task.getId(), port.getTaskId())).toList();
        if (task.getWorkflowInstanceId() != null && own.isEmpty()) {
            return invalid("工作流实例缺少当前任务端口快照");
        }
        if (own.isEmpty()) {
            // Pre-workflow tasks have an explicit old input-unit contract. A planned
            // finished quantity or the request itself cannot supply a missing unit.
            String input = legacy == null ? null : legacy.getUnit();
            String output = legacy == null ? null : usable(legacy.getOutputUnit())
                    ? legacy.getOutputUnit() : legacy.getUnit();
            return project(input, output, List.of(), List.of(), requestedInput, requestedOutput);
        }
        List<WorkflowTaskPort> inputs = own.stream()
                .filter(port -> port.getDirection() == WorkflowTaskPort.Direction.INPUT).toList();
        List<WorkflowTaskPort> outputs = own.stream()
                .filter(port -> port.getDirection() == WorkflowTaskPort.Direction.OUTPUT).toList();
        String input = commonUnit(inputs) == null ? null : canonicalPortUnit(inputs.getFirst());
        String output = commonUnit(outputs);
        if (!usable(input) || !usable(output)) {
            return invalid("任务存在缺失或多个不一致的投入/产出端口单位快照");
        }
        if (usable(task.getPlannedUnit()) && outputs.stream().noneMatch(port -> matchesUnit(port, task.getPlannedUnit()))) {
            return invalid("任务 plannedUnit 与产出端口单位冲突");
        }
        return project(input, output, inputs, outputs, requestedInput, requestedOutput);
    }

    private static Projection project(String input, String output,
                                      List<WorkflowTaskPort> inputs, List<WorkflowTaskPort> outputs,
                                      String requestedInput, String requestedOutput) {
        if (!usable(input) || !usable(output)) return invalid("缺少可用的投入或产出单位快照");
        if (requestedInput != null && !(inputs.isEmpty() ? same(requestedInput, input)
                : inputs.stream().anyMatch(port -> matchesUnit(port, requestedInput)))) {
            return invalid("报工投入单位与任务快照不一致");
        }
        if (requestedOutput != null && !(outputs.isEmpty() ? same(requestedOutput, output)
                : outputs.stream().anyMatch(port -> matchesUnit(port, requestedOutput)))) {
            return invalid("报工产出单位与任务快照不一致");
        }
        String inputCode = inputs.isEmpty() ? canonical(input) : canonicalPortUnit(inputs.getFirst());
        String outputCode = outputs.isEmpty() ? canonical(output) : canonicalPortUnit(outputs.getFirst());
        if (same(inputCode, outputCode)) return new Projection(input, output, BigDecimal.ONE, null);
        // Scientific conversions share the existing unit contract. In particular,
        // volume must never borrow a product's mass to become a package quantity.
        BigDecimal scientific = FgQuantityUnitConverter.convert(BigDecimal.ONE,
                inputCode, outputCode, null);
        if (positive(scientific)) return new Projection(input, output, scientific, null);

        BigDecimal inputGrams = commonGrams(inputs);
        BigDecimal outputGrams = commonGrams(outputs);
        if (!positive(inputGrams) || !positive(outputGrams)) {
            return invalid("投入与产出单位不同，缺少一致且可追溯的质量/包装换算快照");
        }
        return new Projection(input, output, inputGrams.divide(outputGrams, CONTEXT), null);
    }

    private static String commonUnit(List<WorkflowTaskPort> ports) {
        if (ports.isEmpty() || !usable(ports.getFirst().getUnit())) return null;
        String unit = ports.getFirst().getUnit();
        String code = canonicalPortUnit(ports.getFirst());
        return usable(code) && ports.stream().allMatch(port -> usable(port.getUnit())
                && Objects.equals(code, canonicalPortUnit(port))) ? unit : null;
    }

    /** Prefer the immutable canonical code; old manually constructed tasks may only have display units. */
    public static String canonicalPortUnit(WorkflowTaskPort port) {
        return canonical(usable(port.getUnitCode()) ? port.getUnitCode() : port.getUnit());
    }

    public static boolean matchesUnit(WorkflowTaskPort port, String requestedUnit) {
        // The exact frozen display is an explicit alias bound to this port's code.
        // Do not infer further aliases from a custom display or a live unit catalog.
        Set<String> frozenAliases = usable(port.getUnit()) ? Set.of(port.getUnit()) : Set.of();
        return usable(requestedUnit) && (frozenAliases.contains(requestedUnit.trim())
                || same(requestedUnit, canonicalPortUnit(port)));
    }

    private static BigDecimal commonGrams(List<WorkflowTaskPort> ports) {
        BigDecimal common = null;
        for (WorkflowTaskPort port : ports) {
            BigDecimal grams = gramsPerPortUnit(port);
            if (!positive(grams) || common != null && common.compareTo(grams) != 0) return null;
            common = grams;
        }
        return common;
    }

    private static BigDecimal gramsPerPortUnit(WorkflowTaskPort port) {
        String unit = canonicalPortUnit(port);
        BigDecimal scientific = FgQuantityUnitConverter.convert(BigDecimal.ONE, unit, "g", null);
        if (positive(scientific)) return scientific;

        String primary = canonical(port.getMaterialPrimaryUnitCode());
        BigDecimal toPrimary = orientedFactor(port, unit, primary);
        if (!positive(toPrimary)) return null;
        // A custom raw-material package may have a frozen conversion to mass.
        BigDecimal primaryMass = FgQuantityUnitConverter.convert(toPrimary, primary, "g", null);
        if (positive(primaryMass)) return primaryMass;
        if (!"FINISHED_GOOD".equals(port.getMaterialKind())
                || !positive(port.getNetWeightGramsSnapshot())) return null;
        return FgQuantityUnitConverter.convert(toPrimary, primary, "g", port.getNetWeightGramsSnapshot());
    }

    private static BigDecimal orientedFactor(WorkflowTaskPort port, String unit, String primary) {
        if (!usable(primary)) return null;
        BigDecimal oriented = port.getPortToPrimaryFactorSnapshot();
        if (same(unit, primary) && port.getConversionRefId() == null) {
            return oriented == null || oriented.compareTo(BigDecimal.ONE) == 0 ? BigDecimal.ONE : null;
        }
        if (port.getConversionRefId() == null || port.getConversionRefId().isBlank()
                || port.getConversionVersion() == null || port.getConversionVersion() <= 0
                || !positive(port.getConversionFactorSnapshot()) || !positive(oriented)) return null;
        String from = canonical(port.getConversionFromUnitCode());
        String to = canonical(port.getConversionToUnitCode());
        BigDecimal expected;
        if (same(from, unit) && same(to, primary)) expected = port.getConversionFactorSnapshot();
        else if (same(from, primary) && same(to, unit)) {
            expected = BigDecimal.ONE.divide(port.getConversionFactorSnapshot(), 8, RoundingMode.HALF_UP);
        } else return null;
        return expected.compareTo(oriented) == 0 ? oriented : null;
    }

    private static String canonical(String unit) { return UnitContractServiceImpl.crossLanguageCode(unit); }
    private static boolean same(String left, String right) {
        return usable(left) && usable(right) && Objects.equals(canonical(left), canonical(right));
    }
    private static boolean usable(String unit) {
        return unit != null && !unit.isBlank() && !"unitless".equalsIgnoreCase(unit.trim());
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static Projection invalid(String message) { return new Projection(null, null, null, message); }
}
