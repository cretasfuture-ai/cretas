package com.cretas.aims.mapper;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.service.warehouse.WarehouseLocationCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code MaterialBatch.sourceIndividualId} 的<b>接线</b>闸 —— 形态 B(机制在, 没接上)。
 *
 * <h3>它守什么</h3>
 * 实体上加一个字段 + 迁移里加一列, 这两件事都不会让这个值出现在 API 响应里。
 * 中间还有三段线: {@code CreateRequest → Entity}、{@code UpdateRequest → Entity}、
 * {@code Entity → DTO}。<b>任何一段没接, 表现都是「字段一直是 null」</b> ——
 * 而 null 对一个可空字段来说完全正常, 不会有任何东西报错。
 *
 * <h3>为什么只测 mapper 就够覆盖读侧</h3>
 * 硬约束 8 先数过: 全仓 {@code MaterialBatchDTO.builder()} 只有<b>一处</b>
 * (BEFORE=1 / AFTER=1, 就在 {@link MaterialBatchMapper#toDTO}) ⇒ 读侧只有这一条投影路径。
 * ⚠️ 如果哪天有人又手写一处 DTO 构造, 这道闸看不见它 —— 那时该做的是把那处并回 mapper,
 * ⛔ 不是在这里再加一条断言。
 *
 * <p>⚠️ {@code new MaterialBatch()} 全仓有 10 处(领料/报工/到货通知/期初/采购/调拨/
 * 工单录入/工序单/mapper), 本 PR <b>刻意只在 mapper 这一处</b>接线:
 * 其余 9 处的入参里根本没有「源头个体」这个概念(它们建的是领料、调拨、期初这类批次)。
 * 这 9 处属于「故意不写」, 已在 PR 描述里逐条登记。
 */
class SourceIndividualMaterialBatchPassthroughTest {

    private static final String FACTORY = "F006";
    private static final String INDIVIDUAL_ID = "si-abc-123";

    private MaterialBatchMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new MaterialBatchMapper();
        // toEntity 会问受控词表要储位; 本测试不设 storageLocation, 但字段不能是 null。
        ReflectionTestUtils.setField(mapper, "locationCatalog", mock(WarehouseLocationCatalog.class));
    }

    @Test
    @DisplayName("创建请求里的 sourceIndividualId 落到实体上")
    void createRequestCarriesSourceIndividualId() {
        MaterialBatch batch = mapper.toEntity(baseCreateRequest(INDIVIDUAL_ID), FACTORY, 1L);

        assertThat(batch.getSourceIndividualId()).isEqualTo(INDIVIDUAL_ID);

        // 阴性对照: 不传就是 null —— ⛔ 不许被兜底成空串或某个"默认个体"。
        assertThat(mapper.toEntity(baseCreateRequest(null), FACTORY, 1L).getSourceIndividualId())
                .isNull();
    }

    @Test
    @DisplayName("实体上的 sourceIndividualId 出现在 DTO 里 (全仓唯一一处投影)")
    void entityValueReachesTheDto() {
        MaterialBatch batch = new MaterialBatch();
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("MB-TEST-1");
        batch.setReceiptQuantity(new BigDecimal("10"));
        batch.setSourceIndividualId(INDIVIDUAL_ID);

        MaterialBatchDTO dto = mapper.toDTO(batch);
        assertThat(dto.getSourceIndividualId()).isEqualTo(INDIVIDUAL_ID);

        // 阴性对照: 实体上没有时 DTO 也是 null。
        batch.setSourceIndividualId(null);
        assertThat(mapper.toDTO(batch).getSourceIndividualId()).isNull();
    }

    @Test
    @DisplayName("更新是 partial: 传了就改, 不传保持原值")
    void updateIsPartialForSourceIndividualId() {
        MaterialBatch batch = new MaterialBatch();
        batch.setFactoryId(FACTORY);
        batch.setSourceIndividualId("si-old");

        // ① 传了 → 改
        UpdateMaterialBatchRequest change = new UpdateMaterialBatchRequest();
        change.setSourceIndividualId("si-new");
        mapper.updateEntity(batch, change);
        assertThat(batch.getSourceIndividualId()).isEqualTo("si-new");

        // ② 没传 → 保持。⚠️ 这一半才是「partial」的实质:
        //    少了它, 一个「每次更新都清空关联」的实现同样能让 ① 绿。
        mapper.updateEntity(batch, new UpdateMaterialBatchRequest());
        assertThat(batch.getSourceIndividualId()).isEqualTo("si-new");
    }

    private static CreateMaterialBatchRequest baseCreateRequest(String sourceIndividualId) {
        CreateMaterialBatchRequest request = new CreateMaterialBatchRequest();
        request.setBatchNumber("MB-TEST-1");
        request.setMaterialTypeId("mt-1");
        request.setReceiptDate(LocalDate.now());
        request.setReceiptQuantity(new BigDecimal("10"));
        request.setQuantityUnit("kg");
        // toEntity 无条件算 totalValue / totalWeight 的单价, 两者都必须有值 ——
        // 这不是本测试关心的东西, 只是为了让被测那条路走得下去。
        request.setTotalWeight(new BigDecimal("10"));
        request.setTotalValue(new BigDecimal("100"));
        request.setSourceIndividualId(sourceIndividualId);
        return request;
    }
}
