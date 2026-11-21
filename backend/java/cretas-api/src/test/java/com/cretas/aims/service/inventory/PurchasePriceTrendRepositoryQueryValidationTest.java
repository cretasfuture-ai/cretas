package com.cretas.aims.service.inventory;

import com.cretas.aims.controller.inventory.PurchaseController;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.RoleDefinitionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 采购均价趋势 (原料×月份加权均价): 真实 JPA Context + 真实 JPQL. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PurchasePriceTrendRepositoryQueryValidationTest extends TradeJpaQueryTestSupport {

    private static final String F = "IT-TREND";
    @Autowired private UserRepository users;
    @Autowired private RoleDefinitionRepository roles;
    @PersistenceContext private EntityManager em;
    private PurchasePriceTrendService svc;
    private int seq;
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = TradeJpaFixtures.createFactoryUser(em, F);
        TradeJpaFixtures.createMaterial(em, F, "M1");
        TradeJpaFixtures.createSupplier(em, F, userId, "S1", "S1");
        TradeJpaFixtures.createSupplier(em, F, userId, "S2", "S2");
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
        order(userId, supplier, st, date, item);
    }

    private void order(Long creator, String supplier, PurchaseOrderStatus st, LocalDate date, PurchaseOrderItem item) {
        PurchaseOrder o = new PurchaseOrder();
        o.setId("PO-" + (++seq));
        o.setFactoryId(F);
        o.setOrderNumber("PON-" + seq);
        o.setCreatedBy(creator);
        o.setSupplierId(supplier);
        o.setOrderDate(date);
        o.setStatus(st);
        em.persist(o);
        item.setPurchaseOrderId(o.getId());
        em.persist(item);
    }

    @Test
    void jsonAndCsvExcludeOutOfScopeOrdersBeforeGrouping() throws Exception {
        String role = "procurement_manager";
        em.find(User.class, userId).setRoleCode(role);
        Long other = TradeJpaFixtures.createUser(em, F, "trend-other", role);
        TradeJpaFixtures.createMaterial(em, F, "M2");
        LocalDate date = LocalDate.of(2026, 10, 10);
        order(other, "S2", PurchaseOrderStatus.COMPLETED, date, line("M1", "盐", "kg", "600", "40"));
        order(other, "S2", PurchaseOrderStatus.COMPLETED, date, line("M2", "范围外原料", "kg", "70", "77"));
        em.flush();

        var scope = TradeJpaFixtures.scopedHttp(users, roles, F, role);
        var permissions = mock(PermissionService.class);
        when(permissions.hasConfiguredPermission(em.find(User.class, userId), "procurement:read",
                java.util.Set.of(role))).thenReturn(true);
        var controller = new PurchaseController(null, null, null, permissions, users, null, null);
        ReflectionTestUtils.setField(controller, "businessRowScope", scope.scope());
        ReflectionTestUtils.setField(controller, "priceTrendService", svc);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(scope.interceptor()).setControllerAdvice(new GlobalExceptionHandler()).build();
        String path = "/api/mobile/" + F + "/purchase/price-trend";

        mvc.perform(scope.asUser(get(path).param("from", "2026-10-01").param("to", "2026-10-31"), userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].unit").value("kg"))
                .andExpect(jsonPath("$.data[0].materialTypeId").value("M1"))
                .andExpect(jsonPath("$.data[0].totalQuantity").value(400.0))
                .andExpect(jsonPath("$.data[0].weightedAveragePrice").value(17.5))
                .andExpect(jsonPath("$.data[0].orderCount").value(3))
                .andExpect(jsonPath("$.data[0].lineCount").value(3))
                .andExpect(jsonPath("$.data[1].unit").value("箱"))
                .andExpect(jsonPath("$.data[1].weightedAveragePrice").value(300.0));

        var csv = mvc.perform(scope.asUser(get(path + "/export")
                        .param("from", "2026-10-01").param("to", "2026-10-31"), userId))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getContentAsString(StandardCharsets.UTF_8).lines().skip(1))
                .containsExactlyInAnyOrder(
                        "\"盐\",\"kg\",\"kg\",2026-10,400,17.5000,3,3,1",
                        "\"盐\",\"箱\",\"箱\",2026-10,2,300.0000,1,1,0");
    }

    private PurchasePriceTrendService.Row find(List<PurchasePriceTrendService.Row> rows, String month, String unit) {
        return rows.stream().filter(r -> r.month().equals(month) && r.unit().equals(unit)).findFirst().orElseThrow();
    }

    @Test
    void weightedAverageByMaterialUnitAndMonth() {
        List<PurchasePriceTrendService.Row> rows = svc.trend(F, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), null, null, null);

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
        assertThat(svc.trend(F, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, "S2", null))
                .singleElement().satisfies(r -> assertThat(r.weightedAveragePrice()).isEqualByComparingTo("20"));
        assertThat(svc.trend(F, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "NO-SUCH", null, null)).isEmpty();
        assertThat(svc.trend("OTHER", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 30), null, null, null)).isEmpty();
    }

    @Test
    void weightsDifferentPackSizesByQuantityInThePriceUnit() {
        PurchaseOrderItem smallPack = line("M1", "盐", "box", "2", "10");
        smallPack.setPriceUnit("kg");
        smallPack.setQuantityToPriceFactor(new BigDecimal("5"));
        PurchaseOrderItem largePack = line("M1", "盐", "box", "2", "20");
        largePack.setPriceUnit("kg");
        largePack.setQuantityToPriceFactor(new BigDecimal("15"));
        LocalDate day = LocalDate.of(2026, 12, 1);
        order("S1", PurchaseOrderStatus.COMPLETED, day, smallPack);
        order("S2", PurchaseOrderStatus.COMPLETED, day, largePack);
        em.flush();

        assertThat(svc.trend(F, day, day, "M1", null, null)).singleElement().satisfies(row -> {
            assertThat(row.totalQuantity()).isEqualByComparingTo("4");
            assertThat(row.weightedAveragePrice()).isEqualByComparingTo("17.5");
        });
    }

    @Test
    void badRangesRejected() {
        assertThatThrownBy(() -> svc.trend(F, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1), null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> svc.trend(F, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 1), null, null, null))
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
