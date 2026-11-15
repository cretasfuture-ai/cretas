package com.cretas.aims.repository;

import com.cretas.aims.dto.material.MaterialStockSummaryDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原型规则「缺单价就整组 totalValue = null, 不能算成偏低的数」(fusion-inventory-views)。
 * 之前 SUM 会跳过 NULL 单价, 部分批次无价时 totalValue 只是有价批次之和, 看起来是个正常数字。
 * 真实 Hibernate 查询 (H2 PG 兼容), 不是 Mockito。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@DisplayName("库存汇总: 缺单价整组 totalValue=null")
class MaterialStockSummaryMissingPriceContractTest {

    private static final String F = "F-SSMP-1";

    @Autowired private MaterialBatchRepository batches;
    @Autowired private RawMaterialTypeRepository types;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("全有价求和; 任一批次无价则 totalValue 为 null; 别的厂不串")
    void partialPriceGivesNullValue() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        material("MT-SSMP-ALL", "A");
        material("MT-SSMP-PART", "B");
        material("MT-SSMP-NONE", "C");
        batches.saveAndFlush(batch("b1", "MT-SSMP-ALL", "10", "2.5"));
        batches.saveAndFlush(batch("b2", "MT-SSMP-ALL", "4", "5"));
        batches.saveAndFlush(batch("b3", "MT-SSMP-PART", "10", "2"));
        batches.saveAndFlush(batch("b4", "MT-SSMP-PART", "10", null));
        batches.saveAndFlush(batch("b5", "MT-SSMP-NONE", "3", null));

        List<MaterialStockSummaryDTO> rows = batches.findStockSummaryByFactory(F);

        MaterialStockSummaryDTO all = row(rows, "MT-SSMP-ALL");
        assertThat(all.getTotalQuantity()).isEqualByComparingTo("14");
        assertThat(all.getTotalValue()).isEqualByComparingTo("45"); // 10*2.5 + 4*5
        MaterialStockSummaryDTO part = row(rows, "MT-SSMP-PART");
        assertThat(part.getTotalQuantity()).isEqualByComparingTo("20");
        assertThat(part.getTotalValue()).as("部分批次无价, 不得给出偏低的 20").isNull();
        assertThat(row(rows, "MT-SSMP-NONE").getTotalValue()).isNull();
    }

    private static MaterialStockSummaryDTO row(List<MaterialStockSummaryDTO> rows, String id) {
        return rows.stream().filter(r -> id.equals(r.getMaterialTypeId())).findFirst().orElseThrow();
    }

    private void material(String id, String name) {
        RawMaterialType t = new RawMaterialType();
        t.setId(id);
        t.setFactoryId(F);
        t.setCode(id);
        t.setName(name);
        t.setUnit("kg");
        t.setIsByproduct(false);
        t.setSelfSupplied(false);
        types.saveAndFlush(t);
    }

    private static MaterialBatch batch(String id, String materialId, String qty, String price) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(F);
        b.setBatchNumber("BN-" + id);
        b.setMaterialTypeId(materialId);
        b.setWarehouseId("WH-SSMP");
        b.setReceiptDate(LocalDate.now());
        b.setReceiptQuantity(new BigDecimal(qty));
        b.setQuantityUnit("kg");
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        b.setCreatedBy(1L);
        if (price != null) {
            b.setUnitPrice(new BigDecimal(price));
        }
        return b;
    }
}
