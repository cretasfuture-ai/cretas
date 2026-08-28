package com.cretas.aims.service.workflow;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowReworkNodesTest {

    @Test
    void 布尔与字符串两种真值都认() {
        // nodes_json 是 jsonb, 历史快照里这个标记既出现过布尔也出现过字符串。
        // 只认 Boolean.TRUE 会让存量回料静默退化成普通半成品 —— 那正是本类要防的缺陷形状。
        assertThat(WorkflowReworkNodes.isReworkFlag(Boolean.TRUE)).isTrue();
        assertThat(WorkflowReworkNodes.isReworkFlag("true")).isTrue();
        assertThat(WorkflowReworkNodes.isReworkFlag("TRUE")).isTrue();
        assertThat(WorkflowReworkNodes.isReworkFlag(Boolean.FALSE)).isFalse();
        assertThat(WorkflowReworkNodes.isReworkFlag(null)).isFalse();
        assertThat(WorkflowReworkNodes.isReworkFlag("yes")).isFalse();
    }

    @Test
    void 从快照里挑出被标记为回料的物料节点() {
        String nodesJson = """
            [
              {"id":"material:semi:good","kind":"SEMI_FINISHED","data":{"name":"金检良品"}},
              {"id":"material:semi:rework","kind":"SEMI_FINISHED","data":{"name":"美式回料","isRework":true}},
              {"id":"material:semi:rework2","kind":"SEMI_FINISHED","data":{"name":"熟肉饼回料","isRework":"true"}}
            ]
            """;
        Set<String> ids = WorkflowReworkNodes.reworkMaterialNodeIds(nodesJson);
        assertThat(ids).containsExactlyInAnyOrder("material:semi:rework", "material:semi:rework2");
    }

    @Test
    void 从同一权威标记取回料SKU而不按名称猜() {
        String nodesJson = """
            [
              {"id":"good","data":{"name":"名字里也有回料字样但未标记","skuId":"GOOD"}},
              {"id":"rework","data":{"name":"任意名称","skuId":"REWORK-1","isRework":true}},
              {"id":"legacy","data":{"skuId":"REWORK-2","isRework":"TRUE"}},
              {"id":"unbound","data":{"isRework":true}}
            ]
            """;

        assertThat(WorkflowReworkNodes.reworkProductTypeIds(nodesJson))
                .containsExactlyInAnyOrder("REWORK-1", "REWORK-2");
    }

    @Test
    void 解析失败返回空集合而不是抛异常() {
        // 一个 JSON 解析问题不该让整张报工单打不开 —— 与 WorkflowByproductNodes 同口径。
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds("{ not json")).isEmpty();
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds(null)).isEmpty();
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds("")).isEmpty();
        assertThat(WorkflowReworkNodes.reworkMaterialNodeIds("{\"a\":1}")).isEmpty();
        assertThat(WorkflowReworkNodes.reworkProductTypeIds("{ not json")).isEmpty();
    }
}
