package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingService;
import com.cretas.aims.service.finding.FindingTextRenderer;
import com.cretas.aims.service.finding.FindingActionPlanService;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Unit tests for {@link FindingController}. */
@ExtendWith(MockitoExtension.class)
class FindingControllerTest {

    private static final String FACTORY_ID = "MOCK_REST";

    @InjectMocks
    private FindingController controller;

    @Mock
    private FindingService findingService;

    @Mock
    private FindingTextRenderer findingTextRenderer;

    @Mock
    private FindingActionPlanService findingActionPlanService;

    private static Finding wastage() {
        return new Finding("LOW_STOCK", "inventory",
                Finding.Severity.INFO, 70, "变质", "变质",
                Map.of("cost", 266929.48, "share", 37.0, "windowDays", 7));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ApiResponse<Map<String, Object>> r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("UT-FC-01: 返回 findings + text，并把三个桶都暴露出去")
    void returnsFindingsAndAllThreeBuckets() {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(wastage()), List.of("损耗类型集中度"), 1,
                        Map.of("WASTAGE_TYPE_CONCENTRATION", 1), List.of(),
                        List.of(new FindingService.SkippedRule("食材损耗离群", "基线历史不足"))));
        when(findingTextRenderer.renderInline(any())).thenReturn("⚠️ 顺带 1 件事：\n · 变质…");

        Map<String, Object> data = body(controller.getFindings(FACTORY_ID, "inventory"));

        assertEquals(1, ((List<?>) data.get("findings")).size());
        assertTrue(((String) data.get("findingsText")).contains("变质"));
        assertEquals(List.of("损耗类型集中度"), data.get("checkedRules"));
        assertEquals(1, ((List<?>) data.get("skippedRules")).size());
        assertTrue(((List<?>) data.get("failedRules")).isEmpty());
        assertEquals(true, data.get("complete"));
    }

    @Test
    @DisplayName("UT-FC-02: 🔴 无发现时 findings 为空但 checkedRules 非空 —— 前端据此说「均正常」而不是「加载失败」")
    void allClearIsDistinguishableFromFailure() {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of("损耗类型集中度"), 0, Map.of(),
                        List.of(), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("✅ 已检查 损耗类型集中度，均正常。");

        Map<String, Object> data = body(controller.getFindings(FACTORY_ID, "inventory"));

        assertTrue(((List<?>) data.get("findings")).isEmpty());
        assertFalse(((List<?>) data.get("checkedRules")).isEmpty(),
                "checkedRules 为空 + findings 为空 = 前端分不清「都正常」和「什么都没跑」");
    }

    @Test
    @DisplayName("UT-FC-03: domain 缺省为 inventory")
    void domainDefaultsToInventory() {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of(), 0, Map.of(), List.of(), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("");

        assertNotNull(controller.getFindings(FACTORY_ID, null));
    }

    @Test
    @DisplayName("UT-FC-04: 🔴 规则失败时 failedRules 非空且 complete=false，前端不得当成正常")
    void failureIsVisibleToTheClient() {
        when(findingService.detectInline(FACTORY_ID, "inventory")).thenReturn(
                new FindingService.Result(List.of(), List.of(), 0, Map.of(),
                        List.of("损耗类型集中度"), List.of()));
        when(findingTextRenderer.renderInline(any())).thenReturn("");

        Map<String, Object> data = body(controller.getFindings(FACTORY_ID, "inventory"));

        assertEquals(List.of("损耗类型集中度"), data.get("failedRules"));
        assertEquals(false, data.get("complete"));
    }

    @Test
    @DisplayName("UT-FC-05: factory action-plan defaults to inventory")
    void actionPlanDefaultsToInventory() {
        when(findingActionPlanService.generate(FACTORY_ID, "inventory"))
                .thenReturn(Map.of("hasPlan", false, "domain", "inventory"));
        Map<String, Object> data = body(controller.getActionPlan(FACTORY_ID, null));
        assertEquals("inventory", data.get("domain"));
    }

    @Test
    @DisplayName("UT-FC-06: retired restaurant finding domain is rejected")
    void retiredRestaurantDomainIsRejected() {
        assertThrows(BusinessException.class, () -> controller.getFindings(FACTORY_ID, "restaurant"));
    }
}
