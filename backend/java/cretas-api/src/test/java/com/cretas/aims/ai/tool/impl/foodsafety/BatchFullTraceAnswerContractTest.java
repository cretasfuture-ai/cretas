package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.dto.traceability.TraceabilityDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 质量追溯数字专员 F: 「从成品批次往回追，用了哪些原料批次、哪些供应商？」
 * 修前: 没给批次号却自己挑了示例批次 B-20260518-A03, 回「0 笔出货…详见 trace 字段」, 答的是出货影响不是原料与供应商。
 */
class BatchFullTraceAnswerContractTest {

    @Test
    void answersRawBatchesAndSuppliersNotShipmentOnly() {
        TraceabilityDTO.MaterialInfo m = new TraceabilityDTO.MaterialInfo();
        m.setBatchNumber("MB-1");
        m.setMaterialName("牛腱");
        m.setSupplierName("甲供应商");
        TraceabilityDTO.FullTraceResponse trace = new TraceabilityDTO.FullTraceResponse();
        trace.setMaterials(List.of(m));

        String text = BatchFullTraceTool.traceMessage("FB-1", trace, 0, 0);

        assertThat(text).contains("用了 1 个原料批次").contains("牛腱（批次 MB-1，供应商 甲供应商）").contains("涉及供应商：甲供应商");
        assertThat(text).doesNotContain("trace 字段").doesNotContain("详见");
    }

    @Test
    void emptyMaterialsIsStatedNotHiddenBehindShipmentCount() {
        TraceabilityDTO.FullTraceResponse trace = new TraceabilityDTO.FullTraceResponse();
        trace.setMaterials(List.of());
        assertThat(BatchFullTraceTool.traceMessage("FB-1", trace, 2, 1)).contains("没有登记投料的原料批次").contains("出货 2 笔");
    }

    @Test
    void schemaDoesNotCarryAnExampleBatchNumberTheModelCouldAdopt() {
        // 阴性对照: 修前描述里的示例批次号被模型当成用户说的批次
        Map<String, Object> schema = new BatchFullTraceTool().getParametersSchema();
        assertThat(String.valueOf(schema)).doesNotContain("B-20260518-A03");
    }
}
