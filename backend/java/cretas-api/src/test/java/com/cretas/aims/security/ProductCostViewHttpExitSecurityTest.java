package com.cretas.aims.security;

import com.cretas.aims.controller.ProductCostViewController;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService.Decision;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService.Outcome;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.costview.ProductCostViewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 真实 HTTP 出口阴性对照 (复审 H2): 请求 /cost-summary/by-product, 经 controller → service → 真实
 * ProcessSecretResponseAdvice (真 Jackson 序列化), 断言最终 JSON。
 * <p>服务层的保密遮蔽在这里被<b>故意关掉</b> (mask 不隐藏任何产品), 所以无权账号拿到 null 金额只能是响应裁剪器的功劳:
 * 这证明新接口的字段名/productTypeId 能被现有保密裁剪认出, 不依赖服务层那一道。</p>
 */
class ProductCostViewHttpExitSecurityTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final String PLAIN = "PLAIN";

    ProcessSecretAccessService access;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        access = mock(ProcessSecretAccessService.class);
        when(access.protectedProductTypeIds(F)).thenReturn(Set.of(SECRET));
        when(access.audit(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        BomRecipeRepository recipes = mock(BomRecipeRepository.class);
        ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
        ProcessSecretResponseAdvice advice = new ProcessSecretResponseAdvice(access, recipes, plans,
                ProcessSecretTestHooks.scrubber(access, recipes, mock(ProductionWorkflowInstanceRepository.class),
                        mock(ProductionPlanRepository.class)), json);

        SalesOrderRepository orders = mock(SalesOrderRepository.class);
        when(orders.findByFactoryIdAndDateRange(anyString(), any(), any())).thenReturn(List.of(so("o1"), so("o2")));
        OrderCostBreakdownService breakdown = mock(OrderCostBreakdownService.class);
        when(breakdown.compute(anyString(), eq("o1"), anyBoolean())).thenReturn(cb("o1", SECRET));
        when(breakdown.compute(anyString(), eq("o2"), anyBoolean())).thenReturn(cb("o2", PLAIN));
        ProcessSecretCostMask noHide = mock(ProcessSecretCostMask.class);
        when(noHide.hiddenFor(anyString(), any(java.util.Collection.class), anyString())).thenReturn(Set.of());
        PriceMaskResolver price = mock(PriceMaskResolver.class);
        when(price.shouldMaskPrice(any())).thenReturn(false);

        ProductCostViewController controller = new ProductCostViewController(
                new ProductCostViewService(orders, breakdown, noHide), price);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    static SalesOrder so(String id) {
        SalesOrder s = new SalesOrder();
        s.setId(id);
        return s;
    }

    static OrderCostBreakdownDTO cb(String id, String product) {
        return OrderCostBreakdownDTO.builder().hasData(true).orderId(id).orderNumber(id).productTypeId(product)
                .productName("产品").outputUnit("kg").outputQuantity(new BigDecimal("100"))
                .rawMaterialCost(new BigDecimal("50")).laborCost(new BigDecimal("50"))
                .missingCostItems(new ArrayList<>())
                .laborDetails(List.of(OrderCostBreakdownDTO.LaborDetail.builder().processOrder(1).processName("切")
                        .laborMinutes(60).amount(new BigDecimal("50")).build()))
                .customerSuppliedItemCount(0).build();
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    JsonNode call(AuthenticatedActor actor, Outcome outcome) throws Exception {
        when(access.decide(eq(actor), eq(F), eq(SECRET), eq(ProcessSecretAction.VIEW_FULL)))
                .thenReturn(new Decision(outcome, ProcessSecretStorageMode.CLOUD_RESTRICTED, "x"));
        String body = mvc.perform(get("/api/mobile/F-A/production/cost-summary/by-product")
                        .param("startDate", "2026-11-01").param("endDate", "2026-11-18")
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, actor))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("data");
    }

    static JsonNode group(JsonNode data, String product) {
        for (JsonNode g : data.get("groups")) {
            if (product.equals(g.get("productTypeId").asText())) return g;
        }
        throw new AssertionError("group not found: " + product + " in " + data);
    }

    @Test
    void unauthorisedCallerGetsNullAmountsForProtectedProduct_andKeepsPlainProduct() throws Exception {
        JsonNode data = call(user(5), Outcome.DENY);
        JsonNode secret = group(data, SECRET);
        assertEquals(SECRET, secret.get("productTypeId").asText());
        assertTrue(secret.get("weightedUnitCost").isNull(), secret.toString());
        assertTrue(secret.get("sumMaterialCost").isNull(), secret.toString());
        assertTrue(secret.get("sumLaborCost").isNull(), secret.toString());
        JsonNode line = secret.get("orders").get(0);
        assertTrue(line.get("materialCost").isNull(), line.toString());
        assertTrue(line.get("unitCost").isNull(), line.toString());
        // 同一响应里未保密产品不受影响 (阴性对照不是把整个接口都遮了)
        JsonNode plain = group(data, PLAIN);
        assertNotNull(plain.get("weightedUnitCost"));
        assertEquals(0, new BigDecimal("1.0000").compareTo(plain.get("weightedUnitCost").decimalValue()));
    }

    @Test
    void authorisedCallerSeesTheAmounts_positiveControl() throws Exception {
        JsonNode secret = group(call(user(10), Outcome.ALLOW), SECRET);
        assertEquals(0, new BigDecimal("1.0000").compareTo(secret.get("weightedUnitCost").decimalValue()),
                secret.toString());
    }
}
