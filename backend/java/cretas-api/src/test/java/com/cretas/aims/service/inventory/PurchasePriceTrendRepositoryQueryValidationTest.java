package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 采购均价趋势 (原料×月份加权均价): 真实 JPA Context + 真实 JPQL. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PurchasePriceTrendRepositoryQueryValidationTest {

    private static final String F = "IT-TREND";
    @PersistenceContext private EntityManager em;
    private PurchasePriceTrendService svc;
    private int seq;

    @BeforeEach
    void setUp() {
        svc = new PurchasePriceTrendService();
        ReflectionTestUtils.setField(svc, "em", em);
        // 10 月: 盐 100kg@10 + 300kg@20 → 加权 (1000+6000)/400 = 17.5 (简单平均会是 15)
        order("S1", PurchaseOrderStatus.COMPLETED, LocalDate.of(2026, 10, 5), line("M1", "盐", "kg", "100", "10"));
        order("S2", PurchaseOrderStatus.PARTIAL_RECEIVED, LocalDate.of(2026, 10, 20), line("M1", "盐", "kg", "300", "20"));
        // 11 月
        order("S1", PurchaseOrderStatus.COMPLETED, LocalDate.of(2026, 11, 3), line("M1", "盐", "kg", "50", "12"));
        // 不计入: 草稿 / 已取消 / 别的厂
        order("S1", PurchaseOrderStatus.DRAFT, LocalDate.of(2026, 10, 6), line("M1", "盐", "kg", "1000", "1"));
        order("S1", PurchaseOrderStatus.CANCELLED, LocalDate.of(2026, 10, 7), line("M1", "盐", "kg", "1000", "1"));
        // 缺单价的行: 不参与均价, 单独计数
        order("S1", PurchaseOrderStatus.COMPLETED, LocalDate.of(2026, 10, 8), line("M1", "盐", "kg", "40", null));
        // 另一个单位不混算
        order("S1", PurchaseOrderStatus.COMPLETED, LocalDate.of(2026, 10, 9), line("M1", "盐", "箱", "2", "300"));
        em.flush();
    }

    private PurchaseOrderItem line(String material, String name, String unit, String qty, String price) {
        PurchaseOrderItem i = new PurchaseOrderItem();
        i.setMaterialTypeId(material);
        i.setMaterialName(name);
        i.setUnit(unit);
        i.setPriceUnit(unit);
        i.setQuantity(new BigDecimal(qty));
        i.setUnitPrice(price == null ? null : new BigDecimal(price));
        return i;
    }

    private void order(String supplier, PurchaseOrderStatus st, LocalDate date, PurchaseOrderItem item) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId("PO-" + (++seq));
        o.setFactoryId(F);
        o.setOrderNumber("PON-" + seq);
        o.setSupplierId(supplier);
        o.setOrderDate(date);
        o.setStatus(st);
        em.persist(o);
        item.setPurchaseOrderId(o.getId());
        em.persist(item);
    }

    private PurchasePriceTrendService.Row find(List<PurchasePriceTrendService.Row> rows, String month, String unit) {
        return rows.stream().filter(r -> r.month().equals(month) && r.unit().equals(unit)).findFirst().orElseThrow();
    }

    @Test
    void weightedAverageByMaterialUnitAndMonth() {
        List<PurchasePriceTrendService.Row> rows = svc.trend(F, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), null, null);

        PurchasePriceTrendService.Row oct = find(rows, "2026-10", "kg");
        assertThat(oct.weightedAveragePrice()).isEqualByComparingTo("17.5");
        assertThat(oct.totalQuantity()).isEqualByComparingTo("400");
        assertThat(oct.orderCount()).isEqualTo(3);        // 含缺单价那张
        assertThat(oct.missingPriceLines()).isEqualTo(1);
        assertThat(find(rows, "2026-11", "kg").weightedAveragePrice()).isEqualByComparingTo("12");
        assertThat(find(rows, "2026-10", "箱").weightedAveragePrice()).isEqualByComparingTo("300"); // 不同单位不混算
        assertThat(rows).hasSize(3);
    }

    @Test
    void supplierFilterDateRangeAndFactoryIsolation() {
        assertThat(svc.trend(F, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, "S2"))
                .singleElement().satisfies(r -> assertThat(r.weightedAveragePrice()).isEqualByComparingTo("20"));
        assertThat(svc.trend(F, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "NO-SUCH", null)).isEmpty();
        assertThat(svc.trend("OTHER", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), null, null)).isEmpty();
    }

    @Test
    void badRangesRejected() {
        assertThatThrownBy(() -> svc.trend(F, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1), null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.trend(F, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 1), null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void csvHasHeaderRowsAndBlankForMissingAverageAndNoFormulaInjection() {
        List<PurchasePriceTrendService.Row> rows = List.of(
                new PurchasePriceTrendService.Row("M", "=cmd", "kg", "kg", "2026-10", new BigDecimal("10"), null, 1, 1, 1));
        String csv = new String(svc.exportCsv(rows), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿原料,单位");
        assertThat(csv).contains("\"'=cmd\"");
        assertThat(csv).contains("2026-10,10,,1,1,1");
    }
}
