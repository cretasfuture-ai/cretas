package com.cretas.aims.controller;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷 44 — {@link PrintController#printMaterialRequisition} 单张领料单打印 payload builder 单测.
 *
 * <p>此前的实现从未接 {@link FactoryMaterialRequisitionService}: 每个字段都是
 * overrides 兜底常量, {@code items} 恒为空列表, 打出的 PDF 单号是 UUID、产品名是
 * "(产品)"、0 行明细。本测试直接测 private builder (与 {@link PrintControllerSp12T8Test}
 * 同一手法, via reflection, 不启动 Spring 容器), 断言真实数据被读进 payload.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("缺陷 44 — PrintController.buildMaterialRequisitionPayload")
class PrintControllerMaterialRequisitionTest {

    private PrintController controller;
    private FactoryMaterialRequisitionService factoryMaterialRequisitionService;
    private ProductionPlanService productionPlanService;

    @BeforeEach
    void setUp() {
        factoryMaterialRequisitionService = mock(FactoryMaterialRequisitionService.class);
        productionPlanService = mock(ProductionPlanService.class);

        controller = new PrintController(
                mock(RestTemplate.class),
                "http://localhost:8083",
                mock(PriceMaskResolver.class));
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService",
                factoryMaterialRequisitionService);
        ReflectionTestUtils.setField(controller, "productionPlanService", productionPlanService);
    }

    private FactoryMaterialRequisitionItem item(
            String materialName, BigDecimal requiredQty, BigDecimal pickedQty, String unit,
            List<Map<String, Object>> batchNumbers) {
        FactoryMaterialRequisitionItem it = new FactoryMaterialRequisitionItem();
        it.setMaterialTypeId("mat-" + materialName);
        it.setMaterialName(materialName);
        it.setRequiredQty(requiredQty);
        it.setPickedQty(pickedQty);
        it.setUnit(unit);
        it.setBatchNumbers(batchNumbers);
        return it;
    }

    // ==================== MR44-1: happy path — 真实数据填满 payload ====================

    @Test
    @DisplayName("MR44-1: 真实需求单号 + 产品名 + 明细行进 payload (不再是 UUID/(产品)/0 行)")
    void buildMaterialRequisitionPayload_realRequisition_populatesRealData() throws Exception {
        FactoryMaterialRequisitionItem rice = item(
                "大米", new BigDecimal("50.000"), new BigDecimal("0.000"), "kg",
                List.of(Map.of("batchNo", "B20260901-01", "qty", 50)));
        FactoryMaterialRequisitionItem oil = item(
                "食用油", new BigDecimal("10.000"), null, "L", null);

        FactoryMaterialRequisition req = mock(FactoryMaterialRequisition.class);
        when(req.getRequisitionNo()).thenReturn("MR20260902-0004");
        when(req.getProductName()).thenReturn("卤味拼盘");
        when(req.getProductionPlanId()).thenReturn("plan-liushanmen-001");
        when(req.getRequiredDate()).thenReturn(LocalDate.of(2026, 9, 2));
        when(req.getRequestedBy()).thenReturn(3007L);
        when(req.getItems()).thenReturn(List.of(rice, oil));
        when(factoryMaterialRequisitionService.getById("F006", "req-mr-0004")).thenReturn(req);

        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlannedQuantity(new BigDecimal("120"));
        plan.setProductUnit("kg");
        when(productionPlanService.getProductionPlanById("F006", "plan-liushanmen-001")).thenReturn(plan);

        Map<String, Object> payload = invoke("F006", "req-mr-0004", null);

        // 单号是 MR 号, 不是 UUID id 参数
        assertThat(payload.get("requisitionNumber")).isEqualTo("MR20260902-0004");
        assertThat(payload.get("requisitionNumber")).isNotEqualTo("req-mr-0004");
        assertThat(payload.get("productName")).isEqualTo("卤味拼盘");
        assertThat(payload.get("plannedQuantity")).isEqualTo("120");
        assertThat(payload.get("unit")).isEqualTo("kg");
        assertThat(payload.get("requestDate")).isEqualTo("2026-09-02");
        assertThat(payload.get("requester")).isEqualTo("3007");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        assertThat(items).hasSize(2);

        Map<String, Object> riceRow = items.stream()
                .filter(r -> "大米".equals(r.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("大米 row not found"));
        assertThat(riceRow.get("plannedQty")).isEqualTo("50");
        // #469: 显式 0 打印字面 "0", 不是空/不是 null
        assertThat(riceRow.get("actualQty")).isEqualTo("0");
        assertThat(riceRow.get("unit")).isEqualTo("kg");
        assertThat(riceRow.get("batch")).isEqualTo("B20260901-01");

        Map<String, Object> oilRow = items.stream()
                .filter(r -> "食用油".equals(r.get("name")))
                .findFirst().orElseThrow(() -> new AssertionError("食用油 row not found"));
        assertThat(oilRow.get("plannedQty")).isEqualTo("10");
        // #469: 未拣(null) 与显式 0 不能塌缩成同一个 —— null 保持 null
        assertThat(oilRow.get("actualQty")).isNull();
    }

    // ==================== MR44-2: 阴性对照 — 两张不同单据互不相同 ====================

    @Test
    @DisplayName("MR44-2 阴性对照: 两个不同 id 打出两个不同单号 + 不同物料行 (排除「每次都是同一模板」)")
    void buildMaterialRequisitionPayload_twoDifferentRequisitions_differ() throws Exception {
        FactoryMaterialRequisition reqA = mock(FactoryMaterialRequisition.class);
        when(reqA.getRequisitionNo()).thenReturn("MR20260902-0004");
        when(reqA.getProductName()).thenReturn("卤味拼盘");
        when(reqA.getItems()).thenReturn(List.of(
                item("大米", new BigDecimal("50"), null, "kg", null)));
        when(factoryMaterialRequisitionService.getById("F006", "req-A")).thenReturn(reqA);

        FactoryMaterialRequisition reqB = mock(FactoryMaterialRequisition.class);
        when(reqB.getRequisitionNo()).thenReturn("MR20260902-0009");
        when(reqB.getProductName()).thenReturn("白切鸡");
        when(reqB.getItems()).thenReturn(List.of(
                item("生姜", new BigDecimal("3"), null, "kg", null)));
        when(factoryMaterialRequisitionService.getById("F006", "req-B")).thenReturn(reqB);

        Map<String, Object> payloadA = invoke("F006", "req-A", null);
        Map<String, Object> payloadB = invoke("F006", "req-B", null);

        assertThat(payloadA.get("requisitionNumber")).isNotEqualTo(payloadB.get("requisitionNumber"));
        assertThat(payloadA.get("requisitionNumber")).isEqualTo("MR20260902-0004");
        assertThat(payloadB.get("requisitionNumber")).isEqualTo("MR20260902-0009");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemsA = (List<Map<String, Object>>) payloadA.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemsB = (List<Map<String, Object>>) payloadB.get("items");
        assertThat(itemsA).extracting(r -> r.get("name")).containsExactly("大米");
        assertThat(itemsB).extracting(r -> r.get("name")).containsExactly("生姜");
    }

    // ==================== MR44-3/4: 服务不可用 / 单据不存在 — 报错, 不伪造 ====================

    @Test
    @DisplayName("MR44-3: factoryMaterialRequisitionService 为 null 时抛 503, 不返回假数据")
    void buildMaterialRequisitionPayload_noService_throwsBusinessException() {
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService", null);

        assertThatThrownBy(() -> invoke("F006", "req-any", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("领料单服务不可用 — 无法生成真实领料单");
    }

    @Test
    @DisplayName("MR44-4: 需求单不存在/跨租户时透传 404, 不 fallback 到 stub")
    void buildMaterialRequisitionPayload_notFound_throwsBusinessException() {
        when(factoryMaterialRequisitionService.getById("F006", "ghost-id"))
                .thenThrow(new BusinessException(404, "领料单不存在: ghost-id"));

        assertThatThrownBy(() -> invoke("F006", "ghost-id", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("领料单不存在: ghost-id");
    }

    // ==================== reflection helper ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(String factoryId, String id, Map<String, String> overrides)
            throws Exception {
        Method m = PrintController.class.getDeclaredMethod(
                "buildMaterialRequisitionPayload", String.class, String.class, Map.class);
        m.setAccessible(true);
        try {
            return (Map<String, Object>) m.invoke(controller, factoryId, id, overrides);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
