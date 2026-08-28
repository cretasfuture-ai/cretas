package com.cretas.aims.mapper;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 2026-08-28 六膳门生产实测的回归闸。
 *
 * <p>手动新建调拨单里，页面「现有库存 100,000 kg」而提交报「可用 0 kg」——
 * 因为「现有库存」走 {@code /inventory/by-warehouse}（{@code findByFactoryIdAndWarehouseId}，
 * 无任何过滤），而闸走 {@code findAvailableBatchesFEFOByWarehouse}，SQL 里排除了
 * {@code ownership <> COMPANY_OWNED} 与 {@code sourceDocType = 'PRODUCTION_BATCH'}。
 *
 * <p>前端按同一口径过滤的**前提**是这两个字段真的被透出来。本闸守的就是这件事 ——
 * 它们一旦从 DTO/mapper 掉了，前端的过滤会静默变成恒真（每条都「可调拨」），
 * 而症状会回到「看着有货、选了必被拒」。⚠️ 这正是本仓记过的
 * 「产出端有 ≠ 消费端收得到」那一类：不报错，只是过滤器空转。
 */
class MaterialBatchMapperTransferabilityFieldsTest {

    private MaterialBatch batch(InventoryOwnership ownership, String sourceDocType) {
        MaterialBatch b = new MaterialBatch();
        b.setId("B1");
        b.setFactoryId("F006");
        b.setBatchNumber("CMA-20260828-TEST");
        b.setMaterialTypeId("RMT_TEST");
        b.setReceiptQuantity(new BigDecimal("100000"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setOwnership(ownership);
        b.setOwnerCustomerId("CUST-1");
        b.setSourceDocType(sourceDocType);
        return b;
    }

    @Test
    @DisplayName("客供料批次的 ownership / ownerCustomerId 必须透出到 DTO")
    void exposesOwnership() {
        MaterialBatchDTO dto = MaterialBatchMapperTestFixtures
                .mapperWithRealLocationCatalog()
                .toDTO(batch(InventoryOwnership.CUSTOMER_OWNED, "CUSTOMER_MATERIAL_ARRIVAL"));

        assertThat(dto.getOwnership())
                .as("客供料的归属丢了, 前端就分不出哪些不可调拨")
                .isEqualTo(InventoryOwnership.CUSTOMER_OWNED);
        assertThat(dto.getOwnerCustomerId()).isEqualTo("CUST-1");
    }

    @Test
    @DisplayName("在制半成品批次的 sourceDocType 必须透出到 DTO")
    void exposesSourceDocType() {
        MaterialBatchDTO dto = MaterialBatchMapperTestFixtures
                .mapperWithRealLocationCatalog()
                .toDTO(batch(InventoryOwnership.COMPANY_OWNED, "PRODUCTION_BATCH"));

        assertThat(dto.getSourceDocType())
                .as("sourceDocType 丢了, 生产仓那 16 条在制半成品会重新漏进调拨下拉")
                .isEqualTo("PRODUCTION_BATCH");
    }

    @Test
    @DisplayName("阳性对照: 普通自有批次两个字段都如实透出, 不会被误标成不可调拨")
    void companyOwnedStaysTransferable() {
        MaterialBatchDTO dto = MaterialBatchMapperTestFixtures
                .mapperWithRealLocationCatalog()
                .toDTO(batch(InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE"));

        assertThat(dto.getOwnership()).isEqualTo(InventoryOwnership.COMPANY_OWNED);
        assertThat(dto.getSourceDocType()).isEqualTo("PURCHASE_RECEIVE");
    }

    @Test
    @DisplayName("物料三大类必须随库存批次透给调拨消费端")
    void exposesMaterialMainCategory() {
        MaterialBatch entity = batch(InventoryOwnership.COMPANY_OWNED, "PURCHASE_RECEIVE");
        RawMaterialType materialType = new RawMaterialType();
        materialType.setName("小苏打");
        materialType.setCode("003-TEST");
        materialType.setCategory("添加剂");
        materialType.setMainCategory("辅料");
        materialType.setUnit("kg");
        entity.setMaterialType(materialType);

        MaterialBatchDTO dto = MaterialBatchMapperTestFixtures.mapperWithRealLocationCatalog().toDTO(entity);

        assertThat(dto.getMaterialCategory()).isEqualTo("添加剂");
        assertThat(dto.getMaterialMainCategory()).isEqualTo("辅料");
    }
}
