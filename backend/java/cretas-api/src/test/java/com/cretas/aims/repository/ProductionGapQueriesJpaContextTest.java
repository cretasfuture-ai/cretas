package com.cretas.aims.repository;

import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产缺口补齐: 领料单列表筛选 (search) 与按物料汇总 (summarizeByMaterial) 的 JPQL 必须在真实 JPA Context
 * (PostgreSQL) 里启动并给对结果。null 的筛选条件走 CAST(... AS string) IS NULL 守卫, 这里既测「全不填」也测每个条件。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ProductionGapQueriesJpaContextTest {

    private static final String F = "F-JPA-GAP";

    @Autowired
    FactoryMaterialRequisitionRepository repository;

    private FactoryMaterialRequisition req(String no, String planNo, String product, Status status, LocalDate required) {
        FactoryMaterialRequisition r = new FactoryMaterialRequisition();
        r.setFactoryId(F);
        r.setRequisitionNo(no);
        r.setProductionPlanId("PLAN-" + no);
        r.setProductionPlanNumber(planNo);
        r.setProductName(product);
        r.setStatus(status);
        r.setRequiredDate(required);
        return r;
    }

    private void item(FactoryMaterialRequisition r, String matId, String name, String unit,
                      String picked, String issued, String returned) {
        FactoryMaterialRequisitionItem i = new FactoryMaterialRequisitionItem();
        i.setRequisition(r);
        i.setMaterialTypeId(matId);
        i.setMaterialName(name);
        i.setUnit(unit);
        i.setRequiredQty(new BigDecimal("100"));
        i.setPickedQty(new BigDecimal(picked));
        i.setIssuedQty(new BigDecimal(issued));
        i.setReturnedQty(new BigDecimal(returned));
        r.getItems().add(i);
    }

    private void seed() {
        FactoryMaterialRequisition a = req("REQ-GAP-1", "PP-GAP-001", "黄油鸡", Status.ISSUED, LocalDate.of(2026, 11, 10));
        item(a, "M-1", "鸡胸", "kg", "50", "48", "3");
        item(a, "M-2", "盐", "kg", "5", "5", "0");
        FactoryMaterialRequisition b = req("REQ-GAP-2", "PP-GAP-002", "牛肉面", Status.CLOSED, LocalDate.of(2026, 11, 15));
        item(b, "M-1", "鸡胸", "kg", "20", "20", "2");
        item(b, "M-1", "鸡胸", "个", "7", "7", "1");   // 同物料不同单位: 分行, 不相加
        FactoryMaterialRequisition c = req("REQ-GAP-3", "PP-GAP-003", "黄油鸡", Status.CANCELLED, LocalDate.of(2026, 11, 12));
        item(c, "M-1", "鸡胸", "kg", "900", "900", "0");   // 已取消不计入汇总
        repository.saveAllAndFlush(List.of(a, b, c));
    }

    @Test
    void searchWithNoFilterReturnsAllOfFactory() {
        seed();
        var page = repository.search(F, null, null, null, null, null, PageRequest.of(0, 50));
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void searchHonoursKeywordPlanDateAndStatus() {
        seed();
        assertThat(repository.search(F, null, "%牛肉%", null, null, null, PageRequest.of(0, 50)).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-2");
        assertThat(repository.search(F, null, null, "%gap-001%", null, null, PageRequest.of(0, 50)).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-1");
        assertThat(repository.search(F, null, null, null, LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 14),
                PageRequest.of(0, 50)).getContent())
                .extracting(FactoryMaterialRequisition::getRequisitionNo).containsExactly("REQ-GAP-3");
        assertThat(repository.search(F, Status.CLOSED, null, null, null, null, PageRequest.of(0, 50))
                .getTotalElements()).isEqualTo(1);
        assertThat(repository.search("F-OTHER", null, null, null, null, null, PageRequest.of(0, 50))
                .getTotalElements()).isZero();
    }

    @Test
    void summarizeByMaterialSplitsUnitsAndSkipsCancelled() {
        seed();
        List<Object[]> rows = repository.summarizeByMaterial(F, null, Status.CANCELLED, null, null, null, null);
        // 鸡胸 kg: 已发 48+20=68, 退回 3+2=5 (已取消那张的 900 不计); 鸡胸 个 单独一行; 盐 一行
        assertThat(rows).hasSize(3);
        Object[] chickenKg = rows.stream()
                .filter(r -> "M-1".equals(r[0]) && "kg".equals(r[2])).findFirst().orElseThrow();
        assertThat((BigDecimal) chickenKg[4]).isEqualByComparingTo("68");
        assertThat((BigDecimal) chickenKg[5]).isEqualByComparingTo("5");
        Object[] chickenPiece = rows.stream()
                .filter(r -> "M-1".equals(r[0]) && "个".equals(r[2])).findFirst().orElseThrow();
        assertThat((BigDecimal) chickenPiece[4]).isEqualByComparingTo("7");
    }
}
