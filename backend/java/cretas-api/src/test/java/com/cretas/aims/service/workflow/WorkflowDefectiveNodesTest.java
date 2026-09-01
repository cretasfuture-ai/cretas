package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不良品判据类。与 {@link WorkflowReworkNodesTest} 同构 —— 三个标记同一套解析纪律。
 *
 * <p>⚠️ 本类是 owner 2026-09-01「好的区别一下吧，补吧」的产物：在它之前，报工端下发的
 * 产出项里没有任何字段能把不良品和普通半成品产出分开。
 */
class WorkflowDefectiveNodesTest {

    @Test
    void 布尔与字符串两种真值都认() {
        // nodes_json 是 jsonb, 历史快照里标记既可能是布尔也可能是字符串。
        // 只认 Boolean.TRUE 会让存量不良品静默退化成普通半成品 —— 那正是本类要防的形状。
        assertThat(WorkflowDefectiveNodes.isDefectiveFlag(Boolean.TRUE)).isTrue();
        assertThat(WorkflowDefectiveNodes.isDefectiveFlag("true")).isTrue();
        assertThat(WorkflowDefectiveNodes.isDefectiveFlag("TRUE")).isTrue();
        assertThat(WorkflowDefectiveNodes.isDefectiveFlag(Boolean.FALSE)).isFalse();
        assertThat(WorkflowDefectiveNodes.isDefectiveFlag(null)).isFalse();
        assertThat(WorkflowDefectiveNodes.isDefectiveFlag("yes")).isFalse();
    }

    @Test
    void 从快照里挑出被标记为不良品的物料节点() {
        String nodesJson = """
            [
              {"id":"material:semi:good","kind":"SEMI_FINISHED","data":{"name":"金检良品"}},
              {"id":"material:semi:def","kind":"SEMI_FINISHED","data":{"name":"金检剔出","isDefective":true}},
              {"id":"material:semi:def2","kind":"SEMI_FINISHED","data":{"name":"X光剔出","isDefective":"true"}}
            ]
            """;
        assertThat(WorkflowDefectiveNodes.defectiveMaterialNodeIds(nodesJson))
                .containsExactlyInAnyOrder("material:semi:def", "material:semi:def2");
    }

    @Test
    void 三个标记互不串味() {
        // 🔴 阴性对照，本类最要紧的一条：副产/回料/不良品是三个独立的键。
        // 少了它，一个把 isByproduct 也算进来的实现照样能让上面那条绿 ——
        // 而那会让副产端口被当成不良品，一路错到报工界面上的角色 tag。
        String nodesJson = """
            [
              {"id":"byp","data":{"name":"肥油","isByproduct":true}},
              {"id":"rew","data":{"name":"回料","isRework":true}},
              {"id":"def","data":{"name":"剔出待复查","isDefective":true}}
            ]
            """;
        assertThat(WorkflowDefectiveNodes.defectiveMaterialNodeIds(nodesJson))
                .containsExactly("def");
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds(nodesJson))
                .containsExactly("rew");
        assertThat(WorkflowByproductNodes.byproductMaterialNodeIds(nodesJson))
                .containsExactly("byp");
    }

    @Test
    void 从同一权威标记取不良品SKU而不按名称猜() {
        String nodesJson = """
            [
              {"id":"good","data":{"name":"名字里也有不良字样但未标记","skuId":"GOOD"}},
              {"id":"def","data":{"name":"任意名称","skuId":"DEF-1","isDefective":true}},
              {"id":"legacy","data":{"skuId":"DEF-2","isDefective":"TRUE"}}
            ]
            """;
        assertThat(WorkflowDefectiveNodes.defectiveProductTypeIds(nodesJson))
                .containsExactlyInAnyOrder("DEF-1", "DEF-2");
    }

    @Test
    void 空与坏输入一律空集合而不是抛异常() {
        assertThat(WorkflowDefectiveNodes.defectiveMaterialNodeIds(null)).isEmpty();
        assertThat(WorkflowDefectiveNodes.defectiveMaterialNodeIds("")).isEmpty();
        assertThat(WorkflowDefectiveNodes.defectiveMaterialNodeIds("{not json")).isEmpty();
        // 不是数组的合法 JSON 也要空集合 —— 快照结构变了不该让报工屏 500。
        assertThat(WorkflowDefectiveNodes.defectiveMaterialNodeIds("{\"nodes\":[]}")).isEmpty();
    }
}
