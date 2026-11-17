package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.dto.bom.MaterialReplacementImpact;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.bom.BomReverseQueryService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 物料替换成本影响 (bom_reverse_query REPLACE_ANALYSIS): 成本差额 / 单价与 web 物料替换分析同一判定。
 * 原先文字里无条件写 {@code totalCostDelta=…}, 没有价格查看权限的账号也能在给大模型的上下文里看到成本差额。
 *
 * <p>走真实入口 {@code execute(ToolCall, context)}, 判定用真实 {@link PriceFieldResponseAdvice}, 权限只来自配置的权限码。
 */
@DisplayName("物料替换成本影响: 成本差额 / 单价按价格查看权限配置给, 看不到就不写、字段为空不为 0")
class BomReverseQueryToolPriceVisibilityContractTest {

    private static final long USER_ID = 1L;
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void priceContextMustNotLeak() {
        com.cretas.aims.security.PriceSensitiveContext.clear();
    }

    private static PriceFieldResponseAdvice priceViewFor(Set<String> held) {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.hasPermission(any(User.class), anyString()))
                .thenAnswer(call -> held.contains(call.<String>getArgument(1)));
        when(permissions.hasConfiguredPermission(any(User.class), anyString(), any()))
                .thenAnswer(call -> held.contains(call.<String>getArgument(1)));
        UserRepository users = mock(UserRepository.class);
        User u = new User();
        u.setId(USER_ID);
        u.setFactoryId("F1");
        u.setRoleCode("whatever_role_name_is_not_the_criterion");
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));
        PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        ReflectionTestUtils.setField(advice, "objectMapper", new ObjectMapper().findAndRegisterModules()
                .registerModule(new PriceSensitiveJacksonConfig().priceSensitiveJacksonModule()));
        return advice;
    }

    /** 旧料 12.50 → 新料 9.75, 一个配方成本 88.80 → 70.10, 差额 -18.70。 */
    private static MaterialReplacementImpact impact() {
        MaterialReplacementImpact.RecipeImpact ri = new MaterialReplacementImpact.RecipeImpact();
        ri.setRecipeId("r1");
        ri.setProductName("卤牛肉");
        ri.setOldRecipeMaterialCost(new BigDecimal("88.80"));
        ri.setNewRecipeMaterialCost(new BigDecimal("70.10"));
        ri.setCostDelta(new BigDecimal("-18.70"));
        MaterialReplacementImpact impact = new MaterialReplacementImpact();
        impact.setOldMaterialId("OLD");
        impact.setNewMaterialId("NEW");
        impact.setOldUnitPrice(new BigDecimal("12.50"));
        impact.setNewUnitPrice(new BigDecimal("9.75"));
        impact.setTotalCostDelta(new BigDecimal("-18.70"));
        impact.setAffectedRecipeCount(1);
        impact.setRecipeBreakdown(new ArrayList<>(List.of(ri)));
        return impact;
    }

    private record Exit(String message, JsonNode data) { }

    private Exit run(Set<String> held) throws Exception {
        BomReverseQueryService reverse = mock(BomReverseQueryService.class);
        when(reverse.analyzeReplacement("F1", "OLD", "NEW")).thenReturn(impact());
        ProcessSecretRecipeGuard guard = mock(ProcessSecretRecipeGuard.class);
        when(guard.permitsFullContent(anyString(), anyString(), anyString())).thenReturn(true);
        BomReverseQueryTool tool = new BomReverseQueryTool();
        ReflectionTestUtils.setField(tool, "reverseQueryService", reverse);
        ReflectionTestUtils.setField(tool, "processSecretGuard", guard);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", priceViewFor(held));
        JsonNode r = mapper.readTree(tool.execute(ToolCall.of("t1", tool.getToolName(),
                        "{\"mode\":\"REPLACE_ANALYSIS\",\"materialId\":\"OLD\",\"newMaterialId\":\"NEW\"}"),
                Map.of("factoryId", "F1", "userId", USER_ID)));
        assertTrue(r.get("success").asBoolean(), r.toString());
        return new Exit(r.get("data").get("message").asText(), r.get("data").get("data"));
    }

    private static final List<String> TOP_PRICE_FIELDS = List.of("oldUnitPrice", "newUnitPrice", "totalCostDelta");
    private static final List<String> ROW_PRICE_FIELDS = List.of("oldRecipeMaterialCost", "newRecipeMaterialCost", "costDelta");

    @Test
    @DisplayName("无价格查看 (只有生产查看): 文字不写成本差额, 单价 / 差额 / 配方成本字段为空 (不是 0)")
    void withoutPriceViewNoCostDeltaInTextOrFields() throws Exception {
        Exit exit = run(Set.of("production:read"));

        assertTrue(exit.message().contains("按当前账号的权限配置不显示"), exit.message());
        assertTrue(exit.message().contains("1 BOM 受影响"), exit.message());
        for (String figure : List.of("18.7", "12.5", "9.75", "88.8", "70.1", "totalCostDelta=")) {
            assertFalse(exit.message().contains(figure), "文字里出现了 " + figure + ": " + exit.message());
        }
        JsonNode row = exit.data().get("recipeBreakdown").get(0);
        for (String f : TOP_PRICE_FIELDS) {
            assertTrue(exit.data().get(f).isNull(), f + " 应为空 (不是 0): " + exit.data());
        }
        for (String f : ROW_PRICE_FIELDS) {
            assertTrue(row.get(f).isNull(), f + " 应为空 (不是 0): " + row);
        }
        assertEquals("r1", row.get("recipeId").asText());
    }

    @Test
    @DisplayName("阳性对照 —— 有价格查看: 文字写成本差额, 字段都在")
    void withPriceViewCostDeltaPresent() throws Exception {
        Exit exit = run(Set.of("production:read", "procurement:price:view"));

        assertTrue(exit.message().contains("totalCostDelta=-18.70"), exit.message());
        assertEquals(0, exit.data().get("totalCostDelta").decimalValue().compareTo(new BigDecimal("-18.70")));
        assertEquals(0, exit.data().get("oldUnitPrice").decimalValue().compareTo(new BigDecimal("12.50")));
        assertEquals(0, exit.data().get("recipeBreakdown").get(0).get("costDelta").decimalValue()
                .compareTo(new BigDecimal("-18.70")));
    }

    @Test
    @DisplayName("口径就是 web 物料替换分析 DTO 上的注解值, 不写死角色")
    void specIsTheWebAnnotation() throws Exception {
        String web = MaterialReplacementImpact.class.getDeclaredField("totalCostDelta")
                .getAnnotation(PriceSensitive.class).permission();
        assertEquals(web, BomReverseQueryTool.COST_DELTA_SPEC);
    }
}
