package com.cretas.aims.dto.yield;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨端契约闸: RN 报工屏按字面名 `isRework` 读这个字段。
 *
 * <p>⚠️ 这条断言存在的理由是一个**静默**的失败模式: 若字段类型从 Boolean 改成 primitive
 * boolean, Lombok 生成 isRework() 而非 getIsRework(), Jackson 剥掉 is 前缀 → JSON 键变成
 * "rework" → RN 把每个产出都当非回料, 「不良品」栏永远不显示, 而所有其它单测照样绿。
 */
class OutputOptionItemJsonContractTest {

    @Test
    void 序列化出来的键必须恰好是_isRework() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                OutputOptionsResponse.OutputOptionItem.builder()
                        .taskId(1L).processName("金检").semiCode("PTSEMI-LSM-0003")
                        .processOrder(9).isRework(Boolean.TRUE).build());

        assertThat(json).contains("\"isRework\":true");
        assertThat(json).doesNotContain("\"rework\":");   // 阴性对照: 前缀被剥掉就是这个样子
    }
}
