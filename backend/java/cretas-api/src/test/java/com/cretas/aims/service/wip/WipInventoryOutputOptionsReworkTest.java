package com.cretas.aims.service.wip;

import com.cretas.aims.service.workflow.WorkflowReworkNodes;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WipInventoryOutputOptionsReworkTest {

    /**
     * 金检节点 1 进 2 出: 良品去 X 光, 回料回下一批绞肉。
     * 两个产出端口都必须出现在选项里 —— 少一个操作员就填不了那个数。
     */
    @Test
    void 金检的两个产出端口都要出现且回料被标出() {
        String nodesJson = """
            [
              {"id":"material:semi:good","kind":"SEMI_FINISHED","data":{"name":"金检良品"}},
              {"id":"material:semi:rw","kind":"SEMI_FINISHED","data":{"name":"美式回料","isRework":true}}
            ]
            """;
        Set<String> rework = WorkflowReworkNodes.reworkMaterialNodeIds(nodesJson);

        assertThat(rework)
                .as("回料节点必须被认出来, 否则报工屏会把它当普通半成品")
                .containsExactly("material:semi:rw");
        assertThat(rework)
                .as("良品节点不能被误标成回料 —— 误标会让良品数被写进 defectQuantity")
                .doesNotContain("material:semi:good");
    }

    /**
     * 阴性对照: 副产标记 (isByproduct) 与回料标记 (isRework) 是两个独立的键,
     * 打了副产标记的节点不该被回料判据认领。
     */
    @Test
    void 副产标记不会被回料判据误认() {
        String nodesJson = """
            [{"id":"material:semi:fat","kind":"SEMI_FINISHED","data":{"name":"肥油","isByproduct":true}}]
            """;
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds(nodesJson)).isEmpty();
    }
}
