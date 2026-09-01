package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.PortDescriptor;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO.ProcessDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联产图(一根共用前段 → 两条线)里, 中间道要能说出「我最终流向哪个成品」。
 *
 * <p>守的行为: 2026-09-02 本机 UAT 实拍 —— 非默认线的中间道辅料**一克不扣**, 因为原逻辑
 * 拿计划级唯一的 selectedBomRecipeId 的终端去比 OUTPUT_EXCLUSIVE scope。
 * 阴性对照两条: 走不到成品 → 空集(⛔ 不兜底成全部终端); 图上没这道 → 空集。
 */
class WorkflowSheetDownstreamTest {

    private static PortDescriptor port(String node, boolean finished, String sku) {
        return PortDescriptor.builder().materialNodeId(node).finished(finished).skuId(sku).build();
    }

    private static ProcessDescriptor proc(String id, List<PortDescriptor> in, List<PortDescriptor> out) {
        return ProcessDescriptor.builder().workflowNodeId(id).inputs(in).outputs(out).build();
    }

    /** raw → p:thaw → m:thaw ─┬→ p:a:0 → m:a:0 → p:a:1 → m:a:fin(成品A)
     *                          └→ p:b:0 → m:b:0 → p:b:1 → m:b:fin(成品B) + m:b:rework(半成品) */
    private static WorkflowClerkSheetConfigDTO joint() {
        return WorkflowClerkSheetConfigDTO.builder().processes(List.of(
                proc("p:thaw", List.of(port("m:raw", false, null)), List.of(port("m:thaw", false, null))),
                proc("p:a:0", List.of(port("m:thaw", false, null)), List.of(port("m:a:0", false, null))),
                proc("p:a:1", List.of(port("m:a:0", false, null)), List.of(port("m:a:fin", true, "SKU-A"))),
                proc("p:b:0", List.of(port("m:thaw", false, null)), List.of(port("m:b:0", false, null))),
                proc("p:b:1", List.of(port("m:b:0", false, null)),
                        List.of(port("m:b:fin", true, "SKU-B"), port("m:b:rework", false, null)))
        )).build();
    }

    private static Set<String> nodes(Set<WorkflowSheetDownstream.Terminal> t) {
        return t.stream().map(WorkflowSheetDownstream.Terminal::materialNodeId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("B 线中间道只流向成品 B —— 不会把 A 线的终端算进来")
    void middleStepOfLineBReachesOnlyB() {
        Set<WorkflowSheetDownstream.Terminal> t = WorkflowSheetDownstream.reachableTerminals(joint(), "p:b:0");
        assertThat(nodes(t)).containsExactly("m:b:fin");
        assertThat(t).extracting(WorkflowSheetDownstream.Terminal::skuId).containsExactly("SKU-B");
    }

    @Test
    @DisplayName("共用前段(拆包缓化)流向两条线的成品")
    void sharedRootReachesBoth() {
        assertThat(nodes(WorkflowSheetDownstream.reachableTerminals(joint(), "p:thaw")))
                .containsExactlyInAnyOrder("m:a:fin", "m:b:fin");
    }

    @Test
    @DisplayName("阴性对照: 图上没有这道 → 空集, ⛔ 不兜底成全部终端")
    void unknownNodeIsEmpty() {
        assertThat(WorkflowSheetDownstream.reachableTerminals(joint(), "p:nope")).isEmpty();
    }

    @Test
    @DisplayName("阴性对照: 下游没有成品(只有半成品)→ 空集")
    void noFinishedDownstreamIsEmpty() {
        WorkflowClerkSheetConfigDTO cfg = WorkflowClerkSheetConfigDTO.builder().processes(List.of(
                proc("p:x", List.of(port("m:raw", false, null)), List.of(port("m:x", false, null))))).build();
        assertThat(WorkflowSheetDownstream.reachableTerminals(cfg, "p:x")).isEmpty();
    }
}
