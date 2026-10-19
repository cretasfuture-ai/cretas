package com.cretas.aims.service.yield;

import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.service.yield.impl.YieldUnitResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class YieldUnitResolverContractTest {
    @Test void outputPlanCannotInventMissingLegacyInputAuthority() {
        assertThat(YieldUnitResolver.resolve(task("kg"), WorkProcess.builder().unit("unitless").build(), List.of(), null, null).valid()).isFalse();
    }

    @Test void explicitLegacyUnitsAndAliasesRemainComparable() {
        for (String[] pair : List.of(new String[]{"克", "g"}, new String[]{"盒", "box"}, new String[]{"箱", "case"}, new String[]{"件", "piece"})) {
            WorkProcess definition = WorkProcess.builder().unit(pair[0]).outputUnit(pair[0]).build();
            assertThat(YieldUnitResolver.resolve(task(pair[0]), definition, List.of(), pair[0], pair[1]).valid()).isTrue();
        }
    }

    @Test void unitlessCannotBeReplacedByRequestAndConflictingPortsFail() {
        assertThat(YieldUnitResolver.resolve(task(null), WorkProcess.builder().unit("unitless").build(), List.of(), "kg", "kg").valid()).isFalse();
        WorkflowTaskPort a = port(7L, WorkflowTaskPort.Direction.INPUT, "kg", "kg", "1");
        WorkflowTaskPort b = port(7L, WorkflowTaskPort.Direction.INPUT, "g", "g", "1");
        assertThat(YieldUnitResolver.resolve(task("kg"), null, List.of(a, b), null, "kg").valid()).isFalse();
    }

    @Test void frozenEightHundredGramBoxHasPhysicalFactorAndConflictingWeightsFail() {
        WorkProcessTask task = task("盒"); task.setWorkflowInstanceId(1L);
        WorkflowTaskPort input = port(7L, WorkflowTaskPort.Direction.INPUT, "kg", "kg", "1");
        WorkflowTaskPort output = port(7L, WorkflowTaskPort.Direction.OUTPUT, "盒", "盒", "1");
        output.setMaterialKind("FINISHED_GOOD"); output.setNetWeightGramsSnapshot(new BigDecimal("800"));
        var projection = YieldUnitResolver.resolve(task, null, List.of(input, output), "kg", "box");
        assertThat(projection.valid()).isTrue();
        assertThat(projection.inputToOutputFactor()).isEqualByComparingTo("1.25");
        WorkflowTaskPort conflicting = port(7L, WorkflowTaskPort.Direction.OUTPUT, "盒", "盒", "1.0");
        conflicting.setMaterialKind("FINISHED_GOOD"); conflicting.setNetWeightGramsSnapshot(new BigDecimal("900"));
        assertThat(YieldUnitResolver.resolve(task, null, List.of(input, output, conflicting), null, null).valid()).isFalse();
        output.setNetWeightGramsSnapshot(null);
        assertThat(YieldUnitResolver.resolve(task, null, List.of(input, output), null, null).valid()).isFalse();
    }

    @Test void immutableCanonicalCodesAllowFrozenCustomDisplayAliasesOnly() {
        WorkProcessTask task = task("成品桶"); task.setWorkflowInstanceId(1L);
        WorkflowTaskPort first = port(7L, WorkflowTaskPort.Direction.INPUT, "投料桶", "kg", "1");
        WorkflowTaskPort second = port(7L, WorkflowTaskPort.Direction.INPUT, "原料桶", "kg", "1");
        WorkflowTaskPort output = port(7L, WorkflowTaskPort.Direction.OUTPUT, "成品桶", "kg", "1");
        first.setUnitCode("kg"); second.setUnitCode("kg"); output.setUnitCode("kg");
        var projection = YieldUnitResolver.resolve(task, null, List.of(first, second, output), "投料桶", "成品桶");
        assertThat(projection.valid()).isTrue();
        assertThat(projection.inputToOutputFactor()).isEqualByComparingTo("1");
        assertThat(YieldUnitResolver.resolve(task, null, List.of(first, second, output), "未知桶", "成品桶").valid()).isFalse();
    }

    @Test void packageConversionRequiresVersionedConsistentOrientationAndNeverConvertsVolumeToMass() {
        WorkProcessTask task = task("case"); task.setWorkflowInstanceId(1L);
        WorkflowTaskPort input = port(7L, WorkflowTaskPort.Direction.INPUT, "kg", "kg", "1");
        WorkflowTaskPort output = port(7L, WorkflowTaskPort.Direction.OUTPUT, "case", "box", "12");
        output.setMaterialKind("FINISHED_GOOD"); output.setNetWeightGramsSnapshot(new BigDecimal("800"));
        assertThat(YieldUnitResolver.resolve(task, null, List.of(input, output), null, null).valid()).isFalse();
        output.setConversionRefId("BOX-CASE-12"); output.setConversionVersion(1L);
        output.setConversionFromUnitCode("case"); output.setConversionToUnitCode("box");
        output.setConversionFactorSnapshot(new BigDecimal("12"));
        var projection = YieldUnitResolver.resolve(task, null, List.of(input, output), null, null);
        assertThat(projection.valid()).isTrue();
        assertThat(projection.inputToOutputFactor().multiply(new BigDecimal("9.6")))
                .isCloseTo(BigDecimal.ONE, org.assertj.core.data.Offset.offset(new BigDecimal("0.000000000000000000000000000001")));
        output.setPortToPrimaryFactorSnapshot(new BigDecimal("11"));
        assertThat(YieldUnitResolver.resolve(task, null, List.of(input, output), null, null).valid()).isFalse();
        output.setPortToPrimaryFactorSnapshot(new BigDecimal("12"));
        input.setUnit("l"); input.setMaterialPrimaryUnitCode("l");
        assertThat(YieldUnitResolver.resolve(task, null, List.of(input, output), null, null).valid()).isFalse();
    }

    private static WorkProcessTask task(String unit) { WorkProcessTask t = new WorkProcessTask(); t.setId(7L); t.setPlannedUnit(unit); return t; }
    private static WorkflowTaskPort port(Long taskId, WorkflowTaskPort.Direction d, String unit, String primary, String factor) {
        WorkflowTaskPort p = new WorkflowTaskPort(); p.setTaskId(taskId); p.setDirection(d); p.setUnit(unit); p.setMaterialPrimaryUnitCode(primary);
        p.setPortToPrimaryFactorSnapshot(new java.math.BigDecimal(factor)); return p;
    }
}
