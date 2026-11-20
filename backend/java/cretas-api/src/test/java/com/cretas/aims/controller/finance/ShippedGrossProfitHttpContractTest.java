package com.cretas.aims.controller.finance;

import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitiveContext;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.finance.BalanceSheetService;
import com.cretas.aims.service.finance.CashFlowService;
import com.cretas.aims.service.finance.IncomeStatementService;
import com.cretas.aims.service.finance.SalesReturnCostService;
import com.cretas.aims.service.finance.ShipmentSkuMarginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * PROTO-GP-SHIPPED — 经营分析「财务」区块的系统毛利, 从真实 HTTP 入口打:
 * {@code GET /api/mobile/{factoryId}/finance/report/shipped-gross-profit}
 * → FinanceReportController → ShipmentSkuMarginService#summarize → 真实收入行 SQL (H2) + 真实价格脱敏 advice。
 *
 * <p>GROSS 按已确认发货的实发金额汇总。
 * 没有发货确认数据时金额为 null 并说明「缺少发货确认数据」。每个用例都在库里放一张金额大得多的
 * 销售订单 (含未发货) 和一家外厂的已发货发运单, 两者都不得进入收入 (订单口径 / 跨厂阴性对照)。
 */
class ShippedGrossProfitHttpContractTest {
    static final String F = "TEST-FACTORY";
    static final String OTHER = "OTHER-FACTORY";
    static final LocalDate START = LocalDate.of(2026, 11, 1), END = LocalDate.of(2026, 11, 18);
    static final String URL = "/api/mobile/" + F + "/finance/report/shipped-gross-profit";

    EmbeddedDatabase db;
    JdbcTemplate jdbc;
    SalesDeliveryItemRepository repo;
    ShipmentSkuMarginService service;

    @BeforeEach
    void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("CREATE TABLE sales_delivery_records(id varchar PRIMARY KEY,factory_id varchar,delivery_date date,record_role varchar,status varchar,deleted_at timestamp)");
        jdbc.execute("CREATE TABLE sales_delivery_items(id bigint PRIMARY KEY,delivery_record_id varchar,product_type_id varchar,product_name varchar,unit varchar,delivered_quantity decimal(20,4),unit_price decimal(20,4),deleted_at timestamp)");
        jdbc.execute("CREATE TABLE product_types(id varchar,factory_id varchar,name varchar,deleted_at timestamp)");
        // 订单口径的诱饵: 本厂一张 5,000 元的部分发货订单 (发了 720 元的货)。收入若读订单金额就会变成 5000。
        jdbc.execute("CREATE TABLE sales_orders(id varchar PRIMARY KEY,factory_id varchar,status varchar,total_amount decimal(20,2),deleted_at timestamp)");
        jdbc.update("INSERT INTO sales_orders VALUES('so-1',?,'PARTIAL_DELIVERED',5000,NULL)", F);
        // 跨厂阴性对照: 外厂同期间已发货 99,999 元。
        record("foreign", OTHER, "SHIPMENT", "SHIPPED");
        line(900, "foreign", "A", "盒", "1", "99999");
        // 母单不计 (子发运单已计), 草稿不计。
        record("master", F, "MASTER", "SHIPPED");
        line(901, "master", "A", "盒", "100", "15");
        record("draft", F, "SHIPMENT", "DRAFT");
        line(902, "draft", "A", "盒", "100", "15");

