package com.cretas.aims.security;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rule 2b (金额词键名, 非财务角色剥掉) 对「名字带金额词、本身不是钱」的豁免只认<b>单位换算系数</b>:
 * PO 明细的 {@code quantityToPriceFactor} 要原样给; 成本率 / 利润率这类比例 (grossProfitRatio、costRatio …)
 * 恰恰是 Rule 2b 要藏的, 一个都不能因为名字里有 ratio / factor 被放过。
 *
 * <p>走真实 {@link PriceFieldResponseAdvice#beforeBodyWrite} 的 Map 分支 (对话工具结果就是 Map), 只把
 * 「这个用户有没有价格 / 财务权限」换成桩。
 */
class PriceFieldNonMoneyKeyContractTest {

    private static final long USER_ID = 42L;
    /** 带金额词 + ratio / factor 的成本率、利润率键: 旧的宽豁免会把它们全放过 (全仓扫描里 117 个之一)。 */
    private static final List<String> MARGIN_AND_COST_RATIOS = List.of(
            "grossProfitRatio", "costRatio", "profitMarginRatio", "foodCostRatio", "laborCostRatio",
            "materialCostRatio", "revenueRatio", "costAllocationRatio", "switchCostFactor", "成本占比");

    private final PermissionService permissions = mock(PermissionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PriceFieldResponseAdvice advice = new PriceFieldResponseAdvice();
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        User u = new User();
        u.setId(USER_ID);
        u.setFactoryId("F006");
        u.setRoleCode("procurement_manager");
        when(users.findById(USER_ID)).thenReturn(Optional.of(u));
        httpRequest.setAttribute("userId", USER_ID);
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void tearDown() {
        PriceSensitiveContext.clear();
    }

    /** 非财务: 无论能不能看价格 (采购主管能看采购价, 仓管不能), 换算系数可见, 成本率 / 利润率为 null。 */
    @ParameterizedTest(name = "canViewPrice={0}")
    @ValueSource(booleans = {true, false})
    void nonFinance_seesTheConversionFactorButNotMarginOrCostRatios(boolean canViewPrice) {
        grant(canViewPrice, false);
        Map<String, Object> item = run();

        assertThat(item.get("quantityToPriceFactor")).as("换算系数").isEqualTo(new BigDecimal("2"));
        assertThat(item.get("quantity_to_price_factor")).as("换算系数列名").isEqualTo(new BigDecimal("2"));
        for (String key : MARGIN_AND_COST_RATIOS) {
            assertThat(item).containsKey(key);
            assertThat(item.get(key)).as("%s 是成本率 / 利润率, 非财务角色必须看不到", key).isNull();
        }
        // 阳性对照: Rule 2b 确实在这个用户身上开火 (真金额被剥), 上面的 null 不是因为别的原因
        assertThat(item.get("totalAmount")).isNull();
        assertThat(item.get("quantity")).isEqualTo(new BigDecimal("12"));
    }

    /** 对照: 有财务 + 价格权限的用户, 全部原样 —— 上面的 null 是这道闸给的。 */
    @Test
    void financeUser_seesEverything() {
        grant(true, true);
        Map<String, Object> item = run();

        assertThat(item.get("quantityToPriceFactor")).isEqualTo(new BigDecimal("2"));
        for (String key : MARGIN_AND_COST_RATIOS) {
            assertThat(item.get(key)).as(key).isEqualTo(new BigDecimal("0.35"));
        }
        assertThat(item.get("totalAmount")).isEqualTo(new BigDecimal("36"));
    }

    private void grant(boolean canViewPrice, boolean canViewFinance) {
        when(permissions.hasPermission(any(User.class), any(String.class))).thenReturn(canViewPrice);
        when(permissions.hasConfiguredPermission(any(User.class), any(String.class), any())).thenReturn(canViewPrice);
        when(permissions.hasPermission(any(User.class), eq(PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)))
                .thenReturn(canViewFinance);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialTypeId", "MT-1");
        item.put("quantity", new BigDecimal("12"));
        item.put("quantityToPriceFactor", new BigDecimal("2"));
        item.put("quantity_to_price_factor", new BigDecimal("2"));
        item.put("totalAmount", new BigDecimal("36"));
        for (String key : MARGIN_AND_COST_RATIOS) {
            item.put(key, new BigDecimal("0.35"));
        }
        // 与对话工具结果同形: resultData.content[].items[]
        Map<String, Object> order = new LinkedHashMap<>(Map.of("id", "PO-1", "items", List.of(item)));
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "resultData", new LinkedHashMap<>(Map.of("content", List.of(order)))));
        Map<String, Object> out = (Map<String, Object>) advice.beforeBodyWrite(body, null,
                MediaType.APPLICATION_JSON, null, new ServletServerHttpRequest(httpRequest), null);
        Map<String, Object> resultData = (Map<String, Object>) out.get("resultData");
        Map<String, Object> firstOrder = ((List<Map<String, Object>>) resultData.get("content")).get(0);
        return ((List<Map<String, Object>>) firstOrder.get("items")).get(0);
    }
}
