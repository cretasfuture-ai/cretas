package com.cretas.aims.mapper;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔒 2026-09-01 「已入库批次按收货单分组」的分组键必须透出到 DTO。
 *
 * <p>owner 原话:「一次一个单入库…它应该是放在一个组里边」。数据已经在
 * {@code material_batches.source_doc_id} 上（同一次收货共享同一个值），只是
 * {@link MaterialBatchMapper} 此前一直没把它写进 DTO —— 前端拿不到分组键。
 * 本闸守的正是「产出端有 ≠ 消费端收得到」这一类：不报错，只是漏映射一行。</p>
 */
class MaterialBatchMapperSourceDocIdTest {

    private MaterialBatch batch(String sourceDocType, String sourceDocId, String sourceDocNumber) {
        MaterialBatch b = new MaterialBatch();
        b.setId("B-SRC-TEST");
        b.setFactoryId("F006");
        b.setBatchNumber("MT-20260901-TEST");
        b.setSourceDocType(sourceDocType);
        b.setSourceDocId(sourceDocId);
        // sourceDocNumber 是 @Formula (只读, 由 Hibernate 从关联单据计算) —— 单测里直接
        // set 是给 mapper 的映射逻辑喂值, 不代表 Hibernate 真的会算出这个值 (那部分由
        // com.cretas.aims.repository.MaterialBatchSourceDocNumberFormulaTest 用真实 H2 查询验证)。
        b.setSourceDocNumber(sourceDocNumber);
        return b;
    }

    @Test
    @DisplayName("同一次收货的 sourceDocId 必须透出到 DTO —— 前端按它分组")
    void exposesSourceDocId() {
        MaterialBatchDTO dto = MaterialBatchMapperTestFixtures
                .mapperWithRealLocationCatalog()
                .toDTO(batch("PURCHASE_RECEIVE", "87e02e01-3bec-47c1-b371-e2bb39e60b45", "RCV-20260901-6529"));

        assertThat(dto.getSourceDocId())
                .as("sourceDocId 丢了, 前端就没有分组键 —— 三个批次号又会退回散平列表")
                .isEqualTo("87e02e01-3bec-47c1-b371-e2bb39e60b45");
        assertThat(dto.getSourceDocNumber())
                .as("收货单号丢了, 分组表头就只能露 UUID 给用户")
                .isEqualTo("RCV-20260901-6529");
    }

    @Test
    @DisplayName("阳性对照: 两个不同批次的 sourceDocId 如实透出不同值, 不是恒定映射")
    void distinctBatchesKeepDistinctSourceDocIds() {
        MaterialBatchMapper mapper = MaterialBatchMapperTestFixtures.mapperWithRealLocationCatalog();

        MaterialBatchDTO first = mapper.toDTO(
                batch("PURCHASE_RECEIVE", "87e02e01-3bec-47c1-b371-e2bb39e60b45", "RCV-20260901-6529"));
        MaterialBatchDTO second = mapper.toDTO(
                batch("PURCHASE_RECEIVE", "5c7c71b0-4cc6-4267-8c03-e221c7f12c45", "RCV-20260901-6530"));

        assertThat(first.getSourceDocId()).isNotEqualTo(second.getSourceDocId());
        assertThat(first.getSourceDocNumber()).isNotEqualTo(second.getSourceDocNumber());
    }

    @Test
    @DisplayName("没有来源单据的批次: sourceDocId 如实为 null, 不伪造成一个假分组键")
    void nullSourceDocIdStaysNull() {
        MaterialBatchDTO dto = MaterialBatchMapperTestFixtures
                .mapperWithRealLocationCatalog()
                .toDTO(batch(null, null, null));

        assertThat(dto.getSourceDocId()).isNull();
        assertThat(dto.getSourceDocNumber()).isNull();
    }
}