        repo = mock(SalesDeliveryItemRepository.class);
        EntityManager em = mock(EntityManager.class);
        Query lock = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(lock);
        service = new ShipmentSkuMarginService(repo, jdbc, mock(IncomeStatementService.class), em,
                mock(SalesReturnCostService.class), mock(FactorySettingsRepository.class),
                mock(com.cretas.aims.service.finance.ShipmentNetRevenueService.class));
    }

    @AfterEach
    void close() {
        PriceSensitiveContext.clear();
        db.shutdown();
    }

    /** financeReadConfigured = 工厂权限配置里该用户有财务读 (PriceVisibility 只看配置)。 */
    MockMvc mvc(boolean financeReadConfigured) {
        PriceSensitiveContext.clear();
        PermissionService permissions = mock(PermissionService.class);
        UserRepository users = mock(UserRepository.class);
        User actor = new User();
        actor.setId(9L);
        when(users.findById(9L)).thenReturn(Optional.of(actor));
        when(permissions.hasConfiguredPermission(eq(actor), eq("finance:read"), anySet())).thenReturn(financeReadConfigured);
        // 固定名单的 procurement:price:view 给 true: 证明金额是否可见只取决于财务读配置, 不是固定角色名单。
        when(permissions.hasPermission(actor, PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)).thenReturn(true);
        PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule());
        FinanceReportController controller = new FinanceReportController(mock(BalanceSheetService.class),
                mock(IncomeStatementService.class), mock(CashFlowService.class), service);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    ResultActions call(boolean financeReadConfigured) throws Exception {
        return mvc(financeReadConfigured).perform(get(URL).requestAttr("userId", 9L)
                        .param("periodStart", START.toString()).param("periodEnd", END.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void shippedRevenueMinusActualShippedBatchCost() throws Exception {
        record("s1", F, "SHIPMENT", "SHIPPED");
        line(1, "s1", "A", "公斤", "9", "30");      // 270
        record("s2", F, "LEGACY", "DELIVERED");
        line(2, "s2", "B", "盒", "30", "15");       // 450
        movements(m(1, "A", "a", "9", "15.2913"), m(2, "B", "b", "14", "6.4165"), m(2, "B", "c", "16", "7.4165"));
        call(true)
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.basis").value("GROSS"))
                .andExpect(jsonPath("$.data.shipmentLineCount").value(2))
                .andExpect(jsonPath("$.data.revenue").value(720.00))     // 不是订单 5000, 不含外厂 99999 / 母单 / 草稿
                .andExpect(jsonPath("$.data.cost").value(346.12))
                .andExpect(jsonPath("$.data.grossProfit").value(373.88))
                .andExpect(jsonPath("$.data.marginRate").value(51.93))
                .andExpect(jsonPath("$.data.unavailableReason").value(nullValue()))
                .andExpect(jsonPath("$.data.revenueBasis", containsString("已确认发货")))
                .andExpect(jsonPath("$.data.revenueBasis", containsString("不含未发货的订单金额")));
    }

    @Test
    void noShipmentConfirmationShowsDashWithReasonAndNeverFallsBackToOrderAmount() throws Exception {
        call(true)
                .andExpect(jsonPath("$.data.status").value("NO_SHIPMENT"))
                .andExpect(jsonPath("$.data.shipmentLineCount").value(0))
                .andExpect(jsonPath("$.data.revenue").value(nullValue()))
                .andExpect(jsonPath("$.data.cost").value(nullValue()))
                .andExpect(jsonPath("$.data.grossProfit").value(nullValue()))
                .andExpect(jsonPath("$.data.marginRate").value(nullValue()))
                .andExpect(jsonPath("$.data.unavailableReason", containsString("缺少发货确认数据")));
        // 没读成本来源: 没有收入就不去凑成本
        verifyNoInteractions(repo);
    }

    @Test
    void foreignFactoryShipmentsNeverCountForThisFactory() throws Exception {
        // 本厂没有任何已发货明细, 外厂有 99,999 ⇒ 本厂仍是「缺少发货确认数据」
        movements();
        call(true).andExpect(jsonPath("$.data.status").value("NO_SHIPMENT"))
                .andExpect(jsonPath("$.data.revenue").value(nullValue()));
        // 本厂有一行: 收入只含本厂这一行
        record("s1", F, "SHIPMENT", "SHIPPED");
        line(1, "s1", "A", "盒", "2", "10");
        movements(m(1, "A", "a", "2", "4"));
        call(true).andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.shipmentLineCount").value(1))
                .andExpect(jsonPath("$.data.revenue").value(20.00))
                .andExpect(jsonPath("$.data.grossProfit").value(12.00));
    }

    @Test
    void costIncompleteKeepsShippedRevenueButLeavesCostAndProfitEmptyWithReason() throws Exception {
        record("s1", F, "SHIPMENT", "SHIPPED");
        line(1, "s1", "A", "盒", "10", "14.31");
        when(repo.findShippedMovementsForLedger(F, START, END)).thenThrow(new BusinessException(409,
                "出货批次扣库记录或单位不完整，本次未结转，请核对实际出货批次和数量"));
        call(true)
                .andExpect(jsonPath("$.data.status").value("COST_INCOMPLETE"))
                .andExpect(jsonPath("$.data.revenue").value(143.10))
                .andExpect(jsonPath("$.data.cost").value(nullValue()))
                .andExpect(jsonPath("$.data.grossProfit").value(nullValue()))
                .andExpect(jsonPath("$.data.marginRate").value(nullValue()))
                .andExpect(jsonPath("$.data.unavailableReason", containsString("出库批次成本不完整")))
                .andExpect(jsonPath("$.data.unavailableReason", containsString("出货批次扣库记录或单位不完整")));
    }

    @Test
    void missingShipmentPriceLeavesRevenueEmptyInsteadOfZero() throws Exception {
        record("s1", F, "SHIPMENT", "SHIPPED");
        line(1, "s1", "A", "盒", "10", null);
        movements(m(1, "A", "a", "10", "4"));
        call(true)
                .andExpect(jsonPath("$.data.status").value("PRICE_MISSING"))
                .andExpect(jsonPath("$.data.revenue").value(nullValue()))
                .andExpect(jsonPath("$.data.unavailableReason", containsString("缺少售价")));
    }

    @Test
    void withoutConfiguredFinanceReadAmountsAreMaskedButStatusAndCountStay() throws Exception {
        record("s1", F, "SHIPMENT", "SHIPPED");
        line(1, "s1", "A", "公斤", "9", "30");
        movements(m(1, "A", "a", "9", "15.2913"));
        call(false)
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.basis").value("GROSS"))
                .andExpect(jsonPath("$.data.shipmentLineCount").value(1))
                .andExpect(jsonPath("$.data.revenue").value(nullValue()))
                .andExpect(jsonPath("$.data.cost").value(nullValue()))
                .andExpect(jsonPath("$.data.grossProfit").value(nullValue()))
                .andExpect(jsonPath("$.data.marginRate").value(nullValue()));
        // 阳性对照: 同一数据、有财务读配置 ⇒ 金额可见 (证明上面是脱敏, 不是算不出)
        call(true).andExpect(jsonPath("$.data.revenue").value(270.00))
                .andExpect(jsonPath("$.data.grossProfit").value(132.38));
    }

    void record(String id, String factory, String role, String status) {
        jdbc.update("INSERT INTO sales_delivery_records VALUES(?,?,?,?,?,NULL)", id, factory,
                java.sql.Date.valueOf(START.plusDays(3)), role, status);
    }

    void line(long id, String record, String sku, String unit, String qty, String price) {
        jdbc.update("INSERT INTO sales_delivery_items VALUES(?,?,?,?,?,?,?,NULL)", id, record, sku, sku, unit,
                new BigDecimal(qty), price == null ? null : new BigDecimal(price));
    }

    Object[] m(long id, String sku, String batch, String qty, String cost) {
        return new Object[]{START, new BigDecimal(qty), cost == null ? null : new BigDecimal(cost), sku, batch, sku,
                "delivery", "unit", id};
    }

    void movements(Object[]... rows) {
        when(repo.findShippedMovementsForLedger(F, START, END)).thenReturn(Arrays.asList(rows));
    }
}
